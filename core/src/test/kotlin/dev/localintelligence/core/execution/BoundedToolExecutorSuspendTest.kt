package dev.localintelligence.core.execution

import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suspending half of the runtime, where time is **virtual**.
 *
 * `runTest` replaces the delay scheduler, so a 30-second budget is exercised in
 * microseconds of real time. That is the whole reason this file can assert real
 * timeout behaviour at production budgets without a 30-second test: the same
 * code path runs, only the clock is fake.
 *
 * If a test here ever needs `Thread.sleep`, the design has gone wrong — that is
 * the rule this suite exists to keep honest.
 */
class BoundedToolExecutorSuspendTest {

    @Test
    fun aFastSuspendingCallSucceeds() = runTest {
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))
        val outcome = guard.run("device.battery") { "42%" }

        assertTrue(outcome is ExecutionOutcome.Succeeded)
        assertEquals("42%", outcome.value)
    }

    @Test
    fun aSuspendingCallThatOverrunsIsStoppedAtItsDeadline() = runTest {
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))
        // 30s of virtual time, no 30s of real time. The budget is the production
        // default on purpose: this is the real policy, exercised for free.
        val outcome = guard.run("web.fetch", budget = ExecutionBudget.DEFAULT) {
            delay(ExecutionBudget.DEFAULT.timeoutMs + 1_000)
            "never delivered"
        }

        assertTrue("expected a timeout, got $outcome", outcome is ExecutionOutcome.TimedOut)
        // Nothing is stranded: a suspending body is cancelled cooperatively, so
        // the timeout path never leaves a thread behind.
        assertFalse((outcome as ExecutionOutcome.TimedOut).abandoned)
    }

    @Test
    fun theDeadlineIsEnforcedWithoutTheBodyFinishing() = runTest {
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))
        var completed = false

        val outcome = guard.run("file.scan", budget = ExecutionBudget(1_000, "scan")) {
            delay(60_000)
            completed = true
            "done"
        }

        assertTrue(outcome is ExecutionOutcome.TimedOut)
        // The critical assertion: the loop moved on without the body completing.
        // If this were false, a "stopped" call would still be running and the
        // agent would be reporting success over an unfinished operation.
        assertFalse("the body must not have completed", completed)
    }

    @Test
    fun aSuspendingCallThatChecksTheTokenStopsEarly() = runTest {
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))
        val token = CancellationToken.none()
        var iterations = 0

        // A well-written tool polls its token between I/O waits. This is the
        // cooperative path, and it must stop at the cancel rather than running on
        // to the 60-second deadline. The call runs in its own coroutine so the
        // test can cancel from the outside, exactly as a user pressing stop would.
        val call = launch {
            guard.run("calendar.search", token, ExecutionBudget(60_000, "poll")) {
                while (true) {
                    token.checkActive()
                    iterations += 1
                    delay(1_000)
                }
                @Suppress("UNREACHABLE_CODE")
                "unreachable"
            }
        }
        advanceTimeBy(5_000)
        token.cancel("user pressed stop")
        advanceUntilIdle()
        call.join()

        assertTrue("the body should have polled a few times", iterations in 1..10)
    }

    @Test
    fun aThrowingSuspendingCallBecomesAFailedOutcome() = runTest {
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))
        val outcome = guard.run("device.battery") {
            throw IllegalArgumentException("bad argument")
        }

        assertTrue(outcome is ExecutionOutcome.Failed)
        val failed = outcome as ExecutionOutcome.Failed
        assertEquals("IllegalArgumentException", failed.typeName)
        assertEquals("bad argument", failed.detail)
    }

    @Test
    fun aResultMapsOntoTheFrozenToolResultContract() = runTest {
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))

        val ok = guard.runForResult("device.battery") { "42%" }
        assertTrue(ok.success)
        assertEquals(null, ok.error)

        val timedOut = guard.runForResult("web.fetch", budget = ExecutionBudget(1_000, "t")) {
            delay(60_000)
            "never"
        }
        assertFalse(timedOut.success)
        assertTrue("a stop must be a typed timeout, not a crash", timedOut.error is ToolError.Timeout)
    }

    @Test
    fun anAlreadyCancelledTokenSkipsTheSuspendingBodyToo() = runTest {
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))
        val token = CancellationToken.none()
        token.cancel("user pressed stop")
        var ran = false

        val outcome = guard.run("web.fetch", token, ExecutionBudget.DEFAULT) {
            ran = true
            "should not run"
        }

        assertTrue(outcome is ExecutionOutcome.Cancelled)
        assertFalse("a cancelled token must not dispatch the body", ran)
    }

    @Test
    fun theBudgetFiresOnVirtualTimeWithNoRealWaiting() = runTest {
        // Proves the suite is really on virtual time: the body is scheduled 10s
        // beyond a 500ms budget, yet the test's own clock never has to be advanced
        // by hand and the test finishes in milliseconds. If the dispatcher leaked
        // out to Dispatchers.IO this would take 10 real seconds — which is exactly
        // the regression this test was written to catch, and why the dispatcher is
        // a constructor parameter rather than a hard-coded constant.
        val scope = this
        val started = scope.testScheduler.currentTime
        val guard = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler))

        val outcome = guard.run("web.fetch", budget = ExecutionBudget(500, "virtual")) {
            delay(10_000)
            "late"
        }

        assertTrue(outcome is ExecutionOutcome.TimedOut)
        // Virtual time advanced past the deadline on its own.
        assertTrue(
            "virtual time should have moved past the budget",
            scope.testScheduler.currentTime > started,
        )
    }
}
