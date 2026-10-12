package com.modulex.translator.core.transport

import com.modulex.translator.core.protocol.ProtocolLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TlsTransportTest {
    private val host = TestIdentity("host")
    private val guest = TestIdentity("guest")

    @Test
    fun handshakeOverPipesExchangesFramesBothWays() = runBlocking {
        withTimeout(20_000) {
            val (s, c) = TestTls.connect(host, guest)
            assertEquals(host.fingerprint, c.remoteFingerprint)
            assertEquals(guest.fingerprint, s.remoteFingerprint)
            assertEquals(host.fingerprint, s.localFingerprint)

            c.send("hello".toByteArray())
            assertContentEquals("hello".toByteArray(), s.receive())
            // Large frame crosses several TLS records.
            val big = ByteArray(60_000) { (it % 251).toByte() }
            s.send(big)
            assertContentEquals(big, c.receive())
            // Many small frames in a row keep their boundaries.
            repeat(200) { c.send("m$it".toByteArray()) }
            repeat(200) { assertEquals("m$it", String(s.receive()!!)) }
            c.close()
            assertNull(s.receive())
        }
    }

    @Test
    fun wrongPinnedFingerprintIsRejected() {
        val other = TestIdentity("attacker")
        assertFailsWith<Exception> {
            runBlocking { withTimeout(20_000) { TestTls.connect(host = other, guest = guest, pinnedHostFp = host.fingerprint) } }
        }
    }

    @Test
    fun tlsOverRealLoopbackTcp() = runBlocking {
        withTimeout(20_000) {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val serverCtx = PeerTrust.context(host.keyManagers, PeerTrust.anyClientRecorded())
                val clientCtx = PeerTrust.context(guest.keyManagers, PeerTrust.pinnedServer(host.fingerprint))
                val accepted = async(Dispatchers.IO) {
                    val sock = server.accept()
                    TlsStream(PeerTrust.serverEngine(serverCtx), sock.getInputStream(), sock.getOutputStream()).let {
                        it.handshake(); TlsFrameChannel(it, "lan") { sock.close() }
                    }
                }
                val client = async(Dispatchers.IO) {
                    val sock = Socket(InetAddress.getLoopbackAddress(), server.localPort)
                    TlsStream(PeerTrust.clientEngine(clientCtx), sock.getInputStream(), sock.getOutputStream()).let {
                        it.handshake(); TlsFrameChannel(it, "lan") { sock.close() }
                    }
                }
                val s = accepted.await()
                val c = client.await()
                c.send("ping".toByteArray())
                assertEquals("ping", String(s.receive()!!))
                s.send("pong".toByteArray())
                assertEquals("pong", String(c.receive()!!))
                c.close(); s.close()
            }
        }
    }

    @Test
    fun guestWithoutClientCertificateIsRejected() {
        assertFailsWith<Exception> {
            runBlocking {
                withTimeout(20_000) {
                    val pipes = PipePair()
                    val serverCtx = PeerTrust.context(host.keyManagers, PeerTrust.anyClientRecorded())
                    val clientCtx = PeerTrust.context(emptyArray(), PeerTrust.pinnedServer(host.fingerprint))
                    val s = async(Dispatchers.IO) { TlsStream(PeerTrust.serverEngine(serverCtx), pipes.a.first, pipes.a.second).apply { handshake() } }
                    val c = async(Dispatchers.IO) {
                        TlsStream(PeerTrust.clientEngine(clientCtx), pipes.b.first, pipes.b.second).apply {
                            handshake()
                            // TLS 1.3 reports client-auth failure on first read.
                            input.read()
                        }
                    }
                    s.await(); c.await()
                }
            }
        }
    }

    @Test
    fun framingRejectsOversizedAndTruncatedFrames() {
        val out = ByteArrayOutputStream()
        FramedStream(ByteArrayInputStream(ByteArray(0)), out, 16).write("abc".toByteArray())
        assertContentEquals(byteArrayOf(0, 0, 0, 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()), out.toByteArray())

        val oversized = byteArrayOf(0, 0, 1, 0) + ByteArray(256)
        assertFailsWith<IOException> { FramedStream(ByteArrayInputStream(oversized), ByteArrayOutputStream(), 16).read() }
        val truncated = byteArrayOf(0, 0, 0, 10, 1, 2)
        assertFailsWith<IOException> { FramedStream(ByteArrayInputStream(truncated), ByteArrayOutputStream(), 16).read() }
        val zero = byteArrayOf(0, 0, 0, 0)
        assertFailsWith<IOException> { FramedStream(ByteArrayInputStream(zero), ByteArrayOutputStream(), 16).read() }
        assertNull(FramedStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), 16).read())
        assertFailsWith<IllegalArgumentException> {
            FramedStream(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), ProtocolLimits.MAX_FRAME_BYTES).write(ByteArray(ProtocolLimits.MAX_FRAME_BYTES + 1))
        }
    }
}
