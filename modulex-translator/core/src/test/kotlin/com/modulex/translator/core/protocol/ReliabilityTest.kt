package com.modulex.translator.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReliabilityTest {
    private fun m(seq: Long) = WireMessage(
        type = MessageType.UTTERANCE, sessionId = "s", messageId = "id$seq", senderId = "p", seq = seq,
        timestamp = 1, sourceLang = "en", targetLang = "ar", translatedText = "t$seq",
    )

    @Test
    fun queueAssignsSequenceAndAcknowledgesCumulatively() {
        val q = OutboundReliableQueue(capacity = 3)
        val a = q.enqueue({ m(it) }, 0)!!
        val b = q.enqueue({ m(it) }, 0)!!
        q.enqueue({ m(it) }, 0)!!
        assertEquals(1, a.seq); assertEquals(2, b.seq)
        assertNull(q.enqueue({ m(it) }, 0), "bounded queue refuses when full")
        assertEquals(listOf("id1", "id2"), q.acknowledge(2))
        assertEquals(1, q.size)
        assertEquals(emptyList(), q.acknowledge(2))
    }

    @Test
    fun retriesThenGivesUp() {
        val q = OutboundReliableQueue(retryAfterMs = 100, maxAttempts = 3)
        q.enqueue({ m(it) }, 0)
        assertEquals(0, (q.due(50) as OutboundReliableQueue.RetryDecision.Resend).messages.size)
        assertEquals(1, (q.due(100) as OutboundReliableQueue.RetryDecision.Resend).messages.size)
        assertEquals(1, (q.due(200) as OutboundReliableQueue.RetryDecision.Resend).messages.size)
        assertIs<OutboundReliableQueue.RetryDecision.GaveUp>(q.due(300))
        // Reconnect replay resets attempts.
        assertEquals(1, q.replayAll(400).size)
        assertIs<OutboundReliableQueue.RetryDecision.Resend>(q.due(500))
    }

    @Test
    fun sequencerDeliversInOrderExactlyOnce() {
        val s = InboundSequencer(maxBuffered = 4)
        assertEquals(listOf(1L), (s.accept(m(1)) as InboundSequencer.Outcome.Deliver).messages.map { it.seq })
        assertIs<InboundSequencer.Outcome.Duplicate>(s.accept(m(1)))
        assertIs<InboundSequencer.Outcome.Buffered>(s.accept(m(3)))
        assertIs<InboundSequencer.Outcome.Duplicate>(s.accept(m(3)))
        assertIs<InboundSequencer.Outcome.Buffered>(s.accept(m(4)))
        val out = s.accept(m(2)) as InboundSequencer.Outcome.Deliver
        assertEquals(listOf(2L, 3L, 4L), out.messages.map { it.seq })
        assertEquals(4, s.highestContiguous)
        assertEquals(0, s.bufferedCount)
        assertIs<InboundSequencer.Outcome.Overflow>(s.accept(m(1000)))
    }

    @Test
    fun recentIdsIsBounded() {
        val r = RecentIds(3)
        assertTrue(r.add("a")); assertTrue(!r.add("a"))
        r.add("b"); r.add("c"); r.add("d")
        assertTrue(r.add("a"), "evicted id is new again")
    }
}
