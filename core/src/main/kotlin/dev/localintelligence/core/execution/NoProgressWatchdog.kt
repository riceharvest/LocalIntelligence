package dev.localintelligence.core.execution

/**
 * Terminates a run after N consecutive steps that changed nothing.
 *
 * ## Why this is separate from the loop
 *
 * `LoopDetector` already refuses a *repeated identical call*, and the loop has a
 * `maxSteps` ceiling. This covers the case neither does: a run that keeps
 * *trying different things* and learning nothing, or that alternates between two
 * useless calls so no call ever repeats twice in a row. A 1B model does exactly
 * that — `web.fetch`, then `device.battery`, then `web.fetch` again — and it
 * will do it until the step limit, spending a minute of the user's battery to
 * reach the same conclusion.
 *
 * ## Why it takes a clock
 *
 * "No progress" is not only a count. A step that changes nothing in 2 seconds is
 * a fast dead end; a step that changes nothing for 90 seconds is a wedged
 * device. Both should stop the run, but they are different bugs, and a component
 * that can only count cannot tell them apart in a trace. [hasStalledFor] is the
 * time-based half, and it is driven by the injected [ExecutionClock] so the test
 * suite exercises a 90-second stall in microseconds.
 *
 * ## What counts as progress
 *
 * Deliberately conservative: a step is progress only if it produced a *different
 * observation* than the previous one. A new tool call is not progress if it
 * returned the same text, because from the model's point of view the world did
 * not change. Encoding this in one comparison keeps the rule auditable, which a
 * set of heuristics would not be.
 */
class NoProgressWatchdog(
    /** Consecutive unchanged steps that terminate the run. */
    private val noProgressLimit: Int = DEFAULT_NO_PROGRESS_LIMIT,
    /**
     * Also terminate after this long with no change. `null` disables the
     * time-based rule, which is the right default for a test suite that wants to
     * assert the counting rule alone.
     */
    private val stallTimeoutMs: Long? = DEFAULT_STALL_TIMEOUT_MS,
    private val clock: ExecutionClock = ExecutionClock.SYSTEM,
) {
    init {
        require(noProgressLimit > 0) { "noProgressLimit must be positive" }
        require(stallTimeoutMs == null || stallTimeoutMs > 0) {
            "stallTimeoutMs must be positive when set, was $stallTimeoutMs"
        }
    }

    private var streak: Int = 0
    private var lastFingerprint: String? = null
    private var lastChangeAt: Long = clock.elapsedMillis()

    /** Consecutive unchanged steps so far. */
    val noProgressStreak: Int get() = streak

    /**
     * Records one step and reports whether the run must stop.
     *
     * [fingerprint] is "what the model now knows that it did not know before" —
     * in practice the **observation text**, not the tool name.
     *
     * WHY observation-only, and not tool-plus-observation: a model that
     * alternates `web.fetch` and `calendar.search`, both returning "no results
     * found", has made no progress on any step. Fingerprinting the tool name
     * would call that fresh work every time and miss the loop entirely — which
     * is precisely the loop a 1B model falls into. The tool that produced an
     * observation is not information; the observation is.
     *
     * Counting, stated precisely because it is easy to get wrong: the first
     * recorded step is always [Verdict.Progress] — there is nothing to compare it
     * against yet. Every subsequent step that repeats the previous fingerprint
     * increments the streak, so [noProgressLimit] `N` terminates on the **Nth
     * repeated** step, not the Nth step overall. A limit of 2 therefore tolerates
     * one repeat and stops on the second.
     */
    fun record(fingerprint: String): Verdict {
        val previous = lastFingerprint
        if (previous == null || previous != fingerprint) {
            lastFingerprint = fingerprint
            lastChangeAt = clock.elapsedMillis()
            streak = 0
            return Verdict.Progress
        }
        streak += 1
        return if (streak >= noProgressLimit || hasStalledFor()) {
            Verdict.Terminate(streak, clock.elapsedMillis() - lastChangeAt)
        } else {
            Verdict.NoProgress(streak)
        }
    }

    /**
     * True when the world has not changed for [stallTimeoutMs].
     *
     * Separate from [record] so a caller polling between steps — rather than
     * stepping — can still be stopped. Returns false when the rule is disabled.
     */
    fun hasStalledFor(nowMs: Long = clock.elapsedMillis()): Boolean {
        val limit = stallTimeoutMs ?: return false
        return streak > 0 && nowMs - lastChangeAt >= limit
    }

    /**
     * Records a step and returns the model-visible nudge when the run should be
     * told it is going nowhere, or null when it is making progress.
     *
     * WHY the nudge is a string here: the loop needs to say something *before*
     * terminating, and the wording is the difference between a model that
     * changes course and one that repeats itself. Centralising it here means the
     * text is asserted by a unit test instead of by reading the loop.
     */
    fun nudgeIfStalling(fingerprint: String): String? =
        when (val verdict = record(fingerprint)) {
            is Verdict.Terminate -> "You have made no progress for ${verdict.streak} steps" +
                (if (verdict.stalledForMs > 0) " (${verdict.stalledForMs}ms)" else "") +
                ". Repeating this approach will not help. Answer the user with what you " +
                "have, or take one clearly different action."

            is Verdict.NoProgress -> {
                // Grammatical number matters more than it looks: "the previous 1
                // steps" reads as machine text, and a model that cannot parse the
                // nudge tends to ignore it.
                val count = verdict.streak
                val phrase = if (count == 1) "step" else "$count steps"
                "That returned the same information as the previous $phrase. " +
                    "Try something different or answer now."
            }

            is Verdict.Progress -> null
        }

    /** Clears all state. Called when a run starts. */
    fun reset() {
        streak = 0
        lastFingerprint = null
        lastChangeAt = clock.elapsedMillis()
    }

    /** The decision for one step. */
    sealed interface Verdict {
        /** The world changed. Keep going. */
        data object Progress : Verdict

        /** Unchanged, but under the limit. */
        data class NoProgress(val streak: Int) : Verdict

        /** Unchanged past the limit, or for too long. Stop the run. */
        data class Terminate(val streak: Int, val stalledForMs: Long) : Verdict
    }

    companion object {
        /**
         * 3, matching `LoopDetector`'s no-progress default.
         *
         * WHY not lower: a legitimate agent run often needs two attempts before
         * it finds the right tool. WHY not higher: three unchanged steps on a
         * phone is already several seconds and a visibly dead conversation.
         */
        const val DEFAULT_NO_PROGRESS_LIMIT = 3

        /**
         * 60s of wall clock with no change. Longer than any single legitimate
         * tool call (the default budget is 30s), so it only fires when several
         * slow calls have all returned nothing — the wedge, not the work.
         */
        const val DEFAULT_STALL_TIMEOUT_MS = 60_000L
    }
}
