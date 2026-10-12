package com.modulex.translator.core.transport

import com.modulex.translator.core.security.Fingerprints
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trust decisions for ModuleX peer connections. Certificates are self-signed device certificates;
 * trust comes from the pairing QR code (host fingerprint pin) and from HMAC proofs bound to both
 * fingerprints (see SessionAuth), never from a CA.
 */
object PeerTrust {

    /** Guest side: only the exact host certificate from the QR code is accepted. */
    fun pinnedServer(expectedFingerprintHex: String): X509ExtendedTrustManager = object : BaseTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no server certificate")
            val fp = Fingerprints.sha256Hex(leaf.encoded)
            if (fp != Fingerprints.normalise(expectedFingerprintHex)) throw CertificateException("server certificate does not match pairing code")
        }
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client role only")
    }

    /**
     * Host side: any client certificate is accepted at the TLS layer *and recorded*; the guest must
     * then prove knowledge of the session secret bound to this certificate before any message is
     * processed (see ConversationSession).
     */
    fun anyClientRecorded(): X509ExtendedTrustManager = object : BaseTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("client certificate required")
            chain[0].checkValidity()
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("server role only")
    }

    abstract class BaseTrustManager : X509ExtendedTrustManager() {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkServerTrusted(chain, authType)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkServerTrusted(chain, authType)
    }

    fun context(keyManagers: Array<KeyManager>, trust: X509ExtendedTrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trust), null) }

    fun serverEngine(ctx: SSLContext): SSLEngine = ctx.createSSLEngine().apply {
        useClientMode = false
        needClientAuth = true
        enabledProtocols = arrayOf("TLSv1.3")
    }

    fun clientEngine(ctx: SSLContext): SSLEngine = ctx.createSSLEngine().apply {
        useClientMode = true
        enabledProtocols = arrayOf("TLSv1.3")
    }
}

/**
 * TLS 1.3 over any pair of byte streams (TCP sockets, Bluetooth RFCOMM sockets, in-memory pipes).
 *
 * The standard JSSE [SSLEngine] does all cryptography; this class only moves bytes. Reads and writes
 * may happen on different threads.
 */
class TlsStream(
    private val engine: SSLEngine,
    private val netIn: InputStream,
    private val netOut: OutputStream,
) : Closeable {
    private val readLock = Any()
    private val writeLock = Any()

    private var inNet: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
    private var inApp: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { flip() }
    private var outNet: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)

    @Volatile private var inboundClosed = false
    @Volatile private var closed = false

    /** Runs the handshake to completion. Throws on authentication failure or EOF. */
    fun handshake() {
        try {
            handshakeLoop()
        } catch (e: Exception) {
            // Tell the peer (alert) and release the transport so neither side waits forever.
            close()
            throw e
        }
    }

    private fun handshakeLoop() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> wrapAndFlush(EMPTY)
                HandshakeStatus.NEED_UNWRAP, HandshakeStatus.NEED_UNWRAP_AGAIN -> synchronized(readLock) {
                    unwrapOnce()
                    if (inboundClosed) throw EOFException("connection closed during handshake")
                }
                HandshakeStatus.NEED_TASK -> runTasks()
                HandshakeStatus.FINISHED, HandshakeStatus.NOT_HANDSHAKING -> return
                null -> return
            }
        }
    }

    val peerCertificate: X509Certificate
        get() = engine.session.peerCertificates.first() as X509Certificate

    val localCertificate: X509Certificate
        get() = engine.session.localCertificates.first() as X509Certificate

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(readLock) {
                while (!inApp.hasRemaining()) {
                    if (inboundClosed) return -1
                    unwrapOnce()
                    afterUnwrap()
                }
                val n = minOf(len, inApp.remaining())
                inApp.get(b, off, n)
                return n
            }
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("stream closed")
            wrapAndFlush(ByteBuffer.wrap(b, off, len))
        }
    }

    private fun afterUnwrap() {
        // TLS 1.3 post-handshake messages (key update, session tickets) may need a reply.
        when (engine.handshakeStatus) {
            HandshakeStatus.NEED_TASK -> runTasks()
            HandshakeStatus.NEED_WRAP -> wrapAndFlush(EMPTY)
            else -> Unit
        }
    }

    /** Decrypts at most one record into [inApp]; reads from the network when needed. Caller holds readLock. */
    private fun unwrapOnce() {
        inApp.compact()
        try {
            while (true) {
                val r = engine.unwrap(inNet, inApp)
                when (r.status) {
                    SSLEngineResult.Status.OK -> {
                        if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
                        if (r.bytesConsumed() == 0 && r.bytesProduced() == 0 && !inNet.hasRemaining() &&
                            r.handshakeStatus == HandshakeStatus.NEED_UNWRAP
                        ) {
                            if (!readMoreNet()) { inboundClosed = true; return }
                            continue
                        }
                        return
                    }
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                        if (inNet.capacity() < engine.session.packetBufferSize) {
                            inNet = ByteBuffer.allocate(engine.session.packetBufferSize).put(inNet).apply { flip() }
                        }
                        if (!readMoreNet()) { inboundClosed = true; return }
                    }
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        val bigger = ByteBuffer.allocate(inApp.capacity() + engine.session.applicationBufferSize)
                        inApp.flip()
                        bigger.put(inApp)
                        inApp = bigger
                    }
                    SSLEngineResult.Status.CLOSED -> { inboundClosed = true; return }
                    null -> return
                }
            }
        } finally {
            inApp.flip()
        }
    }

    /** Appends network bytes to [inNet] (kept in read mode). Returns false on EOF. */
    private fun readMoreNet(): Boolean {
        inNet.compact()
        try {
            if (!inNet.hasRemaining()) {
                val bigger = ByteBuffer.allocate(inNet.capacity() * 2)
                inNet.flip(); bigger.put(inNet); inNet = bigger
            }
            val n = netIn.read(inNet.array(), inNet.arrayOffset() + inNet.position(), inNet.remaining())
            if (n < 0) return false
            inNet.position(inNet.position() + n)
            return true
        } finally {
            inNet.flip()
        }
    }

    private fun wrapAndFlush(src: ByteBuffer) = synchronized(writeLock) {
        do {
            outNet.clear()
            val r = engine.wrap(src, outNet)
            when (r.status) {
                SSLEngineResult.Status.OK, SSLEngineResult.Status.CLOSED -> {
                    outNet.flip()
                    if (outNet.hasRemaining()) netOut.write(outNet.array(), outNet.arrayOffset(), outNet.remaining())
                    if (r.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
                    if (r.status == SSLEngineResult.Status.CLOSED) break
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> outNet = ByteBuffer.allocate(outNet.capacity() * 2)
                else -> throw IOException("unexpected wrap status ${r.status}")
            }
        } while (src.hasRemaining())
        netOut.flush()
    }

    private fun runTasks() {
        while (true) engine.delegatedTask?.run() ?: break
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            engine.closeOutbound()
            wrapAndFlush(EMPTY)
        }
        runCatching { netOut.close() }
        runCatching { netIn.close() }
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}

/** Length-prefixed frames (4-byte big-endian length) with a hard size limit. */
class FramedStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val maxFrame: Int,
) {
    private val writeLock = Any()

    fun write(frame: ByteArray) {
        require(frame.size in 1..maxFrame) { "frame size ${frame.size} out of range" }
        val buf = ByteArray(4 + frame.size)
        buf[0] = (frame.size ushr 24).toByte()
        buf[1] = (frame.size ushr 16).toByte()
        buf[2] = (frame.size ushr 8).toByte()
        buf[3] = frame.size.toByte()
        System.arraycopy(frame, 0, buf, 4, frame.size)
        synchronized(writeLock) {
            output.write(buf)
            output.flush()
        }
    }

    /** Returns null on clean EOF at a frame boundary; throws on truncated or oversized frames. */
    fun read(): ByteArray? {
        val header = ByteArray(4)
        val first = readFully(header, allowEof = true) ?: return null
        check(first == 4)
        val len = ((header[0].toInt() and 0xff) shl 24) or ((header[1].toInt() and 0xff) shl 16) or
            ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
        if (len <= 0 || len > maxFrame) throw IOException("frame length $len rejected")
        val body = ByteArray(len)
        readFully(body, allowEof = false)
        return body
    }

    private fun readFully(b: ByteArray, allowEof: Boolean): Int? {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) {
                if (off == 0 && allowEof) return null
                throw EOFException("truncated frame")
            }
            off += n
        }
        return off
    }
}
