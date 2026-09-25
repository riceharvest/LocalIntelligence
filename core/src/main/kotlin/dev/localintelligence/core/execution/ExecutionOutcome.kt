package dev.localintelligence.core.execution

import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult

/**
 * The three ways a bounded execution can end other than success, kept apart on
 * purpose.
 *
 * WHY this is a sealed type rather than a `success` boolean plus a string: a
 * model that cannot tell "retry" from "give up" retries forever, and it burns a
 * phone battery doing it. Each of these three outcomes implies a *different*
 * next action, and the observation text exists solely to make that action
 * unambiguous:
 *
 *  - [TimedOut]  → the operation was too slow. Retrying the identical call is
 *    the single most expensive mistake available, because the thing that was
 *    slow will still be slow. The text says so explicitly.
 *  - [Cancelled] → someone asked the run to stop. Retrying is not merely
 *    useless, it is *disobedient*: the user pressed stop. The text must make a
 *    1B model understand the run is over, not merely that this one call failed.
 *  - [Failed]    → the operation genuinely errored. This is the only case where
 *    a different attempt is a reasonable suggestion.
 *
 * Note the asymmetry: none of these carry a stack trace, and none of them
 * escape as exceptions. An exception unwinding the agent loop is a bug that
 * kills a step which should have recovered (`docs/tool-contract.md`).
 */
sealed interface ExecutionOutcome<out T> {

    /** The value, or the reason there is not one. */
    val value: T?

    /** What the model is told. This is the whole point of the type. */
    fun observation(toolName: String): String

    /** The typed error for [ToolResult], or null on success. */
    fun toToolError(): ToolError?

    /**
     * True when retrying the same call unchanged is pointless. The loop uses
     * this to avoid feeding a model a "try again" suggestion for a call that
     * cannot succeed differently.
     */
    val exhausted: Boolean

    /** Finished inside its budget. */
    data class Succeeded<T>(val result: T) : ExecutionOutcome<T> {
        override val value: T get() = result
        override val exhausted: Boolean get() = false
        override fun observation(toolName: String): String = result.toString()
        override fun toToolError(): ToolError? = null
    }

    /**
     * The operation exceeded its budget and was stopped.
     *
     * [abandoned] means **"not confirmed stopped at the deadline"** — the call
     * was still running when we gave up, so we cannot promise its thread is
     * gone. It is deliberately not a claim that the thread is *permanently*
     * wedged: a call that honours the interrupt dies microseconds later, and
     * the runtime has no way to know that at the instant it must return.
     *
     * That is why the observation discloses it ("may still be open") rather than
     * asserting it. The settled truth is `BoundedExecutor.leakedThreadCount`,
     * which the task clears from its own `finally` when it really ends.
     */
    data class TimedOut(
        val budgetMs: Long,
        val abandoned: Boolean = false,
        val detail: String? = null,
    ) : ExecutionOutcome<Nothing> {
        override val value: Nothing? get() = null

        /** A stop is not a verdict on the tool: it may work fine when retried. */
        override val exhausted: Boolean get() = false

        override fun observation(toolName: String): String = buildString {
            append("$toolName did not finish within ${budgetMs}ms and was stopped. ")
            append("It was too slow, not wrong. ")
            if (abandoned) {
                // Honesty over comfort. Telling the model the call was "stopped"
                // when a thread is still wedged in a socket read is a lie that
                // surfaces later as a mysteriously saturated thread pool.
                append("The underlying request could not be stopped cleanly, so its connection " +
                    "may still be open on the device. ")
            }
            append("Do not call $toolName again with the same arguments: it will be just as " +
                "slow. Use a different approach, or answer without it.")
            if (!detail.isNullOrBlank()) append(" ($detail)")
        }

        override fun toToolError(): ToolError = ToolError.Timeout(
            "exceeded ${budgetMs}ms budget" + if (abandoned) "; interrupt ignored" else "",
        )
    }

    /**
     * Someone asked the run to stop: the user, the loop, or an enclosing
     * deadline. Not an error and not a tool fault.
     */
    data class Cancelled(
        val reason: String,
        val abandoned: Boolean = false,
    ) : ExecutionOutcome<Nothing> {
        override val value: Nothing? get() = null

        /**
         * True on purpose. After a cancel the run is over, so *any* further
         * call this tool might make is a retry of a stopped run. Marking it
         * exhausted stops the loop from nudging a model toward another attempt.
         */
        override val exhausted: Boolean get() = true

        override fun observation(toolName: String): String = buildString {
            append("$toolName was stopped because the run was cancelled ($reason). ")
            append("This was not a fault in the tool and the result is unknown. ")
            append("The run is over: do not call $toolName again or start new work.")
            if (abandoned) {
                append(" The request could not be stopped cleanly and its connection may " +
                    "still be open on the device.")
            }
        }

        override fun toToolError(): ToolError = ToolError.Cancelled(reason)
    }

    /**
     * The operation threw. The only outcome for which a different attempt is a
     * reasonable thing to suggest.
     *
     * [typeName] rather than the exception itself: a `Throwable` in a
     * model-visible string risks leaking an Android class name or a message
     * containing a URL the user typed, and the stack trace is never useful to
     * the model.
     */
    data class Failed(
        val typeName: String,
        val detail: String? = null,
    ) : ExecutionOutcome<Nothing> {
        override val value: Nothing? get() = null
        override val exhausted: Boolean get() = false

        override fun observation(toolName: String): String = buildString {
            append("$toolName failed ($typeName)")
            if (!detail.isNullOrBlank()) append(": ${detail.take(120)}")
            append(". ")
            // The contract's "name the failure and what would fix it".
            append("This is a real error rather than a slow call. If the cause looks " +
                "temporary you may try once with different arguments; otherwise answer " +
                "without it.")
        }

        override fun toToolError(): ToolError = ToolError.Internal(
            typeName + (detail?.let { ": ${it.take(80)}" } ?: ""),
        )
    }

    /**
     * The runtime refused to start the call at all: the worker pool is
     * saturated by threads that ignored earlier interrupts, or the tool is not
     * allowed to run.
     *
     * WHY a separate case: it is neither a tool fault nor a slow tool, and
     * reporting it as [Failed] would tell the model to retry into a pool that is
     * already out of threads. Retrying is exactly wrong here.
     */
    data class Rejected(
        val reason: String,
    ) : ExecutionOutcome<Nothing> {
        override val value: Nothing? get() = null
        override val exhausted: Boolean get() = true

        override fun observation(toolName: String): String =
            "$toolName was not run ($reason). The device is out of capacity to start it, " +
                "and retrying now would fail the same way. Report this to the user rather " +
                "than trying again."

        override fun toToolError(): ToolError = ToolError.Unavailable(reason.take(120))
    }
}

/**
 * Renders any outcome as the frozen `ToolResult` the agent loop consumes.
 *
 * WHY this lives here and not in the loop: the loop's job is sequencing, and it
 * has no business knowing that a stop and a failure read differently. Keeping
 * the mapping in the package that owns the distinction means a tool cannot
 * accidentally report a stop as a crash, and the mapping is testable on its own.
 */
fun <T> ExecutionOutcome<T>.toToolResult(toolName: String): ToolResult = when (this) {
    is ExecutionOutcome.Succeeded -> ToolResult(
        success = true,
        observation = observation(toolName),
    )

    is ExecutionOutcome.Failed,
    is ExecutionOutcome.TimedOut,
    is ExecutionOutcome.Cancelled,
    is ExecutionOutcome.Rejected,
    -> ToolResult(
        success = false,
        observation = observation(toolName),
        error = toToolError(),
    )
}
