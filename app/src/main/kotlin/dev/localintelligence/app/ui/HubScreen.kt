package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubGgufFile
import dev.localintelligence.core.hub.formatBytes

/**
 * The HuggingFace download screen: type a repo id, pick a quant, see the size
 * and the fit verdict, download.
 *
 * ## Why the fit verdict is the largest thing on the screen
 *
 * The user flow this replaces was "import a .gguf you downloaded by hand on a
 * laptop". The new flow has to earn trust for a decision the old one deferred
 * entirely: *is this model going to work on my phone?* A confident "no, this
 * needs 2.9 GB and you have 2.1" is more valuable than a fast download, and it
 * is the reason [HubViewModel.resolve] refuses to hand the download button
 * anything it has not already checked.
 *
 * The refusal is rendered in the error colour and names the two levers the user
 * actually has — a smaller quantization, or a shorter context — because a
 * refusal the user cannot act on is just a dead end.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(viewModel: HubViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // WHY a local: `HubUiState.blocked` is a computed property, and a computed
    // property cannot be smart-cast across a recomposition boundary.
    val blocked = state.blocked

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download a model") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.repoInput,
                onValueChange = viewModel::onRepoInputChanged,
                label = { Text("HuggingFace repository") },
                placeholder = { Text("Qwen/Qwen3-0.6B-GGUF") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                // WHY enabled while downloading: cancelling is a button, not a
                // reason to lock the user out of the screen they are on.
                enabled = !state.downloading,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::resolve,
                    enabled = !state.loading && !state.downloading,
                ) {
                    Text("Find models")
                }
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                }
            }
        }

        state.error?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // The size and the fit verdict, before the button.
        val plan = state.plan
        val selected = state.selected
        if (selected != null && plan != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(selected.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Download size: ${formatBytes(selected.sizeBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // WHY the basis is shown and not just the verdict: a
                        // refusal computed from a 4 MiB header probe is a fact
                        // about this file, while one computed from its NAME is a
                        // guess, and the user is being asked to spend 668 MB of
                        // their data on the strength of it. Saying which one they
                        // are looking at is the difference between a number they
                        // can check and a number they have to trust.
                        Text(
                            if (state.exactFit) {
                                "Measured from the model's own header."
                            } else {
                                "Estimated from the file name — could not read the model's header."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            plan.ram.explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (plan.ram.fits) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            "Free storage: ${formatBytes(plan.freeDiskBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.files.isNotEmpty()) {
            item { Text("Available files", style = MaterialTheme.typography.titleSmall) }
            items(state.files, key = { it.fileName + it.sizeBytes }) { file ->
                QuantRow(
                    file = file,
                    selected = state.selected?.fileName == file.fileName,
                    enabled = !state.downloading,
                    onSelect = { viewModel.select(file) },
                )
            }
        }

        item {
            when {
                state.downloading -> {
                    val progress = state.progress
                    if (progress is DownloadProgress.InProgress) {
                        DownloadProgressBlock(progress, viewModel::cancelDownload)
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(24.dp))
                            Text("Starting download…")
                        }
                    }
                }
                state.progress is DownloadProgress.Done -> {
                    // WHY the completion state says what happened to the MODEL and
                    // not just to the download: the question behind every one of
                    // these bytes is "can I use this now", and "downloaded to app
                    // storage" does not answer it. `registeredNote` carries the
                    // registrar's own answer, including its failure cases.
                    val note = state.registeredNote
                    Text(
                        note ?: "Downloaded. Open Models to load it.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                blocked != null -> {
                    // The refusal is shown in full, not summarised: the user is
                    // being refused a 2 GB download and deserves the reason.
                    Text(
                        blocked.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                else -> {
                    Button(
                        onClick = viewModel::startDownload,
                        enabled = state.selected != null && state.plan?.isAllowed == true,
                    ) {
                        // WHY the label changes: the button's action changes too.
                        // "Download" on a file that is already in app storage
                        // promises 668 MB of transfer that will not happen, and
                        // "Add to models" is the action that will.
                        Text(
                            when {
                                state.alreadyOnDevice -> "Add to models"
                                else -> "Download${state.sizeLabel?.let { " ($it)" } ?: ""}"
                            },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    }
}

/** One selectable quant row, with the size beside it. */
@Composable
private fun QuantRow(
    file: HubGgufFile,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                file.quant?.label ?: "unknown quant",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${formatBytes(file.sizeBytes)} — ${file.fileName}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The progress bar and the cancel button, shown only while downloading. */
@Composable
private fun DownloadProgressBlock(
    progress: DownloadProgress.InProgress,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // WHY a determinate bar only when the total is known: an indeterminate
        // bar that suddenly becomes determinate looks like a glitch, and the
        // total is -1 whenever the server did not send Content-Length.
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val rateLabel = if (progress.bytesPerSecond > 0) {
            "${formatBytes(progress.bytesPerSecond)}/s"
        } else {
            "starting…"
        }
        Text(
            "${formatBytes(progress.bytesDownloaded)} of ${formatBytes(progress.totalBytes)} — $rateLabel",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}
