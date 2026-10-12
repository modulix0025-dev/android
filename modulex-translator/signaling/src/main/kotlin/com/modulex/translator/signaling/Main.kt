package com.modulex.translator.signaling

import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore

fun main() {
    val log = LoggerFactory.getLogger("com.modulex.translator.signaling")
    val config = Config.fromEnv()

    val server = embeddedServer(
        Netty,
        configure = {
            connector {
                host = config.host
                port = config.port
            }
            config.tls?.let { tls ->
                val password = tls.keystorePassword.toCharArray()
                val keyStore = KeyStore.getInstance(File(tls.keystorePath), password)
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = tls.keyAlias,
                    keyStorePassword = { password.copyOf() },
                    privateKeyPassword = { password.copyOf() },
                ) {
                    host = config.host
                    port = tls.port
                }
            }
        },
        module = { signalingModule(config) },
    )

    log.info(
        "ModuleX signaling relay v1 on {}:{} (tls={}) maxRooms={} maxFrameBytes={}",
        config.host, config.port, config.tls?.port ?: "off", config.maxRooms, config.maxFrameBytes,
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop(1_000, 5_000) })
    server.start(wait = true)
}
