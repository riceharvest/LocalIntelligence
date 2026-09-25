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
    val busy = viewModel.isBusy
    val blocked = modelState.blockingReason()

    val listState = rememberLazyListState()
    // The progress line participates in the count because it is a real row: the
    // auto-scroll has to reach it, or a run that is waiting on a model load
    // scrolls the user's own question off the top of the screen.
    val progress = runState.progressLabel
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
        modifier = modifier,
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
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && streaming.isEmpty() && progress == null) {
                item("empty") {
                    EmptyState(
                        blockedReason = blocked,
                        // Structural, not string-matched: only "nothing imported"
                        // is fixable by going to the model screen. A load that
                        // already failed is not.
                        canFixByImporting = modelState is ModelAvailability.None,
                        onOpenModels = onOpenModels,
                    )
                }
            }
            itemsIndexed(messages, key = { index, message -> "$index:${message::class.simpleName}" }) { _, message ->
                when (message) {
                    is ChatMessage.User -> UserBubble(message.text)
                    is ChatMessage.Assistant -> AssistantBubble(message.text)
                    is ChatMessage.Notice -> NoticeLine(message.text)
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
 * The first-run and no-model states, in one place.
 *
 * The reason this takes a [blockedReason] rather than reading the model state
 * itself is that "you cannot send anything" and "you have nothing to send yet"
 * are different sentences to a user, and only [ModelAvailability] knows which
 * one applies. The composable renders; it does not decide.
 *
 * A button appears only when there is somewhere to go. Offering "Open models"
 * when the problem is a *failed* load would be sending the user somewhere that
 * cannot fix it.
 */
@Composable
private fun EmptyState(
    blockedReason: String?,
    canFixByImporting: Boolean,
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
            text = if (blockedReason == null) {
                "Ask the agent to do something on this phone."
            } else {
                blockedReason
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canFixByImporting) {
            // 48dp: the minimum touch target. A plain TextButton would be 40dp
            // and sit below the platform accessibility floor.
            Button(
                onClick = onOpenModels,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Import a model")
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
