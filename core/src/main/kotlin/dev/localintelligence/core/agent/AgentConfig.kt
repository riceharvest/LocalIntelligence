package dev.localintelligence.core.agent

import dev.localintelligence.core.model.token.ContextCeiling
import dev.localintelligence.core.tool.ObservationTruncator

/**
 * Every budget the loop enforces.
 *
 * Each number is a deliberate v0 choice from `docs/agent-loop.md`, not a
 * "reasonable default". Raise one only with eval evidence
 * (`docs/architecture.md` §19). No `require` block here on purpose: an
 * invalid budget must degrade the loop, not throw out of a constructor, because
 * `run()` is contractually incapable of throwing.
 */
data class AgentConfig(
    /** 2-10 reliable actions is the v0 target. Past that, loop quality collapses. */
    val maxSteps: Int = 8,
    /**
     * How many tools the model may call this turn. The single most consequential
     * number in this file, because the selected set is what BUILDS the grammar
     * and an unselected tool is not merely discouraged — it is unspeakable.
     * Selection accuracy is therefore an upper bound on task success, not a
     * component of it.
     *
     * ## Why 10 and not the 6 this shipped until now
     *
     * 6 -> 10 measured, on the 176-case dataset in
     * `core/tool/eval/SelectorDataset.kt` against the 25 tools `:android` ships
     * (run `core/tool/eval/run-recall-harness.sh`; the harness re-derives every
     * number below from the live selector, so if this comment and the harness
     * ever disagree the harness is right):
     *
     * | visible tools | tasks made possible | mean system-prompt tokens |
     * |---------------|---------------------:|-------------------------:|
     * | 6 (previous)  |            171/176  |  411                     |
     * | **10 (now)**  |        **176/176**  |  **516**                 |
     * | 12            |            176/176  |  566                     |
     * | all 25        |            176/176  |  894                     |
     *
     * **176/176 means this metric is saturated, not that the ceiling is
     * proven.** The tag lists were repaired while reading these 176
     * utterances, so the last row is a ceiling that has been met rather than
     * a generalisation estimate. The independent check is the held-out probe
     * in `core/tool/holdout/`, 25 utterances written after the tags were
     * frozen: it goes 20/25 -> 22/25. That is a real gain and a modest one.
     *
     * **The width argument is now much weaker than it was, and this constant
     * is kept for that reason rather than because the case is strong.** At
     * k=3 recall is already 164/176 (93.2%) and k=6 is 171/176 (97.2%), so
     * most of what 6 -> 10 bought has been bought back by fixing the tags
     * instead. The unmeasured risk below is the only thing still arguing for
     * 10 over 6, and it is unmeasured. If someone measures that risk and it
     * does not bite, 6 is the better number.
     *
     * Nine tasks in a hundred and seventy-six stop being performable at all
     * at k=6, for 103 prompt tokens against a 6000-token working limit. A task
     * that is not performable is worth more than 103 tokens of prefill, and the
     * model is spending thousands of tokens on the working context anyway.
     * (That comparison is from the pre-tag-fix measurement and no longer
     * decides anything — see the note above on the flattened curve.)
     *
     * **12 buys two more cases for 49 tokens and is deliberately not taken.**
     * The tie rate is the reason: at k=10 the 10th and 11th tools score
     * identically on 86.9% of turns, and at k=12 on 96.6%. Past 10 the cut is
     * almost entirely `thenBy { name }` deciding between tools the scorer
     * scored zero, so extra width buys recall by accident of the alphabet
     * rather than by ranking. 10 is the knee, and 12 is recorded above as a
     * measured option rather than left as an unexamined next step. Both tie
     * rates are from the pre-tag-fix run; after the fix 12 buys ZERO further
     * cases, which is a further reason not to take it.
     *
     * ## Why this cannot overrun the working limit
     *
     * Checked, not assumed, and the answer is that no width can:
     *
     *  - **The grammar costs zero context tokens.** `GrammarBuilder.forActions`
     *    output is a SAMPLER constraint passed as `GenerationRequest.grammar`,
     *    never prefilled. It grows 1042 -> 1339 chars from k=6 to k=10, and
     *    that is parse overhead, not budget.
     *  - **The prompt cost is bounded and small.** Worst case over the whole
     *    dataset at k=10 is 581 tokens for system prompt plus task, against a
     *    `ContextBudget.promptTokens` ceiling of 5744 (`workingTokenLimit` 6000
     *    less the 256-token reply reserve). Even at k=25 the worst case is 934.
     *    Headroom at k=10 is 5163 tokens.
     *  - **The builder degrades rather than overflowing.**
     *    `DefaultContextBuilder` budgets the tool-carrying system prompt first
     *    and trims history to whatever is left, so widening the set spends
     *    history budget, and `ContextBudget` still names tool definitions as
     *    the last thing it drops.
     *
     * So the trade is one-sided at this width: 103 tokens of a 6000 budget
     * against 9 tasks that were otherwise uncallable. Past roughly 30 tools
     * this stops being true and the constant would need re-deriving.
     *
     * ## The half of the trade that is NOT measured
     *
     * Whether a 1-3B model chooses *reliably* from a 10-item grammar is not
     * established. The harness has no model in it, so it can only prove the
     * ceiling went up, not that the model exploits the higher ceiling. The
     * unmeasured risk is a model that picks the wrong one of ten rather than
     * the wrong one of six — a worse answer rather than an impossible task.
     * That is a strictly smaller loss than the one being fixed, and it is
     * still unmeasured rather than assumed away; `docs/evals.md` owns that gap.
     */
    val maxVisibleTools: Int = 10,
    /**
     * Consecutive malformed model outputs tolerated before the loop gives up.
     * The loop aborts when the streak *reaches* this, so the default 3 means
     * three attempts and two corrections.
     */
    val maxMalformedRetries: Int = 3,
    /**
     * The prefill COST cap, in tokens — architecture §9's "a routine Android
     * action should never require a 20K prefill".
     *
     * NOT the prompt ceiling. This is the upper bound on what the loop is
     * willing to spend, and the real ceiling is the smaller of this and the
     * loaded model's own window (`ContextCeiling.workingLimit` does that
     * arithmetic). It used to be named `workingTokenLimit` and defaulted to a
     * bare 6000, which read as a ceiling and was passed to
     * `DefaultContextBuilder` as one — the 6000-vs-4096 defect. The name says
     * what the number is now.
     */
    val prefillCostCapTokens: Int = ContextCeiling.PREFILL_COST_CAP,
    /** Hard ceiling 5 memories returned into context. */
    val memoryResults: Int = 5,
    /** The only tool output the model ever sees. */
    val observationBudgetChars: Int = ObservationTruncator.DEFAULT_BUDGET_CHARS,
)
