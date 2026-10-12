package com.modulex.translator.core.conversation

import com.modulex.translator.core.security.SecureIds
import com.modulex.translator.core.transport.FrameChannel
import com.modulex.translator.core.transport.TestIdentity
import com.modulex.translator.core.transport.TestTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConversationSessionTest {
    private val hostId = TestIdentity("host")
    private val guestId = TestIdentity("guest")
    private val timings = ConversationTimings(pingIntervalMs = 200, idleTimeoutMs = 2_000, retryTickMs = 100, retryAfterMs = 400)

    private fun sessions(secretForGuest: ByteArray? = null): Pair<ConversationSession, ConversationSession> {
        val sid = SecureIds.newId()
        val secret = SecureIds.randomBytes(32)
        val host = ConversationSession(Role.HOST, sid, secret, "hostAAAA", ParticipantProfile("Amal", "ar", "en"), timings)
        val guest = ConversationSession(Role.GUEST, sid, secretForGuest ?: secret, "guestBBBB", ParticipantProfile("Ben", "en", "ar"), timings)
        return host to guest
    }

    private fun CoroutineScope.attachBoth(h: ConversationSession, g: ConversationSession, hc: FrameChannel, gc: FrameChannel): Pair<Job, Job> =
        launch(Dispatchers.IO) { h.attach(hc) } to launch(Dispatchers.IO) { g.attach(gc) }

    @Test
    fun handshakeAuthenticatesAndDeliversBothDirections() = runBlocking {
        withTimeout(30_000) {
            val (h, g) = sessions()
            val (hc, gc) = TestTls.connect(hostId, guestId)
            val hostJoined = async(start = CoroutineStart.UNDISPATCHED) { h.events.filterIsInstance<SessionEvent.PeerJoined>().first() }
            val guestJoined = async(start = CoroutineStart.UNDISPATCHED) { g.events.filterIsInstance<SessionEvent.PeerJoined>().first() }
            val (j1, j2) = attachBoth(h, g, hc, gc)
            val hj = hostJoined.await()
            val gj = guestJoined.await()
            assertEquals("Ben", hj.profile.displayName)
            assertEquals("en", hj.profile.speaks)
            assertEquals("Amal", gj.profile.displayName)
            // Both screens show the same short authentication string.
            assertEquals(hj.sas, gj.sas)
            assertEquals(6, hj.sas.length)

            val received = async(start = CoroutineStart.UNDISPATCHED) { h.events.filterIsInstance<SessionEvent.Received>().first() }
            val delivered = async(start = CoroutineStart.UNDISPATCHED) { g.events.filterIsInstance<SessionEvent.Delivered>().first() }
            val id = g.sendUtterance("مرحبا", "en", "ar", original = "Hello")
            assertNotNull(id)
            val r = received.await().message
            assertEquals("مرحبا", r.translatedText)
            assertEquals("Hello", r.originalText)
            assertEquals(1, r.seq)
            assertEquals(id, delivered.await().messageId)

            val back = async(start = CoroutineStart.UNDISPATCHED) { g.events.filterIsInstance<SessionEvent.Received>().first() }
            h.sendUtterance("How are you?", "ar", "en", original = null)
            assertEquals("How are you?", back.await().message.translatedText)

            val latency = g.events.filterIsInstance<SessionEvent.LatencyMeasured>().first()
            assertTrue(latency.rttMs >= 0)

            val ended = async(start = CoroutineStart.UNDISPATCHED) { h.state.first { it is LinkState.Ended } }
            g.end()
            assertIs<LinkState.Ended>(ended.await())
            assertTrue((ended.await() as LinkState.Ended).byPeer)
            j1.cancel(); j2.cancel()
        }
    }

    @Test
    fun wrongSecretIsRejectedByHost() = runBlocking {
        withTimeout(30_000) {
            val (h, g) = sessions(secretForGuest = SecureIds.randomBytes(32))
            val (hc, gc) = TestTls.connect(hostId, guestId)
            val hr = async(Dispatchers.IO) { h.attach(hc) }
            val gr = async(Dispatchers.IO) { g.attach(gc) }
            assertIs<ConversationSession.AttachResult.Rejected>(hr.await())
            assertIs<ConversationSession.AttachResult.Rejected>(gr.await())
            assertEquals(null, h.peerId)
        }
    }

    @Test
    fun reconnectReplaysUnacknowledgedMessagesWithoutDuplicates() = runBlocking {
        withTimeout(30_000) {
            val (h, g) = sessions()
            val receivedTexts = Channel<String>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.events.filterIsInstance<SessionEvent.Received>().collect { receivedTexts.send(it.message.translatedText!!) } }

            // First connection.
            val (hc1, gc1) = TestTls.connect(hostId, guestId)
            val (a1, b1) = attachBoth(h, g, hc1, gc1)
            g.state.first { it is LinkState.Connected }
            g.sendUtterance("one", "en", "ar", null)
            assertEquals("one", receivedTexts.receive())

            // Connection drops; guest keeps talking while offline.
            gc1.close(); hc1.close()
            a1.join(); b1.join()
            g.sendUtterance("two", "en", "ar", null)
            g.sendUtterance("three", "en", "ar", null)
            // "one" may or may not have been acknowledged before the drop. If not, it is replayed
            // on reconnect and must be dropped as a duplicate by the receiver (asserted below).
            assertTrue(g.pendingOutbound in 2..3, "pending=${g.pendingOutbound}")

            // Reconnect: queued messages arrive once, in order.
            val rejoined = async(start = CoroutineStart.UNDISPATCHED) { h.events.filterIsInstance<SessionEvent.PeerJoined>().first() }
            val (hc2, gc2) = TestTls.connect(hostId, guestId)
            val (a2, b2) = attachBoth(h, g, hc2, gc2)
            assertTrue(rejoined.await().rejoin)
            assertEquals("two", receivedTexts.receive())
            assertEquals("three", receivedTexts.receive())
            g.sendUtterance("four", "en", "ar", null)
            assertEquals("four", receivedTexts.receive())
            // Wait for acks to drain.
            while (g.pendingOutbound > 0) kotlinx.coroutines.delay(50)
            assertTrue(receivedTexts.tryReceive().isFailure, "no duplicates expected")
            collector.cancel(); a2.cancel(); b2.cancel()
        }
    }

    @Test
    fun aDifferentGuestCannotTakeOverAnEstablishedSession() = runBlocking {
        withTimeout(30_000) {
            val sid = SecureIds.newId()
            val secret = SecureIds.randomBytes(32)
            val h = ConversationSession(Role.HOST, sid, secret, "hostAAAA", ParticipantProfile("A", "ar", "en"), timings)
            val g1 = ConversationSession(Role.GUEST, sid, secret, "guestOne", ParticipantProfile("B", "en", "ar"), timings)
            val (hc1, gc1) = TestTls.connect(hostId, guestId)
            val (a, b) = attachBoth(h, g1, hc1, gc1)
            g1.state.first { it is LinkState.Connected }
            gc1.close(); a.join(); b.join()

            // Someone who also scanned the QR code tries to join as another participant.
            val g2 = ConversationSession(Role.GUEST, sid, secret, "guestTwo", ParticipantProfile("C", "en", "ar"), timings)
            val (hc2, gc2) = TestTls.connect(hostId, TestIdentity("intruder"))
            val hr = async(Dispatchers.IO) { h.attach(hc2) }
            val gr = async(Dispatchers.IO) { g2.attach(gc2) }
            assertIs<ConversationSession.AttachResult.Rejected>(hr.await())
            assertIs<ConversationSession.AttachResult.Rejected>(gr.await())
            assertEquals("guestOne", h.peerId)
        }
    }

    @Test
    fun languageUpdatesPropagate() = runBlocking {
        withTimeout(30_000) {
            val (h, g) = sessions()
            val (hc, gc) = TestTls.connect(hostId, guestId)
            val (a, b) = attachBoth(h, g, hc, gc)
            g.state.first { it is LinkState.Connected }
            val upd = async(start = CoroutineStart.UNDISPATCHED) { h.events.filterIsInstance<SessionEvent.PeerLanguagesChanged>().first() }
            g.updateLanguages(ParticipantProfile("Ben", "fr", "ar"))
            assertEquals("fr", upd.await().profile.speaks)
            a.cancel(); b.cancel()
        }
    }
}
