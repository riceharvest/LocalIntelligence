package dev.localintelligence.core.model.token

/**
 * The question the agent loop actually needs answered, once per step:
 *
 * > "Does the next step fit, and if not, what is the single cheapest thing I
 * > can give up to make it fit?"
 *
 * ## Why this is separate from [ContextBudget]
 *
 * [ContextBudget] knows what a prompt costs. It does not know what the *next*
 * step adds, which is the harder half: a model that wants to call a tool with
 * 2KB of arguments, a tool that will return 2KB of observation, and a summary
 * that is about to be regenerated all land on the same step. Deciding that
 * once, up front, in one call is what stops a 1B model from spending four
 * seconds on a prefill that was always going to overflow.
 *
 * ## The commitment it makes
 *
 * [evaluate] is a pure function of its arguments. It does not mutate anything,
 * does not call the model, and does not decide policy the caller has not given
 * it. What it commits to is arithmetically checkable: the tokens returned in
 * [StepPlan.projectedTokens] are an upper bound on what the step can cost if
 * the caller applies [StepPlan.adjustments], which means a caller that follows
 * the plan cannot overflow the window even if its real token counts come in
 * higher than estimated.
 *
 * ## Degenerate inputs
 *
 * A pathological observation (a 4MB logcat dump) is a real possibility on
 * Android and a memory hazard in a per-step budget check. Every candidate is
 * clipped to a stated ceiling before pricing, so the check itself stays cheap
 * and bounded regardless of what a tool returns — and a candidate over the
 * ceiling is truncated, never rejected, because the runtime needs an answer
 * more than it needs an exception.
 *
 * ## What "upper bound" is arithmetic, and what it is not
 *
 * The bound holds for the STEP: [StepPlan.projectedTokens] counts the reply,
 * the arguments, the observation and the summary the step is about to add, and
 * the caller is told to apply [StepPlan.adjustments] when it does not fit.
 *
 * It is NOT a proof that the adjustments, applied once, close the gap, and the
 * loop must not treat it as one. [ContextBudget] prices a drop as the cost of
 * its whole bucket ([ContextUsage.costOf] returns the bucket total), so N
 * planned drops of a bucket of size B claim to free N x B when dropping one
 * item frees roughly B/N; and the state [evaluate] re-plans against
 * (`stateAfter`) does not contain the reply or the arguments at all, so its
 * overage is smaller than [StepPlan.overage]. A caller that wants certainty
 * re-evaluates after applying, which is one line and is what makes the claim
 * true in practice.
 */
class StepEnforcer(
    private val budget: ContextBudget,
    private val counter: TokenCounter = DefaultTokenCounter,
) {
    /**
     * Price a whole proposed step and say what to do about it.
     *
     * @param current the context as it stands
     * @param proposed the next step's content: the reply slot, the tool
     *   arguments, the observation that comes back, the new summary
     */
    fun evaluate(
        current: ContextState,
        proposed: ProposedStep = ProposedStep(),
    ): StepPlan {
        val base = current.measure(counter)
        val currentTotal = base.totalTokens

        val reply = counter.count(clip(proposed.replyCandidate))
        val arguments = counter.count(clip(proposed.toolArguments))
        // An observation is the model's next input, so it is budgeted NOW even
        // though the tool has not run yet. Discovering the overflow after the
        // tool call is exactly the expensive case.
        val observation = counter.count(clip(proposed.observationCandidate))
        val summary = counter.count(clip(proposed.summaryCandidate))
        val delta = reply + arguments + observation + summary
        val projected = currentTotal + delta

        if (projected <= budget.promptTokens) {
            return StepPlan.Fits(
                currentTokens = currentTotal,
                addedTokens = delta,
                projectedTokens = projected,
                limit = budget.promptTokens,
            )
        }

        val overage = projected - budget.promptTokens
        val stateAfter = current.copy(
            workingSummary = if (summary > 0) proposed.summaryCandidate else current.workingSummary,
            observations = current.observations + listOfNotNull(
                proposed.observationCandidate.takeIf { it.isNotEmpty() },
            ),
        )
        val reduce = budget.plan(stateAfter, counter)
        val adjustments = when (reduce) {
            is BudgetPlan.Fits -> emptyList()
            // The step alone overflows, before any trimming of the existing
            // context could help. The reply and the tool arguments are not
            // droppable: they ARE the step. Say so instead of inventing a
            // plan that does not exist.
            is BudgetPlan.Impossible -> emptyList()
            is BudgetPlan.Reduce -> reduce.actions
        }

        return StepPlan.Trim(
            currentTokens = currentTotal,
            addedTokens = delta,
            projectedTokens = projected,
            limit = budget.promptTokens,
            overage = overage,
            adjustments = adjustments,
        )
    }

    /** The one-line version. See [evaluate] for the one with the answers in it. */
    fun fits(
        current: ContextState,
        proposed: ProposedStep = ProposedStep(),
    ): Boolean = evaluate(current, proposed) is StepPlan.Fits

    /**
     * The cheapest single reduction that makes the step fit.
     *
     * This is the shape a loop wants at the top of `repeat(maxSteps)`: one
     * number, or null. Null means "nothing you can drop will help", which the
     * loop should treat as a stop condition rather than retrying — retrying a
     * step that provably cannot fit is how an agent burns a battery in a loop.
     */
    fun cheapestReduction(
        current: ContextState,
        proposed: ProposedStep = ProposedStep(),
    ): CheapestReduction? {
        val plan = evaluate(current, proposed)
        if (plan !is StepPlan.Trim) return null
        val first = plan.adjustments.firstOrNull() ?: return null
        return CheapestReduction(
            component = first.component,
            tokens = first.tokens,
            description = first.label,
        )
    }

    /**
     * Cuts one candidate down to the stated ceiling before it is priced.
     *
     * WHY CLIPPING AND NOT REJECTING: a 4MB logcat dump is a real observation
     * on Android and a real reply from a model that will not stop talking.
     * [ProposedStep] used to `require` every candidate under the ceiling, so
     * the defensive path threw at exactly the moment the runtime needed an
     * answer — a budget check that dies on the input it exists to price. The
     * ceiling is now unskippable (it is applied here, in the only place a
     * candidate is read) and the failure mode is a truncated price instead of
     * an exception. Clipping also keeps the check O(ceiling) rather than
     * O(candidate), which is the memory bound the class KDoc promises.
     */
    private fun clip(candidate: String): String =
        if (candidate.length <= MAX_CANDIDATE_CHARS) candidate
        else candidate.take(MAX_CANDIDATE_CHARS)

    companion object {
        /**
         * Ceiling on any single proposed blob, in characters.
         *
         * 16KB is ~4-8K tokens depending on content, which is larger than any
         * legitimate tool argument or observation in this system and small
         * enough that pricing it is microseconds. Above this the runtime
         * should be truncating at the source anyway — architecture section 7
         * caps `ToolResult.observation` at 2048 characters.
         *
         * Applied by [evaluate] before pricing, so no caller can skip it by
         * forgetting and no caller can be punished for exceeding it.
         */
        const val MAX_CANDIDATE_CHARS = 16 * 1024
    }
}

/**
 * The content one step is about to add to the context.
 *
 * All fields optional and defaulting to empty, because most steps add only one
 * of them: a plain reply adds a [replyCandidate], a tool call adds
 * [toolArguments] and an [observationCandidate]. Requiring all four would mean
 * passing empty strings at six call sites, and empty strings that get counted
 * as real content are exactly the kind of off-by-a-few-hundred that a budget
 * type is supposed to prevent.
 */
data class ProposedStep(
    /**
     * Text the model has produced this step.
     *
     * Counted because a runaway 1B model that will not stop talking must be
     * caught BEFORE the next prefill, not after.
     */
    val replyCandidate: String = "",
    /** Serialized tool-call arguments, before the tool runs. */
    val toolArguments: String = "",
    /**
     * Observation the tool will return.
     *
     * Caller-supplied estimate, not the real thing — the tool has not run.
     * When the real observation arrives, re-evaluate with it; the loop does
     * that anyway because it must re-budget after every tool call.
     */
    val observationCandidate: String = "",
    /** The summary the compactor would write after this step. */
    val summaryCandidate: String = "",
) {
    /** True when this step adds nothing. A no-op step still costs the check. */
    val isEmpty: Boolean
        get() = replyCandidate.isEmpty() && toolArguments.isEmpty() &&
            observationCandidate.isEmpty() && summaryCandidate.isEmpty()
}

/**
 * What [StepEnforcer.evaluate] concluded.
 *
 * Every case carries the arithmetic, so a caller can log the numbers without
 * re-running the estimator — and a test can assert the plan is self-consistent
 * without a second implementation of the arithmetic to check it against.
 */
sealed interface StepPlan {
    val currentTokens: Int
    val addedTokens: Int
    val projectedTokens: Int
    val limit: Int

    /** Room left after this step. Negative means over. */
    val headroom: Int get() = limit - projectedTokens

    /** The step fits. [headroom] is what is left for the next one. */
    data class Fits(
        override val currentTokens: Int,
        override val addedTokens: Int,
        override val projectedTokens: Int,
        override val limit: Int,
    ) : StepPlan

    /**
     * The step does not fit. [adjustments] is what to drop, in order.
     *
     * Empty adjustments with a positive [overage] means the step itself is
     * larger than the window and no amount of trimming will save it. That is a
     * stop condition, and it is reported rather than hidden behind a
     * never-enough plan.
     */
    data class Trim(
        override val currentTokens: Int,
        override val addedTokens: Int,
        override val projectedTokens: Int,
        override val limit: Int,
        val overage: Int,
        val adjustments: List<BudgetAction>,
    ) : StepPlan {
        /** True when no drop can help: the step alone is over the limit. */
        val unfixable: Boolean get() = adjustments.isEmpty()
    }
}

/**
 * The single cheapest fix, for callers that want one number and a decision.
 *
 * Returned instead of a boolean from [StepEnforcer.cheapestReduction] because
 * the agent loop's next branch depends on WHICH thing goes, not merely that
 * something does.
 */
data class CheapestReduction(
    val component: UsageBucket,
    val tokens: Int,
    /** For logs and a debug overlay. Not a prompt. */
    val description: String,
)
