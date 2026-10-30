package dev.localintelligence.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.localintelligence.app.AgentGateway
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.ModelAvailabilityHolder
import dev.localintelligence.app.RunOutcome
import dev.localintelligence.app.RunState
import dev.localintelligence.app.RunningTool
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.isActive
import dev.localintelligence.app.isTerminal
import android.speech.SpeechRecognizer
import dev.localintelligence.app.media.LiveDictation
import dev.localintelligence.app.media.PendingAttachment
import dev.localintelligence.app.media.PendingAttachments
import dev.localintelligence.app.media.ShownAttachment
import dev.localintelligence.app.media.VoiceClipRecorder
import dev.localintelligence.app.notice
import dev.localintelligence.app.speech.Speaker
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.context.GenerationProse
import dev.localintelligence.core.model.ChatMessage as CoreChatMessage
import dev.localintelligence.core.model.MediaInput
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.settings.CoreSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * `:app` is compile-only: it has no JVM unit-test source set, so nothing in
 * this class is asserted by a test today.
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
    /**
     * Where the next run finds the attachments the user queued. Production passes
     * the container's holder; the default keeps a bare view model self-contained.
     */
    private val attachmentQueue: PendingAttachments = PendingAttachments(),
    /**
     * What the loaded model reads. A function, not a value, because the model is
     * loaded after this view model exists and can be swapped while the screen lives.
     */
    private val capabilities: () -> ModelCapabilities = { ModelCapabilities.UNKNOWN },
    /** Null in hosts without speech output; every speech call then does nothing. */
    private val speaker: Speaker? = null,
    /** Read at the end of each run, so a settings change applies from the next answer. */
    private val readRepliesAloud: () -> Boolean = { false },
    /**
     * Speech recognition for hands-free mode with a model that cannot read audio. Built by the
     * host, because it needs a Context. Null means hands-free can only use the audio path.
     */
    private val dictation: LiveDictation? = null,
    /** Hands-free pause before a turn is sent, in seconds. Read at each listen. */
    private val handsFreeSilence: () -> Float = { CoreSettings.HANDS_FREE_SILENCE.default },
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    /**
     * Held as a property, not just a constructor parameter, because the public
     * accessors below read it long after construction. Making it a parameter
     * would silently drop it on the floor the moment `init` finished.
     */
    private val modelAvailability: ModelAvailabilityHolder = modelAvailability

    /** Attachments queued in the composer for the next message. Not yet sent. */
    private val _attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingAttachment>> = _attachments.asStateFlow()

    /** A one-line complaint from the composer, such as a refused photo. Null when there is none. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * Hands-free mode. Declared before the `init` block below, which reads it: the collectors
     * there can run before the constructor returns.
     */
    private val _handsFree = MutableStateFlow(HandsFreeState())

    /** Hands-free mode: where it is now, and why it last stopped when the user did not stop it. */
    val handsFree: StateFlow<HandsFreeState> = _handsFree.asStateFlow()

    /** The one thing hands-free mode is waiting on: a relisten, or the end of speech. Cancelled on stop. */
    private var handsFreeJob: Job? = null

    /** Listens in a row that heard nothing. Two stop the mode. */
    private var emptyListens = 0

    /**
     * Records voice clips. A finished clip becomes a composer attachment. Built
     * here, after [scope], because its completion callback needs [_attachments].
     */
    private val voice = VoiceClipRecorder(scope) { wav, seconds ->
        _attachments.update { it + PendingAttachment(MediaInput(wav, "audio/wav"), ShownAttachment.Voice(seconds)) }
    }

    /** Seconds of the clip being recorded, or null while not recording. */
    val voiceSeconds: StateFlow<Int?> get() = voice.elapsedSeconds

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /**
     * Guards every transcript mutation.
     *
     * The trace collector and the run-state collector both write here, and the
     * row-index bookkeeping below is only correct if a trim and the index
     * remap that has to follow it cannot be split by the other collector.
     * `MutableStateFlow.update` is CAS-based and may run its block more than
     * once, so the block must stay free of side effects; the map writes are
     * therefore done under this lock, outside the flow update.
     */
    private val transcriptLock = Any()

    /**
     * Which transcript row each trace entry is drawn in.
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
     * mistaken for the previous run's. The maps are cleared when a run starts
     * only so they do not grow for the life of the Activity.
     */
    private val toolRows = java.util.IdentityHashMap<StepTrace, Int>()

    /**
     * The same bookkeeping for narrated `GENERATION` entries.
     *
     * A generation is narrated once and never again. The trace is republished
     * whole — at an approval and again at the end — so without this the same
     * words would be appended a second time at the terminal publication and the
     * answer would appear twice in the transcript.
     */
    private val narratedRows = java.util.IdentityHashMap<StepTrace, Int>()

    /** Whether [seedRestored] has run. Guarded by [transcriptLock]. */
    private var transcriptSeeded = false

    val runState: StateFlow<RunState> get() = gateway.runState
    val trace: StateFlow<List<StepTrace>> get() = gateway.trace

    /** The call in flight, drawn as a live row until its result replaces it. */
    val runningTool: StateFlow<RunningTool?> get() = gateway.runningTool

    /**
     * The live token stream, suppressed once the run is over.
     *
     * WHY NOT A PASSTHROUGH: `AgentViewModel.publish` writes the terminal
     * state and clears `streamingText` as two separate statements, and
     * `StateFlow` emission is asynchronous. There is therefore a window in
     * which the run is already `Finished` — so this class has committed the
     * final answer as a permanent `Assistant` row — while the live bubble is
     * still showing the same words. The user sees the answer twice. Hiding the
     * stream on the terminal transition closes that window at the source rather
     * than relying on the two writes landing in one frame.
     */
    val streamingText: StateFlow<String> =
        combine(gateway.runState, gateway.streamingText) { state, text ->
            if (state.isTerminal) "" else text
        }.stateIn(scope, SharingStarted.Eagerly, "")

    /**
     * Whether a model is resident and able to answer.
     *
     * Read by the chat screen to decide whether the composer is worth showing.
     * It is a [StateFlow] and not a snapshot because the answer changes while
     * the screen is alive: importing a model in the manager makes the composer
     * usable without the user coming back to a blank chat.
     */
    val modelState: StateFlow<ModelAvailability> get() = modelAvailability.state

    /** The reply being read aloud, or null. Rows compare against it to show Stop instead of Read. */
    val speakingText: StateFlow<String?> = speaker?.speakingText ?: MutableStateFlow<String?>(null).asStateFlow()

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
        var lastTerminal: RunState.Finished? = gateway.runState.value as? RunState.Finished
        var wasTerminal = gateway.runState.value.isTerminal
        scope.launch {
            gateway.trace.collect { steps -> renderTrace(steps, terminal = null) }
        }
        scope.launch {
            gateway.runState.collect { state ->
                // A run is starting. Drop the previous run's entries so the maps
                // do not accumulate for the life of the Activity. Not a
                // correctness requirement — each run's entries are new objects,
                // so nothing is ever mistaken for an older call — but an
                // unbounded map in a ViewModel is a leak with extra steps.
                if (wasTerminal && !state.isTerminal) {
                    synchronized(transcriptLock) {
                        toolRows.clear()
                        narratedRows.clear()
                    }
                }
                wasTerminal = state.isTerminal

                // A tool needs a human. Hands-free mode cannot answer by voice, so it stops.
                // The run itself waits, untouched, for the Allow or Deny on screen.
                if (state is RunState.AwaitingApproval && _handsFree.value.phase != HandsFreePhase.Off) {
                    stopHandsFree("Hands-free paused: tap Allow or Deny.")
                }

                if (state !is RunState.Finished) return@collect
                if (state === lastTerminal) return@collect
                lastTerminal = state

                // The narrative first, then the outcome.
                //
                // Both publications happen inside one `publish()` in
                // `AgentViewModel` and reach this class over two independent
                // collectors, so whichever ran first used to decide the order.
                // The transcript could read "here is your answer" and then list
                // the calls that produced it underneath, which reads as though
                // the agent did all of its work after answering.
                renderTrace(gateway.trace.value, terminal = state.outcome)

                // Read aloud: the final answer, once, when the run completes. Only the
                // answer is spoken, never streamed text or tool rows; a cancelled or
                // failed run has no answer and is silent. Hands-free mode speaks its own
                // answers, so a reply is never read twice.
                val answer = state.outcome as? RunOutcome.Answer
                val spokenByHandsFree = handsFreeRunEnded(state.outcome)
                if (answer != null && !spokenByHandsFree && readRepliesAloud()) speaker?.speak(answer.text)
            }
        }
    }

    // ------------------------------------------------------------ hands-free

    /**
     * Turns hands-free mode on: listen, send what was said, wait for the answer, speak it,
     * listen again. Starts only from an idle chat; the screen asks for the microphone first.
     */
    fun startHandsFree() {
        if (_handsFree.value.phase != HandsFreePhase.Off) return
        if (speaker == null) {
            _handsFree.value = HandsFreeState(notice = "Hands-free isn't available on this phone.")
            return
        }
        blockedReason?.let { reason ->
            _handsFree.value = HandsFreeState(notice = reason)
            return
        }
        if (!gateway.runState.value.isTerminal) {
            _handsFree.value = HandsFreeState(notice = "Wait for the current answer, then start hands-free.")
            return
        }
        emptyListens = 0
        startListening()
    }

    /**
     * Turns hands-free mode off. Sets the phase first, so a recording or a recognizer result
     * that arrives afterwards is ignored. [notice] says why, when the user did not stop it.
     * The run in progress, if any, is left alone.
     */
    fun stopHandsFree(notice: String? = null) {
        val was = _handsFree.value.phase
        if (was == HandsFreePhase.Off && notice == null) return
        handsFreeJob?.cancel()
        handsFreeJob = null
        _handsFree.value = HandsFreeState(HandsFreePhase.Off, notice)
        voice.stop()
        dictation?.stop()
        if (was != HandsFreePhase.Off) speaker?.stop()
    }

    private fun startListening() {
        _handsFree.value = HandsFreeState(HandsFreePhase.Listening)
        if (modelCapabilities().supportsAudioInput) listenForClip() else listenForWords()
    }

    /** Audio-capable model: record until the user pauses. The clip is sent as it is, like a voice note. */
    private fun listenForClip() {
        val started = voice.startUntilPause(handsFreeSilence()) { wav, seconds ->
            scope.launch { handsFreeClip(wav, seconds) }
        }
        if (!started) stopHandsFree("The microphone is not available. Check that the app may record audio.")
    }

    /** Text-only model: the recognizer decides when speech ends, and its words are sent. */
    private fun listenForWords() {
        val started = dictation?.startHandsFree(
            current = _input.value,
            silenceMillis = (handsFreeSilence() * 1000).toLong(),
            onText = { _input.value = it },
            onEnd = { spoken, error -> handsFreeWords(spoken, error) },
        ) ?: false
        if (!started) stopHandsFree("Speech recognition is not available on this phone.")
    }

    private fun handsFreeClip(wav: ByteArray?, seconds: Int) {
        if (_handsFree.value.phase != HandsFreePhase.Listening) return
        if (wav == null) {
            emptyListen()
            return
        }
        sendHandsFree(typed = "", clip = PendingAttachment(MediaInput(wav, "audio/wav"), ShownAttachment.Voice(seconds)))
    }

    private fun handsFreeWords(spoken: String?, error: Int?) {
        if (_handsFree.value.phase != HandsFreePhase.Listening) return
        when {
            error != null && error !in SILENT_LISTEN_ERRORS ->
                stopHandsFree("Hands-free stopped.")
            spoken == null -> emptyListen()
            else -> sendHandsFree(typed = _input.value.trim(), clip = null)
        }
    }

    /** Sends one heard turn. [clip] is the recorded speech, when the model takes audio. */
    private fun sendHandsFree(typed: String, clip: PendingAttachment?) {
        emptyListens = 0
        if (clip != null) _attachments.update { it + clip }
        if (!canSubmit(hasText = typed.isNotEmpty())) {
            clip?.let { heard -> _attachments.update { current -> current.filterNot { it === heard } } }
            stopHandsFree("Hands-free stopped.")
            return
        }
        _handsFree.value = HandsFreeState(HandsFreePhase.Thinking)
        _input.value = ""
        dispatch(typed)
    }

    /**
     * Counts a listen that heard nothing. Two in a row stop the mode, so a quiet room
     * does not keep the microphone open for good.
     */
    private fun emptyListen() {
        emptyListens++
        if (emptyListens >= 2) stopHandsFree("Hands-free stopped: nothing heard.")
        else relistenSoon()
    }

    /**
     * Called when a run has finished. In Thinking, an answer is spoken here and the mode
     * listens again afterwards. Any other end stops the mode, with a reason unless it was
     * the user's Stop. Returns true when the answer is being spoken by hands-free mode.
     */
    private fun handsFreeRunEnded(outcome: RunOutcome): Boolean {
        if (_handsFree.value.phase != HandsFreePhase.Thinking) return false
        return when (outcome) {
            is RunOutcome.Answer -> {
                speakForHandsFree(outcome.text)
                true
            }
            RunOutcome.Cancelled -> {
                stopHandsFree()
                false
            }
            RunOutcome.StepLimitReached -> {
                stopHandsFree("Hands-free stopped.")
                false
            }
            is RunOutcome.Failed -> {
                stopHandsFree("Hands-free stopped.")
                false
            }
        }
    }

    /**
     * Reads the answer, then listens again once speech has ended. [Speaker.speak] sets
     * `speakingText` synchronously, so the wait starts from the text being spoken. When there
     * is nothing to say, `speakingText` is never set and the wait returns at once.
     */
    private fun speakForHandsFree(text: String) {
        val tts = speaker
        if (tts == null) {
            stopHandsFree("Hands-free isn't available on this phone.")
            return
        }
        _handsFree.value = HandsFreeState(HandsFreePhase.Speaking)
        tts.speak(text)
        handsFreeJob = scope.launch {
            tts.speakingText.first { it == null }
            relistenSoon()
        }
    }

    /**
     * Listens again after a short pause. The pause lets the recognizer release the
     * microphone and lets the tail of the spoken answer die away before the mic opens.
     */
    private fun relistenSoon() {
        handsFreeJob = scope.launch {
            delay(RELISTEN_DELAY_MS)
            startListening()
        }
    }

    // ------------------------------------------------------------ transcript

    /**
     * What the terminal publication adds on top of the narrated trace.
     *
     * @param answerText the run's answer, at full fidelity. Non-null only for
     *   [RunOutcome.Answer], and the reason the final row is not taken from the
     *   trace: the trace clips a generation to a fixed budget, and a long
     *   answer must not be shown clipped.
     * @param narrateInterruptedFinal whether the last generation, which the
     *   runtime reports as not completed, still counts as something the user
     *   should see. True for a cancellation, where it is the answer the user
     *   was in the middle of reading. False for a failure, where it is the
     *   native library's error string and belongs in the trace screen.
     */
    private class TerminalNarrative(
        val outcome: RunOutcome,
        val answerText: String?,
        val narrateInterruptedFinal: Boolean,
    )

    /**
     * Renders the trace into the transcript, in the order the agent acted.
     *
     * Idempotent, keyed on trace-entry identity: a second call over a list that
     * has already been drawn adds nothing, fills in an observation that has
     * since arrived, and never duplicates a row. The trace is republished whole
     * at an approval and again at the end, so this is called more than once per
     * run and the property is load-bearing rather than defensive.
     *
     * ORDER IS THE POINT. Within a step the runtime appends `GENERATION` and
     * then `TOOL_CALL`, and this walks the list in that order, so the words the
     * model said before acting are always drawn above the call it then made.
     * The previous version drew only the calls and committed the final answer,
     * so everything the model said before a tool call existed for the length of
     * the decode and was then erased — the user never saw it at all.
     */
    private fun renderTrace(steps: List<StepTrace>, terminal: RunOutcome?) {
        val narrative = terminal?.let {
            TerminalNarrative(
                outcome = it,
                answerText = (it as? RunOutcome.Answer)?.text,
                // A cancelled generation is the answer the user was reading.
                // A failed one is `native generation failed: …`, which
                // `transcriptLine` exists specifically to keep out of the chat.
                narrateInterruptedFinal = it is RunOutcome.Cancelled,
            )
        }

        val generations = steps.filter { it.kind == StepTrace.Kind.GENERATION }
        val lastGeneration = generations.lastOrNull()

        // Built purely, then assigned once: `StateFlow.update` may run its block
        // more than once, so nothing here may have a side effect.
        val additions = ArrayList<ChatMessage>()
        val toolPlacements = ArrayList<Pair<StepTrace, Int>>()
        val narrationPlacements = ArrayList<Pair<StepTrace, Int>>()
        val observationFills = ArrayList<Pair<Int, StepTrace>>()
        val narrationFixes = ArrayList<Pair<Int, String>>()

        var base = 0
        val baseSize = synchronized(transcriptLock) { _messages.value.size }

        for (entry in steps) {
            when (entry.kind) {
                StepTrace.Kind.GENERATION -> {
                    val isFinal = entry === lastGeneration
                    val alreadyNarrated = synchronized(transcriptLock) { narratedRows[entry] }

                    val text = when {
                        // The answer, at full fidelity rather than the trace's
                        // clipped copy. This is the one row that is never taken
                        // from the trace.
                        isFinal && narrative?.answerText != null -> narrative.answerText

                        // The generation the user was watching when they hit
                        // STOP. Incomplete, and shown as such by the notice the
                        // caller appends after it.
                        isFinal && narrative?.narrateInterruptedFinal == true ->
                            GenerationProse.display(entry.detail)

                        // A generation the runtime did not complete, and which
                        // is not the cancelled one: malformed output, or a
                        // generation cut at the output limit. Not an answer, and
                        // not something to narrate as one.
                        !entry.success -> null

                        // The ordinary case: the words before this step's call.
                        else -> GenerationProse.display(entry.detail)
                    }
                    if (text.isNullOrBlank()) continue

                    // RULE 4, and the race it exists to survive: the trace and
                    // the run state are two independent StateFlows, so the trace
                    // collector can narrate the final generation from its
                    // CLIPPED detail before the state collector arrives with the
                    // unclipped answer. Skipping the second commit would leave
                    // the user with the truncated copy forever, so the full
                    // answer REPLACES the row already drawn. Same row, same
                    // place, corrected text — never a second copy.
                    if (alreadyNarrated != null) {
                        if (isFinal && narrative?.answerText != null) {
                            narrationFixes += alreadyNarrated to narrative.answerText
                        }
                        continue
                    }

                    narrationPlacements += entry to (baseSize + base)
                    additions += ChatMessage.Assistant(text)
                    base += 1
                }

                StepTrace.Kind.TOOL_CALL -> {
                    // The matching OBSERVATION is the next entry with the same
                    // step number. The loop dispatches at most one tool per
                    // step, so the step number is a sound pairing key.
                    val observation = steps.firstOrNull {
                        it.kind == StepTrace.Kind.OBSERVATION && it.step == entry.step
                    }
                    val known = synchronized(transcriptLock) { toolRows[entry] }
                    if (known != null) {
                        if (observation == null) continue
                        observationFills += known to observation
                        continue
                    }
                    toolPlacements += entry to (baseSize + base)
                    additions += ChatMessage.ToolStep(
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
                    base += 1
                }

                else -> Unit
            }
        }

        if (additions.isEmpty() && observationFills.isEmpty() && narrationFixes.isEmpty()) {
            appendTerminalNotices(steps, narrative, lastGeneration)
            return
        }

        if (observationFills.isNotEmpty() || narrationFixes.isNotEmpty()) {
            // In-place only, so the length does not change and no recorded index
            // moves. A tool row is only ever completed once, and a narrated row
            // is only ever corrected once.
            _messages.update { list ->
                // Copy once, on the first write, not by comparing identities:
                // `next === list` stays true for every later iteration and the
                // assignment below would then land on the shared, immutable
                // list. An explicit flag cannot be re-entered by a second pass.
                var copy: MutableList<ChatMessage>? = null
                for ((index, observation) in observationFills) {
                    if (index !in list.indices) continue
                    val row = list[index]
                    if (row !is ChatMessage.ToolStep) continue
                    if (row.observation != null) continue
                    val target = copy ?: list.toMutableList().also { copy = it }
                    target[index] = row.completedBy(observation)
                }
                for ((index, text) in narrationFixes) {
                    if (index !in list.indices) continue
                    val row = list[index]
                    if (row !is ChatMessage.Assistant) continue
                    if (row.text == text) continue
                    val target = copy ?: list.toMutableList().also { copy = it }
                    target[index] = ChatMessage.Assistant(text)
                }
                copy ?: list
            }
        }

        appendRows(additions, toolPlacements, narrationPlacements)
        appendTerminalNotices(steps, narrative, lastGeneration)
    }

    /**
     * Appends whatever the outcome line has to add after the narrative.
     *
     * The notice is the affordance for "this did not finish". A truncated
     * answer is committed as an ordinary `Assistant` row and the row directly
     * beneath it says so, which is the difference between a user who knows the
     * sentence was cut off and one who believes they were shown a finished
     * thought.
     */
    private fun appendTerminalNotices(
        steps: List<StepTrace>,
        narrative: TerminalNarrative?,
        lastGeneration: StepTrace?,
    ) {
        if (narrative == null) return
        val outcome = narrative.outcome

        // A completed answer speaks for itself. A notice under it would be
        // noise, and "Done." after a real answer is a claim about work.
        if (outcome is RunOutcome.Answer) {
            // An answer the runtime reports as NOT completed reached this point
            // when the model hit its output limit mid-`<respond>`. The parser
            // takes an unterminated respond as the answer, so without this the
            // user gets a clipped sentence with nothing marking it as clipped.
            if (lastGeneration != null && !lastGeneration.success) {
                appendRows(listOf(ChatMessage.Notice(TRUNCATED_AT_LIMIT)))
            }
            return
        }

        // "The text above" is only true when some text was drawn above the notice.
        val hasPartialText = lastGeneration != null && GenerationProse.display(lastGeneration.detail).isNotBlank()
        val notice = when (outcome) {
            is RunOutcome.Cancelled -> if (hasPartialText) CANCELLED_MID_ANSWER else outcome.transcriptLine()
            else -> outcome.transcriptLine()
        }
        appendRows(listOf(ChatMessage.Notice(notice)))
    }

    /**
     * Appends rows, then enforces the cap, then fixes the recorded indices.
     *
     * These three are one operation and must not be split: the cap drops rows
     * from the *front* of the list, which invalidates every index recorded so
     * far, and an index that silently addresses the wrong row is worse than an
     * absent one — a tool result would be written into somebody else's message.
     */
    private fun appendRows(
        rows: List<ChatMessage>,
        toolPlacements: List<Pair<StepTrace, Int>> = emptyList(),
        narrationPlacements: List<Pair<StepTrace, Int>> = emptyList(),
    ) {
        if (rows.isEmpty() && toolPlacements.isEmpty() && narrationPlacements.isEmpty()) return
        synchronized(transcriptLock) {
            val current = _messages.value
            val grown = current + rows
            val result = trim(grown)
            _messages.value = result.kept

            // Remap recorded indices to their new positions after trim.
            // Rows dropped from the transcript are forgotten rather than left
            // pointing at an unrelated row.
            val remap = result.remap
            if (remap != null) {
                remapIndices(toolRows, remap)
                remapIndices(narratedRows, remap)
                for ((entry, provisional) in toolPlacements) {
                    val at = if (provisional in remap.indices) remap[provisional] else -1
                    if (at >= 0) toolRows[entry] = at
                }
                for ((entry, provisional) in narrationPlacements) {
                    val at = if (provisional in remap.indices) remap[provisional] else -1
                    if (at >= 0) narratedRows[entry] = at
                }
            } else {
                for ((entry, provisional) in toolPlacements) toolRows[entry] = provisional
                for ((entry, provisional) in narrationPlacements) narratedRows[entry] = provisional
            }
        }
    }

    private fun remapIndices(map: java.util.IdentityHashMap<StepTrace, Int>, remap: IntArray) {
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val oldIndex = entry.value
            val newIndex = if (oldIndex in remap.indices) remap[oldIndex] else -1
            if (newIndex >= 0) {
                entry.setValue(newIndex)
            } else {
                iterator.remove()
            }
        }
    }

    private class TrimResult(
        val kept: List<ChatMessage>,
        val remap: IntArray?,
    )

    /**
     * Enforces [MAX_TRANSCRIPT_ROWS], dropping from the front.
     *
     * WHY THE FRONT: the newest exchange is the one the user is looking at, and
     * a chat that evicts its own most recent messages to keep old ones is
     * backwards.
     *
     * WHY THE USER'S OWN MESSAGES ARE KEPT LONGER: an assistant row is
     * reconstructible from the model's behaviour, but "what did I ask this app"
     * is the user's own record and there is no second copy of it anywhere. So a
     * `User` row is only ever dropped when there is nothing else left to drop.
     */
    private fun trim(rows: List<ChatMessage>): TrimResult {
        var excess = rows.size - MAX_TRANSCRIPT_ROWS
        if (excess <= 0) return TrimResult(rows, null)
        val doomed = BooleanArray(rows.size)
        for (index in rows.indices) {
            if (excess <= 0) break
            if (rows[index] is ChatMessage.User) continue
            doomed[index] = true
            excess -= 1
        }
        for (index in rows.indices) {
            if (excess <= 0) break
            if (doomed[index]) continue
            doomed[index] = true
            excess -= 1
        }
        val kept = ArrayList<ChatMessage>(MAX_TRANSCRIPT_ROWS)
        val remap = IntArray(rows.size) { -1 }
        for (index in rows.indices) {
            if (!doomed[index]) {
                remap[index] = kept.size
                kept.add(rows[index])
            }
        }
        return TrimResult(kept, remap)
    }

    // ------------------------------------------------------------------- intents

    fun onInputChange(value: String) {
        _input.value = value
    }

    /** The loaded model's capabilities, read now. The composer asks this to decide what it can attach. */
    fun modelCapabilities(): ModelCapabilities = capabilities()

    fun send() {
        val typed = _input.value.trim()
        if (!canSubmit(hasText = typed.isNotEmpty())) {
            refuseQueued()
            return
        }
        _input.value = ""
        speaker?.stop()
        dispatch(typed)
    }

    /** Convenience for a SEND intent or a suggestion tap. */
    fun send(text: String) {
        if (text.isBlank() || !canSubmit(hasText = true)) {
            refuseQueued()
            return
        }
        speaker?.stop()
        dispatch(text.trim())
    }

    /**
     * A message that cannot be sent takes its queued attachments with it. The assistant
     * queues a screen capture before forwarding its text; if that text is refused, the
     * capture must not wait for the user's next, unrelated message.
     */
    private fun refuseQueued() {
        attachmentQueue.clear()
    }

    /**
     * Whether a message can go now.
     *
     * Refused before the transcript is touched. Appending a user message that no
     * run will ever answer is the single most dishonest thing this screen could
     * do: the user would be looking at their own question and silence.
     */
    private fun canSubmit(hasText: Boolean): Boolean =
        (hasText || _attachments.value.isNotEmpty()) &&
            gateway.runState.value.isTerminal &&
            blockedReason == null &&
            voice.elapsedSeconds.value == null

    /**
     * Starts a run with [typed] and whatever is attached.
     *
     * The composer's attachments join the queue, and the transcript row is drawn
     * from the queue itself, so the row shows exactly what the run will receive.
     * That includes an assistant screen capture, which the assistant queued
     * before forwarding its text here.
     */
    private fun dispatch(typed: String) {
        attachmentQueue.add(_attachments.value)
        _attachments.value = emptyList()
        _notice.value = null
        _handsFree.update { if (it.notice == null) it else it.copy(notice = null) }
        val shown = attachmentQueue.snapshot().map { it.shown }
        _messages.update { it + ChatMessage.User(typed, shown) }
        // ExecutionService refuses a blank task, so media alone still needs words to run on.
        gateway.start(typed.ifEmpty { DEFAULT_MEDIA_TASK })
    }

    /**
     * Adds a picture from the photo picker.
     *
     * [encode] runs on a background thread: decoding and re-encoding a photo is
     * the expensive part and must not happen on the main thread. It returns null
     * for a file that is not a picture this app can read.
     */
    fun attachPicture(encode: () -> PendingAttachment?) {
        if (pictureCount() >= MAX_PICTURES_PER_MESSAGE) {
            showNotice("A message can carry up to $MAX_PICTURES_PER_MESSAGE photos.")
            return
        }
        scope.launch {
            val item = withContext(Dispatchers.Default) { runCatching { encode() }.getOrNull() }
            if (item == null) {
                showNotice("That file could not be read as a picture.")
                return@launch
            }
            _attachments.update { current ->
                if (current.count { it.shown is ShownAttachment.Picture } >= MAX_PICTURES_PER_MESSAGE) {
                    current
                } else {
                    current + item
                }
            }
        }
    }

    fun removeAttachment(index: Int) {
        _attachments.update { current ->
            if (index in current.indices) current.filterIndexed { i, _ -> i != index } else current
        }
    }

    /**
     * Starts a voice clip. One clip per message, because each one is a full
     * audio input to the model and a second would double the cost of every step.
     */
    fun startVoiceClip() {
        if (isBusy) return
        if (_attachments.value.any { it.shown is ShownAttachment.Voice }) {
            showNotice("A message can carry one voice clip. Remove it to record another.")
            return
        }
        _notice.value = null
        if (!voice.start()) {
            showNotice("The microphone is not available. Check that the app may record audio.")
        }
    }

    /** Ends the clip being recorded. It arrives as an attachment a moment later. */
    fun stopVoiceClip() {
        voice.stop()
    }

    /** The screen is gone, so the microphone must not keep recording for its remaining seconds. */
    override fun onCleared() {
        stopHandsFree()
        dictation?.release()
        voice.stop()
        super.onCleared()
    }

    private var noticeJob: Job? = null

    /** A one-line complaint under the field. It goes away by itself after a few seconds. */
    fun showNotice(text: String) {
        _notice.value = text
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_SHOWN_MS)
            if (_notice.value == text) _notice.value = null
        }
    }

    private fun pictureCount(): Int = _attachments.value.count { it.shown is ShownAttachment.Picture }

    /** The STOP button. Cooperative: the loop returns `Cancelled` when it can. */
    fun stop() {
        stopHandsFree()
        speaker?.stop()
        gateway.cancel()
    }

    /** The speaker button on an answer: reads it, or stops it if it is the one being read. */
    fun toggleSpeak(text: String) {
        speaker?.toggle(text)
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
        appendRows(listOf(ChatMessage.Approval(toolName = pending.toolName, approved = approved)))
    }

    /**
     * Shows the conversation restored at launch, in an empty transcript.
     *
     * The turns are already in the session, so the model answers from them; without
     * this the user sees an empty chat and answers built on history they cannot see.
     * Only User and Assistant turns have a row. Idempotent: the screen that calls this
     * is recomposed on rotation, while this object survives it.
     */
    fun seedRestored(turns: List<CoreChatMessage>) {
        synchronized(transcriptLock) {
            if (transcriptSeeded) return
            transcriptSeeded = true
            // Never over rows already drawn: those belong to a run, and the indices
            // recorded for them address this list.
            if (_messages.value.isNotEmpty()) return
            _messages.value = turns.mapNotNull { turn ->
                when (turn) {
                    is CoreChatMessage.User -> ChatMessage.User(turn.text)
                    is CoreChatMessage.Assistant -> ChatMessage.Assistant(turn.text)
                    else -> null
                }
            }.takeLast(MAX_TRANSCRIPT_ROWS)
        }
    }

    fun clear() {
        synchronized(transcriptLock) {
            _messages.value = emptyList()
            // The row indices in `toolRows` no longer address anything.
            toolRows.clear()
            narratedRows.clear()
        }
    }

    class Factory(
        private val gateway: AgentGateway,
        private val modelAvailability: ModelAvailabilityHolder = ModelAvailabilityHolder(),
        private val attachmentQueue: PendingAttachments = PendingAttachments(),
        private val capabilities: () -> ModelCapabilities = { ModelCapabilities.UNKNOWN },
        private val speaker: Speaker? = null,
        private val readRepliesAloud: () -> Boolean = { false },
        private val dictation: LiveDictation? = null,
        private val handsFreeSilence: () -> Float = { CoreSettings.HANDS_FREE_SILENCE.default },
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                "unknown ViewModel ${modelClass.name}"
            }
            return ChatViewModel(
                gateway,
                modelAvailability = modelAvailability,
                attachmentQueue = attachmentQueue,
                capabilities = capabilities,
                speaker = speaker,
                readRepliesAloud = readRepliesAloud,
                dictation = dictation,
                handsFreeSilence = handsFreeSilence,
            ) as T
        }
    }
}

/**
 * ===================================================================
 *  THE COMMIT RULE
 * ===================================================================
 *
 * The loop is a stream of interleaved events — tokens, a tool call, an
 * observation, more tokens, a final answer — and the transcript is a flat list.
 * These are the rules that turn one into the other. They are written down here
 * because the previous implementation had none of them, and every gap that left
 * is a bug the user could hit.
 *
 * 1. A generation becomes a permanent `Assistant` message ONCE, and only once
 *    the trace says the step it belongs to is over. An arriving token is not a
 *    commit: it is a live preview in a bubble that is not part of the list.
 *    Committing per token is what floods a transcript, and the preview already
 *    covers "show it to me as it happens".
 *
 * 2. Text the model produced BEFORE a tool call is kept, not overwritten. It is
 *    committed as its own `Assistant` row, above the `ToolStep` row for that
 *    call, because the runtime appends `GENERATION` before `TOOL_CALL` within a
 *    step and this walks the trace in that order. Previously those words existed
 *    only in the live bubble and were erased by the next step's tokens: the
 *    user watched the agent say it and then never saw it again. Losing the
 *    model's own words around a tool call is a correctness bug, not a cosmetic
 *    one.
 *
 * 3. The final answer is committed from the run outcome, never from the trace.
 *    `AgentController` clips a traced generation to a fixed character budget, so
 *    a long answer taken from the trace would be silently truncated. The outcome
 *    carries it at full fidelity.
 *
 * 4. Nothing is committed twice. Identity-keyed, because the trace is
 *    republished whole at an approval and again at the end; a key that was not
 *    identity would re-append on the second publication and show the answer
 *    twice.
 *
 * 5. A run that did not finish says so, in a `Notice` directly beneath the text
 *    it did produce. A cancelled run keeps its partial text and marks it
 *    incomplete. A truncated answer must never be indistinguishable from a
 *    finished one. `Notice` is the affordance that already exists for this; no
 *    new component was added.
 *
 * 6. The transcript is capped at [MAX_TRANSCRIPT_ROWS], dropping from the
 *    front, and the user's own messages outlive assistant output.
 */

/**
 * Hard ceiling on retained transcript rows.
 *
 * WHY A NUMBER: this list is held in a `StateFlow` for the life of the
 * Activity and is re-emitted in full on every change, and a `ToolStep` row
 * carries an observation the runtime clips to a couple of thousand characters.
 * An unbounded list is therefore a real leak on the one platform where RAM is
 * the product, and it is retained across rotation because the ViewModel
 * outlives the Activity.
 *
 * WHY THIS NUMBER: 200 rows is on the order of 30–50 exchanges — far more than
 * a phone conversation holds before the user scrolls away from it — and bounds
 * the worst case to a few hundred kilobytes, which is noise beside the
 * gigabytes of weights the same process is already holding resident.
 */
private const val MAX_TRANSCRIPT_ROWS = 200

/** The most photos one message carries. A vision encoder's cost grows with each one. */
internal const val MAX_PICTURES_PER_MESSAGE = 3

/** What a run is told when the user sent only attachments and no words. */
private const val DEFAULT_MEDIA_TASK = "Respond to what is attached."

/** Recognizer errors that mean "nothing was said", so the listen counts as empty rather than failed. */
private val SILENT_LISTEN_ERRORS = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

/** Pause between a finished answer, or an empty listen, and the next microphone open. */
private const val RELISTEN_DELAY_MS = 300L

/** Where hands-free mode is. Listening, Thinking and Speaking are the three states the screen shows. */
enum class HandsFreePhase { Off, Listening, Thinking, Speaking }

/** Hands-free mode as the chat screen shows it. [notice] is set when the mode stopped without the user's Stop. */
data class HandsFreeState(val phase: HandsFreePhase = HandsFreePhase.Off, val notice: String? = null)

/**
 * Said under an answer the model never finished producing.
 *
 * The parser accepts an unterminated `<respond>` as the answer, so hitting the
 * output limit mid-sentence yields a plausible-looking answer that is missing
 * its ending. Without this line the user has no way to tell that from a
 * complete thought.
 */
private const val TRUNCATED_AT_LIMIT = "Answer cut short."

private const val NOTICE_SHOWN_MS = 4_000L

/**
 * Said under the partial text of a run the user stopped.
 *
 * The words above are the model's, kept rather than discarded, and this is what
 * stops them reading as a finished answer.
 */
private const val CANCELLED_MID_ANSWER = "Stopped."

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
    /**
     * What the user sent. [attached] is what the row draws for the pictures and
     * voice clip, never the bytes the model read.
     */
    data class User(val text: String, val attached: List<ShownAttachment> = emptyList()) : ChatMessage
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
internal fun RunOutcome.transcriptLine(): String = when (this) {
    // The runtime's reason ("no valid action in 3 attempts", an exception name)
    // is for the Trace screen, which keeps it. The chat says what the user can do.
    is RunOutcome.Failed -> if (reason.contains(CONTEXT_TOO_SMALL, ignoreCase = true)) {
        "That is too long for this model. Try a shorter message."
    } else {
        "Something went wrong. Try again."
    }
    RunOutcome.StepLimitReached -> "I couldn't finish that. Try asking for one thing at a time."
    RunOutcome.Cancelled -> "Stopped."
    is RunOutcome.Answer -> notice
}

/** The start of the runtime's reason when the prompt no longer fits the context window. */
private const val CONTEXT_TOO_SMALL = "context window too small"

