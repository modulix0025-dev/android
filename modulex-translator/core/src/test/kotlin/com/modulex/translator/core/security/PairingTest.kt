package com.modulex.translator.core.security

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    private val fp = "a".repeat(64)

    private fun lan(expires: Long = System.currentTimeMillis() + 60_000) = PairingPayload(
        sessionId = SecureIds.newId(), secret = SecureIds.randomBytes(32), transport = TransportKind.LAN,
        hostName = "Amal's phone & co", hostFingerprint = fp, address = "192.168.1.20", port = 47100, expiresAtMillis = expires,
    )

    @Test
    fun qrPayloadRoundTrips() {
        val p = lan()
        val parsed = PairingPayload.parse(p.toUri())
        assertIs<PairingPayload.Companion.ParseResult.Ok>(parsed)
        assertEquals(p, parsed.payload)
        assertContentEquals(p.secret, parsed.payload.secret)
        assertEquals("Amal's phone & co", parsed.payload.hostName)
    }

    @Test
    fun rejectsExpiredForeignAndIncompletePayloads() {
        fun invalid(s: String) = assertIs<PairingPayload.Companion.ParseResult.Invalid>(PairingPayload.parse(s))
        invalid(lan(expires = System.currentTimeMillis() - 1).toUri())
        invalid("https://example.com/pair?v=1")
        invalid("modulex://pair?v=2&s=x")
        invalid(lan().toUri().replace("&f=$fp", ""))
        invalid(lan().toUri().replace(Regex("&k=[^&]+"), "&k=AAAA"))
        invalid(lan().toUri().replace("p=47100", "p=70000"))
        invalid("modulex://pair?" + "x".repeat(2000))
        // Internet sessions must use secure signaling.
        val net = PairingPayload(SecureIds.newId(), SecureIds.randomBytes(32), TransportKind.INTERNET, "n",
            signalingUrl = "ws://insecure.example", room = "r", expiresAtMillis = System.currentTimeMillis() + 60_000)
        invalid(net.toUri())
        assertIs<PairingPayload.Companion.ParseResult.Ok>(PairingPayload.parse(net.copy(signalingUrl = "wss://signal.example").toUri()))
    }

    @Test
    fun proofsBindSessionParticipantsAndFingerprints() {
        val secret = SecureIds.randomBytes(32)
        val g = "b".repeat(64)
        val p = SessionAuth.joinProof(secret, "sess", "guest", g, fp)
        assertTrue(SessionAuth.verify(p, SessionAuth.joinProof(secret, "sess", "guest", g.uppercase(), fp)))
        assertFalse(SessionAuth.verify(p, SessionAuth.joinProof(secret, "sess", "guest", g, "c".repeat(64))))
        assertFalse(SessionAuth.verify(p, SessionAuth.joinProof(secret, "sess2", "guest", g, fp)))
        assertFalse(SessionAuth.verify(p, SessionAuth.joinProof(SecureIds.randomBytes(32), "sess", "guest", g, fp)))
        assertFalse(SessionAuth.verify(p, null))
        // Host proof is domain separated from the join proof.
        assertNotEquals(p, SessionAuth.hostProof(secret, "sess", "guest", "guest", g, fp))
    }

    @Test
    fun shortAuthStringIsSixDigitsAndStable() {
        val secret = SecureIds.randomBytes(32)
        val a = SessionAuth.shortAuthString(secret, "s", fp, "b".repeat(64))
        assertEquals(6, a.length)
        assertTrue(a.all { it.isDigit() })
        assertEquals(a, SessionAuth.shortAuthString(secret, "s", fp.uppercase(), "b".repeat(64)))
    }

    @Test
    fun pairingCodesNormaliseAndDeriveDeterministically() {
        val code = SecureIds.newPairingCode()
        assertEquals(11, code.length)
        assertEquals(code, SecureIds.normalisePairingCode(code.lowercase().replace("-", " ")))
        assertEquals("01234-56789", SecureIds.normalisePairingCode("oi234-56789"))
        assertNull(SecureIds.normalisePairingCode("12345"))
        assertNull(SecureIds.normalisePairingCode("UUUUU-UUUUU")) // U is not Crockford
        val (s1, r1) = SessionAuth.deriveFromPairingCode(code)
        val (s2, r2) = SessionAuth.deriveFromPairingCode(code.lowercase())
        assertContentEquals(s1, s2)
        assertEquals(r1, r2)
        assertEquals(32, s1.size)
        // Room id must not reveal the secret.
        assertFalse(SecureIds.b64(s1).startsWith(r1))
    }

    @Test
    fun idsAreUniqueAndUrlSafe() {
        val ids = (1..1000).map { SecureIds.newId() }.toSet()
        assertEquals(1000, ids.size)
        assertTrue(ids.all { Regex("^[A-Za-z0-9_-]{22}$").matches(it) })
    }
}
