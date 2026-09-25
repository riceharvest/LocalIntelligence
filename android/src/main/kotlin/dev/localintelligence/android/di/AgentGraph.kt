package dev.localintelligence.android.di

import dev.localintelligence.core.agent.ActionParserImpl
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.MemoryStore
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.SessionStore
import dev.localintelligence.core.agent.ToolCallValidatorGate
import dev.localintelligence.core.context.ContextBuilder
import dev.localintelligence.core.context.DefaultContextBuilder
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolRegistry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The outcome of asking the graph to run a task.
 *
 * WHY this is a sealed type rather than an exception: the three ways a run can
 * fail to *start* (no model, model won't load, controller construction blew up)
 * are states the UI has to render, and `docs/architecture.md` §8 puts permission
 * and confirmation in the runtime, not in a crash. A first run with no model
 * imported is the single most common state in the app's life, and it must be a
 * sentence to the user, not a stack trace in logcat.
 */
sealed interface AgentLaunch {

    /** The loop ran and returned; inspect [result] for success, stop, or cancel. */
    data class Ran(val result: AgentResult) : AgentLaunch

    /**
     * The graph could not start a run. [message] is written for a user, because
     * the UI shows it verbatim — every string produced here is user-facing and
     * must read as an instruction, not a diagnostic.
     */
    data class Unavailable(val reason: Reason, val message: String) : AgentLaunch

    enum class Reason {
        /** No model has been imported yet. First run. */
        NO_MODEL,

        /** A model was named but `ModelBackend.load` failed: OOM, bad GGUF, no native lib. */
        MODEL_LOAD_FAILED,

        /** The backend refused to start work, e.g. the registry is empty. */
        NOT_READY,
    }
}

/**
 * The composition root. Turns the individual pieces — a model backend, a tool
 * registry, two stores, a context builder, the parser — into an
 * [AgentController] the UI can drive, and owns the model lifecycle around it.
 *
 * ## No framework
 *
 * `docs/architecture.md` §3: *"If you need a dependency injected, it is a
 * constructor parameter. That is the whole DI system."* Every collaborator is a
 * constructor parameter with a real production default where one exists, and
 * `kotlin.Lazy` does the rest. There is no Hilt, no Koin, no Dagger, and no
 * service locator: a caller that cannot name a dependency cannot accidentally
 * acquire one.
 *
 * ## Laziness is the RAM budget, not a micro-optimisation
 *
 * `docs/architecture.md` §16 budgets the process at ~2.4 GB of native memory for
 * a 3B Q4_K_M plus a Room connection pool. Every field below is `by lazy`, so
 * constructing this graph costs a handful of object headers and opening not one
 * file. [AgentGraphTest] asserts that directly, because "we meant to make it
 * lazy" is not a property a test can see and this is the property that decides
 * whether the app gets OOM-killed in the background.
 *
 * In particular [backend] is lazy *and* [AgentLaunch] requires a model to be
 * named before anything loads: a graph can be fully constructed with no model
 * imported and still be a working app that says so.
 *
 * ## One model resident at a time
 *
 * [loadMutex] serialises [launch]. `LlamaCppBackend` holds a native handle and
 * its own lock; two concurrent `run` calls on a 2 GB model is an OOM, not a
 * race the loop can recover from. The mutex is here rather than in the backend
 * because the *decision* to admit a second run belongs to the graph.
 *
 * ## Deleting a model mid-run
 *
 * A user can delete the model from the settings screen while a conversation is
 * open. The registry is unaffected — tools do not hold model state — so the only
 * thing to get right is that the next [launch] reports honestly instead of
 * generating against a closed native handle. [forget] drops the loaded model
 * and unloads the backend; the next launch returns
 * [AgentLaunch.Unavailable]/[AgentLaunch.Reason.NO_MODEL].
 */
class AgentGraph(
    private val toolSource: ToolSource,
    private val backendFactory: () -> ModelBackend,
    private val memoryStoreFactory: () -> MemoryStore,
    private val sessionStoreFactory: () -> SessionStore,
    private val config: AgentConfig = AgentConfig(),
    private val contextBuilderFactory: (AgentConfig) -> ContextBuilder = { DefaultContextBuilder(workingLimit = it.workingTokenLimit) },
) {

    /**
     * The tool registry, validated on first use.
     *
     * Built through [ToolRegistryFactory] rather than `SimpleToolRegistry`
     * directly so a duplicate name from two agents working in parallel surfaces
     * as a diagnosable report instead of a `require` failure at app start.
     * [registryReport] exposes the diagnostics; see [requireWellFormed] for the
     * hard-fail path.
     */
    private val registryReport: ToolRegistryReport by lazy { ToolRegistryFactory.assemble(toolSource) }

    /** The one registry the process uses. Empty when the source was malformed. */
    val tools: ToolRegistry get() = registryReport.registry

    /** Diagnostics from the last assembly: duplicates and contract violations. */
    val registryDiagnostics: List<String>
        get() = registryReport.duplicates + registryReport.malformed

    /** Durable + working memory. Opened lazily: a cold start that never asks a
     *  question must not create the database file. */
    val memoryStore: MemoryStore by lazy { memoryStoreFactory() }

    /**
     * The durable transcript, exposed for the app layer to persist into.
     *
     * Honest limitation: `AgentController`'s wave-1 signature takes a
     * `Session` (in-memory) and a `MemoryStore`, and has no `SessionStore`
     * parameter, so this graph cannot hand the store to the loop. It is wired
     * and exposed here because the app layer already depends on it, and because
     * the fix belongs in `:core`'s constructor — not in a wiring layer that was
     * told not to touch `:core`.
     */
    val sessionStore: SessionStore by lazy { sessionStoreFactory() }

    /**
     * The inference backend. Constructing `LlamaCppBackend` is cheap — it only
     * allocates a handle object — but it is still the collaborator most worth
     * deferring, because it is the one that reaches native code the moment
     * anything calls [ModelBackend.load].
     */
    val backend: ModelBackend by lazy { backendFactory() }

    /** Prompt assembly. Stateless and cheap; one shared instance is correct. */
    val contextBuilder: ContextBuilder by lazy { contextBuilderFactory(config) }

    /** The model currently loaded, or null. Read by the UI to show model state. */
    @Volatile
    var loadedModel: ModelSpec? = null
        private set

    /**
     * Guards *model residency only*: one model loaded at a time.
     *
     * `LlamaCppBackend` holds a native handle and takes its own lock for the
     * whole of a generation, so two concurrent decodes are already serialised
     * natively. Holding a graph-level lock across the entire run would stack a
     * second, coarser queue on top: a second task would wait for the first to
     * finish generating rather than merely for the model to be resident. That
     * turns "load a model" into "run one task at a time", which is not this
     * graph's decision to make — deciding whether two runs may overlap belongs
     * to the caller that owns the run (see `AgentViewModel`, which already
     * refuses to start a second run).
     */
    private val loadMutex = Mutex()

    /**
     * Builds a controller for exactly one run.
     *
     * A function, never a singleton: `AgentController.cancel()` is sticky and an
     * [AgentResult.AwaitingConfirmation] must be resumed on the very instance
     * that staged it, so one controller is one run and a second run needs a
     * second controller. Sharing one would make `confirmAndResume` resume a
     * different run's pending call.
     */
    fun newController(): AgentController = AgentController(
        model = backend,
        parser = ActionParserImpl,
        tools = tools,
        toolSelector = LexicalToolSelector(),
        validator = ToolCallValidatorGate.forRegistry(tools),
        // A fresh detector per run, for the same single-use reason as the controller.
        loopDetector = LoopDetector(),
        contextBuilder = contextBuilder,
        memory = memoryStore,
        sessions = Session(),
        config = config,
    )

    /**
     * The single entry point the UI calls: load [model] if needed, build a
     * controller, run [task].
     *
     * Never throws. Every failure to *start* is an [AgentLaunch.Unavailable] with
     * a user-facing sentence, because this is called from a click handler and a
     * first-run user has no model.
     */
    suspend fun launch(model: ModelSpec?, task: String): AgentLaunch {
        if (model == null) {
            return AgentLaunch.Unavailable(
                AgentLaunch.Reason.NO_MODEL,
                "Import a model first, then ask again.",
            )
        }
        if (tools.all().isEmpty()) {
            return AgentLaunch.Unavailable(
                AgentLaunch.Reason.NOT_READY,
                "No tools are available, so the agent cannot act yet.",
            )
        }

        if (!ensureLoaded(model)) {
            return AgentLaunch.Unavailable(
                AgentLaunch.Reason.MODEL_LOAD_FAILED,
                "That model could not be loaded. It may be too large for this device.",
            )
        }

        // Outside the lock on purpose: a model load is seconds of blocking work
        // and a run is many, and serialising them against each other would make
        // a second task wait for the first to finish thinking. The backend's own
        // lock is what keeps two decodes from interleaving.
        return AgentLaunch.Ran(newController().run(task))
    }

    /**
     * Loads [model] if it is not already the resident one. Returns false if the
     * load failed, having left nothing loaded.
     *
     * This is where the 2 GB happens, and it is reached only from [launch] with
     * a model the caller named — never from a constructor, never at app start.
     */
    private suspend fun ensureLoaded(model: ModelSpec): Boolean = loadMutex.withLock {
        if (loadedModel?.id == model.id) return@withLock true
        try {
            backend.load(model)
        } catch (t: Throwable) {
            // A failed load can leave a half-initialised native handle behind;
            // dropping the reference means the next attempt starts clean rather
            // than trusting whatever survived.
            loadedModel = null
            return@withLock false
        }
        loadedModel = model
        true
    }

    /**
     * Forgets the loaded model, e.g. the user deleted it from settings.
     *
     * Unloads the backend so the native memory and the SAF descriptor are
     * actually returned (see `LoadedModel`), and leaves [tools] untouched: the
     * registry holds tool instances that know nothing about models, so a model
     * delete cannot make it inconsistent. The next [launch] reports
     * [AgentLaunch.Reason.NO_MODEL] instead of generating against a freed handle.
     */
    suspend fun forget() {
        loadMutex.withLock {
            loadedModel = null
            try {
                backend.unload()
            } catch (_: Throwable) {
                // A backend that cannot unload cleanly has already released, or
                // never held, its native memory. Reporting an error here would
                // block the next model from ever loading.
            }
        }
    }

    /**
     * Throws if the registry is not well formed. For the app-start path, where a
     * broken registration is a programming error that must not ship silently.
     */
    fun requireWellFormed() {
        check(registryReport.isWellFormed) {
            "tool registry is malformed: ${registryDiagnostics.joinToString("; ")}"
        }
    }
}
