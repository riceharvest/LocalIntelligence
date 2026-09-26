package dev.localintelligence.core.model.token

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.tool.ToolDefinition

/**
 * A hard token ceiling for one inference call, plus the knowledge of what to do
 * when the context does not fit inside it.
 *
 * ## Why this is a type and not an `if`
 *
 * docs/architecture.md section 9 states a hard product constraint: "A routine
 * Android action should never require a 20K prefill." Until now that was a
 * sentence in a document. This makes it executable: a [ContextBudget] cannot be
 * constructed without a ceiling, and asking it what fits returns a structured
 * answer rather than a boolean.
 *
 * ## Why it never truncates silently
 *
 * The failure this exists to prevent is a builder that helpfully slices a tool
 * schema in half to make it fit. The model then sees a malformed JSON schema
 * and either hallucinates arguments or emits invalid output — and neither is
 * debuggable from a log line. [plan] therefore returns an ordered list of
 * [BudgetAction]s, each naming a specific component and a specific number of
 * tokens, and the caller executes them explicitly. If nothing can be dropped,
 * the plan is [impossible] and the caller is told the request cannot be served
 * at this ceiling — a loud, correct failure instead of a quiet corrupt prompt.
 *
 * ## Stateless
 *
 * Holds two Ints. No cache, no accumulation, no state between calls, so it is
 * safe to share and costs 16 bytes.
 */
class ContextBudget(
    /** The hard ceiling. The full prompt plus its framing must fit under it. */
    val limitTokens: Int,
    /**
     * Tokens held back for the model's reply.
     *
     * A prompt that exactly fills the window leaves the model no room to
     * answer, and the answer is the whole point. Default 256 — enough for the
     * concise reply architecture section 10 asks for, small enough not to halve
     * the working budget.
     */
    val reserveForOutputTokens: Int = DEFAULT_OUTPUT_RESERVE,
) {
    init {
        require(limitTokens > 0) { "limitTokens must be positive, was $limitTokens" }
        require(reserveForOutputTokens >= 0) {
            "reserveForOutputTokens must not be negative, was $reserveForOutputTokens"
        }
    }

    /** What the prompt itself may spend. This is the number callers enforce. */
    val promptTokens: Int = (limitTokens - reserveForOutputTokens).coerceAtLeast(1)

    /**
     * How much of [promptTokens] is already spoken for by parts that cannot be
     * traded away. Architecture sections 9 and 10: the system prompt and the
     * current task are the request itself. Everything else is optional in
     * principle, so this is the true floor of any prompt this budget builds.
     */
    fun floorFor(state: ContextState, counter: TokenCounter = DefaultTokenCounter): Int =
        counter.count(state.systemPrompt) + counter.count(state.task)

    /**
     * Whether a step fits, and — if it does not — exactly what to give up.
     *
     * Returns a [BudgetPlan] rather than a boolean because the useful answer to
     * "does this fit?" is not yes or no, it is "yes, and if you ever need the
     * room, drop these, in this order". A caller that only wants the boolean
     * uses [fits]; a caller about to build a prompt wants the plan.
     *
     * @param state what the prompt would contain
     * @param counter the estimator; defaults to the shared [HeuristicTokenCounter]
     */
    fun plan(
        state: ContextState,
        counter: TokenCounter = DefaultTokenCounter,
    ): BudgetPlan {
        val usage = state.measure(counter)
        val total = usage.totalTokens
        if (total <= promptTokens) {
            return BudgetPlan.Fits(total = total, limit = promptTokens)
        }

        val overage = total - promptTokens
        val drops = dropOrder(usage, overage)
        if (!drops.closedTheGap) {
            return BudgetPlan.Impossible(
                total = total,
                limit = promptTokens,
                overage = overage,
                // Even after every optional component is gone this still does
                // not fit: the request itself is larger than the window.
                irreducible = total - usage.droppableTokens,
            )
        }
        return BudgetPlan.Reduce(
            total = total,
            limit = promptTokens,
            overage = overage,
            actions = drops.actions,
        )
    }

    /** The cheap question. See [plan] for the useful one. */
    fun fits(state: ContextState, counter: TokenCounter = DefaultTokenCounter): Boolean =
        state.measure(counter).totalTokens <= promptTokens

    /** Unused headroom, floored at 0. Negative means over budget. */
    fun headroom(state: ContextState, counter: TokenCounter = DefaultTokenCounter): Int =
        promptTokens - state.measure(counter).totalTokens

    /**
     * The cheapest set of drops that closes [overage], in the order they should
     * be applied.
     *
     * The order is the product decision, and it is not "biggest first". It runs
     * from least-harmful to most-harmful, because the cheapest way to fit a
     * prompt is the one the user cannot tell you did it:
     *
     *  1. **Observations** — old tool results. The model already acted on them;
     *     a stale observation is worth less than a missing instruction.
     *  2. **Recent turns** — the conversation so far, oldest first. Reconstructable
     *     from the session, which outlives the window.
     *  3. **Memories** — facts, not instructions. Re-searchable from the store
     *     on the next step, at zero inference cost.
     *  4. **Tool definitions** — the expensive one, so it goes last. Dropping a
     *     tool is the only drop that can make an action *impossible*, which is
     *     why it is not first even though it frees the most tokens.
     *
     * Within observations and turns, the OLDEST goes first, always: recency is
     * what makes a context useful to a small model.
     *
     * The system prompt and the task are never in this list. A prompt without a
     * task is not a request, and architecture section 10 keeps the system
     * prompt deliberately tiny so that dropping it is never necessary.
     */
    private fun dropOrder(usage: ContextUsage, overage: Int): DropSequence {
        val out = ArrayList<BudgetAction>(5)
        var remaining = overage

        fun take(from: UsageBucket, label: String) {
            // `available` is local and decremented. It is tempting to re-read
            // the usage count each iteration, but nothing decrements it, so a
            // single observation would produce two "drop oldest observation"
            // actions and the caller would try to remove a message that is not
            // there.
            var available = usage.remainingIn(from)
            val one = usage.costOf(from)
            while (remaining > 0 && available > 0) {
                out += BudgetAction.DropOne(
                    component = from,
                    label = label,
                    tokens = one,
                )
                remaining -= one
                available--
            }
        }

        take(UsageBucket.OBSERVATION, "drop oldest observation")
        take(UsageBucket.TURN, "drop oldest recent turn")
        take(UsageBucket.MEMORY, "drop lowest-importance memory")
        take(UsageBucket.TOOL, "drop lowest-relevance tool definition")

        // `remaining > 0` here means everything droppable is gone and the
        // prompt still does not fit. Reported, not papered over.
        return DropSequence(actions = out, closedTheGap = remaining <= 0)
    }

    companion object {
        /**
         * Room held back for the reply.
         *
         * Architecture section 10: "When the task is complete, answer
         * concisely." 256 tokens is ~180 words, which is far more than a
         * concise answer needs and small enough that a [ContextCeiling] working
         * target still allows a prompt of nearly the whole limit.
         */
        const val DEFAULT_OUTPUT_RESERVE = 256

        /**
         * The working target as a budget, for a model with this window.
         *
         * The parameter is the loaded model's real context length, and it is
         * REQUIRED rather than defaulted. A default here is what made this
         * function a second source of truth: it used to return
         * `ContextBudget(6000)` with no model in sight, which is how a 6000
         * ceiling and a 4096 cache coexisted in one product. A caller that
         * cannot say how big the window is must pass
         * [ContextCeiling.FALLBACK_WINDOW_TOKENS] deliberately and be wrong on
         * purpose, rather than inherit a number that was never anybody's.
         *
         * @param modelContextTokens what the loaded model reports, or 0 for
         *   "does not know yet" — resolved by [ContextCeiling].
         * @param costCap the prefill cost cap. See [ContextCeiling.PREFILL_COST_CAP].
         */
        fun workingTarget(
            modelContextTokens: Int,
            costCap: Int = ContextCeiling.PREFILL_COST_CAP,
        ): ContextBudget = ContextCeiling.budget(modelContextTokens, costCap)
    }
}

/**
 * The shared estimator.
 *
 * One instance, no state, no allocation per call. Callers may pass their own
 * [TokenCounter] — most usefully a lambda over `ModelBackend.countTokens` when
 * a model is loaded — but the default exists so the common path allocates
 * nothing and cannot be wired up wrong.
 */
val DefaultTokenCounter: TokenCounter = HeuristicTokenCounter()

/**
 * Everything that could go into one prompt.
 *
 * Defaults are empty, so a caller that only cares about the system prompt and
 * the task writes two named arguments and gets a correct answer. That matters:
 * the whole point of this type is to be callable in a hot path, and a
 * constructor with seven required arguments gets skipped in favour of an
 * `if` in the loop.
 */
data class ContextState(
    /** The system prompt, tool list included. Never dropped. */
    val systemPrompt: String = "",
    /** The current task. Never dropped. */
    val task: String = "",
    /** Compacted working summary. Architecture section 12 slots. */
    val workingSummary: String = "",
    /** Retrieved memories, already rendered as the prompt lines. */
    val memories: List<String> = emptyList(),
    /** Conversation turns, oldest first. */
    val recentTurns: List<ChatMessage> = emptyList(),
    /** Tool result text fed back to the model, oldest first. */
    val observations: List<String> = emptyList(),
    /** Tool definitions, in the order the model will see them. */
    val tools: List<ToolDefinition> = emptyList(),
)

/**
 * The priced breakdown of a [ContextState].
 *
 * Computed once and then queried. The agent loop asks for this on every step,
 * so it is a value with Int fields rather than a Map: no hashing, no boxing,
 * no allocation beyond the object itself.
 */
class ContextUsage internal constructor(
    val systemPromptTokens: Int,
    val taskTokens: Int,
    val workingSummaryTokens: Int,
    val memoryTokens: Int,
    val turnTokens: Int,
    val observationTokens: Int,
    val toolTokens: Int,
    /** Item counts, so a drop plan can say "one of these" and mean it. */
    internal val turnCount: Int = 0,
    internal val memoryCount: Int = 0,
    internal val observationCount: Int = 0,
    internal val toolCount: Int = 0,
) {
    val totalTokens: Int = systemPromptTokens + taskTokens + workingSummaryTokens +
        memoryTokens + turnTokens + observationTokens + toolTokens

    /** Everything except the system prompt and the task. See [ContextBudget]. */
    val droppableTokens: Int = workingSummaryTokens + memoryTokens + turnTokens +
        observationTokens + toolTokens

    fun droppableComponentCount(): Int =
        (if (workingSummaryTokens > 0) 1 else 0) +
            (if (memoryTokens > 0) 1 else 0) +
            (if (turnTokens > 0) 1 else 0) +
            (if (observationTokens > 0) 1 else 0) +
            (if (toolTokens > 0) 1 else 0)

    /**
     * Tokens attributable to one bucket. For a single-item bucket this is also
     * the cost of dropping ONE of them, which is what a [BudgetAction.DropOne]
     * reports.
     */
    fun costOf(bucket: UsageBucket): Int = when (bucket) {
        UsageBucket.TASK -> taskTokens
        UsageBucket.TURN -> turnTokens
        UsageBucket.MEMORY -> memoryTokens
        UsageBucket.OBSERVATION -> observationTokens
        UsageBucket.TOOL -> toolTokens
        UsageBucket.SUMMARY -> workingSummaryTokens
    }

    /**
     * How many whole components of this kind could still be dropped.
     *
     * A summary is one component however long it is. Memories, turns,
     * observations and tools are one each. The task and the system prompt are
     * zero, which is the whole point: they are not droppable.
     */
    fun remainingIn(bucket: UsageBucket): Int = when (bucket) {
        UsageBucket.TURN -> turnCount
        UsageBucket.MEMORY -> memoryCount
        UsageBucket.OBSERVATION -> observationCount
        UsageBucket.TOOL -> toolCount
        // The task and the system prompt are never droppable, which is the
        // single most important line in this file.
        UsageBucket.SUMMARY -> if (workingSummaryTokens > 0) 1 else 0
        UsageBucket.TASK -> 0
    }

    override fun toString(): String =
        "ContextUsage(total=$totalTokens system=$systemPromptTokens task=$taskTokens " +
            "summary=$workingSummaryTokens memories=$memoryTokens turns=$turnTokens " +
            "observations=$observationTokens tools=$toolTokens)"
}

/**
 * The output of the drop-order walk: the actions, and whether they were enough.
 *
 * A private carrier rather than inferring success from `actions.size`, because
 * "did dropping everything actually fix it" is a different question from "how
 * many things did we list", and conflating them is how a trim loop ends up
 * believing it made progress.
 */
private class DropSequence(
    val actions: List<BudgetAction>,
    val closedTheGap: Boolean,
)

/** Which priced slot an action refers to. Kept small and closed on purpose. */
enum class UsageBucket {
    TASK,
    SUMMARY,
    MEMORY,
    TURN,
    OBSERVATION,
    TOOL,
}

/**
 * Price a [ContextState]. The only place that knows how the pieces add up.
 *
 * Split out from [ContextState.measure] so a caller that has already-priced
 * pieces (a loop that knows the tool set did not change) does not re-count
 * them. That is the difference between O(1) and O(prompt) per step.
 */
fun ContextState.measure(counter: TokenCounter = DefaultTokenCounter): ContextUsage {
    var turnTotal = 0
    for (i in recentTurns.indices) {
        turnTotal += counter.count(renderMessage(recentTurns[i]))
    }

    var observationTotal = 0
    for (i in observations.indices) observationTotal += counter.count(observations[i])

    var memoryTotal = 0
    for (i in memories.indices) memoryTotal += counter.count(memories[i])

    var toolTotal = 0
    for (i in tools.indices) toolTotal += counter.count(renderTool(tools[i]))

    return ContextUsage(
        systemPromptTokens = counter.count(systemPrompt),
        taskTokens = counter.count(task),
        workingSummaryTokens = counter.count(workingSummary),
        memoryTokens = memoryTotal,
        turnTokens = turnTotal,
        observationTokens = observationTotal,
        toolTokens = toolTotal,
        turnCount = recentTurns.size,
        memoryCount = memories.size,
        observationCount = observations.size,
        toolCount = tools.size,
    )
}

/**
 * The verdict.
 *
 * Three cases, not two, because "no" is not actionable. A caller needs to know
 * whether it can proceed as-is, can proceed after doing something specific, or
 * cannot proceed at all at this ceiling.
 */
sealed interface BudgetPlan {
    /** Fits as assembled. [headroom] is what is left. */
    val total: Int
    val limit: Int

    /** The prompt fits. Nothing to do. */
    data class Fits(override val total: Int, override val limit: Int) : BudgetPlan {
        val headroom: Int get() = limit - total
    }

    /**
     * Over budget, but fixable. [actions] is ordered: apply front to back, and
     * stop as soon as [headroom] is non-negative.
     */
    data class Reduce(
        override val total: Int,
        override val limit: Int,
        /** How far over the limit the assembled prompt is. Positive. */
        val overage: Int,
        val actions: List<BudgetAction>,
    ) : BudgetPlan

    /**
     * Over budget and unfixable by dropping.
     *
     * Reached when the system prompt plus the task alone exceed the window.
     * [irreducible] is what those two cost, so the caller can either raise the
     * ceiling, shorten the task, or fail — and know the size of the problem
     * rather than guessing.
     */
    data class Impossible(
        override val total: Int,
        override val limit: Int,
        val overage: Int,
        val irreducible: Int,
    ) : BudgetPlan
}

/**
 * One concrete thing to give up.
 *
 * [tokens] is the single component's cost, not a share of the overage, so a
 * caller can stop the moment it has freed [BudgetPlan.Reduce.overage] and does
 * not need to know how the arithmetic was split.
 */
sealed interface BudgetAction {
    /** The slot this action frees. */
    val component: UsageBucket
    /** Tokens freed by this one action. Always >= 1. */
    val tokens: Int

    /** Human-readable reason, for a log line or a debug overlay. */
    val label: String

    /** Remove one component — the oldest of its kind. See [ContextBudget]. */
    data class DropOne(
        override val component: UsageBucket,
        override val tokens: Int,
        override val label: String,
    ) : BudgetAction
}

/**
 * A view of a chat message as the model sees it.
 *
 * Small, because it is allocated per message per step, and it exists so the
 * token package never has to grow a dependency on the shape of the message
 * hierarchy. Adding a case to `ChatMessage` then cannot silently break
 * accounting in three other places.
 */
data class ChatMessageTokens(val rendered: String)

/**
 * A view of a tool definition as the model sees it.
 *
 * The schema is the expensive half. A tool with a three-line description and a
 * thirty-line JSON schema costs six times what its author expects, and it is
 * invisible in a prompt preview. Pricing the whole thing is what makes
 * "drop the cheapest tool" a decision the runtime can actually make.
 */
data class ToolTokenText(val rendered: String)

/**
 * Render a message the way the prompt would carry it.
 *
 * The role prefix is included because the model pays for it. A tool
 * observation is rendered as name + observation, matching
 * `DefaultContextBuilder`'s accounting so the two agree.
 */
fun renderMessage(message: ChatMessage): String = when (message) {
    is ChatMessage.System -> message.text
    is ChatMessage.User -> message.text
    is ChatMessage.Assistant -> message.text
    is ChatMessage.ToolObservation -> message.observation
}

/**
 * Render a tool definition the way a backend would serialize it.
 *
 * Deliberately close to `SystemPrompts.forTools` (name + description) plus the
 * fields a typed tool-call backend sends. The JSON schema goes through
 * [dev.localintelligence.core.tool.ToolDefinition]'s own `toString` because
 * `JsonObject.toString()` is already a compact, deterministic rendering — and
 * re-implementing JSON serialization to measure it would be a new bug surface
 * in the one component that must never be wrong about size.
 */
fun renderTool(definition: ToolDefinition): String = buildString {
    append(definition.name)
    append('\n')
    append(definition.description)
    if (definition.category.isNotEmpty()) {
        append('\n')
        append(definition.category)
    }
    if (definition.tags.isNotEmpty()) {
        append('\n')
        append(definition.tags.sorted().joinToString(" "))
    }
    append('\n')
    append(definition.schema)
}
