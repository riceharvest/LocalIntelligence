package dev.localintelligence.android.data

import android.content.Context
import android.util.Log
import dev.localintelligence.core.agent.InMemoryMemoryStore
import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.agent.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The store the app actually runs on: Room when it works, RAM when it does not.
 *
 * ## Why this class exists
 *
 * Room is lazy in a way that defeats the obvious wiring. `LocalIntelligenceDatabase.build`
 * only constructs a [dev.localintelligence.androidx.room.RoomDatabase] handle — it does
 * not open the file. The first statement that actually touches SQLite is the first DAO
 * call, which happens deep inside an agent run, on a background dispatcher, with no
 * `try` around it. So "did the database open?" cannot be answered at composition time
 * by calling `build()`, and a failure there would surface as a crash during a task the
 * user had already started — losing the run, not just the memory.
 *
 * This wrapper answers the question where it can actually be answered: on first use.
 * The first operation runs against the durable store inside a [Mutex]; if it throws,
 * the store is marked degraded and every operation from then on — including retries of
 * that same one — goes to [InMemoryMemoryStore]. A degraded process behaves exactly
 * like the pre-Room build did: memory works, memory does not survive a restart. That
 * is the intended failure mode, not a silent data-loss bug.
 *
 * ## What is and is not caught
 *
 * [CancellationException] is rethrown before the `catch`, because a cancelled agent run
 * is not a database failure and must not permanently poison the store for the life of
 * the process. Everything else is caught, deliberately including programming errors:
 * the cost of a wrong guess here is "memory is not durable on this device", and the
 * cost of not catching is "the app crashes when the user asks a question".
 *
 * ## Degraded is sticky, but not permanent across processes
 *
 * Once degraded, this instance stays degraded — a corrupt or unwritable file does not
 * fix itself mid-process, and re-probing would just re-throw on every call. The next
 * cold start builds a fresh container and probes again, so a transient fault (storage
 * mounted read-only, file removed by the user) heals on restart.
 *
 * ## Cost when healthy
 *
 * One [Mutex] and one boolean. The happy path adds no copy of the corpus: this holds no
 * [Memory] list, so the RAM argument in docs/architecture.md §16 is unchanged. The
 * fallback [InMemoryMemoryStore] is allocated but empty until something degrades.
 */
class ResilientMemoryStore(
    private val durable: () -> MemoryStore,
) : MemoryStore {

    /** The RAM store. Only ever written to once [degraded] is true. */
    private val fallback: MemoryStore = InMemoryMemoryStore()

    private val probe = Mutex()

    @Volatile
    private var degraded: Boolean = false

    /**
     * The store that is known to work, or null while the first call is still in flight.
     *
     * Read without the lock on the hot path. The write happens-before any subsequent
     * read of a non-null value because it is published through [degraded], which is
     * `@Volatile` — the volatile write in [degrade] happens-before every later volatile
     * read of [degraded], and this field is only ever published after that write.
     */
    @Volatile
    private var active: MemoryStore? = null

    /** True when this store has fallen back to RAM. Exposed for diagnostics and UI. */
    val isDegraded: Boolean get() = degraded

    /**
     * Runs [op] against a store that has been proven to work, degrading to RAM on the
     * first failure.
     *
     * The retry is deliberate. If the very first call is the one that discovers the
     * database is unusable, re-running it against [fallback] is what keeps that call
     * behaving like the pre-Room build instead of throwing at the caller: a `remember`
     * that failed still records the memory, a `search` that failed still returns RAM
     * results, a `forget` that failed still reports honestly.
     */
    private suspend fun <T> run(op: suspend (MemoryStore) -> T): T {
        active?.let { return op(it) }
        return probe.withLock {
            // Re-check inside the lock: another coroutine may have finished the probe
            // while this one waited, and repeating a first `remember` would duplicate
            // the row.
            active?.let { return@withLock op(it) }
            try {
                val candidate = durable()
                op(candidate).also { active = candidate }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                degrade(t)
                op(fallback)
            }
        }
    }

    /**
     * Marks the store degraded and records why.
     *
     * The cause is kept, not swallowed: "memory stopped persisting" is otherwise
     * indistinguishable from "the agent forgot", and the log line is the only evidence
     * a crash report would not have given us anyway.
     */
    private fun degrade(cause: Throwable) {
        degraded = true
        Log.w(
            TAG,
            "Durable memory unavailable, falling back to in-memory for this process. " +
                "Memory will not survive a restart. Cause: $cause",
            cause,
        )
    }

    override suspend fun remember(text: String, importance: Float): Memory =
        run { it.remember(text, importance) }

    override suspend fun search(query: String, limit: Int): List<Memory> =
        run { it.search(query, limit) }

    override suspend fun forget(id: Long): Boolean = run { it.forget(id) }

    override suspend fun all(limit: Int): List<Memory> = run { it.all(limit) }

    private companion object {
        const val TAG: String = "ResilientMemoryStore"
    }
}

/**
 * Builds the memory store the app should run on, from nothing but a [Context].
 *
 * This factory exists so that `:app` never names a Room type. `:android` declares
 * `androidx.room` as `implementation`, not `api`, so from `:app` the compiler cannot
 * resolve the `RoomDatabase` supertype of `LocalIntelligenceDatabase` — calling
 * `database.memoryStore()` there is a compile error, not just a style question. Rather
 * than widen `:android`'s dependency surface (or add Room to `:app`) to move one
 * expression across a module boundary, the whole assembly lives here, in the module
 * that owns Room.
 *
 * The database is built lazily on the first memory operation, not here, so a cold start
 * that never runs a task never creates the file.
 */
fun resilientMemoryStore(context: Context): MemoryStore =
    ResilientMemoryStore { LocalIntelligenceDatabase.build(context.applicationContext).memoryStore() }
