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
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.SessionStore
import dev.localintelligence.core.agent.ToolCallValidatorGate
import dev.localintelligence.core.agent.TurnRecorder
import dev.localintelligence.core.execution.RunGate
import dev.localintelligence.core.memory.LexicalMemoryStore
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
import dev.localintelligence.core.tool.redaction.RedactingToolRegistry

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
         * Preferences file holding the pinned conversation id.
         *
         * Its own file, separate from the model's, so that clearing a downloaded
         * model - a several-gigabyte operation - cannot silently orphan the
         * conversation, and clearing the conversation cannot un-resolve the
         * model. They are independent pieces of state with independent reasons
         * to be reset.
         */
        const val PREFERENCES = "localintelligence.conversation"

        /** Key for [PREFERENCES]: the [SessionStore] id of the conversation. */
        const val KEY_SESSION_ID = "session_id"
    }

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
    val tools: ToolRegistry by lazy {
        // Wrapped, not replaced: every observation the loop sees is filtered
        // here, at the moment it is born, BEFORE the model reads it. Filtering
        // after the answer would leave the model holding a secret the transcript
        // no longer shows, which is worse than no filter at all - the log would
        // misdescribe what actually happened, in the direction that looks like
        // safety. The decorator delegates everything, so catalogue agreement and
        // risk tiers still run on the real registry underneath.
        RedactingToolRegistry(SimpleToolRegistry(androidTools(context)))
    }

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
     * THE GATE IS NOT YET CLAIMED ANYWHERE. `RunGate` and `RUN_ALREADY_ACTIVE_REASON`
     * exist in `core/execution` and nothing calls them: `grep -rn 'tryClaim\|RunGate'`
     * outside of that file returns the class and nothing else. The two claim sites are
     * `ExecutionService.startRun` and `ScheduledTaskFireReceiver.startRun`, both in
     * `:app` and both owned by another agent, so the exact edits are in the PR
     * description rather than in this branch. This property is the piece they need: one
     * instance, reachable from both, so the two claims contend with each other instead
     * of each holding a private gate that never fires.
     */
    val runGate: RunGate by lazy { RunGate() }

    /**
     * The conversation, kept across runs and - since this change - across
     * process death.
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
    @Volatile
    private var durableWriteCount: Int = 0

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
        // re-append it or the next run would double the history.
        durableWriteCount = session.appendedCount
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
    suspend fun persistConversation() {
        val store = sessionStore ?: return
        val produced = session.appendedCount
        val newMessages = produced - durableWriteCount
        if (newMessages <= 0) return
        val id = try {
            durableSessionId(store)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Could not open a durable session; skipping write.", t)
            return
        }
        // A trim only ever removes from the front, so the newest `newMessages`
        // entries of the window are exactly the ones not yet written.
        val pending = session.messages.takeLast(newMessages)
        try {
            withContext(Dispatchers.IO) {
                for (message in pending) store.appendMessage(id, message)
            }
            durableWriteCount = produced
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Losing a write is survivable in a way failing the run is not: the
            // answer has already been produced and shown. Leave the count
            // alone so the next run retries these rows.
            android.util.Log.w(TAG, "Could not persist the conversation; will retry next run.", t)
        }
    }

    @Volatile
    private var restoredOnce: Boolean = false

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
        // The name is the model's OWN `general.name` when the GGUF header has
        // one, falling back to a name derived from the file. For many real GGUFs
        // that fallback is all there is, and the field is documented as such
        // rather than implying more precision than exists. It is never taken
        // from the file the user tapped before the load, which is how a failed
        // load used to end up named after a model that was never opened.
        ModelAvailability.Ready(
            displayName = model.displayName,
            quantType = model.quantType,
        ).also { modelAvailability.set(it) }
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
     *
     * ## ALSO THE PERSISTENCE HOOK, DELIBERATELY
     *
     * This is the one suspend function that both entry points - a chat turn
     * and a scheduled task - already call on the run path, on a background
     * dispatcher, before the controller exists. So the conversation is
     * rehydrated here on the first run of a process and written back on every
     * run after it, without either `ExecutionService` or `AgentController`
     * knowing that persistence exists. Both of those files are owned
     * elsewhere, and a feature that needed an edit in each would not be
     * landable next to them.
     *
     * The cost is a few milliseconds of SQLite against a model load measured
     * in seconds, on a thread that is already doing background work. Restoring
     * is once per process; the write is a handful of rows per turn.
     */
    suspend fun ensureModelReady(): ModelAvailability {
        restoreConversationOnce()
        persistConversation()
        // Reuse the RESIDENT identity, not a bare Ready: the early return is the
        // common path (every run after the first) and returning a nameless Ready
        // here would blank the model name out of the UI precisely when the model
        // is warm and running.
        if (modelAvailability.current.canRun) return modelAvailability.current
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
