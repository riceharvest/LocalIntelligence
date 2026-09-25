package dev.localintelligence.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.localintelligence.app.AgentGateway
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.ModelAvailabilityHolder
import dev.localintelligence.app.RunOutcome
import dev.localintelligence.app.RunState
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.isActive
import dev.localintelligence.app.isTerminal
import dev.localintelligence.app.notice
import dev.localintelligence.core.agent.StepTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Screen state for the chat. A real `androidx.lifecycle.ViewModel`, so it
 * survives rotation — but deliberately thin.
 *
 * The split that matters: the *run* lives in [AgentGateway], a process-scoped
 * object owned by the foreground service, and this class keeps only the
 * *transcript* and the pending tool confirmations. A ViewModel is cleared when
 * its Activity is destroyed for good, and a run can outlive that; if the
 * transcript were derived from the run state it would vanish mid-decode.
 *
 * The pending confirmation is NOT duplicated here. It is read straight off
 * [gateway] on every access ([pendingApproval]), so a rotated Activity cannot
 * lose the fact that `sms.send` is waiting for a human, and there is still only
 * one source of truth for *which* call is pending.
 *
 * There are no tests in this repository. This class is compile-only, like every
 * other class in `:app`; the previous version of this comment claimed a JVM
 * test suite drove it, which stopped being true when the suite was deleted.
 */
class ChatViewModel(
    private val gateway: AgentGateway,
    /**
     * Overrides the scope. Production passes nothing and gets [viewModelScope].
     *
     * A nullable constructor parameter rather than a default of `viewModelScope`
     * because a primary-constructor default cannot reference `this` — the
     * instance does not exist yet. Resolving it in a property initializer below
     * is the first point where the superclass has been constructed and
     * `viewModelScope` is legal.
     */
    scopeOverride: CoroutineScope? = null,
    /**
     * Whether a model can answer, from the process-wide holder.
     *
     * Defaults to a fresh [ModelAvailabilityHolder] so a caller with no model
     * concept still constructs cleanly; production passes the container's
     * shared one, so a rotation or a backgrounded app reads the same state the
     * service writes.
     */
    modelAvailability: ModelAvailabilityHolder = ModelAvailabilityHolder(),
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    /**
     * Held as a property, not just a constructor parameter, because the public
     * accessors below read it long after construction. Making it a parameter
     * would silently drop it on the floor the moment `init` finished.
     */
    private val modelAvailability: ModelAvailabilityHolder = modelAvailability

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /**
     * Which transcript row each `TOOL_CALL` trace entry is drawn in.
     *
     * WHY IT STORES AN INDEX: a row is drawn before its result necessarily
     * exists. `AgentController` publishes the trace at an approval *and* again
     * when the run ends, handing out the *same* `StepTrace` objects both times.
     * So a call dispatched just before a prompt is drawn from the first
     * publication, and the observation that completes it only arrives in the
     * second. The previous version appended each `TOOL_CALL` exactly once and
     * never looked at it again, so any row drawn at an approval kept its empty
     * observation for the rest of the session.
     *
     * WHY THE OBJECT IS THE KEY: recognising an already-drawn call is what makes
     * the fill-in possible, and the runtime reuses one object per call, so
     * identity is the key that holds across those republications. A per-run
     * counter would not, because the list arrives whole rather than appended to.
     *
     * Across runs it does not matter: every run gets a fresh controller with a
     * fresh trace list, so a new run's entries are new objects and are never
     * mistaken for the previous run's. The map is cleared when a run starts only
     * so it does not grow for the life of the Activity.
     *
     * Indices stay valid because the transcript only ever grows at the end, or
     * has one row replaced in place.
     */
    private val toolRows = java.util.IdentityHashMap<StepTrace, Int>()

    val runState: StateFlow<RunState> get() = gateway.runState
    val trace: StateFlow<List<StepTrace>> get() = gateway.trace
    val streamingText: StateFlow<String> get() = gateway.streamingText

    /**
     * Whether a model is resident and able to answer.
     *
     * Read by the chat screen to decide whether the composer is worth showing.
     * It is a [StateFlow] and not a snapshot because the answer changes while
     * the screen is alive: importing a model in the manager makes the composer
     * usable without the user coming back to a blank chat.
     */
    val modelState: StateFlow<ModelAvailability> get() = modelAvailability.state

    /**
     * The reason sending is impossible, or null when a send would work.
     *
     * Delegated to [blockingReason] so the composable cannot disagree with the
     * state machine about *why* it is disabled — the two drifting apart is how a
     * user ends up with a greyed-out button and no explanation.
     */
    val blockedReason: String? get() = modelAvailability.current.blockingReason()

    /**
     * The confirmation currently staged by the runtime, or null.
     *
     * A computed read rather than a mirrored copy: the gateway already holds it,
     * and two mutable copies of "which destructive call is pending" is exactly
     * how a dialog ends up approving something the user did not read.
     */
    val pendingApproval: RunState.AwaitingApproval?
        get() = gateway.runState.value as? RunState.AwaitingApproval

    /**
     * True while the run owns the foreground service, in any phase.
     *
     * Includes `LoadingModel` and `AwaitingApproval`, not just `Running`. That
     * matters because it drives which button the composer shows: if this were
     * false during a model load, the user would be offered SEND for a run that is
     * already in flight, and tapping it would silently do nothing.
     */
    val isBusy: Boolean get() = gateway.runState.value.isActive

    init {
        // Draws every tool call in [steps] that has no row yet, and fills in the
        // observation of any row that has one now. Idempotent: keyed on trace
        // entry identity, so a second call over the same list adds nothing and
        // never duplicates a row.
        fun renderToolSteps(steps: List<StepTrace>) {
            steps.forEach { entry ->
                if (entry.kind != StepTrace.Kind.TOOL_CALL) return@forEach
                // The matching OBSERVATION is the next entry with the same step
                // number. The loop dispatches at most one tool per step, so the
                // step number is a sound pairing key.
                val observation = steps.firstOrNull {
                    it.kind == StepTrace.Kind.OBSERVATION && it.step == entry.step
                }
                val known = toolRows[entry]
                if (known != null) {
                    if (observation == null) return@forEach
                    _messages.update { list ->
                        val index = known
                        if (index !in list.indices) return@update list
                        val row = list[index]
                        if (row !is ChatMessage.ToolStep) return@update list
                        if (row.observation != null) return@update list
                        list.toMutableList().also { it[index] = row.completedBy(observation) }
                    }
                    return@forEach
                }
                _messages.update { list ->
                    val row = ChatMessage.ToolStep(
                        // From the explicit fields, never by cutting up
                        // `detail`: that string is a display format and parsing
                        // it is how a tool named "files.read_text" ends up
                        // displayed as "files". It is null on the refusal paths
                        // the runtime takes, which is why the row carries the
                        // whole `detail` as well.
                        toolName = entry.toolName.orEmpty(),
                        args = entry.toolArgs.orEmpty(),
                        observation = observation?.detail,
                        // The runtime's own sentence for this call. For a
                        // dispatch with no observation it is the only thing
                        // there is to show, and it is the thing worth showing:
                        // "refused sms.send: rate limit — …".
                        detail = entry.detail,
                        durationMs = observation?.durationMs ?: 0L,
                        // No observation means no result. The previous version
                        // defaulted this to `true`, so a call the policy had
                        // refused rendered in the colour of a success.
                        success = observation?.success ?: false,
                    )
                    val next = list + row
                    toolRows[entry] = next.lastIndex
                    next
                }
            }
        }

        var lastTerminal: RunState.Finished? = gateway.runState.value as? RunState.Finished
        var wasTerminal = gateway.runState.value.isTerminal
        scope.launch {
            gateway.trace.collect { steps -> renderToolSteps(steps) }
        }
        scope.launch {
            gateway.runState.collect { state ->
                // A run is starting. Drop the previous run's entries so the map
                // does not accumulate for the life of the Activity. Not a
                // correctness requirement — each run's entries are new objects,
                // so nothing is ever mistaken for an older call — but an
                // unbounded map in a ViewModel is a leak with extra steps.
                if (wasTerminal && !state.isTerminal) toolRows.clear()
                wasTerminal = state.isTerminal

                if (state !is RunState.Finished) return@collect
                if (state === lastTerminal) return@collect
                lastTerminal = state

                // The calls first, then the outcome.
                //
                // Both publications happen inside one `publish()` in
                // `AgentViewModel` and reach this class over two independent
                // collectors, so whichever ran first used to decide the order.
                // The transcript could read "here is your answer" and then list
                // the calls that produced it underneath, which reads as though
                // the agent did all of its work after answering.
                renderToolSteps(gateway.trace.value)

                _messages.update {
                    it + when (val outcome = state.outcome) {
                        is RunOutcome.Answer -> ChatMessage.Assistant(outcome.text)
                        else -> ChatMessage.Notice(outcome.transcriptLine())
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------- intents

    fun onInputChange(value: String) {
        _input.value = value
    }

    fun send() {
        val task = _input.value.trim()
        if (task.isEmpty() || !gateway.runState.value.isTerminal) return
        // Refuse before touching the transcript. Appending a user message that
        // no run will ever answer is the single most dishonest thing this screen
        // could do: the user would be looking at their own question and silence.
        if (blockedReason != null) return
        _input.value = ""
        _messages.update { it + ChatMessage.User(task) }
        gateway.start(task)
    }

    /** Convenience for a SEND intent or a suggestion tap. */
    fun send(text: String) {
        if (text.isBlank() || !gateway.runState.value.isTerminal) return
        if (blockedReason != null) return
        _messages.update { it + ChatMessage.User(text.trim()) }
        gateway.start(text.trim())
    }

    /** The STOP button. Cooperative: the loop returns `Cancelled` when it can. */
    fun stop() {
        gateway.cancel()
    }

    /**
     * Answer a staged risky tool.
     *
     * Approving runs the exact call the runtime staged. Declining does **not**
     * cancel the run: it calls the same `confirmAndResume(false)`, which tells
     * the model the user said no so it can route around the action. Stopping the
     * whole task would be a different, and wrong, answer to "declined this one
     * thing".
     */
    fun resolveConfirmation(approved: Boolean) {
        val pending = pendingApproval ?: return
        gateway.confirm(approved)
        // The decision, in the transcript, where the user gave it.
        //
        // The runtime does reach the model on a decline —
        // `AgentController.confirmAndResume` puts "The user declined <tool>. Do
        // not call it again." into the session — so the agent can route around
        // it. What it does not leave behind is a trace entry, so nothing in the
        // app recorded the answer: the dialog closed and the transcript carried
        // on as if nothing had been asked. The old `ToolApproval` list was
        // written here and read by no screen at all, which is the same missing
        // row in a place nobody was looking.
        _messages.update {
            it + ChatMessage.Approval(toolName = pending.toolName, approved = approved)
        }
    }

    fun clear() {
        _messages.value = emptyList()
        // The row indices in `toolRows` no longer address anything.
        toolRows.clear()
    }

    class Factory(
        private val gateway: AgentGateway,
        private val modelAvailability: ModelAvailabilityHolder = ModelAvailabilityHolder(),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                "unknown ViewModel ${modelClass.name}"
            }
            return ChatViewModel(gateway, modelAvailability = modelAvailability) as T
        }
    }
}

/**
 * One row of the transcript. A flat list, not a tree: the runtime is a loop.
 *
 * WHY [ToolStep] EXISTS: without it the agent was invisible. The loop called
 * device.battery, read the real percentage, and put it in the transcript of the
 * model conversation — where the user never sees it. The screen then showed the
 * question, a pause, and an answer, which is indistinguishable from an app that
 * ignored the request. Acting on the phone and saying so are the same feature.
 */
sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String) : ChatMessage
    data class Notice(val text: String) : ChatMessage

    /**
     * A call the agent made, and what the phone said back.
     *
     * [observation] is the tool's real return value, already clipped to the
     * model-visible budget by `AgentController.truncate` — the model is handed
     * the same clipped string, so this screen shows the user exactly what the
     * model saw and not a fuller version of it.
     */
    data class ToolStep(
        val toolName: String,
        val args: String,
        val observation: String?,
        /**
         * The runtime's own sentence for this call.
         *
         * Shown when there is no [observation], which is every path where the
         * runtime decided *not* to run the tool: a policy `BLOCK`, a rejected
         * argument, an approval that could not be claimed. Those emit a
         * `TOOL_CALL` and no `OBSERVATION`, and the previous version rendered
         * them as "running…" — a tool that had already been refused, shown to
         * the user as still going.
         */
        val detail: String,
        val durationMs: Long,
        val success: Boolean,
    ) : ChatMessage {
        /**
         * Fills in the result that arrived after this row was drawn.
         *
         * Only the three fields the runtime actually reports. [toolName] and
         * [detail] stay as they were: the call was identified when it was
         * dispatched and does not get renamed by its result.
         */
        fun completedBy(observation: StepTrace) = copy(
            observation = observation.detail,
            durationMs = observation.durationMs,
            success = observation.success,
        )
    }

    /**
     * The user's answer to an approval prompt, kept in the transcript.
     *
     * Declining is a decision with a consequence — the agent is told the tool
     * was refused and told not to attempt it again — so the record of it belongs
     * beside the call it decided rather than in a field no screen reads.
     */
    data class Approval(val toolName: String, val approved: Boolean) : ChatMessage
}

/**
 * The transcript line for a run that produced no answer.
 *
 * Delegates to [notice] so there is one wording per outcome, and translates
 * exactly one of them.
 *
 * THE ONE CASE: `AgentController` turns a `StopReason.ERROR` generation into
 * `Stop("model failed: " + generation.text)`, and on a device that text is the
 * native library's own error string — `native generation failed: …`, a
 * llama.cpp message, sometimes a JNI class name. Printed raw in the transcript
 * it reads as the app explaining itself in native jargon, and it is the most
 * common terminal state on a real phone.
 *
 * The bytes are not discarded: the `GENERATION` entry in `StepTrace` carries the
 * same text, and the trace screen renders it verbatim. So the chat states what
 * happened and where the detail is, and the developer still gets the original.
 */
internal fun RunOutcome.transcriptLine(): String {
    val line = notice
    if (this !is RunOutcome.Failed) return line
    if (!reason.startsWith(RAW_MODEL_FAILURE)) return line
    return "The model stopped while it was generating, and produced no answer. " +
        "The runtime's own error is on the Trace screen."
}

/** The prefix `AgentController` puts on a failed generation. */
private const val RAW_MODEL_FAILURE = "model failed: "
