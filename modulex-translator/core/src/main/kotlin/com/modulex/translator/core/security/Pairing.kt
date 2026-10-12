package com.modulex.translator.core.security

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Random identifiers and secrets from the platform CSPRNG. */
object SecureIds {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** 128-bit identifier, URL-safe base64 without padding (22 chars). */
    fun newId(): String = b64(randomBytes(16))

    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    /**
     * Human-enterable pairing code: 10 Crockford base32 characters (50 bits) shown as XXXXX-XXXXX.
     * Used only for Internet sessions where a QR code cannot be scanned.
     */
    fun newPairingCode(): String {
        val bytes = randomBytes(7)
        var bits = 0L
        for (i in 0 until 7) bits = (bits shl 8) or (bytes[i].toLong() and 0xff)
        val sb = StringBuilder()
        for (i in 0 until 10) {
            sb.append(CROCKFORD[((bits shr (i * 5)) and 31).toInt()])
        }
        return sb.substring(0, 5) + "-" + sb.substring(5)
    }

    /** Normalises a typed code (case, separators, ambiguous letters) or returns null if invalid. */
    fun normalisePairingCode(input: String): String? {
        val cleaned = input.uppercase().filter { it != '-' && !it.isWhitespace() }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
            .joinToString("")
        if (cleaned.length != 10 || cleaned.any { it !in CROCKFORD }) return null
        return cleaned.substring(0, 5) + "-" + cleaned.substring(5)
    }

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
}

object Fingerprints {
    /** SHA-256 of DER bytes, lowercase hex with colons removed. */
    fun sha256Hex(der: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(der))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Normalises "AB:CD:..." (SDP style) or plain hex into lowercase hex. */
    fun normalise(fp: String): String = fp.replace(":", "").replace(" ", "").lowercase()
}

/**
 * Join/host proofs and the short authentication string (SAS).
 *
 * Both proofs are HMAC-SHA256 values keyed by the session secret that only the two devices know
 * (it travels in the QR code or is derived from the pairing code). They bind the session id, the
 * participant ids and both transport certificate fingerprints, so a relay or man in the middle that
 * terminates the encrypted transport separately on each side cannot produce valid proofs.
 */
object SessionAuth {
    private const val JOIN_LABEL = "modulex-join-v1"
    private const val HOST_LABEL = "modulex-host-v1"
    private const val SAS_LABEL = "modulex-sas-v1"
    private const val CODE_LABEL = "modulex-code-v1"

    fun joinProof(secret: ByteArray, sessionId: String, guestId: String, guestFp: String, hostFp: String): String =
        hmacB64(secret, JOIN_LABEL, sessionId, guestId, Fingerprints.normalise(guestFp), Fingerprints.normalise(hostFp))

    fun hostProof(secret: ByteArray, sessionId: String, hostId: String, guestId: String, guestFp: String, hostFp: String): String =
        hmacB64(secret, HOST_LABEL, sessionId, hostId, guestId, Fingerprints.normalise(guestFp), Fingerprints.normalise(hostFp))

    fun verify(expected: String, actual: String?): Boolean {
        if (actual == null) return false
        return MessageDigest.isEqual(expected.toByteArray(UTF_8), actual.toByteArray(UTF_8))
    }

    /** Six digits both users compare on screen to confirm they are connected to each other. */
    fun shortAuthString(secret: ByteArray, sessionId: String, hostFp: String, guestFp: String): String {
        val mac = hmac(secret, SAS_LABEL, sessionId, Fingerprints.normalise(hostFp), Fingerprints.normalise(guestFp))
        val v = ((mac[0].toLong() and 0x7f) shl 24) or ((mac[1].toLong() and 0xff) shl 16) or
            ((mac[2].toLong() and 0xff) shl 8) or (mac[3].toLong() and 0xff)
        return "%06d".format(v % 1_000_000)
    }

    /**
     * Derives the session secret and the signaling room id from a typed pairing code using PBKDF2
     * (platform implementation). The room id is derived with a different label so the signaling
     * server never learns the secret. A 50-bit code is weaker than a QR secret; see SECURITY.md.
     */
    fun deriveFromPairingCode(code: String): Pair<ByteArray, String> {
        val normalised = SecureIds.normalisePairingCode(code) ?: throw IllegalArgumentException("Invalid pairing code")
        val spec = javax.crypto.spec.PBEKeySpec(normalised.toCharArray(), CODE_LABEL.toByteArray(UTF_8), 120_000, 512)
        val out = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val secret = out.copyOfRange(0, 32)
        val room = SecureIds.b64(out.copyOfRange(32, 48))
        return secret to room
    }

    private fun hmac(key: ByteArray, vararg parts: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) {
            val b = p.toByteArray(UTF_8)
            // Length-prefix every field so different splits never collide.
            mac.update(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
            mac.update(b)
        }
        return mac.doFinal()
    }

    private fun hmacB64(key: ByteArray, vararg parts: String) = SecureIds.b64(hmac(key, *parts))
}

enum class TransportKind(val wire: String) {
    LAN("lan"), WIFI_DIRECT("p2p"), BLUETOOTH("bt"), INTERNET("net");

    companion object {
        fun fromWire(s: String) = entries.firstOrNull { it.wire == s }
    }
}

/**
 * Contents of the pairing QR code: `modulex://pair?v=1&...`.
 *
 * The QR code is the trust anchor for local sessions: it carries the host certificate fingerprint
 * (so the guest can pin it) and a 256-bit session secret used for mutual proofs.
 */
data class PairingPayload(
    val sessionId: String,
    val secret: ByteArray,
    val transport: TransportKind,
    val hostName: String,
    /** Host certificate SHA-256 (hex). Absent for Internet sessions (DTLS fingerprints are bound by proofs). */
    val hostFingerprint: String? = null,
    /** LAN/Wi-Fi Direct: IP address; Bluetooth: MAC is not exposed, the service UUID is derived from sessionId. */
    val address: String? = null,
    val port: Int? = null,
    /** Wi-Fi Direct group credentials, when the host created a group. */
    val p2pNetworkName: String? = null,
    val p2pPassphrase: String? = null,
    /** Internet: signaling server URL and room. */
    val signalingUrl: String? = null,
    val room: String? = null,
    val expiresAtMillis: Long,
) {
    fun toUri(): String {
        val q = linkedMapOf(
            "v" to "1",
            "s" to sessionId,
            "k" to SecureIds.b64(secret),
            "t" to transport.wire,
            "n" to hostName,
            "e" to expiresAtMillis.toString(),
        )
        hostFingerprint?.let { q["f"] = it }
        address?.let { q["a"] = it }
        port?.let { q["p"] = it.toString() }
        p2pNetworkName?.let { q["wn"] = it }
        p2pPassphrase?.let { q["wp"] = it }
        signalingUrl?.let { q["u"] = it }
        room?.let { q["r"] = it }
        return "modulex://pair?" + q.entries.joinToString("&") { (k, v) -> k + "=" + enc(v) }
    }

    override fun equals(other: Any?) = other is PairingPayload && toUri() == other.toUri()
    override fun hashCode() = toUri().hashCode()

    companion object {
        const val MAX_URI_LENGTH = 1024

        sealed interface ParseResult {
            data class Ok(val payload: PairingPayload) : ParseResult
            data class Invalid(val reason: String) : ParseResult
        }

        fun parse(uri: String, nowMillis: Long = System.currentTimeMillis()): ParseResult {
            if (uri.length > MAX_URI_LENGTH) return ParseResult.Invalid("too long")
            val u = runCatching { URI(uri) }.getOrNull() ?: return ParseResult.Invalid("not a URI")
            if (u.scheme != "modulex" || u.host != "pair") return ParseResult.Invalid("not a ModuleX pairing code")
            val params = (u.rawQuery ?: "").split("&").filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                if (i < 0) it to "" else it.substring(0, i) to dec(it.substring(i + 1))
            }
            if (params["v"] != "1") return ParseResult.Invalid("unsupported version")
            val sessionId = params["s"]?.takeIf { ID_RE.matches(it) } ?: return ParseResult.Invalid("bad session id")
            val secret = params["k"]?.let { runCatching { SecureIds.unb64(it) }.getOrNull() }
                ?.takeIf { it.size == 32 } ?: return ParseResult.Invalid("bad secret")
            val transport = params["t"]?.let { TransportKind.fromWire(it) } ?: return ParseResult.Invalid("bad transport")
            val name = params["n"]?.take(64) ?: ""
            val expires = params["e"]?.toLongOrNull() ?: return ParseResult.Invalid("missing expiry")
            if (expires < nowMillis) return ParseResult.Invalid("expired")
            val fp = params["f"]?.let { Fingerprints.normalise(it) }
            if (fp != null && !HEX64.matches(fp)) return ParseResult.Invalid("bad fingerprint")
            val port = params["p"]?.toIntOrNull()
            if (port != null && port !in 1..65535) return ParseResult.Invalid("bad port")
            val payload = PairingPayload(
                sessionId, secret, transport, name, fp, params["a"], port,
                params["wn"], params["wp"], params["u"], params["r"], expires,
            )
            val err = when (transport) {
                TransportKind.LAN, TransportKind.WIFI_DIRECT ->
                    if (fp == null || payload.address == null || port == null) "missing address or fingerprint" else null
                TransportKind.BLUETOOTH -> if (fp == null) "missing fingerprint" else null
                TransportKind.INTERNET ->
                    if (payload.signalingUrl?.startsWith("wss://") != true || payload.room == null) "missing secure signaling" else null
            }
            return if (err != null) ParseResult.Invalid(err) else ParseResult.Ok(payload)
        }

        private val ID_RE = Regex("^[A-Za-z0-9_-]{16,64}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
