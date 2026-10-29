package dev.localintelligence.android.hub

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption under one non-exportable Android Keystore key.
 *
 * Extracted from [KeystoreTokenStore] so that any stored secret (the HF token,
 * an OpenRouter key) uses the same key handling. Each [alias] names its own
 * Keystore key; the alias for an existing secret must never change, or the
 * ciphertext already on disk becomes undecryptable.
 *
 * WHY a fresh IV per call: GCM with a repeated IV leaks the key stream. The IV
 * is randomised by the Keystore and returned with the ciphertext.
 *
 * Failures return null rather than throwing. A lost Keystore key (lock-screen
 * change) must degrade to "no secret", never crash the caller.
 */
class KeystoreCipher(private val alias: String) {

    /** Returns (ciphertext, iv), both base64, or null when the Keystore refuses. */
    fun encryptParts(plain: String): Pair<String, String>? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(bytes, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }.getOrNull()

    /** Null for a malformed input, a wrong key, or a Keystore failure. */
    fun decryptParts(ciphertext: String, iv: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Fetches the non-exportable key, generating it on first use.
     *
     * @throws java.security.GeneralSecurityException if the Keystore refuses.
     */
    @Throws(java.security.GeneralSecurityException::class)
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // WHY randomised encryption: GCM with a fixed IV is broken for
                // more than one message. A fresh 12-byte IV per save is
                // required, and it is stored alongside the ciphertext.
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

    }
}
