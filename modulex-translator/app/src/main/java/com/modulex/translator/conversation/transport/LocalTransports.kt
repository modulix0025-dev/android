package com.modulex.translator.conversation.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.modulex.translator.core.transport.FrameChannel
import com.modulex.translator.core.transport.PeerTrust
import com.modulex.translator.core.transport.TlsFrameChannel
import com.modulex.translator.core.transport.TlsStream
import com.modulex.translator.security.DeviceIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Wraps an established byte stream (TCP or Bluetooth) in TLS 1.3 using this phone's identity. */
class PeerTls(private val identity: DeviceIdentity) {

    /** Host side: requires a client certificate, which session proofs then bind to. */
    suspend fun server(input: InputStream, output: OutputStream, label: String, onClose: () -> Unit): FrameChannel =
        withContext(Dispatchers.IO) {
            val ctx = PeerTrust.context(identity.keyManagers(), PeerTrust.anyClientRecorded())
            val tls = TlsStream(PeerTrust.serverEngine(ctx), input, output)
            tls.handshake()
            TlsFrameChannel(tls, label, onClose)
        }

    /** Guest side: only the host certificate pinned by the QR code is accepted. */
    suspend fun client(input: InputStream, output: OutputStream, pinnedHostFingerprint: String, label: String, onClose: () -> Unit): FrameChannel =
        withContext(Dispatchers.IO) {
            val ctx = PeerTrust.context(identity.keyManagers(), PeerTrust.pinnedServer(pinnedHostFingerprint))
            val tls = TlsStream(PeerTrust.clientEngine(ctx), input, output)
            tls.handshake()
            TlsFrameChannel(tls, label, onClose)
        }
}

/** Listens for the guest on a local network (shared Wi-Fi, hotspot or Wi-Fi Direct group). */
class TcpHost(private val tls: PeerTls, private val label: String, bindAddress: InetAddress? = null) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, 0), 2)
    }
    val port: Int get() = server.localPort

    /** Waits for the next guest connection and completes the TLS handshake. */
    suspend fun accept(): FrameChannel = withContext(Dispatchers.IO) {
        var channel: FrameChannel? = null
        while (channel == null) {
            val s = server.accept()
            s.tcpNoDelay = true
            channel = try {
                withTimeout(15_000) { tls.server(s.getInputStream(), s.getOutputStream(), label) { runCatching { s.close() } } }
            } catch (e: Exception) {
                // Failed or unauthenticated handshakes are dropped; keep listening.
                runCatching { s.close() }
                if (server.isClosed) throw e
                null
            }
        }
        channel
    }

    override fun close() { runCatching { server.close() } }
}

object TcpGuest {
    suspend fun connect(tls: PeerTls, address: String, port: Int, pinnedFp: String, label: String): FrameChannel =
        withContext(Dispatchers.IO) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(InetAddress.getByName(address), port), 8_000)
                s.tcpNoDelay = true
                withTimeout(15_000) { tls.client(s.getInputStream(), s.getOutputStream(), pinnedFp, label) { runCatching { s.close() } } }
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }
        }
}

object LocalNetwork {
    /**
     * IPv4 address the guest can reach: Wi-Fi client, hotspot or Wi-Fi Direct interface.
     * Only private (site-local) addresses are used; nothing is published beyond the QR code.
     */
    fun localIpv4(preferP2p: Boolean = false): Inet4Address? {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nif.name to it } }
        fun rank(name: String) = when {
            preferP2p && name.startsWith("p2p") -> 0
            name.startsWith("wlan") -> 1
            name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") -> 2
            name.startsWith("p2p") -> 3
            else -> 4
        }
        return candidates.minByOrNull { rank(it.first) }?.second
    }
}

/**
 * Wi-Fi Direct group management. The host creates an autonomous group with a random passphrase
 * (shared only via the QR code); the guest joins it directly, so no router or internet is used.
 */
@SuppressLint("MissingPermission") // NEARBY_WIFI_DEVICES / location are requested by the UI first
class WifiDirect(private val context: Context) {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    val available: Boolean get() = manager != null

    private fun ch(): WifiP2pManager.Channel =
        channel ?: manager!!.initialize(context, Looper.getMainLooper(), null).also { channel = it }

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) = suspendCancellableCoroutine { cont ->
        block(object : WifiP2pManager.ActionListener {
            override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onFailure(reason: Int) { if (cont.isActive) cont.resumeWithException(IllegalStateException("wifi p2p failure $reason")) }
        })
    }

    suspend fun createGroup(networkName: String, passphrase: String): WifiP2pInfo {
        val m = manager ?: error("Wi-Fi Direct unavailable")
        runCatching { action { m.removeGroup(ch(), it) } }
        val config = WifiP2pConfig.Builder().setNetworkName(networkName).setPassphrase(passphrase).enablePersistentMode(false).build()
        action { m.createGroup(ch(), config, it) }
        return awaitConnection()
    }

    suspend fun joinGroup(networkName: String, passphrase: String): WifiP2pInfo {
        val m = manager ?: error("Wi-Fi Direct unavailable")
        val config = WifiP2pConfig.Builder().setNetworkName(networkName).setPassphrase(passphrase).enablePersistentMode(false).build()
        action { m.connect(ch(), config, it) }
        return awaitConnection()
    }

    private suspend fun awaitConnection(): WifiP2pInfo = withTimeout(30_000) {
        val result = CompletableDeferred<WifiP2pInfo>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                manager?.requestConnectionInfo(ch()) { info -> if (info?.groupFormed == true && info.groupOwnerAddress != null) result.complete(info) }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            manager?.requestConnectionInfo(ch()) { info -> if (info?.groupFormed == true && info.groupOwnerAddress != null) result.complete(info) }
            result.await()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    fun removeGroup() {
        val m = manager ?: return
        runCatching { m.removeGroup(ch(), null) }
        channel?.close()
        channel = null
    }
}
