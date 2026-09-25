package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.RunState
import dev.localintelligence.app.blockingReason
import dev.localintelligence.app.isActive

/**
 * The chat. A message list, a text field, a send button, and a STOP button while
 * something is running.
 *
 * Boring on purpose. This is a debugging tool that happens to be a chat app, and
 * every feature added to the transcript is one more thing between a developer and
 * the answer to "why did the agent do that". [TraceView] is where the depth lives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenTrace: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
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

    val listState = rememberLazyListState()
    // The progress line participates in the count because it is a real row: the
    // auto-scroll has to reach it, or a run that is waiting on a model load
    // scrolls the user's own question off the top of the screen.
    val progress = progressLine(runState)
    val rowCount = messages.size +
        (if (streaming.isNotEmpty()) 1 else 0) +
        (if (progress != null) 1 else 0)

    // Follow the conversation as it grows. The `animate` matters: the phone
    // keyboard animating open is the difference between the composer staying put
    // and the whole list jumping under the user's thumb.
    LaunchedEffect(rowCount) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    val pending = runState as? RunState.AwaitingApproval
    if (pending != null) {
        ConfirmationDialog(
            pending = pending,
            onApprove = { viewModel.resolveConfirmation(approved = true) },
            onDecline = { viewModel.resolveConfirmation(approved = false) },
        )
    }

    Scaffold(
        // WHY imePadding LIVES HERE, NOT ON THE LAZYCOLUMN: the composer is the
        // Scaffold's bottomBar. An inset on the LazyColumn pads the message list
        // and leaves the composer underneath the keyboard, which is why the text
        // box used to be unreachable. The window that has to shrink is the one
        // that contains the composer.
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("LocalIntelligence") },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Filled.Memory, contentDescription = "Models")
                    }
                    IconButton(onClick = onOpenTrace) {
                        Icon(Icons.Filled.Timeline, contentDescription = "Trace")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                input = input,
                busy = busy,
                // A disabled send button with no reason is the definition of a
                // dead end, so the reason travels with the state.
                blockedReason = blocked,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),  // IME inset is applied on the Scaffold.
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

@Composable
private fun Composer(
    input: String,
    busy: Boolean,
    blockedReason: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = input.isNotBlank() && blockedReason == null && !busy
    Surface(tonalElevation = 3.dp) {
        Column {
            if (blockedReason != null) {
                // Announced as it appears: without this, a TalkBack user
                // encounters a disabled button with no explanation of why.
                Text(
                    text = blockedReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    // A label, not just a placeholder: a placeholder disappears
                    // the moment the user types, which leaves a screen-reader
                    // user with an unlabelled edit field.
                    label = { Text("Message") },
                    placeholder = { Text("Ask for something…") },
                    maxLines = 4,
                    // Enter sends, Shift+Enter is a newline: a phone keyboard has one
                    // return key and the user expects it to do the obvious thing.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                )
                if (busy) {
                    IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop the current task",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
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
 */
@Composable
private fun StreamingBubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(0.95f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * One tool call and the phone's answer to it.
 *
 * WHY IT IS NOT A CHAT BUBBLE: a bubble is something the user said or the model
 * said. This is the app reporting on its own work, and dressing it as dialogue
 * would put a fabricated "assistant" turn in the transcript. It is deliberately
 * quiet: monospace, smaller, and it shows the raw observation because a
 * paraphrase here would let the app claim the phone said something it did not.
 *
 * ## The three shapes a row can take
 *
 *  - **A result.** The runtime dispatched the tool and the phone answered. The
 *    observation is rendered exactly as the runtime recorded it.
 *  - **A failure.** Same, with the runtime's success flag false.
 *  - **A refusal.** No observation at all, because the runtime never dispatched
 *    the tool: a policy `BLOCK`, a rejected argument, an approval that could not
 *    be claimed. These were previously rendered as "running…", which told the
 *    user a call was in flight at the exact moment the app had already decided
 *    not to make it — a refusal the user could watch as a spinner, forever, with
 *    no way to tell it from a tool that was genuinely still working. The
 *    runtime's own sentence is shown instead, and the row is styled as a
 *    failure because that is what it is.
 *
 * Note the consequence: with the current gateway there is no "in flight" shape
 * at all. `AgentViewModel` publishes the trace only when the run reports back —
 * at an approval or a terminal outcome — so a `TOOL_CALL` is never observed
 * before its `OBSERVATION`. If the trace is ever made live, the honest rendering
 * for the gap is the runtime's `detail` line, which is what this uses.
 */
@Composable
private fun ToolStepRow(step: ChatMessage.ToolStep) {
    val refused = step.observation == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // A refusal can arrive without a tool name — the two approval paths that
        // fail to claim a call do exactly that. Showing the runtime's sentence
        // in the name slot and then again in the body would read as a stutter,
        // so with no name there is only the one line.
        if (step.toolName.isNotBlank()) {
            Text(
                text = "▸ ${step.toolName}${if (step.args.isNotBlank()) " ${step.args}" else ""}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (refused) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        Text(
            text = when {
                refused -> step.detail
                step.success -> step.observation
                else -> "failed: ${step.observation}"
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (step.success && !refused) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(start = 14.dp),
        )
        if (!refused && step.durationMs > 0) {
            // The runtime measures every dispatch. It was captured in the row and
            // then never shown, so the one number that answers "was it slow?" was
            // thrown away on the floor.
            Text(
                text = "took ${step.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

/**
 * The user's answer to an approval prompt.
 *
 * Without this the decline vanished: the dialog closed, `ChatViewModel` recorded
 * it in a list no screen read, and the transcript resumed as if nothing had been
 * asked. For a destructive action that is the worst outcome available — the user
 * cannot afterwards tell whether the agent acted, and the app cannot show them.
 *
 * The wording states the consequence because it is the real one: on a decline
 * `AgentController.confirmAndResume` puts "The user declined <tool>. Do not call
 * it again." into the session, so the model is told and told not to retry. On an
 * approval the staged call runs exactly as it was shown.
 */
@Composable
private fun ApprovalRow(approval: ChatMessage.Approval) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (approval.approved) {
                "▸ you approved ${approval.toolName}"
            } else {
                "▸ you declined ${approval.toolName}"
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = if (approval.approved) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Text(
            text = if (approval.approved) {
                "The agent was told, and it may now run this call."
            } else {
                "The agent was told, and was told not to try it again."
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp),
        )
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
 */
@Composable
private fun ProgressLine(label: String) {
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
        )
    }
}
