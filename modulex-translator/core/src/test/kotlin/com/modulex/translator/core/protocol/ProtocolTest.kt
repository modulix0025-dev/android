package com.modulex.translator.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    private fun msg(type: MessageType = MessageType.UTTERANCE, seq: Long = 1) = WireMessage(
        type = type, sessionId = "session123456789", messageId = "msg1", senderId = "peer1", seq = seq,
        timestamp = 1_700_000_000_000, sourceLang = "ar", targetLang = "en", translatedText = "Hello", originalText = "مرحبا",
    )

    private fun valid(m: WireMessage) = MessageValidator.validate(m, "session123456789", "peer1")

    @Test
    fun codecRoundTripsUnicode() {
        val m = msg()
        assertEquals(m, WireCodec.decode(WireCodec.encode(m)))
    }

    @Test
    fun codecRejectsMalformedUnknownAndOversized() {
        assertNull(WireCodec.decode("not json".toByteArray()))
        assertNull(WireCodec.decode(ByteArray(0)))
        assertNull(WireCodec.decode(ByteArray(ProtocolLimits.MAX_FRAME_BYTES + 1) { 'a'.code.toByte() }))
        // Unknown fields are rejected rather than silently ignored.
        val withExtra = String(WireCodec.encode(msg())).replaceFirst("{", "{\"evil\":1,")
        assertNull(WireCodec.decode(withExtra.toByteArray()))
        assertNull(WireCodec.decode(String(WireCodec.encode(msg())).replace("\"utterance\"", "\"bogus\"").toByteArray()))
    }

    @Test
    fun validatorAcceptsWellFormedMessages() {
        assertIs<Validation.Ok>(valid(msg()))
        assertIs<Validation.Ok>(valid(msg(MessageType.ACK, 0).copy(translatedText = null, originalText = null, sourceLang = null, targetLang = null, ackSeq = 3)))
        assertIs<Validation.Ok>(valid(msg(MessageType.PING, 0).copy(translatedText = null, originalText = null, nonce = "n")))
    }

    @Test
    fun validatorRejectsBadMessages() {
        fun bad(m: WireMessage) = assertIs<Validation.Rejected>(valid(m))
        bad(msg().copy(v = 2))
        bad(msg().copy(sessionId = "other"))
        bad(msg().copy(senderId = "intruder"))
        bad(msg().copy(messageId = "has space"))
        bad(msg().copy(seq = 0))
        bad(msg(MessageType.PING, 5).copy(nonce = "n", translatedText = null, originalText = null))
        bad(msg().copy(translatedText = "x".repeat(ProtocolLimits.MAX_TEXT_CHARS + 1)))
        bad(msg().copy(translatedText = " "))
        bad(msg().copy(sourceLang = "arabic!"))
        bad(msg(MessageType.ACK, 0).copy(translatedText = null, originalText = null))
        bad(msg(MessageType.LANGUAGE_UPDATE, 2)) // carries text it should not
        bad(msg(MessageType.HELLO, 0).copy(translatedText = null, originalText = null)) // no proof
        bad(msg().copy(timestamp = 0))
        bad(msg().copy(displayName = "n".repeat(100)))
    }

    @Test
    fun reliableTypesAreSequenced() {
        assertTrue(MessageValidator.isReliable(MessageType.UTTERANCE))
        assertTrue(MessageValidator.isReliable(MessageType.BYE))
        assertTrue(!MessageValidator.isReliable(MessageType.ACK))
    }
}
