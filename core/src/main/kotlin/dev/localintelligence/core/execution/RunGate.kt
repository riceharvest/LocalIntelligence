package dev.localintelligence.core.execution

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One run at a time, for a process that can be asked to start two.
 *
 * ## Why this is needed, with the evidence
 *
 * The chat path and the scheduled-task path both end up in
 * `ExecutionService.onStartCommand`, and neither of them is serialised by the
 * service:
 *
 *  - `ExecutionService.serviceScope` is
 *    `CoroutineScope(SupervisorJob() + Dispatchers.Default)` (ExecutionService.kt:84).
 *    One scope, yes, but `Dispatchers.Default` is a thread POOL. A scope is a
 *    factory for coroutines, not a mutex: two `launch`es into it run
 *    concurrently on two threads.
 *  - `ExecutionService.startRun` builds a NEW `AgentViewModel` on every
 *    `ACTION_START` and overwrites `this.agent` (ExecutionService.kt:263-283).
 *    The per-instance guard that would stop it, `if (job?.isActive == true)
 *    return` (AgentViewModel.kt:105), tests a field of the instance that was
 *    JUST constructed and is therefore always null. It cannot fire.
 *  - `startForegroundService` on an already-running service delivers a second
 *    `onStartCommand` to the SAME instance. It does not serialise anything.
 *
 * So an alarm that fires while the user is mid-conversation starts a second
 * loop, and both write into the one `Session` the container hands out
 * (AppContainer.kt:150). The consequences are not theoretical: one run's
 * `sessions.clear()` at the top of the next run (AgentController.kt:268)
 * deletes the live run's window mid-generation, and a compaction firing in
 * either run folds the other's messages into its summary.
 *
 * ## What this does and does not do
 *
 * It REFUSES the second run and says so. It does not queue it, because a queue
 * is a work scheduler and this is a chat app: a scheduled task that fires while
 * the user is talking should be reported as not run, with a reason the user can
 * read, and the next tick of its own cadence will try again. `AgentConfig` has
 * no queue, the architecture has no scheduler (§17 defers deferred work to
 * WorkManager), and inventing one here would be a redesign.
 *
 * It also cannot be a `Mutex`. A `Mutex` suspends the caller, which is how you
 * accidentally turn a guard into a queue, and it needs a coroutine context the
 * service does not have at the point `onStartCommand` decides. This is a
 * compare-and-set: the loser is told immediately and returns.
 *
 * ## Scope
 *
 * Process-wide, because that is the actual contention domain. One model, one
 * session, one native llama context: two concurrent loops would not merely
 * interleave messages, they would call into the same JNI context at once.
 *
 * ## Cancellation
 *
 * Nothing here suspends, so there is no `CancellationException` to swallow. The
 * release is idempotent and is expected to run from a `finally`, so a run that
 * is cancelled, that throws, or that is torn down with its service still gives
 * the gate back. A gate that leaks on the cancellation path is the exact bug
 * this class is here to prevent, repeated one level up.
 */
class RunGate {

    private val busy = AtomicBoolean(false)

    /**
     * Claims the gate for one run.
     *
     * @return the claim, which the caller MUST release exactly once, or null
     *   when a run already holds it. Null is a real answer, not a failure: the
     *   caller turns it into a stated reason rather than dropping the message.
     */
    fun tryClaim(): Claim? {
        if (!busy.compareAndSet(false, true)) return null
        return Claim(this)
    }

    /** True while a run holds the gate. Read-only; does not claim. */
    val isBusy: Boolean get() = busy.get()

    private fun release() {
        busy.set(false)
    }

    /**
     * Proof that the gate is held, and the only way to give it back.
     *
     * A claim is not a token that can be copied and leaked: it is the one
     * object whose [close] releases, and it is `AutoCloseable` so the release
     * reads as a `use { }` at the call site rather than as a line someone can
     * forget on an early return.
     */
    class Claim internal constructor(private val gate: RunGate) : AutoCloseable {

        /**
         * Set by the first [close] only.
         *
         * WHY THIS FLAG AND NOT "a release is cheap, just do it twice": the
         * failure this prevents is claim A being closed twice, run B having
         * claimed the gate in between, and A's second close freeing B's claim.
         * C then starts while B is still decoding, which is the exact overlap
         * this class exists to make impossible - reached through a `finally`
         * that ran twice, which is the least surprising thing in the world.
         */
        private val released = AtomicBoolean(false)

        /**
         * Idempotent. A `finally` and an explicit release both firing is the
         * normal case rather than a bug, and the second one must be a no-op
         * instead of a release of somebody else's claim.
         */
        override fun close() {
            if (released.compareAndSet(false, true)) gate.release()
        }
    }
}
