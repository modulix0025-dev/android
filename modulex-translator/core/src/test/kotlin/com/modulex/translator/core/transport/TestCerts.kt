package com.modulex.translator.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory

/** A device identity like the one the app creates in the Android Keystore (EC P-256, self-signed). */
class TestIdentity(cn: String) {
    val certificate: X509Certificate
    val keyManagers: Array<KeyManager>

    init {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=$cn")
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000L), name, kp.public)
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("id", kp.private, CharArray(0), arrayOf(certificate))
        }
        keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }.keyManagers
    }

    val fingerprint: String get() = com.modulex.translator.core.security.Fingerprints.sha256Hex(certificate.encoded)
}

/** Two connected in-memory byte pipes, standing in for a socket pair. */
class PipePair {
    private val aIn = PipedInputStream(1 shl 16)
    private val bOut = PipedOutputStream(aIn)
    private val bIn = PipedInputStream(1 shl 16)
    private val aOut = PipedOutputStream(bIn)
    val a = aIn to aOut
    val b = bIn to bOut
}

object TestTls {
    /** Performs a real TLS 1.3 handshake between host and guest over pipes. */
    suspend fun connect(host: TestIdentity, guest: TestIdentity, pinnedHostFp: String = host.fingerprint): Pair<FrameChannel, FrameChannel> = coroutineScope {
        val pipes = PipePair()
        val serverCtx = PeerTrust.context(host.keyManagers, PeerTrust.anyClientRecorded())
        val clientCtx = PeerTrust.context(guest.keyManagers, PeerTrust.pinnedServer(pinnedHostFp))
        val s = async(Dispatchers.IO) {
            val t = TlsStream(PeerTrust.serverEngine(serverCtx), pipes.a.first, pipes.a.second)
            t.handshake(); TlsFrameChannel(t, "test")
        }
        val c = async(Dispatchers.IO) {
            val t = TlsStream(PeerTrust.clientEngine(clientCtx), pipes.b.first, pipes.b.second)
            t.handshake(); TlsFrameChannel(t, "test")
        }
        s.await() to c.await()
    }
}

suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
