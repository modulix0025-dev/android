package com.modulex.translator.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PROTOCOL_VERSION = 1

/** Hard limits applied to every inbound frame before and after decoding. */
object ProtocolLimits {
    const val MAX_FRAME_BYTES = 64 * 1024
    const val MAX_TEXT_CHARS = 8_000
    const val MAX_NAME_CHARS = 64
    const val MAX_ID_CHARS = 64
    /** Messages older/newer than this relative to the local clock are still accepted (clocks drift), but flagged. */
    const val CLOCK_SKEW_TOLERANCE_MS = 10 * 60 * 1000L
}

@Serializable
enum class MessageType {
    /** Guest -> host: authenticated join or rejoin. */
    @SerialName("hello") HELLO,
    /** Host -> guest: join accepted, with host proof. */
    @SerialName("hello_ack") HELLO_ACK,
    /** A translated utterance (reliable, sequenced). */
    @SerialName("utterance") UTTERANCE,
    /** A participant changed their language (reliable, sequenced). */
    @SerialName("lang") LANGUAGE_UPDATE,
    /** Cumulative acknowledgement of reliable messages. */
    @SerialName("ack") ACK,
    @SerialName("ping") PING,
    @SerialName("pong") PONG,
    /** Orderly session termination (reliable). */
    @SerialName("bye") BYE,
    /** Fatal protocol/auth error; the connection is closed afterwards. */
    @SerialName("error") ERROR,
}

/**
 * The single versioned wire message. Fields that do not apply to a [type] must be null; the
 * [MessageValidator] rejects messages that carry fields they should not.
 */
@Serializable
data class WireMessage(
    val v: Int = PROTOCOL_VERSION,
    val type: MessageType,
    val sessionId: String,
    val messageId: String,
    val senderId: String,
    /** Sequence number for reliable types, starting at 1 per sender and session. 0 for control messages. */
    val seq: Long = 0,
    val timestamp: Long,
    val sourceLang: String? = null,
    val targetLang: String? = null,
    /** Only present when the sender allowed sharing the original text. */
    val originalText: String? = null,
    val translatedText: String? = null,
    val displayName: String? = null,
    /** HELLO: join proof; HELLO_ACK: host proof. */
    val proof: String? = null,
    /** ACK / HELLO / HELLO_ACK: highest contiguous seq received from the peer. */
    val ackSeq: Long? = null,
    /** PING/PONG correlation. */
    val nonce: String? = null,
    val errorCode: String? = null,
)

object WireCodec {
    val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(m: WireMessage): ByteArray = json.encodeToString(WireMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    /** Decodes a frame; returns null for anything malformed or oversized. */
    fun decode(frame: ByteArray): WireMessage? {
        if (frame.isEmpty() || frame.size > ProtocolLimits.MAX_FRAME_BYTES) return null
        return runCatching { json.decodeFromString(WireMessage.serializer(), frame.toString(Charsets.UTF_8)) }.getOrNull()
    }
}

sealed interface Validation {
    data object Ok : Validation
    data class Rejected(val reason: String) : Validation
}

object MessageValidator {
    private val ID_RE = Regex("^[A-Za-z0-9_-]{1,${ProtocolLimits.MAX_ID_CHARS}}$")
    private val LANG_RE = Regex("^[a-z]{2,3}(-[A-Za-z]{2,4})?$")

    private val reliable = setOf(MessageType.UTTERANCE, MessageType.LANGUAGE_UPDATE, MessageType.BYE)

    fun isReliable(type: MessageType) = type in reliable

    fun validate(m: WireMessage, expectedSessionId: String, expectedPeerId: String?): Validation {
        fun reject(r: String) = Validation.Rejected(r)
        if (m.v != PROTOCOL_VERSION) return reject("unsupported protocol version ${m.v}")
        if (m.sessionId != expectedSessionId) return reject("session mismatch")
        if (!ID_RE.matches(m.messageId)) return reject("bad message id")
        if (!ID_RE.matches(m.senderId)) return reject("bad sender id")
        if (expectedPeerId != null && m.senderId != expectedPeerId) return reject("unexpected sender")
        if (m.timestamp <= 0) return reject("bad timestamp")
        if (isReliable(m.type)) {
            if (m.seq < 1) return reject("reliable message without sequence number")
        } else if (m.seq != 0L) return reject("control message with sequence number")
        m.sourceLang?.let { if (!LANG_RE.matches(it)) return reject("bad source language") }
        m.targetLang?.let { if (!LANG_RE.matches(it)) return reject("bad target language") }
        if ((m.originalText?.length ?: 0) > ProtocolLimits.MAX_TEXT_CHARS) return reject("original text too long")
        if ((m.translatedText?.length ?: 0) > ProtocolLimits.MAX_TEXT_CHARS) return reject("translated text too long")
        if ((m.displayName?.length ?: 0) > ProtocolLimits.MAX_NAME_CHARS) return reject("display name too long")
        if ((m.proof?.length ?: 0) > 128 || (m.nonce?.length ?: 0) > 64 || (m.errorCode?.length ?: 0) > 64) return reject("field too long")
        if (m.ackSeq != null && m.ackSeq < 0) return reject("bad ack")

        val textFields = m.originalText != null || m.translatedText != null
        when (m.type) {
            MessageType.HELLO -> {
                if (m.proof == null) return reject("hello without proof")
                if (m.sourceLang == null || m.targetLang == null) return reject("hello without languages")
                if (textFields) return reject("unexpected text")
            }
            MessageType.HELLO_ACK -> {
                if (m.proof == null) return reject("hello_ack without proof")
                if (textFields) return reject("unexpected text")
            }
            MessageType.UTTERANCE -> {
                if (m.translatedText.isNullOrBlank()) return reject("utterance without translation")
                if (m.sourceLang == null || m.targetLang == null) return reject("utterance without languages")
            }
            MessageType.LANGUAGE_UPDATE -> {
                if (m.sourceLang == null || m.targetLang == null) return reject("language update without languages")
                if (textFields) return reject("unexpected text")
            }
            MessageType.ACK -> if (m.ackSeq == null) return reject("ack without ackSeq")
            MessageType.PING, MessageType.PONG -> if (m.nonce == null) return reject("ping without nonce")
            MessageType.BYE, MessageType.ERROR -> if (textFields) return reject("unexpected text")
        }
        return Validation.Ok
    }
}
