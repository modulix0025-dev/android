package com.modulex.translator.signaling

import java.util.concurrent.ConcurrentHashMap

/** Monotonic millisecond clock, injectable for tests. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.nanoTime() / 1_000_000 }
    }
}

/**
 * Sliding-window limiter: at most [limit] events in any [windowMillis] span.
 * Keeps a ring buffer of the last [limit] accepted timestamps. Thread-safe.
 */
class SlidingWindowLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val clock: Clock = Clock.System,
) {
    private val stamps = LongArray(limit)
    private var count = 0
    private var head = 0 // index of the oldest stamp once full

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.nowMillis()
        if (count < limit) {
            stamps[(head + count) % limit] = now
            count++
            return true
        }
        if (now - stamps[head] < windowMillis) return false
        stamps[head] = now
        head = (head + 1) % limit
        return true
    }

    /** True when no event happened within the window, i.e. the limiter holds no state worth keeping. */
    @Synchronized
    fun isIdle(): Boolean {
        if (count == 0) return true
        val newest = stamps[(head + count - 1) % limit]
        return clock.nowMillis() - newest >= windowMillis
    }
}

/** One [SlidingWindowLimiter] per key (client IP). Call [prune] periodically to bound memory. */
class KeyedRateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val clock: Clock = Clock.System,
) {
    private val limiters = ConcurrentHashMap<String, SlidingWindowLimiter>()

    fun tryAcquire(key: String): Boolean =
        limiters.computeIfAbsent(key) { SlidingWindowLimiter(limit, windowMillis, clock) }.tryAcquire()

    fun prune() {
        limiters.entries.removeIf { it.value.isIdle() }
    }

    val size: Int get() = limiters.size
}
