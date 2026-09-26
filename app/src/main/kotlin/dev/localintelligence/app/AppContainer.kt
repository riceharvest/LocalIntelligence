package dev.localintelligence.app

import android.content.Context
import dev.localintelligence.android.data.LocalIntelligenceDatabase
import dev.localintelligence.android.data.resilientMemoryStore
import dev.localintelligence.android.inference.ImportedModel
import dev.localintelligence.android.inference.LlamaCppBackend
import dev.localintelligence.inference.litertlm.ModelBackendRouter
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.android.tools.androidTools
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.MemoryStore
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.ToolCallValidatorGate
import dev.localintelligence.android.inference.RamEstimate
import dev.localintelligence.app.ui.DownloadedModelRegistrar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.localintelligence.core.context.ContextBuilder
import dev.localintelligence.core.context.DefaultContextBuilder
import dev.localintelligence.core.metrics.RunRecorder
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.policy.RiskPolicy
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.HubTokenSource
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.android.hub.AndroidDeviceBudget
import dev.localintelligence.android.hub.KeystoreTokenStore
import dev.localintelligence.android.hub.ModelDownloader
import dev.localintelligence.android.hub.UrlConnectionTransport
import dev.localintelligence.app.ui.HubViewModel
import java.io.File
import dev.localintelligence.core.execution.RunGate

/**
 * The DI system. `docs/architecture.md` §3: *"If you need a dependency injected,
 * it is a constructor parameter. That is the whole DI system."*
 *
 * Every field is `by lazy`, which is not incidental laziness — it is the RAM
 * budget of §16 expressed as code. A 3B Q4_K_M costs ~2.4 GB of native memory
 * (§16 worked example) and Room builds a connection pool; a process that opens
 * either one because a screen happened to be composed pays for it whether or not
 * the user ever asks a question. The model backend in particular is the last
 * thing that should be constructed at process start.
 *
 * Constructed once by [LocalIntelligenceApp] and read from there. The service
 * and the ViewModels both receive it as a constructor parameter; neither reaches
 * for a global.
 */
class AppContainer(private val context: Context) {

    /**
     * The one tool registry for the process.
     *
     * This used to be `SimpleToolRegistry(emptyList())` with a comment saying
     * the tool workstreams had not landed. They had — all nine families ship in
     * `:android` — so the agent was handed an empty registry and could not
     * read a battery level, open a file, or look up a contact. Every end-to-end
     * task was unreachable no matter what the model produced.
     *
     * WHY the assembly lives in `:android` and not here: the tools are that
     * module's, and `:app` is Compose and nothing else
     * (`docs/architecture.md` §4). Listing nine families here would mean a
     * `:app` edit every time a tool lands.
     *
     * WHY it is safe to build eagerly-in-a-lazy: `androidTools` only
     * constructs tool objects. No platform call happens, no service is
     * resolved, and no model is touched — the RAM budget of §16 is untouched by
     * a few kilobytes of definition objects.
     */
    val tools: ToolRegistry by lazy { SimpleToolRegistry(androidTools(context)) }

    /**
     * The risk policy, shared by every controller this container builds.
     *
     * WHY one instance and not one per controller:
     * [dev.localintelligence.core.policy.BlastRadiusTracker] holds per-task
     * counters, and the cap the user cares about is "20 destructive actions in
     * one task". A policy per controller gives every run a fresh budget, so the
     * limit never binds. The controller calls `resetTask()` at the start of
     * every run, which is what scopes the budget to a task rather than to this
     * object.
     *
     * WHY `PolicyConfig.default`: it is the configuration where ignorance
     * denies — an empty `grantedPermissions` means a tool declaring an
     * ungranted permission returns `REQUIRE_PERMISSION` rather than executing,
     * and an empty `knownTargets` means an external send to anyone the user has
     * not vouched for is held for confirmation. The wiring here is what makes
     * that default real; changing the default is a product decision, not a
     * wiring one.
     */
    val riskPolicy: RiskPolicy by lazy { RiskPolicy() }

    /**
     * The memory store the agent runs on.
     *
     * WHY this is not `InMemoryMemoryStore()` any more: an in-RAM list is gone the
     * moment the process is, so every restart forgot everything the agent had learned —
     * the exact opposite of what a memory is for. The Room schema, entities, DAOs and a
     * working [dev.localintelligence.android.data.RoomMemoryStore] already existed in
     * `:android` and were never wired in here; the search path behind them was built on
     * FTS5, which platform SQLite does not have, so the database could not have been
     * wired in as it stood. See the KDoc on [MemoryQueries] for that.
     *
     * WHY a factory in `:android` rather than `database.memoryStore()` here: `:android`
     * declares Room as `implementation`, so from `:app` the `RoomDatabase` supertype of
     * `LocalIntelligenceDatabase` does not resolve and that call does not compile. The
     * assembly belongs in the module that owns Room regardless.
     *
     * WHY the indirection rather than the Room store directly: Room opens the file
     * lazily, on the first DAO call, not at `build()`. A direct wiring would move the
     * failure from "store is always in RAM" to "crash in the middle of an agent run" for
     * any device where the file cannot be opened. The returned store probes on first
     * use and falls back to `InMemoryMemoryStore` for the rest of the process, so the
     * worst case is exactly the behaviour this line used to have.
     *
     * WHY `by lazy`: the database must not be built just because something read this
     * property. Construction is deferred to the first memory operation, which keeps the
     * §16 RAM budget intact for a cold start that never runs a task.
     */
    val memoryStore: MemoryStore by lazy { resilientMemoryStore(context) }

    /**
     * The conversation, kept across runs.
     *
     * WHY THIS IS NOT `Session()` PER RUN: a controller is single-use by design -
     * it owns per-run mutable state and is thrown away afterwards. But the
     * conversation is not per-run. It used to be constructed inline as
     * `sessions = Session()` on every `newController()` call, which meant a
     * second message carried nothing at all: the model had no idea what had
     * already been said. The app then looked like it had no memory while
     * claiming to be an agent that remembers. `RoomSessionStore` has been fully
     * implemented this whole time and had zero callers.
     *
     * One instance per container, so both entry points - a chat turn and a
     * scheduled task - share the same thread of conversation, which is what a
     * user means by "the conversation".
     *
     * WHY NOT GUARDED: the container is constructed once and lives for the
     * process, and `ExecutionService` serialises runs on a single serviceScope,
     * so two runs cannot interleave writes. If that ever changes, this needs a
     * lock - noted here so the invariant is not lost.
     */
    val session: Session by lazy { Session() }

    /**
     * One run at a time, shared by the chat path and the scheduled path.
     *
     * WHY IT LIVES HERE AND NOT IN THE SERVICE: ExecutionService is created and
     * destroyed per run, so a gate held there would be a different object on
     * every run and would exclude nothing. The two entry points that can race -
     * a chat message and a scheduled alarm, both delivered as a start command to
     * the same live service - have to contend for the same instance, and the
     * container is what both of them already share.
     */
    val runGate: RunGate by lazy { RunGate() }

    val agentConfig: AgentConfig by lazy { AgentConfig() }

    /**
     * Prompt assembly is cheap and stateless, so one shared instance is safe and
     * correct. `DefaultContextBuilder` holds no state between calls by design
     * (see its own KDoc on heap discipline).
     */
    val contextBuilder: ContextBuilder by lazy {
        DefaultContextBuilder(workingLimit = agentConfig.workingTokenLimit)
    }

    /**
     * The inference backend. Constructed lazily, and the single biggest reason
     * this file exists: `LlamaCppBackend` is what pulls llama.cpp into the
     * process.
     */
    val modelBackend: ModelBackend by lazy { LlamaCppBackend(importer) }

    /**
     * Chooses the runtime from the model's own bytes.
     *
     * WHY THIS EXISTS: modelBackend above is hardcoded to llama.cpp, which made
     * LiteRT-LM unreachable - not selectable, not reachable, no code path a user
     * could take. The router picks by content rather than by a user toggle
     * because a GGUF and a .litertlm are two different FORMATS, not two ways to
     * run one model: a toggle would let someone pair llama.cpp with a .litertlm
     * and find out in native code. A file that is neither raises
     * BackendRoutingException, which is a real user outcome (a .tflite, a
     * half-finished download) and is named rather than guessed at.
     *
     * nativeLibraryDir is passed because a phone is not a JVM harness: without
     * it the LiteRT-LM backend falls back to a CPU-only probe and reports the
     * plausible-sounding "no device probe was supplied".
     */
    val backendRouter: ModelBackendRouter by lazy {
        ModelBackendRouter(
            importer = importer,
            modelsDir = modelsDir,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            cacheDir = context.cacheDir.absolutePath,
        )
    }

    /** The runtime that can actually load [model], decided from its file. */
    fun backendFor(model: ImportedModel): ModelBackend {
        val file = model.uri.path?.let { java.io.File(it) }
            ?: return modelBackend
        if (!file.isFile) return modelBackend
        return runCatching { backendRouter.route(file) }.getOrElse { modelBackend }
    }

    val importer: ModelImporter by lazy { ModelImporter(context.contentResolver) }

    /**
     * Durable stores. Opened lazily so a cold start that never runs a task never
     * creates the database file.
     *
     * Kept public because the session/history side of the schema is reachable through
     * it. Memory reaches the database through [memoryStore], which does not hand this
     * out directly — see there for why a failure to open must not be fatal.
     */
    val database: LocalIntelligenceDatabase by lazy { LocalIntelligenceDatabase.build(context) }

    /**
     * The state a run publishes into, shared between [ExecutionService] (writer)
     * and [dev.localintelligence.app.ui.ChatViewModel] (reader).
     *
     * This is the service/UI seam. It is a plain object holding three
     * [kotlinx.coroutines.flow.StateFlow]s — not an event bus, and deliberately
     * not a bound service: binding would add lifecycle state for no benefit when
     * a process-scoped state holder already gives the UI a hot, replaying stream
     * that survives configuration change for free.
     */
    val runSinks: RunSinks by lazy { RunSinks() }

    /**
     * Whether the backend can actually answer, published rather than inferred.
     *
     * The service sets this after a real [loadModel] attempt; the chat screen
     * reads it to decide whether it can offer a composer at all. Without it the
     * only signal reaching the user is the backend's `StopReason.ERROR` with an
     * empty body, which renders as the useless notice `model failed: `.
     */
    val modelAvailability: ModelAvailabilityHolder by lazy { ModelAvailabilityHolder() }

    /**
     * The model the agent should run, chosen by the user in the model manager.
     *
     * Null means "nothing imported", which is the state of a fresh install and
     * a legitimate thing for the UI to render — it is not an error, and it is
     * not a reason to crash on first send.
     */
    @Volatile
    var selectedModel: ImportedModel? = null

    /**
     * Loads [model] into the inference backend and records the outcome.
     *
     * Returns the resulting [ModelAvailability] instead of throwing, because
     * every caller wants the same thing: state to render. A load failure is an
     * expected outcome of picking the wrong file, not an exceptional one, and a
     * thrown exception here would reach the UI as a crash on a button press.
     *
     * Loading on the calling coroutine's dispatcher is deliberate: this runs
     * 2 GB of native allocation and must never sit on the main thread.
     */
    suspend fun loadModel(model: ImportedModel): ModelAvailability {
        return try {
        // Refuse before the native load, not after. A model that needs more RAM
        // than this device has is an OOM kill: on Android the process dies and
        // the user loses the conversation, with a system dialog that says nothing
        // about which model did it. The estimate is already on ImportedModel and
        // ModelManagerScreen renders it, so the only missing piece was the gate.
        // This is where a download that was judged too large at plan time still
        // gets caught, e.g. a model sideloaded onto a smaller device.
        if (!model.fitsOnDevice(ModelImporter.DEFAULT_CONTEXT_LENGTH)) {
            val needed = model.estimate.totalBytes(ModelImporter.DEFAULT_CONTEXT_LENGTH)
            val have = RamEstimate.usableDeviceBytes()
            return ModelAvailability.Failed(
                "This model needs about ${needed / (1024 * 1024)} MiB of RAM and " +
                    "this device has about ${have / (1024 * 1024)} MiB usable. " +
                    "Pick a smaller quant, or a shorter context.",
            ).also { modelAvailability.set(it) }
        }
        // Route per model, not once for the app: a .litertlm needs LiteRT-LM and
        // a GGUF needs llama.cpp, and loadModel is the only place that knows
        // which file it was handed.
        val backend = backendFor(model)
        backend.load(
            ModelSpec(
                // The backend opens this as a URI; see LlamaCppBackend.load.
                id = model.uri.toString(),
                displayName = model.displayName,
                sizeBytes = model.fileSizeBytes,
                parameterCount = model.parameterCount,
                quantType = model.quantType,
                supportedBackends = model.supportedBackends,
            ),
        )
        selectedModel = model
        ModelAvailability.Ready.also { modelAvailability.set(it) }
    } catch (e: CancellationException) {
        throw e
        } catch (t: Throwable) {
            ModelAvailability.Failed(describeLoadFailure(t))
                .also { modelAvailability.set(it) }
        }
    }

    /**
     * Ensures a model is resident, loading the selected one if needed.
     *
     * Idempotent, because [ExecutionService] calls it on every task and a
     * second 2 GB load for a task the user just sent would be both slow and a
     * reliable way to get OOM-killed.
     */
    suspend fun ensureModelReady(): ModelAvailability {
        if (modelAvailability.current.canRun) return ModelAvailability.Ready
        val model = selectedModel ?: return ModelAvailability.None
            .also { modelAvailability.set(it) }
        return loadModel(model)
    }

    /**
     * Builds the agent loop for one run.
     *
     * A function, not a singleton, and that is the point: a controller is
     * single-use. `cancel()` is sticky, and an `AwaitingConfirmation` has to be
     * resumed on the same instance that staged it. One call is one run.
     *
     * WHY `riskPolicy` is passed rather than left to the default: the argument
     * is [riskPolicy] so the per-task blast-radius budget is shared across runs
     * instead of resetting per controller. Left defaulted it would still gate
     * correctly — the parameter has a default — but the destructive-action cap
     * would never bind, because a fresh tracker starts at zero every time.
     *
     * WHY `metrics` is a parameter and not built here: a recorder is
     * [dev.localintelligence.core.metrics.RunRecorder], and who keeps the
     * resulting [dev.localintelligence.core.metrics.RunMetrics] is the caller's
     * decision. Wiring it is what makes the loop observable; storing it is not
     * the loop's business.
     */
    /**
     * Builds one single-use controller for one run.
     *
     * WHY [onToken] IS A PARAMETER: the sink belongs to the run that is starting
     * now, and a controller is thrown away after it. A field on the container
     * would be a second source of truth that a second run could overwrite while
     * the first is still decoding.
     */
    fun newController(
        model: ModelBackend = modelBackend,
        metrics: RunRecorder? = null,
        onToken: ((String) -> Unit)? = null,
    ): AgentController = AgentController(
        onToken = onToken,
        model = model,
        parser = dev.localintelligence.core.agent.ActionParserImpl,
        tools = tools,
        toolSelector = LexicalToolSelector(),
        validator = ToolCallValidatorGate.forRegistry(tools),
        loopDetector = LoopDetector(),
        contextBuilder = contextBuilder,
        memory = memoryStore,
        // The shared conversation, not a fresh one. See [session] for why this
        // used to silently discard everything the model had already been told.
        sessions = session,
        config = agentConfig,
        riskPolicy = riskPolicy,
        metrics = metrics,
    )

    // ---- HuggingFace download -----------------------------------------
    //
    // WHY lazy: the models directory is only touched when the user opens the
    // hub screen, so a cold start that goes straight to chat never creates it
    // and never opens a socket.

    /**
     * Where downloaded models live.
     *
     * WHY app-private (`filesDir`), not shared storage: a 2 GB model in a
     * user-visible directory is visible to every other app and survives an
     * uninstall, and this app has no use for either. `filesDir/models` is
     * removed with the app, which is the behaviour a user expects from a
     * 2 GB download they did not explicitly ask to keep.
     */
    val modelsDir: File by lazy { File(context.filesDir, "models").apply { mkdirs() } }

    val hubBudget: DeviceBudget by lazy { AndroidDeviceBudget(context, modelsDir) }

    /**
     * The optional read token. Null on a fresh install, and every ungated
     * download works without it — the gate is a capability, not a
     * requirement.
     */
    val hubTokenSource: HubTokenSource by lazy { KeystoreTokenStore(context) }

    val hubClient: HuggingFaceClient by lazy {
        HuggingFaceClient(UrlConnectionTransport(), tokenSource = hubTokenSource)
    }

    /**
     * WHY the client takes a token source rather than a token: the token is
     * read at request time, so a user who adds one in settings does not need
     * the client rebuilt and no cached request list goes stale.
     */
    val hubDownloader: ModelDownloader by lazy {
        ModelDownloader(
            client = hubClient,
            transport = UrlConnectionTransport(),
            modelsDir = modelsDir,
            tokenSource = hubTokenSource,
        )
    }

    /**
     * The hub ViewModel. One per screen instance, not a singleton: a screen
     * that is popped must not leave a download running behind it.
     */
    /**
     * The Hub screen's view model, wired to the app's own model list.
     *
     * WHY THE REGISTRAR IS SUPPLIED HERE AND NOT LEFT AT ITS DEFAULT: the
     * default is DownloadedModelRegistrar.NONE, so a finished download updated
     * the screen's state and stopped. The 668 MB file sat in files/models and
     * nothing knew it existed - the user had paid for it and the app could not
     * load it. Registering here means a download ends up in the same list, and
     * under the same RAM gate, as a model picked from Files.
     *
     * The registrar returns null on failure rather than throwing, and that null
     * is surfaced in the UI, so "the bytes are on disk but the header will not
     * parse" never renders as a successful download.
     */
    fun newHubViewModel(
        registrar: DownloadedModelRegistrar = DownloadedModelRegistrar.NONE,
    ): HubViewModel = HubViewModel(
        client = hubClient,
        downloader = hubDownloader,
        budget = hubBudget,
        tokenSource = hubTokenSource,
        registrar = registrar,
    )

    /**
     * Adopts a freshly downloaded file into the app's model list.
     *
     * This is the composition root's job because all three steps need the
     * container: read the header, add the model, and select it. Returning null
     * is a real outcome, not an error to swallow - it is how "the bytes landed
     * but the header will not parse" reaches the user instead of a download
     * that claims success and cannot be loaded.
     */
    suspend fun adoptDownloaded(file: java.io.File): String? = withContext(Dispatchers.IO) {
        val uri = android.net.Uri.fromFile(file)
        val model = runCatching { importer.inspect(uri) }.getOrNull() ?: return@withContext null
        loadModel(model)
        model.displayName
    }
}
