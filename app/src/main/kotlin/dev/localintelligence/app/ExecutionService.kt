package dev.localintelligence.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.localintelligence.core.agent.StepTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A foreground service that exists only while a task is running.
 *
 * ```
 * user starts a task -> service starts -> task finishes -> service stops
 * ```
 *
 * That is `docs/architecture.md` §17 verbatim, and the whole design of this class
 * is in the third clause. This is **not** an immortal daemon. Android deliberately
 * restricts background foreground-service starts (API 31+ throws
 * `ForegroundServiceStartNotAllowedException` from the background) and Android 15+
 * puts a six-hour ceiling on `dataSync`. Both are reasons to stop promptly, not
 * reasons to hold on.
 *
 * ## Why `dataSync`
 *
 * The run is a bounded, user-initiated chunk of work that moves state into and
 * out of the device through a local model. The three candidates:
 *
 *  - `dataSync` — correct fit. A user-initiated task with a definite end. Carries
 *    a six-hour/24h budget on Android 15+, which a local agent run never reaches
 *    because the service stops when the task does.
 *  - `shortService` — a hard 3-minute cap (API 34+). Importing a 2 GB GGUF over
 *    USB and then generating on a phone routinely exceeds three minutes, so this
 *    would kill legitimate runs.
 *  - `specialUse` — requires a Play Console declaration and a justification
 *    questionnaire. Claiming it to dodge the `dataSync` timer is exactly the kind
 *    of thing that gets an app removed.
 *
 * ## How the service is guaranteed to stop
 *
 * One predicate and no list to forget to update:
 *
 *  - every terminal `AgentResult` sets the shared state to a `RunState` for which
 *    [isTerminal] is true — exhaustively, over the sealed type, in
 *    `AgentViewModel.publish`;
 *  - [watchForTerminal] waits for exactly that predicate and then calls
 *    [stopForegroundAndSelf];
 *  - a `finally` calls it again, so a thrown exception or a cancelled scope cannot
 *    leave a notification pinned to the shade;
 *  - [onDestroy] calls it a third time, for the case where the system takes the
 *    service down while a run is in flight.
 *
 * A leaked `dataSync` foreground service with a persistent notification is the
 * most reliable way to get an app killed by the system, so this is the part that
 * had to be right rather than merely working.
 *
 * ## Manifest
 *
 * Needs a `<service>` entry plus two permissions this PR is not allowed to add.
 * The exact XML is in the PR description for the parent to apply.
 */
class ExecutionService : Service() {

    private val serviceJob: Job = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Default)

    private lateinit var container: AppContainer
    private lateinit var sinks: RunSinks
    private var agent: AgentViewModel? = null
    private var watcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as LocalIntelligenceApp).container
        sinks = container.runSinks
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val task = intent.getStringExtra(EXTRA_TASK).orEmpty()
                if (task.isBlank()) {
                    // Nothing to run. Foregrounding and stopping a millisecond
                    // later is a worse outcome than never starting at all.
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                // Post the notification BEFORE any work. A model load can take
                // seconds, and the five-second foreground deadline does not pause
                // for it.
                startTaskForeground()
                startRun(task)
            }

            ACTION_CONFIRM -> {
                // Already foreground: a run awaiting approval never stopped the
                // service. If the process died in between there is no controller
                // to resume, so this is a no-op rather than a crash.
                agent?.confirm(intent.getBooleanExtra(EXTRA_APPROVED, false))
            }

            ACTION_CANCEL -> agent?.cancel()

            else -> stopForegroundAndSelf()
        }

        // One watcher per service instance. It returns on the first terminal
        // state; `finally` is the backstop for the paths that never reach one.
        if (watcher?.isActive != true) {
            watcher = serviceScope.launch {
                try {
                    sinks.state.filter { it.isTerminal }.first()
                } finally {
                    stopForegroundAndSelf()
                }
            }
        }

        // START_NOT_STICKY: a task killed by the system must not silently restart
        // and re-run an action the user may have already seen finish. The agent is
        // not idempotent, so redelivery is a correctness risk, not just waste.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Braces to the watcher's braces: if the system is taking us down, the
        // notification and the foreground flag go with it, and the in-flight
        // decode is cancelled rather than left running against a dead service.
        agent?.cancel()
        watcher?.cancel()
        stopForegroundAndSelf()
        serviceScope.cancel()
        super.onDestroy()
    }

    /** The user swiped the app away mid-run. Cancel; do not keep decoding. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        agent?.cancel()
        super.onTaskRemoved(rootIntent)
    }

    // ----------------------------------------------------------------- internals

    private fun startRun(task: String) {
        // A controller is single-use (cancel is sticky; a pending confirmation
        // must be resumed on the same instance), so every task gets a new one.
        val agent = AgentViewModel(
            controller = container.newController(),
            scope = serviceScope,
            sinks = sinks,
        )
        this.agent = agent
        agent.start(task)
    }

    private fun startTaskForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(NOTIFICATION_TEXT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun stopForegroundAndSelf() {
        // STOP_FOREGROUND_REMOVE, not DETACH: a detached notification survives
        // stopSelf() and lingers in the shade with nothing behind it.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            // LOW: this is a progress indicator, not something to interrupt for.
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * The public command surface. [ServiceAgentGateway] is the only intended
     * caller, and it exists so a screen never has to know an Intent exists.
     */
    companion object {
        const val ACTION_START = "dev.localintelligence.app.START"
        const val ACTION_CONFIRM = "dev.localintelligence.app.CONFIRM"
        const val ACTION_CANCEL = "dev.localintelligence.app.CANCEL"
        const val EXTRA_TASK = "task"
        const val EXTRA_APPROVED = "approved"

        /**
         * Starts a run. Always `startForegroundService`, so `onStartCommand` is
         * obliged to post the notification within five seconds — and we post it
         * before doing any work, which is why a slow model load cannot trip
         * `ForegroundServiceDidNotStartInTimeException`.
         *
         * Callers must be in the foreground. Android 12+ throws from a background
         * start, and the only legitimate start here is the user pressing Send.
         */
        fun start(context: Context, task: String) {
            val intent = Intent(context, ExecutionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK, task)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Approve or decline a staged risky tool.
         *
         * A plain `startService`, not a foreground one: the service is already
         * foreground because a run that is awaiting approval never stopped it.
         * That also sidesteps the API 31+ background-start restriction entirely,
         * since a foreground service starting a service is always allowed.
         */
        fun confirm(context: Context, approved: Boolean) {
            val intent = Intent(context, ExecutionService::class.java)
                .setAction(ACTION_CONFIRM)
                .putExtra(EXTRA_APPROVED, approved)
            context.startService(intent)
        }

        /**
         * The STOP button. `runCatching` because the service may legitimately be
         * gone by the time a user hits Stop on a result that just finished, and a
         * crash on a button that worked is not an acceptable trade.
         */
        fun cancel(context: Context) {
            val intent = Intent(context, ExecutionService::class.java).setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
        }

        // ---- notification internals -------------------------------------------
        // Nothing outside this class posts one, but Kotlin allows only one
        // companion per class, so they live here under a section marker rather
        // than in a second one.

        /** Private: the channel is an implementation detail of this service. */
        private const val CHANNEL_ID = "execution"
        private const val CHANNEL_NAME = "Running task"
        private const val NOTIFICATION_ID = 41

        /**
         * Hardcoded rather than a string resource: the manifest, the service and
         * this file were the only ones this change was allowed to touch, and a
         * res/values edit would have been a collision with another workstream. A
         * real string lands with the localisation pass.
         */
        private const val NOTIFICATION_TEXT = "Working on your request…"
    }
}

/**
 * [AgentGateway] backed by [ExecutionService] intents.
 *
 * The screen's half of the seam. It holds no agent state of its own: everything
 * it exposes is a read of the shared [RunSinks], so a rotation cannot desync the
 * UI from the run, and a UI that never attaches (process death, then a service
 * still running) cannot corrupt anything.
 */
class ServiceAgentGateway(
    private val context: Context,
    private val sinks: RunSinks,
) : AgentGateway {

    override val runState: StateFlow<RunState> get() = sinks.state
    override val trace: StateFlow<List<StepTrace>> get() = sinks.trace
    override val streamingText: StateFlow<String> get() = sinks.streamingText

    override fun start(task: String) = ExecutionService.start(context, task)

    override fun confirm(approved: Boolean) = ExecutionService.confirm(context, approved)

    override fun cancel() = ExecutionService.cancel(context)
}
