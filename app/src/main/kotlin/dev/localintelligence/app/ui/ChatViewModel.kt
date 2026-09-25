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
 * [pendingApprovals] is the one piece of state that is genuinely duplicated
 * between here and the gateway, and it is duplicated on purpose: a rotated
 * Activity must not lose the fact that `sms.send` is waiting for a human. It is
 * mirrored from the gateway, not authored here, so there is exactly one source
 * of truth for *which* call is pending.
 *
 * The JVM tests drive this class with a real `AgentController` over a fake
 * `ModelBackend`; the composables are compile-only.
 */
class ChatViewModel(
    private val gateway: AgentGateway,
    /**
     * Overrides the scope. Production passes nothing and gets [viewModelScope];
     * tests pass a `TestScope` so the transcript is deterministic.
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
     * Defaults to a fresh [ModelAvailabilityHolder] so a caller that has no
     * model concept (the existing tests) still constructs cleanly; production
     * passes the container's shared one, so a rotation or a backgrounded app
     * reads the same state the service writes.
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

    /**
     * Every confirmation the user has been shown, and their answer, in order.
     *
     * Kept because a transcript that silently drops a declined destructive action
     * is worse than one that shows it. The user asked "what happens when I
     * decline?" and this is the answer, rendered in the place they asked it.
     */
    private val _approvals = MutableStateFlow<List<ToolApproval>>(emptyList())
    val approvals: StateFlow<List<ToolApproval>> = _approvals.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

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
        // Mirror each terminal outcome into the transcript once, so the reason a
        // run ended is visible afterwards rather than only while it was happening.
        //
        // Identity (`!==`) rather than equality, and seeded from the current
        // value: a StateFlow replays its current value to every new collector, and
        // this collector is re-subscribed whenever the scope restarts. Two runs
        // that both end in `Cancelled` are equal data classes, so equality would
        // silently swallow the second one.
        var lastTerminal: RunState.Finished? = gateway.runState.value as? RunState.Finished
        scope.launch {
            gateway.runState.collect { state ->
                if (state !is RunState.Finished) return@collect
                if (state === lastTerminal) return@collect
                lastTerminal = state
                val outcome = state.outcome
                if (outcome is RunOutcome.Answer) {
                    _messages.update { it + ChatMessage.Assistant(outcome.text) }
                } else {
                    _messages.update { it + ChatMessage.Notice(outcome.notice) }
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
        _approvals.update {
            it + ToolApproval(toolName = pending.toolName, approved = approved)
        }
    }

    fun clear() {
        _messages.value = emptyList()
        _approvals.value = emptyList()
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

/** One row of the transcript. A flat list, not a tree: the runtime is a loop. */
sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String) : ChatMessage
    data class Notice(val text: String) : ChatMessage
}

/** The user's answer to one `AwaitingConfirmation`, kept for the transcript. */
data class ToolApproval(val toolName: String, val approved: Boolean)
