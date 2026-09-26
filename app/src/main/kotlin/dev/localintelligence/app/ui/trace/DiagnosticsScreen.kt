package dev.localintelligence.app.ui.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.core.metrics.CheckOutcome
import dev.localintelligence.core.metrics.DiagnosticCheck
import dev.localintelligence.core.metrics.DiagnosticReport
import dev.localintelligence.core.metrics.Finding
import dev.localintelligence.core.metrics.Provenance

/**
 * The self-check screen: what is actually broken on this phone, and what this
 * build cannot find out.
 *
 * ## Why every check is expanded by default
 *
 * A collapsed card saying "native library: FAIL" is the same useless sentence
 * as a toast. The three things that make a failure actionable — what was
 * verified, what the loader itself said, and where the button goes — are all
 * below the fold in a collapsed card, so nothing is collapsed. A user who came
 * here because something is wrong should not have to ask a card to open.
 *
 * ## Why "cannot tell" is rendered as prominently as a failure
 *
 * It is the same card, the same shape, the same weight. The distinction is
 * colour and a word, not a collapsed section. A screen where the unknowable is
 * visually quieter than the broken teaches users to read the quiet parts as
 * fine, which is the specific habit this project is trying to break.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permissions, memory and what is resident all change while the user is in
    // another app. Re-running on resume is the only way the answers on screen
    // are the answers now.
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
                title = { Text("Self-check") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Run every check again")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { error ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "The self-check itself failed",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "Nothing below this card ran, so nothing below it should " +
                                    "be read as passing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            state.report?.let { report ->
                item { ReportHeader(report, state.running) }
                items(report.checks, key = { it.id }) { check ->
                    CheckCard(
                        check = check,
                        onFix = { action ->
                            when (action) {
                                dev.localintelligence.core.metrics.DiagnosticAction.OpenModels ->
                                    onOpenModels()
                                else -> action.intentFor(context)?.let(context::startActivity)
                            }
                        },
                    )
                }
            }

            item { Footer() }
        }
    }
}

/**
 * The counts, and the sentence that keeps the screen honest.
 *
 * WHY THE BANNER IS NOT OPTIONAL FILL: this project has zero measured
 * performance figures for a real device. A screen full of numbers with no such
 * statement reads as though the numbers came from somewhere, and the one decode
 * figure that exists in the repository is an x86_64 emulator artefact. The
 * banner is what stops a screenshot of this screen from becoming evidence.
 */
@Composable
private fun ReportHeader(report: DiagnosticReport, running: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(report.headline(), style = MaterialTheme.typography.titleSmall)
            Text(
                "Every figure below is labelled with where it came from. " +
                    "This project contains no measured decode rate, latency or " +
                    "resident-memory figure for a real phone, and this screen does " +
                    "not invent one.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (running) {
                Text(
                    "Re-running…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One check, fully expanded. See the screen KDoc for why nothing collapses. */
@Composable
private fun CheckCard(
    check: DiagnosticCheck,
    onFix: (dev.localintelligence.core.metrics.DiagnosticAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (check.outcome) {
                CheckOutcome.FAIL -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutcomeBadge(check.outcome)
                Text(
                    check.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }

            Labelled("Checked", check.verified)
            Labelled("Found", check.finding)

            if (check.findings.isNotEmpty()) {
                HorizontalDivider()
                Text("Figures", style = MaterialTheme.typography.labelLarge)
                check.findings.forEach { FindingRow(it) }
            }

            // Rendered unconditionally and in the same weight as the finding.
            // A blank here would be the screen implying more than it knows.
            HorizontalDivider()
            Labelled("Cannot tell", check.cannotTell, emphasise = true)

            check.nextAction?.let { action ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (check.isUnfixableHere) {
                        Text(
                            action.describe() +
                                ". This is a build or packaging problem, not a " +
                                "setting: a user cannot fix it, and a button that " +
                                "pretends otherwise is worse than no button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedButton(onClick = { onFix(action) }) {
                            Text(action.describe())
                        }
                    }
                }
            }
        }
    }
}

/**
 * A number, its provenance, and the arithmetic behind it.
 *
 * WHY THE DERIVATION IS ALWAYS VISIBLE: a provenance label without its inputs
 * is a claim, and a claim is exactly what this project keeps making and
 * retracting. "computed" without "from 7 tensors x 4 KiB pages" is a number
 * nobody can check, and an uncheckable number is the thing this whole screen
 * exists to replace.
 */
@Composable
private fun FindingRow(finding: Finding) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                finding.label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            ProvenanceChip(finding.provenance)
        }
        Text(
            finding.value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            finding.derivation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * MEASURED / COMPUTED / UNKNOWN, in the vocabulary `docs/memory-model.md` set.
 *
 * WHY THREE STATES RATHER THAN A TICK: a tick next to a number is a claim of
 * trust, and a two-state indicator forces "I could not determine this" to be
 * drawn as either fine or broken. Both are lies, and this project has a long
 * list of comments that were true and behaviour that was not.
 */
@Composable
private fun ProvenanceChip(provenance: Provenance) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = when (provenance) {
            Provenance.MEASURED -> MaterialTheme.colorScheme.tertiaryContainer
            Provenance.COMPUTED -> MaterialTheme.colorScheme.secondaryContainer
            Provenance.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            provenance.label,
            style = MaterialTheme.typography.labelSmall,
            color = when (provenance) {
                Provenance.MEASURED -> MaterialTheme.colorScheme.onTertiaryContainer
                Provenance.COMPUTED -> MaterialTheme.colorScheme.onSecondaryContainer
                Provenance.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun OutcomeBadge(outcome: CheckOutcome) {
    val (label, container, content) = when (outcome) {
        CheckOutcome.PASS -> Triple(
            "verified",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        CheckOutcome.FAIL -> Triple(
            "broken",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
        )
        CheckOutcome.CANNOT_TELL -> Triple(
            "cannot tell",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(shape = RoundedCornerShape(4.dp), color = container) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Labelled(label: String, body: String, emphasise: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasise) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What this screen will never be able to tell you, said on the screen itself.
 *
 * WHY IT IS AT THE BOTTOM AND NOT ONLY IN A COMMENT: a user who screenshots
 * the top of this screen has the counts and the figures. The honest limit of
 * those figures is what stops the screenshot being used as a benchmark, and it
 * has to travel with them.
 */
@Composable
private fun Footer() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What this screen cannot tell you", style = MaterialTheme.typography.titleSmall)
            Text(
                "It never runs a model. Nothing here loads weights, so it cannot show " +
                    "whether generation succeeds, how fast it is, or how much memory it " +
                    "costs — the three questions people actually open a diagnostics " +
                    "screen to answer.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "It cannot compare this phone to another. A figure from this screen " +
                    "describes this device on this day under this load.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "It is not a benchmark and its output must not be quoted as one. The " +
                    "two decode figures in this project, about 0.67 tok/s and 37.4 " +
                    "tok/s, were taken on an x86_64 emulator and a desktop CPU. Neither " +
                    "is a phone number.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "It cannot tell you why something failed earlier in a run. It reports " +
                    "state at the moment you opened it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "To measure a loaded model, run docs/measure/measure_ram.sh from a " +
                    "computer with the phone attached. It prints '?' rather than " +
                    "inventing a value.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
