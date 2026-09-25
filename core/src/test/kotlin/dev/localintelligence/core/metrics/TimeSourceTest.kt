package dev.localintelligence.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock. Every duration assertion in this package depends on a [FakeTimeSource]
 * that hands out exact nanoseconds, so "exactly 120 ms" has to actually mean
 * exactly 120 ms. No test here sleeps, and none of them can.
 */
class TimeSourceTest {

    @Test
    fun `a fake clock hands out exact known durations`() {
        val time = FakeTimeSource()
        assertEquals(0L, time.nanoTime())

        time.advanceMs(120)
        assertEquals(120_000_000L, time.nanoTime())

        time.advanceMs(30)
        assertEquals(150_000_000L, time.nanoTime())
    }

    @Test
    fun `sub-millisecond movement is preserved and floors to whole milliseconds`() {
        val time = FakeTimeSource()
        time.advanceNanos(1_500_000) // 1.5 ms
        assertEquals(1_500_000L, time.nanoTime())
        // A reported duration must never exceed the time that actually elapsed.
        assertEquals(1L, Stopwatch(time, originNanos = 0L).elapsedMs())
    }

    @Test
    fun `a fake clock refuses to run backwards`() {
        val time = FakeTimeSource()
        val thrown = runCatching { time.advanceNanos(-1) }.exceptionOrNull()
        assertTrue("a clock must not move backwards", thrown is IllegalArgumentException)
    }

    @Test
    fun `a stopwatch measures exactly what the fake clock was told to advance`() {
        val time = FakeTimeSource()
        val watch = Stopwatch(time)

        time.advanceMs(40)
        assertEquals(40L, watch.elapsedMs())

        time.advanceMs(2)
        assertEquals(42L, watch.elapsedMs())
        assertEquals(42_000_000L, watch.elapsedNanos())
    }

    @Test
    fun `reset re-anchors the stopwatch so a step duration is not a run duration`() {
        val time = FakeTimeSource()
        val watch = Stopwatch(time)
        time.advanceMs(100)

        watch.reset()
        assertEquals(0L, watch.elapsedMs())

        time.advanceMs(5)
        assertEquals(5L, watch.elapsedMs())
    }

    @Test
    fun `measure returns the value and its exact duration together`() {
        val time = FakeTimeSource()
        val watch = Stopwatch(time)
        val timed = watch.measure {
            time.advanceMs(250)
            "done"
        }
        assertEquals("done", timed.value)
        assertEquals(250L, timed.durationMs)
    }

    @Test
    fun `the system clock is monotonic within a single call pair`() {
        // Not a timing assertion — a regression guard that SystemTimeSource
        // actually delegates to a monotonic source rather than the wall clock.
        val first = SystemTimeSource.nanoTime()
        val second = SystemTimeSource.nanoTime()
        assertTrue("SystemTimeSource must not go backwards", second >= first)
    }
}
