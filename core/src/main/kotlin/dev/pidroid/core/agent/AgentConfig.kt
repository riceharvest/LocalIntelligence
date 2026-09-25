package dev.pidroid.core.agent

import dev.pidroid.core.tool.ObservationTruncator

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
    /** Compaction trigger, in tokens. */
    val workingTokenLimit: Int = 6000,
    /** Hard ceiling 5 memories returned into context. */
    val memoryResults: Int = 5,
    /** The only tool output the model ever sees. */
    val observationBudgetChars: Int = ObservationTruncator.DEFAULT_BUDGET_CHARS,
)
