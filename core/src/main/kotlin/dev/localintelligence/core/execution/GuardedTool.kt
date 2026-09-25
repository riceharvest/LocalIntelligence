package dev.localintelligence.core.execution

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolResult
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Wraps any [AgentTool] so it runs under a deadline and a cancellation token.
 *
 * ## Why a decorator rather than a change to `AgentTool`
 *
 * `AgentTool`, `ToolContext` and `ToolResult` are frozen: every workstream
 * compiles against them and a signature change is a sign-off event
 * (`docs/tool-contract.md`). A decorator delivers the guarantee to tools that
 * already exist, today, without a single frozen type changing — and a tool that
 * has not been wrapped behaves exactly as before, which keeps the blast radius
 * of this package at zero for anything that does not opt in.
 *
 * ## Why the token rides the coroutine context
 *
 * Nested calls. A tool that calls another tool — or a tool that calls itself
 * recursively — must see the *same* cancellation tree, or a run-level cancel
 * stops the outer call and leaves the inner one reading a socket. The obvious
 * implementation, a `@Volatile` field on the wrapper, is wrong twice over: it
 * cannot express "this call is nested inside that one", and a second concurrent
 * call on the same instance clobbers it.
 *
 * Putting the token in the coroutine context fixes both. A nested call inherits
 * the enclosing token, and two concurrent calls each get their own copy. That is
 * why [TokenContext] is a `CopyableThreadContextElement`: without copying, every
 * `withContext` inside a tool would hand the child coroutine the *same* mutable
 * slot and re-introduce the clobbering this replaces.
 *
 * ## Why the token is also installed as `ToolContext.signal`
 *
 * `ToolContext` already carries a `CancellationSignal` and tools are already
 * told to check it. Rather than invent a parallel channel, the wrapper puts the
 * token *as* that signal, so a tool written against the old contract observes
 * the new one for free.
 */
class GuardedTool(
    private val delegate: AgentTool,
    private val executor: BoundedToolExecutor = BoundedToolExecutor(),
    private val budgetFor: (toolName: String) -> ExecutionBudget = { ExecutionBudget.DEFAULT },
) : AgentTool {

    /** The delegate's definition, verbatim. A guard must not change identity. */
    override val definition get() = delegate.definition

    /**
     * The token governing the call currently executing on this *coroutine*, or a
     * non-cancelling token outside a guarded call.
     *
     * Read by a nested guarded tool to inherit its parent, and available to a
     * tool that wants to hand the token to something it calls directly.
     *
     * A suspend function rather than a property because the token lives in the
     * coroutine context, and reading a coroutine context is a suspend
     * operation. Caching it in a field instead is exactly the clobbering bug
     * this class documents against.
     */
    suspend fun activeToken(): CancellationToken =
        currentCoroutineContext()[TokenContext.Key]?.token ?: CancellationToken.none()

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        // A caller may have marked the frozen signal already, or left the default
        // non-cancelling one. Both are honoured: if the incoming signal says
        // cancelled, that is the caller's decision and is not overridable.
        if (context.signal.isCancelled()) {
            return ExecutionOutcome.Cancelled("cancelled before the call started")
                .toToolResult(definition.name)
        }

        val budget = budgetFor(definition.name)
        val outcome = executor.run(definition.name, activeToken(), budget) {
            // Nested calls inherit this token: the inner wrapper finds it in the
            // context and derives a child, so one cancel reaches the whole tree.
            val token = activeToken()
            delegate.execute(args, context.copy(signal = token.asSignal()))
        }
        return outcome.toToolResult(definition.name)
    }

    /**
     * Runs this tool under [token], so a run-level cancel reaches a tool the
     * loop did not otherwise hand one to.
     *
     * The token is *not* cloned here: the caller owns it and may cancel it from
     * another thread. Cloning would make the wrapper's view and the caller's view
     * two different objects, which is precisely the bug [TokenContext] exists to
     * prevent.
     */
    suspend fun executeWith(
        args: ToolArgs,
        context: ToolContext,
        token: CancellationToken,
    ): ToolResult = withContext(TokenContext(token)) {
        execute(args, context)
    }

    /** A copy with a fixed budget, for callers that know a tool's real cost. */
    fun withBudget(budget: ExecutionBudget): GuardedTool =
        GuardedTool(delegate, executor) { budget }

    /**
     * The token carrier.
     *
     * `CopyableThreadContextElement` is the load-bearing part: it gives each
     * child coroutine its own instance, so `withContext` inside a tool cannot
     * clobber a sibling's token. The two overrides are the compiler-enforced
     * bookkeeping that makes that true.
     */
    class TokenContext(val token: CancellationToken) :
        ThreadContextElement<TokenContext?>,
        CopyableThreadContextElement<TokenContext?> {

        override val key: CoroutineContext.Key<*> get() = Key

        override fun updateThreadContext(context: CoroutineContext): TokenContext? = this

        override fun restoreThreadContext(context: CoroutineContext, oldState: TokenContext?) {
            // Nothing to restore: the token is immutable, and the context slot is
            // restored by the coroutine machinery itself.
        }

        override fun copyForChild(): TokenContext = TokenContext(token)

        /**
         * Required by [CopyableThreadContextElement] so a `withContext` that
         * installs a *different* token replaces rather than merges with this one.
         * Without it the coroutine machinery keeps both elements and the winner
         * would depend on key order — the kind of ambiguity that only shows up
         * under load, on a user's phone.
         *
         * Opted into deliberately: the alternative is dropping the override, which
         * compiles, leaves the ambiguity in place, and fails silently.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext =
            overwritingElement

        /** Identity key, so two different tokens are two different context slots. */
        object Key : CoroutineContext.Key<TokenContext>
    }
}
