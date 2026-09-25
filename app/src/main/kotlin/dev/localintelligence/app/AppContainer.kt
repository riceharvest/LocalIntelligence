package dev.localintelligence.app

import android.content.Context
import dev.localintelligence.android.data.LocalIntelligenceDatabase
import dev.localintelligence.android.inference.LlamaCppBackend
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.InMemoryMemoryStore
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.MemoryStore
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.ToolCallValidatorGate
import dev.localintelligence.core.context.ContextBuilder
import dev.localintelligence.core.context.DefaultContextBuilder
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.ToolRegistry

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
     * Today the shipped tool workstreams have not landed on this branch, so the
     * registry starts empty rather than being faked. When they do land, the wiring
     * is one line in [tools] — not a refactor.
     */
    val tools: ToolRegistry by lazy { SimpleToolRegistry(androidTools()) }

    val memoryStore: MemoryStore by lazy { InMemoryMemoryStore() }

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

    val importer: ModelImporter by lazy { ModelImporter(context.contentResolver) }

    /**
     * Durable stores. Opened lazily so a cold start that never runs a task never
     * creates the database file.
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
     * Builds the agent loop for one run.
     *
     * A function, not a singleton, and that is the point: a controller is
     * single-use. `cancel()` is sticky, and an `AwaitingConfirmation` has to be
     * resumed on the same instance that staged it. One call is one run.
     */
    fun newController(model: ModelBackend = modelBackend): AgentController = AgentController(
        model = model,
        parser = dev.localintelligence.core.agent.ActionParserImpl,
        tools = tools,
        toolSelector = LexicalToolSelector(),
        validator = ToolCallValidatorGate.forRegistry(tools),
        loopDetector = LoopDetector(),
        contextBuilder = contextBuilder,
        memory = memoryStore,
        sessions = Session(),
        config = agentConfig,
    )

    /**
     * The Android tool implementations, from `:android`.
     *
     * Empty on this branch: the calendar/device/files/notify tool workstreams are
     * separate PRs and this one must not touch their files. When they land they
     * are registered here, in `Application.onCreate`, from the platform context
     * each tool needs. Deliberately a seam with a body rather than a TODO in a
     * comment, so the wiring is visible and type-checked the day it is filled.
     */
    private fun androidTools(): List<dev.localintelligence.core.tool.AgentTool> = emptyList()
}
