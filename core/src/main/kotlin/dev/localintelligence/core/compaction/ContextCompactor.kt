package dev.localintelligence.core.compaction

/**
 * Turns a growing session into six labelled slots, deterministically, with an
 * audit of everything it had to leave behind.
 *
 * ## What it is for
 *
 * `docs/architecture.md` §12: a 3B model stays on task with labelled slots and
 * drifts with prose. This is the slot-filling half of that, extracted from the
 * loop so it can be tested without a model, a session, or a device.
 *
 * ## Determinism, and why it is the first requirement
 *
 * Pure Kotlin over the input. No clock, no randomness, no locale-sensitive
 * formatting, no I/O, no model call. Identical input produces byte-identical
 * output, which is what makes the eval suite measure the agent rather than
 * sampling noise. Every ordering decision is by insertion index or by an
 * explicit enum priority; nothing depends on hash iteration order.
 *
 * ## Generations
 *
 * Compacting an already-compacted summary is the case a naive implementation
 * gets wrong. [StructuredSummary] is folded forward, so the second compaction of
 * a session keeps the first one's failures and the third keeps the second's.
 * Growth is bounded by [CompactionConfig.maxEntriesPerSection] AND by the
 * character budget, and the budget is enforced on every pass — so generation 30
 * is the same size as generation 2, not merely a smaller one.
 *
 * ## Lossless where it matters
 *
 * The task, the tool names, the arguments, and the error text survive verbatim.
 * What may be dropped, in this order, is [SummarySection]'s eviction order, and
 * every drop is recorded in [CompactionAudit] with its reason.
 *
 * ## Bounded RAM (`docs/architecture.md` §16)
 *
 * One pass over [CompactionState.events] holding at most
 * [CompactionConfig.maxEntriesPerSection] entries per section, plus a dedup set
 * bounded by the same number. The input list is never copied. Worst case for the
 * default config is 5 sections x 12 entries x 2048 chars = ~120 KB transient,
 * reduced to ~1.6 KB by the budget pass before return. No cache, no retained
 * state between calls.
 *
 * Cost per compaction: one O(n) pass to build, then at most 60 render checks of
 * a ~1.6 KB string. That is a few hundred KB of string work on the path where
 * the alternative is a multi-second inference.
 *
 * ## Not thread-safe, and does not need to be
 *
 * The loop owns one session. A lock would imply concurrency that does not exist
 * (`docs/architecture.md` §19: no abstractions for hypothetical use).
 */
class ContextCompactor(
    /** All limits and thresholds. One value, so the budget is always knowable. */
    val config: CompactionConfig = CompactionConfig(),
) {

    /**
     * Whether the active context is too big to keep growing.
     *
     * `docs/architecture.md` §12:
     * `activeTokens > min(model.context * 0.65, workingLimit)`.
     * The model context stops us overflowing the KV cache; the working limit
     * stops us paying a prefill the architecture says we should never pay (§9).
     * On a 4K model the first term binds; on a 32K model the second does.
     *
     * A non-positive [modelContextTokens] means the backend does not know its
     * own window (`ModelCapabilities.UNKNOWN.contextLength` is 0). The formula
     * is applied as written, which degenerates to "always compact" — slow and
     * wasteful, never wrong. Substituting a guessed window would be a guess.
     */
    fun needsCompaction(activeTokens: Int, modelContextTokens: Int): Boolean =
        activeTokens > triggerCeiling(modelContextTokens)

    /**
     * The token count above which [needsCompaction] returns true. Exposed so a
     * caller can log the threshold it is actually using rather than re-deriving
     * a second, possibly different, copy of the formula.
     */
    fun triggerCeiling(modelContextTokens: Int): Int {
        val byModel = modelContextTokens * config.triggerFraction
        return if (byModel.isNaN() || byModel <= 0.0) {
            config.workingTokenLimit
        } else {
            minOf(byModel, config.workingTokenLimit.toDouble()).toInt()
        }
    }

    /**
     * The pure compaction. Same state in, same bytes out, every time.
     *
     * @param state the events plus the previous generation, if any. Never modified.
     * @return the new summary AND the audit of what was dropped. Returning both
     *   is deliberate: a summary without its audit is a compactor that can lose
     *   the user's request with nobody able to tell.
     */
    fun compact(state: CompactionState): CompactionResult {
        val previous = state.previous
        val generation = (previous?.generation ?: 0) + 1
        val audit = AuditSink(config.maxAuditEntries)

        val progress = Section<String>(SummarySection.PROGRESS, config.maxEntriesPerSection, audit)
        val facts = Section<String>(SummarySection.KNOWN_FACTS, config.maxEntriesPerSection, audit)
        val actions = Section<ActionLine>(SummarySection.ACTIONS_TAKEN, config.maxEntriesPerSection, audit)
        val failures = Section<ActionLine>(SummarySection.FAILURES, config.maxEntriesPerSection, audit)
        val remaining = Section<String>(SummarySection.REMAINING_WORK, config.maxEntriesPerSection, audit)

        // The previous generation goes in first, so a replayed call is recognised
        // as a duplicate of what is already recorded rather than as new work.
        previous?.let { prior ->
            prior.progress.forEach { progress.add(it) }
            prior.knownFacts.forEach { facts.add(it) }
            prior.actionsTaken.forEach { actions.add(it) }
            prior.failures.forEach { failures.add(it) }
            prior.remainingWork.forEach { remaining.add(it) }
        }

        state.knownFacts.forEach { facts.add(it) }
        state.remainingWork.forEach { remaining.add(it) }

        // Single observation shown to the model, in one summary.
        //
        // WHY the clamp: [CompactionConfig.maxEntryChars] is the ceiling an
        // incoming observation arrives under, while [maxRenderedChars] is the
        // whole budget. Without this clamp a single maximum-length observation
        // (2048 chars) could never fit in a 1600-char summary, so the budget
        // pass would evict the entire actions section and the model would be
        // told nothing about work it had already done — the worst outcome the
        // section exists to prevent. Clamping guarantees at least one action
        // line always survives, and the tool name and arguments are still exact.
        val renderCap: Int = minOf(config.maxEntryChars, config.maxRenderedChars)

        // Single pass over the events. No sorting, no clock, no I/O.
        //
        // `task` is resolved first and the first user event is consumed by it, so
        // the request is not ALSO recorded as progress. It is the task; recording
        // it twice costs tokens and teaches a small model that the user asked
        // twice.
        val task = resolveTask(state)
        var taskConsumed = false

        for (event in state.events) {
            when (event) {
                // The first user event became the task. Later ones are the user
                // steering mid-task, which is progress, not a new task.
                is CompactionEvent.UserRequest -> {
                    val line = StructuredSummary.oneLine(event.text)
                    if (!taskConsumed && event.text.isNotBlank()) {
                        taskConsumed = true
                    } else if (line.isNotEmpty()) {
                        progress.add(line)
                    }
                }

                is CompactionEvent.ModelSaid ->
                    progress.add(StructuredSummary.oneLine(event.text))

                is CompactionEvent.ToolCall -> {
                    val line = event.toActionLine(renderCap, audit)
                    if (line != null) {
                        if (line.success) actions.add(line) else failures.add(line)
                    }
                }
            }
        }

        // The budget runs on the assembled summary, so it applies identically to
        // the first generation and the hundredth.
        val trimmed = enforceBudget(
            StructuredSummary(
                task = task,
                progress = progress.snapshot(),
                knownFacts = facts.snapshot(),
                actionsTaken = actions.snapshot(),
                failures = failures.snapshot(),
                remainingWork = remaining.snapshot(),
                generation = generation,
            ),
            audit,
        )

        val rendered = trimmed.render(renderCap)
        return CompactionResult(
            summary = trimmed,
            audit = CompactionAudit(
                generation = generation,
                renderedChars = rendered.length,
                renderedTokens = estimateTokens(rendered),
                dropped = audit.entries,
                droppedCount = audit.total,
                withinBudget = fits(rendered),
                // Any capacity loss counts, including a full section: a summary
                // that silently forgot 28 tool calls is lossy even though the
                // character budget was never reached. Duplicates and empties are
                // the only non-losses.
                lossy = audit.total > audit.harmlessCount,
            ),
        )
    }

    /**
     * The user's literal request, in order of trustworthiness: the previous
     * generation's task (established earlier, and it does not drift), else the
     * first non-blank user event, else blank.
     *
     * WHY never a paraphrase of the task: this is the one field the whole
     * component exists to protect, and the shortest faithful representation of a
     * user's request is the request itself.
     */
    private fun resolveTask(state: CompactionState): String {
        state.previous?.task?.takeIf { it.isNotEmpty() }?.let { return it }
        for (event in state.events) {
            if (event is CompactionEvent.UserRequest && event.text.isNotBlank()) {
                return event.text.trim()
            }
        }
        return ""
    }

    /**
     * Build the line for a tool call, or null when there is nothing to record.
     *
     * The only place truncation is allowed, and only on the observation. The tool
     * name and the arguments pass through untouched, so a replayed call is still
     * recognisable as the same call after compaction.
     */
    private fun CompactionEvent.ToolCall.toActionLine(
        maxObservationChars: Int,
        audit: AuditSink,
    ): ActionLine? {
        val cleanName = StructuredSummary.oneLine(name)
        val cleanArgs = StructuredSummary.oneLine(arguments)
        val section = if (success) SummarySection.ACTIONS_TAKEN else SummarySection.FAILURES

        if (cleanName.isEmpty() && cleanArgs.isEmpty() && observation.isBlank()) {
            audit.record(section, DropReason.EMPTY, "", 0)
            return null
        }

        val flat = StructuredSummary.oneLine(observation)
        val capped = if (flat.length > maxObservationChars) {
            val kept = flat.surrogateSafeTake(maxObservationChars)
            audit.record(section, DropReason.TRUNCATED, kept, flat.length)
            kept
        } else {
            flat
        }

        return ActionLine(cleanName, cleanArgs, capped, success)
    }

    /**
     * Make the summary fit, in two phases.
     *
     * ## Phase 1 — shrink observations
     *
     * The tool name and the arguments are the protected part of an action line
     * (see [ActionLine]); the observation is not. So before anything is evicted,
     * oversized observations are shortened until the summary fits. This is what
     * makes "tool names and arguments survive compaction" true in practice: a
     * 2000-char observation is shortened, not the whole line discarded, and the
     * model still learns that `files.share` was already called.
     *
     * ## Phase 2 — evict by value
     *
     * Order is [SummarySection.EVICTION_ORDER], oldest entry first within each
     * section: recent failures are the ones a model must not repeat, so the
     * oldest is the cheapest to lose. Only reached when shrinking was not enough.
     *
     * ## The task
     *
     * Exempt from both phases. If the task alone exceeds the budget, the summary
     * comes back over budget with [CompactionAudit.withinBudget] false and the
     * budget-loss count at zero — an honest "this could not fit" rather than a
     * silently mutilated request.
     */
    private fun enforceBudget(summary: StructuredSummary, audit: AuditSink): StructuredSummary {
        val renderCap: Int = minOf(config.maxEntryChars, config.maxRenderedChars)

        val progress = summary.progress.toMutableList()
        val facts = summary.knownFacts.toMutableList()
        val remaining = summary.remainingWork.toMutableList()
        val actions = summary.actionsTaken.toMutableList()
        val failures = summary.failures.toMutableList()

        fun current() = StructuredSummary(
            task = summary.task,
            progress = progress,
            knownFacts = facts,
            actionsTaken = actions,
            failures = failures,
            remainingWork = remaining,
            generation = summary.generation,
        )

        // (1) Shrink observations until the summary fits.
        //
        // Iterative, and shrinking the LONGEST observation by exactly the
        // current overflow, because one pass is not enough: a line that is
        // individually "small enough" can still push the summary over once every
        // line is counted. A single pass leaves the summary over budget and falls
        // through to eviction, which discards the tool name and arguments — the
        // exact loss this phase exists to avoid.
        //
        // Deterministic: longest first, ties broken by section ordinal then
        // insertion index. Bounded: each iteration strictly reduces total
        // observation length, so it terminates in at most (total observation
        // chars) iterations, and in practice a handful.
        while (true) {
            val rendered = current().render(renderCap)
            if (fits(rendered)) break
            val overflow = rendered.length - config.maxRenderedChars
            if (overflow <= 0) break

            // Longest observation first: cutting the biggest line needs the
            // fewest iterations to reach the budget. Ties break on section
            // ordinal then insertion index, so the choice is never hash- or
            // iteration-order-dependent.
            val target = (actions.indices.map { SummarySection.ACTIONS_TAKEN to it } +
                failures.indices.map { SummarySection.FAILURES to it })
                .maxWithOrNull(
                    compareBy<Pair<SummarySection, Int>> {
                        when (it.first) {
                            SummarySection.ACTIONS_TAKEN -> actions[it.second].observation.length
                            else -> failures[it.second].observation.length
                        }
                    }.thenBy { it.first.ordinal }.thenBy { it.second },
                )
                ?: break

            val line = when (target.first) {
                SummarySection.ACTIONS_TAKEN -> actions[target.second]
                else -> failures[target.second]
            }
            if (line.observation.isEmpty()) break // Nothing left to shrink.

            val cut = minOf(overflow, line.observation.length).coerceAtLeast(1)
            val reduced = line.copy(
                observation = line.observation.surrogateSafeTake(line.observation.length - cut),
            )
            audit.record(target.first, DropReason.TRUNCATED, reduced.observation, line.observation.length)
            if (target.first == SummarySection.ACTIONS_TAKEN) actions[target.second] = reduced
            else failures[target.second] = reduced
        }

        if (fits(current().render(renderCap))) return current()

        // (2) Evict whole entries, lowest value first, oldest first within each.
        for (section in SummarySection.EVICTION_ORDER) {
            while (true) {
                val empty = when (section) {
                    SummarySection.PROGRESS -> progress.isEmpty()
                    SummarySection.KNOWN_FACTS -> facts.isEmpty()
                    SummarySection.REMAINING_WORK -> remaining.isEmpty()
                    SummarySection.ACTIONS_TAKEN -> actions.isEmpty()
                    SummarySection.FAILURES -> failures.isEmpty()
                    SummarySection.TASK -> true
                }
                if (empty) break

                when (section) {
                    SummarySection.PROGRESS ->
                        audit.record(section, DropReason.DISCARDED, progress.removeAt(0))
                    SummarySection.KNOWN_FACTS ->
                        audit.record(section, DropReason.DISCARDED, facts.removeAt(0))
                    SummarySection.REMAINING_WORK ->
                        audit.record(section, DropReason.DISCARDED, remaining.removeAt(0))
                    SummarySection.ACTIONS_TAKEN ->
                        audit.record(section, DropReason.DISCARDED, describe(actions.removeAt(0)))
                    SummarySection.FAILURES ->
                        audit.record(section, DropReason.DISCARDED, describe(failures.removeAt(0)))
                    SummarySection.TASK -> Unit
                }

                if (fits(current().render(renderCap))) return current()
            }
        }
        return current()
    }

    private fun fits(rendered: String): Boolean =
        rendered.length <= config.maxRenderedChars &&
            estimateTokens(rendered) <= config.maxRenderedTokens

    private fun estimateTokens(text: String): Int =
        (text.length + config.charsPerToken - 1) / config.charsPerToken

    /** Enough text for the audit to identify an evicted entry. Not a dump. */
    private fun describe(entry: Any): String = when (entry) {
        is String -> entry
        is ActionLine -> "${entry.toolName}(${entry.arguments})"
        else -> entry.toString()
    }

    /**
     * A capped, deduplicating per-section collector.
     *
     * The dedup set is bounded by [cap] for the same reason the entries are:
     * §16 forbids an unbounded structure on a phone. A session long enough to
     * evict an entry will re-accept a later duplicate of it, which costs one
     * line and cannot lose information.
     *
     * Eviction is oldest-first because the newest entries describe the most
     * recent state, which is the state the model has not seen yet.
     */
    private class Section<T : Any>(
        private val section: SummarySection,
        private val cap: Int,
        private val audit: AuditSink,
    ) {
        private val entries = ArrayList<T>(cap)

        /** Insertion-ordered so the oldest key is always the first one. */
        private val seen = LinkedHashSet<String>(cap)

        fun add(entry: T) {
            val key = keyOf(entry)
            if (seen.contains(key)) {
                audit.record(section, DropReason.DUPLICATE, describeEntry(entry))
                return
            }
            if (!hasContent(entry)) {
                audit.record(section, DropReason.EMPTY, "", 0)
                return
            }
            entries.add(entry)
            seen.add(key)
            if (entries.size > cap) {
                val oldest = seen.iterator().next()
                seen.remove(oldest)
                // Audited, not silent. A section that quietly overflowed its cap
                // is indistinguishable from one that never saw the content.
                audit.record(section, DropReason.SECTION_CAP, describeEntry(entries.removeAt(0)))
            }
        }

        fun snapshot(): List<T> = entries.toList()

        private fun keyOf(entry: T): String = when (entry) {
            is ActionLine -> entry.dedupeKey
            is String -> entry
            else -> entry.toString()
        }

        private fun describeEntry(entry: T): String = when (entry) {
            is ActionLine -> "${entry.toolName}(${entry.arguments})"
            is String -> entry
            else -> entry.toString()
        }

        /**
         * "Has something worth showing the model". A blank line is not content,
         * and a failure whose error text vanished is worse than no failure line,
         * because it tells the model the call succeeded.
         */
        private fun hasContent(entry: T): Boolean = when (entry) {
            is String -> entry.isNotEmpty()
            is ActionLine ->
                entry.toolName.isNotEmpty() || entry.arguments.isNotEmpty() ||
                    entry.observation.isNotEmpty()
            else -> true
        }
    }

    /**
     * Bounded record of what was removed.
     *
     * WHY bounded: a 10,000-message history that loses 10,000 entries would
     * build a 10,000-entry audit, on a phone, inside the call the user is
     * waiting on. [CompactionConfig.maxAuditEntries] caps the list; [total] keeps
     * the honest count so a truncated audit can never understate the loss.
     */
    private class AuditSink(private val cap: Int) {
        private val recorded = ArrayList<DroppedEntry>(minOf(cap, 32))

        var total: Int = 0
            private set

        /**
         * Drops that cost nothing: a replayed call, or an event with no text.
         * Everything else in [total] is real information loss.
         */
        var harmlessCount: Int = 0
            private set

        fun record(section: SummarySection, reason: DropReason, text: String, originalChars: Int = text.length) {
            total += 1
            if (reason == DropReason.DUPLICATE || reason == DropReason.EMPTY) harmlessCount += 1
            if (recorded.size < cap) {
                recorded.add(DroppedEntry(section, reason, text, originalChars))
            }
        }

        val entries: List<DroppedEntry> get() = recorded
    }
}
