package dev.localintelligence.app

import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The UI's whole view of the agent, in one file.
 *
 * The agent loop is the only thing that decides anything
 * (`docs/architecture.md` §2). This type does three jobs and makes no decisions:
 *
 *  1. owns one [AgentController] for the duration of one run and drives it,
 *  2. projects the sealed [AgentResult] onto flat [StateFlow]s the UI can render,
 *  3. guarantees a terminal state is published for every exit.
 *
 * It is deliberately **not** an `androidx.lifecycle.ViewModel`. This object has
 * to outlive any Activity: it is created by a foreground service that keeps
 * running when the user rotates the phone or backgrounds the app, and an
 * `androidx.lifecycle.ViewModel` would be cleared at exactly the moment the run
 * most needs to keep going. The screen-scoped state lives in
 * [dev.localintelligence.app.ui.ChatViewModel], which is a real ViewModel.
 *
 * A controller is single-use: `cancel()` is sticky, and
 * [AgentResult.AwaitingConfirmation] has to be resumed on the *same* instance.
 * So one of these is one run, and [ExecutionService] builds a fresh one per task.
 */
class AgentViewModel(
    private val newController: () -> AgentController,
    private val scope: CoroutineScope,
    private val sinks: RunSinks = RunSinks(),
    /**
     * True for a run nobody is watching, such as a scheduled one. A risky tool it
     * asks for is declined in place: the approval dialog only exists in the chat,
     * so waiting for it would hold the service and block every later firing.
     */
    private val declineApprovals: Boolean = false,
) : AgentGateway {

    private var job: Job? = null

    /**
     * Made on the first start, not at construction: a controller takes the backend
     * that is resident when it is made, and that is only known once the model is ready.
     */
    private var controller: AgentController? = null

    /** A STOP pressed before the controller exists, for example during a model load. */
    @Volatile
    private var cancelRequested: Boolean = false

    /** Set once a STOP that arrived before the controller existed has been published. */
    private val stopAnswered = AtomicBoolean(false)

    /** True once an unattended run has declined a risky tool. */
    @Volatile
    var approvalDeclined: Boolean = false
        private set

    // ------------------------------------------------------------- AgentGateway

    override val runState: StateFlow<RunState> get() = sinks.state
    override val trace: StateFlow<List<StepTrace>> get() = sinks.trace
    override val streamingText: StateFlow<String> get() = sinks.streamingText
    override val runningTool: StateFlow<RunningTool?> get() = sinks.runningTool

    override fun start(task: String) {
        // One run at a time. The STOP button is not a queue.
        if (job?.isActive == true) return
        runTask(task)
    }

    /**
     * Runs the loop for [task] and publishes the result, terminal whatever
     * happens.
     *
     * Split out of [start] because [prepareThenStart] needs to run a task from
     * *inside* its own preparation coroutine — and `start` refuses to do anything
     * while a job is active, which that job always is. Calling `start` there
     * silently returned, and the run was left sitting in `LoadingModel` forever
     * with the service held open behind it.
     */
    private fun runTask(task: String) {
        sinks.state.value = RunState.Running
        sinks.streamingText.value = ""
        sinks.runningTool.value = null
        val runner = controller ?: newController().also {
            controller = it
            if (cancelRequested) it.cancel()
        }
        job = scope.launch { runAndPublish(runner) { runner.run(task) } }
    }

    override fun confirm(approved: Boolean) {
        // The previous coroutine has already returned (it returned the
        // AwaitingConfirmation), so a new one continues the same controller.
        if (job?.isActive == true) return
        val runner = controller ?: return
        // Leave the approval state now, as runTask does. Otherwise the dialog stays
        // up for the whole resumed run, and every tap adds another row.
        sinks.state.value = RunState.Running
        job = scope.launch { runAndPublish(runner) { runner.confirmAndResume(approved) } }
    }

    override fun cancel() {
        cancelRequested = true
        controller?.cancel()
        // A load has no controller to cancel, and the load itself cannot be interrupted from
        // here, so the STOP is answered now rather than when the load ends.
        if (controller == null && sinks.state.value == RunState.LoadingModel) answerStopBeforeRun()
    }

    /**
     * Ensures a model is resident, then runs the task.
     *
     * The service's entry point rather than [start], because the two things a
     * user presses SEND for — a working model and an answer — are separate
     * steps, and the second is impossible without the first. A 2 GB load takes
     * tens of seconds; reporting that as silence is indistinguishable from a
     * hang, so the run goes through [RunState.LoadingModel] first.
     *
     * [ensureReady] reports whether a model could be made resident. When it
     * cannot, the run ends *terminally* carrying [blockingReason] as the
     * failure text, rather than throwing: a failed send has to stop the service
     * like any other terminal result, and routing it through [publish] is what
     * guarantees that. The user sees the actual reason instead of the backend's
     * empty "model failed: ".
     */
    fun prepareThenStart(
        task: String,
        ensureReady: suspend () -> ModelAvailability,
    ) {
        if (job?.isActive == true) return
        sinks.state.value = RunState.LoadingModel
        job = scope.launch {
            val readiness = try {
                ensureReady()
            } catch (e: CancellationException) {
                publishCancelled()
                throw e
            } catch (t: Throwable) {
                ModelAvailability.Failed(describeLoadFailure(t))
            }
            // Before the blocking reason, so a load that failed after the STOP cannot replace
            // the cancelled outcome with its own failure.
            if (cancelRequested) {
                answerStopBeforeRun()
                return@launch
            }
            val blocked = readiness.blockingReason()
            if (blocked != null) {
                // Terminal on purpose: the task cannot proceed, so pretending to
                // be "running" would strand the service and the STOP button.
                publish(AgentResult.Stop(reason = blocked, trace = sinks.trace.value))
                return@launch
            }
            runTask(task)
        }
    }

    /**
     * Runs the task with no preparation step.
     *
     * Kept as the public entry point for any caller that already holds a loaded
     * backend, where the extra state transition would be noise.
     *
     * ## WHY IT TAKES THE RESIDENT MODEL AS A PARAMETER
     *
     * It used to pass `ModelAvailability.Ready` — a nameless constant — so a
     * caller could assert "a model is ready" without ever saying which one.
     * That is now impossible to write: `Ready` carries the name, and a
     * readiness claim without a name would be a claim the state cannot back.
     *
     * The parameter is the caller's own [ModelAvailability.Ready] — normally
     * the one already in `ModelAvailabilityHolder` — rather than a freshly
     * invented one, so this overload publishes no state and does not
     * contradict the holder it is bypassing.
     */
    fun prepareThenStart(task: String, resident: ModelAvailability.Ready) {
        prepareThenStart(task, ensureReady = { resident })
    }

    /** True when no coroutine of ours is still suspended inside the controller. */
    val isIdle: Boolean get() = job?.isActive != true

    /**
     * Appends one streamed token.
     *
     * `AgentController` streams through `generateStreaming` whenever a token
     * sink is attached and the backend supports it, so tokens of every step
     * arrive here, protocol markup included; the chat screen filters what it
     * draws.
     */
    fun appendStreamToken(token: String) {
        if (sinks.state.value != RunState.Running) return
        sinks.streamingText.value += token
    }

    // ----------------------------------------------------------------- internal

    /**
     * Runs the controller and guarantees a terminal state exists before this
     * coroutine returns, however it ends.
     *
     * This wrapper exists because `AgentController.guarded` deliberately
     * rethrows `CancellationException` — which is correct for a suspending
     * function, and catastrophic here. Without it, a cancelled pass leaves
     * `sinks.state` sitting on `RunState.Running`, and because
     * [RunState.isTerminal] is false for `Running`:
     *
     *  - `ExecutionService`'s watcher never fires, so the foreground service and
     *    its notification are never stopped;
     *  - the chat keeps rendering a STOP button for a run that is already dead.
     *
     * The user sees an app that is permanently busy and cannot be freed. A
     * `finally` is the whole point: a terminal outcome is published on the
     * normal path, on a thrown exception, and on cancellation.
     *
     * Note the state write is not a suspending call, so it is still legal after
     * this coroutine's own job has been cancelled — which is exactly when it
     * matters most.
     */
    private suspend fun runAndPublish(runner: AgentController, block: suspend () -> AgentResult) {
        try {
            var result = block()
            // Declined here, in this coroutine, and not from outside with confirm():
            // confirm() refuses while this job is still active, and it still is for a
            // moment after the staged call is published.
            while (declineApprovals && result is AgentResult.AwaitingConfirmation) {
                approvalDeclined = true
                result = runner.confirmAndResume(approved = false)
            }
            // Neither result carries a trace, so the controller's own copy of this run is the one to show.
            if (result is AgentResult.Cancelled || result is AgentResult.StepLimitReached) {
                sinks.trace.value = runner.traceSnapshot
            }
            publish(result)
        } catch (e: CancellationException) {
            publishCancelled()
            throw e
        } catch (t: Throwable) {
            // `guarded` already converts an exception into `Stop`, so reaching
            // here means something outside the controller's own try block
            // failed. Publishing a terminal state is still better than leaking
            // a service, but the message stays generic on purpose: the raw
            // exception text is for the trace view, not the transcript.
            publish(
                AgentResult.Stop(
                    reason = "internal error: ${t::class.java.simpleName}",
                    trace = sinks.trace.value,
                ),
            )
        } finally {
            sinks.streamingText.value = ""
            sinks.runningTool.value = null
        }
    }

    /**
     * Publishes the terminal state for a coroutine that was cancelled before it
     * could return a result.
     *
     * Split out of [runAndPublish] because model preparation can be cancelled
     * too, and it must reach the same terminal state — a user who backgrounds
     * the app during a 2 GB load and comes back must not find the app stuck on
     * a spinner with no way to stop it.
     */
    private fun publishCancelled() {
        // A STOP answered during the load already holds this run's terminal state.
        if (stopAnswered.get()) return
        publish(
            AgentResult.Stop(
                reason = CANCELLED_REASON,
                trace = sinks.trace.value,
            ),
        )
        sinks.streamingText.value = ""
    }

    /**
     * Publishes the terminal state for a STOP that arrived before the controller existed.
     *
     * Reached from [cancel] during a load and from the preparation coroutine after it. The
     * flag makes whichever gets there first the only publication.
     */
    private fun answerStopBeforeRun() {
        if (!stopAnswered.compareAndSet(false, true)) return
        // This run has no steps of its own. Without the reset the sink still holds the
        // previous run's, and the chat would draw them under this cancelled one.
        sinks.trace.value = emptyList()
        publish(AgentResult.Cancelled)
    }

    /**
     * The one place an [AgentResult] becomes UI state. Every branch of the sealed
     * interface is handled exhaustively on purpose: adding a sixth result to
     * `:core` should break this function at compile time rather than silently
     * leave the foreground service running forever.
     */
    private fun publish(result: AgentResult) {
        when (result) {
            is AgentResult.Success -> {
                sinks.trace.value = result.trace
                sinks.state.value = RunState.Finished(RunOutcome.Answer(result.text))
            }

            is AgentResult.Stop -> {
                sinks.trace.value = result.trace
                sinks.state.value = RunState.Finished(RunOutcome.Failed(result.reason))
            }

            // NOT terminal. The loop is suspended mid-run, the service stays
            // foreground, and the user is asked to approve or decline.
            is AgentResult.AwaitingConfirmation -> {
                sinks.trace.value = result.trace
                val definition = result.tool.definition
                sinks.state.value = RunState.AwaitingApproval(
                    toolName = result.toolName,
                    description = definition.description,
                    risk = definition.risk,
                    arguments = PRETTY.encodeToString(
                        JsonElement.serializer(),
                        result.pendingArgs,
                    ),
                    requiredPermission = definition.requiredPermission,
                    justification = result.justification,
                )
            }

            // These two carry no trace (pinned wave-1 signature). runAndPublish sets
            // the trace from the controller before they reach this point.
            AgentResult.StepLimitReached ->
                sinks.state.value = RunState.Finished(RunOutcome.StepLimitReached)

            AgentResult.Cancelled ->
                sinks.state.value = RunState.Finished(RunOutcome.Cancelled)
        }
        sinks.streamingText.value = ""
        // A tool that is still "running" cannot be running once the run has a
        // result: either it finished, and the trace says so, or it was never run.
        sinks.runningTool.value = null
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /**
         * Wording for a run whose coroutine was cancelled before the loop
         * returned a result.
         *
         * Deliberately not [RunOutcome.Cancelled]'s "Stopped at your request":
         * that string is a claim about *why*, and a `CancellationException`
         * reaching here usually means the scope was torn down, not that the user
         * pressed STOP. The honest, vaguer wording costs nothing and cannot be
         * wrong.
         */
        internal const val CANCELLED_REASON = "The run was interrupted before it finished."
    }
}

/**
 * The mutable state a run publishes into. A separate object so the screen can
 * hold the [StateFlow]s before any run exists, and so a service-driven run and a
 * directly-driven test run have exactly the same shape.
 */
class RunSinks {
    val state: MutableStateFlow<RunState> = MutableStateFlow(RunState.Idle)
    val trace: MutableStateFlow<List<StepTrace>> = MutableStateFlow(emptyList())
    val streamingText: MutableStateFlow<String> = MutableStateFlow("")
    val runningTool: MutableStateFlow<RunningTool?> = MutableStateFlow(null)

    fun reset() {
        state.value = RunState.Idle
        trace.value = emptyList()
        streamingText.value = ""
        runningTool.value = null
    }
}

/**
 * What the UI controls and observes. Two implementations exist and both are
 * constructor-injected: [AgentViewModel] (in-process; used by tests and by any
 * caller that already holds a controller) and [ServiceAgentGateway] (forwards to
 * [ExecutionService]). That is the whole "binding" — no event bus, no framework.
 */
interface AgentGateway {
    val runState: StateFlow<RunState>
    val trace: StateFlow<List<StepTrace>>
    val streamingText: StateFlow<String>

    /** The tool call in flight right now, or null. Not a trace entry: see [RunningTool]. */
    val runningTool: StateFlow<RunningTool?>

    fun start(task: String)
    fun confirm(approved: Boolean)
    fun cancel()
}

/**
 * A tool call that has started and has not returned yet.
 *
 * Kept beside the trace rather than inside it, on purpose: the trace is the
 * record of what happened and every entry in it is final, so a call that is
 * still running has no entry yet. [args] is the compact form the trace uses.
 */
data class RunningTool(val toolName: String, val args: String)

/** Coarse run phase. Everything the chat screen branches on is here. */
sealed interface RunState {
    data object Idle : RunState

    /**
     * One line for the transcript while a run is in flight, or null when the
     * screen should say nothing.
     *
     * Null for [Idle] and [Finished] on purpose: those are states the transcript
     * already describes with its own message, and a second line saying
     * "Working…" next to a finished answer is a lie about work that is not
     * happening. `LoadingModel` gets real words because the wait is long enough
     * that a bare spinner reads as a hang.
     */
    val progressLabel: String?
        get() = when (this) {
            Idle, is Finished -> null
            LoadingModel -> "Loading the model into memory. This can take a minute."
            Running -> null
            is AwaitingApproval -> "Waiting for your approval."
        }

    /** The loop is running. [AgentGateway.cancel] is the only way out. */
    data object Running : RunState

    /**
     * A model is being loaded into memory, before the loop has started.
     *
     * Non-terminal and non-`Running` on purpose. A first run on a 3B model spends
     * tens of seconds in `ModelBackend.load` with no decode in flight, and a UI
     * that reported nothing during that window is indistinguishable from a
     * crash. It is also *not* cancellable through the controller, which does not
     * exist yet: `cancel()` answers a STOP here directly. Until one arrives, the
     * service must keep running through the load, exactly as it does for
     * [AwaitingApproval].
     */
    data object LoadingModel : RunState

    /**
     * A risky tool is staged and waiting for a human. The runtime decided this
     * from [ToolRisk.requiresConfirmation]; the UI only renders it and reports
     * the answer back.
     */
    data class AwaitingApproval(
        val toolName: String,
        val description: String,
        val risk: ToolRisk,
        /** Already formatted as indented JSON, ready to display. */
        val arguments: String,
        val requiredPermission: String? = null,
        /** Why the policy wants a human to decide, such as the destination host. Empty if none. */
        val justification: String = "",
    ) : RunState

    data class Finished(val outcome: RunOutcome) : RunState
}

/** The terminal states. A finished run is finished; there is no "and then". */
sealed interface RunOutcome {
    data class Answer(val text: String) : RunOutcome
    data class Failed(val reason: String) : RunOutcome
    data object StepLimitReached : RunOutcome
    data object Cancelled : RunOutcome
}

/**
 * True for exactly the states that end a run. [ExecutionService] waits on this
 * and nothing else, which is what makes "stops on every terminal result" a
 * single checkable predicate rather than a list someone can forget to update.
 *
 * [RunState.AwaitingApproval] is deliberately **not** terminal: the run is
 * suspended, not over, and the service must stay foreground so a 2 GB decode
 * survives the user reading a dialog. [RunState.LoadingModel] is non-terminal
 * for the same reason — a model load in progress is work in flight, and
 * stopping the service during one is what produces the notification-with-nothing-
 * behind-it bug this predicate exists to prevent.
 */
val RunState.isTerminal: Boolean
    get() = this is RunState.Finished || this is RunState.Idle

/**
 * True while the run owns the foreground service, whatever phase it is in.
 *
 * The inverse question to [isTerminal] and the one a screen actually asks when
 * deciding whether to show STOP. `AwaitingApproval` and `LoadingModel` both count:
 * in both cases the service is alive and holding native memory, so the user
 * must be able to end it.
 */
val RunState.isActive: Boolean
    get() = !isTerminal

/** One line for the transcript notice. */
val RunOutcome.notice: String
    get() = when (this) {
        is RunOutcome.Answer -> "Done."
        is RunOutcome.Failed -> reason
        RunOutcome.StepLimitReached -> "Stopped: the agent used all of its steps."
        RunOutcome.Cancelled -> "Stopped at your request."
    }
