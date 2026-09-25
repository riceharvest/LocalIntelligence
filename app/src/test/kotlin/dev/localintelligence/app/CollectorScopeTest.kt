package dev.localintelligence.app

import dev.localintelligence.app.ui.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collector [ChatViewModel] owns, and the scope it must die with.
 *
 * ## What this is actually guarding
 *
 * `ChatViewModel.init` launches one long-lived coroutine that mirrors terminal
 * outcomes into the transcript. It is the one piece of work a `ViewModel` owns
 * that outlives an individual run, so it is the one piece that can leak.
 *
 * The failure this pins is specific and was not previously covered: collecting a
 * `StateFlow` from a scope the ViewModel does not own. If that collector were on
 * a `GlobalScope`, or on a scope passed in from an Activity, it would keep
 * appending to a transcript that no screen is showing, for the life of the
 * process — and because the runs it observes are process-scoped, it would
 * resurrect stale state every time the user re-entered the chat.
 *
 * The test asserts the collector is cancelled when the ViewModel's scope dies,
 * by counting active children of a scope this test controls. If someone moves
 * the collect to a scope they do not cancel, the count does not drop and this
 * fails.
 */
class CollectorScopeTest {

    @Test
    fun theTranscriptCollectorStopsWhenItsScopeIsCancelled() = runBlocking {
        val harness = Harness(responses = listOf(respond("hi")))

        // The collector is running: it is a child of the ViewModel's own scope,
        // which is the one that stands in for `viewModelScope`.
        assertTrue(
            "expected a live transcript collector, found ${harness.activeChildren()}",
            harness.activeChildren() >= 1,
        )

        // What `ViewModel.onCleared` does to `viewModelScope` in production.
        harness.clearViewModel()

        assertEquals(
            "the transcript collector must not outlive the ViewModel's scope; " +
                "a survivor appends to a transcript no screen is showing",
            0,
            harness.activeChildren(),
        )
        harness.close()
    }

    /**
     * A new run after the ViewModel's scope died must not resurrect it.
     *
     * This is the user-visible half of the leak: a `ChatViewModel` that has been
     * cleared still reacting to a run means a message appearing in a transcript
     * that belongs to a screen the user already left.
     */
    @Test
    fun aClearedViewModelDoesNotReactToLaterRuns() = runBlocking {
        val harness = Harness(responses = listOf(respond("first")))

        harness.viewModel.send("one")
        val beforeClear = harness.viewModel.messages.value.size
        assertTrue("expected the first exchange", beforeClear > 0)

        // Clear the ViewModel the way the framework would.
        harness.clearViewModel()

        harness.agent.start("two")
        // Unconfined means the run has already completed by now. The cleared
        // ViewModel must show no trace of it.
        assertEquals(
            "a cleared ViewModel appended a message from a later run",
            beforeClear,
            harness.viewModel.messages.value.size,
        )

        harness.close()
    }

    /**
     * The ViewModel must not hold the *gateway's* scope.
     *
     * In production the gateway is backed by the foreground service, which is
     * process-scoped. A ViewModel collecting that is correct; a ViewModel
     * *cancelling* it would be a serious bug — it would kill a run the user
     * started. Asserting the ViewModel leaves it alone is cheap and catches an
     * over-eager `onCleared`.
     */
    @Test
    fun clearingTheViewModelDoesNotCancelTheRunItIsWatching() = runBlocking {
        val harness = Harness(responses = listOf(respond("answer")))

        harness.viewModel.send("question")
        harness.clearViewModel()

        assertTrue(
            "the ViewModel cancelled the agent's scope; a rotation or a " +
                "backgrounded app would kill a live decode",
            harness.serviceAlive(),
        )
        harness.close()
    }

    private fun activeChildren(job: Job): Int = job.children.count { it.isActive }
}
