package dev.localintelligence.app.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localintelligence.app.RunState
import dev.localintelligence.app.RunningTool
import dev.localintelligence.app.ui.ChatMessage
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.delay

/**
 * One tool call, drawn as a compact row in the transcript.
 *
 * ## WHY IT IS STILL NOT A CHAT BUBBLE
 *
 * A bubble is something the user said or the model said. This is the app
 * reporting on its own work, so it stays quiet: a small mark, a plain verb
 * phrase, and a meta line. The raw input and output are one tap away in the
 * panel, and the output is the observation exactly as the model saw it, because
 * a paraphrase here would let the app claim the phone said something it did not.
 *
 * ## WHAT THE ROW CAN AND CANNOT SAY
 *
 * A finished row is a `TOOL_CALL` the runtime recorded after the tool returned,
 * or a refusal. A row with no observation is a call the runtime refused. The
 * call still in flight is [RunningToolRow], drawn from the runtime's start
 * signal and replaced by this row when the result lands.
 *
 * ## WHAT STAYS VISIBLE WHEN COLLAPSED
 *
 * The status shape and the meta line. The meta carries the short result and the
 * duration, so a collapsed row is still a complete statement about the call.
 */
@Composable
fun ToolStepRow(
    step: ChatMessage.ToolStep,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = stateOf(step)
    // A failure or a refusal is the row a user needs to read, so it opens. A
    // success starts folded and is one tap from its output.
    var expanded by rememberSaveable(step.toolName, step.args) {
        mutableStateOf(state == ToolRowState.Failed || state == ToolRowState.Refused)
    }
    ToolRow(
        toolName = step.toolName,
        state = state,
        summary = summaryOf(step, state),
        durationMs = step.durationMs,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        animate = animate,
        modifier = modifier,
    ) {
        ToolDetail(step, state, animate)
    }
}

/**
 * The call that has started and not yet returned.
 *
 * Drawn from [RunningTool], which the runtime sets the moment it dispatches a
 * call. It has no detail panel: the arguments are the call's own, and the
 * result row that replaces this one carries them once the call is done.
 */
@Composable
fun RunningToolRow(
    tool: RunningTool,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    ToolRow(
        toolName = tool.toolName,
        state = ToolRowState.Running,
        summary = "Running",
        durationMs = 0,
        expanded = null,
        onToggle = {},
        animate = animate,
        modifier = modifier,
        detail = null,
    )
}

/**
 * The approval for the call that is waiting on the user, answered inline.
 *
 * This replaces the modal dialog. Everything the dialog showed is still here: the
 * tool, why the policy stopped it, what the tool does, the risk wording, and the
 * arguments verbatim. The Input panel starts open, because the approval is the
 * one place a person must read the arguments, and the buttons sit outside the
 * panel so they stay visible even when the user folds it.
 *
 * A decline still goes through `confirmAndResume(false)` via [onDecline]: the run
 * continues and the model is told the call was refused.
 */
@Composable
fun PendingToolRow(
    pending: RunState.AwaitingApproval,
    animate: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(pending.toolName, pending.arguments) { mutableStateOf(true) }
    // The service flips the run state a moment after a tap, so a second tap in that
    // gap would send a second decision and add a second Approval row.
    var answered by remember(pending) { mutableStateOf(false) }
    val accent = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToolRow(
            toolName = pending.toolName,
            state = ToolRowState.Awaiting,
            summary = "Waiting for your approval",
            durationMs = 0,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            animate = animate,
            modifier = Modifier,
        ) {
            PendingDetail(pending)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    answered = true
                    onApprove()
                },
                enabled = !answered,
                border = accent,
            ) { Text("Allow") }
            OutlinedButton(
                onClick = {
                    answered = true
                    onDecline()
                },
                enabled = !answered,
                border = accent,
            ) { Text("Deny") }
        }
    }
}

/**
 * The approval's reasons and arguments, in the same panel shape as a finished
 * row's detail so the two read as one kind of thing.
 */
@Composable
private fun PendingDetail(pending: RunState.AwaitingApproval) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 6.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(
            Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pending.justification.isNotBlank()) {
                Text(
                    text = pending.justification,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = pending.description,
                style = MaterialTheme.typography.bodyMedium,
            )
            RiskBanner(pending.risk)
            ArgumentsBlock(pending.arguments)
        }
    }
}

/**
 * The derived risk, in words.
 *
 * `requiresConfirmation` is computed from the enum in `:core`, so a tool cannot
 * lie about needing permission and the UI cannot invent a different threshold.
 * Anything that is not one of the gated values is a bug upstream, and says so
 * rather than rendering a reassuring banner.
 *
 * Three distinct cases, and the wording keeps them apart on purpose: one cannot
 * be undone, one hands text to a third party, and one receives text FROM a third
 * party. Collapsing them into "are you sure?" would hide the difference that
 * decides the answer.
 */
@Composable
private fun RiskBanner(risk: ToolRisk) {
    val (label, explanation) = when (risk) {
        ToolRisk.DESTRUCTIVE ->
            "Destructive — this cannot be undone." to
                "Whatever this changes, it is gone afterwards. There is no undo."

        ToolRisk.EXTERNAL_COMMUNICATION ->
            "Sends to someone else — a person or a service will see this." to
                "This leaves your device. Check the recipient and the content."

        // For a fetch nothing is composed: the request reveals where the user is
        // asking about, and the reply is somebody else's text that the model will
        // read. A banner written for sending would make the user check the wrong thing.
        ToolRisk.NETWORK_EGRESS ->
            "Fetches a web page — this contacts a server and reads what it says." to
                "The server sees this request, and whatever it sends back is " +
                    "someone else's text, not instructions to you. Check the " +
                    "address below: only approve a site you recognise."

        else ->
            "Needs approval" to
                "This tool is not one of the gated risk classes, which should not happen."
    }
    val isExpected = risk.requiresConfirmation

    Surface(
        color = if (isExpected) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** The arguments, verbatim and horizontally scrollable, so nothing is truncated. */
@Composable
private fun ArgumentsBlock(arguments: String) {
    Column {
        Text(
            text = "Input",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = arguments,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

/**
 * Consecutive tool calls of one assistant turn, under one header.
 *
 * The header is the only thing shown when folded. It says "Using N tools" while
 * the turn is live and "Used N tools" once it is not, and carries the marks and
 * the total time, so the fold never hides how much happened.
 *
 * ## WHY THE FOLD IS AUTOMATIC, AND WHY A TAP WINS
 *
 * A turn that made five calls is five rows of plumbing above the answer the user
 * came for. When the turn finishes the group folds after [AUTO_FOLD_DELAY_MS],
 * so the answer is what remains on screen. The delay is there so a row that was
 * just appended is seen before it goes. A user who has tapped the header has
 * made their own choice, and the automatic fold leaves that choice alone.
 */
@Composable
fun ToolCallGroup(
    steps: List<ChatMessage.ToolStep>,
    live: Boolean,
    autoCollapse: Boolean,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var userChose by rememberSaveable { mutableStateOf(false) }

    val finished = !live
    LaunchedEffect(finished, autoCollapse) {
        if (finished && autoCollapse && !userChose) {
            delay(AUTO_FOLD_DELAY_MS)
            if (!userChose) collapsed = true
        }
    }

    val total = steps.sumOf { it.durationMs.coerceAtLeast(0L) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 40.dp)
                .clickable {
                    userChose = true
                    collapsed = !collapsed
                }
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(if (live) "Using " else "Used ")
                        append(steps.size).append(" tools")
                        formatDuration(total)?.let { append(", ").append(it) }
                        append(if (collapsed) ". Folded. Double tap to expand." else ". Double tap to fold.")
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MarkStack(steps.map { markFor(it.toolName) })
            Text(
                text = (if (live) "Using " else "Used ") + "${steps.size} tools",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            formatDuration(total)?.let { DurationText(it) }
            Icon(
                imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(
            visible = !collapsed,
            enter = enterFor(animate),
            exit = exitFor(animate),
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                steps.forEach { step -> ToolStepRow(step, animate) }
            }
        }
    }
}

/**
 * The one row shape every tool call uses: mark, title, meta, status, and an
 * optional panel behind a tap.
 */
@Composable
private fun ToolRow(
    toolName: String,
    state: ToolRowState,
    summary: String,
    durationMs: Long,
    expanded: Boolean?,
    onToggle: () -> Unit,
    animate: Boolean,
    modifier: Modifier,
    detail: (@Composable () -> Unit)? = null,
) {
    val verb = verbFor(toolName)
    val title = when {
        verb == null -> if (state == ToolRowState.Running || state == ToolRowState.Awaiting) {
            "Running $toolName…"
        } else {
            "Ran $toolName"
        }
        state == ToolRowState.Running -> verb.present + "…"
        state == ToolRowState.Awaiting -> verb.present
        else -> verb.past
    }
    val duration = formatDuration(durationMs)
    val tap = if (expanded != null) Modifier.clickable(onClick = onToggle) else Modifier

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 40.dp)
                .then(tap)
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(title).append(". ").append(state.word).append(". ")
                        append(summary)
                        duration?.let { append(". ").append(it) }
                        if (expanded != null) {
                            append(if (expanded) ". Showing details. Double tap to hide." else ". Double tap to show details.")
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = markFor(toolName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state == ToolRowState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    duration?.let {
                        Spacer(Modifier.width(6.dp))
                        DurationText(it)
                    }
                }
            }
            StatusShape(state)
            if (expanded != null) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (detail != null) {
            AnimatedVisibility(
                visible = expanded == true,
                enter = enterFor(animate),
                exit = exitFor(animate),
            ) {
                detail()
            }
        }
    }
}

/** Input and output, both monospace, both copyable where it matters. */
@Composable
private fun ToolDetail(step: ChatMessage.ToolStep, state: ToolRowState, animate: Boolean) {
    val input = inputLines(step.args)
    val output = if (state == ToolRowState.Refused) step.detail else step.observation.orEmpty()
    val outputIsProblem = state == ToolRowState.Failed || state == ToolRowState.Refused
    var showAll by rememberSaveable(step.toolName, step.args) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, bottom = 6.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = step.toolName.ifBlank { "tool call" },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Input",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(input)) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy input",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = input,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            Text(
                text = "Output",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            val lines = output.ifBlank { "(no output)" }.lines()
            val shown = if (showAll || lines.size <= OUTPUT_FOLD_LINES) {
                lines.joinToString("\n")
            } else {
                lines.take(OUTPUT_FOLD_LINES).joinToString("\n")
            }
            Text(
                text = shown,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (outputIsProblem) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!showAll && lines.size > OUTPUT_FOLD_LINES) {
                TextButton(onClick = { showAll = true }) {
                    Text("Show ${lines.size - OUTPUT_FOLD_LINES} more lines")
                }
            }
        }
    }
}

/** The status shape. Each state has its own silhouette, not just its own colour. */
@Composable
private fun StatusShape(state: ToolRowState) {
    val tint = when (state) {
        ToolRowState.Running -> MaterialTheme.colorScheme.primary
        ToolRowState.Done -> MaterialTheme.colorScheme.primary
        ToolRowState.Failed -> MaterialTheme.colorScheme.error
        ToolRowState.Awaiting -> MaterialTheme.colorScheme.tertiary
        ToolRowState.Refused -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    when (state) {
        ToolRowState.Running -> CircularProgressIndicator(
            modifier = Modifier.size(16.dp).semantics { contentDescription = state.word },
            strokeWidth = 2.dp,
            color = tint,
        )
        else -> Icon(
            imageVector = when (state) {
                ToolRowState.Done -> Icons.Filled.Check
                ToolRowState.Failed -> Icons.Filled.ErrorOutline
                ToolRowState.Awaiting -> Icons.Filled.Shield
                else -> Icons.Filled.Block
            },
            contentDescription = state.word,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Up to four family marks, overlapping, so a group reads as one stack of what
 * kinds of thing it touched.
 */
@Composable
private fun MarkStack(marks: List<ImageVector>) {
    val shown = marks.take(MAX_STACKED_MARKS)
    if (shown.isEmpty()) return
    val step = 12.dp
    Box(modifier = Modifier.size(width = 24.dp + step * (shown.size - 1), height = 24.dp)) {
        shown.forEachIndexed { index, mark ->
            Surface(
                modifier = Modifier.size(24.dp).offset(x = step * index),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = mark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DurationText(text: String) {
    Text(
        text = text,
        // Tabular figures so a column of durations lines up and "1.2s" does not
        // jitter the row when it changes width.
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Whether the row should animate, read from the system animator scale.
 *
 * A user who has turned animations off, or a test harness that sets the scale
 * to 0, gets the layout changing in one step. Read once per composition root so
 * it costs nothing per row.
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

private fun enterFor(animate: Boolean): EnterTransition =
    if (animate) {
        expandVertically(spring<IntSize>(stiffness = Spring.StiffnessMediumLow)) +
            fadeIn(spring())
    } else {
        EnterTransition.None
    }

private fun exitFor(animate: Boolean): ExitTransition =
    if (animate) {
        shrinkVertically(spring<IntSize>(stiffness = Spring.StiffnessMediumLow)) +
            fadeOut(spring())
    } else {
        ExitTransition.None
    }

/**
 * One entry of the transcript after tool calls are grouped. A run of adjacent
 * [ChatMessage.ToolStep] rows is one [Group]; everything else stays a [Single].
 */
internal sealed interface TranscriptEntry {
    val key: String

    data class Single(val index: Int, val message: ChatMessage) : TranscriptEntry {
        override val key: String get() = "$index:${message::class.simpleName}"
    }

    data class Group(
        val firstIndex: Int,
        val lastIndex: Int,
        val steps: List<ChatMessage.ToolStep>,
    ) : TranscriptEntry {
        override val key: String get() = "group:$firstIndex"
    }
}

/** Groups adjacent tool rows. An approval, a message or a notice ends a group. */
internal fun groupToolCalls(messages: List<ChatMessage>): List<TranscriptEntry> {
    val out = ArrayList<TranscriptEntry>()
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        if (message is ChatMessage.ToolStep) {
            val start = i
            val steps = ArrayList<ChatMessage.ToolStep>()
            while (i < messages.size) {
                val next = messages[i] as? ChatMessage.ToolStep ?: break
                steps += next
                i++
            }
            out += TranscriptEntry.Group(start, i - 1, steps)
        } else {
            out += TranscriptEntry.Single(i, message)
            i++
        }
    }
    return out
}

/** Where a row sits in its lifecycle, derived from what the runtime reports. */
internal enum class ToolRowState(val word: String) {
    Running("running"),
    Done("done"),
    Failed("failed"),
    Awaiting("waiting for approval"),
    Refused("refused"),
}

internal fun stateOf(step: ChatMessage.ToolStep): ToolRowState = when {
    step.observation == null -> ToolRowState.Refused
    step.success -> ToolRowState.Done
    else -> ToolRowState.Failed
}

private fun summaryOf(step: ChatMessage.ToolStep, state: ToolRowState): String = when (state) {
    ToolRowState.Refused -> firstLine(step.detail).ifBlank { "Not run" }
    ToolRowState.Failed -> firstLine(step.observation.orEmpty()).ifBlank { "Failed" }
    ToolRowState.Done -> firstLine(step.observation.orEmpty()).ifBlank { "Done" }
    ToolRowState.Awaiting -> "Waiting for your approval"
    ToolRowState.Running -> "Running"
}

private fun firstLine(text: String): String =
    text.lineSequence().firstOrNull().orEmpty().trim().take(120)

private class ToolVerb(val present: String, val past: String)

/**
 * Tool name to a verb phrase. A tool missing from this map still gets a row,
 * with the raw name in the title, so a new tool is never invisible.
 */
private val toolVerbs: Map<String, ToolVerb> = mapOf(
    "device.battery" to ToolVerb("Checking battery", "Checked battery"),
    "device.info" to ToolVerb("Reading device info", "Read device info"),
    "device.open_settings" to ToolVerb("Opening settings", "Opened settings"),
    "device.vibrate" to ToolVerb("Vibrating phone", "Vibrated phone"),
    "files.delete" to ToolVerb("Deleting file", "Deleted file"),
    "files.list" to ToolVerb("Listing files", "Listed files"),
    "files.read_text" to ToolVerb("Reading file", "Read file"),
    "files.search" to ToolVerb("Searching files", "Searched files"),
    "files.write_text" to ToolVerb("Writing file", "Wrote file"),
    "apps.list" to ToolVerb("Listing apps", "Listed apps"),
    "apps.open" to ToolVerb("Opening app", "Opened app"),
    "apps.share" to ToolVerb("Sharing content", "Shared content"),
    "clipboard.read" to ToolVerb("Reading clipboard", "Read clipboard"),
    "clipboard.write" to ToolVerb("Copying to clipboard", "Copied to clipboard"),
    "alarm.create" to ToolVerb("Setting alarm", "Set alarm"),
    "alarm.list" to ToolVerb("Listing alarms", "Listed alarms"),
    "alarm.cancel" to ToolVerb("Cancelling alarm", "Cancelled alarm"),
    "calendar.create" to ToolVerb("Adding calendar event", "Added calendar event"),
    "calendar.search" to ToolVerb("Searching calendar", "Searched calendar"),
    "contacts.get" to ToolVerb("Reading contact", "Read contact"),
    "contacts.search" to ToolVerb("Searching contacts", "Searched contacts"),
    "notifications.list" to ToolVerb("Listing notifications", "Listed notifications"),
    "notifications.dismiss" to ToolVerb("Dismissing notification", "Dismissed notification"),
    "notifications.reply" to ToolVerb("Replying to notification", "Replied to notification"),
    "web.fetch" to ToolVerb("Fetching page", "Fetched page"),
)

private fun verbFor(toolName: String): ToolVerb? = toolVerbs[toolName]

/** The mark by tool family: the part of the name before the first dot. */
private fun markFor(toolName: String): ImageVector =
    when (toolName.substringBefore('.', "")) {
        "device" -> Icons.Filled.PhoneAndroid
        "files" -> Icons.Filled.Folder
        "apps" -> Icons.Filled.Apps
        "clipboard" -> Icons.Filled.ContentPaste
        "alarm" -> Icons.Filled.Alarm
        "calendar" -> Icons.Filled.Event
        "contacts" -> Icons.Filled.Person
        "notifications" -> Icons.Filled.Notifications
        "web" -> Icons.Filled.Public
        else -> Icons.Filled.Build
    }

/**
 * The arguments as "key: value" lines for the Input panel.
 *
 * The runtime stores arguments compacted as `{key=value, key=value}`, with each
 * value cut to a short prefix. That is what the model and the transcript both
 * have, so the panel shows exactly that, reformatted for reading. Anything that
 * does not parse is shown raw rather than dropped.
 */
internal fun inputLines(compact: String): String {
    val body = compact.trim().removePrefix("{").removeSuffix("}").trim()
    if (body.isEmpty()) return "(no arguments)"
    val parts = body.split(Regex(",\\s(?=[A-Za-z_][\\w.]*=)"))
    val lines = parts.map { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) return compact
        part.substring(0, eq) + ": " + part.substring(eq + 1)
    }
    return lines.joinToString("\n")
}

/** "640ms", "1.2s", or null under 10ms, where a column of "3ms" is noise. */
internal fun formatDuration(ms: Long): String? = when {
    ms < 10 -> null
    ms < 1000 -> "${ms}ms"
    else -> "%.1fs".format(ms / 1000.0)
}

private const val AUTO_FOLD_DELAY_MS = 450L
private const val OUTPUT_FOLD_LINES = 10
private const val MAX_STACKED_MARKS = 4

/**
 * The user's answer to an approval prompt, kept in the transcript.
 *
 * Without this the decline vanished: the dialog closed, `ChatViewModel` recorded
 * it in a list no screen read, and the transcript resumed as if nothing had
 * been asked. For a destructive action that is the worst outcome available — the
 * user cannot afterwards tell whether the agent acted, and the app cannot show
 * them.
 *
 * The wording states the consequence because it is the real one: on a decline
 * `AgentController.confirmAndResume` puts "The user declined <tool>. Do not call
 * it again." into the session, so the model is told and told not to retry. On an
 * approval the staged call runs exactly as it was shown.
 */
@Composable
fun ApprovalRow(
    approval: ChatMessage.Approval,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
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
