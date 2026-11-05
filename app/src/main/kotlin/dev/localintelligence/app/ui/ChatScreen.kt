package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.android.skills.SkillRepository
import dev.localintelligence.android.tools.phonesearch.PhoneSearchLink
import dev.localintelligence.core.skill.SkillDraft
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.SimpleWording
import dev.localintelligence.app.RunOutcome
import dev.localintelligence.app.RunState
import dev.localintelligence.app.SetupState
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.isActive
import dev.localintelligence.app.media.LiveDictation
import dev.localintelligence.app.media.ShownAttachment
import dev.localintelligence.app.settings.SuggestedModel
import dev.localintelligence.core.agent.ReplySpeed
import dev.localintelligence.app.ui.components.ApprovalRow
import dev.localintelligence.app.ui.components.ChatComposer
import dev.localintelligence.app.ui.components.LocalTechnicalDetail
import dev.localintelligence.app.ui.components.MarkdownText
import dev.localintelligence.app.ui.components.PendingToolRow
import dev.localintelligence.app.ui.components.RunningToolRow
import dev.localintelligence.app.ui.components.SimpleChatTopRow
import dev.localintelligence.app.ui.components.SentAttachments
import dev.localintelligence.app.ui.components.ToolCallGroup
import dev.localintelligence.app.ui.components.MemoryRow
import dev.localintelligence.app.ui.components.ToolStepRow
import dev.localintelligence.app.settings.SettingsMemoryBlockStore
import dev.localintelligence.app.ui.components.TranscriptEntry
import dev.localintelligence.app.ui.components.clipLength
import dev.localintelligence.app.ui.components.groupToolCalls
import dev.localintelligence.app.ui.components.rememberMotionEnabled
import dev.localintelligence.core.hub.formatBytes
import dev.localintelligence.core.persona.Persona
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The chat. A message list, a text field, a send button, and a STOP button while
 * something is running.
 *
 * Boring on purpose. This is a debugging tool that happens to be a chat app, and
 * every feature added to the transcript is one more thing between a developer and
 * the answer to "why did the agent do that". [TraceView] is where the depth lives.
 *
 * ## WHAT LIVES IN A COMPONENT INSTEAD
 *
 * The composer, the model line and the tool rows moved to
 * `ui/components/`. That is not tidiness for its own sake: the composer carries
 * the one-handed geometry argument and the inset reasoning, and the tool rows
 * carry the rule about what collapsing may hide. Those are two paragraphs each
 * and they were burying the screen's actual logic, which is the transcript.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Whether a finished run of tool calls folds itself to one header line.
     *
     * Passed in, not read here: the host reads it from the settings store
     * (`CoreSettings.TOOL_CALLS_AUTO_COLLAPSE`), so this screen stays free of
     * preferences and the default matches the setting's own default.
     */
    toolCallsAutoCollapse: Boolean = true,
    /**
     * Keeps the screen on while a run is in flight (the setting, read by the host). Off by
     * default; the flag is set only for the length of a run and cleared when it ends.
     */
    keepScreenAwake: Boolean = false,
    /** Technical mode: each reply shows its speed line (prefill, first token, decode). */
    technical: Boolean = false,
    /**
     * The memory block, for the "Remembered: ..." line and Undo under a call that wrote it. Null
     * hides both, for a host with no block.
     */
    memoryBlock: SettingsMemoryBlockStore? = null,
    /** The skills store. Null hides "Save as skill", for hosts that have no store. */
    skills: SkillRepository? = null,
    /** Whether the "Save this as a skill?" offer for a repeated request is on (its Technical switch). */
    repeatOfferOn: Boolean = false,
    /** Whether the user has said "not now" to the offer with this key, from an earlier session. */
    repeatDeclined: (String) -> Boolean = { false },
    /** Records that the offer with this key is done: "not now", or saved. It is not offered again. */
    onDeclineRepeat: (String) -> Unit = {},
    /** The name from Settings, used in the empty chat's first line. Blank: no name. */
    assistantName: String = "",
    /** The model Simple mode recommends, offered on the empty chat when there is no model. */
    suggestion: SuggestedModel? = null,
    /** Opens the download screen for [suggestion]. */
    onGetSuggested: () -> Unit = {},
    /** Simple mode's first run. Null in Technical mode, which keeps the Hub card. */
    starter: SetupState? = null,
    onStartStarter: () -> Unit = {},
    onCancelStarter: () -> Unit = {},
    /**
     * Loads the saved personas. The empty chat offers the choice only when some exist, so with none the
     * screen is unchanged. Null hides the choice.
     */
    loadPersonas: (suspend () -> List<Persona>)? = null,
    /** Starts the chat with the persona picked on the empty chat (null for none). */
    onChoosePersona: (Long?) -> Unit = {},
    /**
     * Simple mode's New chat, in the chat itself (Settings has the same one). Null hides it. Shown only
     * once there are messages, and never while a run is in flight.
     */
    onNewChat: (() -> Unit)? = null,
) {
    // `collectAsStateWithLifecycle`, not `collectAsState`: a backgrounded app
    // should not keep recomposing a chat transcript nobody is looking at.
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    // "Save as skill" draft for the answer on screen, if the run used 2+ tools.
    // Shown in SkillEditorDialog; nothing is written until the user taps Save.
    val skillDraft = remember(messages) { saveAsSkillDraft(messages) }
    // The repeat offer for the answer on screen, hidden once the user answers it.
    val repeatContext = LocalContext.current
    val repeatOffer = remember(messages, repeatOfferOn) {
        if (skills != null && repeatOfferOn) repeatOfferFor(messages) else null
    }
    var repeatAnswered by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleOffer = repeatOffer?.takeIf { it.candidate.key != repeatAnswered && !repeatDeclined(it.candidate.key) }
    fun acceptRepeat(offer: RepeatOffer) {
        val store = skills ?: return
        val text = SkillDraft.render(offer.candidate.request, offer.steps, offer.candidate.tools.toSet())
        when (val saved = store.save(text)) {
            is SkillRepository.SaveResult.Saved -> {
                repeatAnswered = offer.candidate.key
                onDeclineRepeat(offer.candidate.key)
                Toast.makeText(repeatContext, repeatContext.getString(R.string.skill_saved), Toast.LENGTH_SHORT).show()
            }
            is SkillRepository.SaveResult.Refused ->
                Toast.makeText(repeatContext, saved.reason, Toast.LENGTH_LONG).show()
        }
    }
    var editingSkill by rememberSaveable { mutableStateOf<String?>(null) }
    editingSkill?.let { draft ->
        val store = skills
        val dialogContext = LocalContext.current
        if (store != null) {
            SkillEditorDialog(
                title = stringResource(R.string.save_as_skill),
                initialText = draft,
                onDismiss = { editingSkill = null },
                onConfirm = { text ->
                    when (val saved = store.save(text)) {
                        is SkillRepository.SaveResult.Saved -> {
                            editingSkill = null
                            Toast.makeText(dialogContext, dialogContext.getString(R.string.skill_saved), Toast.LENGTH_SHORT).show()
                            null
                        }
                        is SkillRepository.SaveResult.Refused -> saved.reason
                    }
                },
            )
        }
    }
    // The reply the user is reporting, or null. Saved, so a rotation does not close the sheet.
    var reportingReply by rememberSaveable { mutableStateOf<String?>(null) }
    reportingReply?.let { reply ->
        ReplyReportSheet(viewModel = viewModel, reply = reply, onDismiss = { reportingReply = null })
    }
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val speakingText by viewModel.speakingText.collectAsStateWithLifecycle()
    val rawStreaming by viewModel.streamingText.collectAsStateWithLifecycle()
    val running by viewModel.runningTool.collectAsStateWithLifecycle()
    // What the live bubble shows. Everything below reads this, so the bubble,
    // its row in the count and the empty state cannot disagree about it.
    val streaming = remember(rawStreaming) { liveBubbleText(rawStreaming) }
    // Read straight from the view model's snapshot state, not a StateFlow, so the field never lags a key behind.
    val input = viewModel.composerText
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    // Derived from the collected `runState` rather than read from
    // `viewModel.isBusy`, which is a plain getter over `runState.value`. A
    // non-observable read happens to be correct here only because this
    // composable also collects `runState`; deriving it keeps that guarantee
    // local instead of resting on a coincidence in another file.
    val transcribing by viewModel.transcribing.collectAsStateWithLifecycle()
    val busy = runState.isActive || transcribing
    // The view's keep-screen-on flag is the window flag FLAG_KEEP_SCREEN_ON, set for this
    // composition's run only. It is cleared when the run ends or the screen leaves composition.
    val view = LocalView.current
    DisposableEffect(keepScreenAwake && busy) {
        view.keepScreenOn = keepScreenAwake && busy
        onDispose { view.keepScreenOn = false }
    }
    // Simple mode says why in plain words; Technical keeps the state's own line.
    val blocked = if (technical) modelState.blockingReason(LocalContext.current) else SimpleWording.blocked(LocalContext.current, modelState)
    // Simple mode only: while the one-tap setup downloads or the first load runs, Send is offered and queues the message.
    val canQueue = !technical && (
        starter is SetupState.Downloading || starter is SetupState.Preparing ||
            modelState is ModelAvailability.Loading
    )
    // Retry and Edit both act on the last turn, so they are offered only there. A turn with
    // pictures or a voice clip cannot be sent again: the media bytes are not kept.
    val lastUserIndex = messages.indexOfLast { it is ChatMessage.User }
    // Read here so the memory rows redraw after an Undo, which bumps the store's revision.
    val memoryRevision = memoryBlock?.revision?.collectAsStateWithLifecycle()?.value ?: 0
    val lastAnswerIndex = messages.indexOfLast { it is ChatMessage.Assistant }
    val lastUserReplayable = (messages.getOrNull(lastUserIndex) as? ChatMessage.User)?.attached?.isEmpty() == true
    val canRetry = !busy && lastUserReplayable && lastAnswerIndex > lastUserIndex &&
        (runState as? RunState.Finished)?.outcome is RunOutcome.Answer
    val canEdit = !busy && lastUserReplayable
    // Simple mode offers Retry after a run that ended without an answer too. It resends the last message
    // (retryLast), so the person is never left at a dead end. Technical mode keeps its own notice as it was.
    val endedWithoutAnswer = (runState as? RunState.Finished)?.outcome.let { it is RunOutcome.Failed || it === RunOutcome.StepLimitReached }
    val canRetryFailure = !technical && !busy && lastUserReplayable && endedWithoutAnswer

    // Read only while the chat is empty: the persona choice is offered there and nowhere else.
    val chatEmpty = messages.isEmpty()
    val personas by produceState(initialValue = emptyList<Persona>(), chatEmpty) {
        value = if (chatEmpty && loadPersonas != null) loadPersonas() else emptyList()
    }

    val listState = rememberLazyListState()

    val context = LocalContext.current

    // Media input. What the loaded model reads decides what the mic does: a model
    // that reads audio gets the clip itself; any other model gets the spoken words
    // typed into the field, because a WAV it cannot read would be sent and ignored.
    val attached by viewModel.attachments.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val voiceSeconds by viewModel.voiceSeconds.collectAsStateWithLifecycle()
    val handsFree by viewModel.handsFree.collectAsStateWithLifecycle()
    val interrupted by viewModel.interruptedRun.collectAsStateWithLifecycle()
    val handsFreeOn = handsFree.phase != HandsFreePhase.Off
    val canReadAudio = remember(modelState) { viewModel.modelCapabilities().supportsAudioInput }
    val dictation = remember { LiveDictation(context.applicationContext) }
    DisposableEffect(dictation) { onDispose { dictation.release() } }
    val dictating by dictation.listening.collectAsStateWithLifecycle()
    val pictureCount = attached.count { it.shown is ShownAttachment.Picture }

    // The permission result resumes whatever the mic press was waiting on. Denied
    // means nothing starts, and the user is told why.
    var whenMicGranted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val resume = whenMicGranted
        whenMicGranted = null
        if (granted) resume?.invoke() else viewModel.showNotice(context.getString(R.string.microphone_access_needed))
    }
    // The system photo picker needs no permission, and returns only the file the user chose.
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.attachPicture(uri.toString())
    }
    // Attach asks what to attach. A photo goes to the vision path as before. An audio file is
    // offered for transcription, and a model that cannot hear says so in one line at once.
    var attachChoice by remember { mutableStateOf(false) }
    var pendingAudio by remember { mutableStateOf<String?>(null) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val blocked = viewModel.transcribeBlockedReason()
        if (blocked != null) viewModel.showNotice(blocked) else pendingAudio = uri.toString()
    }
    val onAttach: () -> Unit = { attachChoice = true }
    if (attachChoice) {
        AlertDialog(
            onDismissRequest = { attachChoice = false },
            title = { Text(stringResource(R.string.attach)) },
            text = { Text(stringResource(if (technical) R.string.a_photo_for_the_model_to_see_or_an else R.string.plain_attach_text)) },
            confirmButton = {
                TextButton(onClick = {
                    attachChoice = false
                    pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(stringResource(R.string.photo)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    attachChoice = false
                    pickAudio.launch(arrayOf("audio/*"))
                }) { Text(stringResource(R.string.audio_file)) }
            },
        )
    }
    pendingAudio?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingAudio = null },
            title = { Text(stringResource(R.string.transcribe_this_recording)) },
            text = { Text(stringResource(if (technical) R.string.the_text_is_written_into_the_chat_as else R.string.plain_transcribe_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingAudio = null
                    viewModel.transcribe(uri)
                }) { Text(stringResource(R.string.transcribe)) }
            },
            dismissButton = { TextButton(onClick = { pendingAudio = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    // Hands-free mode needs the same microphone grant as the mic button. It is not
    // offered while the mic button is in use, and it stops when the app leaves the screen.
    val onHandsFree: () -> Unit = {
        when {
            handsFreeOn -> viewModel.stopHandsFree()
            dictating || voiceSeconds != null -> viewModel.showNotice(context.getString(R.string.finish_the_voice_input_first))
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> viewModel.startHandsFree()
            else -> {
                whenMicGranted = { viewModel.startHandsFree() }
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && viewModel.handsFree.value.phase != HandsFreePhase.Off) {
                viewModel.stopHandsFree(context.getString(R.string.hands_free_stopped_in_background))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val onMic: () -> Unit = {
        fun startCapture() {
            if (canReadAudio) {
                viewModel.startVoiceClip()
            } else if (!dictation.start(input) { viewModel.onInputChange(it) }) {
                viewModel.showNotice(context.getString(R.string.speech_recognition_unavailable))
            }
        }
        when {
            dictating -> dictation.stop()
            voiceSeconds != null -> viewModel.stopVoiceClip()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startCapture()
            else -> {
                whenMicGranted = { startCapture() }
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    val liveStatus = voiceSeconds?.let { context.getString(R.string.recording_with_length, clipLength(it)) }
        ?: if (dictating) stringResource(R.string.listening) else null
        ?: when (handsFree.phase) {
            HandsFreePhase.Off -> null
            HandsFreePhase.Listening -> stringResource(R.string.listening)
            HandsFreePhase.Thinking -> stringResource(R.string.thinking)
            HandsFreePhase.Speaking -> stringResource(R.string.speaking)
        }
    // The progress line participates in the count because it is a real row: the
    // auto-scroll has to reach it, or a run that is waiting on a model load
    // scrolls the user's own question off the top of the screen.
    // While the runtime waits on an approval, the call's own row (with its
    // Allow and Deny) says so, so the progress line stays silent to avoid saying it twice.
    val awaiting = runState as? RunState.AwaitingApproval
    val progress = if (awaiting != null) null else progressLine(runState, stringResource(R.string.working_on_it), LocalContext.current)
    val entries = remember(messages) { groupToolCalls(messages) }
    val animate = rememberMotionEnabled()
    val rowCount = messages.size +
        (if (streaming.isNotEmpty()) 1 else 0) +
        (if (running != null) 1 else 0) +
        (if (progress != null) 1 else 0) +
        (if (awaiting != null) 1 else 0)

    // Whether the transcript follows the newest row.
    //
    // It changes only when a scroll comes to rest: at the bottom it follows,
    // anywhere above it it does not. A row or a streamed token arriving below the
    // fold makes the list scrollable without anyone scrolling, so it cannot turn
    // following off; only the reader moving away can, and moving back turns it
    // on. The first value is dropped so the list's initial position at the top
    // of a long history does not count as the reader leaving.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .filter { !it }
            .collect { follow = !listState.canScrollForward }
    }
    val viewportHeight by remember { derivedStateOf { listState.layoutInfo.viewportSize.height } }
    val sentByUser = messages.lastOrNull() is ChatMessage.User

    // Follow new rows, every streamed token (a long answer grows inside one row,
    // so the row count alone never moves) and the keyboard opening (the viewport
    // shrinks). The user's own message always comes into view, even when they
    // had scrolled up to re-read something before sending. Nothing moves while
    // the reader's own drag or fling is still running; where it comes to rest
    // decides whether following continues.
    LaunchedEffect(rowCount, streaming.length, viewportHeight, sentByUser) {
        if (sentByUser) follow = true
        if (follow && !listState.isScrollInProgress) listState.scrollToEnd()
    }

    // Whether the window is too short for a stacked composer.
    //
    // WHY A HEIGHT TEST RATHER THAN A FOLD DETECTOR: the failure being prevented
    // is arithmetic, not posture. A landscape phone with the keyboard up leaves
    // on the order of 200dp of window, and a two-row composer is most of that, so the transcript is reduced to a sliver. That happens on
    // an unfolded foldable in landscape for the same reason and with the same
    // number, so one height test covers both without a hinge API this screen
    // would otherwise have to learn about.
    //
    // The threshold is a heuristic on `screenHeightDp`, and it is honest about
    // being one: it cannot tell a book-posture fold from a normal landscape
    // window, and it does not need to, because the layout that answers both is
    // the same one.
    val configuration = LocalConfiguration.current
    val compactComposer = configuration.screenHeightDp < COMPACT_HEIGHT_DP

    Scaffold(
        // NO imePadding HERE, and this is the load-bearing part of the fix.
        //
        // The composer is this Scaffold's bottomBar, and Material3's Scaffold
        // documents that it expects the bottom bar to handle its own insets: the
        // padding it hands the content is the bottom bar's measured height, not
        // that height plus an inset. So the inset belongs on the composer's own
        // content — see `ChatComposer` — where it grows the bar by exactly the
        // keyboard's height, the Scaffold measures the grown bar, and the
        // transcript above shrinks by the same amount. One inset, one place, and
        // no interval in which the window and the composer can disagree.
        //
        // The version this replaced put `imePadding()` on the Scaffold, which
        // shrinks the whole scaffold including the composer's own background. The
        // composer did come above the keyboard — that part worked — but the strip
        // between the composer and the keyboard was then painted with the window
        // background instead of the composer's surface, and on a build where the
        // window is already resized for the keyboard the same inset gets counted
        // twice.
        modifier = modifier,
        topBar = {
            // Simple's one way to Settings, and New chat once there is a chat. Technical keeps the gear in its composer row.
            if (!technical) {
                SimpleChatTopRow(
                    onOpenSettings = onOpenSettings,
                    // The top bar draws under the status bar on an edge-to-edge window (targetSdk 36), so it takes the inset itself.
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    actions = {
                        if (onNewChat != null && messages.isNotEmpty()) NewChatAction(busy = busy, onNewChat = onNewChat)
                    },
                )
            }
        },
        bottomBar = {
          Column {
            if (interrupted) {
                InterruptedRunOffer(
                    onContinue = viewModel::continueInterrupted,
                    onDismiss = viewModel::dismissInterrupted,
                )
            }
            ChatComposer(
                input = input,
                busy = busy,
                // A disabled send button with no reason is the definition of a
                // dead end, so the reason travels with the state.
                blockedReason = blocked,
                onInputChange = viewModel::onInputChange,
                // Simple's first run: a message typed while the assistant is not ready is kept and sent when it is.
                onSend = {
                    if (canQueue && blocked != null) viewModel.queueSend() else viewModel.send()
                },
                canQueue = canQueue,
                onStop = viewModel::stop,
                compact = compactComposer,
                attachments = attached,
                canAttach = !busy && pictureCount < MAX_PICTURES_PER_MESSAGE,
                canUseMic = !busy && !handsFreeOn,
                micActive = dictating || voiceSeconds != null,
                liveStatus = liveStatus,
                notice = notice ?: handsFree.notice,
                handsFreeOn = handsFreeOn,
                canHandsFree = handsFreeOn || (!busy && !dictating && voiceSeconds == null),
                onAttach = onAttach,
                onMic = onMic,
                onHandsFree = onHandsFree,
                onRemoveAttachment = viewModel::removeAttachment,
                onOpenSettings = onOpenSettings,
                showSettingsEntry = technical,
                showBlockedReason = messages.isNotEmpty(),
                blockedAction = when {
                    // Simple has no Models screen: its one action starts the one-tap setup, or tries it again.
                    !technical && modelState is ModelAvailability.None ->
                        stringResource(R.string.get_started_plain) to onStartStarter
                    !technical && modelState is ModelAvailability.Failed ->
                        stringResource(R.string.try_again) to onStartStarter
                    modelState is ModelAvailability.None -> stringResource(R.string.download_a_model) to onOpenModels
                    modelState is ModelAvailability.Failed -> stringResource(R.string.choose_another_model) to onOpenModels
                    else -> null
                },
            )
          }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),  // bottom bar height, which carries the IME inset
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && streaming.isEmpty() && running == null && progress == null) {
                item("empty") {
                    EmptyState(
                        // A bounded height, so the content can scroll inside it at 200% text. A minimum height
                        // would leave the list's max unbounded, and a scrolling column cannot be measured that way.
                        modifier = Modifier.fillParentMaxHeight(0.9f),
                        blockedReason = blocked,
                        // Structural, not string-matched, and both cases get a
                        // route: "nothing imported" is fixed by going to the
                        // model screen, and a *failed* load is fixed by choosing a
                        // different file on it. The previous version only offered
                        // the button for `None`, so a model the loader had just
                        // rejected left the user on a dead end with no way to
                        // reach the one screen that lists the alternatives.
                        isMissingModel = modelState is ModelAvailability.None,
                        isLoading = modelState is ModelAvailability.Loading,
                        onOpenModels = onOpenModels,
                        assistantName = assistantName,
                        suggestion = suggestion,
                        onGetSuggested = onGetSuggested,
                        starter = starter,
                        onStartStarter = onStartStarter,
                        onCancelStarter = onCancelStarter,
                        onExample = { text -> viewModel.send(text) },
                        personas = personas,
                        onChoosePersona = onChoosePersona,
                    )
                }
            }
            items(entries, key = { entry -> entry.key }) { entry ->
                when (entry) {
                    is TranscriptEntry.Single -> when (val message = entry.message) {
                        is ChatMessage.User -> UserBubble(
                            text = message.text,
                            attached = message.attached,
                            onEdit = if (canEdit && message === messages.getOrNull(lastUserIndex)) {
                                { viewModel.editLast() }
                            } else {
                                null
                            },
                            // Simple keeps the pencil's row in place while a run is going, so it
                            // does not appear under the bubble when the run ends. Disabled until then.
                            reserveEdit = !technical && message === messages.getOrNull(lastUserIndex),
                        )
                        is ChatMessage.Assistant -> Column {
                            AssistantBubble(
                                text = message.text,
                                // Simple: the reasoning is not shown. Technical keeps its folded line.
                                thought = if (technical) message.thought else null,
                                speed = if (technical) message.speed else null,
                                photos = message.photos,
                                phoneLinks = message.phoneLinks,
                                speaking = speakingText == message.text,
                                onSpeak = { viewModel.toggleSpeak(message.text) },
                                onReport = { reportingReply = message.text },
                                onRetry = if (canRetry && message === messages.getOrNull(lastAnswerIndex)) {
                                    { viewModel.retryLast() }
                                } else {
                                    null
                                },
                                onSaveAsSkill = if (skills != null && skillDraft != null && message === messages.last()) {
                                    { editingSkill = skillDraft }
                                } else {
                                    null
                                },
                                simple = !technical,
                            )
                            val offer = visibleOffer
                            if (offer != null && message === messages.last()) {
                                RepeatOfferRow(
                                    onSave = { acceptRepeat(offer) },
                                    onNotNow = {
                                        repeatAnswered = offer.candidate.key
                                        onDeclineRepeat(offer.candidate.key)
                                    },
                                )
                            }
                        }
                        is ChatMessage.Notice -> NoticeLine(
                            message.textRes?.let { stringResource(it) } ?: message.text,
                            onRetry = if (canRetryFailure && message === messages.last()) { { viewModel.retryLast() } } else null,
                        )
                        is ChatMessage.Transcript -> TranscriptCard(message) { viewModel.saveTranscript(it) }
                        is ChatMessage.Approval -> ApprovalRow(message)
                        is ChatMessage.ToolStep -> ToolStepRow(
                            message, animate, memory = memoryRowFor(message, memoryBlock, memoryRevision),
                        )
                    }
                    is TranscriptEntry.Group -> if (entry.steps.size == 1) {
                        ToolStepRow(
                            entry.steps.first(), animate,
                            memory = memoryRowFor(entry.steps.first(), memoryBlock, memoryRevision),
                        )
                    } else {
                        // Live only while it is the last thing in the transcript
                        // and the run is still going; an approval pending row is
                        // also "after" it, so the group is not live then.
                        ToolCallGroup(
                            steps = entry.steps,
                            live = busy && awaiting == null && entry.lastIndex == messages.lastIndex,
                            autoCollapse = toolCallsAutoCollapse,
                            animate = animate,
                        )
                    }
                }
            }
            running?.let { tool ->
                item("running") { RunningToolRow(tool, animate) }
            }
            if (awaiting != null) {
                item("pending") {
                    PendingToolRow(
                        pending = awaiting,
                        animate = animate,
                        onApprove = { viewModel.resolveConfirmation(approved = true) },
                        onDecline = { viewModel.resolveConfirmation(approved = false) },
                    )
                }
            }
            if (progress != null) {
                item("progress") { ProgressLine(progress) }
            }
            if (streaming.isNotEmpty()) {
                item("streaming") { StreamingBubble(streaming) }
            }
        }
    }
}

/**
 * Below this the composer stacks and the transcript is a sliver.
 *
 * 560dp rather than something round: a Pixel-class phone is ~915dp tall in
 * portrait and ~411dp in landscape, a small phone in portrait is ~640dp, and an
 * unfolded foldable in landscape is ~450dp. One number that separates "a phone
 * held upright" from "a window too short to be read in" has to sit between the
 * largest of the second group and the smallest of the first, and that gap is
 * wide.
 */
private const val COMPACT_HEIGHT_DP = 560

/**
 * Brings the bottom of the last row into view. Not animated: it runs on every
 * streamed token, and a fresh animation per token would never settle. A scroll
 * the user is already making wins, because a programmatic scroll has the lower
 * priority and is the one cancelled.
 */
private suspend fun LazyListState.scrollToEnd() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == last }) scrollToItem(last)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == last } ?: return
    val overhang = item.offset + item.size + layoutInfo.afterContentPadding - layoutInfo.viewportEndOffset
    if (overhang > 0) scrollBy(overhang.toFloat())
}

/** The user's message: a compact bubble on the right that fits its text, up to 80% of the width. */
@Composable
private fun UserBubble(
    text: String,
    attached: List<ShownAttachment>,
    onEdit: (() -> Unit)? = null,
    reserveEdit: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.fillMaxWidth(0.8f), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (attached.isNotEmpty()) SentAttachments(attached)
                    if (text.isNotEmpty()) {
                        SelectionContainer {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
    if (onEdit != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_message), modifier = Modifier.size(18.dp))
            }
        }
    } else if (reserveEdit) {
        // Same height as the pencil's row, drawn as nothing and skipped by TalkBack, so the
        // transcript does not move when the pencil appears, and no dead control is shown.
        Box(Modifier.fillMaxWidth().height(48.dp).semantics { invisibleToUser() })
    }
}

/**
 * The answer: text on the page, not in a box, with copy, read aloud and a menu
 * under it. Selectable, so part of an answer can be copied too.
 */
@Composable
private fun AssistantBubble(
    text: String,
    thought: String?,
    speed: ReplySpeed? = null,
    photos: List<String> = emptyList(),
    phoneLinks: List<PhoneSearchLink> = emptyList(),
    speaking: Boolean,
    onSpeak: () -> Unit,
    onReport: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onSaveAsSkill: (() -> Unit)? = null,
    /** Simple mode: the four reply actions sit in one overflow menu, and the skill and speed lines are not shown. */
    simple: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (thought != null) ThoughtRow(thought)
        SelectionContainer { MarkdownText(text) }
        if (photos.isNotEmpty()) PhotoHitsRow(photos)
        if (phoneLinks.isNotEmpty()) PhoneHitsRow(phoneLinks)
        if (speed != null && !simple) {
            Text(
                text = replySpeedLine(speed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        if (simple) {
            // Simple mode: one small menu holds the reply actions. Report stays in it, because Play requires it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.more), modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy)) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(text))
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (speaking) stringResource(R.string.stop_reading) else stringResource(R.string.read_aloud)) },
                            leadingIcon = { Icon(if (speaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onSpeak()
                            },
                        )
                        if (onRetry != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.retry)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onRetry()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.report_reply)) },
                            leadingIcon = { Icon(Icons.Filled.Flag, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onReport()
                            },
                        )
                    }
                }
            }
            return@Column
        }
        // One traversal group: TalkBack moves through the reply's actions together, in order.
        Row(
            modifier = Modifier.semantics { isTraversalGroup = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.copy), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSpeak) {
                Icon(
                    imageVector = if (speaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (speaking) stringResource(R.string.stop_reading) else stringResource(R.string.read_aloud),
                    modifier = Modifier.size(20.dp),
                )
            }
            // In-app reporting, required by Play's AI content policy. Only on finished replies:
            // this bubble is never drawn for the streaming text.
            IconButton(onClick = onReport) {
                Icon(Icons.Filled.Flag, contentDescription = stringResource(R.string.report_reply), modifier = Modifier.size(20.dp))
            }
            if (onRetry != null) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.retry), modifier = Modifier.size(20.dp))
                }
            }
            if (onSaveAsSkill != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.more), modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.save_as_skill)) },
                            onClick = {
                                menuOpen = false
                                onSaveAsSkill()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The model's reasoning for an answer, folded: a single line that opens to the text. Folded
 * by default so the answer is what the eye lands on; the thought is there for anyone who asks.
 */
/**
 * The Technical speed line under a reply: prefill rate, first-token time, decode rate. A rate
 * whose timer or count is zero, and a first-token time the backend did not report, read as "n/a",
 * never as a figure made up from nothing.
 */
@Composable
private fun replySpeedLine(speed: ReplySpeed): String {
    val na = stringResource(R.string.reply_speed_not_available)
    val prefill = speed.prefillTokensPerSecond?.let { "%.1f".format(it) } ?: na
    val firstToken = speed.firstTokenMs?.let { "$it ms" } ?: na
    val decode = speed.decodeTokensPerSecond?.let { "%.1f".format(it) } ?: na
    return stringResource(R.string.reply_speed_line, prefill, firstToken, decode)
}

@Composable
private fun ThoughtRow(thought: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                stringResource(R.string.thought_for_a_moment),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    thought,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}

private val TOOL_OPENER = Regex("""<tool(?=[\s>]|$)""", RegexOption.IGNORE_CASE)
private val RESPOND_OPENER = Regex("""<respond\s*>""", RegexOption.IGNORE_CASE)
private val RESPOND_CLOSER = Regex("""</respond\s*>""", RegexOption.IGNORE_CASE)
private const val TOOL_CLOSER = "</tool>"

/** Tags a stream can be cut off inside. A whole `<tool` is an opener, not a partial. */
private val PARTIAL_TAGS = listOf("<tool", "<respond>", "</tool>", "</respond>")

/**
 * The part of the live stream to show while the run is going.
 *
 * The stream is every token of every step, concatenated, because nothing clears
 * it between steps. So it holds the protocol as well as the answer: a
 * `<tool name="device.battery">{}</tool>` followed by the `<respond>` that uses
 * its result. Shown raw, the user watches the markup arrive. This picks the text
 * a person should read, and it is applied only to what is drawn.
 *
 * - The last `<tool` has no `</tool>` after it: the call is being made, so
 *   nothing. The transcript shows the call once the run has recorded it.
 * - Otherwise the text after the last `<respond>`, up to `</respond>`.
 * - A closed tool call, or no action tag at all (unconstrained prose): the text
 *   after the last `</tool>`, or the whole text.
 */
private fun liveBubbleText(stream: String): String {
    val text = stream.withoutPartialTagAtEnd()
    val tool = TOOL_OPENER.findAll(text).lastOrNull()?.range?.first ?: -1
    val respond = RESPOND_OPENER.findAll(text).lastOrNull()
    val toolClose = text.lastIndexOf(TOOL_CLOSER, ignoreCase = true)
    return when {
        respond != null && respond.range.first > tool -> {
            val body = text.substring(respond.range.last + 1)
            val close = RESPOND_CLOSER.find(body)
            (if (close != null) body.substring(0, close.range.first) else body).trim()
        }
        tool >= 0 && toolClose < tool -> ""
        toolClose >= 0 -> text.substring(toolClose + TOOL_CLOSER.length).trim()
        else -> text.trim()
    }
}

/** Drops a tag still arriving at the end of the stream, such as `<res`, so it never flashes. */
private fun String.withoutPartialTagAtEnd(): String {
    val start = lastIndexOf('<')
    if (start < 0) return this
    val fragment = substring(start)
    val partial = PARTIAL_TAGS.any { it.length > fragment.length && it.startsWith(fragment, ignoreCase = true) }
    return if (partial) substring(0, start) else this
}

/**
 * Streamed text, shown as it arrives.
 *
 * A spinner next to real tokens, not a "thinking…" label: the runtime produces no
 * reasoning narration, and inventing one would be a claim the system cannot back.
 *
 * ## WHY THIS AND THE COMMITTED BUBBLE SHARE ONE LAYOUT
 *
 * The committed text lands in exactly this slot — the transcript commits the
 * generation and the stream is suppressed on the terminal transition, so there
 * is one row, not two, and the answer never appears twice. That is true, but it
 * is only half of it: if the two renderings differ at all, the row still changes
 * size and the text under the user's eye shifts at the moment they are reading
 * it.
 *
 * So the column, the padding and the renderer here are the same as
 * [AssistantBubble], and the spinner sits in a 48dp row under the text where the
 * actions will be. Committing swaps that row and nothing else: the words do not
 * move, and the text does not re-wrap.
 */
@Composable
private fun StreamingBubble(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        MarkdownText(text)
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * A transcript of a picked audio file. It grows while the model writes; Copy and Save as
 * document appear once it is finished, so a half-written transcript cannot be saved.
 */
@Composable
private fun TranscriptCard(message: ChatMessage.Transcript, onSave: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = if (message.running) stringResource(R.string.transcribing) else stringResource(R.string.transcript),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = message.text.ifEmpty { "…" },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!message.running && message.text.isNotBlank()) {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = { onSave(message.text) }) { Text(stringResource(R.string.save_as_document)) }
            }
        }
    }
}

@Composable
private fun NoticeLine(text: String, onRetry: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

/**
 * The one line that says work is happening, or null when nothing is.
 *
 * ## Why `Running` gets a line at all
 *
 * `RunState.progressLabel` is null for `Running` on purpose: it is right not to
 * put "Working…" next to a finished answer, where it would describe work that
 * is not happening. But that left the *longest* phase of a run with no indicator
 * whatsoever. A decode on a phone takes seconds to minutes, the live bubble is
 * empty while the model is writing a tool call, and so the screen the user had
 * just typed into sat showing their own question and nothing else — the exact
 * "the app is not wired in" impression this state is supposed to avoid.
 *
 * So `Running` gets the same treatment `LoadingModel` already had. The wording
 * claims work and says where it happens; it does not narrate reasoning, because
 * `AgentAction` is `Respond | CallTool` and the model has no thought channel to
 * report. A "thinking…" label would be a narration the system cannot back.
 */
private fun progressLine(state: RunState, working: String, context: Context): String? = when (state) {
    RunState.Idle, is RunState.Finished -> null
    RunState.LoadingModel -> state.progressLabel(context)
    RunState.Running -> working
    is RunState.AwaitingApproval -> state.progressLabel(context)
}

/**
 * The first-run and no-model states, in one place.
 *
 * The reason this takes a [blockedReason] rather than reading the model state
 * itself is that "you cannot send anything" and "you have nothing to send yet"
 * are different sentences to a user, and only [ModelAvailability] knows which
 * one applies. The composable renders; it does not decide.
 *
 * A button appears whenever a route exists, and the two cases are worded
 * differently because the actions differ: there is nothing to choose between
 * when no model was ever imported, and everything to choose between when the one
 * you picked would not load.
 */
@Composable
private fun EmptyState(
    blockedReason: String?,
    isMissingModel: Boolean,
    isLoading: Boolean,
    onOpenModels: () -> Unit,
    assistantName: String,
    suggestion: SuggestedModel?,
    onGetSuggested: () -> Unit,
    starter: SetupState?,
    onStartStarter: () -> Unit,
    onCancelStarter: () -> Unit,
    onExample: (String) -> Unit,
    personas: List<Persona>,
    onChoosePersona: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Simple mode's first run replaces the Hub card while there is no model, and while a setup
    // that the user started is running. A ready model shows the normal line and the examples.
    val starterShown = starter != null && starter !is SetupState.Ready && (
        isMissingModel || starter is SetupState.Downloading || starter is SetupState.Preparing ||
            starter is SetupState.Failed
        )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        // The empty chat is drawn from the same mode as the rest of the chat, without a parameter for it.
        val technical = LocalTechnicalDetail.current
        if (starter != null && starterShown) {
            StarterPanel(
                starter = starter,
                assistantName = assistantName,
                onStart = onStartStarter,
                onCancel = onCancelStarter,
            )
            EmptyPersonaRow(personas, onChoosePersona)
            return@Column
        }
        Text(
            text = blockedReason
                ?: stringResource(
                    R.string.ask_assistant_to_do_something,
                    assistantName.trim().ifEmpty { stringResource(R.string.the_assistant) },
                ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (starter != null && blockedReason == null) {
            Text(
                text = stringResource(R.string.starter_capability),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            ExampleChips(onExample)
        }
        EmptyPersonaRow(personas, onChoosePersona)
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (isMissingModel && suggestion != null) {
            // First run: the model picked for this phone is one tap away, and
            // the download screen still shows its size and fit before anything
            // is fetched.
            Button(
                onClick = onGetSuggested,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.download_4, suggestion.name, formatBytes(suggestion.sizeBytes)))
            }
            Text(
                text = suggestion.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenModels, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (technical) R.string.other_models else R.string.simple_other_options))
            }
        } else if (blockedReason != null && starter != null) {
            // Simple mode: the Models screen is not offered there, so the one action is to start again.
            Button(
                onClick = onStartStarter,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.try_again))
            }
        } else if (blockedReason != null) {
            // 48dp: the minimum touch target. A plain TextButton would be 40dp
            // and sit below the platform accessibility floor.
            Button(
                onClick = onOpenModels,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    if (isMissingModel) {
                        stringResource(if (technical) R.string.download_a_model else R.string.simple_set_up_chat)
                    } else {
                        stringResource(if (technical) R.string.choose_another_model else R.string.simple_try_another_option)
                    },
                )
            }
        }
    }
}

/**
 * New chat, one tap from the chat itself. It asks first, in plain words, the same way Settings does, and it
 * is disabled while a run is in flight, because starting a new conversation is only safe between runs.
 */
@Composable
private fun NewChatAction(busy: Boolean, onNewChat: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    // Read at the moment of the tap, not when the dialog opened: a run can start while the dialog is up.
    val latestBusy by rememberUpdatedState(busy)
    TextButton(
        onClick = { asking = true },
        enabled = !busy,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.new_chat))
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(stringResource(R.string.start_a_new_chat)) },
            text = { Text(stringResource(R.string.plain_new_chat_text)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    if (!latestBusy) onNewChat()
                }) { Text(stringResource(R.string.new_chat)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The persona choice on the empty chat. The New chat row is hidden on an empty chat, so without this an
 * empty chat could never get a persona. A plain text button, shown only when personas exist.
 */
@Composable
private fun EmptyPersonaRow(personas: List<Persona>, onChoose: (Long?) -> Unit) {
    if (personas.isEmpty()) return
    var choosing by remember { mutableStateOf(false) }
    TextButton(onClick = { choosing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.choose_a_persona))
    }
    if (choosing) {
        var chosen by remember { mutableStateOf<Long?>(null) }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.choose_a_persona)) },
            text = {
                Column {
                    PersonaChoice(stringResource(R.string.none), selected = chosen == null) { chosen = null }
                    personas.forEach { persona ->
                        PersonaChoice(persona.name, selected = chosen == persona.id) { chosen = persona.id }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    choosing = false
                    onChoose(chosen)
                }) { Text(stringResource(R.string.start)) }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The first-run panel of Simple mode: one button for the download, then progress, then the wait for
 * the first answer. No model name, quant or reason is shown; the user only needs to know what happens.
 */
@Composable
private fun StarterPanel(
    starter: SetupState,
    assistantName: String,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    // It opens the sentence, so a capital: "the assistant runs on your phone" with no name set.
    val name = assistantName.trim().ifEmpty { stringResource(R.string.the_assistant) }.replaceFirstChar { it.uppercase() }
    when (starter) {
        is SetupState.Idle -> {
            Text(
                text = stringResource(R.string.runs_on_your_phone_it_needs_a_one_time, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            val context = LocalContext.current
            val label = when {
                starter.onDisk -> stringResource(R.string.finish_setup)
                starter.keptBytes > 0 -> stringResource(R.string.continue_setup, percentOf(starter.keptBytes, starter.sizeBytes))
                else -> stringResource(R.string.get_started_plain)
            }
            Button(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(label)
            }
            if (!starter.onDisk && starter.keptBytes == 0L) {
                Text(
                    text = stringResource(R.string.download_is_about_stays_on_phone, formatBytes(starter.sizeBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (!starter.onDisk) {
                Text(
                    text = stringResource(R.string.starter_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            // Read when the panel appears, not on every recomposition: the network does not change under it.
            val metered = remember { isOnMeteredNetwork(context) }
            if (metered) {
                Text(
                    text = stringResource(R.string.you_re_on_mobile_data_wi_fi_is_better_3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        is SetupState.Downloading -> {
            val fraction = if (starter.totalBytes > 0) {
                (starter.bytes.toFloat() / starter.totalBytes).coerceIn(0f, 1f)
            } else {
                0f
            }
            val percent = (fraction * 100).toInt()
            // TalkBack hears the progress once per ten percent, not on every tick of the bar.
            val milestone = percent / 10
            val announced = stringResource(R.string.setting_up, milestone * 10)
            val secondsLeft = rememberSecondsLeft(starter.bytes, starter.totalBytes)
            Text(
                text = stringResource(R.string.setting_up, percent),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = announced
                },
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.of_2, formatBytes(starter.bytes), formatBytes(starter.totalBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // Blank until the pace is measured (the first few seconds); never a figure we did not measure.
            secondsLeft?.let { seconds ->
                val minutes = (seconds + 59) / 60
                Text(
                    text = if (seconds < 60) {
                        stringResource(R.string.starter_eta_under_minute)
                    } else {
                        LocalContext.current.resources.getQuantityString(R.plurals.starter_eta_minutes, minutes.toInt(), minutes.toInt())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = stringResource(R.string.you_can_leave_the_app_it_keeps_going),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }

        SetupState.Preparing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.getting_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                text = stringResource(R.string.almost_ready_this_only_happens_once),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        is SetupState.Failed -> {
            Text(
                text = SimpleWording.failureText(LocalContext.current, starter.kind),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Button(onClick = onStart, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.try_again))
            }
        }

        // The empty state does not show the panel for a ready model.
        SetupState.Ready -> Unit
    }
}

/** The example questions, each a 48dp button that sends its text at once. */
@Composable
private fun ExampleChips(onExample: (String) -> Unit) {
    val starterExamples = listOf(
        stringResource(R.string.starter_battery),
        stringResource(R.string.starter_timer),
        stringResource(R.string.starter_wake),
        stringResource(R.string.starter_weather),
        stringResource(R.string.starter_sum),
        stringResource(R.string.starter_web),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        starterExamples.forEach { text ->
            OutlinedButton(
                onClick = { onExample(text) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text)
            }
        }
    }
}

/** The pace of one setup download, measured from the bytes that arrived. Written only by [rememberSecondsLeft]. */
private class DownloadPace(startBytes: Long) {
    val startedAt = SystemClock.elapsedRealtime()
    var lastBytes = startBytes
    var lastAt = startedAt
    var bytesPerSecond = 0.0
}

/**
 * Seconds left in the setup download, or null while the pace is not measured yet. The pace is the
 * bytes that arrived over the time they took, smoothed, and the estimate waits for five seconds so
 * the first burst does not set it. A resumed download starts from the bytes already on the phone.
 */
@Composable
private fun rememberSecondsLeft(bytes: Long, totalBytes: Long): Long? {
    // Keyed on the total: the projector and the model are two downloads, and the second must not inherit the first's pace.
    val pace = remember(totalBytes) { DownloadPace(bytes) }
    var secondsLeft by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(bytes, totalBytes) {
        val now = SystemClock.elapsedRealtime()
        if (bytes > pace.lastBytes && now > pace.lastAt) {
            val instant = (bytes - pace.lastBytes) * 1000.0 / (now - pace.lastAt)
            pace.bytesPerSecond = if (pace.bytesPerSecond == 0.0) instant else 0.3 * instant + 0.7 * pace.bytesPerSecond
            pace.lastBytes = bytes
            pace.lastAt = now
        }
        secondsLeft = if (now - pace.startedAt >= PACE_WAIT_MS && pace.bytesPerSecond > 0.0 && totalBytes > bytes) {
            ((totalBytes - bytes) / pace.bytesPerSecond).toLong()
        } else {
            null
        }
    }
    return secondsLeft
}

private const val PACE_WAIT_MS = 5_000L

/** Whole percent of [part] in [whole], kept under 100 so a setup that is nearly done never reads "100%". */
private fun percentOf(part: Long, whole: Long): Int =
    if (whole > 0) ((part * 100) / whole).toInt().coerceIn(0, 99) else 0

/** True when the active connection is metered, which is mobile data for most phones. False when it cannot be read. */
private fun isOnMeteredNetwork(context: Context): Boolean = try {
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true
} catch (_: SecurityException) {
    false
}

/**
 * One honest line about work in flight.
 *
 * Labelled as a live region so TalkBack announces it when a run enters a state
 * that takes a long time. A screen-reader user otherwise gets silence for the
 * whole of a model load, which is the same problem this line exists to fix for
 * everyone else.
 *
 * ## Why it counts
 *
 * A spinner with no clock cannot answer the only question a slow run raises:
 * is this working, or is it stuck? There is no timeout in the agent loop —
 * `AgentController` awaits the backend's `generate` and a native decode that
 * has stopped making progress simply never returns — so a hung run is
 * indistinguishable from a slow one, and a user watching an unchanging dot has
 * no basis for deciding whether to press Stop. Elapsed seconds give them one.
 *
 * No estimate of the remaining time is shown. There is no measured decode
 * figure for this app on any phone, so any "about 2 minutes left" would be
 * invented.
 */
@Composable
private fun ProgressLine(label: String) {
    val elapsed = rememberElapsedSeconds()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // `weight` on the label and the clock last, so a long label wraps
        // instead of pushing the count off the edge where nobody can see it.
        // The clock appears only once a wait is long enough to wonder about.
        if (elapsed >= SHOW_CLOCK_AFTER_S) {
            Text(
                text = formatElapsed(elapsed),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // A reserved width. Without it the clock grows from "4s" to
                // "2m 05s" as it runs, and the row re-measures every second.
                modifier = Modifier.widthIn(min = 64.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

private const val SHOW_CLOCK_AFTER_S = 20

/** Seconds since this composable entered composition; 0 before the first tick. */
@Composable
private fun rememberElapsedSeconds(): Int {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            seconds += 1
        }
    }
    return seconds
}

/** "4s", "2m 05s", "1h 12m". Deliberately has no "remaining" form. */
private fun formatElapsed(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "%dm %02ds".format(seconds / 60, seconds % 60)
    else -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
}

/**
 * The memory line for a call that wrote the memory block, or null for every other call. The
 * revision is read by the caller so that a change or an Undo redraws the row.
 */
private fun memoryRowFor(
    step: ChatMessage.ToolStep,
    store: SettingsMemoryBlockStore?,
    @Suppress("UNUSED_PARAMETER") revision: Int,
): MemoryRow? {
    val id = step.changeId ?: return null
    if (store == null) return null
    val note = store.noteOf(id) ?: return null
    return MemoryRow(
        note = note,
        cleared = note.isEmpty(),
        undoable = store.canUndo(id),
        onUndo = { store.undo(id) },
    )
}

/** "Save this as a skill?" under the answer to a request the user has made three times. */
@Composable
private fun RepeatOfferRow(onSave: () -> Unit, onNotNow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.repeat_offer_question),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onNotNow) { Text(stringResource(R.string.repeat_offer_not_now)) }
        TextButton(onClick = onSave) { Text(stringResource(R.string.repeat_offer_save)) }
    }
}
