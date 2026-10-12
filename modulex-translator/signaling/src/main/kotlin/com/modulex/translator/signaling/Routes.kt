package com.modulex.translator.signaling

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("com.modulex.translator.signaling")

/** Installs the relay into an application. Everything it needs comes from [config]. */
fun Application.signalingModule(config: Config, clock: Clock = Clock.System) {
    val registry = RoomRegistry(config, clock)
    val joinLimiter = KeyedRateLimiter(config.joinRatePerMinute, 1.minutes.inWholeMilliseconds, clock)
    val connections = AtomicInteger()

    install(WebSockets) {
        pingPeriod = config.pingPeriod
        timeout = config.pingTimeout
        // Hard ceiling enforced by Ktor itself; frames between maxFrameBytes and this
        // ceiling get the friendlier `too_large` error from the handler below.
        maxFrameSize = config.maxFrameBytes.toLong() * 4
        masking = false
    }

    launch {
        val interval = (config.roomIdleTimeout / 4).coerceIn(100.milliseconds, 30.seconds)
        var lastStats = -1 to -1
        while (isActive) {
            delay(interval)
            val dropped = registry.sweepIdle()
            joinLimiter.prune()
            val stats = registry.roomCount to connections.get()
            if (stats != lastStats || dropped > 0) {
                log.info("rooms={} connections={} idleDropped={}", stats.first, stats.second, dropped)
                lastStats = stats
            }
        }
    }

    // Runs before the WebSocket upgrade, so rejected attempts never become sockets.
    // Join attempts are counted per client IP before the room id is even looked at.
    val roomGate = createRouteScopedPlugin("RoomGate") {
        onCall { call ->
            if (!joinLimiter.tryAcquire(call.clientIp(config.trustProxyHeaders))) {
                call.respondError(HttpStatusCode.TooManyRequests, Protocol.Err.RATE_LIMITED)
            } else if (!Protocol.ROOM_ID.matches(call.parameters["room"].orEmpty())) {
                call.respondError(HttpStatusCode.BadRequest, Protocol.Err.BAD_ROOM)
            }
        }
    }

    routing {
        get("/healthz") { call.respondText("ok", ContentType.Text.Plain) }

        route(Protocol.VERSION_PATH) {
            install(roomGate)

            webSocket { relaySession(config, registry, connections) }

            // A valid room id but no WebSocket handshake.
            get { call.respondError(HttpStatusCode.UpgradeRequired, Protocol.Err.BAD_FRAME) }
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String) =
    respondText(Protocol.error(code), ContentType.Application.Json, status)

internal fun ApplicationCall.clientIp(trustProxyHeaders: Boolean): String {
    if (trustProxyHeaders) {
        request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.origin.remoteAddress
}

private suspend fun DefaultWebSocketServerSession.relaySession(
    config: Config,
    registry: RoomRegistry,
    connections: AtomicInteger,
) {
    val roomId = call.parameters["room"]!!
    val tag = shortHash(roomId)
    val peer = Peer()
    val writer = launch { peer.runWriter(this@relaySession) }
    connections.incrementAndGet()
    var room: Room? = null
    try {
        when (val result = registry.join(roomId, peer)) {
            is JoinResult.Joined -> {
                room = result.room
                log.debug("room {} joined peers={}", tag, result.peers)
                readLoop(config, registry, result.room, peer)
            }
            JoinResult.Full -> peer.fail(Protocol.Err.ROOM_FULL, CloseReason.Codes.VIOLATED_POLICY)
            JoinResult.ServerFull -> {
                log.warn("server_full: room limit {} reached", config.maxRooms)
                peer.fail(Protocol.Err.SERVER_FULL, CloseReason.Codes.TRY_AGAIN_LATER)
            }
        }
    } finally {
        withContext(NonCancellable) {
            room?.let {
                registry.leave(it, peer)
                log.debug("room {} left", tag)
            }
            peer.close(CloseReason.Codes.NORMAL)
            if (withTimeoutOrNull(5.seconds) { writer.join() } == null) writer.cancel()
            connections.decrementAndGet()
        }
    }
}

private suspend fun DefaultWebSocketServerSession.readLoop(
    config: Config,
    registry: RoomRegistry,
    room: Room,
    peer: Peer,
) {
    val rate = SlidingWindowLimiter(config.msgRatePerWindow, config.msgRateWindow.inWholeMilliseconds)
    var badFrames = 0
    for (frame in incoming) {
        if (peer.isClosing) return
        if (frame !is Frame.Text && frame !is Frame.Binary) continue
        if (!rate.tryAcquire()) {
            return peer.fail(Protocol.Err.RATE_LIMITED, CloseReason.Codes.VIOLATED_POLICY)
        }
        if (frame is Frame.Binary) {
            return peer.fail(Protocol.Err.BAD_FRAME, CloseReason.Codes.CANNOT_ACCEPT)
        }
        if (frame.data.size > config.maxFrameBytes) {
            return peer.fail(Protocol.Err.TOO_LARGE, CloseReason.Codes.TOO_BIG)
        }
        when (val msg = parseClientMessage((frame as Frame.Text).readText())) {
            is ClientMessage.Signal ->
                if (!registry.relay(room, peer, Protocol.signal(msg.payload))) peer.send(Protocol.error(Protocol.Err.NO_PEER))
            ClientMessage.Ping -> {
                registry.touch(room)
                peer.send(Protocol.PONG)
            }
            null -> {
                if (++badFrames >= config.maxBadFrames) {
                    return peer.fail(Protocol.Err.BAD_FRAME, CloseReason.Codes.VIOLATED_POLICY)
                }
                peer.send(Protocol.error(Protocol.Err.BAD_FRAME))
            }
        }
    }
}

internal sealed interface ClientMessage {
    data class Signal(val payload: JsonElement) : ClientMessage
    data object Ping : ClientMessage
}

/** Returns null for anything that is not a well-formed v1 client message. Payloads are never inspected. */
internal fun parseClientMessage(text: String): ClientMessage? {
    if (nestingTooDeep(text)) return null
    val obj = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        return null
    } as? JsonObject ?: return null
    val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when (type) {
        "signal" -> obj["payload"]?.let { ClientMessage.Signal(it) }
        "ping" -> ClientMessage.Ping
        else -> null
    }
}

/**
 * kotlinx.serialization's tree reader recurses per nesting level, so a 16 KiB frame of `[[[[...`
 * would overflow the stack. WebRTC signals nest a few levels at most; anything deeper is a bad frame.
 */
internal const val MAX_JSON_DEPTH = 64

private fun nestingTooDeep(text: String): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '[', '{' -> if (++depth > MAX_JSON_DEPTH) return true
            ']', '}' -> depth--
        }
    }
    return false
}

/** First 8 hex chars of SHA-256: enough to correlate debug lines, useless for joining a room. */
internal fun shortHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
