package com.modulex.translator.conversation.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.modulex.translator.core.transport.FrameChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID

/**
 * Bluetooth Low Energy transport.
 *
 * Discovery: the host advertises the ModuleX service UUID with 8 bytes of service data derived
 * from the session id (not the id itself) plus the L2CAP channel number. Data: an LE
 * connection-oriented L2CAP channel (no system pairing dialog), carrying the same TLS 1.3 stream
 * as the network transports, so security does not depend on Bluetooth link encryption.
 *
 * Framing on top of L2CAP is the protocol's 4-byte length prefix; maximum frame 64 KiB.
 * Typical range is ~10 m; throughput is ample for text but not intended for audio.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_ADVERTISE / SCAN / CONNECT are requested by the UI
class BluetoothTransport(private val context: Context, private val tls: PeerTls) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    val available: Boolean get() = adapter != null && context.packageManager.hasSystemFeature("android.hardware.bluetooth_le")
    val enabled: Boolean get() = adapter?.isEnabled == true

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6d2f4a1e-58c3-4d8e-9b7a-3c1e0f2a9d51")

        /** Short tag identifying a session in advertisements without revealing its id. */
        fun sessionTag(sessionId: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(("modulex-ble-tag|$sessionId").toByteArray()).copyOf(8)
    }

    inner class Host(sessionId: String) : Closeable {
        private val server: BluetoothServerSocket = adapter!!.listenUsingInsecureL2capChannel()
        val psm: Int = server.psm
        private val started = CompletableDeferred<Unit>()
        private val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { started.complete(Unit) }
            override fun onStartFailure(errorCode: Int) { started.completeExceptionally(IllegalStateException("advertising failed $errorCode")) }
        }

        init {
            // Legacy advertisements carry 31 bytes: flags (3) + service data with a 128-bit UUID
            // and 10 bytes of payload (28) fits exactly, so no separate UUID list is included.
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid(SERVICE_UUID), sessionTag(sessionId) + byteArrayOf((psm shr 8).toByte(), psm.toByte()))
                .build()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()
            adapter!!.bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
                ?: error("Bluetooth advertising unavailable")
        }

        /** Throws if the phone refused to advertise (e.g. Bluetooth off, advertising unsupported). */
        suspend fun awaitAdvertising() = withTimeout(5_000) { started.await() }

        suspend fun accept(): FrameChannel = withContext(Dispatchers.IO) {
            var channel: FrameChannel? = null
            while (channel == null) {
                val socket = server.accept()
                channel = try {
                    withTimeout(20_000) { tls.server(socket.inputStream, socket.outputStream, "Bluetooth") { runCatching { socket.close() } } }
                } catch (e: Exception) {
                    runCatching { socket.close() }
                    null
                }
            }
            channel
        }

        override fun close() {
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            runCatching { server.close() }
        }
    }

    /** Scans for the host advertising [sessionId]'s tag. */
    suspend fun findHost(sessionId: String, timeoutMs: Long = 20_000): Pair<BluetoothDevice, Int> {
        val scanner = adapter?.bluetoothLeScanner ?: error("Bluetooth scanning unavailable")
        val tag = sessionTag(sessionId)
        val found = CompletableDeferred<Pair<BluetoothDevice, Int>>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val sd = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
                if (sd.size < 10 || !sd.copyOf(8).contentEquals(tag)) return
                val psm = ((sd[8].toInt() and 0xff) shl 8) or (sd[9].toInt() and 0xff)
                found.complete(result.device to psm)
            }
            override fun onScanFailed(errorCode: Int) { found.completeExceptionally(IllegalStateException("scan failed $errorCode")) }
        }
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(SERVICE_UUID), tag + byteArrayOf(0, 0), ByteArray(8) { 0xff.toByte() } + byteArrayOf(0, 0))
            .build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        return try {
            withTimeout(timeoutMs) { found.await() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    suspend fun connect(sessionId: String, pinnedFp: String): FrameChannel {
        val (device, psm) = findHost(sessionId)
        return withContext(Dispatchers.IO) {
            val socket = device.createInsecureL2capChannel(psm)
            try {
                socket.connect()
                withTimeout(20_000) { tls.client(socket.inputStream, socket.outputStream, pinnedFp, "Bluetooth") { runCatching { socket.close() } } }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }
    }
}
