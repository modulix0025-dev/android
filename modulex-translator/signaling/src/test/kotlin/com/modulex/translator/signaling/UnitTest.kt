package com.modulex.translator.signaling

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class UnitTest {
    @Test
    fun slidingWindow() {
        var now = 0L
        val limiter = SlidingWindowLimiter(3, 1_000) { now }
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        now = 999
        assertFalse(limiter.tryAcquire())
        now = 1_000
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
        now = 1_500
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun keyedLimiterPrunesIdleKeys() {
        var now = 0L
        val limiter = KeyedRateLimiter(1, 1_000) { now }
        assertTrue(limiter.tryAcquire("a"))
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"))
        limiter.prune()
        assertEquals(2, limiter.size)
        now = 2_000
        limiter.prune()
        assertEquals(0, limiter.size)
    }

    @Test
    fun configFromEnv() {
        val defaults = Config.fromEnv(emptyMap())
        assertEquals(Config(), defaults)
        assertEquals(8080, defaults.port)
        assertEquals(16384, defaults.maxFrameBytes)
        assertEquals(600.seconds, defaults.roomIdleTimeout)
        assertNull(defaults.tls)

        val c = Config.fromEnv(
            mapOf(
                "PORT" to "9000", "HOST" to "127.0.0.1", "MAX_FRAME_BYTES" to "2048", "MAX_ROOMS" to "5",
                "ROOM_IDLE_SECONDS" to "60", "JOIN_RATE_PER_MINUTE" to "7", "MSG_RATE_PER_10S" to "9",
                "TRUST_PROXY_HEADERS" to "true", "TLS_PORT" to "8443", "TLS_KEYSTORE_PATH" to "/k.p12",
                "TLS_KEYSTORE_PASSWORD" to "secret", "TLS_KEY_ALIAS" to "relay",
            ),
        )
        assertEquals(9000, c.port)
        assertEquals("127.0.0.1", c.host)
        assertEquals(2048, c.maxFrameBytes)
        assertEquals(5, c.maxRooms)
        assertEquals(60.seconds, c.roomIdleTimeout)
        assertEquals(7, c.joinRatePerMinute)
        assertEquals(9, c.msgRatePerWindow)
        assertTrue(c.trustProxyHeaders)
        assertEquals(TlsConfig("/k.p12", "secret", "relay", 8443), c.tls)
        assertFalse("secret" in c.toString())

        assertFailsWith<IllegalStateException> { Config.fromEnv(mapOf("TLS_PORT" to "8443")) }
        assertFailsWith<IllegalStateException> { Config.fromEnv(mapOf("PORT" to "abc")) }
    }

    @Test
    fun parsing() {
        assertEquals(ClientMessage.Ping, parseClientMessage("""{"type":"ping"}"""))
        assertTrue(parseClientMessage("""{"type":"signal","payload":[]}""") is ClientMessage.Signal)
        for (bad in listOf("", "null", "\"ping\"", """{"type":"signal"}""", """{"type":["ping"]}""", "{", "[[[[[[")) {
            assertNull(parseClientMessage(bad), bad)
        }
        // Deep nesting is rejected instead of blowing the stack; brackets inside strings don't count.
        fun nested(depth: Int) = """{"type":"signal","payload":${"[".repeat(depth)}${"]".repeat(depth)}}"""
        assertNull(parseClientMessage(nested(8000)))
        assertNull(parseClientMessage(nested(MAX_JSON_DEPTH)))
        assertTrue(parseClientMessage(nested(MAX_JSON_DEPTH - 1)) is ClientMessage.Signal)
        val brackets = "[{\\\"".repeat(100)
        assertTrue(parseClientMessage("""{"type":"signal","payload":"$brackets"}""") is ClientMessage.Signal)
    }

    @Test
    fun shortHashIsShort() {
        assertEquals(8, shortHash("pairingCode_0123456789").length)
    }
}
