package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.RunState
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.isActive
import dev.localintelligence.app.ui.components.ChatComposer
import dev.localintelligence.app.ui.components.ChatModelIdentity
import dev.localintelligence.app.ui.components.ModelIdentityLine
import dev.localintelligence.app.ui.components.ApprovalRow
import dev.localintelligence.app.ui.components.ToolStepRow
import kotlinx.coroutines.delay

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
    onOpenTrace: () -> Unit,
    onOpenModels: () -> Unit,
    /**
     * Opens the "what is filtered" screen.
     *
     * Defaulted to a no-op rather than required, so adding a callback to this
     * screen cannot break a caller that has not been updated yet.
     */
    onOpenRedaction: () -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Which model is answering, when the host knows.
     *
     * Nullable and defaulted on purpose, but no longer because the state
     * lacks a name — [ModelAvailability.Ready] carries one now. The screen
     * treats this as a *claim that a model is resident*, not as a name to
     * print unconditionally, and drops it in any state where that is not
     * true.
     *
     * Pass the [ModelAvailability.Ready] the loader published, or null. Do
     * not pass `AppContainer.selectedModel`: the model manager sets that the
     * moment a row is tapped and *before* the load is attempted, so a load
     * that then fails leaves it naming a file the backend never opened.
     */
    activeModel: ChatModelIdentity? = null,
) {
    // `collectAsStateWithLifecycle`, not `collectAsState`: a backgrounded app
    // should not keep recomposing a chat transcript nobody is looking at.
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val runState by viewModel.runState.collectAsStateWithLifecycle()
    val streaming by viewModel.streamingText.collectAsStateWithLifecycle()
    val input by viewModel.input.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    // Derived from the collected `runState` rather than read from
    // `viewModel.isBusy`, which is a plain getter over `runState.value`. A
    // non-observable read happens to be correct here only because this
    // composable also collects `runState`; deriving it keeps that guarantee
    // local instead of resting on a coincidence in another file.
    val busy = runState.isActive
    val blocked = modelState.blockingReason()

    // The name is rendered as "this is the model answering you", so it is only
    // ever shown in the state where that is literally true.
    //
    // WHY IT IS FILTERED RATHER THAN PASSED STRAIGHT THROUGH: a caller that
    // hands this screen a name while the load has *failed* gets a name in the
    // app bar and a failure reason nowhere. `ModelIdentityLine` gives a
    // non-null identity precedence over every other state, so the two cannot
    // both be shown. Dropping the name is what keeps "the model I picked would
    // not load" and "a model is loaded and answering" from looking the same.
    //
    // The fallback to the state itself is what makes that guarantee local: if
    // a host forgets to pass the name at all, the screen still names the
    // resident model rather than falling back to the placeholder.
    val activeIdentity: ChatModelIdentity? = if (modelState.canRun) {
        activeModel ?: residentIdentity(modelState)
    } else {
        null
    }

    val listState = rememberLazyListState()
    // The progress line participates in the count because it is a real row: the
    // auto-scroll has to reach it, or a run that is waiting on a model load
    // scrolls the user's own question off the top of the screen.
    val progress = progressLine(runState)
    val rowCount = messages.size +
        (if (streaming.isNotEmpty()) 1 else 0) +
        (if (progress != null) 1 else 0)

    // Whether the transcript is parked at the newest row.
    //
    // WHY THIS IS GATED AT ALL: the previous version scrolled on every change
    // to the row count, unconditionally. A user who had scrolled up to re-read
    // an answer was yanked back to the bottom the moment the next tool row
    // arrived — which, in a run that adds a row every few seconds, means the
    // transcript could not actually be read. This is the transcript fighting
    // the reader, and it is worse than the layout problems it sits next to.
    //
    // It is read INSIDE the effect, deliberately: at the instant a row is
    // appended the layout is still the previous one, so this answers "was the
    // user at the bottom before this arrived?", which is the question worth
    // asking. A user who has not moved stays followed; a user who has scrolled
    // is left alone until they come back.
    val parkedAtNewest by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    // Follow the conversation as it grows. The `animate` matters: the phone
    // keyboard animating open is the difference between the composer staying put
    // and the whole list jumping under the user's thumb.
    LaunchedEffect(rowCount) {
        if (rowCount > 0 && parkedAtNewest) listState.animateScrollToItem(rowCount - 1)
    }

    // Whether the window is too short for a stacked composer.
    //
    // WHY A HEIGHT TEST RATHER THAN A FOLD DETECTOR: the failure being prevented
    // is arithmetic, not posture. A landscape phone with the keyboard up leaves
    // on the order of 200dp of window, and a two-row composer plus the app bar
    // is most of that, so the transcript is reduced to a sliver. That happens on
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

    val pending = runState as? RunState.AwaitingApproval
    if (pending != null) {
        ConfirmationDialog(
            pending = pending,
            onApprove = { viewModel.resolveConfirmation(approved = true) },
            onDecline = { viewModel.resolveConfirmation(approved = false) },
        )
    }

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
            TopAppBar(
                title = {
                    ModelIdentityLine(
                        availability = modelState,
                        identity = activeIdentity,
                        onOpenModels = onOpenModels,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Filled.Memory, contentDescription = "Models")
                    }
                    IconButton(onClick = onOpenTrace) {
                        Icon(Icons.Filled.Timeline, contentDescription = "Trace")
                    }
                    // The shield is the whole point of the icon: this is where
                    // a user goes to find out what the app is doing with their
                    // secrets, and it has to be one tap from the chat rather
                    // than two menus deep. A text label would not fit three
                    // actions into a phone's app bar, and an unlabelled glyph
                    // with no contentDescription is invisible to a screen
                    // reader — hence the description, which is what actually
                    // names it.
                    IconButton(onClick = onOpenRedaction) {
                        Icon(Icons.Filled.Shield, contentDescription = "What is filtered")
                    }
                },
            )
        },
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
            if (messages.isEmpty() && streaming.isEmpty() && progress == null) {
                item("empty") {
                    EmptyState(
                        blockedReason = blocked,
                        // Structural, not string-matched, and both cases get a
                        // route: "nothing imported" is fixed by going to the
                        // model screen, and a *failed* load is fixed by choosing a
                        // different file on it. The previous version only offered
                        // the button for `None`, so a model the loader had just
                        // rejected left the user on a dead end with no way to
                        // reach the one screen that lists the alternatives.
                        isMissingModel = modelState is ModelAvailability.None,
                        onOpenModels = onOpenModels,
                    )
                }
            }
            itemsIndexed(messages, key = { index, message -> "$index:${message::class.simpleName}" }) { _, message ->
                when (message) {
                    is ChatMessage.User -> UserBubble(message.text)
                    is ChatMessage.Assistant -> AssistantBubble(message.text)
                    is ChatMessage.Notice -> NoticeLine(message.text)
                    is ChatMessage.ToolStep -> ToolStepRow(message)
                    is ChatMessage.Approval -> ApprovalRow(message)
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
 * The identity [ModelAvailability.Ready] itself carries, or null when nothing
 * is loaded.
 *
 * This exists so the name shown on a working chat cannot depend on a caller
 * remembering to pass one. Before [ModelAvailability.Ready] carried a name
 * there was nothing here to read and the host was the only possible source;
 * now that the truth is in the state, the state is the default and a host
 * argument is the thing that has to justify itself.
 *
 * The mapping is a copy, not a conversion of the same object, because the two
 * types answer different questions: this one is what the app bar shows, and
 * the state carries the provenance in its KDoc. `quantType` is carried across
 * because a line that says "qwen 1.7b" without saying Q4_K_M is only half the
 * answer to "which model".
 */
private fun residentIdentity(availability: ModelAvailability): ChatModelIdentity? =
    (availability as? ModelAvailability.Ready)?.let { ready ->
        ChatModelIdentity(displayName = ready.displayName, quantType = ready.quantType)
    }

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(0.85f),
        ) {
            Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(0.95f),
    ) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
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
 * So the surface, the width and the padding here are literally the same values
 * as [AssistantBubble], and the live text is laid out by the same [Row] with the
 * spinner hanging off the end. Committing then removes the spinner and nothing
 * else: the words do not move, and the text does not re-wrap.
 */
@Composable
private fun StreamingBubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(0.95f),
    ) {
        // `Arrangement.spacedBy`, not padding on the spinner. A 14dp indicator
        // with 8dp of padding on it is a 6dp spinner, which is not a spinner.
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun NoticeLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
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
 * whatsoever. A decode on a phone takes seconds to minutes, `streamingText` is
 * never fed (`AgentController` calls `generate`, not `generateStreaming`), and
 * so the screen the user had just typed into sat showing their own question and
 * nothing else — the exact "the app is not wired in" impression this state is
 * supposed to avoid.
 *
 * So `Running` gets the same treatment `LoadingModel` already had. The wording
 * claims work and says where it happens; it does not narrate reasoning, because
 * `AgentAction` is `Respond | CallTool` and the model has no thought channel to
 * report. A "thinking…" label would be a narration the system cannot back.
 */
private fun progressLine(state: RunState): String? = when (state) {
    RunState.Idle, is RunState.Finished -> null
    RunState.LoadingModel -> state.progressLabel
    RunState.Running -> "Working. The model is running on this phone."
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
    onOpenModels: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = blockedReason ?: "Ask the agent to do something on this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (blockedReason != null) {
            // 48dp: the minimum touch target. A plain TextButton would be 40dp
            // and sit below the platform accessibility floor.
            Button(
                onClick = onOpenModels,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (isMissingModel) "Import a model" else "Choose a different model")
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
        if (elapsed > 0) {
            Text(
                text = formatElapsed(elapsed),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // A reserved width. Without it the clock grows from "4s" to
                // "2m 05s" as it runs, and the row re-measures every second.
                modifier = Modifier.widthIn(min = 64.dp),
                textAlign = TextAlign.End,
            )
        }
    }
}

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
