package com.modulex.translator.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.modulex.translator.core.security.Fingerprints
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.security.auth.x500.X500Principal

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * This phone's identity for peer connections: an EC P-256 key that never leaves the Android
 * Keystore, with a self-signed certificate. Its SHA-256 fingerprint is what the other phone pins
 * (from the QR code) and what session proofs are bound to.
 */
class DeviceIdentity {
    private val alias = "modulex-peer-identity-v1"
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    fun ensure(): X509Certificate {
        (keyStore.getCertificate(alias) as? X509Certificate)?.let { cert ->
            if (runCatching { cert.checkValidity() }.isSuccess) return cert
            keyStore.deleteEntry(alias)
        }
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 10) }.time
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=ModuleX Device"))
            .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
            .setCertificateNotBefore(start)
            .setCertificateNotAfter(end)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        return keyStore.getCertificate(alias) as X509Certificate
    }

    val fingerprint: String get() = Fingerprints.sha256Hex(ensure().encoded)

    /** Key managers presenting this identity in TLS (keys stay in the Keystore). */
    fun keyManagers(): Array<KeyManager> {
        ensure()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, null)
        return kmf.keyManagers
    }

    /** Creates a fresh identity (e.g. when the user resets pairing data). */
    @Synchronized
    fun reset() {
        keyStore.deleteEntry(alias)
    }
}

/** AES-256-GCM encryption with a non-exportable Keystore key, for secrets kept in preferences. */
class SecretBox(private val alias: String = "modulex-settings-secret-v1") {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(c.iv) + ":" + Base64.getEncoder().encodeToString(ct)
    }

    fun decrypt(box: String): String? = runCatching {
        val (iv, ct) = box.split(":").let { Base64.getDecoder().decode(it[0]) to Base64.getDecoder().decode(it[1]) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}
