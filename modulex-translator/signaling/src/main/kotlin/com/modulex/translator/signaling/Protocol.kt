package com.modulex.translator.signaling

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wire format of protocol v1. All frames are UTF-8 JSON text frames. */
object Protocol {
    const val VERSION_PATH = "/v1/rooms/{room}"
    val ROOM_ID = Regex("^[A-Za-z0-9_-]{16,64}$")

    object Err {
        const val ROOM_FULL = "room_full"
        const val BAD_ROOM = "bad_room"
        const val BAD_FRAME = "bad_frame"
        const val TOO_LARGE = "too_large"
        const val RATE_LIMITED = "rate_limited"
        const val NO_PEER = "no_peer"
        const val SERVER_FULL = "server_full"
    }

    fun joined(peers: Int): String = buildJsonObject { put("type", "joined"); put("peers", peers) }.toString()
    val PEER_JOINED: String = buildJsonObject { put("type", "peer-joined") }.toString()
    val PEER_LEFT: String = buildJsonObject { put("type", "peer-left") }.toString()
    val PONG: String = buildJsonObject { put("type", "pong") }.toString()
    fun error(code: String): String = buildJsonObject { put("type", "error"); put("code", code) }.toString()
    fun signal(payload: JsonElement): String =
        JsonObject(mapOf("type" to JsonPrimitive("signal"), "payload" to payload)).toString()
}
