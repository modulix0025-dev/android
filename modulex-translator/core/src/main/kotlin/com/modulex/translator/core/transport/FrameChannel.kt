package com.modulex.translator.core.transport

import com.modulex.translator.core.protocol.ProtocolLimits
import com.modulex.translator.core.security.Fingerprints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * A bidirectional, ordered, encrypted message pipe between the two devices. Implementations:
 * TLS over TCP (LAN / Wi-Fi Direct), TLS over Bluetooth RFCOMM, and WebRTC data channels (DTLS).
 *
 * The fingerprints identify the certificates that secure this particular connection; they are
 * bound into the session proofs so that a relay cannot splice two separate connections together.
 */
interface FrameChannel : Closeable {
    val localFingerprint: String
    val remoteFingerprint: String
    /** Human-readable transport label for diagnostics ("LAN", "Bluetooth", ...). */
    val label: String

    /** Sends one frame. Throws [java.io.IOException] once the channel is closed. */
    suspend fun send(frame: ByteArray)

    /** Next frame, or null once the channel is closed by either side. */
    suspend fun receive(): ByteArray?
}

/** [FrameChannel] over an established [TlsStream]. */
class TlsFrameChannel(
    private val tls: TlsStream,
    override val label: String,
    private val onClose: () -> Unit = {},
) : FrameChannel {
    private val framed = FramedStream(tls.input, tls.output, ProtocolLimits.MAX_FRAME_BYTES)
    override val localFingerprint: String = Fingerprints.sha256Hex(tls.localCertificate.encoded)
    override val remoteFingerprint: String = Fingerprints.sha256Hex(tls.peerCertificate.encoded)

    override suspend fun send(frame: ByteArray) = withContext(Dispatchers.IO) { framed.write(frame) }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            framed.read()
        } catch (e: java.io.IOException) {
            null
        }
    }

    override fun close() {
        runCatching { tls.close() }
        onClose()
    }
}
