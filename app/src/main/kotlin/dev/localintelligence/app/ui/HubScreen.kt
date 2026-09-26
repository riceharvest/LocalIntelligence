package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
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
    // WHY a local: `HubUiState.blocked` and `HubUiState.stopped` are computed
    // properties, and a computed property cannot be smart-cast across a
    // recomposition boundary.
    val blocked = state.blocked
    val stopped = state.stopped

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
                    // Labelled, because a bare spinner next to a button is a
                    // control that cannot say what it is waiting for, and this
                    // one waits on somebody else's server. Announced as a live
                    // region so a screen-reader user hears the wait start.
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(24.dp)
                            .semantics { contentDescription = "Asking HuggingFace for this repository" },
                    )
                }
            }
        }

        state.error?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
                        // WHY THE BASIS IS SHOWN AND NOT JUST THE VERDICT: a
                        // refusal computed from a 4 MiB header probe is a fact
                        // about this file, while one computed from its NAME is a
                        // guess, and the user is being asked to spend 668 MB of
                        // their data on the strength of it. Saying which one they
                        // are looking at is the difference between a number they
                        // can check and a number they have to trust.
                        Text(
                            if (state.exactFit) {
                                "The size and the memory figure are read from the " +
                                    "model's own header, over the network, before you " +
                                    "download it. Not a measurement of this phone."
                            } else {
                                "The header could not be read, so the memory figure " +
                                    "below is estimated from the file name and size."
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
                        // WHY THE RANGE IS RENDERED AT ALL: `RamFit` carries
                        // `lowBytes`/`highBytes` and an `uncertain` verdict that
                        // the screen previously dropped on the floor, so a
                        // name-derived estimate was shown as one confident
                        // point figure. The two ends are the same calculation
                        // under the architecture assumptions that separate a
                        // phone-sized KV cache from a desktop-sized one, and
                        // `uncertain` is core's own statement that the verdict
                        // could land on either side. Showing it is the only way
                        // the refusal below it can be trusted.
                        if (plan.ram.uncertain) {
                            Text(
                                "Estimated range: ${formatBytes(plan.ram.lowBytes)} to " +
                                    "${formatBytes(plan.ram.highBytes)}." +
                                    if (state.exactFit) {
                                        " The figure above is the one the load gate " +
                                            "will use."
                                    } else {
                                        " That is wide enough for the verdict above " +
                                            "to go either way."
                                    },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // WHY THE STALE SENTENCE IS CORRECTED HERE: when the
                        // header was read, `FitGate.explanation` may still say
                        // "the file's own header settles it for 8 MB of
                        // download" — a promise of a step this screen already
                        // took. Left alone, the screen offers the user a
                        // download that is already in the number above it. The
                        // wording lives in :core, which this screen does not
                        // own, so the correction is made here.
                        if (state.exactFit && plan.ram.fits &&
                            plan.ram.highBytes > plan.ram.availableBytes
                        ) {
                            Text(
                                "That header has already been read, so the higher figure " +
                                    "is not needed. This one loads.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Free storage on this phone: ${formatBytes(plan.freeDiskBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // WHY THE `.litertlm` FILES ARE LISTED AND NOT MADE INTO QUANT ROWS:
        // the list below is priced by FitGate against the GGUF tensor table, and
        // a LiteRT-LM FlatBuffer has no GGUF header. Offering one as a
        // selectable row would put a RAM figure on this screen derived from a
        // format that arithmetic knows nothing about — and this screen's
        // entire value is that its numbers can be believed. A wrong number here
        // is worse than no row, so these get their own block: the real file
        // name, the real byte size (a fact about the server's bytes, not a
        // memory estimate), and the reason the app cannot fetch it.
        val nonGguf = state.nonGgufFiles
        state.nonGgufNotice?.let { notice ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "LiteRT-LM models in this repository " +
                                "(${nonGguf.size}, not downloadable here)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        // The size is `lfs.size` — the payload size HF reports
                        // for the blob. It is a file size and nothing more; it is
                        // never combined with a context length or a tensor
                        // count to produce a memory figure, because there is no
                        // arithmetic in this app for that format.
                        nonGguf.forEach { file ->
                            Text(
                                text = if (file.sizeBytes > 0) {
                                    "${formatBytes(file.sizeBytes)} — ${file.fileName}"
                                } else {
                                    // An unbacked sub-1 KiB `size` is a git
                                    // pointer, not a model. Printing it would
                                    // report a 3.4 GB model as 130 bytes.
                                    "size not reported — ${file.fileName}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
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
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(24.dp)
                                    .semantics { contentDescription = "Starting the download" },
                            )
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
                // WHY THE STOPPED STATE IS RENDERED RATHER THAN FALLING THROUGH
                // TO THE IDLE BUTTON: the transfer is resumable and the bytes
                // already spent are known, so the alternative was a button
                // reading "Download (668 MB)" under a message that says
                // "Tap retry to resume" — naming a control that did not exist
                // while quoting a size the next tap would not transfer.
                state.stopped != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stopped!!.error.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (state.partialBytesKept > 0) {
                                "${formatBytes(state.partialBytesKept)} is already on " +
                                    "this phone and is kept. The button below resumes " +
                                    "from there rather than starting again."
                            } else {
                                "Nothing was kept, so the button below starts from " +
                                    "the beginning."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                selected == null -> {
                    // The idle state. This used to render nothing at all: a
                    // fresh screen was a text field, a button, and 24 dp of
                    // bottom padding, which is indistinguishable from a screen
                    // that failed to load. A user who opened "Download a model"
                    // and saw nothing had no way to learn what the field wanted
                    // in it.
                    Text(
                        text = "Type a HuggingFace repository — owner/name, like " +
                            "Qwen/Qwen3-0.6B-GGUF — and Find models will list the " +
                            "GGUF files in it with the size of each and whether it " +
                            "fits in this phone's memory, before anything is " +
                            "downloaded. Gated repositories need a licence accepted " +
                            "on huggingface.co first; this app has no token field, " +
                            "so a private or gated repository will be refused.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The second format, named before the user goes looking for
                    // it. A repository such as
                    // `litert-community/gemma-4-E4B-it-litert-lm` is public and
                    // ungated and hosts a real model, and before this it
                    // rendered as "no GGUF files this app can use" — which reads
                    // as an empty repository rather than as a capability the
                    // app has and cannot yet feed. Saying it up front costs two
                    // sentences and saves the user a search.
                    Text(
                        text = "LiteRT-LM (.litertlm) models are not listed as " +
                            "downloadable. The app has a backend for them and no " +
                            "way to fetch one, so if the repository holds any you " +
                            "will see them named with their size and an explanation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                else -> {
                    Button(
                        onClick = viewModel::startDownload,
                        enabled = state.plan?.isAllowed == true,
                    ) {
                        // WHY the label changes: the button's action changes too.
                        // "Download" on a file that is already in app storage
                        // promises 668 MB of transfer that will not happen, and
                        // "Add to models" is the action that will.
                        Text(
                            when {
                                state.alreadyOnDevice -> "Add to models"
                                // A resume moves only the missing bytes, so the
                                // full size next to it would be a second
                                // overstatement on the same button.
                                state.resumable -> "Resume download"
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

/**
 * One selectable quant row, with the size beside it.
 *
 * ## Why the row is the touch target and not the radio
 *
 * Only the 20 dp `RadioButton` was clickable before. The filename and size
 * beside it are the information a user picks from, and a 20 dp target is
 * below the 48 dp accessibility floor, so a motor-impaired user could not
 * reliably select a quant at all — and a screen reader reached a bare radio
 * button whose label was whatever the row's `Text` children happened to be
 * announced as. `selectable` with `Role.RadioButton` makes the whole row one
 * node that reads as "4-bit Q4_K_M, 668 MB, qwen3-4b-q4_k_m.gguf, selected" and
 * takes one double-tap anywhere across it. The radio's own `onClick` is null so
 * it does not become a second, nested tap target for the same action.
 */
@Composable
private fun QuantRow(
    file: HubGgufFile,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    // No contentDescription on purpose: `selectable` merges the row's own
    // children, so TalkBack reads the quant, the size and the filename in one
    // pass. Overriding it would replace those with a duplicate string that has
    // to be kept in step with them.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
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
        // The bar carries its own spoken value. A bare progress indicator is
        // announced as "progress indicator" and nothing else, so a screen-reader
        // user watching a 668 MB transfer has no way to know whether it is at
        // 2% or 90% — the one number this whole block exists to deliver.
        val spoken = if (fraction != null) {
            "Downloading, ${(fraction * 100).toInt()} percent, " +
                "${formatBytes(progress.bytesDownloaded)} of " +
                "${formatBytes(progress.totalBytes)}"
        } else {
            "Downloading, ${formatBytes(progress.bytesDownloaded)} so far, " +
                "total size not reported by the server"
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = spoken },
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = spoken },
            )
        }
        val rateLabel = if (progress.bytesPerSecond > 0) {
            "${formatBytes(progress.bytesPerSecond)}/s"
        } else {
            "starting…"
        }
        Text(
            // WHY THE TOTAL IS CONDITIONAL: the server does not always send
            // Content-Length, and `totalBytes` is -1 when it does not. The
            // previous line printed it unconditionally, so a HuggingFace
            // response without a length rendered the literal "12.0 MB of -1 B"
            // to the user.
            text = if (progress.totalBytes > 0) {
                "${formatBytes(progress.bytesDownloaded)} of " +
                    "${formatBytes(progress.totalBytes)} — $rateLabel"
            } else {
                "${formatBytes(progress.bytesDownloaded)} downloaded — " +
                    "the server did not say how big this file is — $rateLabel"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}
