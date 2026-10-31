package dev.localintelligence.app

import dev.localintelligence.core.tool.ToolFinder
import dev.localintelligence.android.data.lazyObservationStore
import dev.localintelligence.core.tool.ObservationStore
import android.content.Context
import dev.localintelligence.android.data.LocalIntelligenceDatabase
import dev.localintelligence.android.data.resilientMemoryStore
import dev.localintelligence.android.inference.ImportedModel
import dev.localintelligence.android.inference.LlamaCppBackend
import dev.localintelligence.inference.litertlm.ModelBackendRouter
import dev.localintelligence.android.inference.ModelImporter
import dev.localintelligence.android.cloud.ChatGptChatBackend
import dev.localintelligence.android.cloud.ChatGptPlugin
import dev.localintelligence.android.cloud.ChatGptSignIn
import dev.localintelligence.android.cloud.OpenRouterChatBackend
import dev.localintelligence.android.cloud.RemoteChatOffer
import dev.localintelligence.android.cloud.RemoteProviders
import dev.localintelligence.android.cloud.OpenRouterPlugin
import dev.localintelligence.android.documents.DocumentEmbedder
import dev.localintelligence.android.cloud.OpenRouterSettings
import dev.localintelligence.android.cloud.browser.CloudBrowserPlugin
import dev.localintelligence.android.cloud.sandbox.SANDBOX_TOOL_PREFIX
import dev.localintelligence.android.cloud.sandbox.SandboxPlugin
import dev.localintelligence.android.documents.DocumentStore
import dev.localintelligence.android.documents.DocumentsPlugin
import dev.localintelligence.android.documents.DOCUMENT_EMBEDDING_MODEL
import dev.localintelligence.android.documents.DOCUMENT_SEARCH_MODE
import dev.localintelligence.android.documents.SEARCH_MODE_HYBRID
import dev.localintelligence.android.gateway.telegram.TelegramPlugin
import dev.localintelligence.android.mcp.McpPlugin
import dev.localintelligence.android.tools.home.HomeAssistantPlugin
import dev.localintelligence.android.tools.email.EmailPlugin
import dev.localintelligence.android.skills.SkillRepository
import dev.localintelligence.android.skills.SkillsPlugin
import dev.localintelligence.android.tools.androidToolPlugins
import dev.localintelligence.android.scripts.ScriptPlugin
import dev.localintelligence.android.scripts.ScriptPluginRepository
import dev.localintelligence.android.scripts.ScriptRunner
import dev.localintelligence.app.settings.ContextChoice
import dev.localintelligence.app.settings.ContextLength
import dev.localintelligence.app.settings.DeviceFacts
import dev.localintelligence.app.execution.ScheduledRunReporter
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.app.settings.ComputeChoice
import dev.localintelligence.app.settings.SimpleSetup
import dev.localintelligence.app.speech.Speaker
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.plugin.PluginSet
import dev.localintelligence.core.policy.BlastRadiusTracker
import dev.localintelligence.core.policy.PolicyConfig
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.hub.formatBytes
import dev.localintelligence.core.settings.mode
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.agentConfig
import dev.localintelligence.core.settings.autoAcceptTiers
import dev.localintelligence.core.settings.samplingParams
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.SessionStore
import dev.localintelligence.core.agent.ToolCallValidatorGate
import dev.localintelligence.core.agent.TurnRecorder
import dev.localintelligence.core.execution.RunGate
import dev.localintelligence.core.memory.LexicalMemoryStore
import dev.localintelligence.core.memory.ProfilePlugin
import dev.localintelligence.android.inference.RamEstimate
import dev.localintelligence.app.ui.DownloadedModelRegistrar
import dev.localintelligence.app.speedtest.ComputeComparison
import dev.localintelligence.app.speedtest.SpeedTestRunner
import dev.localintelligence.app.speedtest.SpeedTestTarget
import dev.localintelligence.app.ui.displayNameOf
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.localintelligence.core.context.DefaultContextBuilder
import dev.localintelligence.core.context.SystemPrompts
import dev.localintelligence.core.metrics.RunRecorder
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.KvCacheType
import dev.localintelligence.core.model.SpeechOutput
import dev.localintelligence.core.policy.RiskPolicy
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.catalogue.CatalogueAgreement
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import dev.localintelligence.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import dev.localintelligence.core.hub.DeviceBudget
import dev.localintelligence.core.hub.HubTokenSource
import dev.localintelligence.core.hub.HuggingFaceClient
import dev.localintelligence.android.hub.AndroidDeviceBudget
import dev.localintelligence.android.hub.ModelDownloader
import dev.localintelligence.android.hub.UrlConnectionTransport
import dev.localintelligence.app.ui.HubViewModel
import java.io.File
import dev.localintelligence.core.tool.redaction.RedactingToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Log tag for this container, matching the class name used elsewhere. */
    private val TAG = "AppContainer"

    private companion object {
        /**
         * Preferences file holding the pinned conversation id and the URI of the
         * model last loaded, so both survive the process. Separate keys, so
         * forgetting the model cannot orphan the conversation or the other way
         * round.
         */
        const val PREFERENCES = "localintelligence.conversation"

        /** Key for [PREFERENCES]: the [SessionStore] id of the conversation. */
        const val KEY_SESSION_ID = "session_id"

        /** Key for [PREFERENCES]: the URI string of the model last selected. */
        const val KEY_MODEL_URI = "model_uri"
    }

    /**
     * The one tool registry for the process.
     *
     * Wraps the shipped Android tools in [RedactingToolRegistry], so every tool
     * result is redacted on the way back to the loop. Keep this the single
     * place the registry is assembled: a path that builds one another way
     * bypasses redaction.
     *
     * WHY the assembly lives in `:android` and not here: the tools are that
     * module's, and `:app` is Compose and nothing else
     * (`docs/architecture.md` §4). Listing the families here would mean a
     * `:app` edit every time a tool lands.
     *
     * WHY it is safe to build eagerly-in-a-lazy: `androidTools` only
     * constructs tool objects. No platform call happens, no service is
     * resolved, and no model is touched — the RAM budget of §16 is untouched by
     * a few kilobytes of definition objects.
     */
    val tools: ToolRegistry by lazy {
        // Wrapped, not replaced: every observation the loop sees is filtered
        // here, at the moment it is born, BEFORE the model reads it. Filtering
        // after the answer would leave the model holding a secret the transcript
        // no longer shows, which is worse than no filter at all - the log would
        // misdescribe what actually happened, in the direction that looks like
        // safety. The decorator delegates everything, so catalogue agreement and
        // risk tiers still run on the real registry underneath.
        RedactingToolRegistry(SimpleToolRegistry(plugins.flatMap { it.tools() }, catalogue = emptyList()))
    }

    /** What Simple mode decides from. Read once: RAM and core count do not change. */
    val deviceFacts: DeviceFacts by lazy { DeviceFacts.of(context) }

    /** Every setting in the app. See [SettingsStore]. */
    /**
     * The decision function for [model]: resolves both the context length and KV cache type
     * (see [ContextLength.decide]). The load and the RAM gate use this same decision.
     */
    fun contextChoiceFor(model: ImportedModel): ContextChoice = ContextLength.decide(
        setting = settings[CoreSettings.CONTEXT_LENGTH],
        kvSetting = settings[CoreSettings.KV_CACHE_TYPE],
        facts = deviceFacts,
        trainedContext = model.trainedContextLength,
        footprintFor = { type ->
            (model.estimate as? RamEstimate)?.let { ram -> RamEstimate.from(ram.header, kvCacheType = { type }) }
                ?: model.estimate
        },
        availableRamBytes = hubBudget.availableRamBytes(),
        liteRtLm = model.architecture == ModelImporter.LITERTLM_ARCHITECTURE,
    )

    /**
     * Resolves the context length and KV cache type for a probed or known [GgufHeader].
     */
    fun contextChoiceFor(header: GgufHeader?): ContextChoice = ContextLength.decide(
        setting = settings[CoreSettings.CONTEXT_LENGTH],
        kvSetting = settings[CoreSettings.KV_CACHE_TYPE],
        facts = deviceFacts,
        trainedContext = header?.metadata?.contextLength?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
        footprintFor = { type -> header?.let { RamEstimate.from(it, kvCacheType = { type }) } },
        availableRamBytes = hubBudget.availableRamBytes(),
    )

    /**
     * The n_ctx [model] is priced at and loaded with: the context-length setting, else the
     * largest window the phone and this model allow (see [ContextLength.decide]). One function for
     * the RAM gate and the load.
     */
    fun contextLengthFor(model: ImportedModel): Int = contextChoiceFor(model).nCtx

    /**
     * The KV cache type allocated for [model], determined per model by [ContextLength.decide].
     */
    fun kvCacheTypeFor(model: ImportedModel): KvCacheType = contextChoiceFor(model).kvCacheType

    /**
     * The KV cache type allocated for [header], determined per header by [ContextLength.decide].
     */
    fun kvCacheTypeFor(header: GgufHeader?): KvCacheType = contextChoiceFor(header).kvCacheType

    /**
     * Fallback for code needing one concrete type without a model: delegates to [kvCacheTypeFor] with null.
     */
    fun kvCacheType(): KvCacheType = kvCacheTypeFor(null as GgufHeader?)

    val settings: SettingsStore by lazy { SettingsStore(context) { SimpleSetup.values(deviceFacts) } }

    /** Reads replies aloud with the platform text-to-speech. The engine starts on first use. */
    val speaker: Speaker by lazy {
        Speaker(context, settings) {
            val backend = residentBackend()
            (backend as? SpeechOutput)?.takeIf { backend.capabilities.supportsSpeechOutput }
        }
    }

    /**
     * Everything that can be switched on and off. The built-in tool families
     * are the first plugins; features add theirs here.
     */
    val plugins: List<AgentPlugin>
        get() = builtInPlugins + scriptPluginsNow()

    /**
     * Installed script plugins, read from disk on each call. Read per call rather
     * than once, so a plugin installed on the Plugins screen is offered from the
     * next message without a restart. Its on/off switch is read by [PluginSet].
     */
    fun scriptPluginsNow(): List<ScriptPlugin> =
        scriptRepository.list().map { ScriptPlugin(it, scriptRunner, settings) }

    /** The JavaScript sandbox script plugins run in. One per process; see ScriptRunner. */
    val scriptRunner: ScriptRunner by lazy { ScriptRunner(context) }

    /** Script plugins on disk under filesDir/plugins. */
    val scriptRepository: ScriptPluginRepository by lazy { ScriptPluginRepository(File(context.filesDir, "plugins")) }

    private val builtInPlugins: List<AgentPlugin> by lazy {
        val families = androidToolPlugins(context, settings)
        val cloud = openRouterPlugin
        val sandbox = sandboxPlugin
        val browser = cloudBrowserPlugin
        val chatGpt = chatGptPlugin
        // Checked here, against everything shipped and switched on or not. A run's
        // registry holds only the enabled tools, so checking it against the
        // catalogue would fail whenever any plugin is off, which is the default for
        // the cloud plugin.
        val docs = DocumentsPlugin(documents)
        // tools.find ships too, offered only with "Let the model look up tools";
        // an instance here stands for it (the check reads definitions only).
        val finder = ToolFinder(SimpleToolRegistry(emptyList(), catalogue = emptyList()), LexicalToolSelector(), session)
        val home = HomeAssistantPlugin(settings)
        val email = EmailPlugin(settings)
        CatalogueAgreement.require(
            families.flatMap { it.tools() } + cloud.shippedTools() + sandbox.shippedTools() + browser.shippedTools() +
                docs.shippedTools() + finder + home.shippedTools() + email.shippedTools(),
            V0ToolCatalogue.definitions,
        )
        val remote = McpPlugin(settings)
        families + ProfilePlugin() + SkillsPlugin(skills) + docs + cloud + chatGpt + sandbox + browser + remote + home +
            email + telegramPlugin
    }

    /**
     * Documents the user added on the Documents screen, as text under filesDir/documents.
     *
     * WHY the embedder and the mode are lambdas: the store asks for the model only when a
     * document is added or a hybrid search runs, and reads the mode at search time, so a
     * settings change applies to the next search without rebuilding anything.
     */
    val documents: DocumentStore by lazy {
        DocumentStore(
            root = File(context.filesDir, "documents"),
            embedder = { documentEmbedder.get() },
            hybridSearch = { settings[DOCUMENT_SEARCH_MODE] == SEARCH_MODE_HYBRID },
        )
    }

    /** The meaning-search model for documents, loaded on first use. See [DocumentEmbedder]. */
    val documentEmbedder: DocumentEmbedder by lazy {
        DocumentEmbedder(modelsDir) { settings[DOCUMENT_EMBEDDING_MODEL] }
    }

    /** The document search model on disk, or null. See [DocumentEmbedder.resolve]. */
    fun documentModelFile(): File? = DocumentEmbedder.resolve(modelsDir, settings[DOCUMENT_EMBEDDING_MODEL])

    /**
     * True when a finished download is the document search model, by name: the setting's
     * file name, or any EmbeddingGemma build. Hub names are `owner__repo__file`, so the
     * setting's name is matched by its ending.
     */
    private fun isDocumentModelName(name: String): Boolean {
        val setting = File(settings[DOCUMENT_EMBEDDING_MODEL].trim()).name
        return name.contains("embeddinggemma", ignoreCase = true) ||
            (setting.isNotEmpty() && (name == setting || name.endsWith("__$setting")))
    }

    /** Skills on disk under filesDir/skills. Shared by the plugin, the skills screen and "Save as skill". */
    val skills: SkillRepository by lazy { SkillRepository(File(context.filesDir, "skills")) }

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
    private val blastRadius: BlastRadiusTracker by lazy { BlastRadiusTracker() }

    /**
     * The policy for one run: the user's Approvals choices for this kind of run,
     * over the one shared [blastRadius] counter.
     */
    fun riskPolicy(settings: SettingsReader, background: Boolean): RiskPolicy = RiskPolicy(
        PolicyConfig(autoAcceptTiers = settings.autoAcceptTiers(background)),
        blastRadius,
    )

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
     *
     * ## WHY IT IS A [LexicalMemoryStore] AND NOT THE BARE RESILIENT ONE
     *
     * The type changed from `MemoryStore` to its `MemoryStore`-shaped subclass and
     * nothing else. `LexicalMemoryStore` wraps the same `resilientMemoryStore(context)`
     * delegate — same Room-when-it-opens / RAM-when-it-does-not behaviour, same
     * `CancellationException` handling, same laziness, because the delegate is the same
     * object — and adds the two halves this feature was missing:
     *
     *  - `recordTurn`, which is what makes a memory FORMABLE. Before this line the
     *    container handed the loop a store it could only read, and the loop only ever
     *    read: `grep -rn '\.remember(' --include=*.kt .` outside of tests returned four
     *    hits, all of them declarations and implementations, zero call sites. The agent
     *    could consult a memory it had no way to create.
     *  - a `search` that ranks by `MemoryIndex` over a bounded candidate set rather
     *    than delegating to the delegate's SQL `LIKE` prefilter, which uses a
     *    different tokeniser and returns nothing for a query whose inflection does not
     *    match a stored row. See `LexicalMemoryStore.search` for the measured case.
     *
     * Nothing in the app that reads this property is affected: `LexicalMemoryStore` IS a
     * `MemoryStore`, so the only consumer — `newController`'s `memory =` argument —
     * compiles and behaves identically. The concrete type is spelled here rather than
     * kept behind an interface because the ONE thing the composition root has to do
     * with a write-capable store is hand `recordTurn` to the loop, and a value typed as
     * the read-only interface cannot express that.
     */
    val memoryStore: LexicalMemoryStore by lazy {
        LexicalMemoryStore(resilientMemoryStore(context))
    }

    /**
     * The one run at a time, for the whole process.
     *
     * ## WHY THE GATE LIVES HERE AND NOT IN THE SERVICE
     *
     * Both ways of starting a run — a chat message and a scheduled alarm — reach
     * `ExecutionService.onStartCommand`, and neither is serialised by the service:
     * `serviceScope` is `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, and
     * `Dispatchers.Default` is a thread POOL, so two `launch`es into it run at once.
     * `startForegroundService` on a live service delivers a second `onStartCommand` to
     * the SAME instance and serialises nothing. So the contention domain is the process,
     * and the process-wide object is this container — not the service, which is created
     * and destroyed around each run and would hand out a fresh gate every time if the
     * gate lived there.
     *
     * WHY IT IS `by lazy` AND WHY IT MUST NOT BE: an `AtomicBoolean` costs nothing
     * when idle, so laziness here is habit rather than necessity, and the real
     * constraint is the one in the KDoc above: the container must not be built
     * eagerly. This is an object with no database, no model and no I/O, so reading it
     * on a cold start that never runs a task is free.
     *
     * THE GATE IS CLAIMED IN EXACTLY ONE PLACE: `ExecutionService.startRun`
     * calls `tryClaim()` and releases in its single terminal cleanup path.
     * Scheduled runs reach it too — `ScheduledTaskFireReceiver` starts the
     * service rather than the loop, so scheduled and chat runs contend for
     * this one instance instead of each holding a private gate.
     *
     * A second claim site here would be a bug, not belt-and-braces: the gate
     * arbitrates a single global resource (the loaded model), and a run that
     * claimed twice would deadlock against itself.
     */
    val runGate: RunGate by lazy { RunGate() }

    /**
     * The conversation, kept across runs and across process death.
     *
     * WHY THIS IS NOT `Session()` PER RUN: a controller is single-use by design
     * — it owns per-run mutable state and is thrown away afterwards. But the
     * conversation is not per-run, and a per-run `Session()` means a second
     * message carries nothing at all: the model has no idea what has already
     * been said, and the app looks like it has no memory while claiming to be an
     * agent that remembers.
     *
     * One instance per container, so both entry points — a chat turn and a
     * scheduled task — share the same thread of conversation, which is what a
     * user means by "the conversation". [sessionStore] mirrors it to disk, so it
     * also survives process death.
     *
     * WHY NOT GUARDED: the container is constructed once and lives for the
     * process, and `ExecutionService` serialises runs on a single serviceScope,
     * so two runs cannot interleave writes. If that ever changes, this needs a
     * lock - noted here so the invariant is not lost.
     */
    val session: Session by lazy { Session() }

    /**
     * Durable mirror of [session], or null when the app is running without one.
     *
     * ## WHY THE WRITER LIVES HERE AND NOT IN `Session`
     *
     * `Session` is a `:core` type and `:core` is a pure JVM module: it has no
     * `android.*` import and no Room dependency, by rule and by the
     * `grep -rn '^import android\.' core/src/main/` check. Anything that touches
     * a database therefore has to sit above it, and this container is the one
     * place that already knows how to build the Room database. `Session` grows
     * exactly two small pure-JVM affordances to make that possible - a
     * high-water [Session.appendedCount] and [Session.restore] - and knows
     * nothing about storage.
     *
     * ## WHY THE STORE IS OPTIONAL
     *
     * The concrete store is reached through a `SessionStore` obtained from the
     * `:android` module. That module is the only one that can see Room, and it
     * is owned elsewhere, so the factory is injected. When it is absent the app
     * degrades to the behaviour it had before this change - a session that
     * lives one process - rather than refusing to start.
     */
    private val sessionStore: SessionStore? by lazy { SessionStoreFactory.provide(context) }

    /**
     * The id of the durable conversation this process appends to.
     *
     * Created once, on the first write, and then held for the process: the
     * store is append-only, so a second id would split one conversation in two
     * and the restore would only ever see half of it.
     */
    @Volatile
    private var durableSessionId: Long = 0L

    /**
     * Messages already handed to the store, to make the writer idempotent.
     *
     * This counts the store's rows, not the window's, so it keeps working
     * after [RetainedHistory.bound] trims the in-memory list.
     */

    /**
     * Rehydrates [session] from the store, once per process, before the first
     * run builds a request from it.
     *
     * Off the main thread by construction: the caller is a coroutine on the
     * service's `Dispatchers.Default`, and every call into the store hops to
     * `Dispatchers.IO` internally. Opening SQLite and reading back a few dozen
     * rows is a couple of milliseconds against a model load measured in
     * seconds, so it is not worth a dedicated warm-up pass - but it is also not
     * something to do on the main thread, which is why it rides the existing
     * pre-run path rather than the container's initialiser.
     */
    suspend fun restoreConversationOnce() {
        val store = sessionStore ?: return
        if (restoredOnce) return
        restoredOnce = true
        // The conversation's id is pinned in preferences, NOT discovered. That
        // is forced by the interface rather than chosen: `SessionStore` can
        // only create a session, append to a given id, and read a given id, so
        // there is no query that answers "which conversation did this user
        // have". Pinning the id is what turns a per-process object into one
        // durable thread, and it is the same fact Room would otherwise have to
        // be asked for.
        val id = pinnedSessionId ?: return
        val history = try {
            withContext(Dispatchers.IO) { store.messages(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A store that cannot be read must not stop the user chatting. The
            // session stays empty, which is the position the app was in before
            // persistence existed.
            android.util.Log.w(TAG, "Could not restore conversation $id; continuing empty.", t)
            return
        }
        if (history.isEmpty()) return
        val adopted = session.restore(history)
        // Everything restored is already on disk, so the writer must not
        // re-append it. With identity-based writing the equivalent is marking
        // those exact message objects as already written, rather than setting a
        // count that the next trim could invalidate.
        session.markAllWritten()
        restoredTurns = session.messages.toList()
        if (adopted > 0) {
            android.util.Log.i(
                TAG,
                "Restored $adopted of ${history.size} stored messages (session $id).",
            )
        } else {
            // The rows exist but the window refuses them - a tail with no user
            // turn in it. An empty window is the honest outcome: it admits
            // ignorance, where a fragment would pretend to recall.
            android.util.Log.i(
                TAG,
                "Conversation $id held ${history.size} messages but none of them " +
                    "began a coherent window; starting fresh.",
            )
        }
    }

    /**
     * The id of the durable conversation, creating and pinning one if this is
     * the first run that has ever had a store.
     *
     * Pinned in [PREFERENCES] rather than held only in a field, because a field
     * dies with the process - which is the entire problem being fixed. The pin
     * is a single [Long] written once and read once per cold start.
     */
    private suspend fun durableSessionId(store: SessionStore): Long {
        pinnedSessionId?.let { return it }
        val id = withContext(Dispatchers.IO) { store.createSession() }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SESSION_ID, id)
            .apply()
        return id
    }

    /**
     * Starts a new conversation. The model forgets this one, and the next
     * message is stored under a new id; the old conversation stays on disk,
     * where Past chats (`history.search`) can still find it.
     *
     * Only between runs: [session] is not guarded (see its KDoc), so the caller
     * checks that nothing is running first.
     */
    suspend fun startNewConversation() {
        // The between-messages work folds this session; it is stopped and joined
        // first, or a fold still running could write the old conversation back
        // into the new one.
        stopIdleWork()
        session.clear()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION_ID)
            .apply()
        restoredTurns = emptyList()
        // The old conversation's cloud sandbox is stopped in the background: a stop can take a
        // network round trip, and the chat should clear now. The next conversation starts its own.
        appScope.launch { sandboxPlugin.endConversation() }
    }

    /**
     * The pinned conversation id, or null if none has been created yet.
     *
     * Read-only, and deliberately so: the only writer is [durableSessionId],
     * which pins the id at the same moment it creates the row, so a pin can
     * never name a session that does not exist.
     */
    private val pinnedSessionId: Long?
        get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION_ID, 0L)
            .takeIf { it != 0L }

    /**
     * Appends whatever the session has produced since the last call.
     *
     * ## WHY IT IS CALLED FROM TWO PLACES
     *
     * Once per run, from [TurnRecorder] below, which fires at
     * `AgentAction.Respond` after the reply has been appended - so a turn that
     * completes is durable immediately, which is the case that matters when a
     * user finishes a conversation and then backgrounds the app.
     *
     * And once per run, from [ensureModelReady], which catches up the turns
     * that first hook cannot see: a run that ended in `Stop`, a cancel, or an
     * `AwaitingConfirmation` never reaches `Respond`, and without this second
     * call those turns would only be written the next time the user happened to
     * send something. Being idempotent - keyed on the high-water mark, not on
     * elapsed time - is what makes it safe for both callers to invoke it and
     * for the second to be a no-op when the first already ran.
     *
     * WHY NOT PER MESSAGE: a turn is a user message, a tool observation and a
     * reply. Writing each separately triples the database work and buys
     * nothing, because a process killed mid-turn loses the same window either
     * way. The unit of durability here is the turn, stated plainly.
     */
    private fun snapshotPendingMessages(): List<ChatMessage> {
        if (sessionStore == null) return emptyList()
        // Write every message in the window that has not been written, by
        // IDENTITY rather than by count.
        //
        // The count arithmetic this replaces was `takeLast(appendedCount -
        // durableWriteCount)`, which is unsound for two separate reasons: the
        // trims are middle trims (so the window is shorter than the count
        // implies and `takeLast` silently returns the wrong slice, duplicating
        // a row and dropping others), and writing the dropped messages first
        // does not fix it either — `durableWriteCount` then advances past a
        // window that was never written. Simulated both ways: 3 messages lost
        // and 11 duplicated, every time.
        //
        // `Session.writtenIds` is the set of message identities already handed
        // to the store, so a trim, a fold, or a repeated call are all naturally
        // idempotent and nothing is inferred from positions.
        // A tool call's own turn (blank text, see Session.recordCall) is prompt
        // plumbing, not conversation: it is not stored or shown after a restart.
        // Under the session lock: the idle compaction folds the window on another thread,
        // and reading the list while it is cleared would throw or see a half-refilled window.
        val pending = session.locked {
            session.messages.filter { session.writtenIds.add(session.idOf(it)) }
        }
        return pending
            .filterNot { (it is ChatMessage.Assistant && it.text.isBlank()) || (it is ChatMessage.User && it.text.isBlank()) }
    }

    private suspend fun writeSnapshot(pending: List<ChatMessage>) {
        if (pending.isEmpty()) return
        val store = sessionStore ?: return
        val id = try {
            durableSessionId(store)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Could not open a durable session; skipping write.", t)
            // Nothing was written, so nothing may stay marked as written.
            session.locked { pending.forEach { session.writtenIds.remove(session.idOf(it)) } }
            return
        }
        // Counts the rows the store accepted. A failure part-way through a batch must
        // keep those marked as written: appending them again would store them twice.
        var stored = 0
        try {
            withContext(Dispatchers.IO) {
                for (message in pending) {
                    store.appendMessage(id, message)
                    stored++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Losing a write is survivable in a way failing the run is not: the
            // answer has already been produced and shown. Un-mark the rows that were
            // not stored so the next run genuinely retries them, rather than leaving
            // them marked as written and silently dropping them.
            session.locked { pending.drop(stored).forEach { session.writtenIds.remove(session.idOf(it)) } }
            android.util.Log.w(TAG, "Could not persist the conversation; will retry next run.", t)
        }
    }

    suspend fun persistConversation() {
        writeSnapshot(snapshotPendingMessages())
    }

    /**
     * Writes the turns of a run that has just reached a terminal state, without
     * waiting for the next run to start. The snapshot is taken on the calling
     * thread and only the write is launched on [appScope]: the caller's scope is
     * torn down with its service, and a write cancelled midway is marked written
     * without ever being stored.
     */
    fun persistRunEnd() {
        val pending = snapshotPendingMessages()
        if (pending.isNotEmpty()) appScope.launch { writeSnapshot(pending) }
    }

    @Volatile
    private var restoredOnce: Boolean = false

    /**
     * The turns [restoreConversationOnce] put into [session], captured at that
     * moment. The chat shows these at launch and reads them from here, not from
     * [session], which a run may already be appending to.
     */
    @Volatile
    private var restoredTurns: List<ChatMessage> = emptyList()

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


    /**
     * The inference backend. Constructed lazily, and the single biggest reason
     * this file exists: `LlamaCppBackend` is what pulls llama.cpp into the
     * process.
     */
    val modelBackend: ModelBackend by lazy {
        LlamaCppBackend(importer, computeSource = computeChoice, prefixCacheDir = prefixCacheDir)
    }

    /** The system prompt's saved llama.cpp state; a cache, so Android may clear it. */
    private val prefixCacheDir: File by lazy { File(context.cacheDir, "prefix") }

    /** Where llama.cpp runs each load: the Compute setting and the speed test's winner. */
    val computeChoice: ComputeChoice by lazy {
        ComputeChoice(settings, File(context.filesDir, "gpu-load-in-progress"))
    }

    /**
     * The OpenRouter plugin, one instance for the plugin list and the remote chat model.
     * The chat model reads the same switch and key, so the two cannot disagree.
     */
    val openRouterPlugin: OpenRouterPlugin by lazy { OpenRouterPlugin(context, settings) }

    /**
     * The cloud sandbox plugin: commands run on the user's own Daytona account. Its tools are
     * offered only in Technical mode and only while the remote chat model answers; see
     * [newController]. New chat stops its sandbox ([startNewConversation]).
     */
    val sandboxPlugin: SandboxPlugin by lazy { SandboxPlugin(settings, settings, context.applicationContext) }

    /**
     * The cloud browser plugin: a web task runs on the user's own Browser Use account. Its tools are
     * offered in Technical mode to any chat model, each start asks first.
     */
    val cloudBrowserPlugin: CloudBrowserPlugin by lazy { CloudBrowserPlugin(settings) }

    /**
     * The remote chat model. It is never loaded by [loadModel]: it becomes the resident
     * backend only when the user picks it on the Models screen ([useRemoteChat]).
     */
    val remoteChatBackend: OpenRouterChatBackend by lazy {
        OpenRouterChatBackend(
            reader = settings,
            enabled = { settings.isPluginEnabled(openRouterPlugin) },
        )
    }

    /** Telegram as a remote keyboard for this phone. Off by default; see TelegramService. */
    val telegramPlugin: TelegramPlugin by lazy { TelegramPlugin() }

    /**
     * The ChatGPT plugin: the sign-in account and the model choice. One instance for the plugin
     * list and the chat model, so the switch and the account cannot disagree.
     */
    val chatGptPlugin: ChatGptPlugin by lazy { ChatGptPlugin(reader = settings, writer = settings) }

    /** The ChatGPT chat model. Like OpenRouter, it is resident only when the user picks it. */
    val chatGptBackend: ChatGptChatBackend by lazy {
        ChatGptChatBackend(plugin = chatGptPlugin, enabled = { settings.isPluginEnabled(chatGptPlugin) })
    }

    /** Sign-in. The keep-alive service runs only while the browser is in front. */
    val chatGptSignIn: ChatGptSignIn by lazy {
        ChatGptSignIn(
            context = context,
            plugin = chatGptPlugin,
            keepAlive = { runCatching { ChatGptSignInService.start(context) } },
            releaseKeepAlive = { ChatGptSignInService.stop(context) },
        )
    }

    /**
     * True when the remote chat model is the one answering. Checked by the speed test and
     * the Models screen, which both describe the resident model and must not describe a
     * local file that is not in memory.
     */
    val remoteChatActive: Boolean get() = loadedBackend === remoteChatBackend

    /** True while ChatGPT is the model answering. The OpenRouter entry does not read this. */
    val chatGptActive: Boolean get() = loadedBackend === chatGptBackend

    /**
     * True when ChatGPT can answer now: the plugin is on and an account is signed in. Read at
     * the moment it matters, so a sign-out or a switch-off takes effect at the next message.
     */
    fun chatGptChatOn(): Boolean = settings.isPluginEnabled(chatGptPlugin) && chatGptPlugin.account() != null

    /**
     * Makes ChatGPT the resident backend, unloading whatever held the memory. Returns false,
     * changing nothing, when ChatGPT cannot answer. Like [useRemoteChat], the choice is not saved.
     */
    suspend fun useChatGpt(): Boolean = readinessLock.withLock {
        if (!chatGptChatOn()) return@withLock false
        val previous = loadedBackend
        if (previous != null && previous !== chatGptBackend) {
            stopPrefixWork()
            loadedBackend = null
            previous.unload()
        }
        loadedBackend = chatGptBackend
        modelAvailability.set(ModelAvailability.Ready(displayName = "ChatGPT"))
        true
    }

    /**
     * Gives the phone's own model back: the selected local model is loaded again. With none ever
     * selected, ChatGPT is only dropped. Used when the user leaves ChatGPT or signs out.
     */
    suspend fun useLocalModel() {
        val model = selectedModel
        if (model != null) {
            loadModel(model)
        } else {
            readinessLock.withLock { dropChatGptLocked() }
        }
    }

    private suspend fun dropChatGptLocked() {
        if (loadedBackend !== chatGptBackend) return
        stopPrefixWork()
        loadedBackend = null
        chatGptBackend.unload()
        modelAvailability.set(ModelAvailability.None)
    }

    /**
     * What the Models screen offers for the remote chat provider, or null when the option is
     * off: the plugin is switched off, or the chosen provider has no key (or no valid address).
     */
    fun remoteChatOfferOrNull(): RemoteChatOffer? {
        if (!settings.isPluginEnabled(openRouterPlugin)) return null
        val endpoint = RemoteProviders.endpoint(settings) ?: return null
        return RemoteChatOffer(
            providerLabel = endpoint.provider.label,
            destination = endpoint.destination,
            model = endpoint.model,
            canListModels = endpoint.provider.listsModels,
        )
    }

    /** The model ids the chosen provider lists. Opt-in: the plugin must be on, and it is a network call. */
    suspend fun listRemoteModels(): List<String> = withContext(Dispatchers.IO) {
        check(settings.isPluginEnabled(openRouterPlugin)) { "remote chat is off" }
        val endpoint = RemoteProviders.endpoint(settings) ?: error("the chat provider is not set up")
        OpenRouterChatBackend.fetchModelIds(endpoint)
    }

    /** Saves the chat model id for the chosen provider. Chosen from the list or typed. */
    fun setRemoteChatModel(model: String) {
        settings.set(OpenRouterSettings.CHAT_MODEL, model.trim())
    }

    /**
     * Makes the remote chat model the resident backend, unloading whatever local model
     * was holding the memory. Returns false, changing nothing, when the option is off.
     *
     * WHY THE CHOICE IS NOT SAVED: a restart comes back to the local model the user had
     * selected. Sending a conversation to a third party should be a choice made again each
     * time, not one that survives a restart unnoticed.
     */
    suspend fun useRemoteChat(): Boolean = readinessLock.withLock {
        val offer = remoteChatOfferOrNull() ?: return@withLock false
        if (offer.model.isBlank()) return@withLock false
        val previous = loadedBackend
        if (previous != null && previous !== remoteChatBackend) {
            stopPrefixWork()
            loadedBackend = null
            previous.unload()
        }
        loadedBackend = remoteChatBackend
        modelAvailability.set(ModelAvailability.Ready(displayName = "${offer.providerLabel}: ${offer.model}"))
        true
    }

    /**
     * The backend that ACTUALLY loaded the resident model, or null when nothing
     * is loaded.
     *
     * WHY THIS IS NOT JUST `backendFor(selectedModel)`: routing happened in
     * `loadModel`, on a local `val`, and was thrown away when that function
     * returned. `newController` then fell back to its `modelBackend` default —
     * hardcoded `LlamaCppBackend` — so a `.litertlm` was correctly LOADED into
     * the LiteRT backend and then GENERATED through llama.cpp. Every stage of
     * the pipeline was individually correct and the assembled path was wrong,
     * which is precisely the failure mode this repository keeps hitting and
     * which no compiler can catch: both types are `ModelBackend`.
     *
     * Set from the same `val` that is handed to `load`, so the runtime that
     * loads and the runtime that generates cannot disagree by construction.
     * Cleared when the model is cleared.
     */
    @Volatile
    private var loadedBackend: ModelBackend? = null

    /** The backend holding the loaded model, for the controller that will run. */
    fun residentBackend(): ModelBackend = loadedBackend ?: modelBackend

    /**
     * The in-app speed test. Lazy, like every other field here: the scope is only
     * created when a user first asks for a measurement.
     *
     * WHY ITS OWN SCOPE: a run can take a minute, and it must outlive the screen that
     * started it. The gate, not the scope, is what keeps it from overlapping a chat run.
     */
    val speedTest: SpeedTestRunner by lazy {
        SpeedTestRunner(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            runGate = runGate,
            resolveTarget = ::speedTestTarget,
        )
    }

    /**
     * What a speed test measures, or null when nothing is resident.
     *
     * "Resident" means the last load succeeded: [ModelAvailability.Ready] and the
     * selected model are set together by [loadModelLocked], so the file named here is
     * the file in memory. The file name comes from the path when the model is a plain
     * file, and from the provider's display name otherwise, because a content URI has
     * no path to show.
     */
    private fun speedTestTarget(): SpeedTestTarget? {
        if (modelAvailability.current !is ModelAvailability.Ready) return null
        // The remote model has no file on this phone to measure.
        if (remoteChatActive || chatGptActive) return null
        val model = selectedModel ?: return null
        val file = model.uri.path?.takeIf { model.uri.scheme == "file" }?.let { File(it).name }
        val backend = residentBackend()
        // On a GPU the weights sit in driver memory, and while the drivers start,
        // compile their kernels and repack the weights they briefly hold more.
        // MEASURED Samsung A05s (3.6 GB RAM, LFM3-1.7B Q4_K_M at ~1.2 GB): trying
        // Vulkan and then OpenCL got the app killed by the low-memory killer. So the
        // GPUs are only tried when the usable RAM holds the model twice.
        val gpuRoom = model.estimate.totalBytes(contextLengthFor(model)) * 2 <=
            RamEstimate.usableDeviceBytes()
        val llama = (backend as? LlamaCppBackend)?.takeIf { computeChoice.isAuto }
        return SpeedTestTarget(
            backend = backend,
            modelUri = model.uri.toString(),
            modelFile = file ?: displayNameOf(context, model.uri) ?: model.displayName,
            comparison = llama?.takeIf { gpuRoom }?.let { ComputeComparison(it, computeChoice) },
            note = if (llama != null && !gpuRoom) {
                "GPU not tried: this phone does not have the memory to hold this model on the GPU."
            } else {
                null
            },
        )
    }

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
            llamaCompute = computeChoice,
            llamaPrefixCache = prefixCacheDir,
        )
    }

    /**
     * The runtime that can actually load [model], decided from its file.
     *
     * WHY a LiteRT-LM model that cannot be routed is an error and not a fall back
     * to llama.cpp: llama.cpp cannot open a `.litertlm`, and the failure would come
     * from native code with no reason attached. The fall back stays for models
     * that list llama.cpp as a supported backend, which is every GGUF.
     */
    fun backendFor(model: ImportedModel): ModelBackend {
        val file = model.uri.path?.let { java.io.File(it) }
        val routed = file?.takeIf { it.isFile }?.let { runCatching { backendRouter.route(it) }.getOrNull() }
        if (routed != null) return routed
        if (ModelImporter.BACKEND_LLAMA_CPP in model.supportedBackends) return modelBackend
        throw IllegalStateException(
            "cannot find a runtime for ${model.displayName}: it is not a file this app can open by path",
        )
    }

    val importer: ModelImporter by lazy { ModelImporter(context.contentResolver, kvCacheType = ::kvCacheTypeFor) }

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
     * Long tool results, saved whole so the model can read past the preview.
     * Through the :android factory: :app cannot see Room's types (see
     * SessionStoreFactory), and the database opens on the first save.
     */
    private val observationStore: ObservationStore by lazy { lazyObservationStore(context) }

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
    val modelAvailability: ModelAvailabilityHolder by lazy {
        // A saved model is loaded at launch, so the chat starts in Loading rather
        // than flashing the first-run screen until it is resident.
        ModelAvailabilityHolder(if (savedModelUri != null) ModelAvailability.Loading else ModelAvailability.None)
    }

    /**
     * Attachments the chat has queued for the next run. Read once, by the chat
     * run's AgentViewModel. See [dev.localintelligence.app.media.PendingAttachments]
     * for why this is not carried on the service Intent.
     */
    val pendingAttachments: dev.localintelligence.app.media.PendingAttachments by lazy {
        dev.localintelligence.app.media.PendingAttachments()
    }

    /**
     * Models imported into the app, held in the container so the list survives
     * activity recreation across configuration changes.
     */
    val models = mutableStateListOf<ImportedModel>()

    /**
     * The model the agent should run, chosen by the user in the model manager.
     *
     * Null means "nothing imported", which is the state of a fresh install and
     * a legitimate thing for the UI to render — it is not an error, and it is
     * not a reason to crash on first send.
     *
     * Written through to [PREFERENCES], so the choice survives a restart. Setting
     * it to null (a delete) clears the saved copy too; otherwise the model would
     * come back on the next launch.
     */
    @Volatile
    var selectedModel: ImportedModel? = null
        set(value) {
            field = value
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODEL_URI, value?.uri?.toString())
                .apply()
        }

    /** The URI of the model last selected, or null. Read from disk on each call. */
    private val savedModelUri: String?
        get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_MODEL_URI, null)

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
    suspend fun loadModel(model: ImportedModel): ModelAvailability =
        readinessLock.withLock { loadModelLocked(model) }

    /** [loadModel] without the lock, for callers that already hold it. */
    private suspend fun loadModelLocked(model: ImportedModel): ModelAvailability {
        // The chat's state and model before this switch. A switch that fails must leave the
        // chat on a model that works, so both are kept until the switch has succeeded.
        val before = modelAvailability.current
        val previous = loadedBackend
        val previousModel = selectedModel
        return try {
        // Refuse before the native load, not after. A model that needs more RAM
        // than this device has is an OOM kill: on Android the process dies and
        // the user loses the conversation, with a system dialog that says nothing
        // about which model did it. The estimate is already on ImportedModel and
        // ModelManagerScreen renders it, so the only missing piece was the gate.
        // This is where a download that was judged too large at plan time still
        // gets caught, e.g. a model sideloaded onto a smaller device.
        val choice = contextChoiceFor(model)
        val loadContext = choice.nCtx
        if (!model.fitsOnDevice(loadContext)) {
            val needed = model.estimate.totalBytes(loadContext)
            val have = RamEstimate.usableDeviceBytes()
            val reason = if (settings.mode == SettingsMode.TECHNICAL) {
                "This model needs about ${formatBytes(needed)} of memory and this phone has about " +
                    "${formatBytes(have)} free. Try a smaller model or a smaller version of it."
            } else {
                "Too big for this phone's memory. Try a smaller model."
            }
            return switchFailed(reason, before, previous, previousModel)
        }
        // Route per model, not once for the app: a .litertlm needs LiteRT-LM and
        // a GGUF needs llama.cpp, and loadModel is the only place that knows
        // which file it was handed.
        val backend = backendFor(model)
        modelAvailability.set(ModelAvailability.Loading)
        // A prefix build for the model being replaced must not run on it, or on the
        // next load's backend, while this one is released.
        stopPrefixWork()
        // The previous model is freed even when it is the same backend object, as for a
        // content:// model on the shared llama.cpp backend: a backend only releases the
        // model it holds, and the RAM gate above priced only the new one. Without this
        // the old model stays resident while the new one is read in.
        if (previous != null) {
            loadedBackend = null
            previous.unload()
        }
        try {
            backend.load(
                ModelSpec(
                    // The backend opens this as a URI; see LlamaCppBackend.load.
                    id = model.uri.toString(),
                    displayName = model.displayName,
                    sizeBytes = model.fileSizeBytes,
                    parameterCount = model.parameterCount,
                    quantType = model.quantType,
                    supportedBackends = model.supportedBackends,
                    contextLength = loadContext,
                    kvCacheType = choice.kvCacheType,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return switchFailed(describeLoadFailure(t), before, previous, previousModel)
        }
        // Recorded HERE, at the only place the decision is made, and read by
        // newController. Previously the decision lived and died in this
        // function, so generation silently fell back to llama.cpp. Set only after
        // the load succeeds, so a failed load cannot leave it pointing at a
        // backend with nothing in it.
        loadedBackend = backend
        selectedModel = model
        // The name is the model's OWN `general.name` when the GGUF header has
        // one, falling back to a name derived from the file. For many real GGUFs
        // that fallback is all there is, and the field is documented as such
        // rather than implying more precision than exists. It is never taken
        // from the file the user tapped before the load, which is how a failed
        // load used to end up named after a model that was never opened.
        ModelAvailability.Ready(
            displayName = model.displayName,
            quantType = model.quantType,
        ).also {
            modelAvailability.set(it)
            startPrefixWork(backend)
        }
    } catch (e: CancellationException) {
        throw e
        } catch (t: Throwable) {
            switchFailed(describeLoadFailure(t), before, previous, previousModel)
        }
    }

    /**
     * A switch that did not take, and the state the chat is left in.
     *
     * - Nothing was resident before: the chat has no model, so the state is Failed.
     * - The previous model is still resident (the switch failed before anything was
     *   unloaded): the state goes back to what it was, and the chat keeps working.
     * - This switch unloaded the previous model: it is loaded again, because it loaded
     *   before and so fits again. The chat keeps working on it. A remote chat model has
     *   nothing to reload, so it is made resident again.
     *
     * [reason] is returned in every case, so the Models screen can say why the switch
     * did not happen. Only when no model can answer is the state set to Failed.
     */
    private suspend fun switchFailed(
        reason: String,
        before: ModelAvailability,
        previous: ModelBackend?,
        previousModel: ImportedModel?,
    ): ModelAvailability {
        val failed = ModelAvailability.Failed(reason)
        when {
            previous == null -> modelAvailability.set(failed)
            loadedBackend === previous -> modelAvailability.set(before)
            previous === remoteChatBackend || previous === chatGptBackend -> {
                loadedBackend = previous
                modelAvailability.set(before)
            }
            previousModel != null -> {
                // The reload sets the state itself: Ready when it works, Failed with its own
                // reason when it does not.
                loadModelLocked(previousModel)
            }
            else -> modelAvailability.set(failed)
        }
        return failed
    }

    /**
     * Ensures a model is resident, loading the selected one if needed.
     *
     * Idempotent, because [ExecutionService] calls it on every task and a
     * second 2 GB load for a task the user just sent would be both slow and a
     * reliable way to get OOM-killed.
     *
     * ## ALSO THE PERSISTENCE HOOK, DELIBERATELY
     *
     * This is the one suspend function that both entry points - a chat turn
     * and a scheduled task - already call on the run path, on a background
     * dispatcher, before the controller exists. So the conversation is
     * rehydrated here on the first run of a process (unless the launch restore got
     * there first) and written back on every run after it, without either
     * `ExecutionService` or `AgentController`
     * knowing that persistence exists. Both of those files are owned
     * elsewhere, and a feature that needed an edit in each would not be
     * landable next to them.
     *
     * The cost is a few milliseconds of SQLite against a model load measured
     * in seconds, on a thread that is already doing background work. Restoring
     * is once per process; the write is a handful of rows per turn.
     */
    suspend fun ensureModelReady(): ModelAvailability {
        // A new run: stop the between-messages work first. The prefill is
        // cancelled in the backend so the run does not wait for it, and the job
        // is joined so its compaction is finished before the run reads the session.
        stopIdleWork()
        // The prefix build is joined, not cancelled: this run needs the same prefix,
        // and cancelling would prefill those tokens again from zero.
        prefixWork?.join()
        return ensureModelReadyLocked()
    }

    /**
     * The prefix build started after a model load (see AgentController.warmPrefix).
     * [prefixBackend] is the backend it runs on: cancelling the job alone does not stop a
     * native prefill, which only the backend's cancel does, so the backend is cancelled first.
     */
    @Volatile
    private var prefixWork: Job? = null

    @Volatile
    private var prefixBackend: ModelBackend? = null

    /**
     * Starts the prefix build for [backend] on the process scope. Never holds
     * [readinessLock]: the build only reads the controller, and a run that arrives during it
     * joins it instead of waiting on the lock.
     */
    private fun startPrefixWork(backend: ModelBackend) {
        prefixBackend = backend
        prefixWork = appScope.launch {
            try {
                newController(model = backend).warmPrefix()
            } catch (e: CancellationException) {
                backend.cancel()
                throw e
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "prefix warm-up failed", t)
            }
        }
    }

    /**
     * Waits for the prefix build started by the last model load, if it is still running. The first-run
     * starter calls it so "Ready" means the first message can be answered at once. It never cancels the
     * build: a run that arrives meanwhile joins the same work.
     */
    internal suspend fun awaitPrefixWarm() {
        prefixWork?.join()
    }

    /**
     * Stops the prefix build before its model is unloaded or replaced, and waits for it to
     * end, so the backend is not used while it is being released. Callers may hold
     * [readinessLock]: the build never takes that lock, so waiting for it cannot deadlock.
     */
    private suspend fun stopPrefixWork() {
        val job = prefixWork ?: return
        if (job.isActive) {
            prefixBackend?.cancel()
            job.cancelAndJoin()
        }
        prefixWork = null
        prefixBackend = null
    }

    /**
     * The work done between messages (see AgentController.prepareNextTurn), on
     * the process scope so it outlives the run's service; one at a time.
     */
    @Volatile
    private var idleWork: Job? = null

    private fun startIdleWork(controller: AgentController, model: ModelBackend) {
        idleWork?.cancel()
        idleWork = appScope.launch {
            try {
                controller.prepareNextTurn()
            } catch (e: CancellationException) {
                model.cancel()
                throw e
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "idle preparation failed", t)
            }
        }
    }

    private suspend fun stopIdleWork() {
        val job = idleWork ?: return
        if (job.isActive) {
            residentBackend().cancel()
            job.cancelAndJoin()
        }
        idleWork = null
    }

    private suspend fun ensureModelReadyLocked(): ModelAvailability = readinessLock.withLock {
        restoreConversationOnce()
        persistConversation()
        // Reuse the RESIDENT identity, not a bare Ready: the early return is the
        // common path (every run after the first) and returning a nameless Ready
        // here would blank the model name out of the UI precisely when the model
        // is warm and running.
        if (modelAvailability.current.canRun) return@withLock modelAvailability.current
        loadSelectedModel()
    }

    /**
     * Serialises every model load and the readiness decisions around them. Not
     * reentrant: a function holding it calls the private helpers, never the public
     * functions that take it again.
     */
    private val readinessLock = Mutex()

    /**
     * Brings back what the app had at its last exit, before the chat is read:
     * the stored conversation, and the model the user last loaded.
     *
     * Without this the transcript starts empty, and the composer refuses to send
     * (`None` reads as "import a model") until the user picks the model again,
     * even though both are on disk. Returns the restored turns for the transcript.
     */
    suspend fun restoreOnLaunch(): List<ChatMessage> = readinessLock.withLock {
        restoreConversationOnce()
        // A scheduled run the last process died during has no result coming (#167).
        ScheduledRunReporter.settleInterrupted(context)
        if (!modelAvailability.current.canRun) loadSelectedModel()
        restoredTurns
    }

    /**
     * The launch restore, started once per process on [appScope] and shared by
     * every caller.
     *
     * WHY NOT A CALL FROM THE SCREEN: the screen's coroutine ends with its
     * composition, and a rotation during the native load would cancel it halfway.
     * The load belongs to the process, as a run does, so it runs on the scope that
     * outlives the screen and the screen only awaits it.
     */
    val launchRestore: Deferred<List<ChatMessage>> by lazy {
        appScope.async(Dispatchers.IO) { restoreOnLaunch() }
    }

    /**
     * Loads [selectedModel], or the model saved when it last loaded. The caller
     * holds [readinessLock].
     *
     * After a process death [selectedModel] is null and only the saved URI is
     * left, so the file is inspected again. If it can no longer be read the state
     * is [ModelAvailability.Failed] with the reason, not `None`: `None` would tell
     * the user to import a model they did import.
     */
    private suspend fun loadSelectedModel(): ModelAvailability {
        val model = selectedModel ?: run {
            val saved = savedModelUri
                ?: return ModelAvailability.None.also { modelAvailability.set(it) }
            val restored = try {
                withContext(Dispatchers.IO) { importer.inspect(android.net.Uri.parse(saved)) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return ModelAvailability.Failed(describeLoadFailure(t))
                    .also { modelAvailability.set(it) }
            }
            // Listed too, so the model screen names the model the chat is about to use.
            withContext(Dispatchers.Main) {
                models.removeAll { it.uri == restored.uri }
                models.add(restored)
            }
            restored
        }
        return loadModelLocked(model)
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
     *
     * WHY [turnRecorder] IS PASSED RATHER THAN THE LOOP FINDING THE STORE: the
     * loop is `:core` and the durable store is `:android`, so the loop has no way
     * to reach one and never will. Without this argument the agent can read
     * memory and cannot write any, which is the exact state this feature was in
     * for its entire life: `memoryStore` existed, `recordTurn` existed, and
     * nothing connected them, so the prompt's "Remembered facts" block was built
     * from a table nothing wrote to. It is a `TurnRecorder` rather than the store
     * itself so the loop depends on the one method it calls and not on a concrete
     * class from a package it does not own.
     *
     * NOTE WHAT IS *NOT* HERE: a `runGate` claim. [runGate] is process-wide and
     * has to be claimed around the whole run — including the model load that
     * happens before the controller exists — so it cannot be claimed from inside
     * `newController`, which returns long before a run starts. The claim belongs
     * at the two `ExecutionService.startRun` call sites; see [runGate].
     */
    /**
     * Scope for work that must outlive a single run.
     *
     * A [SupervisorJob] so one failed write cannot cancel unrelated work, and
     * [Dispatchers.IO] because every use is a database write. Deliberately
     * process-scoped rather than tied to a run: durable persistence is exactly
     * the work that has to finish even if the run that triggered it is
     * cancelled.
     */
    private val appScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun newController(
        model: ModelBackend = residentBackend(),
        metrics: RunRecorder? = null,
        onToken: ((String) -> Unit)? = null,
        /** A tool is about to run. Forwarded to the controller; see [AgentController]. */
        onToolStart: ((name: String, args: String) -> Unit)? = null,
        /** A tool has returned and the trace now holds it. Forwarded to the controller. */
        onToolDone: ((List<dev.localintelligence.core.agent.StepTrace>) -> Unit)? = null,
        /** A scheduled or background run: nobody is there to approve anything. */
        background: Boolean = false,
    ): AgentController {
        // Settings are frozen for the run, and the tool set is built from the
        // plugins switched on right now: a toggle applies from the next message.
        val snapshot = settings.snapshot()
        val pluginSet = PluginSet(plugins, snapshot, settings::isPluginEnabled)
        // No catalogue here: the enabled subset is smaller than the catalogue by design.
        // The full set is checked once, in `plugins`.
        // Simple mode sizes the prompt budget from the window the loaded model really has, bounded
        // by this CPU's speed; Technical keeps the user's values. A remote model is not bound by
        // this phone's CPU, so it keeps the table's values too.
        val config = snapshot.agentConfig().let { base ->
            val budget = if (settings.mode == SettingsMode.SIMPLE && model !== remoteChatBackend && model !== chatGptBackend) {
                SimpleSetup.promptBudget(deviceFacts, model.capabilities.contextLength)
            } else {
                null
            }
            if (budget == null) base else base.copy(
                workingTokenLimit = budget.workingTokenLimit,
                observationBudgetChars = budget.observationBudget,
            )
        }
        // Sandbox tools run on the user's cloud account, so they are offered only while the
        // remote chat model is the one answering this run (the same as remoteChatActive). A
        // small on-device model is not trusted with a computer that is billed to the user.
        val pluginTools = pluginSet.tools().filter { tool ->
            !tool.definition.name.startsWith(SANDBOX_TOOL_PREFIX) || model === remoteChatBackend || model === chatGptBackend
        }
        // With "Let the model look up tools" on, tools.find searches the same
        // enabled tools the run may call.
        val finder = if (config.toolLookup) {
            ToolFinder(SimpleToolRegistry(pluginTools, catalogue = emptyList()), LexicalToolSelector(), session)
        } else {
            null
        }
        val runTools: ToolRegistry = RedactingToolRegistry(
            SimpleToolRegistry(pluginTools + listOfNotNull(finder), catalogue = emptyList()),
        )
        return AgentController(
        onToken = onToken,
        onToolStart = onToolStart,
        onToolDone = onToolDone,
        log = { line -> android.util.Log.i("LocalAgent", line) },
        observationStore = observationStore,
        // Between messages, compact ahead and prefill while the answer is read.
        // Not for scheduled runs: nobody is about to type.
        afterAnswer = if (background) null else { controller -> startIdleWork(controller, model) },
        // Persist whenever the loop trims the window, not only at run
        // boundaries: a message removed by a mid-run fold is unrecoverable
        // afterwards. See AgentController.onWindowChanged.
        // The snapshot is taken synchronously on the calling thread before
        // the trim/fold drops messages from the window; the database write
        // is launched on appScope so it does not stall token production.
        onWindowChanged = {
            val pending = snapshotPendingMessages()
            if (pending.isNotEmpty()) {
                appScope.launch { writeSnapshot(pending) }
            }
        },
        model = model,
        parser = dev.localintelligence.core.agent.ActionParserImpl,
        tools = runTools,
        toolSelector = LexicalToolSelector(),
        validator = ToolCallValidatorGate.forRegistry(runTools),
        loopDetector = LoopDetector(),
        // The system prompt holds only what stays the same from message to
        // message (rules, name, tools); the clock is read once per run and goes
        // into the user turn after the task, for time-related tasks only, which the session stores as sent. A
        // prompt that changes near its start cannot reuse the previous
        // message's prefill (llama_jni.cpp).
        contextBuilder = java.time.ZonedDateTime.now().let { now ->
            val name = settings[CoreSettings.ASSISTANT_NAME]
            DefaultContextBuilder(
                systemPrompt = { tools, lines -> SystemPrompts.forTools(tools, name, lines) },
                // A safety net at the model's window: the agent loop compacts
                // well before this, by its own count in the model's tokens.
                workingLimit = model.capabilities.contextLength.takeIf { it > 0 } ?: config.workingTokenLimit,
                turnPreamble = { SystemPrompts.nowLine(now) },
            )
        },
        memory = memoryStore,
        // The shared conversation, not a fresh one. See [session] for why this
        // used to silently discard everything the model had already been told.
        sessions = session,
        config = config,
        riskPolicy = riskPolicy(snapshot, background),
        metrics = metrics,
        // The write half of memory. Bound here rather than inside the loop
        // because the loop is `:core` and only `:app` knows which store is
        // durable. `recordTurn` applies MemoryWritePolicy, so this costs one
        // regex per completed run and at most one row.
        //
        // It doubles as the conversation write hook. `AgentController` calls
        // this at `AgentAction.Respond`, which is after the reply has been
        // appended to the session, so mirroring the conversation here makes a
        // finished turn durable immediately. Waiting for the *next* run instead
        // would lose the last turn of any conversation the user ends by
        // backgrounding the app - the exact case this is meant to fix. It is
        // the same dependency-injection shape as the memory write for the same
        // reason: `:core` must not know that a database exists.
        turnRecorder = TurnRecorder { userText ->
            val memory = memoryStore.recordTurn(userText)
            // After, not before: `recordTurn` applies the write policy and
            // returns what it actually stored. Persisting the conversation
            // first would put a turn on disk that policy then declined to
            // remember - two stores disagreeing about the same turn.
            persistConversation()
            memory
        },
        sampling = snapshot.samplingParams(),
        hooks = pluginSet.hooks,
        constrainOutput = snapshot[CoreSettings.CONSTRAINED_OUTPUT],
        useModelTemplate = snapshot[CoreSettings.MODEL_CHAT_TEMPLATE],
        useNativeToolFormat = snapshot[CoreSettings.NATIVE_TOOL_FORMAT],
        thinkingBudget = snapshot[CoreSettings.THINKING_BUDGET],
    )
    }

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
     * The optional read token, typed in Settings (Technical mode) and kept
     * encrypted by [SettingsStore] like the other API keys. Null on a fresh
     * install, and every ungated download works without it — the gate is a
     * capability, not a requirement.
     *
     * Read with `stored`, not the mode-aware getter: the token must not vanish
     * in Simple mode, where the Simple table would answer "" for this row.
     */
    val hubTokenSource: HubTokenSource by lazy {
        HubTokenSource { settings.stored(CoreSettings.HF_READ_TOKEN).takeIf { it.isNotBlank() } }
    }

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
     * Where model downloads run. App-level, so a transfer is not ended by its screen
     * being cleared; [DownloadService] is started and stopped around it.
     */
    val hubTransfers: HubTransfers by lazy {
        HubTransfers(
            start = { DownloadService.start(context) },
            stop = { result -> DownloadService.stop(context, result) },
        )
    }

    /**
     * The hub ViewModel. One per screen instance. Leaving the screen mid-download
     * asks first and then stops it (MainActivity), so a download does not outlive
     * the screen that shows it; leaving the app does not stop it.
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
        contextLength = { hubContextLength(null) },
        contextLengthFor = ::hubContextLength,
        kvCacheType = ::hubKvCacheType,
        transfers = hubTransfers,
    )

    /**
     * The n_ctx a Hub download is priced at: the same function, with the same inputs, the model
     * load will use. With the file's [header] (the Hub probes it before a download) that is the
     * window the load will allocate; without one it is the Simple table's value.
     */
    private fun hubContextLength(header: GgufHeader?): Int = contextChoiceFor(header).nCtx

    private fun hubKvCacheType(header: GgufHeader?): KvCacheType = contextChoiceFor(header).kvCacheType

    /**
     * Simple mode's one-tap first run (see [StarterSetup]). App-scoped, so a download that the chat
     * started keeps its state while the user is on another screen.
     */
    val starterSetup: StarterSetup by lazy {
        StarterSetup(
            scope = appScope,
            suggestion = SimpleSetup.suggestedModel(deviceFacts),
            client = hubClient,
            downloader = hubDownloader,
            budget = hubBudget,
            tokenSource = hubTokenSource,
            transfers = hubTransfers,
            contextLength = { hubContextLength(null) },
            contextLengthFor = ::hubContextLength,
            kvCacheType = ::hubKvCacheType,
            availability = modelAvailability.state,
            adopt = { file -> adoptDownloaded(file) },
            awaitPrefix = { awaitPrefixWarm() },
        )
    }

    /**
     * Adopts a freshly downloaded file into the app's model list.
     *
     * This is the composition root's job because all three steps need the
     * container: read the header, add the model, and select it. Returning null
     * is a real outcome, not an error to swallow - it is how "the bytes landed
     * but the header will not parse" reaches the user instead of a download
     * that claims success and cannot be loaded.
     */
    suspend fun adoptDownloaded(file: java.io.File): DownloadedModelRegistrar.Registered? = withContext(Dispatchers.IO) {
        // The embedding model is not a chat model. Loading it as one would replace the chat
        // model with a model that cannot write replies, so it is only kept for document search.
        if (isDocumentModelName(file.name)) {
            return@withContext DownloadedModelRegistrar.Registered("Saved as the document search model.", chatReady = false)
        }
        val uri = android.net.Uri.fromFile(file)
        val model = runCatching { importer.inspect(uri) }.getOrNull() ?: return@withContext null
        // Listed BEFORE the load: a model that downloaded but would not start is still on
        // disk, and the Models screen has to show it instead of "No models yet".
        withContext(Dispatchers.Main) {
            models.removeAll { it.uri == model.uri }
            models.add(model)
        }
        val availability = loadModel(model)
        if (availability is ModelAvailability.Ready) {
            DownloadedModelRegistrar.Registered(availability.displayName, chatReady = true)
        } else {
            null
        }
    }

    /**
     * Lists every model file in the app's own models directory, without loading any.
     *
     * The list lives in memory, so after a restart only the saved model was in it and
     * a Simple user (who has no manual scan) lost their other downloads. This is a
     * directory listing plus a header read per file, cheap enough to run each time the
     * Models screen opens. A file whose header will not parse is skipped; the first such
     * error is returned so the manual scan can still report it.
     */
    suspend fun scanOwnModels(): Throwable? {
        val files = withContext(Dispatchers.IO) {
            modelsDir.listFiles()?.filter { file ->
                file.isFile && (
                    file.name.endsWith(".gguf", ignoreCase = true) ||
                        file.name.endsWith(".litertlm", ignoreCase = true)
                    ) && !isDocumentModelName(file.name)
            }
        } ?: return null
        var firstFailure: Throwable? = null
        for (file in files) {
            val uri = android.net.Uri.fromFile(file)
            if (models.any { it.uri == uri }) continue
            val model = try {
                withContext(Dispatchers.IO) { importer.inspect(uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (firstFailure == null) firstFailure = t
                continue
            }
            withContext(Dispatchers.Main) {
                models.removeAll { it.uri == model.uri }
                models.add(model)
            }
        }
        return firstFailure
    }

    /** True when [model] is a file inside the app's own models directory. */
    fun isOwnModelFile(model: ImportedModel): Boolean {
        val path = model.uri.takeIf { it.scheme == "file" }?.path ?: return false
        return File(path).parentFile?.canonicalPath == modelsDir.canonicalPath
    }

    /**
     * Forgets [model]; deletes its file when the app owns it.
     *
     * A file outside the models directory belongs to the user and is never deleted. If
     * the model is the one in use it is unloaded first, so the chat stops answering from
     * a model that was just removed and availability goes back to None.
     */
    suspend fun removeModel(model: ImportedModel) {
        readinessLock.withLock {
            val uriText = model.uri.toString()
            if (selectedModel?.uri == model.uri && loadedBackend !== remoteChatBackend && loadedBackend !== chatGptBackend) {
                stopPrefixWork()
                loadedBackend?.let { runCatching { it.unload() } }
                loadedBackend = null
                modelAvailability.set(ModelAvailability.None)
            }
            if (selectedModel?.uri == model.uri || savedModelUri == uriText) {
                selectedModel = null
            }
        }
        withContext(Dispatchers.Main) { models.removeAll { it.uri == model.uri } }
        if (isOwnModelFile(model)) {
            withContext(Dispatchers.IO) { runCatching { File(model.uri.path!!).delete() } }
        }
    }
}

/**
 * Supplies the durable [SessionStore], or null when none can be built.
 *
 * ## WHY THIS IS A SEAM AND NOT A DIRECT CALL
 *
 * `:app` provably cannot construct a Room store itself, and this is not a
 * style preference - it was verified by compiling the direct form. `:android`
 * declares `implementation(libs.androidx.room.runtime)` rather than `api`, so
 * `RoomDatabase` is absent from `:app`'s compile classpath, and every attempt
 * to reach through it fails at the compiler with:
 *
 *     Cannot access 'RoomDatabase' which is a supertype of
 *     'LocalIntelligenceDatabase'.
 *
 * That holds for the DAO accessors, for the `sessionStore()` extension, and
 * for the database type itself. So the store has to be built in the module
 * that owns Room and handed across as a `:core` interface - the same trick
 * `resilientMemoryStore` already uses for the memory store on the very same
 * database.
 *
 * ## THE REQUIRED EDIT, IN THE MODULE THAT OWNS ROOM
 *
 * This returns null until the following lands in `:android` (it is one small
 * function in a file this change does not own, so it is specified here rather
 * than applied):
 *
 * ```kotlin
 * // android/src/main/kotlin/dev/localintelligence/android/data/SessionStores.kt
 * package dev.localintelligence.android.data
 *
 * import android.content.Context
 * import dev.localintelligence.core.agent.SessionStore
 *
 * fun durableSessionStore(context: Context): SessionStore =
 *     LocalIntelligenceDatabase.build(context.applicationContext).sessionStore()
 * ```
 *
 * and then this object delegates to it:
 *
 * ```kotlin
 * object SessionStoreFactory {
 *     fun provide(context: Context): SessionStore? = runCatching {
 *         dev.localintelligence.android.data.durableSessionStore(context)
 *     }.getOrNull()
 * }
 * ```
 *
 * Returning null is the correct degraded mode, not a stub: the app keeps the
 * exact behaviour it had before this change (a conversation that lasts one
 * process) instead of failing to start, so the seam can land independently of
 * this wiring.
 */
object SessionStoreFactory {
    /**
     * The store to mirror the session into, or null if none is available.
     *
     * Resolved through reflection so that `:app` compiles and runs whether or
     * not the `:android` factory has landed yet, which is what makes the two
     * halves independently landable. Once the factory exists this becomes a
     * direct call; the indirection is a bridge, not an architecture.
     */
    fun provide(context: Context): SessionStore? = try {
        val type = Class.forName("dev.localintelligence.android.data.SessionStoresKt")
        type.getDeclaredMethod("durableSessionStore", Context::class.java)
            .invoke(null, context) as? SessionStore
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Absent factory, or a database that will not open. Either way the app
        // runs with an in-process conversation rather than not at all.
        android.util.Log.i("AppContainer", "No durable session store; conversation is process-local.", t)
        null
    }
}
