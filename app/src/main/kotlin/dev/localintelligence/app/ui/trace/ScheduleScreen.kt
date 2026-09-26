package dev.localintelligence.app.ui.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.app.data.ScheduledTask
import dev.localintelligence.app.data.TaskAction
import dev.localintelligence.app.data.TaskBlocker
import dev.localintelligence.app.data.TaskCadence
import dev.localintelligence.app.execution.ScheduledRunReporter.FAILED_PREFIX
import dev.localintelligence.app.execution.ScheduledRunReporter.REFUSED_PREFIX
import dev.localintelligence.app.execution.ScheduledRunReporter.SCHEDULE_WARNING_PREFIX

/**
 * Create, see and delete a scheduled task.
 *
 * ## The one thing this screen must not do
 *
 * It must not show a task as scheduled when it cannot run. Every state this
 * screen renders is a state the *device* is in — `canScheduleExactAlarms`,
 * `isPowerSaveMode`, the POST_NOTIFICATIONS grant, the model's own
 * `ModelAvailability` — read at composition, re-read on resume. There is no
 * optimistic "scheduled!" and no assume-will-work: a task with no model is
 * listed with the reason next to it, because the alternative is a row that
 * promises 07:00 and delivers nothing.
 *
 * ## Why the blocker list is above the form, not inside it
 *
 * Putting the reason under a disabled Create button makes the user hunt for it.
 * Putting it above the form means the screen answers "why can't I schedule
 * this?" before the question is asked, which is the only order in which it is
 * a useful answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    viewModel: ScheduleViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The self-check is reachable from here because this is the only navigable
    // screen in the package this change owns, and a diagnostics screen nobody
    // can reach is the same dead code this project keeps shipping. It belongs
    // in `MainActivity`'s NavHost next to the other routes; the exact edit is
    // in the PR description, and once that lands this branch's `showSelfCheck`
    // state and the app-bar icon below both go away.
    var showSelfCheck by rememberSaveable { mutableStateOf(false) }
    if (showSelfCheck) {
        DiagnosticsScreen(
            // Fully qualified because this composable's own `viewModel`
            // parameter shadows the `viewModel()` factory function of the same
            // name. Writing `viewModel<DiagnosticsViewModel>()` here resolves
            // to the ScheduleViewModel parameter and does not compile, which is
            // the kind of quiet trap a rename later would reintroduce.
            viewModel = androidx.lifecycle.viewmodel.compose.viewModel<DiagnosticsViewModel>(),
            onBack = { showSelfCheck = false },
            onOpenModels = onOpenModels,
            modifier = modifier,
        )
        return
    }

    // Readiness is a snapshot of settings the user can change in another app,
    // so it is re-read on every resume rather than captured at composition.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Scheduled tasks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSelfCheck = true }) {
                        Icon(
                            Icons.Filled.HealthAndSafety,
                            contentDescription = "Self-check: what is actually working on this phone",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The form is fixed above the list, not inside it: creating a task
            // is the primary action and it must not scroll away under a long
            // list of existing tasks.
            ScheduleForm(
                canCreate = state.canCreate,
                // The blockers list below covers the readiness term of
                // `canCreate`; this covers the other two. Without it a full
                // task list disables the button with no explanation anywhere
                // on screen, and the explanation the app does have can only be
                // produced by pressing it.
                disabledReason = state.disabledReason,
                onCreate = viewModel::create,
            )

            state.notice?.let { notice ->
                ScheduleNotice(notice)
            }

            state.readiness?.blockers?.takeIf { it.isNotEmpty() }?.let { blockers ->
                BlockerList(
                    blockers = blockers,
                    onFix = { action ->
                        // The Models destination is a route in this app's own
                        // NavHost, not a system screen, so it has no Intent and
                        // is reached through the callback. Everything else is a
                        // system screen; if the OEM has none, `intentFor` is
                        // null and nothing is started — the blocker text stays on
                        // screen, which is the honest outcome. A button that
                        // silently does nothing is indistinguishable from a
                        // broken app.
                        if (action == TaskAction.OpenModels) {
                            onOpenModels()
                            return@BlockerList
                        }
                        viewModel.intentFor(action)?.let { context.startActivity(it) }
                    },
                )
            }

            HorizontalDivider()

            if (state.tasks.isEmpty()) {
                Text(
                    text = "No scheduled tasks yet. Write what the assistant should do, " +
                        "pick how often, and it will run on this phone at that time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            isRunning = task.id == state.runningTaskId,
                            onDelete = { viewModel.delete(task.id) },
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleForm(
    canCreate: Boolean,
    disabledReason: String?,
    onCreate: (String, TaskCadence) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var cadence by remember { mutableStateOf<CadenceChoice>(CadenceChoice.EveryHour) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("What should the assistant do?") },
            placeholder = { Text("Check my notifications and summarise anything urgent") },
            modifier = Modifier.fillMaxWidth(),
            // Honest about the limit rather than silently truncating: a prompt
            // clipped mid-word is a prompt the agent cannot run.
            supportingText = { Text("Runs on this phone. Nothing is sent to a server.") },
            minLines = 2,
        )

        Text(
            text = "How often",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        // A single-select chip row. FilterChip reports its own selected state,
        // which is what a screen reader announces; a custom clickable row would
        // have to describe "selected" in words by hand and could get it wrong.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CadenceChoice.entries.forEach { choice ->
                FilterChip(
                    selected = choice == cadence,
                    onClick = { cadence = choice },
                    label = { Text(choice.label) },
                )
            }
        }

        // The reason sits directly above the control it explains, not only in
        // the blockers list further down, because the two cover different
        // reasons and the user is looking at the button.
        disabledReason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = { onCreate(prompt, cadence.cadence) },
            // Disabled rather than hidden. A Create button that vanishes is a
            // user with no idea that the feature exists; a disabled one with
            // the reason listed above it is an instruction.
            enabled = canCreate,
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text("Schedule this")
        }
    }
}

/** What a recurring task supports, and nothing more. */
private enum class CadenceChoice(val label: String, val cadence: TaskCadence) {
    EveryHour("Every hour", TaskCadence.EveryMinutes(60)),
    Every15Minutes("Every 15 min", TaskCadence.EveryMinutes(15)),
    DailyAtMorning("Daily 08:00", TaskCadence.DailyAt(8, 0)),
    DailyAtEvening("Daily 18:00", TaskCadence.DailyAt(18, 0)),
}

/**
 * How many lines a result may occupy in a list row.
 *
 * The stored string is already capped by `ScheduledRunReporter`; this is the
 * second cap, and it is the one that guarantees a row's height. A scheduled
 * task's answer is a paragraph and a `LazyColumn` item is not — a row that
 * grows to fit a 400-character answer pushes the next task off the screen,
 * which for a screen whose job is comparing tasks is a worse failure than
 * truncating one.
 */
private const val RESULT_MAX_LINES = 3

/**
 * One scheduled task.
 *
 * The row shows three things and each answers a question the user actually has:
 * what it will do, when it next runs, and what happened last time. The last
 * result is not decoration — a task that fired at 03:00 and found no model has
 * to say so here, because that is the only place the user will ever look.
 */
@Composable
private fun TaskRow(
    task: ScheduledTask,
    isRunning: Boolean,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = task.prompt,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                    )
                    Text(
                        text = TaskCadence.describe(task.cadence),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete this task")
                }
            }

            // The next-run line is stated differently when there is no next run,
            // because "next: <time>" on a paused or finished one-shot is a
            // promise the app is not keeping.
            //
            // WHY THE SCHEDULE-FAULT CASE IS HERE TOO: the paused and finished
            // cases below are driven by `task.enabled`, so they were already
            // honest. A task that is still enabled but whose re-arm *failed* is
            // not covered by either of them, and it is the one that reads worst:
            // the row showed "Next: <time>" directly above a result line saying
            // "Next run not scheduled: <reason>". Two adjacent lines, one
            // promising a run and one denying it, both true, and the user has
            // to guess which one the app means. The stored `nextRunAtMillis` is
            // still set in that state, so the time is not stale data — the
            // *alarm* is absent, and that is what the line has to say.
            val scheduleFaulted = task.lastResult
                ?.startsWith(SCHEDULE_WARNING_PREFIX) == true
            Text(
                text = when {
                    isRunning -> "Running now."
                    scheduleFaulted ->
                        "No next run scheduled. ${task.timeLabel()} is when it was " +
                            "due to run, not a promise."
                    !task.enabled && task.cadence == TaskCadence.Once ->
                        "Already ran. Not scheduled any more."
                    !task.enabled -> "Paused. Not scheduled."
                    else -> "Next: ${task.timeLabel()}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isRunning) FontWeight.Bold else FontWeight.Normal,
                color = if (isRunning) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 8.dp),
            )

            task.lastResult?.let { result ->
                LastRunLine(
                    task = task,
                    isRunning = isRunning,
                )
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    color = resultColor(result),
                    // Bounded here as well as at write time. The stored value
                    // is already truncated by `ScheduledRunReporter`, but a
                    // row written by an older version of the app is not, and
                    // `maxLines` is the only thing standing between a 4 KB
                    // observation and a list row that is taller than the
                    // screen. Two lines, because the outcome is the thing the
                    // user opened this screen for and the full answer is one
                    // tap away in the chat transcript.
                    maxLines = RESULT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    // A result that changes on its own (a run finishing while
                    // the screen is open) must be announced, or a screen-reader
                    // user never learns the outcome.
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (!isRunning) {
                TextButton(
                    onClick = if (task.enabled) onPause else onResume,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(if (task.enabled) "Pause" else "Resume")
                }
            }
        }
    }
}

/**
 * Why a scheduled task cannot run, with a button that goes where it is fixed.
 *
 * `liveRegion = Assertive` because this is the answer to a question the user
 * just asked by pressing Create and finding it disabled; a polite announcement
 * can be queued behind other speech and never heard.
 */
@Composable
private fun BlockerList(
    blockers: List<TaskBlocker>,
    onFix: (TaskAction) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive }) {
            Text(
                text = if (blockers.size == 1) {
                    "This task will not run yet:"
                } else {
                    "This task will not run until these are fixed:"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            blockers.forEach { blocker ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 48dp minimum touch target, as everywhere else in this
                        // app: the blocker is the one row that must be read and
                        // acted on, so it is the worst place for a small target.
                        .heightIn(min = 48.dp)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = blocker.message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onFix(blocker.action) }) {
                        Text(blocker.action.label())
                    }
                }
            }
        }
    }
}

/**
 * When the last run happened, and — if this task is running right now — that
 * the sentence below it is about a run still in flight.
 *
 * ## Why the time is on its own line
 *
 * A result on its own is a claim with no date. "Failed: the model was not
 * ready." read tomorrow morning is about *this morning's* run; read next week
 * it is about a run the user has long forgotten scheduling, and they will
 * assume it is current. Pairing the two is what makes the outcome checkable.
 *
 * Shown only when there is a result to explain. A task that has never fired has
 * nothing to date, and "Last run: never" is a worse line than no line — the
 * empty state above the list already says there is nothing yet.
 */
@Composable
private fun LastRunLine(task: ScheduledTask, isRunning: Boolean) {
    val at = task.lastRunAtMillis ?: return
    Text(
        text = if (isRunning) {
            "Running now. Started at ${timeOf(at)}."
        } else {
            "Last ran at ${timeOf(at)}."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * The colour of a result line.
 *
 * Not decoration: a list of sixteen tasks read at a glance needs the failure
 * and the refusal to be findable without reading every sentence. `Answered:`
 * stays in the neutral variant colour so a normal result does not shout, and
 * the two that need action are tinted. Exact-alarm access and no-model are not
 * failures of the *run*, so they stay neutral — the blockers list above is
 * where the user fixes those, and painting them red here would point at the
 * wrong thing.
 */
@Composable
private fun resultColor(result: String): Color = when {
    result.startsWith(REFUSED_PREFIX) || result.startsWith(FAILED_PREFIX) ->
        MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun timeOf(millis: Long): String =
    java.time.format.DateTimeFormatter
        .ofPattern("d MMM, HH:mm", java.util.Locale.getDefault())
        .format(
            java.time.Instant.ofEpochMilli(millis)
                .atZone(java.time.ZoneId.systemDefault()),
        )

/**
 * Why a refused action, in the user's terms.
 *
 * Separate from [BlockerList] because a refusal is about *this press* — "the
 * store is full", "Android said no" — while a blocker is about the device
 * state. Collapsing them would make a user who simply filled up their task list
 * read about battery settings.
 */
@Composable
private fun ScheduleNotice(notice: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            text = notice,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The button label for an action.
 *
 * A function on the enum's use site rather than a property on the enum, because
 * the enum is in `:app`'s data layer and this string is UI wording. Putting
 * "Fix" next to a data type is how a data class ends up carrying a translation
 * key.
 */
private fun TaskAction.label(): String = when (this) {
    TaskAction.OpenModels -> "Open models"
    TaskAction.OpenNotificationAccess -> "Fix"
    TaskAction.OpenBatterySettings -> "Fix"
    TaskAction.OpenExactAlarmSettings -> "Fix"
}
