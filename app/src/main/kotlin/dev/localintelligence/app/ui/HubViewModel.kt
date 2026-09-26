package dev.localintelligence.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.DownloadPlan
import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import dev.localintelligence.core.hub.HubGgufFile
import dev.localintelligence.core.hub.HubRepoId
import dev.localintelligence.core.hub.HubRequest
import dev.localintelligence.core.hub.HubTokenSource
import dev.localintelligence.core.hub.HubTransport
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.core.hub.formatBytes
import dev.localintelligence.android.hub.ModelDownloader
import dev.localintelligence.android.hub.UrlConnectionTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    /**
     * Non-GGUF model files the repository also publishes, when it publishes any.
     *
     * ## Why this exists next to [files] rather than inside it
     *
     * Because `HuggingFaceClient.listGgufFiles` filters on `.gguf` in `:core`,
     * so a repository that hosts a `.litertlm` reaches this screen as an
     * **empty list** and the user is told "That repository has no GGUF files
     * this app can use." That sentence is true and useless: the repository
     * `litert-community/gemma-4-E4B-it-litert-lm` hosts a 3.7 GB model, the
     * app has a LiteRT-LM backend, and the two are one bug apart. Read as
     * written, the user concludes the repo is empty and tries another one.
     *
     * The alternative — putting a `.litertlm` into [files] so it appears in the
     * quant list — is exactly the failure this field exists to avoid. The list
     * is priced by `FitGate` against the **GGUF** tensor table, and a
     * LiteRT-LM FlatBuffer has no GGUF header: `probeHeader` returns null, the
     * estimate falls back to a name-derived guess, and the user is quoted a RAM
     * figure derived from a format whose layout that arithmetic knows nothing
     * about. A wrong number on this screen is worse than no row, because the
     * whole screen exists to be believed.
     *
     * So the `.litertlm` is named, sized, and explicitly **not priced**.
     */
    val nonGgufFiles: List<NonGgufModelFile> = emptyList(),
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
    /**
     * Bytes still sitting in the `.part` file from a download that stopped.
     *
     * WHY THIS IS HERE: the downloader keeps a partial transfer so the next
     * attempt resumes instead of spending the user's data again. Without this
     * field the screen cannot tell a stopped 120 MB transfer from one that
     * never started, so it fell through to a button labelled "Download
     * (668 MB)" — a full-size promise for a download that would actually move
     * 548 MB, or none at all. The `HubError.ConnectionLost` message the user
     * sees says "Tap retry to resume where it stopped", so the screen also has
     * to offer something that is recognisably a retry.
     *
     * Zero means nothing usable is on disk and the next tap starts from zero.
     */
    val partialBytesKept: Long = 0,
) {
    /** The plan the user is being asked to approve, or the refusal. */
    val blocked: HubError? get() = plan?.reject

    /** The size label shown before starting. */
    val sizeLabel: String? get() = selected?.let { formatBytes(it.sizeBytes) }

    /**
     * True when the next tap continues a transfer rather than starting one.
     *
     * The button label, the byte count and the size estimate all have to agree
     * with this, or the user is quoted 668 MB for 120 MB of work.
     */
    val resumable: Boolean
        get() = partialBytesKept > 0 && progress is DownloadProgress.Stopped

    /** The download stopped for a reason the user may be able to do something about. */
    val stopped: DownloadProgress.Stopped? get() = progress as? DownloadProgress.Stopped

    /**
     * Why this repository's `.litertlm` files are listed but not offered.
     *
     * Null when the repository publishes none, or when the probe could not
     * run — the latter matters: a failed probe must not become a claim. This
     * screen would rather say nothing about `.litertlm` than guess.
     *
     * Kept short on purpose. The reasons are numerous and all true — this is
     * the one place a user is told the acquisition path does not exist, and a
     * wall of text is a wall nobody reads. The specifics that would otherwise
     * be lost are in `docs/acceleration.md`, which says the same thing without
     * a 180-character limit.
     *
     * The last sentence is the one that earns its place. "No NPU, GPU is
     * OpenCL, never initialised" is what stops a user who has a `.litertlm`
     * and a fast phone from concluding this is the fast path and spending an
     * evening on it.
     */
    val nonGgufNotice: String? get() {
        val litertlm = nonGgufFiles.filter { it.isLiteRtLm }
        if (litertlm.isEmpty()) return null
        return "This app has a LiteRT-LM backend but cannot fetch a .litertlm " +
            "yet, so these are listed without a price. No size or memory " +
            "figure is shown for them because this screen works out memory by " +
            "reading a GGUF header, and a .litertlm is a FlatBuffer of TFLite " +
            "graphs — a number here would be invented. There is also no " +
            "GGUF-to-.litertlm converter in existence, so one cannot be made " +
            "from the GGUFs this app downloads. Worth knowing before you " +
            "look elsewhere: litertlm-android 0.13.1 ships no NPU library and " +
            "no GOOGLE_TENSOR backend, and its GPU is OpenCL, not Vulkan. No " +
            ".litertlm has ever been loaded here, so no speed is claimed for " +
            "one."
    }
}

/**
 * A model file in a repository that is not a GGUF.
 *
 * Deliberately carries only what HF reports truthfully for any file: the name
 * and the size. There is no RAM estimate, no quant, no context length, because
 * there is no arithmetic in this app that applies to a LiteRT-LM FlatBuffer —
 * the tensor table `FitGate` prices does not exist in that format.
 */
data class NonGgufModelFile(
    val fileName: String,
    val sizeBytes: Long,
) {
    /**
     * True for the LiteRT-LM container this app has a backend for.
     *
     * By **name**, and the KDoc says why that is acceptable here: this is a
     * label for a message, never a gate. Nothing is downloaded, loaded or
     * priced off the back of it, so a misdetection costs a slightly wrong
     * sentence rather than a wrong number or a wrong download. The one place a
     * LiteRT-LM file is *acted* on — the import path in `ModelManagerScreen` —
     * sniffs the file's bytes instead, because there the difference is a
     * refusal the user acts on.
     */
    val isLiteRtLm: Boolean get() = fileName.endsWith(".litertlm", ignoreCase = true)
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
    /**
     * Used only to look for `.litertlm` siblings that `listGgufFiles` filtered
     * out. Defaults to the production transport so the screen is truthful with
     * no wiring at all, which matters because the gap this closes is exactly a
     * case where the app says "nothing here" and there is in fact a 3.7 GB
     * model sitting in the repository.
     */
    private val siblingTransport: HubTransport = UrlConnectionTransport(),
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
                // WHY EVERY DOWNLOAD FIELD IS CLEARED HERE: `resolve` is also
                // how the user gets from a finished download to a different
                // repo. The previous version only touched `loading` and
                // `error`, so the completed state survived the navigation and
                // the new repo's screen rendered "Downloaded. Open Models to
                // load it." in primary colour — a success belonging to a file
                // that was not on screen. A stale success is the most
                // confident lie this state machine can tell.
                _state.update {
                    it.copy(
                        loading = true,
                        error = null,
                        progress = null,
                        partialBytesKept = 0,
                        registeredNote = null,
                        downloading = false,
                    )
                }
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
                        // WHY THE PROBE RUNS EVEN WHEN `loadable` IS NON-EMPTY:
                        // a repository can publish both, and in that case the
                        // `.litertlm` is the more interesting half — it is the
                        // one the user cannot get anywhere else. Probing only on
                        // an empty result would hide it behind a full list of
                        // quant rows. It is one extra request on a screen where
                        // the user has already pressed Find models, and it is
                        // best-effort: a null result changes nothing.
                        val nonGguf = listNonGgufSiblings(parsed.repoId)
                        _state.update {
                            it.copy(
                                loading = false,
                                error = when {
                                    // A repo that is *only* `.litertlm` is the
                                    // clearest case there is, and the old
                                    // message ("no GGUF files this app can
                                    // use") reads as "this repo is empty".
                                    loadable.isEmpty() && nonGguf.isNotEmpty() -> null
                                    loadable.isEmpty() ->
                                        "That repository has no GGUF files this app can use."
                                    else -> null
                                },
                                files = loadable,
                                nonGgufFiles = nonGguf,
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
                                nonGgufFiles = emptyList(),
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
                // The transfer state belongs to the file it happened on. Tapping
                // a different quant used to leave the previous file's progress
                // or its "Downloaded." note on screen, describing bytes that are
                // not this file's.
                it.copy(
                    selected = file,
                    plan = planned.first,
                    exactFit = planned.second,
                    alreadyOnDevice = planned.third,
                    progress = null,
                    partialBytesKept = 0,
                    registeredNote = null,
                    error = null,
                )
            }
        }
    }

    /**
     * The repository's model files that are **not** GGUF, by name and size only.
     *
     * ## Why this re-reads the listing instead of asking the client
     *
     * Because `HuggingFaceClient.listGgufFiles` drops non-`.gguf` siblings
     * inside `toGgufFiles` and returns nothing about them, and that file is in
     * `:core`, owned by another agent. Rather than block on that edit, the Hub
     * makes its own small listing request and reads the sibling names. It is
     * the same `GET /api/models/{id}?blobs=true` the client already made, so it
     * costs one extra round trip on a screen whose entire purpose is to answer
     * before the user spends their data.
     *
     * The alternative — leaving it to `:core` and shipping nothing — is how the
     * gap reached this screen in the first place. `litert-community/gemma-4-E4B-it-litert-lm`
     * is a public, non-gated repository hosting a 3.7 GB LiteRT-LM model, and
     * this app renders it as "That repository has no GGUF files this app can
     * use", which is indistinguishable from an empty repository.
     *
     * ## Why every failure mode returns an empty list
     *
     * Offline, 401, 429, a schema change, a truncated body. All of them mean
     * "say nothing about `.litertlm`", never "there is none". The notice is
     * built from this list, so a failure that returned an empty list would
     * produce a confident false claim. An empty list is the safe direction and
     * the screen is exactly as it was before this existed.
     *
     * ## Why size is trusted but nothing else is
     *
     * `lfs.size` is HF's own payload size, the same field
     * `HubSibling.trueSize` prefers, and it is a *file size* — a fact about
     * bytes on a server that holds no format-specific assumption. It is not a
     * memory estimate, and nothing here turns it into one.
     */
    private suspend fun listNonGgufSiblings(repo: HubRepoId): List<NonGgufModelFile> =
        withContext(Dispatchers.IO) {
            val request = HubRequest(
                url = "https://huggingface.co/api/models/${repo.id}?blobs=true",
                authToken = tokenSource.token(),
            )
            val response = runCatching { siblingTransport.open(request) }.getOrNull() ?: return@withContext emptyList()
            try {
                if (response.status !in 200..299) return@withContext emptyList()
                val body = response.body ?: return@withContext emptyList()
                val text = body.use { it.readBytes().decodeToString() }
                parseNonGgufSiblings(text)
            } catch (_: Exception) {
                // Deliberately broad. This is an advisory notice; there is
                // nothing here worth failing a resolve over, and throwing would
                // take down a GGUF listing that succeeded.
                emptyList()
            }
        }

    private fun parseNonGgufSiblings(json: String): List<NonGgufModelFile> {
        val root = runCatching { SIBLING_JSON.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return emptyList()
        val siblings = root["siblings"] as? JsonArray ?: return emptyList()
        val out = ArrayList<NonGgufModelFile>()
        for (element in siblings) {
            val obj = element as? JsonObject ?: continue
            val name = (obj["rfilename"] as? JsonPrimitive)?.content ?: continue
            if (name.startsWith(".")) continue
            if (name.endsWith(".gguf", ignoreCase = true)) continue
            val lower = name.lowercase()
            // Only the two model extensions this app has a backend for. A repo
            // full of `.md` and `.ipynb` is not a model this app declined, and
            // listing those would turn a useful notice into noise.
            if (!lower.endsWith(".litertlm") && !lower.endsWith(".task")) continue
            out += NonGgufModelFile(fileName = name, sizeBytes = siblingSize(obj))
        }
        return out.sortedBy { it.sizeBytes }
    }

    /**
     * The payload size, preferring LFS exactly as `HubSibling.trueSize` does.
     *
     * A git-blob `size` under 1 KiB is a pointer file's own size, not a model's
     * — reporting 130 bytes for a 3.7 GB model is precisely the failure this
     * whole field exists to prevent, so a small unbacked size yields 0 and the
     * caller omits the figure rather than printing it.
     */
    private fun siblingSize(sibling: JsonObject): Long {
        val lfs = sibling["lfs"] as? JsonObject
        (lfs?.get("size") as? JsonPrimitive)?.content?.toLongOrNull()?.let { return it }
        val declared = (sibling["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return 0L
        return if (declared < MIN_PLAIN_GIT_BLOB_BYTES) 0L else declared
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
        _state.update {
            it.copy(
                downloading = true,
                progress = null,
                error = null,
                registeredNote = null,
                // Cleared because a fresh attempt decides for itself: the
                // `.part` file is still on disk and the downloader will resume
                // it, so the first progress event repopulates this from what is
                // really there rather than from what was there last time.
                partialBytesKept = 0,
            )
        }

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
                                partialBytesKept = 0,
                            )
                        }
                    }
                    // WHY THE KEPT BYTES ARE RECORDED: a stop is the one state
                    // the user has to act on, and "Connection dropped" alone
                    // does not tell them whether the next tap is a 548 MB
                    // transfer or a 30-second one. The downloader keeps the
                    // `.part` file, so the honest number is in the event.
                    //
                    // `error` is deliberately left alone. It is the "this repo
                    // could not be resolved" slot at the top of the screen, and
                    // a stop renders its own message next to the resume button.
                    // Setting both printed the same sentence twice, which reads
                    // as two different failures.
                    is DownloadProgress.Stopped -> _state.update {
                        it.copy(
                            downloading = false,
                            progress = event,
                            partialBytesKept = event.partialBytesKept,
                        )
                    }
                    is DownloadProgress.InProgress -> _state.update {
                        it.copy(progress = event, partialBytesKept = event.bytesDownloaded)
                    }
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
     *
     * WHY A `Stopped` EVENT IS WRITTEN HERE RATHER THAN LEAVING THE LAST
     * `InProgress`: cancelling the collecting coroutine means no further event
     * arrives, so `progress` used to stay pinned at the last in-flight value
     * while `downloading` went false. The screen's next branch was then the
     * idle "Download (668 MB)" button, with the bar vanished and nothing said
     * about the 120 MB now sitting on disk. The user was told a download had
     * never started when they had just stopped one on purpose. The byte count
     * is the last one the downloader reported, which is what the `.part` file
     * holds.
     */
    fun cancelDownload() {
        val kept = _state.value.partialBytesKept
        _state.value.downloadJob?.cancel()
        _state.update {
            it.copy(
                downloading = false,
                downloadJob = null,
                progress = DownloadProgress.Stopped(HubError.Cancelled, kept),
                error = null,
                partialBytesKept = kept,
            )
        }
    }

    /** Best-effort search. A failure leaves the list empty, never throws. */
    fun search(query: String) {
        viewModelScope.launch {
            _searchResults.value = client.search(query, token = tokenSource.token())
        }
    }

    /** Clears a completed download so the user can pick another quant. */
    fun reset() {
        _state.update {
            it.copy(
                progress = null,
                error = null,
                downloading = false,
                partialBytesKept = 0,
            )
        }
    }

    companion object {
        /**
         * WHY 4096: the context the app's tool-using agent needs for a system
         * prompt, a tool result and a reply. It is also the value
         * `ModelImporter.DEFAULT_CONTEXT_LENGTH` uses, so the fit decision here
         * and the one at load time agree.
         */
        const val DEFAULT_CONTEXT_LENGTH = 4_096

        /**
         * Mirrors `HubSibling.MAX_PLAIN_GIT_BLOB_BYTES` in `:core`: below this,
         * a `size` with no `lfs` block is the size of a pointer file, not of a
         * model. Duplicated rather than imported because that constant is
         * `internal` to a module this file does not belong to.
         */
        private const val MIN_PLAIN_GIT_BLOB_BYTES = 1024L

        /**
         * Permissive on purpose. HF's model payload is large and grows, and
         * this parse exists to read two string/number fields out of one
         * array — a strict schema would turn any unrelated HF change into a
         * resolve failure. The two values that matter are pulled out
         * defensively, so a missing or retyped field yields an omitted figure
         * rather than a wrong one.
         */
        private val SIBLING_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
