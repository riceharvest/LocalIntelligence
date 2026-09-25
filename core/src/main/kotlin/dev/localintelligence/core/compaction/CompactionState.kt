package dev.localintelligence.core.compaction

/**
 * What the compactor was handed. The complete input to a pure function.
 *
 * WHY the previous summary is an input and not hidden state: compaction that
 * depends on what the component happened to remember cannot be reasoned about,
 * cannot be tested for a second generation, and cannot be run twice to check it
 * is deterministic. Generations are a function of the arguments.
 */
data class CompactionState(
    /**
     * Events in chronological order, oldest first. Read only — never modified,
     * never copied beyond the derived slots. Raw history stays in the database
     * forever (`docs/architecture.md` §12); compaction only changes what enters
     * the next inference context.
     */
    val events: List<CompactionEvent> = emptyList(),

    /**
     * The previous compaction, or null on the first. Folded in rather than
     * replaced, which is what makes a second and third generation of compaction
     * lossless instead of a sliding window over the last few messages.
     */
    val previous: StructuredSummary? = null,

    /**
     * Facts the runtime already holds — memories, retrieved context, device
     * state. Caller-supplied because only the caller knows what it retrieved;
     * the compactor cannot guess, and a guessed fact is worse than no fact.
     */
    val knownFacts: List<String> = emptyList(),

    /**
     * What the caller believes is still outstanding. Caller-supplied for the
     * same reason: this is the loop's plan, not something derivable from a
     * transcript.
     */
    val remainingWork: List<String> = emptyList(),
) {
    /** Total events seen, including those dropped by budget. The audit's denominator. */
    val eventCount: Int get() = events.size
}

/**
 * One thing the compactor removed, and why.
 *
 * WHY this is a value and not a log line: `docs/architecture.md` §18 makes
 * "large result" and "no exception escapes" testable requirements, and a test
 * cannot assert on a log line. The trace view wants the same data, and building
 * it from a value keeps the two in agreement.
 */
data class DroppedEntry(
    /** Which slot the content belonged to. */
    val section: SummarySection,
    /** Why it is not in the summary. */
    val reason: DropReason,
    /** The dropped text, capped by [CompactionConfig.maxAuditEntries] policy. */
    val text: String,
    /** Char length BEFORE any truncation, so the audit shows the real loss. */
    val originalChars: Int = text.length,
)

/**
 * What one compaction did, beyond producing a summary.
 *
 * WHY compaction is not self-describing: a component that silently discards
 * information is indistinguishable from one that is working, from the outside,
 * forever. This is the only way a caller can tell the difference without
 * re-implementing the compactor.
 */
data class CompactionAudit(
    /** 1 for a first compaction, 2 for a compaction of one, and so on. */
    val generation: Int,

    /** Rendered length of the resulting summary, in chars. */
    val renderedChars: Int,
    /** Token estimate of the resulting summary, using [CompactionConfig.charsPerToken]. */
    val renderedTokens: Int,

    /** Every entry removed, in deterministic eviction order. Capped by [CompactionConfig.maxAuditEntries]. */
    val dropped: List<DroppedEntry> = emptyList(),

    /**
     * The TRUE number removed, which exceeds [dropped].size when the audit list
     * hit its cap. Reported separately so a truncated audit cannot understate
     * the loss.
     */
    val droppedCount: Int = 0,

    /** True when the resulting summary is within both configured budgets. */
    val withinBudget: Boolean = true,

    /**
     * True when anything was lost to a capacity limit — the character budget, the
     * per-section cap, or a truncated observation.
     *
     * WHY it is not "was anything evicted": dropping a replayed call loses
     * nothing, so counting duplicates as loss would make every long session
     * report lossy and train a reader to ignore the flag. A short session is
     * [false] here, which is the case worth seeing in a trace.
     */
    val lossy: Boolean = false,
) {
    /**
     * The count of entries lost to the character budget specifically, as opposed
     * to deduplication. A healthy long session has a non-zero deduplication
     * count and a zero [budgetLossCount].
     */
    val budgetLossCount: Int get() = dropped.count { it.reason == DropReason.DISCARDED }

    /** Entries removed because the section was full, not because of the budget. */
    val capLossCount: Int get() = dropped.count { it.reason == DropReason.SECTION_CAP }

    /** Entries removed because they repeated something already recorded. */
    val duplicateCount: Int get() = dropped.count { it.reason == DropReason.DUPLICATE }

    /**
     * A single line for a `StepTrace.COMPACTION` entry.
     *
     * WHY bounded: `StepTrace.detail` is shown in a trace view on a phone and is
     * itself capped by the loop. This is a status line, not a dump — the full
     * audit is available to any caller that wants it.
     */
    fun summaryLine(): String = buildString {
        append("gen ").append(generation)
        append(" | ").append(renderedChars).append(" chars / ").append(renderedTokens).append(" tokens")
        if (droppedCount > 0) append(" | dropped ").append(droppedCount)
        if (duplicateCount > 0) append(" (dup ").append(duplicateCount).append(')')
        if (budgetLossCount > 0) append(" (budget ").append(budgetLossCount).append(')')
        if (!withinBudget) append(" | OVER BUDGET")
    }
}

/**
 * The result of one compaction: the new summary, and the audit of what it cost.
 *
 * WHY both in one value: they are produced together and are meaningless apart.
 * Returning the summary without the audit is the omission that makes a lossy
 * compactor undetectable.
 */
data class CompactionResult(
    val summary: StructuredSummary,
    val audit: CompactionAudit,
)
