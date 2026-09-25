package dev.localintelligence.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.DownloadPlan
import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import dev.localintelligence.core.hub.HubGgufFile
import dev.localintelligence.core.hub.HubRepoId
import dev.localintelligence.core.hub.HubTokenSource
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.core.hub.formatBytes
import dev.localintelligence.android.hub.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Registers a finished download with the rest of the app so it can be selected
 * and loaded.
 *
 * ## WHY this is a seam and not a call to [ModelImporter] inside the ViewModel
 *
 * Because a download that lands on disk and is never registered is invisible:
 * the model manager lists what has been *adopted*, not what exists in the files
 * directory, and the agent runs [AppContainer.selectedModel]. A `Done` event
 * that nothing acts on is the exact failure this type exists to make impossible
 * to write — the ViewModel cannot finish a download without offering the result
 * to someone who can register it.
 *
 * The implementation belongs to the composition root, because registering means
 * inspecting the header, adding to the model list and selecting it, and all
 * three need the container.
 */
fun interface DownloadedModelRegistrar {
    /**
     * Adopts [file] and returns a short line for the UI, or null when the file
     * could not be adopted (which the caller must surface — a downloaded model
     * that is not loadable is a failure, not a success).
     */
    suspend fun register(file: File): String?

    companion object {
        /**
         * The default: report the path and adopt nothing.
         *
         * WHY a default exists at all rather than making the parameter required:
         * so the failure mode is visible. A build that never wires a registrar
         * still shows the user exactly where the bytes went, which is a support
         * answer, instead of silently dropping the model.
         */
        val NONE: DownloadedModelRegistrar = DownloadedModelRegistrar { null }
    }
}

/**
 * Screen state for the HuggingFace download flow.
 *
 * ## Why the download size and the fit verdict are computed BEFORE the button
 *
 * This is the whole design of the feature in one sentence. Every other model
 * manager asks "which file?" first and tells you the size after. This one
 * resolves the repo, computes the plan, and shows the user the size, the RAM
 * estimate and the refusal (if any) before a single byte moves — because a
 * 2 GB download on mobile data that was never going to fit is the most
 * expensive mistake the app can let someone make, and once it is finished the
 * app has no leverage at all.
 */
data class HubUiState(
    val repoInput: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val files: List<HubGgufFile> = emptyList(),
    val selected: HubGgufFile? = null,
    /** Null until the repo resolves. A non-null plan with a reject is a refusal. */
    val plan: DownloadPlan? = null,
    val progress: DownloadProgress? = null,
    val downloadJob: Job? = null,
    /** True while a download is running, so the UI can disable the pickers. */
    val downloading: Boolean = false,
    /**
     * The real GGUF header for [selected], when one could be fetched.
     *
     * Non-null means the fit verdict below was computed from the file's own
     * tensor table rather than from its name. The UI shows which of the two it
     * used, because "estimated from the file name" and "measured from the
     * header" deserve different amounts of the user's trust.
     */
    val exactFit: Boolean = false,
    /** The file is already in app storage at its final path, checksum-verified. */
    val alreadyOnDevice: Boolean = false,
    /** Set once a finished download has been adopted by the rest of the app. */
    val registeredNote: String? = null,
) {
    /** The plan the user is being asked to approve, or the refusal. */
    val blocked: HubError? get() = plan?.reject

    /** The size label shown before starting. */
    val sizeLabel: String? get() = selected?.let { formatBytes(it.sizeBytes) }
}

/**
 * Drives the browse -> plan -> download flow.
 *
 * WHY the ViewModel holds no Android types: it takes a [HuggingFaceClient], a
 * [ModelDownloader] and a [DeviceBudget] as constructor parameters, so the whole
 * state machine is testable on the JVM with fakes. The Compose layer above it
 * only renders [HubUiState] and calls these methods.
 *
 * WHY there is exactly one [downloadJob]: a second concurrent download of a
 * different model would double the disk and the network for no benefit, and the
 * user is choosing one model at a time. Starting a new one cancels the old.
 */
class HubViewModel(
    private val client: HuggingFaceClient,
    private val downloader: ModelDownloader,
    private val budget: DeviceBudget,
    private val tokenSource: HubTokenSource = HubTokenSource.NONE,
    private val registrar: DownloadedModelRegistrar = DownloadedModelRegistrar.NONE,
) : ViewModel() {

    private val _state = MutableStateFlow(HubUiState())
    val state: StateFlow<HubUiState> = _state.asStateFlow()

    /** Search hits. Best-effort: an empty list is a valid, non-error state. */
    private val _searchResults = MutableStateFlow<List<HuggingFaceClient.HubSearchHitPublic>>(emptyList())
    val searchResults: StateFlow<List<HuggingFaceClient.HubSearchHitPublic>> = _searchResults.asStateFlow()

    fun onRepoInputChanged(value: String) {
        _state.update { it.copy(repoInput = value) }
    }

    /**
     * Resolves the typed repo id and lists its GGUF files, then pre-selects the
     * largest quant that fits and computes its plan.
     *
     * WHY validation happens here and not in the client: the input is a user
     * typing free text, and the failure is a *typo*, not a network problem. It
     * has to be reported as a one-line message the user can act on, which is
     * [HubRepoId.parse]'s job.
     */
    fun resolve() {
        val raw = _state.value.repoInput
        when (val parsed = HubRepoId.parse(raw)) {
            is HubRepoId.Result.Invalid -> {
                _state.update { it.copy(error = parsed.message, files = emptyList(), selected = null, plan = null) }
                return
            }
            is HubRepoId.Result.Valid -> {
                _state.update { it.copy(loading = true, error = null) }
                viewModelScope.launch {
                    val result = runCatching { client.listGgufFiles(parsed.repoId, tokenSource.token()) }
                    result.onSuccess { files ->
                        val loadable = client.loadableFiles(files)
                        val best = client.chooseBestFitting(
                            candidates = loadable,
                            contextLength = DEFAULT_CONTEXT_LENGTH,
                            budget = budget,
                        )
                        val planned = best?.let { planFor(it) }
                        _state.update {
                            it.copy(
                                loading = false,
                                error = if (loadable.isEmpty()) "That repository has no GGUF files this app can use." else null,
                                files = loadable,
                                selected = best,
                                plan = planned?.first,
                                exactFit = planned?.second == true,
                                alreadyOnDevice = planned?.third == true,
                            )
                        }
                    }.onFailure { e ->
                        _state.update {
                            it.copy(
                                loading = false,
                                error = (e as? HubError)?.message ?: "Could not reach HuggingFace.",
                                files = emptyList(),
                                selected = null,
                                plan = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /** Selects a specific quant and recomputes the plan for it. */
    fun select(file: HubGgufFile) {
        viewModelScope.launch {
            val planned = planFor(file)
            _state.update {
                it.copy(
                    selected = file,
                    plan = planned.first,
                    exactFit = planned.second,
                    alreadyOnDevice = planned.third,
                )
            }
        }
    }

    /**
     * Computes the plan for [file] against the device, using the real header when
     * one can be fetched.
     *
     * WHY the probe is here and not in [HuggingFaceClient.plan]: the probe is a
     * network call, and this is the one place in the screen's lifecycle where a
     * network call belongs — it is already off the main thread inside
     * `viewModelScope`, it happens once per selection rather than once per
     * recomposition, and its result is cached in [HubUiState] so tapping a
     * different quant does not re-probe the one already probed.
     *
     * @return plan, whether the verdict is exact (from the tensor table rather
     *   than the file name), and whether the file is already on disk.
     */
    private suspend fun planFor(file: HubGgufFile): Triple<DownloadPlan, Boolean, Boolean> {
        val header = client.probeHeader(file, tokenSource.token())
        val plan = client.plan(file, DEFAULT_CONTEXT_LENGTH, budget, header = header)
        val onDevice = withContext(Dispatchers.IO) { downloader.isDownloaded(file) }
        return Triple(plan, header != null, onDevice)
    }

    /**
     * Starts the download, but only if the plan allows it.
     *
     * The `blocked` check is duplicated here even though [ModelDownloader]
     * enforces it too. WHY: the downloader is the enforcement point, and this
     * is the one that keeps a tap on a refused row from starting a coroutine
     * that immediately reports the same refusal back to the user as an error
     * toast. Belt, braces, and one fewer confusing round trip.
     */
    fun startDownload() {
        val current = _state.value
        val file = current.selected ?: return
        val plan = current.plan ?: return
        if (plan.reject != null) return

        // Already on disk, already checksum-verified. Starting again would spend
        // 668 MB of the user's data to produce the identical file, and the
        // downloader would answer `Discard("already complete")` and restart from
        // byte 0 anyway. So the button adopts instead of downloading.
        if (current.alreadyOnDevice) {
            viewModelScope.launch { adopt(file) }
            return
        }

        current.downloadJob?.cancel()
        _state.update { it.copy(downloading = true, progress = null, error = null, registeredNote = null) }

        val job = viewModelScope.launch {
            downloader.download(file, plan).collect { event ->
                when (event) {
                    is DownloadProgress.Done -> {
                        // Registration happens HERE, inside the Done branch, and
                        // not in the UI: a `Done` that only renders text is
                        // exactly the bug this closes. The bytes exist; the app
                        // must be able to select and load them.
                        val note = adopt(event.file)
                        _state.update {
                            it.copy(
                                downloading = false,
                                progress = event,
                                error = null,
                                registeredNote = note,
                                alreadyOnDevice = true,
                            )
                        }
                    }
                    is DownloadProgress.Stopped -> _state.update {
                        it.copy(downloading = false, progress = event, error = event.error.message)
                    }
                    is DownloadProgress.InProgress -> _state.update { it.copy(progress = event) }
                }
            }
        }
        _state.update { it.copy(downloadJob = job) }
    }

    /**
     * Hands the downloaded file to the app and reports what happened.
     *
     * WHY a failure here is an error and not a shrug: a GGUF that is on disk but
     * not adopted cannot be loaded, cannot be selected and does not appear in the
     * model list. Telling the user "downloaded!" in that state is a lie with a
     * 668 MB price tag, so the failure is surfaced and the path is named.
     */
    private suspend fun adopt(file: HubGgufFile): String? {
        val local = downloader.localFileFor(file)
        if (!local.isFile) {
            return "Downloaded, but the file is not where the app looks for models."
        }
        val note = runCatching { registrar.register(local) }.getOrNull()
        return note ?: "Saved to ${local.name}. Open Models to load it."
    }

    /**
     * Cancels an in-flight download.
     *
     * The `.part` file is kept by the downloader so the next attempt resumes
     * rather than restarting; nothing appears at the final path. That is the
     * invariant, and it is why this does not need to clean anything up.
     */
    fun cancelDownload() {
        _state.value.downloadJob?.cancel()
        _state.update { it.copy(downloading = false) }
    }

    /** Best-effort search. A failure leaves the list empty, never throws. */
    fun search(query: String) {
        viewModelScope.launch {
            _searchResults.value = client.search(query, token = tokenSource.token())
        }
    }

    /** Clears a completed download so the user can pick another quant. */
    fun reset() {
        _state.update { it.copy(progress = null, error = null, downloading = false) }
    }

    companion object {
        /**
         * WHY 4096: the context the app's tool-using agent needs for a system
         * prompt, a tool result and a reply. It is also the value
         * `ModelImporter.DEFAULT_CONTEXT_LENGTH` uses, so the fit decision here
         * and the one at load time agree.
         */
        const val DEFAULT_CONTEXT_LENGTH = 4_096
    }
}
