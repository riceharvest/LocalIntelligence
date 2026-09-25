package dev.localintelligence.core.compaction

/**
 * Eviction priority that no real section can occupy, so the task can never be
 * sorted into the drop order. File-level rather than in [SummarySection]'s
 * companion because an enum's entries are constructed before its companion
 * object is initialised.
 */
private const val NEVER_DROPPED_PRIORITY: Int = Int.MAX_VALUE

/**
 * The six labelled slots from `docs/architecture.md` §12, in the order they are
 * rendered, with the budget-eviction order attached to each one.
 *
 * WHY an enum and not six string constants: "individually queryable" is a
 * requirement, and a caller that hand-writes `"Failures:"` will eventually hand-
 * write it wrong. A case that cannot be misspelled is the whole point.
 *
 * [dropPriority] is the deterministic eviction order, ascending. When the
 * rendered summary does not fit the budget, whole entries are dropped starting
 * at priority 0. The order is a judgement, and it is this one:
 *
 *  - [PROGRESS] first. Self-reported narration, usually redundant with the
 *    action it describes, and the cheapest thing to lose.
 *  - [KNOWN_FACTS] next. The loop re-queries its memory store on every step, so
 *    a dropped fact can be re-supplied on the next turn at no structural cost.
 *  - [REMAINING_WORK] next. A plan restated from the task; informative but
 *    derivable, and the most likely of the survivors to be stale.
 *  - [ACTIONS_TAKEN] late. These are what stop the model redoing work that
 *    already succeeded.
 *  - [FAILURES] last. Highest value in the whole summary: a dropped failure is
 *    a battery-draining retry loop, which is exactly what `docs/architecture.md`
 *    §13 exists to prevent.
 *  - [TASK] never. See [NEVER_DROPPED].
 */
enum class SummarySection(
    /** The literal label used in the rendered summary. */
    val label: String,
    /** Ascending eviction order. See the type KDoc. */
    val dropPriority: Int,
) {
    // Reads the file-level constant, not the companion's: an enum entry is
    // initialised before its companion object exists.
    TASK("Task", NEVER_DROPPED_PRIORITY),
    PROGRESS("Progress", 0),
    KNOWN_FACTS("Known facts", 1),
    REMAINING_WORK("Remaining work", 2),
    ACTIONS_TAKEN("Actions already taken", 3),
    FAILURES("Failures", 4),
    ;

    /** True for content the budget may never evict, however over budget we are. */
    val protected: Boolean get() = dropPriority == NEVER_DROPPED

    companion object {
        /**
         * Sentinel eviction priority for the task. It is deliberately unreachable
         * by any real priority so that a naive `sortedBy { it.dropPriority }` in
         * a future change cannot accidentally put the task first in the drop
         * order.
         */
        const val NEVER_DROPPED: Int = NEVER_DROPPED_PRIORITY

        /** Render order, which is the architecture's order and is NOT eviction order. */
        val RENDER_ORDER: List<SummarySection> = listOf(
            TASK, PROGRESS, KNOWN_FACTS, ACTIONS_TAKEN, FAILURES, REMAINING_WORK,
        )

        /** Eviction order, lowest value first. */
        val EVICTION_ORDER: List<SummarySection> = listOf(
            PROGRESS, KNOWN_FACTS, REMAINING_WORK, ACTIONS_TAKEN, FAILURES,
        )
    }
}

/**
 * Why a candidate entry is not in the resulting summary.
 *
 * WHY the distinction between [DUPLICATE] and [DISCARDED] exists: they look
 * identical in the output and mean opposite things. A duplicate is a loop
 * detector replaying a call, and removing it is the component WORKING. A discard
 * is information the model needed and did not get. A trace view that conflates
 * them cannot tell a healthy run from a starving one.
 */
enum class DropReason {
    /**
     * Byte-identical to an entry already present, or the same call as an entry
     * already present ([CompactionConfig] documents the call-identity key).
     * Nothing was lost that the surviving line does not already say.
     */
    DUPLICATE,

    /**
     * Evicted to fit the budget, after every higher-priority section had already
     * been emptied. This is real information loss and the reason
     * [CompactionAudit] exists.
     */
    DISCARDED,

    /**
     * A single entry larger than the entire budget was dropped WHOLE rather than
     * sliced. A half-written JSON argument teaches the model that malformed
     * arguments are acceptable (`DefaultContextBuilder` makes the same argument
     * about the turn window).
     */
    OVERSIZED,

    /**
     * The entry's observation was shortened from [CompactionConfig.maxEntryChars].
     * Recorded with both lengths so the loss is never silent. The tool name and
     * arguments on the same entry are never shortened.
     */
    TRUNCATED,

    /** There was nothing to keep: a blank line, or an event that carried no
     *  text. Recorded so "the model had nothing" is distinguishable from "the
     *  compactor threw it away". */
    EMPTY,

    /**
     * The section was already at [CompactionConfig.maxEntriesPerSection] and its
     * oldest entry was pushed out to make room.
     *
     * WHY this is not [DISCARDED]: the character budget was not the constraint
     * and the summary may well be comfortably inside it. Folding a cap eviction
     * into the budget-loss count would tell a trace view that the summary was
     * truncated for space when it was in fact simply long, which is a different
     * bug with a different fix.
     */
    SECTION_CAP,
}

/**
 * Every knob, in one value, with a default chosen from `docs/architecture.md`
 * rather than from taste.
 *
 * WHY a data class and not constructor parameters: a compactor configured in
 * three places is a compactor whose budget is unknowable. One value can be
 * logged, asserted on, and passed to a test unchanged.
 */
data class CompactionConfig(
    /**
     * The architecture's compaction trigger ceiling
     * (`docs/agent-loop.md`: `min(model.context * 0.65, workingLimit)`).
     */
    val workingTokenLimit: Int = 6_000,

    /** The 0.65 from `docs/architecture.md` §12. */
    val triggerFraction: Double = 0.65,

    /**
     * Hard ceiling on the rendered summary, in characters. Default 1600 is
     * ~400 tokens at [charsPerToken], inside the 300-800 token working-summary
     * band in `docs/architecture.md` §9.
     */
    val maxRenderedChars: Int = 1_600,

    /**
     * The same ceiling in tokens, enforced independently. Both budgets are
     * honoured; the character one binds first at the default [charsPerToken],
     * and the token one is the one that would bind if a caller lowered
     * [maxRenderedChars] less than proportionally.
     */
    val maxRenderedTokens: Int = 500,

    /**
     * Per-section entry cap, independent of the character budget. This is the
     * bound that survives a pathological history of 10,000 short observations
     * that would otherwise total fewer characters than [maxRenderedChars].
     */
    val maxEntriesPerSection: Int = 12,

    /**
     * Per-observation truncation, in characters. 2048 is
     * `ObservationTruncator.DEFAULT_BUDGET_CHARS` — the ceiling a well-behaved
     * tool already respects, so this normally never fires, and when it does the
     * audit says so. Tool names and arguments are exempt, always.
     */
    val maxEntryChars: Int = 2_048,

    /**
     * Chars per token. Matches `NoopModelBackend.countTokens` and
     * `TokenEstimate.CHARS_PER_TOKEN`, so this component's budget and the
     * context builder's budget agree without :core needing a live model.
     */
    val charsPerToken: Int = 4,

    /**
     * Cap on [CompactionAudit.dropped]. The audit is a debugging surface shown
     * in a trace view on a phone; an unbounded one is a memory leak wearing a
     * helpful hat. [CompactionAudit.droppedCount] still reports the true total.
     */
    val maxAuditEntries: Int = 64,
) {
    init {
        require(maxRenderedChars > 0) { "maxRenderedChars must be positive" }
        require(charsPerToken > 0) { "charsPerToken must be positive" }
        require(maxEntriesPerSection > 0) { "maxEntriesPerSection must be positive" }
        require(maxEntryChars > 0) { "maxEntryChars must be positive" }
        require(maxAuditEntries > 0) { "maxAuditEntries must be positive" }
        require(triggerFraction > 0.0) { "triggerFraction must be positive" }
    }
}
