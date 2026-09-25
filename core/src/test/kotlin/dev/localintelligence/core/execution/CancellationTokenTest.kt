package dev.localintelligence.core.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The cancellation token's contract, asserted directly.
 *
 * The properties that matter and are easy to get wrong: cancellation is
 * idempotent, the first reason wins, listeners fire exactly once, and a
 * multi-threaded cancel storm produces exactly one winner and no exceptions.
 */
class CancellationTokenTest {

    @Test
    fun aFreshTokenIsNotCancelled() {
        val token = CancellationToken.none()
        assertFalse(token.isCancelled)
        assertNull(token.cancelReason)
    }

    @Test
    fun cancelMarksTheTokenAndRecordsTheReason() {
        val token = CancellationToken.none()
        assertTrue("the first cancel should win", token.cancel("user pressed stop"))
        assertTrue(token.isCancelled)
        assertEquals("user pressed stop", token.cancelReason)
    }

    @Test
    fun cancelIsIdempotentAndTheFirstReasonWins() {
        val token = CancellationToken.none()
        token.cancel("the real reason")
        assertFalse("a second cancel must not claim the transition", token.cancel("something else"))
        // The losing reason must not overwrite the record: the model is told why
        // the run stopped, and a later, vaguer reason would be a worse answer.
        assertEquals("the real reason", token.cancelReason)
    }

    @Test
    fun aBlankReasonIsNormalisedRatherThanBlank() {
        val token = CancellationToken.none()
        token.cancel("   ")
        assertFalse(token.reasonOrUnknown().isBlank())
    }

    @Test
    fun listenersRunOnceAndSeeTheWinningReason() {
        val token = CancellationToken.none()
        val seen = mutableListOf<String>()
        token.onCancel { seen += token.reasonOrUnknown() }

        token.cancel("stopped")
        token.cancel("again")

        assertEquals(1, seen.size)
        assertEquals("stopped", seen.single())
    }

    @Test
    fun aListenerRegisteredAfterCancelStillRunsExactlyOnce() {
        val token = CancellationToken.none()
        token.cancel("already stopped")

        var calls = 0
        token.onCancel { calls++ }

        // The lost-wakeup case: registering after the fact must still notify, or a
        // tool that registers late would run to completion after the user stopped.
        assertEquals(1, calls)
    }

    @Test
    fun aThrowingListenerDoesNotStarveTheOthers() {
        val token = CancellationToken.none()
        var reached = false
        token.onCancel { throw IllegalStateException("listener is broken") }
        token.onCancel { reached = true }

        token.cancel("stop")

        // One bad listener must not leave a nested call running forever.
        assertTrue(reached)
    }

    @Test
    fun disposeStopsLaterNotifications() {
        val token = CancellationToken.none()
        var calls = 0
        val registration = token.onCancel { calls++ }
        registration.dispose()

        token.cancel("stopped")

        // A disposed listener belongs to a call that already finished; firing it
        // would poke a worker that is no longer relevant.
        assertEquals(0, calls)
    }

    @Test
    fun disposeIsIdempotent() {
        val token = CancellationToken.none()
        val registration = token.onCancel { }
        registration.dispose()
        registration.dispose()
    }

    @Test
    fun checkActiveThrowsOnlyAfterCancel() {
        val token = CancellationToken.none()
        token.checkActive()
        token.cancel("stop")
        try {
            token.checkActive()
            fail("checkActive should have thrown after cancel")
        } catch (e: OperationCancelledException) {
            assertEquals("stop", e.reason)
        }
    }

    @Test
    fun asSignalExposesTheTokenThroughTheFrozenSeam() {
        val token = CancellationToken.none()
        val signal = token.asSignal()
        assertFalse(signal.isCancelled())
        token.cancel("stop")
        assertTrue(signal.isCancelled())
    }

    // ---------------------------------------------------------------- nesting

    @Test
    fun aChildIsCancelledWhenTheParentIs() {
        val parent = CancellationToken.none()
        val child = parent.child()
        assertFalse(child.isCancelled)

        parent.cancel("user stopped the run")

        // This is the nested-tool guarantee: one cancel reaches the whole tree.
        assertTrue(child.isCancelled)
        assertEquals("user stopped the run", child.cancelReason)
    }

    @Test
    fun aChildCreatedAfterTheParentIsAlreadyCancelledIsBornCancelled() {
        val parent = CancellationToken.none()
        parent.cancel("already stopped")

        val child = parent.child()

        assertTrue(child.isCancelled)
    }

    @Test
    fun cancellingAChildDoesNotCancelTheParent() {
        val parent = CancellationToken.none()
        val child = parent.child()

        child.cancel("this call was abandoned")

        // The parent may be a healthy step that simply decided not to make that
        // call. A child must never be able to stop the run.
        assertFalse(parent.isCancelled)
    }

    @Test
    fun cancellingAChildDoesNotStopASibling() {
        val parent = CancellationToken.none()
        val first = parent.child()
        val second = parent.child()

        first.cancel("one call gave up")

        assertFalse(second.isCancelled)
    }

    // ------------------------------------------------------------ concurrency

    @Test
    fun aCancelStormFromManyThreadsHasExactlyOneWinner() {
        val token = CancellationToken.none()
        val storm = Fixtures.ThreadStorm(threadCount = 16)

        storm.run { index -> if (token.cancel("reason-$index")) storm.winners.incrementAndGet() }
        storm.release()

        assertTrue("threads did not finish in time", storm.awaitDone())
        // Exactly one caller may observe the CAS succeeding. More than one means
        // the token can be transitioned twice, and the listeners can run twice.
        assertEquals(1, storm.winners.get())
        assertTrue(token.isCancelled)
    }

    @Test
    fun aCancelStormRunsEveryListenerExactlyOnce() {
        val token = CancellationToken.none()
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        token.onCancel { calls.incrementAndGet() }
        token.onCancel { calls.incrementAndGet() }

        val storm = Fixtures.ThreadStorm(threadCount = 16)
        storm.run { token.cancel("stop") }
        storm.release()

        assertTrue(storm.awaitDone())
        // Two listeners, one cancel, one firing each — regardless of which thread
        // won and how many were mid-registration.
        assertEquals(2, calls.get())
    }

    @Test
    fun registeringListenersWhileBeingCancelledIsRaceFree() {
        val token = CancellationToken.none()
        val registered = java.util.concurrent.atomic.AtomicInteger(0)
        val fired = java.util.concurrent.atomic.AtomicInteger(0)
        val storm = Fixtures.ThreadStorm(threadCount = 16)

        // Half the threads register, half cancel. Every registration that
        // returns must have fired exactly once by the time they all finish —
        // that is the lost-wakeup property, asserted under contention.
        storm.run { index ->
            if (index % 2 == 0) {
                token.onCancel { fired.incrementAndGet() }
                registered.incrementAndGet()
            } else {
                token.cancel("stop")
            }
        }
        storm.release()
        assertTrue(storm.awaitDone())

        assertEquals(registered.get(), fired.get())
    }

    @Test
    fun aDisposedRegistrationInsideAStormDoesNotFire() {
        val token = CancellationToken.none()
        val fired = java.util.concurrent.atomic.AtomicInteger(0)
        val registration = token.onCancel { fired.incrementAndGet() }
        registration.dispose()

        val storm = Fixtures.ThreadStorm(threadCount = 8)
        storm.run { token.cancel("stop") }
        storm.release()

        assertTrue(storm.awaitDone())
        assertEquals(0, fired.get())
        assertNotNull(token.cancelReason)
    }
}
