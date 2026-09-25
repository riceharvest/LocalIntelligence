package dev.localintelligence.core.metrics

/**
 * The only clock instrumented code is allowed to read.
 *
 * WHY an interface and not `System.nanoTime()`: a metric that is measured with
 * the real clock is not a metric, it is a measurement you cannot assert on. Every
 * duration in [RunMetrics] therefore comes from an injected [TimeSource], so a
 * test can drive an exact, known timeline with no sleeping and no flakiness. The
 * eval suite's rule — "nothing is random, nothing sleeps, nothing touches a
 * device" — extends to time itself.
 *
 * WHY monotonic and not wall-clock: `System.currentTimeMillis()` can jump
 * backwards when NTP corrects, which turns a run duration into a negative
 * number and silently poisons a p95. The interface has no wall-clock method at
 * all, which is the only way to keep that mistake unmakeable.
 */
interface TimeSource {
    /**
     * Monotonic nanoseconds since an arbitrary origin.
     *
     * Only differences between two readings are meaningful — never the value
     * itself, which is why [Stopwatch] captures its origin at construction.
     */
    fun nanoTime(): Long
}

/**
 * The production clock: [System.nanoTime].
 *
 * A JVM-singleton rather than a class you construct, so the production path has
 * exactly one place that can drift. Tests never use it — they use
 * [FakeTimeSource] and get exact numbers.
 */
object SystemTimeSource : TimeSource {
    override fun nanoTime(): Long = System.nanoTime()
}

/**
 * A clock the test drives by hand. Never sleeps, never reads the machine.
 *
 * WHY it advances in explicit millisecond steps: an assertion like
 * "this step took exactly 120 ms" is only possible if the test decides what
 * elapses. A fake that scaled with a real thread would make every timing test
 * a tolerance test, and tolerance tests are how flakiness enters a suite.
 */
class FakeTimeSource(
    startNanos: Long = 0L,
) : TimeSource {
    private var now: Long = startNanos

    override fun nanoTime(): Long = now

    /** Moves the clock forward. Negative deltas are rejected, not silently applied. */
    fun advanceMs(millis: Long): Long = advanceNanos(millis * NANOS_PER_MS)

    /** Sub-millisecond movement, for exercising the nanosecond remainder path. */
    fun advanceNanos(nanos: Long): Long {
        require(nanos >= 0) { "FakeTimeSource cannot run backwards (asked for $nanos ns)" }
        now += nanos
        return now
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/**
 * An elapsed-time measurement anchored at one instant.
 *
 * WHY this exists instead of `val t0 = nanoTime()` scattered through the loop:
 * the un-subtracted timestamp is a leak waiting to happen. A [Stopwatch] is
 * created at the start of a phase and read at its end, so a duration can never
 * be paired with the wrong origin and the pairing is visible in the code.
 */
class Stopwatch(
    private val time: TimeSource,
    /** Anchored at construction, so every read on this stopwatch is comparable. */
    private var originNanos: Long = time.nanoTime(),
) {
    /** Whole milliseconds since the origin. Floors, never rounds — a reported
     *  duration must never exceed the time that actually elapsed. */
    fun elapsedMs(): Long = (time.nanoTime() - originNanos) / NANOS_PER_MS

    /** Exact nanoseconds, for callers that need sub-millisecond resolution. */
    fun elapsedNanos(): Long = time.nanoTime() - originNanos

    /**
     * Restarts the stopwatch in place.
     *
     * Used at step boundaries, where "how long did *this step* take" is the
     * question rather than "how long has the run taken".
     */
    fun reset() {
        originNanos = time.nanoTime()
    }

    /**
     * Runs [block], returning both its result and how long it took.
     *
     * The one place instrumentation is allowed to wrap a phase. Everything else
     * in this package measures by holding a stopwatch across explicit
     * begin/end calls, because a suspend phase cannot be wrapped by a
     * non-suspend lambda and pretending otherwise would have produced an API
     * that silently cannot time the thing it exists to time.
     */
    fun <T> measure(block: () -> T): Timed<T> {
        val start = time.nanoTime()
        val value = block()
        return Timed(value, (time.nanoTime() - start) / NANOS_PER_MS)
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}

/** A value plus how long producing it took. */
data class Timed<out T>(val value: T, val durationMs: Long)
