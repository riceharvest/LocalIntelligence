package dev.localintelligence.core.execution

import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs a blocking call with a deadline and a cancellation token, and returns an
 * [ExecutionOutcome] instead of throwing.
 *
 * ## Why this is a thread pool and not `withTimeout`
 *
 * `withTimeout` cancels a coroutine, which only works if the coroutine is
 * suspended at a cancellable point. A tool doing a blocking `read()` on a
 * `HttpURLConnection` is not suspended — it owns a platform thread inside a
 * native syscall, and cancelling the coroutine that dispatched it changes
 * nothing about the socket. So the deadline is enforced by a *separate* thread
 * that waits, and the work is abandoned rather than cancelled.
 *
 * ## The honest part: abandonment is not killing
 *
 * [Thread.interrupt] sets a flag. A thread blocked in a socket read on Android
 * may never observe it, and even on the JVM a thread inside a native call often
 * does not. There is no safe way to stop such a thread — `Thread.stop` throws
 * `UnsupportedOperationException` on modern JVMs for good reason, and killing a
 * thread mid-write corrupts shared state.
 *
 * So this class does the only three things that are actually sound:
 *
 *  1. **Reports.** `abandoned = true` on the outcome, so a caller knows a
 *     thread is still out there. See [ExecutionOutcome.TimedOut.abandoned].
 *  2. **Bounds.** The pool is fixed-size with a bounded queue, so abandoned
 *     threads cannot multiply without limit. When they do exhaust it, calls are
 *     [ExecutionOutcome.Rejected] rather than queued forever — a fast, honest
 *     "no capacity" beats a silent wait.
 *  3. **Counts.** [leakedThreadCount] makes the residual risk observable. If it
 *     climbs on a device, that is a bug report with a number attached instead of
 *     a mystery ANR.
 *
 * The residual risk is therefore explicit and bounded: a wedged socket can cost
 * one pool thread, permanently, and the pool eventually refuses work. It cannot
 * hang the agent, because the waiter always returns at the deadline.
 */
class BoundedExecutor(
    /** Max concurrent blocking calls. Small on purpose: a phone has 4-8 cores. */
    private val maxWorkers: Int = DEFAULT_WORKERS,
    /**
     * How long a task may sit in the queue before it is rejected outright.
     * Zero means "reject when the pool is busy", which is the right default for
     * an agent that can always choose a different tool.
     */
    private val queueCapacity: Int = DEFAULT_QUEUE,
    private val clock: ExecutionClock = ExecutionClock.SYSTEM,
    /** Threads the pool spawns before it starts refusing work. */
    private val maxTotalThreads: Int = (maxWorkers + queueCapacity).coerceAtLeast(maxWorkers),
) {
    init {
        require(maxWorkers > 0) { "maxWorkers must be positive" }
    }

    private val threadCounter = AtomicInteger(0)
    private val completed = AtomicLong(0)

    private val pool = ThreadPoolExecutor(
        maxWorkers,
        // core == max: a phone agent wants predictable, bounded concurrency, not
        // elastic scaling that would spawn threads when it can least afford them.
        maxWorkers,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        // A zero-capacity queue is legal only as a SynchronousQueue; the
        // ArrayBlockingQueue constructor rejects 0 outright. Semantically the two
        // are what a caller asking for "no queue" means: hand the task straight
        // to a free worker or reject it.
        if (queueCapacity <= 0) SynchronousQueue() else ArrayBlockingQueue(queueCapacity),
        DaemonThreadFactory(threadCounter),
        // Fail fast. The default (CallerRunsPolicy) would run a blocking socket
        // read on the *agent's* thread, which is the exact ANR this class exists
        // to prevent.
        ThreadPoolExecutor.AbortPolicy(),
    )

    /**
     * Tasks we gave up on, keyed by the future that carries them.
     *
     * WHY this is a set rather than a counter incremented on the timeout path:
     * `FutureTask.cancel(true)` returns `true` whenever the task had not yet
     * completed at the instant we called it, which tells us nothing about
     * whether the thread then died. A thread that honours the interrupt and one
     * that ignores it are indistinguishable at that moment. So instead the task
     * is *tracked* here, and removes itself in a `finally` when it really ends.
     * The count therefore becomes a fact rather than a guess: it falls back to
     * zero for a call that stopped, and stays put for one that is still wedged
     * in a socket.
     */
    private val abandonedTasks: MutableSet<FutureTask<*>> =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(IdentityHashMap()))

    /**
     * Calls that were abandoned and are **still running right now**.
     *
     * Read this after a timeout has had a moment to settle: a non-zero value is
     * a thread holding a socket open on the device. It is the residual risk of
     * this design, made measurable — a bug report with a number attached instead
     * of a mystery ANR.
     */
    val leakedThreadCount: Long get() = abandonedTasks.size.toLong()

    /** Calls that finished normally. Diagnostic only. */
    val completedCount: Long get() = completed.get()

    /**
     * Blocks until no abandoned call is still running, or [timeoutMs] elapses.
     * Returns whether it quiesced.
     *
     * WHY this exists in production code: a graceful-shutdown path needs to know
     * whether the device is still holding sockets open before it declares itself
     * clean. A tool runtime that reports "done" while three threads are wedged in
     * `read()` is lying to whatever comes next — a process restart, a battery
     * reclaim, a bug report.
     *
     * The wait is a bounded poll rather than a condition variable because a task
     * is only ever *removed* from the set by its own `finally` on a different
     * thread, and the cost of being wrong here is one timed poll, not a deadlock.
     * In practice it returns on the first check: the interesting case is the one
     * that never quiesces, and that returns `false` on its own budget.
     */
    fun awaitAbandonedQuiescence(timeoutMs: Long = 1_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (abandonedTasks.isNotEmpty()) {
            if (System.nanoTime() >= deadline) return false
            // parkNanos rather than sleep: no interrupt handling, no 1ms floor,
            // and it yields the CPU instead of spinning a core on a phone.
            java.util.concurrent.locks.LockSupport.parkNanos(POLL_INTERVAL_NANOS)
            if (Thread.currentThread().isInterrupted) return false
        }
        return true
    }

    /**
     * Runs [block] under [token] and [budget], returning an outcome rather than
     * throwing.
     *
     * [block] is a *blocking* lambda on purpose: this is the seam for tools whose
     * internals are blocking (a socket read, a file walk). A suspending tool
     * should use [BoundedToolExecutor] instead, which adds coroutine
     * cancellation on top of this.
     *
     * Precedence, and it is deliberate: **cancel beats timeout**. If a user
     * presses stop and the deadline expires in the same millisecond, the run was
     * cancelled, and reporting a timeout would tell the model to try a different
     * approach when in fact the user wants it to stop entirely.
     */
    fun <T> execute(
        block: () -> T,
        token: CancellationToken = CancellationToken.none(),
        budget: ExecutionBudget = ExecutionBudget.DEFAULT,
        toolName: String = "tool",
    ): ExecutionOutcome<T> {
        if (token.isCancelled) return ExecutionOutcome.Cancelled(token.reasonOrUnknown())

        // A self-reference so the task's own finally can deregister itself. Held
        // in a one-element array because Kotlin lambdas cannot capture the val
        // they are assigned to, and a plain field would be a leak on a long-lived
        // executor.
        val self = arrayOfNulls<FutureTask<T>>(1)
        val task = FutureTask<T> {
            try {
                block()
            } finally {
                // If we already gave up on this task, it is genuinely finished
                // now. Removing it here is what makes leakedThreadCount a fact
                // rather than a running total of every timeout ever seen.
                self[0]?.let { abandonedTasks.remove(it) }
            }
        }
        self[0] = task
        val startedAt = clock.elapsedMillis()

        try {
            pool.execute(task)
        } catch (e: RejectedExecutionException) {
            // No capacity. Not a tool fault and not a slow tool: retrying into a
            // saturated pool is precisely the wrong move, hence Rejected.
            return ExecutionOutcome.Rejected(
                "all $maxWorkers tool threads are busy or stuck and none could be stopped",
            )
        }

        // Registered AFTER the task is queued so a cancel arriving in between
        // still wins: the post-registration re-check in onCancel fires it.
        val registration = token.onCancel { abandon(task) }

        val remaining = budget.remainingMs(startedAt, clock.elapsedMillis())
        return try {
            // An unbounded budget must not be turned into a millisecond count
            // and handed to get(): that converts "no limit" into a limit of
            // ~292 million years by way of nanosecond overflow arithmetic, which
            // is absurd but also exactly the sort of thing that silently becomes
            // a small number on some path. Wait for completion instead.
            val value = if (budget.unbounded) {
                task.get()
            } else {
                task.get(remaining, TimeUnit.MILLISECONDS)
            }
            completed.incrementAndGet()
            if (token.isCancelled) {
                // Finished, but the user asked to stop while it ran. Honesty:
                // the work happened, so this is not reported as success.
                ExecutionOutcome.Cancelled(token.reasonOrUnknown())
            } else {
                ExecutionOutcome.Succeeded(value)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            // We could not stop it, and we do not yet know whether it will stop
            // itself. Tracking it (rather than guessing) is what lets
            // leakedThreadCount later distinguish the two cases.
            abandonedTasks.add(task)
            abandon(task)
            val stillRunning = abandonedTasks.contains(task)
            ExecutionOutcome.TimedOut(
                budgetMs = budget.timeoutMs,
                abandoned = stillRunning,
                detail = toolName.takeIf { stillRunning }
                    ?.let { "the call to $it is still running" },
            )
        } catch (e: java.util.concurrent.CancellationException) {
            // task.cancel(true) won the race against our own get(). That happens
            // when a cancel arrives after the wait started.
            ExecutionOutcome.Cancelled(token.reasonOrUnknown())
        } catch (e: InterruptedException) {
            // The *waiter* was interrupted — not the task. Restore the flag:
            // swallowing it would hide a cancelled enclosing scope from code
            // upstream, which is how deadlocks become undebuggable.
            Thread.currentThread().interrupt()
            ExecutionOutcome.Cancelled("the waiting thread was interrupted")
        } catch (e: java.util.concurrent.ExecutionException) {
            // FutureTask wraps whatever the tool threw. The wrapper is an
            // implementation detail of the pool and must never reach the model:
            // "ExecutionException" is not a reason, "IOException" is.
            val cause = e.cause ?: e
            ExecutionOutcome.Failed(
                typeName = cause::class.java.simpleName,
                detail = cause.message,
            )
        } catch (e: Throwable) {
            // The tool threw. That is a normal, expected outcome, not a bug in
            // the runtime, and it must not escape into the agent loop.
            ExecutionOutcome.Failed(
                typeName = e::class.java.simpleName,
                detail = e.message,
            )
        } finally {
            registration.dispose()
        }
    }

    /**
     * Asks [task] to stop.
     *
     * `FutureTask.cancel(true)` interrupts the worker. Its return value is
     * deliberately ignored: it is `true` whenever the task had not completed at
     * the instant we asked, which says nothing about whether the thread then
     * died — and treating it as an answer is how a runtime ends up claiming it
     * killed a socket read that is still very much alive. The truth is
     * [abandonedTasks], which the task clears from its own `finally`.
     */
    private fun abandon(task: FutureTask<*>) {
        try {
            task.cancel(true)
        } catch (ignored: Throwable) {
            // A hostile SecurityManager can refuse. The task stays in
            // abandonedTasks, so the leak stays visible rather than being
            // silently misreported as a clean stop.
        }
    }

    /**
     * Daemon threads, always.
     *
     * WHY daemon: an abandoned thread blocked on a socket must never be the
     * reason the process refuses to exit. On Android that would turn a
     * recoverable wedged call into a "force stop" for the user.
     */
    private class DaemonThreadFactory(private val counter: AtomicInteger) : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "tool-exec-${counter.incrementAndGet()}").apply {
                isDaemon = true
                // Below default priority: the agent loop and the UI must win a
                // contended CPU. A tool is never more urgent than the frame.
                priority = Thread.NORM_PRIORITY - 1
            }
    }

    companion object {
        const val DEFAULT_WORKERS = 4
        const val DEFAULT_QUEUE = 4
        private const val KEEP_ALIVE_SECONDS = 30L

        /** 1ms between quiescence checks. Short enough to be invisible, long
         *  enough not to spin a core. */
        private const val POLL_INTERVAL_NANOS = 1_000_000L
    }
}
