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


