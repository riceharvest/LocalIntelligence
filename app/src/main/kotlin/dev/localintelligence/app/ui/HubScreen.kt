package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import dev.localintelligence.core.hub.HubGgufFile
import dev.localintelligence.core.hub.LiteRtFit
import dev.localintelligence.core.hub.liteRtFits
import dev.localintelligence.app.settings.SimpleSetup
import dev.localintelligence.core.hub.formatBytes
import java.util.Locale

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
fun HubScreen(
    viewModel: HubViewModel,
    onBack: () -> Unit,
    /** Returns to the chat once a download is loaded. */
    onStartChat: () -> Unit = {},
    /** Opens the Models screen, for a download that was saved but did not start. */
    onOpenModels: () -> Unit = {},
    /** True in Technical mode; Simple shows the verdict and the recommended file only. */
    technical: Boolean = false,
    /**
     * True in the Play build's Simple mode: no search, only [recommended] offered. Passed in
     * from the mode, which the Activity re-reads on every settings change, as [technical] is.
     */
    restricted: Boolean = false,
    /** The models a restricted screen offers as cards, the phone's suggestion first. */
    recommended: List<SimpleSetup.RecommendedModel> = emptyList(),
) {
    val hubContext = LocalContext.current
    // The ViewModel re-checks the restriction against what it last saw, so this is safe on every
    // composition and on rotation; it is what drops a listing the new mode does not allow.
    LaunchedEffect(restricted) { viewModel.onRestrictionChanged() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val suggestions by viewModel.searchResults.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val searchNote by viewModel.searchNote.collectAsStateWithLifecycle()
    val showingPopular by viewModel.showingPopular.collectAsStateWithLifecycle()
    // WHY a local: `HubUiState.blocked` and `HubUiState.stopped` are computed
    // properties, and a computed property cannot be smart-cast across a
    // recomposition boundary.
    val blocked = state.blocked
    val stopped = state.stopped
    // Simple mode starts with the recommended file only; this reveals the rest.
    var showAllVersions by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.download_a_model)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
    val nonGguf = state.nonGgufFiles
    // WHY the fit is computed here, from the names, and not stored in the state: the list is the
    // repository's own files, and the phone's generation is fixed for the session.
    val fits = remember(nonGguf, viewModel.deviceTensorGeneration) {
        liteRtFits(nonGguf.map { it.fileName }, viewModel.deviceTensorGeneration)
    }
    val hiddenCount = fits.count { it == LiteRtFit.Hidden }
    // Default off, and reset when a different repository is listed. Not persisted.
    var showAllLiteRt by rememberSaveable(nonGguf.firstOrNull()?.repo?.id.orEmpty()) { mutableStateOf(false) }
    // Recommended first; sortedBy is stable, so the rest keep the size order from the repository.
    val shown = nonGguf.indices
        .filter { showAllLiteRt || fits[it] != LiteRtFit.Hidden }
        .sortedBy { if (fits[it] == LiteRtFit.Recommended) 0 else 1 }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!restricted) item {
            OutlinedTextField(
                value = viewModel.repoText,
                onValueChange = viewModel::onRepoInputChanged,
                label = { Text(stringResource(R.string.search_models)) },
                placeholder = { Text(if (technical) stringResource(R.string.qwen3_0_6b_gemma_liquidai_lfm3) else stringResource(R.string.model_name_e_g_gemma)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                modifier = Modifier.fillMaxWidth(),
                // WHY enabled while downloading: cancelling is a button, not a
                // reason to lock the user out of the screen they are on.
                enabled = !state.downloading,
            )
        }

        if (searching && !state.downloading && !restricted) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        searchNote?.let { note ->
            if (!state.downloading && !restricted) {
                item { Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        // Matches for what is typed so far (or, before typing, the most downloaded
        // models), most downloaded first. A tap lists that repo's files.
        if (suggestions.isNotEmpty() && !state.downloading && state.files.isEmpty() && !restricted) {
            if (showingPopular) {
                item { Text(stringResource(R.string.good_on_phones), style = MaterialTheme.typography.labelLarge) }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    suggestions.forEach { hit ->
                        ListItem(
                            headlineContent = { Text(hit.repoId.name) },
                            supportingContent = {
                                Text(
                                    if (hit.downloads > 0) stringResource(R.string.downloads, hit.repoId.owner, compactCount(hit.downloads))
                                    else hit.repoId.owner,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { viewModel.pickSearchHit(hit) },
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!restricted) Button(
                    onClick = viewModel::submit,
                    enabled = !state.loading && !state.downloading,
                ) {
                    Text(stringResource(R.string.search))
                }
                if (state.loading) {
                    // Labelled, because a bare spinner next to a button is a
                    // control that cannot say what it is waiting for, and this
                    // one waits on somebody else's server. Announced as a live
                    // region so a screen-reader user hears the wait start.
                    val asking = stringResource(R.string.hub_a11y_asking_hf)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .height(24.dp)
                            .semantics { contentDescription = asking },
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

        // Play build, Simple mode: the recommended models as cards, and nothing else to search.
        // Shown only while no model is listed, loading or downloading, so the screen shows one thing.
        if (restricted && !state.loading && state.selected == null && !state.downloading && state.progress == null) {
            items(recommended, key = { it.model.repo + "/" + it.model.file }) { option ->
                RecommendedModelCard(option = option, onDownload = { viewModel.prefill(option.model.repo, option.model.file) })
            }
            item {
                Text(
                    stringResource(R.string.simple_hub_more_choices),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // The size and the fit verdict, before the button.
        val plan = state.plan
        val selected = state.selected
        if (selected != null && plan != null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!technical) {
                            Text(
                                text = if (plan.ram.fits) stringResource(R.string.fits_on_this_phone) else stringResource(R.string.too_big_for_this_phone),
                                style = MaterialTheme.typography.headlineSmall,
                                color = if (plan.ram.fits) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Text(
                                stringResource(R.string.download_2, formatBytes(selected.sizeBytes)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else {
                        Text(selected.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.download_size, formatBytes(selected.sizeBytes)),
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
                                stringResource(R.string.the_size_and_the_memory_figure_are_read_3)
                            } else {
                                stringResource(R.string.the_header_could_not_be_read_so_the)
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
                                stringResource(R.string.estimated_range_to, formatBytes(plan.ram.lowBytes), formatBytes(plan.ram.highBytes)) +
                                    if (state.exactFit) {
                                        stringResource(R.string.the_figure_above_is_the_one_the_load_3)
                                    } else {
                                        stringResource(R.string.that_is_wide_enough_for_the_verdict_3)
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
                                stringResource(R.string.that_header_has_already_been_read_so),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(R.string.free_storage_on_this_phone, formatBytes(plan.freeDiskBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        }
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
        state.nonGgufNotice(hubContext)?.takeIf { technical }?.let { notice ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.litert_lm_models_in_this_repository, shown.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (shown.isEmpty()) {
                            Text(
                                text = stringResource(R.string.none_of_these_run_on_this_phone),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        shown.forEach { index ->
                            val file = nonGguf[index]
                            val fit = fits[index]
                            if (fit == LiteRtFit.Recommended) {
                                Text(
                                    text = stringResource(R.string.recommended_for_this_phone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = if (file.sizeBytes > 0) {
                                    "${formatBytes(file.sizeBytes)} — ${file.fileName}"
                                } else {
                                    // An unbacked sub-1 KiB `size` is a git
                                    // pointer, not a model. Printing it would
                                    // report a 3.4 GB model as 130 bytes.
                                    stringResource(R.string.size_not_reported, file.fileName)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            file.npuLabel(LocalContext.current)?.let { label ->
                                Text(text = label, style = MaterialTheme.typography.bodySmall)
                            }
                            if (fit == LiteRtFit.Hidden) {
                                Text(
                                    text = stringResource(R.string.does_not_run_on_this_phone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Only a `.litertlm` gets the action. A `.task` is
                            // listed for information, and a zero size is never
                            // offered because the downloader's size check would
                            // be skipped.
                            if (file.isLiteRtLm && file.sizeBytes > 0 && file.onDeviceKey in state.nonGgufOnDevice) {
                                // Already on the phone: the file is adopted, so there is nothing to download.
                                Text(
                                    text = stringResource(R.string.on_this_phone),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (file.isLiteRtLm && file.sizeBytes > 0) {
                                OutlinedButton(
                                    onClick = { viewModel.startNonGgufDownload(file) },
                                    enabled = !state.downloading,
                                ) {
                                    Text(stringResource(R.string.download_3, formatBytes(file.sizeBytes)))
                                }
                            }
                        }
                        if (hiddenCount > 0) {
                            TextButton(onClick = { showAllLiteRt = !showAllLiteRt }) {
                                Text(if (showAllLiteRt) stringResource(R.string.hide_files_this_phone_cannot_run) else stringResource(R.string.show_all_files, nonGguf.size))
                            }
                        }
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        if (!technical && state.files.isEmpty() && state.nonGgufNotice(hubContext) != null) {
            item {
                Text(
                    stringResource(R.string.no_models_in_this_repository_can_run_on),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.files.isNotEmpty()) {
            item { Text(if (technical) stringResource(R.string.available_files) else stringResource(R.string.versions), style = MaterialTheme.typography.titleSmall) }
            // Simple: the recommended file (and whatever is selected), with the rest behind a button.
            val recommended = state.recommended
            val collapse = !technical && !restricted && !showAllVersions && recommended != null
            val shown = if (collapse) {
                state.files.filter { it == recommended || it.fileName == state.selected?.fileName }
            } else {
                state.files
            }
            // Simple mode names versions by size order, not by quant code.
            val bySize = shown.sortedBy { it.sizeBytes }
            items(shown, key = { it.fileName + it.sizeBytes }) { file ->
                QuantRow(
                    label = if (technical) {
                        file.quant?.label ?: stringResource(R.string.unknown_quant)
                    } else if (file == recommended) {
                        stringResource(R.string.recommended)
                    } else if (recommended != null && file.sizeBytes < recommended.sizeBytes) {
                        stringResource(R.string.smaller)
                    } else if (recommended == null && bySize.size > 1 && file == bySize.first()) {
                        stringResource(R.string.smaller)
                    } else {
                        stringResource(R.string.higher_quality)
                    },
                    technical = technical,
                    recommended = file == recommended,
                    file = file,
                    selected = state.selected?.fileName == file.fileName,
                    enabled = !state.downloading,
                    onSelect = { viewModel.select(file) },
                )
            }
            if (collapse && shown.size < state.files.size) {
                item {
                    TextButton(onClick = { showAllVersions = true }) { Text(stringResource(R.string.show_all_versions)) }
                }
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
                            val starting = stringResource(R.string.hub_a11y_starting_download)
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(24.dp)
                                    .semantics { contentDescription = starting },
                            )
                            Text(stringResource(R.string.starting_download))
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
                    if (state.ready) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.ready_to_use),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (technical) {
                                (state.progress as? DownloadProgress.Done)?.let { done ->
                                    Text(
                                        stringResource(R.string.saved_to, done.file.fileName),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            Button(onClick = onStartChat) { Text(stringResource(R.string.start_chatting)) }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                note ?: stringResource(R.string.hub_downloaded_wont_start),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (note == null || note == stringResource(R.string.hub_downloaded_wont_start)) {
                                Button(onClick = onOpenModels) { Text(stringResource(R.string.go_to_models)) }
                            }
                        }
                    }
                }
                // WHY THE STOPPED STATE IS RENDERED RATHER THAN FALLING THROUGH
                // TO THE IDLE BUTTON: the transfer is resumable and the bytes
                // already spent are known, so the alternative was a button
                // reading "Download (668 MB)" under a message that says
                // "Tap retry to resume" — naming a control that did not exist
                // while quoting a size the next tap would not transfer.
                state.stopped != null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // A deliberate stop is not a failure: no error colour for it.
                        val userCancelled = stopped!!.error is HubError.Cancelled
                        Text(
                            text = stopped.error.message,
                            color = if (userCancelled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.partialBytesKept <= 0) {
                            Text(
                                text = stringResource(R.string.nothing_was_saved_so_the_button_below),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // Same action as the idle button: startDownload resumes
                        // from the kept .part file, so this is the resume path.
                        Button(
                            onClick = viewModel::startDownload,
                            enabled = state.plan?.isAllowed == true,
                        ) {
                            Text(
                                if (state.resumable) {
                                    stringResource(R.string.resume_download)
                                } else {
                                    "Download${state.sizeLabel(LocalContext.current)?.let { " ($it)" } ?: ""}"
                                },
                            )
                        }
                    }
                }
                blocked != null -> {
                    // The refusal is shown in full, not summarised: the user is
                    // being refused a 2 GB download and deserves the reason.
                    Text(
                        if (!technical && plan?.ram?.fits == false) {
                            stringResource(R.string.too_big_for_this_phone_try_a_smaller_2)
                        } else {
                            blocked.message
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Restricted: the cards above are the whole screen, so no search prompt is added.
                restricted && selected == null -> Unit
                selected == null -> {
                    // The idle state. This used to render nothing at all: a
                    // fresh screen was a text field, a button, and 24 dp of
                    // bottom padding, which is indistinguishable from a screen
                    // that failed to load. A user who opened "Download a model"
                    // and saw nothing had no way to learn what the field wanted
                    // in it.
                    Text(
                        text = if (technical) {
                            stringResource(R.string.start_typing_a_model_name_and_pick_a_3)
                        } else {
                            stringResource(R.string.search_for_a_model_you_ll_see_what_fits_3)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The second format, named before the user goes looking for it: a
                    // repository such as `litert-community/gemma-4-E4B-it-litert-lm` is
                    // public and hosts a real model, and would otherwise read as empty.
                    if (technical) {
                        Text(
                            text = stringResource(R.string.litert_lm_litertlm_files_in_a_2),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
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
                                state.alreadyOnDevice -> stringResource(R.string.add_to_models)
                                // A resume moves only the missing bytes, so the
                                // full size next to it would be a second
                                // overstatement on the same button.
                                state.resumable -> stringResource(R.string.resume_download)
                                else -> "Download${state.sizeLabel(LocalContext.current)?.let { " ($it)" } ?: ""}"
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
 * One model the Play build's Simple mode offers, with its size and its reason.
 *
 * ## Why Download only lists the file
 *
 * The button runs the same prefill the Settings suggestion card runs: the file is listed and
 * selected, then its size, verdict and the existing download button come next. Nothing is
 * fetched by the tap itself, so the user always sees the fit verdict before any bytes move.
 */
@Composable
private fun RecommendedModelCard(option: SimpleSetup.RecommendedModel, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.model.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.download_2, formatBytes(option.model.sizeBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                option.model.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            option.note?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onDownload, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.download)) }
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
    label: String,
    technical: Boolean,
    recommended: Boolean,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (recommended && technical) {
                    // A plain label, not a chip: the whole row is already the tap target.
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.recommended),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (technical) {
                Text(
                    "${formatBytes(file.sizeBytes)} — ${file.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            stringResource(R.string.downloading_percent_of, (fraction * 100).toInt(), formatBytes(progress.bytesDownloaded), formatBytes(progress.totalBytes))
        } else {
            stringResource(R.string.downloading_so_far_total_size_not, formatBytes(progress.bytesDownloaded))
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
            stringResource(R.string.starting)
        }
        Text(
            // WHY THE TOTAL IS CONDITIONAL: the server does not always send
            // Content-Length, and `totalBytes` is -1 when it does not. The
            // previous line printed it unconditionally, so a HuggingFace
            // response without a length rendered the literal "12.0 MB of -1 B"
            // to the user.
            text = if (progress.totalBytes > 0) {
                stringResource(R.string.of, formatBytes(progress.bytesDownloaded), formatBytes(progress.totalBytes), rateLabel)
            } else {
                stringResource(R.string.downloaded, formatBytes(progress.bytesDownloaded), rateLabel)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

/** 1234 -> "1.2K", 2345678 -> "2.3M": a download count read at a glance. */
private fun compactCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.ROOT, "%.1fK", n / 1_000.0)
    else -> n.toString()
}
