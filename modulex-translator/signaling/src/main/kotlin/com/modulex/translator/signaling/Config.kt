package com.modulex.translator.signaling

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Optional TLS listener. Secrets come only from the environment, never from the repository. */
data class TlsConfig(
    val keystorePath: String,
    val keystorePassword: String,
    val keyAlias: String,
    val port: Int,
) {
    override fun toString(): String = "TlsConfig(keystorePath=$keystorePath, keyAlias=$keyAlias, port=$port)"
}

/**
 * All relay limits. Logic receives this object (it never reads the environment itself),
 * so tests can inject tight limits.
 */
data class Config(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    /** Largest accepted text frame, in bytes. Bigger → `too_large` + close 1009. */
    val maxFrameBytes: Int = 16 * 1024,
    /** Maximum number of rooms existing at once. Exceeded → `server_full`. */
    val maxRooms: Int = 10_000,
    /** A room with no client messages for this long is dropped and both sockets closed with 1001. */
    val roomIdleTimeout: Duration = 10.minutes,
    /** Upgrade attempts on /v1/rooms/... per client IP per minute. Exceeded → HTTP 429. */
    val joinRatePerMinute: Int = 30,
    /** Client messages per connection per [msgRateWindow]. Exceeded → `rate_limited` + close 1008. */
    val msgRatePerWindow: Int = 50,
    val msgRateWindow: Duration = 10.seconds,
    /** Bad frames tolerated; the [maxBadFrames]-th one also closes the socket with 1008. */
    val maxBadFrames: Int = 5,
    val pingPeriod: Duration = 20.seconds,
    val pingTimeout: Duration = 30.seconds,
    /** Use the first X-Forwarded-For value as the client IP (only behind a trusted reverse proxy). */
    val trustProxyHeaders: Boolean = false,
    val tls: TlsConfig? = null,
) {
    init {
        require(maxFrameBytes > 0) { "MAX_FRAME_BYTES must be > 0" }
        require(maxRooms > 0) { "MAX_ROOMS must be > 0" }
        require(roomIdleTimeout.isPositive()) { "ROOM_IDLE_SECONDS must be > 0" }
        require(joinRatePerMinute > 0) { "JOIN_RATE_PER_MINUTE must be > 0" }
        require(msgRatePerWindow > 0) { "MSG_RATE_PER_10S must be > 0" }
        require(maxBadFrames > 0)
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun str(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun int(name: String, default: Int): Int =
                str(name)?.let { it.toIntOrNull() ?: error("$name must be an integer") } ?: default

            val tlsPort = str("TLS_PORT")?.let { it.toIntOrNull() ?: error("TLS_PORT must be an integer") }
            val tls = tlsPort?.let {
                TlsConfig(
                    keystorePath = str("TLS_KEYSTORE_PATH") ?: error("TLS_PORT set but TLS_KEYSTORE_PATH missing"),
                    keystorePassword = env["TLS_KEYSTORE_PASSWORD"] ?: error("TLS_PORT set but TLS_KEYSTORE_PASSWORD missing"),
                    keyAlias = str("TLS_KEY_ALIAS") ?: error("TLS_PORT set but TLS_KEY_ALIAS missing"),
                    port = it,
                )
            }
            return Config(
                host = str("HOST") ?: "0.0.0.0",
                port = int("PORT", 8080),
                maxFrameBytes = int("MAX_FRAME_BYTES", 16 * 1024),
                maxRooms = int("MAX_ROOMS", 10_000),
                roomIdleTimeout = int("ROOM_IDLE_SECONDS", 600).seconds,
                joinRatePerMinute = int("JOIN_RATE_PER_MINUTE", 30),
                msgRatePerWindow = int("MSG_RATE_PER_10S", 50),
                trustProxyHeaders = str("TRUST_PROXY_HEADERS").equals("true", ignoreCase = true),
                tls = tls,
            )
        }
    }
}
