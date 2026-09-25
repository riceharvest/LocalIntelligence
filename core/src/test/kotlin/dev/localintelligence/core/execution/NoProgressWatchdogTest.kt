package dev.localintelligence.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The no-progress watchdog, driven entirely by a [FakeExecutionClock].
 *
 * The counting rule needs no clock at all. The *time* rule is the reason the
 * clock is injected: a 60-second stall would take 60 real seconds to test, and
 * would therefore never be tested. Here the fake clock is advanced by hand, so
 * a 90-second wedge is asserted in microseconds.
 */
class NoProgressWatchdogTest {

    @Test
    fun theFirstStepIsAlwaysProgress() {
        val watchdog = NoProgressWatchdog(stallTimeoutMs = null)
        assertEquals(NoProgressWatchdog.Verdict.Progress, watchdog.record("first observation"))
    }

    @Test
    fun aChangedObservationResetsTheStreak() {
        val watchdog = NoProgressWatchdog(stallTimeoutMs = null)
        watchdog.record("a")
        watchdog.record("a")
        watchdog.record("a")

        // Different information means the world changed, even though the tool is
        // the same one and the step count keeps climbing.
        assertEquals(NoProgressWatchdog.Verdict.Progress, watchdog.record("b"))
        assertEquals(0, watchdog.noProgressStreak)
    }

    @Test
    fun aRunTerminatesAfterNUnchangedSteps() {
        val watchdog = NoProgressWatchdog(noProgressLimit = 3, stallTimeoutMs = null)

        watchdog.record("same")
        assertEquals(NoProgressWatchdog.Verdict.NoProgress(1), watchdog.record("same"))
        assertEquals(NoProgressWatchdog.Verdict.NoProgress(2), watchdog.record("same"))
        // The third unchanged step is the limit and must terminate, not merely warn.
        val verdict = watchdog.record("same")
        assertTrue("expected termination, got $verdict", verdict is NoProgressWatchdog.Verdict.Terminate)
        assertEquals(3, (verdict as NoProgressWatchdog.Verdict.Terminate).streak)
    }

    @Test
    fun aLimitOfTwoTerminatesOnTheSecondRepeatedStep() {
        val watchdog = NoProgressWatchdog(noProgressLimit = 2, stallTimeoutMs = null)
        // The first record is always Progress: there is nothing to compare it
        // against yet. "Unchanged" begins at the second, so a limit of 2
        // tolerates one repeat and terminates on the second.
        assertEquals(NoProgressWatchdog.Verdict.Progress, watchdog.record("same"))
        assertEquals(NoProgressWatchdog.Verdict.NoProgress(1), watchdog.record("same"))
        assertTrue(
            "the second repeated step reaches a limit of two",
            watchdog.record("same") is NoProgressWatchdog.Verdict.Terminate,
        )
    }

    @Test
    fun identicalObservationsFromDifferentToolsStillStall() = run {
        // The case LoopDetector cannot catch: no *call* ever repeats, so a
        // repetition-based detector sees fresh work while the model learns
        // nothing. A 1B model does exactly this, alternating between two tools
        // that both return the same "nothing found".
        //
        // Note the fingerprint is the OBSERVATION, not the tool: the watchdog
        // asks "did the model learn anything", and which tool it asked is not
        // something it learned.
        val watchdog = NoProgressWatchdog(noProgressLimit = 3, stallTimeoutMs = null)

        // web.fetch, then calendar.search, then web.fetch again. Four steps, all
        // returning the same text.
        watchdog.record("no results found")            // streak 0: Progress
        assertEquals(
            NoProgressWatchdog.Verdict.NoProgress(1),
            watchdog.record("no results found"),        // streak 1
        )
        assertEquals(
            NoProgressWatchdog.Verdict.NoProgress(2),
            watchdog.record("no results found"),        // streak 2
        )
        assertTrue(
            "the third repeat must reach the limit of three",
            watchdog.record("no results found") is NoProgressWatchdog.Verdict.Terminate,
        )
    }

    @Test
    fun resetClearsTheStreak() {
        val watchdog = NoProgressWatchdog(noProgressLimit = 2, stallTimeoutMs = null)
        watchdog.record("same")
        watchdog.record("same")
        assertEquals(1, watchdog.noProgressStreak)

        watchdog.reset()

        assertEquals(0, watchdog.noProgressStreak)
        // A fresh run must not inherit the previous run's stall. This is the
        // assertion that matters: without it, a second task in the same process
        // would start already one step from termination.
        assertEquals(NoProgressWatchdog.Verdict.Progress, watchdog.record("same"))
    }

    // ------------------------------------------------------------- the time rule

    @Test
    fun aStallShorterThanTheTimeoutDoesNotTerminate() {
        val clock = FakeExecutionClock()
        val watchdog = NoProgressWatchdog(
            noProgressLimit = 100,
            stallTimeoutMs = 60_000,
            clock = clock,
        )

        watchdog.record("same")
        clock.advance(59_000)

        // 59s of nothing is slow, not broken. Terminating here would kill a run
        // that was merely working through a slow tool.
        assertFalse(watchdog.hasStalledFor())
        assertTrue(watchdog.record("same") is NoProgressWatchdog.Verdict.NoProgress)
    }

    @Test
    fun aStallLongerThanTheTimeoutTerminatesWithoutWaiting() {
        val clock = FakeExecutionClock()
        val watchdog = NoProgressWatchdog(
            // A high count-limit isolates the *time* rule: if this terminates, it
            // is because of elapsed time, not because of the streak.
            noProgressLimit = 1_000,
            stallTimeoutMs = 60_000,
            clock = clock,
        )

        watchdog.record("same")
        clock.advance(90_000)
        // One identical step so the streak is non-zero: "no progress" is only
        // meaningful once the world has actually failed to change.
        watchdog.record("same")
        clock.advance(90_000)

        // 180 virtual seconds, asserted instantly. On a real clock this test would
        // take three minutes, which is why it would never be written.
        assertTrue(watchdog.hasStalledFor())
        val verdict = watchdog.record("same")
        assertTrue("the time rule alone must terminate", verdict is NoProgressWatchdog.Verdict.Terminate)
        assertEquals(180_000, (verdict as NoProgressWatchdog.Verdict.Terminate).stalledForMs)
    }

    @Test
    fun progressResetsTheStallClock() {
        val clock = FakeExecutionClock()
        val watchdog = NoProgressWatchdog(noProgressLimit = 100, stallTimeoutMs = 60_000, clock = clock)

        watchdog.record("a")
        clock.advance(50_000)
        watchdog.record("b")
        clock.advance(50_000)

        // 100s of total runtime, but never 60s of *no* progress. Elapsed time
        // alone must not kill a run that is still learning things.
        assertFalse(watchdog.hasStalledFor())
    }

    @Test
    fun aDisabledStallRuleNeverFiresOnTime() {
        val clock = FakeExecutionClock()
        val watchdog = NoProgressWatchdog(noProgressLimit = 100, stallTimeoutMs = null, clock = clock)

        watchdog.record("same")
        clock.advance(10_000_000)

        assertFalse(watchdog.hasStalledFor())
    }

    @Test
    fun aFakeClockRefusesToGoBackwards() {
        val clock = FakeExecutionClock()
        try {
            clock.advance(-1)
            org.junit.Assert.fail("a monotonic clock must reject a negative delta")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("backwards"))
        }
    }

    @Test
    fun invalidConfigurationIsRejectedAtConstruction() {
        try {
            NoProgressWatchdog(noProgressLimit = 0)
            org.junit.Assert.fail("a zero limit would terminate every run immediately")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("noProgressLimit"))
        }
        try {
            NoProgressWatchdog(stallTimeoutMs = 0)
            org.junit.Assert.fail("a zero stall timeout would terminate on the first step")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("stallTimeoutMs"))
        }
    }

    // ------------------------------------------------------------------- nudges

    @Test
    fun theNudgeIsNullWhileProgressContinues() {
        val watchdog = NoProgressWatchdog(stallTimeoutMs = null)
        assertNull(watchdog.nudgeIfStalling("a"))
    }

    @Test
    fun theNudgeUsesCorrectGrammarForOneStep() {
        val watchdog = NoProgressWatchdog(noProgressLimit = 5, stallTimeoutMs = null)
        watchdog.record("same")

        val nudge = watchdog.nudgeIfStalling("same")!!

        // "the previous 1 steps" reads as machine text, and a model that cannot
        // parse the nudge tends to ignore it.
        assertTrue(nudge.contains("previous step."))
        assertFalse(nudge.contains("1 steps"))
    }

    @Test
    fun theTerminalNudgeTellsTheModelToStopRepeating() {
        val watchdog = NoProgressWatchdog(noProgressLimit = 1, stallTimeoutMs = null)
        watchdog.record("same")
        val nudge = watchdog.nudgeIfStalling("same")!!

        assertTrue("must report the stall: $nudge", nudge.contains("no progress"))
        assertTrue("must forbid the repeat: $nudge", nudge.contains("will not help"))
    }

    @Test
    fun theThreeNudgesAreDistinctMessages() {
        val progress = NoProgressWatchdog(noProgressLimit = 5, stallTimeoutMs = null)
        assertNull(progress.nudgeIfStalling("a"))

        val stalling = NoProgressWatchdog(noProgressLimit = 5, stallTimeoutMs = null)
        stalling.record("same")
        val nudgeOne = stalling.nudgeIfStalling("same")!!

        val terminating = NoProgressWatchdog(noProgressLimit = 1, stallTimeoutMs = null)
        terminating.record("same")
        val nudgeTwo = terminating.nudgeIfStalling("same")!!

        // "Keep going", "you are going nowhere", and "stop" are three different
        // instructions. Collapsing them into one string is how a model gets told
        // to keep trying after it should have answered.
        assertNotEquals(nudgeOne, nudgeTwo)
    }
}
