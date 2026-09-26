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
    /** Hard ceiling 8. A small visible set is the biggest context saving in the system. */
    val maxVisibleTools: Int = 6,
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
