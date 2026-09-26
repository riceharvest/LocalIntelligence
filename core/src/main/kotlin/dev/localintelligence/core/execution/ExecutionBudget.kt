package dev.localintelligence.core.execution

/**
 * A monotonic time source, injected rather than read from [System].
 *
 * WHY inject it: every timing rule in this package is a *policy* decision
 * (30s for a socket, 3 stalled steps), and a policy that can only be exercised
 * by waiting is a policy nobody can exercise. This seam is what lets a caller
 * drive a fake clock forward instantly while the real device keeps real
 * deadlines.
 *
 * Implementations must be monotonic: a deadline computed as `start + budget` is
 * only meaningful if the clock never goes backwards. `System.currentTimeMillis`
 * does go backwards (NTP, user changes the clock), which is why the default
 * implementation is `nanoTime`-based and why a wall-clock implementation here
 * would be a bug.
 */
fun interface ExecutionClock {
    /** Monotonic milliseconds. Only differences between two readings are used. */
    fun elapsedMillis(): Long

    companion object {
        /** The production clock. */
        val SYSTEM: ExecutionClock = ExecutionClock { System.nanoTime() / 1_000_000 }
    }
}

/**
 * A clock the test advances by hand.
 *
 * Top-level rather than nested in [ExecutionClock] so it can be referred to as
 * `ExecutionClock.Fake` from a test without the companion-resolution gymnastics a
 * `fun interface` forces.
 *
 * NOT thread-safe by accident: every read and write goes through the same
 * monitor, so a test hammering [advance] from several threads — the
 * cancellation race test does — cannot observe a torn value.
 */
class FakeExecutionClock(start: Long = 0) : ExecutionClock {
    private var now = start

    @Synchronized
    override fun elapsedMillis(): Long = now

    /** Moves virtual time forward. Never accepts a negative delta. */
    @Synchronized
    fun advance(millis: Long) {
        require(millis >= 0) { "a monotonic clock cannot go backwards" }
        now += millis
    }
}

/**
 * A budget for one operation, plus the machinery to ask "how much is left".
 *
 * WHY a type rather than a bare `Long`: a raw millisecond count has no unit at
 * the call site, no ceiling, and no way to say "unbounded", and every one of
 * those omissions eventually becomes a zero that means "no timeout" or an hour
 * that means "the phone is dead". Clamping in the constructor makes the
 * degenerate values unrepresentable at the boundary.
 */
data class ExecutionBudget(
    val timeoutMs: Long,
    /** Free-text provenance, surfaced in observations and traces. */
    val label: String = "default",
) {
    init {
        // Zero is a legitimate "give up immediately" but a *negative* budget is
        // always a bug, and clamping here is cheaper than debugging it later.
        require(timeoutMs >= 0) { "budget must not be negative: $timeoutMs" }
    }

    /** True when this budget places no ceiling at all. */
    val unbounded: Boolean get() = timeoutMs == UNBOUNDED

    /**
     * Milliseconds left against [startedAt], floored at zero.
     *
     * WHY floor at zero rather than let it go negative: a caller that does
     * `withTimeout(remaining)` with a negative argument throws
     * `IllegalArgumentException` in some coroutine versions and treats it as
     * "already expired" in others. Flooring makes "the deadline passed" a single
     * well-defined zero.
     */
    fun remainingMs(startedAt: Long, now: Long): Long =
        if (unbounded) Long.MAX_VALUE else (timeoutMs - (now - startedAt)).coerceAtLeast(0)

    /** True when the budget is spent. Unbounded budgets never expire. */
    fun isExpired(startedAt: Long, now: Long): Boolean =
        !unbounded && now - startedAt >= timeoutMs

    companion object {
        /**
         * The sentinel for "no ceiling". A distinct value rather than `0`, so a
         * misconfigured tool that computes 0 by accident fails fast (immediately
         * timed out) instead of silently running unbounded — which on a phone is
         * the failure mode that drains the battery.
         */
        const val UNBOUNDED: Long = Long.MAX_VALUE

        /**
         * Default tool budget.
         *
         * 30s: long enough for a slow TLS handshake on mobile data, short enough
         * that a wedged call does not outlive the user's patience or the
         * foreground timeout that kills the process anyway. Derived from the
         * web tool's own 8s connect + 10s read pair, with headroom for retries
         * inside a single call.
         */
        val DEFAULT: ExecutionBudget = ExecutionBudget(30_000, "default tool budget")
    }
}
