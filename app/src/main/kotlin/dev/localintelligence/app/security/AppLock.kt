package dev.localintelligence.app.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.security.PinAttempts
import dev.localintelligence.core.security.PinCode
import dev.localintelligence.core.settings.CoreSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The answer to one PIN check. */
sealed interface PinCheck {
    data object Correct : PinCheck
    data object Wrong : PinCheck

    /** Too many wrong PINs: no check is made until [remainingMillis] has passed. */
    data class Waiting(val remainingMillis: Long) : PinCheck
}

/**
 * Whether the app is locked right now, and the PIN check behind the lock.
 *
 * WHY ONE OBJECT FOR THE WHOLE PROCESS: the lock has to hold across MainActivity,
 * AssistActivity and rotation. [AppContainer] owns the single instance.
 *
 * WHY THE STATE IS IN SETTINGS: the switch, the PIN hash and the wrong-PIN count
 * all go through [SettingsStore], so there is no preferences file of its own.
 * [enabled] fails closed: a switch that is on keeps the lock on even when the
 * stored hash can no longer be read, and then no PIN matches.
 *
 * WHEN IT LOCKS: at process start, when [enabled] is true, and again when the
 * app comes back after every activity has been stopped for [GRACE_MILLIS]. A
 * short trip out (a share sheet, a rotation) does not lock. Background tracking
 * uses activity callbacks because `lifecycle-process` is not a dependency here.
 *
 * Scheduled and background runs are not touched by this: they never go through
 * the lock screen, so an unattended run keeps going while the app is locked.
 */
class AppLock(context: Context, private val settings: SettingsStore) {

    private val _locked = MutableStateFlow(enabled())

    /** True while the lock screen must cover the app. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var started = 0

    /** Monotonic time of the last moment with no activity started. -1 before any. */
    private var stoppedAt = -1L

    init {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(Foreground())
    }

    /** True when the switch is on. */
    fun enabled(): Boolean = settings[CoreSettings.APP_LOCK]

    /** The fingerprint or face option, which only applies while the lock is on. */
    fun biometricEnabled(): Boolean = enabled() && settings[CoreSettings.APP_LOCK_BIOMETRIC]

    /** Called when the lock screen is passed: a correct PIN, or a biometric match. */
    fun unlock() {
        _locked.value = false
    }

    /**
     * Checks [pin] against the saved hash, and counts a wrong one. The hash is slow
     * on purpose, so it runs off the main thread. This does not unlock: the caller
     * decides, so the settings screen can reuse the same check.
     */
    suspend fun check(pin: String): PinCheck = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val attempts = PinAttempts.decode(settings.stored(CoreSettings.APP_LOCK_ATTEMPTS))
        attempts.waitRemainingMillis(now).takeIf { it > 0 }?.let { return@withContext PinCheck.Waiting(it) }

        if (PinCode.matches(pin, settings.stored(CoreSettings.APP_LOCK_PIN))) {
            settings.set(CoreSettings.APP_LOCK_ATTEMPTS, PinAttempts.NONE.encode())
            return@withContext PinCheck.Correct
        }
        val next = attempts.afterWrong(now)
        settings.set(CoreSettings.APP_LOCK_ATTEMPTS, next.encode())
        next.waitRemainingMillis(now).takeIf { it > 0 }?.let { PinCheck.Waiting(it) } ?: PinCheck.Wrong
    }

    /**
     * Turns the lock on with [pin]. Returns false, and changes nothing, when the
     * PIN is not 4 to 12 digits or the Keystore refuses to store the hash. The
     * switch is flipped only after the hash is saved, so the lock is never on
     * without a PIN behind it.
     */
    suspend fun enable(pin: String): Boolean = withContext(Dispatchers.Default) {
        if (!PinCode.isValid(pin)) return@withContext false
        if (!settings.setSecret(CoreSettings.APP_LOCK_PIN, PinCode.hash(pin))) return@withContext false
        settings.set(CoreSettings.APP_LOCK_ATTEMPTS, PinAttempts.NONE.encode())
        settings.set(CoreSettings.APP_LOCK, true)
        true
    }

    /** Turns the lock off and forgets the PIN. The caller checks the PIN first. */
    suspend fun disable() = withContext(Dispatchers.Default) {
        settings.set(CoreSettings.APP_LOCK, false)
        settings.clearSecret(CoreSettings.APP_LOCK_PIN)
        settings.set(CoreSettings.APP_LOCK_ATTEMPTS, PinAttempts.NONE.encode())
        _locked.value = false
    }

    private fun noteStarted() {
        started += 1
        val away = if (stoppedAt >= 0) SystemClock.elapsedRealtime() - stoppedAt else 0L
        if (started == 1 && away >= GRACE_MILLIS && enabled()) {
            _locked.value = true
        }
    }

    private fun noteStopped() {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) stoppedAt = SystemClock.elapsedRealtime()
    }

    /** Counts started activities, so "in the background" means none is on screen. */
    private inner class Foreground : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) = noteStarted()
        override fun onActivityStopped(activity: Activity) = noteStopped()
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        /** How long the app may be out of sight before it locks again. */
        const val GRACE_MILLIS = 60_000L
    }
}
