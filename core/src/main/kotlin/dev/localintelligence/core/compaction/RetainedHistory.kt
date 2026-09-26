package dev.localintelligence.core.compaction

import dev.localintelligence.core.model.ChatMessage

/**
 * The hard cap on how many messages one session keeps in memory, and the trim
 * that enforces it.
 *
 * ## Why this exists when a compaction trigger already exists
 *
 * The loop's `compactIfNeeded` fires at 0.65 of the model's window and folds the
 * tail into a [dev.localintelligence.core.context.CompactedState]. That bounds
 * what the MODEL is asked to prefill. It does not bound what the PROCESS holds.
 *
 * A run is capped at `AgentConfig.maxSteps` (8) and so contributes at most a task
 * turn, a final answer, and two messages per step — 18 in the worst case. A
 * session outlives its run, so it grows by up to 18 messages per conversation,
 * and the token trigger only looks at the total once a loop is already running.
 * A long day of activity on a device whose primary metric is RAM is exactly the
 * case where nothing is looking.
 *
 * ## The bound, and the number
 *
 * [MAX_RETAINED_MESSAGES] is 32, and the reasoning is arithmetic rather than
 * taste:
 *
 *  - it must exceed the worst case of ONE run (18) or a run would evict its own
 *    task before finishing. 32 does, with room for a second run's worth of
 *    history so the model can see what it was already told — which is the entire
 *    reason the session is shared.
 *  - it must not be large enough for the count to matter as a sum of runs. At
 *    32 the working window is two conversations deep, so the steady state is a
 *    fixed-size list rather than a number that grows with the day.
 *  - per message, the model-visible text is already capped at
 *    `ObservationTruncator.DEFAULT_BUDGET_CHARS` (2048). This is a count bound
 *    rather than a byte bound for exactly that reason: a cap in bytes would have
 *    to re-derive a size the runtime already fixes in one place, and a second
 *    derivation of a number is how a second implementation of it starts.
 *
 * ## The RAM figure is deliberately not stated
 *
 * `docs/architecture.md` §16 records that no resident-cost number in this
 * repository has been measured on a device, and that stands. What can be said
 * from the code is the shape: at most [MAX_RETAINED_MESSAGES] messages, each
 * carrying at most one truncator-budget of model-visible text. The byte cost of
 * that is unmeasured and is not estimated here.
 */
object RetainedHistory {

    /**
     * Messages retained across runs, anchor included.
     *
     * See the type KDoc for the arithmetic. Two conversations deep on purpose:
     * deep enough that the model can answer a follow-up about what it just did,
     * shallow enough that the list is a constant rather than a running total.
     */
    const val MAX_RETAINED_MESSAGES: Int = 32

    /**
     * Trims [messages] in place to [MAX_RETAINED_MESSAGES], newest first, with
     * the anchor and the user turns protected.
     *
     * ## In place, and why
     *
     * The list is the session's own and the loop reads it by reference every
     * step (`buildRequest` -> `withWorkingSummary`, `foldWindow`). Mutating it
     * rather than replacing it keeps every holder pointing at the same list and
     * allocates one retained list per trim, not a second live copy of the
     * history. `foldWindow` in the loop already mutates in place for the same
     * reason.
     *
     * ## Bounded work
     *
     * The kept set is a sorted set of indices capped at
     * [MAX_RETAINED_MESSAGES], so the bookkeeping is O(cap) regardless of how
     * far over the cap the list is. A boolean array indexed by list size would
     * be O(n) and would allocate a second array as large as the thing being
     * trimmed, which is the opposite of the point of a memory bound.
     *
     * @return how many messages were dropped. Zero when the list was already
     *   within the cap, so a caller can log a real number rather than
     *   re-deriving whether anything happened.
     */
    fun bound(messages: MutableList<ChatMessage>): Int {
        val cap = MAX_RETAINED_MESSAGES
        val size = messages.size
        if (size <= cap) return 0

        // A TreeSet because iteration order is ascending index order, which is
        // the order the messages have to go back in, and because its size is
        // bounded by cap rather than by the input.
        val keep = java.util.TreeSet<Int>()

        // The anchor: the session's OPENING turn, kept regardless of budget.
        //
        // The old comment here said "index 0 is the run's task for every session
        // this has ever seen", which was true when each run got a fresh Session
        // and false the moment the session became shared and multi-run. Index 0
        // is now the FIRST-EVER task, which may be many turns old and unrelated
        // to the run in progress.
        //
        // It is still protected, deliberately: a session's opening request is
        // what identifies the conversation ("remind me about X"), and losing it
        // leaves a reply with no referent. That is a worse failure than
        // carrying one stale turn, and it costs exactly one message. The
        // CURRENT run's instruction is protected separately, by the user-turn
        // re-admission loop below, which is the protection that actually
        // matters for the run in progress.
        keep.add(0)

        // Newest wins the budget. Scanning downwards means the most recent
        // messages are the ones that survive a trim.
        for (index in size - 1 downTo 1) {
            if (keep.size >= cap) break
            keep.add(index)
        }

        // Re-admit any user turn the recency scan dropped, paying for it with
        // the oldest kept message that is not itself an instruction.
        for (index in 1 until size) {
            if (index in keep) continue
            if (messages[index] !is ChatMessage.User) continue
            val victim = keep.firstOrNull { it != 0 && messages[it] !is ChatMessage.User }
            // Every kept message is the anchor or an instruction. There is
            // nothing cheaper to give up, so the cap stands.
                ?: break
            keep.remove(victim)
            keep.add(index)
        }

        if (keep.size == size) return 0

        val retained = keep.mapTo(ArrayList<ChatMessage>(keep.size)) { messages[it] }
        messages.clear()
        messages.addAll(retained)
        return size - retained.size
    }
}
