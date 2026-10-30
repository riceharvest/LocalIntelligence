package dev.localintelligence.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
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
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.RunState
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.isActive
import dev.localintelligence.app.media.LiveDictation
import dev.localintelligence.app.media.ShownAttachment
import dev.localintelligence.app.media.encodePickedPicture
import dev.localintelligence.app.settings.SuggestedModel
import dev.localintelligence.app.ui.components.ApprovalRow
import dev.localintelligence.app.ui.components.ChatComposer
import dev.localintelligence.app.ui.components.MarkdownText
import dev.localintelligence.app.ui.components.PendingToolRow
import dev.localintelligence.app.ui.components.RunningToolRow
import dev.localintelligence.app.ui.components.SentAttachments
import dev.localintelligence.app.ui.components.ToolCallGroup
import dev.localintelligence.app.ui.components.ToolStepRow
import dev.localintelligence.app.ui.components.TranscriptEntry
import dev.localintelligence.app.ui.components.clipLength
import dev.localintelligence.app.ui.components.groupToolCalls
import dev.localintelligence.app.ui.components.rememberMotionEnabled
import dev.localintelligence.core.hub.formatBytes
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
    /** The skills store. Null hides "Save as skill", for hosts that have no store. */
    skills: SkillRepository? = null,
    /** The name from Settings, used in the empty chat's first line. Blank: no name. */
    assistantName: String = "",
    /** The model Simple mode recommends, offered on the empty chat when there is no model. */
    suggestion: SuggestedModel? = null,
    /** Opens the download screen for [suggestion]. */
    onGetSuggested: () -> Unit = {},
) {
    // `collectAsStateWithLifecycle`, not `collectAsState`: a backgrounded app
    // should not keep recomposing a chat transcript nobody is looking at.
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    // "Save as skill" draft for the answer on screen, if the run used 2+ tools.
    // Shown in SkillEditorDialog; nothing is written until the user taps Save.
    val skillDraft = remember(messages) { saveAsSkillDraft(messages) }
    var editingSkill by remember { mutableStateOf<String?>(null) }
    editingSkill?.let { draft ->
        val store = skills
        val dialogContext = LocalContext.current
        if (store != null) {
            SkillEditorDialog(
                title = "Save as skill",
                initialText = draft,
                onDismiss = { editingSkill = null },
                onConfirm = { text ->
                    when (val saved = store.save(text)) {
                        is SkillRepository.SaveResult.Saved -> {
                            editingSkill = null
                            Toast.makeText(dialogContext, "Skill saved", Toast.LENGTH_SHORT).show()
                            null
                        }
                        is SkillRepository.SaveResult.Refused -> saved.reason
                    }
                },
            )
        }
    }
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val speakingText by viewModel.speakingText.collectAsStateWithLifecycle()
    val rawStreaming by viewModel.streamingText.collectAsStateWithLifecycle()
    val running by viewModel.runningTool.collectAsStateWithLifecycle()
    // What the live bubble shows. Everything below reads this, so the bubble,
    // its row in the count and the empty state cannot disagree about it.
    val streaming = remember(rawStreaming) { liveBubbleText(rawStreaming) }
    val input by viewModel.input.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    // Derived from the collected `runState` rather than read from
    // `viewModel.isBusy`, which is a plain getter over `runState.value`. A
    // non-observable read happens to be correct here only because this
    // composable also collects `runState`; deriving it keeps that guarantee
    // local instead of resting on a coincidence in another file.
    val busy = runState.isActive
    val blocked = modelState.blockingReason()

    val listState = rememberLazyListState()

    val context = LocalContext.current

    // Media input. What the loaded model reads decides what the mic does: a model
    // that reads audio gets the clip itself; any other model gets the spoken words
    // typed into the field, because a WAV it cannot read would be sent and ignored.
    val attached by viewModel.attachments.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val voiceSeconds by viewModel.voiceSeconds.collectAsStateWithLifecycle()
    val handsFree by viewModel.handsFree.collectAsStateWithLifecycle()
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
        if (granted) resume?.invoke() else viewModel.showNotice("Microphone access is needed for voice input.")
    }
    // The system photo picker needs no permission, and returns only the file the user chose.
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.attachPicture { encodePickedPicture(context.contentResolver, uri) }
    }
    val onAttach: () -> Unit = {
        pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    // Hands-free mode needs the same microphone grant as the mic button. It is not
    // offered while the mic button is in use, and it stops when the app leaves the screen.
    val onHandsFree: () -> Unit = {
        when {
            handsFreeOn -> viewModel.stopHandsFree()
            dictating || voiceSeconds != null -> viewModel.showNotice("Finish the voice input first.")
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
                viewModel.stopHandsFree("Hands-free stopped because the app went to the background.")
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
                viewModel.showNotice("Speech recognition is not available on this phone.")
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
    val liveStatus = voiceSeconds?.let { "Recording ${clipLength(it)}" }
        ?: if (dictating) "Listening…" else null
        ?: when (handsFree.phase) {
            HandsFreePhase.Off -> null
            HandsFreePhase.Listening -> "Listening…"
            HandsFreePhase.Thinking -> "Thinking…"
            HandsFreePhase.Speaking -> "Speaking…"
        }
    // The progress line participates in the count because it is a real row: the
    // auto-scroll has to reach it, or a run that is waiting on a model load
    // scrolls the user's own question off the top of the screen.
    // While the runtime waits on an approval, the call's own row (with its
    // Allow and Deny) says so, so the progress line stays silent to avoid saying it twice.
    val awaiting = runState as? RunState.AwaitingApproval
    val progress = if (awaiting != null) null else progressLine(runState)
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
        bottomBar = {
            ChatComposer(
                input = input,
                busy = busy,
                // A disabled send button with no reason is the definition of a
                // dead end, so the reason travels with the state.
                blockedReason = blocked,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
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
                showBlockedReason = messages.isNotEmpty(),
                blockedAction = when (modelState) {
                    ModelAvailability.None -> "Download a model" to onOpenModels
                    is ModelAvailability.Failed -> "Choose another model" to onOpenModels
                    else -> null
                },
            )
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
                    )
                }
            }
            items(entries, key = { entry -> entry.key }) { entry ->
                when (entry) {
                    is TranscriptEntry.Single -> when (val message = entry.message) {
                        is ChatMessage.User -> UserBubble(message.text, message.attached)
                        is ChatMessage.Assistant -> AssistantBubble(
                            text = message.text,
                            speaking = speakingText == message.text,
                            onSpeak = { viewModel.toggleSpeak(message.text) },
                            onSaveAsSkill = if (skills != null && skillDraft != null && message === messages.last()) {
                                { editingSkill = skillDraft }
                            } else {
                                null
                            },
                        )
                        is ChatMessage.Notice -> NoticeLine(message.text)
                        is ChatMessage.Approval -> ApprovalRow(message)
                        is ChatMessage.ToolStep -> ToolStepRow(message, animate)
                    }
                    is TranscriptEntry.Group -> if (entry.steps.size == 1) {
                        ToolStepRow(entry.steps.first(), animate)
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
private fun UserBubble(text: String, attached: List<ShownAttachment>) {
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
}

/**
 * The answer: text on the page, not in a box, with copy, read aloud and a menu
 * under it. Selectable, so part of an answer can be copied too.
 */
@Composable
private fun AssistantBubble(
    text: String,
    speaking: Boolean,
    onSpeak: () -> Unit,
    onSaveAsSkill: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        SelectionContainer { MarkdownText(text) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSpeak) {
                Icon(
                    imageVector = if (speaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (speaking) "Stop reading" else "Read aloud",
                    modifier = Modifier.size(20.dp),
                )
            }
            if (onSaveAsSkill != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = "More", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Save as skill") },
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

@Composable
private fun NoticeLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
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
private fun progressLine(state: RunState): String? = when (state) {
    RunState.Idle, is RunState.Finished -> null
    RunState.LoadingModel -> state.progressLabel
    RunState.Running -> "Working on it…"
    is RunState.AwaitingApproval -> state.progressLabel
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = blockedReason
                ?: "Ask ${assistantName.trim().ifEmpty { "the assistant" }} to do something on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
                Text("Download ${suggestion.name} · ${formatBytes(suggestion.sizeBytes)}")
            }
            Text(
                text = suggestion.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onOpenModels, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Other models")
            }
        } else if (blockedReason != null) {
            // 48dp: the minimum touch target. A plain TextButton would be 40dp
            // and sit below the platform accessibility floor.
            Button(
                onClick = onOpenModels,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (isMissingModel) "Download a model" else "Choose another model")
            }
        }
    }
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
