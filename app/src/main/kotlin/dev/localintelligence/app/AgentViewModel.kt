package dev.localintelligence.app

import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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
    private val controller: AgentController,
    private val scope: CoroutineScope,
    private val sinks: RunSinks = RunSinks(),
) : AgentGateway {

    private var job: Job? = null

    // ------------------------------------------------------------- AgentGateway

    override val runState: StateFlow<RunState> get() = sinks.state
    override val trace: StateFlow<List<StepTrace>> get() = sinks.trace
    override val streamingText: StateFlow<String> get() = sinks.streamingText

    override fun start(task: String) {
        // One run at a time. The STOP button is not a queue.
        if (job?.isActive == true) return
        sinks.state.value = RunState.Running
        sinks.streamingText.value = ""
        job = scope.launch { publish(controller.run(task)) }
    }

    override fun confirm(approved: Boolean) {
        // The previous coroutine has already returned (it returned the
        // AwaitingConfirmation), so a new one continues the same controller.
        if (job?.isActive == true) return
        job = scope.launch { publish(controller.confirmAndResume(approved)) }
    }

    override fun cancel() {
        controller.cancel()
    }

    /** True when no coroutine of ours is still suspended inside the controller. */
    val isIdle: Boolean get() = job?.isActive != true

    /**
     * Appends one streamed token.
     *
     * Honesty about what is wired: `AgentController` calls
     * `ModelBackend.generate`, not `generateStreaming`, so on the current core
     * this never fires. The sink exists so the UI contract ("show tokens as they
     * arrive") is real the moment the loop calls `generateStreaming`, rather than
     * being a spinner pretending to be a stream.
     */
    fun appendStreamToken(token: String) {
        if (sinks.state.value != RunState.Running) return
        sinks.streamingText.value += token
    }

    // ----------------------------------------------------------------- internal

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
                )
            }

            // These two carry no trace (pinned wave-1 signature), so the last
            // known trace stays on screen rather than blanking.
            AgentResult.StepLimitReached ->
                sinks.state.value = RunState.Finished(RunOutcome.StepLimitReached)

            AgentResult.Cancelled ->
                sinks.state.value = RunState.Finished(RunOutcome.Cancelled)
        }
        sinks.streamingText.value = ""
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }
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

    fun reset() {
        state.value = RunState.Idle
        trace.value = emptyList()
        streamingText.value = ""
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

    fun start(task: String)
    fun confirm(approved: Boolean)
    fun cancel()
}

/** Coarse run phase. Everything the chat screen branches on is here. */
sealed interface RunState {
    data object Idle : RunState

    /** The loop is running. [AgentGateway.cancel] is the only way out. */
    data object Running : RunState

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
 * survives the user reading a dialog.
 */
val RunState.isTerminal: Boolean
    get() = this is RunState.Finished || this is RunState.Idle

/** One line for the transcript notice. */
val RunOutcome.notice: String
    get() = when (this) {
        is RunOutcome.Answer -> "Done."
        is RunOutcome.Failed -> reason
        RunOutcome.StepLimitReached -> "Stopped: the agent used all of its steps."
        RunOutcome.Cancelled -> "Stopped at your request."
    }
