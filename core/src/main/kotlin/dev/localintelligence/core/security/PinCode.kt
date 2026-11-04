package dev.localintelligence.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN rules and the salted PBKDF2 hash the app lock stores. Pure JVM, so the
 * hashing and comparison can be read and checked without a phone.
 *
 * WHY PBKDF2 and not a plain digest: a PIN has at most 10^12 values, and a fast
 * hash of a stolen value is brute-forced in seconds. The iteration count makes
 * each guess cost real time on the phone.
 *
 * WHY the version prefix: the stored form names its own parameters, so a later
 * change to the iteration count or the algorithm can be recognised instead of
 * silently failing every unlock.
 *
 * The hash is never logged, and the PIN is never stored: only the hash goes to
 * settings, through the Keystore-backed secret store.
 */
object PinCode {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 12

    /** OWASP's PBKDF2-HMAC-SHA256 guidance. Each guess costs real time on the phone. */
    const val ITERATIONS = 210_000

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "v1-pbkdf2-sha256"
    private val random = SecureRandom()

    /** Digits only, between [MIN_LENGTH] and [MAX_LENGTH] long. */
    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** The stored form: `v1-pbkdf2-sha256:<iterations>:<salt>:<hash>`, base64 for the last two. */
    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = derive(pin, salt, ITERATIONS)
        return listOf(PREFIX, ITERATIONS.toString(), encode(salt), encode(digest)).joinToString(":")
    }

    /**
     * True when [pin] hashes to [stored]. A malformed or blank [stored] is false,
     * so a lost or unreadable hash can never unlock the app.
     */
    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = decode(parts[2]) ?: return false
        val expected = decode(parts[3]) ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()
}

/**
 * Wrong-PIN bookkeeping. Five wrong PINs in a row start a wait of 30 seconds, and
 * each later lockout doubles it. A correct PIN clears everything.
 *
 * WHY persisted: the count lives in settings, not in memory, so force-stopping
 * the app does not reset the wait. [encode] and [decode] are the stored text form.
 */
data class PinAttempts(
    /** Wrong PINs since the last lockout or the last correct PIN. */
    val failures: Int = 0,
    /** Lockouts so far. The wait doubles with each one. */
    val lockouts: Int = 0,
    /** Wall-clock millis before which no PIN is checked. 0 when not locked out. */
    val lockedUntilMillis: Long = 0L,
) {
    fun waitRemainingMillis(now: Long): Long = (lockedUntilMillis - now).coerceAtLeast(0L)

    /** The state after one wrong PIN at [now]. */
    fun afterWrong(now: Long): PinAttempts {
        val count = failures + 1
        if (count < FAILURES_PER_LOCKOUT) return copy(failures = count)
        // The shift is capped so a very long streak cannot overflow the Long.
        val wait = BASE_WAIT_MILLIS shl minOf(lockouts, MAX_DOUBLINGS)
        return PinAttempts(failures = 0, lockouts = lockouts + 1, lockedUntilMillis = now + wait)
    }

    fun encode(): String = "$failures;$lockouts;$lockedUntilMillis"

    companion object {
        const val FAILURES_PER_LOCKOUT = 5
        const val BASE_WAIT_MILLIS = 30_000L
        const val MAX_DOUBLINGS = 30
        val NONE = PinAttempts()

        /** Anything unreadable reads as [NONE]: the stored text is only ever written by this class. */
        fun decode(raw: String?): PinAttempts {
            val parts = raw?.split(";") ?: return NONE
            if (parts.size != 3) return NONE
            val failures = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return NONE
            val lockouts = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return NONE
            val until = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return NONE
            return PinAttempts(failures, lockouts, until)
        }
    }
}
