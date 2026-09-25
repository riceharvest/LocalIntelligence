package dev.localintelligence.core.execution

import dev.localintelligence.core.tool.CancellationSignal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A one-way, thread-safe "stop what you are doing" flag that the agent loop
 * raises and every layer of tool execution reads.
 *
 * WHY this exists rather than a bare `AtomicBoolean`: cancellation has to carry
 * a *reason*, and the reason is model-visible. A run stopped by the user and a
 * run stopped by a deadline are different outcomes that a model must be able to
 * tell apart, and a boolean cannot say which one happened. The first reason to
 * arrive wins and is then immutable — a second canceller is told the truth
 * rather than overwriting the record.
 *
 * WHY it is not `kotlinx.coroutines.CancellationException`: that type belongs to
 * structured concurrency, and `AgentController` deliberately rethrows it,
 * because a cancelled *coroutine* is the caller's business. A token here is a
 * cooperative stop request that a tool is expected to absorb and report as a
 * failed `ToolResult`. Conflating the two would make "the loop must survive
 * cancellation" untestable, and would make a user pressing stop look identical
 * to the surrounding scope being torn down. So [OperationCancelledException] is
 * a plain `RuntimeException` on purpose: no `catch (e: CancellationException)`
 * anywhere in the pipeline can swallow it by accident.
 *
 * Thread-safe and idempotent. [cancel] may be hammered from any number of
 * threads; listeners run exactly once each, and a listener that throws cannot
 * prevent the others from running.
 */
class CancellationToken private constructor() {

    /**
     * `null` until cancelled, then the winning reason, forever.
     *
     * An `AtomicReference` rather than a `@Volatile var` behind a lock because
     * the whole contract is "compare-and-set exactly once", and CAS is the only
     * primitive that says that without a second field to keep in sync.
     */
    private val reason = AtomicReference<String?>(null)

    /**
     * Copy-on-write: listeners are registered rarely (once per nested call) and
     * fired rarely, but *read* from whichever thread cancels. Iteration during a
     * concurrent add is then safe with no lock on the cancel path.
     */
    private val listeners = CopyOnWriteArrayList<Listener>()

    /**
     * True once [cancel] has won. Safe to call in a hot loop: this is the check
     * a tool performs at every point it can block.
     */
    val isCancelled: Boolean
        get() = reason.get() != null

    /**
     * Why the run stopped, or `null` while it is still running.
     *
     * Nullable precisely so a race with [cancel] cannot be papered over with a
     * silent default; use [reasonOrUnknown] when building model-visible text.
     */
    val cancelReason: String?
        get() = reason.get()

    /** [cancelReason] with a total fallback, for building model-visible text. */
    fun reasonOrUnknown(): String = reason.get() ?: DEFAULT_REASON

    /**
     * Raises the token. Returns `true` only for the thread that actually
     * transitioned it, which is what makes a multi-threaded `cancel()` storm
     * assertable: exactly one caller sees `true`.
     */
    fun cancel(why: String = DEFAULT_REASON): Boolean {
        if (!reason.compareAndSet(null, why.ifBlank { DEFAULT_REASON })) return false
        for (listener in listeners) {
            // A listener that throws must not rob the remaining listeners of
            // their cancellation; an abandoned nested call would then run on.
            try {
                listener.fire()
            } catch (ignored: Throwable) {
                // Deliberately swallowed; see above.
            }
        }
        return true
    }

    /**
     * Runs [block] the moment the token is cancelled, or immediately if it
     * already is. Returns a handle that undoes the registration.
     *
     * WHY a handle instead of fire-and-forget: a token outlives the call that
     * registered on it. Without an explicit dispose, a tool that finished
     * normally leaves a live listener behind, and a later cancel of a different
     * run would poke a dead worker.
     */
    fun onCancel(block: () -> Unit): Registration {
        val listener = Listener(block)
        // Register BEFORE re-checking. The reverse order is the classic
        // lost wakeup: cancel() lands between the check and the add, so the
        // listener is never invoked and the operation runs to completion.
        listeners.add(listener)
        if (isCancelled) {
            try {
                listener.fire()
            } catch (ignored: Throwable) {
                // Same contract as cancel().
            }
        }
        return Registration { listeners.remove(listener) }
    }

    /**
     * A token cancelled when this one is, and independently cancellable itself
     * without affecting the parent.
     *
     * WHY nesting is explicit: a tool that calls another tool needs its own flag
     * so "the inner call was abandoned" is not encoded in the outer run's
     * reason. Cancellation inherits *downward* only — a child cancelling must
     * never cancel the parent, which may be a perfectly healthy step that
     * simply decided not to make that call.
     */
    fun child(): CancellationToken {
        val child = CancellationToken()
        if (isCancelled) {
            child.cancel(reasonOrUnknown())
        } else {
            onCancel { child.cancel(reasonOrUnknown()) }
        }
        return child
    }

    /**
     * Throws [OperationCancelledException] if cancelled. Call this at every
     * point a tool can block, so a cancel lands between two I/O waits instead
     * of after the whole operation.
     */
    fun checkActive() {
        val why = reason.get()
        if (why != null) throw OperationCancelledException(why)
    }

    /**
     * Exposes this token through the frozen `CancellationSignal` seam that every
     * existing tool already consults, so a token-aware runtime can drive tools
     * written before this package existed.
     */
    fun asSignal(): CancellationSignal = CancellationSignal { isCancelled }

    /**
     * A registered listener that runs its body at most once, whoever gets there
     * first: [cancel] iterating the list, or [onCancel]'s post-registration
     * re-check. The CAS is what makes the lost-wakeup race impossible to lose
     * *and* impossible to double-fire.
     */
    private class Listener(private val body: () -> Unit) {
        private val fired = AtomicBoolean(false)

        fun fire() {
            if (fired.compareAndSet(false, true)) body()
        }
    }

    /**
     * Undo handle for [onCancel]. Idempotent, so it is safe in a `finally` that
     * may run after an explicit dispose.
     */
    fun interface Registration {
        fun dispose()
    }

    companion object {
        private const val DEFAULT_REASON = "cancelled by the runtime"

        /**
         * A token that is never cancelled.
         *
         * WHY a shared instance rather than a nullable token: every call site
         * would otherwise need a null check, and the null check is exactly where
         * a cancellation check gets forgotten. A non-cancelling token is the
         * same as no token but cannot be null-checked away by accident.
         */
        fun none(): CancellationToken = CancellationToken()
    }
}

/**
 * Thrown by [CancellationToken.checkActive], and converted by the guard into a
 * structured `Cancelled` outcome.
 *
 * Intentionally NOT a `CancellationException`: see [CancellationToken]. A
 * coroutine cancellation means the surrounding scope is dying and belongs to
 * the caller; this means "stop early and report it", and the agent loop is
 * required to survive it.
 */
class OperationCancelledException(val reason: String) :
    RuntimeException("operation cancelled: $reason")
