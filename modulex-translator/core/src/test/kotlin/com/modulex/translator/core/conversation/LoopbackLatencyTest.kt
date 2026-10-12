package com.modulex.translator.core.conversation

import com.modulex.translator.core.security.SecureIds
import com.modulex.translator.core.transport.PeerTrust
import com.modulex.translator.core.transport.TestIdentity
import com.modulex.translator.core.transport.TlsFrameChannel
import com.modulex.translator.core.transport.TlsStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Full stack over real TCP on the loopback interface: TLS 1.3 handshake with pinning, authenticated
 * join, then 200 utterances. Reports end-to-end delivery latency (send -> delivered at receiver).
 * HOST LOOPBACK ONLY: this measures protocol/crypto overhead, not Wi-Fi or Bluetooth latency.
 */
class LoopbackLatencyTest {
    @Test
    fun deliveryLatencyOverLoopbackTls() = runBlocking<Unit> {
        withTimeout(60_000) {
            val hostId = TestIdentity("host"); val guestId = TestIdentity("guest")
            val sid = SecureIds.newId(); val secret = SecureIds.randomBytes(32)
            val host = ConversationSession(Role.HOST, sid, secret, "hostAAAA", ParticipantProfile("A", "ar", "ar"))
            val guest = ConversationSession(Role.GUEST, sid, secret, "guestBBB", ParticipantProfile("B", "en", "en"))
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val handshakeStart = System.nanoTime()
                val sCh = async(Dispatchers.IO) {
                    val s = server.accept(); s.tcpNoDelay = true
                    TlsStream(PeerTrust.serverEngine(PeerTrust.context(hostId.keyManagers, PeerTrust.anyClientRecorded())), s.getInputStream(), s.getOutputStream())
                        .apply { handshake() }.let { TlsFrameChannel(it, "lan") { s.close() } }
                }
                val cCh = async(Dispatchers.IO) {
                    val s = Socket(InetAddress.getLoopbackAddress(), server.localPort); s.tcpNoDelay = true
                    TlsStream(PeerTrust.clientEngine(PeerTrust.context(guestId.keyManagers, PeerTrust.pinnedServer(hostId.fingerprint))), s.getInputStream(), s.getOutputStream())
                        .apply { handshake() }.let { TlsFrameChannel(it, "lan") { s.close() } }
                }
                val joined = async(start = CoroutineStart.UNDISPATCHED) { host.events.filterIsInstance<SessionEvent.PeerJoined>().first() }
                val receivedAt = Channel<Pair<String, Long>>(Channel.UNLIMITED)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    host.events.filterIsInstance<SessionEvent.Received>().collect { receivedAt.send(it.message.translatedText!! to System.nanoTime()) }
                }
                val a = launch(Dispatchers.IO) { host.attach(sCh.await()) }
                val b = launch(Dispatchers.IO) { guest.attach(cCh.await()) }
                joined.await()
                val connectMs = (System.nanoTime() - handshakeStart) / 1e6
                val lat = ArrayList<Double>()
                repeat(200) { i ->
                    val t0 = System.nanoTime()
                    guest.sendUtterance("message $i", "en", "ar", null)
                    val (text, t1) = receivedAt.receive()
                    assertEquals("message $i", text)
                    lat += (t1 - t0) / 1e6
                }
                lat.sort()
                println("LOOPBACK (host JVM, not a device): connect+TLS+auth=%.1f ms; delivery median=%.3f ms p95=%.3f ms max=%.3f ms n=%d"
                    .format(connectMs, lat[lat.size / 2], lat[(lat.size * 95) / 100], lat.last(), lat.size))
                collector.cancel(); a.cancel(); b.cancel()
            }
        }
    }
}
