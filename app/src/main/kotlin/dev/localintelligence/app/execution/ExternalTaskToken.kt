package dev.localintelligence.app.execution

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The shared secret an app must present to start a scheduled task from outside.
 *
 * ## Why a token and not a custom permission
 *
 * A permission declared by this app can be held only by an app that asks for it
 * in its own manifest. Tasker and adb do not, so they could never hold it, and the
 * feature would be unusable. A token travels in the broadcast instead, and the
 * user copies it into the other app.
 *
 * ## What the token is
 *
 * 32 random bytes, hex encoded. It is stored through the same encrypted
 * [SettingDef.Secret] path as the API keys, so it is never written in plain text.
 * Regenerating replaces it, and the old one stops working at once.
 */
object ExternalTaskToken {
    private const val BYTES = 32

    /** A fresh token. [SecureRandom], because the token is the only thing guarding the trigger. */
    fun generate(): String {
        val bytes = ByteArray(BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * True when [presented] equals [stored], compared in constant time.
     *
     * A missing value on either side is a refusal. Comparing with `==` would stop
     * at the first differing character, which lets a caller learn the token one
     * character at a time from how long the check takes.
     */
    fun matches(stored: String?, presented: String?): Boolean {
        if (stored.isNullOrEmpty() || presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            stored.toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8),
        )
    }
}
