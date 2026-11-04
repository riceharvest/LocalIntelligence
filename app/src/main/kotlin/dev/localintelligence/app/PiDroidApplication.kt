package dev.localintelligence.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.localintelligence.app.crash.CrashReports
import dev.localintelligence.core.settings.CoreSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the [AppContainer] for the life of the process.
 *
 * This is the one place a container is created, and it does nothing else — no
 * tool registration, no backend warm-up, no database open. All of that is `by
 * lazy` inside [AppContainer] and stays cold until something asks for it, which
 * is the RAM budget of `docs/architecture.md` §16 expressed as code: a process
 * that has not loaded a model and has not opened a database costs almost
 * nothing.
 *
 * The file kept its original name because the manifest's `android:name` points at
 * `.LocalIntelligenceApp` and renaming the file is churn the diff does not need.
 */
class LocalIntelligenceApp : Application() {

    /**
     * The DI container. `lateinit` rather than a constructor property because the
     * application context does not exist until after `Application` is attached,
     * and taking it in the constructor is the classic way to get a half-built
     * `Context` that never sees the rest of the app's configuration.
     */
    lateinit var container: AppContainer
        private set

    /** Lives as long as the process, for the Telegram gateway's start and stop (see [onCreate]). */
    private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while the container is being built is still written down.
        CrashReports.install(this)
        CrashReports.importExitReasons(this)
        container = AppContainer(this)
        // Telegram follows the settings: it starts when it becomes wanted and stops when it stops being
        // wanted. The first value also covers a launch, so a phone that had it on reconnects when the app opens.
        gatewayScope.launch {
            container.settings.changes
                .map { LocalServerService.enabledInTechnical(container) }
                .distinctUntilChanged()
                .collect { LocalServerService.sync(this@LocalIntelligenceApp, container) }
        }
        gatewayScope.launch {
            container.settings.changes
                .map { TelegramService.wanted(container) }
                .distinctUntilChanged()
                .collect { TelegramService.sync(this@LocalIntelligenceApp, container) }
        }
        gatewayScope.launch {
            container.settings.changes
                .map { container.settings[CoreSettings.WAKE_WORD] to WakeWordService.activePhrase(container) }
                .distinctUntilChanged()
                // Only real changes: the process also starts in the background (alarms,
                // boot), where the screens and the voice service do the starting.
                .drop(1)
                .collect { WakeWordService.sync(this@LocalIntelligenceApp, container) }
        }
        // Counts started screens for the Lean memory policy (AppContainer.leanEjectIfBackgrounded).
        // A rotation stops and starts the activity too, so the eject is skipped while it changes
        // configuration: rotating must not free the model.
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                container.activitiesStarted++
            }

            override fun onActivityStopped(activity: Activity) {
                container.activitiesStarted = (container.activitiesStarted - 1).coerceAtLeast(0)
                if (!activity.isChangingConfigurations) container.leanEjectIfBackgrounded()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
