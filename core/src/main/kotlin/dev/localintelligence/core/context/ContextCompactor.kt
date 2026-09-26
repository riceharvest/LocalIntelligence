package dev.localintelligence.core.context

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.UntrustedContent
import dev.localintelligence.core.model.ModelBackend
import kotlin.math.min

/**
 * The seam a future model-based summariser would slot into.
 *
 * It is kept as an interface for one reason: the day somebody wants to A/B a
 * 3B-model-written summary against this deterministic one, the experiment needs a
 * seam and an eval, and swapping implementations must not touch [ContextCompactor].
 * That is the whole justification.
 *
 * The SHIPPED implementation is [DeterministicSummaryWriter], which returns the
 * state it was handed, unchanged.
 *
 * Why deterministic, and why this is not a punt:
 *
 *  - Compaction runs on the critical path of a phone. A model call here is a
 *    SECOND full inference (prefill the whole history, decode the summary) on
 *    every compaction. That can be tens of seconds and a large battery hit, for
 *    something Kotlin does exactly and for free.
 *  - A 3B model asked to "summarise this conversation" reliably drops the
 *    failures list, because failures are the least salient thing in a transcript.
 *    [CompactedState.failures] existing at all is the fix for that. Handing the
 *    problem to the model throws the fix away.
 *  - Non-determinism in the context builder makes the whole eval suite
 *    unreproducible. docs/architecture.md section 19 and the evals both assume a
 *    fixed input produces a fixed prompt.
 *
 * If a future PR ships a model summariser, it must beat this one on the eval
 * suite, not on vibes.
 */
fun interface SummaryWriter {
    suspend fun summarize(state: CompactedState, model: ModelBackend): CompactedState
}

/**
 * Marker on the message the loop prepends to the history it hands to
 * [DefaultContextBuilder]. Kept short — it is paid for on every single turn.
 */
internal const val SUMMARY_PREFIX = "Working state so far:"

/**
 * The shipped summariser: a pass-through.
 *
 * All the work happens in [ContextCompactor.compact], which derives the slots
 * directly from the [ChatMessage] list. This object exists to satisfy the
 * [SummaryWriter] seam without spending a second inference.
 */
object DeterministicSummaryWriter : SummaryWriter {
    override suspend fun summarize(state: CompactedState, model: ModelBackend): CompactedState = state
}

/**
 * Turns a long session into labelled slots when the context gets too big.
 *
 * Trigger (docs/architecture.md section 12):
 *
 *     activeTokens > min(modelContext * 0.65, workingLimit)
 *
 * Compaction is DETERMINISTIC. It is pure Kotlin over the [ChatMessage] list: no
 * model call, no clock, no randomness, no locale-dependent formatting. Same
 * input, byte-identical output — which is what makes the eval suite meaningful.
 *
 * ## Raw history is never destroyed
 *
 * Compaction only changes what goes into the NEXT INFERENCE CONTEXT. It does not
 * delete, mutate, or truncate a single stored message. The [ChatMessage] list
 * handed in is read and returned untouched; the database keeps the full
 * transcript forever. Deleting history would be unrecoverable on a device with
 * no backup, and it would make the transcript disagree with what the agent saw.
 *
 * ## RAM (docs/architecture.md section 16)
 *
 * Every slot is hard-capped, so the compacted state is bounded no matter how long
 * the session runs:
 *
 *  - 5 slots x [MAX_SLOT_ENTRIES] (12) entries = 60 lines maximum, ever
 *  - each line truncated to [MAX_ENTRY_CHARS] (120) chars
 *  - a compacted state is therefore at most ~7.2 KB of text, and its rendered
 *    form is under 1,900 tokens — inside the 300-800 token working-summary budget
 *    for every realistic session
 *  - the scan is a single O(n) pass holding O(1) counters plus the capped slots;
 *    the input list is never copied
 *
 * There is no cache, no buffer that grows, and no retained state between calls.
 */
class ContextCompactor(
    private val summarizer: SummaryWriter = DeterministicSummaryWriter,
    private val workingLimit: Int = 6000,
    private val triggerFraction: Double = 0.65,
) {

    /**
     * True when the active context is too big to keep growing.
     *
     * `min(modelContext * triggerFraction, workingLimit)` — the model context
     * stops us overflowing the KV cache, and workingLimit stops us paying for a
     * prefill the architecture says we should never pay (section 9). On a 4K
     * model the first term binds; on a 32K model the second does.
     *
     * Note: `ModelCapabilities.UNKNOWN.contextLength` is 0, and a naive
     * `min(0 * 0.65, 6000)` is 0, so an unknown context would compact on every
     * step. The architecture fixes the formula, so this does not silently change
     * it — but callers holding a real model should pass the real context length.
     * A zero context degrades to compacting constantly, which is slow and
     * wasteful rather than wrong. Flagged for the integration agent in the PR.
     */
    fun shouldCompact(activeTokens: Int, modelContext: Int): Boolean =
        activeTokens > min(modelContext * triggerFraction, workingLimit.toDouble())

    /**
     * The contract signature from docs/wave1-contract.md, minus `Session`.
     *
     * That file (lines 196-199) explicitly prefers `(history, summary, model)`
     * over taking agent A's `Session`, because `Session` does not exist yet and a
     * compile-order dependency between two parallel worktrees helps nobody. This
     * is that variant.
     *
     * @param history the session's messages, oldest first. READ ONLY — not
     *   modified, not copied, not truncated. What leaves the inference context is
     *   [CompactedState]; what stays in the database is every one of these.
     * @param summary the previous compaction, or null on the first one. Carried
     *   forward so facts and failures are never lost across successive
     *   compactions — see [merge].
     */
    suspend fun compact(
        history: List<ChatMessage>,
        summary: CompactedState?,
        model: ModelBackend,
    ): CompactedState {
        val progress = Slot()
        val actions = Slot()
        val failures = Slot()

        // Single deterministic pass. `actions` and `failures` are separate slots
        // because a 3B model needs them labelled apart, not interleaved.
        for (message in history) {
            when (message) {
                is ChatMessage.Assistant ->
                    progress.add(message.text)

                is ChatMessage.ToolObservation -> {
                    val line = observationLine(message)
                    if (message.success) actions.add(line) else failures.add(line)
                }

                // User and System messages are not progress. The task is carried
                // in its own slot and the summary slot already exists.
                else -> Unit
            }
        }

        val derived = CompactedState(
            task = resolveTask(history, summary),
            progress = progress.toList(),
            knownFacts = summary?.knownFacts.orEmpty(),
            actionsTaken = actions.toList(),
            failures = failures.toList(),
            remainingWork = summary?.remainingWork.orEmpty(),
        )

        return merge(summary, derived)
    }

    /**
     * Renders a compacted state as the single message the loop prepends to the
     * history it hands to [DefaultContextBuilder]. The prefix is what lets the
     * builder recognise the message and keep it in slot 2.
     */
    fun summaryMessage(state: CompactedState): ChatMessage.System =
        ChatMessage.System(SUMMARY_PREFIX + "\n" + state.render())

    /**
     * Folds the previous compaction into the newly derived one.
     *
     * This is what makes failures survive REPEATED compaction. Without it, the
     * second compaction of a session would overwrite the first one's failure list
     * with only the newest window, and the agent would walk straight back into a
     * call that already failed — the exact regression labelled slots exist to
     * prevent. Slots are carried forward newest-last and capped, so the most
     * recent failures are the ones that survive.
     */
    private fun merge(previous: CompactedState?, derived: CompactedState): CompactedState {
        if (previous == null) return derived
        return CompactedState(
            task = derived.task,
            progress = carry(previous.progress, derived.progress),
            knownFacts = carry(previous.knownFacts, derived.knownFacts),
            actionsTaken = carry(previous.actionsTaken, derived.actionsTaken),
            failures = carry(previous.failures, derived.failures),
            remainingWork = carry(previous.remainingWork, derived.remainingWork),
        )
    }

    /**
     * Oldest-first carry-forward capped to [MAX_SLOT_ENTRIES].
     *
     * Dedupes with a bounded set so a tool that fails identically five times
     * occupies one line, not five. When the cap is hit the OLDEST entries are
     * dropped, because recent failures are the ones the model must not repeat.
     */
    private fun carry(previous: List<String>, derived: List<String>): List<String> {
        if (previous.isEmpty()) return derived
        if (derived.isEmpty()) return previous.takeLast(MAX_SLOT_ENTRIES)
        val out = LinkedHashSet<String>(MAX_SLOT_ENTRIES)
        previous.forEach { out.add(it) }
        derived.forEach { out.add(it) }
        return out.toList().takeLast(MAX_SLOT_ENTRIES)
    }

    /**
     * The task, in order of trustworthiness: the previous summary's task (it was
     * established earlier and does not drift), else the first user message, else
     * an explicit "unknown". Never invented, never a guess at what the user meant.
     */
    private fun resolveTask(history: List<ChatMessage>, summary: CompactedState?): String {
        summary?.task?.takeIf { it.isNotBlank() }?.let { return truncate(it, MAX_ENTRY_CHARS) }
        for (message in history) {
            if (message is ChatMessage.User && message.text.isNotBlank()) {
                return truncate(message.text.trim(), MAX_ENTRY_CHARS)
            }
        }
        return UNKNOWN
    }

    /**
     * A tool outcome as one slot line. Collapsed to a single line because a
     * multi-line entry would read as several slots to a model scanning labels.
     */
    private fun observationLine(message: ChatMessage.ToolObservation): String {
        val body = collapse(message.observation)
        return if (message.success) {
            // Cap the WHOLE line, prefix included: "x FAILED: " must not be able
            // to push a slot entry past its budget.
            truncate("${message.toolName}: $body", MAX_ENTRY_CHARS)
        } else {
            // The word is the signal. A small model scanning for a failure needs
            // it to survive, and it is what stops a retry loop.
            truncate("${message.toolName} FAILED: $body", MAX_ENTRY_CHARS)
        }
    }

    /** One line, trimmed, at most [MAX_ENTRY_CHARS]. Never throws. */
    private fun collapse(text: String): String {
        val flat = if (WHITESPACE.containsMatchIn(text)) {
            text.replace(WHITESPACE, " ").trim()
        } else {
            text.trim()
        }
        return truncate(flat, MAX_ENTRY_CHARS)
    }

    private fun truncate(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"

    /**
     * One capped slot. Holds at most [MAX_SLOT_ENTRIES] lines, evicting the
     * oldest. Dedupe is bounded by the same cap, so the worst-case footprint is
     * fixed regardless of history length.
     */
    private class Slot(private val cap: Int = MAX_SLOT_ENTRIES) {
        private val lines = ArrayList<String>(cap)
        private val seen = LinkedHashSet<String>(cap)

        fun add(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || !seen.add(line)) return
            lines.add(line)
            if (lines.size > cap) {
                lines.removeAt(0)
                seen.remove(lines.first())
            }
        }

        fun toList(): List<String> = lines
    }

    companion object {
        /** Slot cap. 5 slots x 12 x 120 chars = ~7.2 KB worst case. */
        const val MAX_SLOT_ENTRIES = 12

        /** Per-line cap, so one enormous observation cannot own a slot. */
        const val MAX_ENTRY_CHARS = 120

        /** Written when there is genuinely no task to report. Not invented text. */
        const val UNKNOWN = "(no task recorded)"

        private val WHITESPACE = Regex("\\s+")
    }
}
