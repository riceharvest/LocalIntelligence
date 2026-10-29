package dev.localintelligence.android.hub

import android.content.Context
import dev.localintelligence.core.hub.HubTokenSource

/**
 * Stores the optional HuggingFace read token encrypted with a hardware-backed
 * Android Keystore key.
 *
 * ## Why Keystore directly and not `EncryptedSharedPreferences`
 *
 * The project forbids new Gradle dependencies, and `androidx.security:
 * security-crypto` is not on the classpath. `EncryptedSharedPreferences` is a
 * thin wrapper over exactly what is implemented here: an AES key that never
 * leaves the Keystore, and ciphertext in the preferences file. Using
 * `javax.crypto` plus `android.security.keystore` gets the same property with
 * zero new dependencies — both are platform API available since API 23, and
 * minSdk here is 26.
 *
 * ## What "encrypted" means here, precisely
 *
 * The AES key is generated inside the TEE/StrongBox on devices that have one
 * and is **non-exportable**: the app can hand it to the Keystore to decrypt,
 * and cannot read the key bytes out. What lands in the preferences XML is
 * `iv:ciphertext`, both base64, neither containing the token in plaintext. A
 * user who pulls `shared_prefs` off a non-rooted device with `adb backup`, or
 * an attacker with a filesystem-level read, gets ciphertext.
 *
 * ## What it does NOT protect against
 *
 * A compromised app process can call [token]. Nothing at this layer can change
 * that, and pretending otherwise would be the dishonest option. The threat
 * model is a stolen backup or a curious file dump, not code execution inside
 * the app — which is already game over.
 *
 * ## Unverified
 *
 * **The Keystore path has no automated test.** `KeyGenParameterSpec` needs a
 * real Android Keystore, which does not exist on the JVM, and the project's
 * test suites are JVM-only. The encryption round-trip, the IV handling and the
 * key-store-missing fallback are therefore untested code. The *no-token* path —
 * which is the default and the one every ungated user takes — is tested on the
 * JVM in `:core`.
 */
class KeystoreTokenStore(
    context: Context,
    private val prefsName: String = PREFS_NAME,
) : HubTokenSource {

    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** The AES primitive, shared with every other encrypted secret. See [KeystoreCipher]. */
    private val cipher = KeystoreCipher(KEY_ALIAS)

    /**
     * @return the token, or null when none is stored, the Keystore is
     *   unavailable, or decryption fails for any reason.
     *
     * WHY a failed decrypt returns null rather than throwing: a user whose
     * Keystore key was invalidated by a lock-screen change must still be able
     * to download public models. The gate is optional by construction.
     */
    override fun token(): String? {
        val stored = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return cipher.decryptParts(stored, iv)?.takeIf { it.isNotBlank() }
    }

    /**
     * Stores [token], or clears the stored one when it is null or blank.
     *
     * A blank token clears rather than storing: an empty `Bearer ` header makes
     * HF answer 401, which the user would read as "my token is wrong" when in
     * fact they never had one.
     */
    fun save(token: String?) {
        if (token.isNullOrBlank()) {
            clear()
            return
        }
        val (ciphertext, iv) = cipher.encryptParts(token) ?: return
        prefs.edit()
            .putString(KEY_CIPHERTEXT, ciphertext)
            .putString(KEY_IV, iv)
            .apply()
    }

    /** Removes the stored token. The Keystore key is left; it is harmless. */
    fun clear() {
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

    /** True when a token is stored. Does not decrypt, so it cannot fail. */
    fun hasToken(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    private companion object {
        /** Must not change: the token already stored on a device is undecryptable under another alias. */
        const val KEY_ALIAS = "localintelligence_hf_token"
        const val PREFS_NAME = "localintelligence_hub"
        const val KEY_CIPHERTEXT = "hf_token_ciphertext"
        const val KEY_IV = "hf_token_iv"
    }
}
