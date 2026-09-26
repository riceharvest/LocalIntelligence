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
import dev.localintelligence.app.execution.ScheduledRunRegistry
import dev.localintelligence.app.execution.ScheduledRunReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.localintelligence.core.execution.RunGate
import dev.localintelligence.core.execution.RUN_ALREADY_ACTIVE_REASON
import dev.localintelligence.app.data.ScheduledTaskStore
import android.util.Log

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

    /**
     * The scheduled task this run belongs to, or null for a hand-started one.
     *
     * Held here rather than re-read from the Intent because the id is needed at
     * *settle* time — minutes later, possibly after a rotation or a
     * process-death-and-relaunch of the service — and an Intent is not a place
     * to keep anything. Null for a user-started run, which has no row to report
     * into and is already visible in the transcript.
     */
    @Volatile
    private var scheduledTaskIdInFlight: String? = null

    /**
     * The run-claim taken at the start of a run, released in
     * `stopForegroundAndSelf`.
     *
     * It lives here rather than on the container because the claim is a
     * PROPERTY OF A RUN, while the gate is a property of the app: a service
     * instance is created and destroyed per run, so a gate stored here would be
     * a fresh gate every time and would never actually exclude anything.
     */
    private var activeClaim: RunGate.Claim? = null

    /**
     * What [dev.localintelligence.app.AppContainer.ensureModelReady] returned
     * for this run, or null while the load is still in flight.
     *
     * Recorded rather than re-queried so "the model was never available" stays
     * a fact about *this* run. Re-reading the process-wide holder at settle
     * time would let a later load mask the fact that this one never ran.
     */
    @Volatile
    private var readinessInFlight: ModelAvailability? = null

    /**
     * True once this run has written its outcome, so the two paths that can
     * reach a terminal state (the watcher and `onDestroy`) cannot both write.
     */
    @Volatile
    private var reportedInFlight: Boolean = false

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
                startRun(
                    task,
                    intent.getStringExtra(EXTRA_SCHEDULED_TASK_ID),
                    startId,
                )
            }

            ACTION_CONFIRM -> {
                // Already foreground: a run awaiting approval never stopped the
                // service. If the process died in between there is no controller
                // to resume, so this is a no-op rather than a crash.
                //
                // The no-op has to end the service, though. A `startService` for a
                // service that is not running *starts* it, and a started-but-not-
                // foreground service that is never stopped is a leak the system
                // will eventually kill — noisily, and with a notification the
                // watcher below was about to post.
                if (agent == null) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                agent?.confirm(intent.getBooleanExtra(EXTRA_APPROVED, false))
            }

            ACTION_CANCEL -> {
                // Same reasoning as ACTION_CONFIRM: a cancel aimed at a service
                // that no longer exists has to stop the service it just started,
                // not leave it resident waiting for a terminal state that will
                // never arrive.
                if (agent == null) {
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                agent?.cancel()
            }

            else -> {
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }

        // One watcher per service instance. It returns on the first terminal
        // state; `finally` is the backstop for the paths that never reach one.
        if (watcher?.isActive != true) {
            watcher = serviceScope.launch {
                try {
                    val terminal = sinks.state.filter { it.isTerminal }.first()
                    // The result is written HERE, from the same terminal state
                    // that stops the service, so the two can never disagree
                    // about whether a run finished. A run that does not settle
                    // its outcome is the bug this whole reporter exists to fix,
                    // so it happens on the one path every terminal state
                    // reaches, not on a path that has to remember to be called.
                    reportOutcome(terminal)
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
        //
        // The outcome is settled here first, and `interruptedIfUnfinished` is
        // what makes it honest: `agent?.cancel()` below only sets a flag, and
        // the terminal state it leads to is published asynchronously by a
        // coroutine that `serviceScope.cancel()` is about to tear down. Reading
        // the state *first* would therefore see `Running`, and the run would be
        // left on the fire path's "Started, waiting for the result." forever —
        // which is the stranding this whole reporter exists to prevent.
        //
        // This is not a guess. The service is being destroyed, the run cannot
        // continue in this process, and the service is START_NOT_STICKY so
        // Android will not resume it: the run was interrupted before it
        // finished, which is the same fact
        // [dev.localintelligence.app.AgentViewModel.CANCELLED_REASON] names and
        // the same sentence the watcher would have written had it lived long
        // enough to see the cancel land.
        reportOutcome(sinks.state.value, interruptedIfUnfinished = true)
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

    private fun startRun(task: String, scheduledTaskId: String?, startId: Int) {
        // One run at a time, across BOTH entry points.
        //
        // serviceScope is a pool scope, not a mutex, and this service receives a
        // second onStartCommand on the same instance when a scheduled alarm
        // fires while a chat run is live. Without this claim the second run
        // would write its turn into the shared session while the first is still
        // decoding, so the first run's next prompt would carry the second run's
        // task and none of its own history.
        //
        // A claim rather than a Mutex on purpose: a Mutex suspends the loser,
        // which turns "someone is already busy" into a silent queue. The user
        // is told instead.
        val claim = container.runGate.tryClaim()
        if (claim == null) {
            // A REFUSAL IS NOT A TERMINAL EVENT FOR THIS SERVICE.
            //
            // `onStartCommand` delivers to the SAME service instance, so
            // `activeClaim` here belongs to the run that is still decoding.
            // Calling `stopForegroundAndSelf()` from a path that never claimed
            // anything did three separate kinds of damage:
            //
            //   1. `activeClaim?.close()` released the FIRST run's claim, so a
            //      third run could then tryClaim() and succeed — two loops
            //      decoding into the same native context and the same Session,
            //      which is precisely the overlap RunGate exists to prevent.
            //   2. `stopSelf()` drove `onDestroy`, which called
            //      `agent?.cancel()` and `serviceScope.cancel()` — killing the
            //      first run. A user pressing Send twice lost their answer.
            //   3. `sinks` is the process-wide `container.runSinks` (onCreate),
            //      so this overwrote the live run's UI state with a terminal
            //      failure: the in-flight answer was replaced by "already
            //      running" while it was still generating.
            //
            // There is nothing to release (we hold no claim) and nothing to stop
            // (this service legitimately belongs to the other run), so the
            // refusal is reported and the service is left entirely alone.
            reportRefusal(startId, task, scheduledTaskId)
            return
        }
        activeClaim = claim

        // A controller is single-use (cancel is sticky; a pending confirmation
        // must be resumed on the same instance), so every task gets a new one.
        //
        // The model is loaded *before* the controller exists. `AgentController`
        // never calls `ModelBackend.load` itself, and `LlamaCppBackend.generate`
        // returns an empty ERROR result when no handle is open — which reaches
        // the user as the useless notice "model failed: ". Loading here turns
        // that into a real, nameable state the chat screen can show.
        //
        // Claimed here rather than in `onStartCommand` so the id is bound to
        // the same statement that creates the agent: a run that exists is a run
        // that is claimed. Released in `stopForegroundAndSelf`, which every exit
        // path reaches.
        ScheduledRunRegistry.begin(scheduledTaskId)
        scheduledTaskIdInFlight = scheduledTaskId
        readinessInFlight = null
        val agent = AgentViewModel(
            // Streaming, wired at last. Every layer below already existed -
            // generateStreaming on the backend, the per-token JNI callback,
            // RunSinks.streamingText, appendStreamToken - and the loop called the
            // non-streaming generate(), so the sink was never fed. On a phone CPU
            // a small model needs tens of seconds for an answer, and the user
            // watched a blank composer for all of it.
            controller = container.newController(
                // The same guard AgentViewModel.appendStreamToken applies: only
                // append while the run is live, so a token decoded just after a
                // cancel does not resurrect a finished transcript.
                onToken = { token ->
                    if (sinks.state.value.isActive) {
                        sinks.streamingText.value += token
                    }
                },
            ),
            scope = serviceScope,
            sinks = sinks,
        )
        this.agent = agent
        agent.prepareThenStart(task) {
            // ONE load, exactly as before. This is the same call
            // `ensureModelReady` always got — the wrapper only *records what it
            // returned*, so the reporter can tell "the run was blocked before it
            // started" apart from "the run started and then failed". Reading the
            // result rather than re-deriving it from a failure string is the
            // whole point: a task that never ran must not be reported as a run
            // that failed.
            container.ensureModelReady().also { readinessInFlight = it }
        }
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

    /**
     * Writes a finished run's outcome into its scheduled task, once.
     *
     * ## Why the guard is a flag and not "is the row there"
     *
     * The row being present does not mean the outcome is unreported: the
     * watcher and `onDestroy` both reach here, and the second call would
     * rewrite the first one's sentence with a state read at a different
     * moment. The flag makes the second call a no-op even when it carries a
     * different state, which is the case that matters.
     *
     * ## Why it cannot throw
     *
     * Both callers are unwinding paths — a `finally` and `onDestroy` — so a
     * `SharedPreferences` write that failed would take the process down while
     * it was trying to record why it was going down. Every throwable is
     * therefore logged rather than propagated.
     *
     * ## `CancellationException` is not a failure to report
     *
     * The project's hard rule is that structured cancellation stays
     * cancellation. Two things follow, and they are different things:
     *
     *  - A *cancelled run* is a real outcome and is reported as one. That is
     *    [RunOutcome.Cancelled], published by `AgentViewModel` as a
     *    `Finished` state long before this function is reached, so it arrives
     *    here as data and not as a thrown exception.
     *  - A `CancellationException` *thrown through this function* is
     *    cancellation of the code doing the reporting. It is not an outcome
     *    and must not be recorded as one — so `ScheduledRunReporter.settle`
     *    is not given a chance to turn it into a "Failed:" sentence, and the
     *    exception is logged and dropped rather than rethrown, because
     *    rethrowing out of `onDestroy` would crash the service and lose the
     *    notification teardown. Nothing is written, and the row keeps the
     *    fire path's "Started, waiting for the result." — which is the honest
     *    state for a run this process was not allowed to finish reporting.
     *
     * ## [interruptedIfUnfinished]
     *
     * Only `onDestroy` passes true. A non-terminal state there means the
     * process is going away with the run still in flight, which *is* an
     * outcome — an interrupted one — so it is reported as the cancellation it
     * is. The watcher passes false because there, a non-terminal state means
     * the run is simply still going, and inventing an outcome for it would be
     * the exact "reports something it did not do" bug this replaces.
     */
    private fun reportOutcome(
        state: RunState,
        interruptedIfUnfinished: Boolean = false,
    ) {
        if (scheduledTaskIdInFlight == null) return
        if (reportedInFlight) return
        val outcome = when {
            state is RunState.Finished -> state
            // Destroyed with work in flight. See the KDoc: the run cannot
            // continue and Android will not resume the service, so this is a
            // cancellation, reported with the same reason constant
            // `AgentViewModel` uses for a scope torn down mid-run.
            interruptedIfUnfinished -> RunState.Finished(
                RunOutcome.Failed(AgentViewModel.CANCELLED_REASON),
            )
            // Still running. Reporting now would be inventing an outcome.
            else -> return
        }
        reportedInFlight = true
        try {
            ScheduledRunReporter.settle(
                context = applicationContext,
                scheduledTaskId = scheduledTaskIdInFlight,
                state = outcome,
                trace = sinks.trace.value,
                readiness = readinessInFlight,
            )
        } catch (e: CancellationException) {
            // Not a run outcome. See the KDoc: logged, not rethrown, not
            // recorded. The flag stands, so nothing retries it.
            android.util.Log.w(TAG, "scheduled run result reporting was cancelled", e)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "could not record the scheduled run result", t)
        }
    }

    /**
     * Reports that a run was refused because one is already active, WITHOUT
     * touching the live run's state.
     *
     * The live run owns `sinks`, so writing a terminal state here would
     * replace the answer currently being generated with "already running".
     * Instead the refused start is finished at the service level with
     * [stopSelfResult], which resolves the *caller's* start without ending the
     * service — `stopSelfResult(startId)` marks this one start delivered and
     * leaves the service alive for the run that actually owns it.
     *
     * A scheduled run that is refused still has to be recorded: the task row
     * would otherwise keep saying the earlier result forever, implying this
     * firing succeeded. `lastResult` is written directly, because
     * [ScheduledRunReporter.settle] reads its state from `sinks`, which is the
     * other run's.
     */
    private fun reportRefusal(startId: Int, task: String, scheduledTaskId: String?) {
        if (scheduledTaskId != null) {
            // Best effort: a failure to record must not crash the receiver path.
            runCatching {
                val store = ScheduledTaskStore(applicationContext)
                val current = store.byId(scheduledTaskId) ?: return@runCatching
                store.upsert(
                    current.copy(
                        lastResult = "Skipped: another task was already running.",
                    ),
                )
            }.onFailure { Log.w(TAG, "could not record refused schedule: ${it.message}") }
        }
        Log.i(TAG, "refused start $startId (task \"$task\"): a run is already active")
        stopSelfResult(startId)
    }

    private fun stopForegroundAndSelf() {
        // STOP_FOREGROUND_REMOVE, not DETACH: a detached notification survives
        // stopSelf() and lingers in the shade with nothing behind it.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        // The single release point for the scheduled-run claim. Every exit from
        // this service — terminal result, `finally`, onDestroy, and the
        // "no agent to talk to" early returns — comes through here, so a stale
        // claim (which would make a later delete cancel a run that is not
        // running) has nowhere to hide. Idempotent, so the double call that
        // `onDestroy` after `stopSelf` produces is harmless.
        ScheduledRunRegistry.end()
        // Same argument, same single release point: a run claim that outlived
        // its run would make the next run - possibly minutes later, from a
        // scheduled alarm - be refused as "already active" with nothing running.
        // close() is idempotent, which matters because onDestroy after stopSelf
        // calls this a second time.
        activeClaim?.close()
        activeClaim = null
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
         * Null for a run the user started, an id for one a scheduled alarm
         * started. Read once in `onStartCommand` and handed straight to
         * `ScheduledRunRegistry.begin`; it is never used to decide *what* runs,
         * only who is responsible for the run while it is in flight.
         */
        const val EXTRA_SCHEDULED_TASK_ID = "scheduledTaskId"

        /**
         * Starts a run. Always `startForegroundService`, so `onStartCommand` is
         * obliged to post the notification within five seconds — and we post it
         * before doing any work, which is why a slow model load cannot trip
         * `ForegroundServiceDidNotStartInTimeException`.
         *
         * [scheduledTaskId] is non-null when a scheduled alarm triggered this
         * run rather than the user pressing Send. It is recorded in
         * [dev.localintelligence.app.execution.ScheduledRunRegistry] so that
         * deleting the task while it is mid-run can stop it — and, just as
         * importantly, so that deleting a *different* task does not.
         *
         * ## Why the background caller is now legitimate
         *
         * This previously said "callers must be in the foreground, and the only
         * legitimate start here is the user pressing Send". That was true when
         * it was written and is now false: the platform documents an exact alarm
         * as an exemption from the API 31+ background start restriction
         * ("your app invokes an exact alarm to complete an action that the user
         * requests"). A scheduled task is exactly that — an action the user
         * asked for, at a time the user chose. The exemption is a platform
         * contract, not a workaround, which is why this stayed a plain
         * `startForegroundService` instead of growing a fallback path.
         */
        fun start(context: Context, task: String, scheduledTaskId: String? = null) {
            val intent = Intent(context, ExecutionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_SCHEDULED_TASK_ID, scheduledTaskId)
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

        /**
         * Log tag for the result-reporting path. Private, because a log tag is
         * an implementation detail — and deliberately a constant rather than
         * the class name, so it stays stable if the class is ever renamed.
         */
        private const val TAG = "ExecutionService"

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
