package dev.localintelligence.core.execution

import dev.localintelligence.core.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The public entry point: run a tool call with a deadline and a token, and get
 * a [ToolResult] back. Never throws for a tool-level failure.
 *
 * This is the layer a tool or the agent loop uses. It composes two mechanisms
 * that cover different failure modes:
 *
 *  - [BoundedExecutor] handles a *blocking* body: a socket read that ignores
 *    coroutine cancellation. The deadline is enforced by a separate thread.
 *  - `withTimeout` handles a *suspending* body: a tool that is properly
 *    cooperative and will actually stop when cancelled. Cheaper and cleaner.
 *
 * WHY both: `withTimeout` alone fails silently on the one tool that matters most
 * (the one doing real network I/O), and a thread pool alone is wasteful for the
 * many tools that are a pure computation. Dispatching to IO and racing a waiter
 * thread would be correct but spends a thread on every call, including the ones
 * that return instantly.
 *
 * The residual risk of the blocking path is documented on [BoundedExecutor] and
 * is not hidden here: a thread blocked in a native socket read may survive the
 * interrupt, and the outcome says so.
 */
class BoundedToolExecutor(
    private val executor: BoundedExecutor = BoundedExecutor(),
    /**
     * Where a suspending tool body runs. `Dispatchers.IO` because a tool that
     * does blocking work on the caller's dispatcher is the bug this whole
     * package exists to prevent.
     */
    private val toolDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Threads the pool has lost to uninterruptible calls. Diagnostics. */
    val leakedThreadCount: Long get() = executor.leakedThreadCount

    /**
     * Runs [block] under a deadline and a token.
     *
     * Returns an outcome, never throws for a tool fault, a timeout, or a
     * cancel. The one exception that escapes is a `CancellationException` from
     * the *enclosing* coroutine scope, which is structured concurrency and
     * belongs to the caller.
     */
    suspend fun <T> run(
        toolName: String,
        token: CancellationToken = CancellationToken.none(),
        budget: ExecutionBudget = ExecutionBudget.DEFAULT,
        block: suspend () -> T,
    ): ExecutionOutcome<T> {
        // Checked before anything is dispatched: a cancel that arrived while the
        // loop was still deciding must not cost a thread hop.
        if (token.isCancelled) return ExecutionOutcome.Cancelled(token.reasonOrUnknown())

        return try {
            withContext(toolDispatcher) {
                kotlinx.coroutines.withTimeoutOrNull(budget.timeoutMs.coerceAtLeast(1)) {
                    try {
                        ExecutionOutcome.Succeeded(block())
                    } catch (e: OperationCancelledException) {
                        // Our own cooperative stop, not a coroutine cancellation:
                        // report it rather than letting it look like a tool bug.
                        ExecutionOutcome.Cancelled(e.reason)
                    }
                } ?: run {
                    // withTimeoutOrNull returned null: the suspending body overran
                    // its deadline. It was cancelled cooperatively, so no thread
                    // is stranded. The blocking pool is not involved on this path.
                    if (token.isCancelled) {
                        ExecutionOutcome.Cancelled(token.reasonOrUnknown())
                    } else {
                        ExecutionOutcome.TimedOut(budget.timeoutMs, abandoned = false)
                    }
                }
            }
        } catch (e: CancellationException) {
            // The enclosing scope died. Rethrow: this is the caller's decision,
            // and swallowing it here would break structured concurrency.
            throw e
        } catch (e: Throwable) {
            ExecutionOutcome.Failed(e::class.java.simpleName, e.message)
        }
    }

    /**
     * Runs a *blocking* body under a deadline, on the bounded pool.
     *
     * Use this for tool internals that block rather than suspend — a
     * `HttpURLConnection` read, a recursive file walk. The cost is a thread per
     * call, which is why [run] is the default for everything else.
     */
    fun <T> runBlocking(
        toolName: String,
        token: CancellationToken = CancellationToken.none(),
        budget: ExecutionBudget = ExecutionBudget.DEFAULT,
        block: () -> T,
    ): ExecutionOutcome<T> = executor.execute(block, token, budget, toolName)

    /** Convenience: [run] mapped straight to the frozen [ToolResult]. */
    suspend fun <T> runForResult(
        toolName: String,
        token: CancellationToken = CancellationToken.none(),
        budget: ExecutionBudget = ExecutionBudget.DEFAULT,
        block: suspend () -> T,
    ): ToolResult = run(toolName, token, budget, block).toToolResult(toolName)
}
