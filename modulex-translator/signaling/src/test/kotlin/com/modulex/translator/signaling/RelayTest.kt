package com.modulex.translator.signaling

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RelayTest {
    private val room = "pairingCode_0123456789"
    private val otherRoom = "AnotherRoom-abcdefgh"

    private fun relayTest(config: Config = Config(), block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) =
        testApplication {
            application { signalingModule(config) }
            val client = createClient { install(WebSockets) }
            block(client)
        }

    private suspend fun HttpClient.join(roomId: String = room): DefaultClientWebSocketSession =
        webSocketSession("/v1/rooms/$roomId")

    private suspend fun DefaultClientWebSocketSession.next(): JsonObject = withTimeout(5.seconds) {
        val frame = incoming.receive()
        assertTrue(frame is Frame.Text, "expected text frame, got $frame")
        Json.parseToJsonElement(frame.readText()).jsonObject
    }

    private suspend fun DefaultClientWebSocketSession.sendJson(json: String) = send(Frame.Text(json))

    private fun JsonObject.type() = this["type"]!!.jsonPrimitive.content
    private fun JsonObject.code() = this["code"]!!.jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.expectError(code: String) {
        val msg = next()
        assertEquals("error", msg.type(), "message: $msg")
        assertEquals(code, msg.code())
    }

    /** Asserts the server closes the socket with [code] and sends nothing else first. */
    private suspend fun DefaultClientWebSocketSession.expectClose(code: CloseReason.Codes) {
        withTimeout(5.seconds) {
            for (frame in incoming) error("unexpected frame before close: ${(frame as? Frame.Text)?.readText() ?: frame}")
            assertEquals(code.code, closeReason.await()?.code)
        }
    }

    private suspend fun DefaultClientWebSocketSession.expectJoined(peers: Int) {
        val msg = next()
        assertEquals("joined", msg.type(), "message: $msg")
        assertEquals(peers, msg["peers"]!!.jsonPrimitive.int)
    }

    /** Joins two peers and consumes the join notifications. */
    private suspend fun HttpClient.pair(roomId: String = room): Pair<DefaultClientWebSocketSession, DefaultClientWebSocketSession> {
        val a = join(roomId)
        a.expectJoined(1)
        val b = join(roomId)
        b.expectJoined(2)
        assertEquals("peer-joined", a.next().type())
        return a to b
    }

    @Test
    fun healthz() = relayTest { client ->
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun twoPeersJoin() = relayTest { client ->
        client.pair()
    }

    @Test
    fun simultaneousJoinsAreSerialized() = relayTest { client ->
        val sessions = coroutineScope { List(3) { async { client.join() } }.awaitAll() }
        val first = sessions.map { it.next() }
        val joinedCounts = first.filter { it.type() == "joined" }.map { it["peers"]!!.jsonPrimitive.int }.sorted()
        assertEquals(listOf(1, 2), joinedCounts)
        assertEquals(listOf("room_full"), first.filter { it.type() == "error" }.map { it.code() })
    }

    @Test
    fun signalRelayedVerbatim() = relayTest { client ->
        val (a, b) = client.pair()
        val payload: JsonElement = Json.parseToJsonElement(
            """{"kind":"offer","sdp":"v=0\r\no=- 46117 2 IN IP4 127.0.0.1\r\na=fingerprint:sha-256 AB:CD\r\n",
               "nested":{"list":[1,2.50,-3e10,true,false,null,{"deep":{"deeper":["x"]}}],"empty":{},"arr":[]},
               "unicode":"Grüße 日本語 مرحبا 😀 \u0000 \"quoted\" \\ /"}""",
        )
        a.sendJson(buildJsonObject { put("type", "signal"); put("payload", payload) }.toString())
        val got = b.next()
        assertEquals("signal", got.type())
        assertEquals(payload, got["payload"])
        assertEquals(setOf("type", "payload"), got.keys)

        // Scalars and the other direction work too.
        b.sendJson("""{"type":"signal","payload":"candidate:1 1 udp 2122260223 192.0.2.1 54321 typ host"}""")
        assertEquals(Json.parseToJsonElement("\"candidate:1 1 udp 2122260223 192.0.2.1 54321 typ host\""), a.next()["payload"])
        b.sendJson("""{"type":"signal","payload":null}""")
        assertEquals(Json.parseToJsonElement("null"), a.next()["payload"])
    }

    @Test
    fun signalWithoutPeerIsNoPeer() = relayTest { client ->
        val a = client.join()
        a.expectJoined(1)
        a.sendJson("""{"type":"signal","payload":{"sdp":"x"}}""")
        a.expectError("no_peer")
        // Connection stays open.
        a.sendJson("""{"type":"ping"}""")
        assertEquals("pong", a.next().type())
    }

    @Test
    fun thirdParticipantGetsRoomFull() = relayTest { client ->
        val (a, b) = client.pair()
        val c = client.join()
        c.expectError("room_full")
        c.expectClose(CloseReason.Codes.VIOLATED_POLICY)
        // The existing pair is unaffected.
        a.sendJson("""{"type":"signal","payload":1}""")
        assertEquals("signal", b.next().type())
    }

    @Test
    fun invalidRoomIdRejectedBeforeUpgrade() = relayTest { client ->
        for (bad in listOf("short", "has%20space_padding_xx", "bad!chars_0123456789", "x".repeat(65))) {
            val response = client.get("/v1/rooms/$bad")
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
            assertEquals("""{"type":"error","code":"bad_room"}""", response.bodyAsText())
        }
        assertFailsWith<Exception> { client.webSocket("/v1/rooms/short") { } }
        // Boundary lengths are accepted.
        client.join("a".repeat(16)).expectJoined(1)
        client.join("Z".repeat(64)).expectJoined(1)
    }

    @Test
    fun plainGetWithoutUpgrade() = relayTest { client ->
        assertEquals(HttpStatusCode.UpgradeRequired, client.get("/v1/rooms/$room").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/rooms/short").status)
    }

    @Test
    fun oversizedFrameIsTooLarge() = relayTest(Config(maxFrameBytes = 1024)) { client ->
        val (a, b) = client.pair()
        // Exactly at the limit is fine.
        val prefix = """{"type":"signal","payload":""""
        val fits = prefix + "x".repeat(1024 - prefix.length - 2) + "\"}"
        assertEquals(1024, fits.toByteArray().size)
        a.sendJson(fits)
        assertEquals("signal", b.next().type())

        a.sendJson(prefix + "x".repeat(2000) + "\"}")
        a.expectError("too_large")
        a.expectClose(CloseReason.Codes.TOO_BIG)
        assertEquals("peer-left", b.next().type())
    }

    @Test
    fun messageRateLimit() = relayTest(Config(msgRatePerWindow = 5)) { client ->
        val a = client.join()
        a.expectJoined(1)
        repeat(5) {
            a.sendJson("""{"type":"ping"}""")
            assertEquals("pong", a.next().type())
        }
        a.sendJson("""{"type":"ping"}""")
        a.expectError("rate_limited")
        a.expectClose(CloseReason.Codes.VIOLATED_POLICY)
    }

    @Test
    fun joinRateLimitPerIp() = relayTest(Config(joinRatePerMinute = 3)) { client ->
        repeat(3) { assertEquals(HttpStatusCode.UpgradeRequired, client.get("/v1/rooms/$room").status) }
        val limited = client.get("/v1/rooms/$room")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("""{"type":"error","code":"rate_limited"}""", limited.bodyAsText())
        // Invalid room ids count too (guessing attempts are throttled regardless).
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/v1/rooms/short").status)
        assertFailsWith<Exception> { client.join() }
    }

    @Test
    fun joinRateLimitUsesForwardedForOnlyWhenTrusted() {
        relayTest(Config(joinRatePerMinute = 1, trustProxyHeaders = true)) { client ->
            suspend fun get(ip: String) = client.get("/v1/rooms/$room") { header("X-Forwarded-For", "$ip, 10.0.0.1") }.status
            assertEquals(HttpStatusCode.UpgradeRequired, get("198.51.100.1"))
            assertEquals(HttpStatusCode.TooManyRequests, get("198.51.100.1"))
            assertEquals(HttpStatusCode.UpgradeRequired, get("198.51.100.2"))
        }
        relayTest(Config(joinRatePerMinute = 1, trustProxyHeaders = false)) { client ->
            suspend fun get(ip: String) = client.get("/v1/rooms/$room") { header("X-Forwarded-For", ip) }.status
            assertEquals(HttpStatusCode.UpgradeRequired, get("198.51.100.1"))
            assertEquals(HttpStatusCode.TooManyRequests, get("198.51.100.2"))
        }
    }

    @Test
    fun peerLeftAndRoomFreed() = relayTest(Config(maxRooms = 1)) { client ->
        val (a, b) = client.pair()
        b.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
        assertEquals("peer-left", a.next().type())

        // A new peer can take the free slot.
        val c = client.join()
        c.expectJoined(2)
        assertEquals("peer-joined", a.next().type())
        c.sendJson("""{"type":"signal","payload":{"hello":"a"}}""")
        assertEquals("signal", a.next().type())

        // Once everyone leaves the room is dropped (maxRooms = 1 lets another room be created).
        a.close()
        assertEquals("peer-left", c.next().type())
        c.close()
        val d = eventually { client.join(otherRoom).also { it.expectJoined(1) } }
        d.close()
    }

    @Test
    fun serverFull() = relayTest(Config(maxRooms = 1)) { client ->
        client.join().expectJoined(1)
        val other = client.join(otherRoom)
        other.expectError("server_full")
        other.expectClose(CloseReason.Codes.TRY_AGAIN_LATER)
    }

    @Test
    fun pingPong() = relayTest { client ->
        val (a, b) = client.pair()
        a.sendJson("""{"type":"ping"}""")
        assertEquals(Json.parseToJsonElement("""{"type":"pong"}"""), a.next())
        // Pings are answered by the server, never relayed.
        b.sendJson("""{"type":"signal","payload":2}""")
        assertEquals("signal", a.next().type())
    }

    @Test
    fun badFramesThenClose() = relayTest { client ->
        val a = client.join()
        a.expectJoined(1)
        val bad = listOf("not json", """{"type":"nope"}""", "[1,2]", """{"type":"signal"}""")
        for (frame in bad) {
            a.sendJson(frame)
            a.expectError("bad_frame")
        }
        // Still open after four bad frames.
        a.sendJson("""{"type":"ping"}""")
        assertEquals("pong", a.next().type())
        a.sendJson("""{"type":42}""")
        a.expectError("bad_frame")
        a.expectClose(CloseReason.Codes.VIOLATED_POLICY)
    }

    @Test
    fun binaryFrameRejected() = relayTest { client ->
        val a = client.join()
        a.expectJoined(1)
        a.send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
        a.expectError("bad_frame")
        a.expectClose(CloseReason.Codes.CANNOT_ACCEPT)
    }

    @Test
    fun idleRoomsAreClosed() = relayTest(Config(roomIdleTimeout = 400.milliseconds)) { client ->
        val (a, b) = client.pair()
        a.expectClose(CloseReason.Codes.GOING_AWAY)
        b.expectClose(CloseReason.Codes.GOING_AWAY)
        client.join().expectJoined(1)
    }

    private suspend fun <T> eventually(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(50) {
            try {
                return block()
            } catch (e: Throwable) {
                last = e
                kotlinx.coroutines.delay(50)
            }
        }
        throw last!!
    }
}
