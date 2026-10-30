package dev.localintelligence.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.DownloadPlan
import dev.localintelligence.core.hub.DownloadProgress
import dev.localintelligence.core.hub.HubError
import dev.localintelligence.core.hub.HubFilePath
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
import kotlinx.coroutines.delay
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
     * Adopts [file] and returns what happened, or null when the file could not
     * be adopted (which the caller must surface — a downloaded model that is not
     * loadable is a failure, not a success).
     */
    suspend fun register(file: File): Registered?

    /**
     * A short line for the UI, and whether the chat can now use the file. A
     * document search model is adopted but is not a chat model.
     */
    data class Registered(val note: String, val chatReady: Boolean)

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
    /** The file the app picked as the best fit for this phone when the repository was listed. */
    val recommended: HubGgufFile? = null,
    /**
     * The repository's vision/audio projector (`mmproj`), downloaded with the
     * model so the model can see or hear. Null when the repository has none.
     */
    val projector: HubGgufFile? = null,
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
     * So the `.litertlm` is named and sized, with no price, and is downloaded
     * through [HubViewModel.startNonGgufDownload] rather than as a quant row.
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
    /** Whether that adoption left a model the chat can use ("Start chatting"). */
    val registeredReady: Boolean = false,
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
    val sizeLabel: String? get() = selected?.let { file ->
        formatBytes(file.sizeBytes) + (projector?.let { " + ${formatBytes(it.sizeBytes)} for photos" } ?: "")
    }

    /**
     * True when the next tap continues a transfer rather than starting one.
     *
     * The button label, the byte count and the size estimate all have to agree
     * with this, or the user is quoted 668 MB for 120 MB of work.
     */
    val resumable: Boolean
        get() = partialBytesKept > 0 && progress is DownloadProgress.Stopped

    /**
     * True when the finished download is loaded and the chat can use it. A failure, or
     * the document search model, keeps its note and gets no "Start chatting" button.
     */
    val ready: Boolean
        get() = registeredNote != null && registeredReady

    /** The download stopped for a reason the user may be able to do something about. */
    val stopped: DownloadProgress.Stopped? get() = progress as? DownloadProgress.Stopped

    /**
     * A note shown on the `.litertlm` card, or null when there are none.
     *
     * Kept short on purpose. The one sentence that earns its place says why no
     * memory figure is shown: the screen reads GGUF headers, and a `.litertlm`
     * has none, so any number here would be invented. The Models list and the
     * "no speed is claimed" caveat are stated where they apply: the notice after
     * a finished download and the ViewModel's `adopt`.
     */
    val nonGgufNotice: String? get() {
        if (nonGgufFiles.none { it.isLiteRtLm }) return null
        return "No memory figure is shown: this screen reads GGUF headers, and a " +
            ".litertlm has none. No .litertlm has been run in this app, so no " +
            "speed is claimed for one."
    }
}

/**
 * A model file in a repository that is not a GGUF.
 *
 * Carries what HF reports for any file (the path, the size and the LFS digest),
 * which is enough to download it through the same transfer as a GGUF. It has no
 * RAM estimate, no quant and no context length, because there is no arithmetic
 * in this app that applies to a LiteRT-LM FlatBuffer. The tensor table `FitGate`
 * prices does not exist in that format.
 */
data class NonGgufModelFile(
    val repo: HubRepoId,
    val path: HubFilePath,
    val sizeBytes: Long,
    /** HF's LFS payload digest, checked after the transfer. Null when unlisted. */
    val sha256: String?,
) {
    val fileName: String get() = path.fileName

    /**
     * True for the LiteRT-LM container this app has a backend for.
     *
     * By **name**, and the KDoc says why that is acceptable here: this decides
     * which label and which gate apply, never what gets downloaded. The one place
     * a LiteRT-LM file is *loaded* sniffs the file's bytes instead.
     */
    val isLiteRtLm: Boolean get() = fileName.endsWith(".litertlm", ignoreCase = true)

    /**
     * The Tensor generation the file was compiled for, read from its name.
     *
     * WHY the regex is repeated here: `TensorNpu` is `internal` to `:android`, and
     * this is the same pattern as its `compiledFor` (case-insensitive, first
     * match), so the label and the backend's refusal read the name the same way.
     */
    val npuLabel: String?
        get() = NPU_COMPILED_FOR.find(fileName.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.let { generation ->
            if (generation == 5) {
                "Runs on the Tensor G5 NPU (Pixel 10) only"
            } else {
                "Runs on the Tensor G$generation NPU only"
            }
        }

    private companion object {
        /** Same pattern as `TensorNpu.COMPILED_FOR`; see [npuLabel]. */
        val NPU_COMPILED_FOR = Regex("""_google_tensor_g(\d+)""")
    }

    /**
     * This file as the downloader's record. There is no quant and no shard, and
     * the local name is the same `owner__name__path` rule a GGUF uses.
     */
    fun toHubFile(): HubGgufFile = HubGgufFile(
        repo = repo,
        path = path,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        quant = null,
        shardIndex = null,
        shardCount = null,
    )
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
    /** The n_ctx a download is priced at: the same answer the model load will use. */
    private val contextLength: () -> Int = { DEFAULT_CONTEXT_LENGTH },
    /**
     * Offer the fastest quant that fits rather than the largest. Speed matters
     * more on a phone than a few bits per weight: Q4_0 is what llama.cpp's ARM
     * repack and KleidiAI kernels (and Adreno's OpenCL kernels) are built for.
     * MEASURED: Pixel 10 Pro prefill 134-151 vs 127-133 tok/s for Q4_K_M in
     * the app (llama-bench 254.6 vs 211.0), decode equal; Samsung A05s 11.1/7.4
     * vs 10.8/7.1. Decoding reads every weight per token, so a Q6_K is about a
     * quarter slower than a Q4_0 to write an answer.
     */
    private val preferFastQuant: Boolean = true,
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
    private var searchJob: Job? = null

    /** True while a search is out, so the screen can say "Searching…". */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** "No models found…" or "Couldn't reach Hugging Face…"; null when the list speaks for itself. */
    private val _searchNote = MutableStateFlow<String?>(null)
    val searchNote: StateFlow<String?> = _searchNote.asStateFlow()

    /** True when [searchResults] is the popular list shown before anything is typed. */
    private val _showingPopular = MutableStateFlow(false)
    val showingPopular: StateFlow<Boolean> = _showingPopular.asStateFlow()

    init {
        // Something to choose from before anything is typed.
        runSearch("", delayMs = 0)
    }

    /**
     * Typing searches Hugging Face as you go, so a partial name ("qwen3 0.6")
     * lists the GGUF repos that match. The wait before asking keeps a fast typist
     * from sending a request per key, and each new key cancels the last request.
     * An empty field shows the most downloaded models again.
     */
    fun onRepoInputChanged(value: String) {
        // Typing a new search closes the repository on screen (unless a download
        // from it is running), so the new matches show instead of its files.
        _state.update {
            if (it.files.isNotEmpty() && !it.downloading) {
                it.copy(
                    repoInput = value, files = emptyList(), nonGgufFiles = emptyList(),
                    selected = null, recommended = null, plan = null, error = null,
                )
            } else {
                it.copy(repoInput = value)
            }
        }
        val query = value.trim()
        when {
            query.isEmpty() -> runSearch("", delayMs = 0)
            query.length < MIN_SEARCH_CHARS -> {
                searchJob?.cancel()
                _searching.value = false
            }
            else -> runSearch(query, delayMs = SEARCH_DELAY_MS)
        }
    }

    /**
     * The search key or the Search button. A full owner/name is listed as typed.
     * Anything else is searched now, without waiting for the typing delay: one
     * match opens, several are listed to choose from. It used to treat a partial
     * name pressed before its results arrived as a repository id, show "must be
     * owner/name", and cancel the search that was about to answer.
     */
    fun submit() {
        val input = _state.value.repoInput.trim()
        if (HubRepoId.parse(input) is HubRepoId.Result.Valid) {
            resolve()
            return
        }
        if (input.isEmpty()) return
        runSearch(input, delayMs = 0, openSingle = true)
    }

    private fun runSearch(query: String, delayMs: Long, openSingle: Boolean = false) {
        searchJob?.cancel()
        if (query.isEmpty()) {
            // Before typing: the models that run well on a phone, not the hub's
            // most downloaded overall (those are 9B-35B, or audio and music models).
            _searchResults.value = PHONE_MODELS
            _showingPopular.value = true
            _searchNote.value = null
            _searching.value = false
            return
        }
        _searching.value = true
        searchJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            var tokenRejected = false
            val hits = withContext(Dispatchers.IO) {
                try {
                    client.browse(query, limit = if (query.isEmpty()) POPULAR_LIMIT else SEARCH_LIMIT, token = tokenSource.token())
                } catch (_: HubError.Unauthorized) {
                    // A rejected token is not a dropped connection: say so, so the
                    // user checks the token in Settings.
                    tokenRejected = true
                    null
                }
            }
            _searching.value = false
            _searchResults.value = hits.orEmpty()
            _showingPopular.value = false
            _searchNote.value = when {
                tokenRejected -> "Hugging Face rejected the access token. Check it in Settings."
                hits == null -> "Couldn't reach Hugging Face. Check the connection and try again."
                hits.isEmpty() && query.isNotEmpty() -> "No models found for \u201c$query\u201d."
                else -> null
            }
            if (openSingle && hits?.size == 1) pickSearchHit(hits.single())
        }
    }

    /** A suggestion was tapped: it becomes the input and its files are listed. */
    fun pickSearchHit(hit: HuggingFaceClient.HubSearchHitPublic) {
        _state.update { it.copy(repoInput = hit.repoId.id) }
        resolve()
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
        // The suggestions are for finding a repo; once one is being listed they go.
        searchJob?.cancel()
        _searching.value = false
        _searchNote.value = null
        _searchResults.value = emptyList()
        val raw = _state.value.repoInput
        when (val parsed = HubRepoId.parse(raw)) {
            is HubRepoId.Result.Invalid -> {
                pendingPrefill = null
                _state.update { it.copy(error = parsed.message, files = emptyList(), selected = null, recommended = null, plan = null) }
                return
            }
            is HubRepoId.Result.Valid -> {
                // Taken here, once, so a prefill applies to the listing it asked
                // for and never to a later resolve the user types by hand.
                val wantedFile = pendingPrefill
                pendingPrefill = null
                // WHY EVERY DOWNLOAD FIELD IS CLEARED HERE: `resolve` is also
                // how the user gets from a finished download to a different
                // repo. The previous version only touched `loading` and
                // `error`, so the completed state survived the navigation and
                // the new repo's screen rendered "Downloaded. Open Models to
                // load it." in primary colour — a success belonging to a file
                // that was not on screen. A stale success is the most
                // confident lie this state machine can tell.
                _state.value.downloadJob?.cancel()
                _state.update {
                    it.copy(
                        loading = true,
                        error = null,
                        progress = null,
                        partialBytesKept = 0,
                        registeredNote = null,
                        registeredReady = false,
                        downloading = false,
                        downloadJob = null,
                    )
                }
                viewModelScope.launch {
                    // On IO: the transport is a blocking HttpURLConnection, and Android
                    // refuses network calls on the main thread.
                    val result = runCatching {
                        withContext(Dispatchers.IO) { client.listGgufFiles(parsed.repoId, tokenSource.token()) }
                    }
                    result.onSuccess { files ->
                        val loadable = client.loadableFiles(files)
                        val guess = client.chooseBestFitting(
                            candidates = loadable,
                            contextLength = contextLength(),
                            budget = budget,
                        )
                        // The guess prices from the file size alone. When it says
                        // nothing fits, the smallest file is still selected and its
                        // header read: Gemma 4 E2B (3.3 GB on disk, 1.8 GB resident
                        // once its lookup table is known to stay on disk) was shown
                        // as unrecommended on a 3.6 GB phone it fits.
                        val pick = preferQ4(loadable, guess) ?: loadable.firstOrNull()
                        val planned = pick?.let { planFor(it) }
                        val best = (if (guess != null) pick else null) ?: pick?.takeIf { planned?.first?.isAllowed == true }
                        // WHY THE PROBE RUNS EVEN WHEN `loadable` IS NON-EMPTY:
                        // a repository can publish both, and in that case the
                        // `.litertlm` is the more interesting half — it is the
                        // one the user cannot get anywhere else. Probing only on
                        // an empty result would hide it behind a full list of
                        // quant rows. It is one extra request on a screen where
                        // the user has already pressed List files, and it is
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
                                projector = chooseProjector(files),
                                selected = pick,
                                recommended = best,
                                plan = planned?.first,
                                exactFit = planned?.second == true,
                                alreadyOnDevice = planned?.third == true,
                            )
                        }
                        // A suggested file that the repository does not list is
                        // simply not selected: the user sees the repo's files.
                        wantedFile?.let { name ->
                            loadable.firstOrNull { it.fileName == name }?.let { select(it) }
                        }
                    }.onFailure { e ->
                        _state.update {
                            it.copy(
                                loading = false,
                                error = (e as? HubError)?.message ?: "Could not reach HuggingFace.",
                                files = emptyList(),
                                nonGgufFiles = emptyList(),
                                selected = null,
                                recommended = null,
                                plan = null,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The file name a Settings suggestion asked for, to select once its
     * repository is listed. Set by [prefill], consumed by the next [resolve].
     */
    private var pendingPrefill: String? = null

    /**
     * Opens [repo] as if the user had typed it and pressed List files, then
     * selects [fileName] from the listing instead of the largest fitting quant.
     *
     * WHY a one-shot field and not a parameter on [resolve]: resolve is also
     * what the user taps by hand, and only the Settings suggestion should
     * preselect a file.
     */
    fun prefill(repo: String, fileName: String) {
        pendingPrefill = fileName
        _state.update { it.copy(repoInput = repo) }
        resolve()
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
                    registeredReady = false,
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
                val text = body.readBytes().decodeToString()
                parseNonGgufSiblings(repo, text)
            } catch (_: Exception) {
                // Deliberately broad. This is an advisory notice; there is
                // nothing here worth failing a resolve over, and throwing would
                // take down a GGUF listing that succeeded.
                emptyList()
            } finally {
                runCatching { response.body?.close() }
            }
        }

    private fun parseNonGgufSiblings(repo: HubRepoId, json: String): List<NonGgufModelFile> {
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
            val path = (HubFilePath.parse(name) as? HubFilePath.Result.Valid)?.filePath ?: continue
            out += NonGgufModelFile(
                repo = repo,
                path = path,
                sizeBytes = siblingSize(obj),
                sha256 = siblingSha256(obj),
            )
        }
        return out.sortedBy { it.sizeBytes }
    }

    /**
     * HF's LFS payload digest, as the downloader's SHA check reads it.
     *
     * Read from the `lfs` block only: a git-blob sibling has no digest of the
     * payload, and a wrong digest would reject a correct file. A missing one
     * skips the check, exactly as it does for a GGUF.
     */
    private fun siblingSha256(sibling: JsonObject): String? =
        ((sibling["lfs"] as? JsonObject)?.get("sha256") as? JsonPrimitive)
            ?.content
            ?.takeIf { it.isNotBlank() }

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
    private suspend fun planFor(file: HubGgufFile): Triple<DownloadPlan, Boolean, Boolean> =
        // All of it on IO: the header probe is a network request, and on the main
        // thread Android refuses it with NetworkOnMainThreadException. Picking any
        // repository crashed the app this way (seen on the Samsung A05s).
        withContext(Dispatchers.IO) {
            val header = client.probeHeader(file, tokenSource.token())
            val alreadyOnDisk = downloader.partialFileFor(file).localBytes()
            val onDevice = downloader.isDownloaded(file)
            val plan = client.plan(
                file = file,
                contextLength = contextLength(),
                budget = budget,
                alreadyOnDiskBytes = alreadyOnDisk,
                header = header,
            )
            Triple(plan, header != null, onDevice)
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
            viewModelScope.launch {
                current.projector?.let { fetchProjector(it) }
                adopt(file)
            }
            return
        }

        current.downloadJob?.cancel()
        _state.update {
            it.copy(
                downloading = true,
                progress = null,
                error = null,
                registeredNote = null,
                registeredReady = false,
                // Cleared because a fresh attempt decides for itself: the
                // `.part` file is still on disk and the downloader will resume
                // it, so the first progress event repopulates this from what is
                // really there rather than from what was there last time.
                partialBytesKept = 0,
            )
        }

        val job = viewModelScope.launch {
            // The projector first, so it is next to the model when the model is
            // adopted and loaded (the backend looks for it at load).
            val projector = current.projector
            if (projector != null && !fetchProjector(projector)) return@launch
            collectDownload(file, plan)
        }
        _state.update { it.copy(downloadJob = job) }
    }

    /**
     * The fastest file to offer instead of [guess] (the largest that fits),
     * never larger than it: Q4_0 (repacked for the ARM and Adreno kernels),
     * then IQ4_NL (also repacked), Q4_K_S, Q4_K_M. [guess] when none of those
     * is in the repository. IQ2/IQ3 files are smaller but slow to unpack on a
     * phone CPU, so they are never preferred over a bigger Q4.
     */
    private fun preferQ4(loadable: List<HubGgufFile>, guess: HubGgufFile?): HubGgufFile? {
        if (!preferFastQuant || guess == null) return guess
        val fitting = loadable.filter { it.sizeBytes <= guess.sizeBytes }
        for (label in FAST_QUANTS) {
            fitting.filter { it.quant?.label == label }.maxByOrNull { it.sizeBytes }?.let { return it }
        }
        return guess
    }

    /** The repository's projector to download with a model: Q8_0, else F16, else the smallest. */
    private fun chooseProjector(files: List<HubGgufFile>): HubGgufFile? {
        val projectors = files.filter { it.isProjector && !it.isSharded }
        if (projectors.isEmpty()) return null
        return projectors.firstOrNull { it.fileName.contains("q8_0", ignoreCase = true) }
            ?: projectors.firstOrNull { it.fileName.contains("f16", ignoreCase = true) }
            ?: projectors.minByOrNull { it.sizeBytes }
    }

    /**
     * Downloads [projector] unless it is already on the phone, showing its
     * progress. Returns false when the user stopped it or it failed, so the
     * model download does not start behind a stop the user is looking at. A
     * projector that cannot be planned (no disk) is skipped: the model still
     * works without it, only not with photos.
     */
    private suspend fun fetchProjector(projector: HubGgufFile): Boolean {
        val plan = withContext(Dispatchers.IO) {
            if (downloader.isDownloaded(projector)) return@withContext null
            client.plan(
                file = projector,
                contextLength = contextLength(),
                budget = budget,
                alreadyOnDiskBytes = downloader.partialFileFor(projector).localBytes(),
                ramGate = false,
            )
        } ?: return true
        if (!plan.isAllowed) return true
        var done = false
        downloader.download(projector, plan).collect { event ->
            when (event) {
                is DownloadProgress.Done -> done = true
                is DownloadProgress.Stopped -> _state.update {
                    it.copy(downloading = false, progress = event, partialBytesKept = event.partialBytesKept)
                }
                is DownloadProgress.InProgress -> _state.update {
                    it.copy(progress = event, partialBytesKept = event.bytesDownloaded)
                }
            }
        }
        return done
    }

    /**
     * Starts the download of one `.litertlm` listed in [HubUiState.nonGgufFiles].
     *
     * WHY this is not a quant selection: a LiteRT-LM file has no GGUF header, so
     * the selection path would price it with the GGUF RAM gate and show a figure
     * derived from a format that gate does not understand. Instead the RAM gate is
     * skipped ([HuggingFaceClient.plan] with `ramGate = false`) and the disk gate
     * still runs. The transfer, resume, size check, SHA check and gated-repo token
     * are the GGUF ones, unchanged, so a `.litertlm` is never a second, weaker
     * download path.
     *
     * A file with no declared size is refused. The downloader only checks the
     * size when it is known, so an unknown size would leave a truncated transfer
     * unchecked.
     */
    fun startNonGgufDownload(file: NonGgufModelFile) {
        if (file.sizeBytes <= 0L) return
        val hubFile = file.toHubFile()
        _state.value.downloadJob?.cancel()
        _state.update {
            it.copy(
                selected = null,
                plan = null,
                exactFit = false,
                alreadyOnDevice = false,
                downloading = true,
                progress = null,
                error = null,
                registeredNote = null,
                registeredReady = false,
                partialBytesKept = 0,
            )
        }
        val job = viewModelScope.launch {
            val (kept, plan, onDisk) = withContext(Dispatchers.IO) {
                val kept = downloader.partialFileFor(hubFile).localBytes()
                Triple(
                    kept,
                    client.plan(
                        file = hubFile,
                        budget = budget,
                        alreadyOnDiskBytes = kept,
                        ramGate = false,
                    ),
                    downloader.isDownloaded(hubFile),
                )
            }
            plan.reject?.let { reject ->
                _state.update { it.copy(downloading = false, downloadJob = null, error = reject.message) }
                return@launch
            }
            if (onDisk) {
                // Already complete and checksum-verified. Adopt it instead of
                // transferring the same bytes again, as the GGUF path does.
                val note = adopt(hubFile)
                _state.update {
                    it.copy(
                        downloading = false,
                        downloadJob = null,
                        progress = DownloadProgress.Done(
                            hubFile,
                            downloader.localFileFor(hubFile).absolutePath,
                        ),
                        registeredNote = note.note,
                        registeredReady = note.chatReady,
                        alreadyOnDevice = true,
                        partialBytesKept = 0,
                    )
                }
                return@launch
            }
            // The kept byte count is recorded so a resumed transfer shows the
            // right starting point, as it does for a GGUF.
            _state.update { it.copy(partialBytesKept = kept) }
            collectDownload(hubFile, plan)
        }
        _state.update { it.copy(downloadJob = job) }
    }

    /**
     * Runs one transfer and writes each event into [HubUiState]. Shared by the
     * GGUF and `.litertlm` entry points so both show the same progress, stop and
     * completion states.
     */
    private suspend fun collectDownload(file: HubGgufFile, plan: DownloadPlan) {
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
                            registeredNote = note.note,
                            registeredReady = note.chatReady,
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

    /**
     * Hands the downloaded file to the app and reports what happened.
     *
     * WHY a failure here is an error and not a shrug: a GGUF that is on disk but
     * not adopted cannot be loaded, cannot be selected and does not appear in the
     * model list. Telling the user "downloaded!" in that state is a lie with a
     * 668 MB price tag, so the failure is surfaced and the path is named.
     */
    private suspend fun adopt(file: HubGgufFile): DownloadedModelRegistrar.Registered {
        // Same path for both formats: the registrar is the one place that decides
        // whether a file can be registered, so this does not try to pre-judge it.
        val local = downloader.localFileFor(file)
        if (!local.isFile) {
            return DownloadedModelRegistrar.Registered(
                "Downloaded, but the file is not where the app looks for models.",
                chatReady = false,
            )
        }
        runCatching { registrar.register(local) }.getOrNull()?.let { return it }
        // WHY a LiteRT-LM file gets its own line: a null from the registrar means
        // the file was saved but did not load, so "Open Models to load it" would
        // point the user at a file that is not usable there.
        // The file is listed in Models even so (adoptDownloaded adds it before loading).
        return DownloadedModelRegistrar.Registered(COULD_NOT_START, chatReady = false)
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
        /** What the Hub says when a download is on disk and listed but did not load. */
        const val COULD_NOT_START = "Downloaded, but it couldn't start. Try a smaller version."

        /**
         * Only the fallback when no resolver is wired: 4096, the context the app's
         * tool-using agent needs for a system prompt, a tool result and a reply. The app
         * passes `AppContainer.contextLengthFor`'s answer instead.
         */
        const val DEFAULT_CONTEXT_LENGTH = 4_096

        /** Two letters before the first search: one letter matches most of the hub. */
        const val MIN_SEARCH_CHARS = 2
        const val SEARCH_DELAY_MS = 300L
        const val SEARCH_LIMIT = 8

        /** Models listed before anything is typed. */
        const val POPULAR_LIMIT = 10

        /** Quants in the order a phone runs them fastest; see preferFastQuant. */
        private val FAST_QUANTS = listOf("Q4_0", "IQ4_NL", "Q4_K_S", "Q4_K_M")

        /**
         * Listed before anything is typed: phone-sized models this app is tested
         * with (docs/roadmap.md, "Model test plan"), the recommended one first.
         */
        private val PHONE_MODELS: List<HuggingFaceClient.HubSearchHitPublic> = listOf(
            "LiquidAI/LFM3-1.7B-GGUF",
            "google/gemma-4-E2B-it-qat-q4_0-gguf",
            "unsloth/Qwen3.5-2B-GGUF",
            "openbmb/MiniCPM5-2B-GGUF",
            "XHToken/Spark-X2.6-1.7B-GGUF",
            "XHToken/Spark-X2.6-4B-GGUF",
            "ibm-granite/granite-4.2-3b-GGUF",
            "LiquidAI/LFM3-VL-2B-GGUF",
            "LiquidAI/LFM3-Omni-2B-GGUF",
            "LiquidAI/LFM2.5-230M-GGUF",
        ).mapNotNull { id ->
            (HubRepoId.parse(id) as? HubRepoId.Result.Valid)?.let {
                HuggingFaceClient.HubSearchHitPublic(it.repoId, downloads = 0, likes = 0, pipelineTag = null)
            }
        }

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
