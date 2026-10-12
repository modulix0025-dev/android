package com.modulex.translator.core.protocol

/**
 * Sender side of reliable delivery: assigns sequence numbers and keeps unacknowledged messages for
 * retransmission (after a timeout or a reconnect). Bounded: when full, [enqueue] refuses new
 * messages instead of silently dropping old ones. Not thread-safe; callers serialise access.
 */
class OutboundReliableQueue(
    private val capacity: Int = 128,
    private val retryAfterMs: Long = 3_000,
    private val maxAttempts: Int = 5,
    startSeq: Long = 0,
) {
    data class Pending(val message: WireMessage, var attempts: Int, var lastSentAt: Long)

    private val pending = ArrayDeque<Pending>()
    var lastAssignedSeq: Long = startSeq
        private set

    val size: Int get() = pending.size

    /** Returns the message with its sequence number assigned, or null when the queue is full. */
    fun enqueue(build: (seq: Long) -> WireMessage, now: Long): WireMessage? {
        if (pending.size >= capacity) return null
        val seq = lastAssignedSeq + 1
        val m = build(seq)
        require(m.seq == seq) { "builder must use the assigned seq" }
        lastAssignedSeq = seq
        pending.addLast(Pending(m, attempts = 1, lastSentAt = now))
        return m
    }

    /** Cumulative ack: removes everything up to and including [ackSeq]. Returns acknowledged ids. */
    fun acknowledge(ackSeq: Long): List<String> {
        val done = ArrayList<String>()
        while (pending.isNotEmpty() && pending.first().message.seq <= ackSeq) {
            done += pending.removeFirst().message.messageId
        }
        return done
    }

    sealed interface RetryDecision {
        data class Resend(val messages: List<WireMessage>) : RetryDecision
        /** Retries exhausted for the oldest message: the link should be considered broken. */
        data class GaveUp(val messageId: String) : RetryDecision
    }

    /** Messages whose retry timer expired, in order. Retransmission keeps the original seq. */
    fun due(now: Long): RetryDecision {
        val out = ArrayList<WireMessage>()
        for (p in pending) {
            if (now - p.lastSentAt >= retryAfterMs) {
                if (p.attempts >= maxAttempts) return RetryDecision.GaveUp(p.message.messageId)
                p.attempts++
                p.lastSentAt = now
                out += p.message
            }
        }
        return RetryDecision.Resend(out)
    }

    /** Everything still unacknowledged, for replay after reconnection. Resets attempt counters. */
    fun replayAll(now: Long): List<WireMessage> = pending.map {
        it.attempts = 1
        it.lastSentAt = now
        it.message
    }
}

/**
 * Receiver side: delivers reliable messages exactly once and in order, even when the sender replays
 * after a reconnect. Out-of-order messages are buffered (bounded) until the gap is filled.
 */
class InboundSequencer(private val maxBuffered: Int = 64, startAfter: Long = 0) {
    var highestContiguous: Long = startAfter
        private set
    private val buffer = java.util.TreeMap<Long, WireMessage>()

    sealed interface Outcome {
        /** Deliver these messages in this order (may be several when a gap closed). */
        data class Deliver(val messages: List<WireMessage>) : Outcome
        /** Already delivered: drop, but re-acknowledge. */
        data object Duplicate : Outcome
        /** Ahead of the expected seq: buffered until the gap fills. */
        data object Buffered : Outcome
        /** Too far ahead or buffer full: reject. */
        data object Overflow : Outcome
    }

    fun accept(m: WireMessage): Outcome {
        val seq = m.seq
        if (seq <= highestContiguous || buffer.containsKey(seq)) return Outcome.Duplicate
        if (seq == highestContiguous + 1) {
            val out = arrayListOf(m)
            highestContiguous = seq
            while (true) {
                val next = buffer.remove(highestContiguous + 1) ?: break
                out += next
                highestContiguous++
            }
            return Outcome.Deliver(out)
        }
        if (buffer.size >= maxBuffered || seq - highestContiguous > maxBuffered * 4L) return Outcome.Overflow
        buffer[seq] = m
        return Outcome.Buffered
    }

    val bufferedCount: Int get() = buffer.size
}

/** Bounded LRU set of message ids, used for control messages that carry no sequence number. */
class RecentIds(private val capacity: Int = 512) {
    private val set = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** Returns true if the id was new. */
    fun add(id: String): Boolean = set.put(id, Unit) == null
}
