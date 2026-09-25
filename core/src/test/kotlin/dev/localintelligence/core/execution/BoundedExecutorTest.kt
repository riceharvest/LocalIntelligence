package dev.localintelligence.core.execution

import dev.localintelligence.core.tool.ToolError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Deadline behaviour for a *blocking* body, on the bounded pool.
 *
 * These tests use real threads, because a thread that ignores an interrupt is
 * the thing under test and cannot be faked. What they do not do is sleep: a
 * blocked body parks on a latch and the test releases it, so the whole file
 * finishes in milliseconds while still exercising the real race between a
 * deadline, an interrupt, and a thread that ignores one.
 */
class BoundedExecutorTest {

    /** Short enough to keep the suite instant, long enough not to be a race. */
    private val budget = ExecutionBudget(120, "test budget")

    @Test
    fun aFastCallSucceeds() {
        val executor = BoundedExecutor()
        val outcome = executor.execute(Fixtures.instant("hello"), budget = budget)
        assertTrue(outcome is ExecutionOutcome.Succeeded)
        assertEquals("hello", outcome.value)
    }

    @Test
    fun aCallThatBlocksIsStoppedAtItsDeadline() {
        val gate = Fixtures.Gate()
        val executor = BoundedExecutor()
        try {
            val outcome = executor.execute(
                Fixtures.uninterruptible(gate),
                budget = ExecutionBudget(80, "deadline test"),
                toolName = "web.fetch",
            )

            // The hard case: the deadline fired, and the waiter returned anyway.
            assertTrue("expected a timeout, got $outcome", outcome is ExecutionOutcome.TimedOut)
            val timedOut = outcome as ExecutionOutcome.TimedOut
            assertEquals(80, timedOut.budgetMs)
        } finally {
            gate.release()
        }
    }

    @Test
    fun aThreadThatIgnoresTheInterruptIsReportedAsAbandoned() {
        val gate = Fixtures.Gate()
        val executor = BoundedExecutor()
        try {
            val outcome = executor.execute(
                Fixtures.uninterruptible(gate),
                budget = ExecutionBudget(80, "abandon test"),
                toolName = "web.fetch",
            )

            // Honesty over comfort. The interrupt did not work, and the outcome
            // has to say so, because "stopped" alone would be a lie that shows
            // up later as a mysteriously saturated thread pool.
            assertTrue(outcome is ExecutionOutcome.TimedOut)
            val timedOut = outcome as ExecutionOutcome.TimedOut
            assertTrue("an uninterruptible call must be reported as abandoned", timedOut.abandoned)
            assertEquals(
                "a wedged call must be visible as a leak",
                1L,
                executor.leakedThreadCount,
            )
        } finally {
            gate.release()
        }
    }

    @Test
    fun theAbandonedObservationTellsTheModelNotToRetry() {
        val timedOut = ExecutionOutcome.TimedOut(30_000, abandoned = true)
        val text = timedOut.observation("web.fetch")

        // The whole point of the three-way split: a model told "it was slow"
        // retries, and retrying a slow tool is the most expensive mistake here.
        assertTrue("must forbid the identical retry: $text", text.contains("Do not call web.fetch again"))
        assertTrue("must say it was slow, not wrong: $text", text.contains("too slow"))
        // And the residual risk is disclosed rather than hidden.
        assertTrue("must disclose the stuck connection: $text", text.contains("still be open"))
    }

    @Test
    fun aThreadThatHonoursTheInterruptEventuallyReleasesItsSlot() {
        val executor = BoundedExecutor()
        val outcome = executor.execute(
            Fixtures.interruptible(),
            budget = ExecutionBudget(80, "cooperative test"),
            toolName = "file.read",
        )

        assertTrue(outcome is ExecutionOutcome.TimedOut)
        // At the deadline instant we cannot yet know the thread died — it may be
        // microseconds from honouring the interrupt. So the outcome is allowed to
        // say "not confirmed stopped" here. The settled fact is the leak count,
        // and that is what the contrast with the wedged test is really about: a
        // cooperative call gives its slot back, an uncooperative one does not.
        assertTrue(executor.awaitAbandonedQuiescence(5_000))
        assertEquals(
            "a call that honoured the interrupt must not stay counted as a leak",
            0L,
            executor.leakedThreadCount,
        )
    }

    @Test
    fun leakedThreadCountFallsBackToZeroOnceAStuckCallFinallyEnds() {
        val gate = Fixtures.Gate()
        val executor = BoundedExecutor()
        try {
            executor.execute(
                Fixtures.uninterruptible(gate),
                budget = ExecutionBudget(80, "leak accounting"),
            )
            assertEquals("the call is wedged, so it must be counted", 1L, executor.leakedThreadCount)

            // Let the stuck call actually finish, the way a socket would when the
            // server eventually answers. The leak must clear itself, or the count
            // is just a tally of timeouts and tells the operator nothing.
            gate.release()
            executor.awaitAbandonedQuiescence()

            assertEquals(
                "a call that eventually ended is no longer a leak",
                0L,
                executor.leakedThreadCount,
            )
        } finally {
            gate.release()
        }
    }

    @Test
    fun aThrowingToolBecomesAFailedOutcomeRatherThanAnException() {
        val executor = BoundedExecutor()
        val outcome = executor.execute(Fixtures.boom("disk on fire"), budget = budget)

        // An exception escaping into the agent loop kills a step that should
        // have recovered. It must arrive as a value.
        assertTrue(outcome is ExecutionOutcome.Failed)
        val failed = outcome as ExecutionOutcome.Failed
        assertEquals("IllegalStateException", failed.typeName)
        assertEquals("disk on fire", failed.detail)
    }

    @Test
    fun aFailureObservationOffersADifferentAttemptUnlikeATimeout() {
        val failure = ExecutionOutcome.Failed("IOException", "connection reset")
        val text = failure.observation("web.fetch")

        assertTrue("a real error may be retried differently: $text", text.contains("may try once"))
        assertTrue(text.contains("IOException"))
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun anAlreadyCancelledTokenSkipsTheCallEntirely() {
        val executor = BoundedExecutor()
        val token = CancellationToken.none()
        token.cancel("user stopped the run")
        val ran = Fixtures.counter()

        val outcome = executor.execute({ ran.set(true); "should not run" }, token, budget)

        assertTrue(outcome is ExecutionOutcome.Cancelled)
        // No thread, no call: a cancel that arrives before dispatch must be free.
        assertFalse("the block must not have run", ran.get())
    }

    @Test
    fun cancellingAnInFlightCallReportsCancelled() {
        val gate = Fixtures.Gate()
        val executor = BoundedExecutor()
        val token = CancellationToken.none()
        try {
            val started = CountDownLatch(1)
            val storm = Fixtures.ThreadStorm(threadCount = 1)
            val results = java.util.concurrent.atomic.AtomicReference<ExecutionOutcome<String>>()

            storm.run {
                // Cancel as soon as the work is actually under way, so this tests
                // cancellation of a running call rather than of a queued one.
                results.set(
                    executor.execute(
                        { started.countDown(); gate.arrive(); "finished anyway" },
                        token,
                        ExecutionBudget(30_000, "long"),
                    ),
                )
            }
            storm.release()
            assertTrue("the call should have started", started.await(5, TimeUnit.SECONDS))
            token.cancel("user pressed stop")

            assertTrue(storm.awaitDone())
            val outcome = results.get()
            assertTrue("expected cancelled, got $outcome", outcome is ExecutionOutcome.Cancelled)
            assertEquals("user pressed stop", (outcome as ExecutionOutcome.Cancelled).reason)
        } finally {
            gate.release()
        }
    }

    @Test
    fun aCancelledObservationSaysTheRunIsOverRatherThanSuggestingARetry() {
        val cancelled = ExecutionOutcome.Cancelled("user pressed stop")
        val text = cancelled.observation("web.fetch")

        // A cancelled call is `exhausted`: suggesting another attempt after the
        // user pressed stop is not a retry, it is disobedience.
        assertTrue("must not invite a retry: $text", text.contains("do not call web.fetch again"))
        assertTrue("must say the run is over: $text", text.contains("run is over"))
        assertTrue(cancelled.exhausted)
    }

    @Test
    fun cancelBeatsTimeoutWhenBothArriveTogether() {
        val gate = Fixtures.Gate()
        val executor = BoundedExecutor()
        val token = CancellationToken.none()
        try {
            val started = CountDownLatch(1)
            val storm = Fixtures.ThreadStorm(threadCount = 1)
            val results = java.util.concurrent.atomic.AtomicReference<ExecutionOutcome<String>>()

            storm.run {
                results.set(
                    executor.execute(
                        { started.countDown(); gate.arrive(); "x" },
                        token,
                        ExecutionBudget(60, "racing"),
                    ),
                )
            }
            storm.release()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            // Cancel and expire the budget in the same instant. The user pressing
            // stop is the more important fact and must win, or the model is told
            // to try a different approach when the user wants it to stop entirely.
            token.cancel("user pressed stop")
            assertTrue(storm.awaitDone())

            val outcome = results.get()
            assertTrue(
                "cancel must win over timeout, got $outcome",
                outcome is ExecutionOutcome.Cancelled,
            )
        } finally {
            gate.release()
        }
    }

    // -------------------------------------------------------------- saturation

    @Test
    fun aSaturatedPoolRejectsRatherThanQueueingForever() {
        // One worker, no queue: the second call has nowhere to go. This is the
        // bounded version of the residual risk — abandoned threads can exhaust
        // the pool, and the answer must be a fast "no capacity", not a wait.
        val executor = BoundedExecutor(maxWorkers = 1, queueCapacity = 0)
        val gate = Fixtures.Gate()
        val running = CountDownLatch(1)
        val outcomes = java.util.concurrent.ConcurrentLinkedQueue<ExecutionOutcome<String>>()

        try {
            // Occupy the single worker with an uninterruptible call, and wait for
            // it to actually be *running*. Without this the second submission can
            // race ahead of the first and the test would assert on the wrong call.
            val holder = Fixtures.ThreadStorm(threadCount = 1)
            holder.run {
                outcomes.add(
                    executor.execute(
                        { running.countDown(); gate.arrive(); "finished anyway" },
                        budget = ExecutionBudget(30_000, "occupy"),
                    ),
                )
            }
            holder.release()
            assertTrue("the occupying call never started", running.await(5, TimeUnit.SECONDS))

            val second = Fixtures.ThreadStorm(threadCount = 1)
            second.run {
                outcomes.add(
                    executor.execute(
                        Fixtures.instant("should be rejected"),
                        budget = ExecutionBudget(80, "rejected"),
                    ),
                )
            }
            second.release()
            assertTrue(second.awaitDone())

            val rejected = outcomes.filterIsInstance<ExecutionOutcome.Rejected>()
            assertEquals(
                "a saturated pool must reject instead of queueing: $outcomes",
                1,
                rejected.size,
            )
            // Retrying into a saturated pool is the wrong move, so the text has
            // to forbid it rather than invite it.
            assertTrue(rejected.single().exhausted)
            assertTrue(rejected.single().observation("web.fetch").contains("would fail the same way"))
        } finally {
            gate.release()
        }
    }

    // -------------------------------------------------- the three-way contract

    @Test
    fun timeoutCancelAndFailureAreThreeDistinctOutcomes() {
        val timedOut: ExecutionOutcome<String> = ExecutionOutcome.TimedOut(30_000)
        val cancelled: ExecutionOutcome<String> = ExecutionOutcome.Cancelled("user")
        val failed: ExecutionOutcome<String> = ExecutionOutcome.Failed("IOException")

        // Three outcomes, three observations. If any two strings matched, the
        // model could not choose between retrying and giving up.
        val texts = listOf(
            timedOut.observation("web.fetch"),
            cancelled.observation("web.fetch"),
            failed.observation("web.fetch"),
        )
        assertEquals("all three observations must differ", 3, texts.toSet().size)
        texts.forEach { assertNotEquals("", it) }

        // And they map onto three different typed errors, so a caller can branch
        // on the cause without parsing English.
        assertTrue(timedOut.toToolError() is ToolError.Timeout)
        assertTrue(cancelled.toToolError() is ToolError.Cancelled)
        assertTrue(failed.toToolError() is ToolError.Internal)
    }

    @Test
    fun onlyCancellationAndRejectionAreMarkedExhausted() {
        // Retrying a timeout or a failure can be reasonable; retrying a cancelled
        // run or a saturated pool is not. This flag drives the loop's nudges.
        assertTrue(ExecutionOutcome.Cancelled("stop").exhausted)
        assertTrue(ExecutionOutcome.Rejected("no capacity").exhausted)
        assertFalse(ExecutionOutcome.TimedOut(1_000).exhausted)
        assertFalse(ExecutionOutcome.Failed("IOException").exhausted)
    }
}
