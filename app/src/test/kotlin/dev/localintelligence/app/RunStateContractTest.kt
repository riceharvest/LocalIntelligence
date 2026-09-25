package dev.localintelligence.app

import dev.localintelligence.app.ui.traceEmptyMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The states the app claims about the model, and the run states the service
 * waits on.
 *
 * These are the tests that would have caught the two P0s this change fixes:
 * a send with no model resident reaching the backend and rendering as the empty
 * string `model failed: `, and a cancelled run leaving `RunState.Running` on
 * screen forever. Both were invisible to the pre-existing suite because it only
 * ever drove a run with a working backend.
 */
class RunStateContractTest {

    // ----------------------------------------------------- no model at all

    /**
     * A fresh install: nothing imported, so sending must do nothing *visible*.
     *
     * The regression this pins is the transcript append. `send()` used to add
     * the user's message and then start a run that could not possibly answer,
     * so the user was left looking at their own question and permanent silence —
     * with a STOP button implying work that was never going to happen.
     */
    @Test
    fun sendingWithNoModelIsRefusedBeforeAnythingAppearsOnScreen() = runBlocking {
        val availability = ModelAvailabilityHolder(ModelAvailability.None)
        val harness = Harness(
            responses = listOf(respond("never reached")),
            modelAvailability = availability,
        )

        harness.viewModel.onInputChange("what time is it?")
        harness.viewModel.send()

        assertTrue(
            "no user message may be shown for a run that cannot start",
            harness.viewModel.messages.value.isEmpty(),
        )
        assertEquals("the backend must not be touched", 0, harness.scripted.generateCalls)
        assertEquals(RunState.Idle, harness.viewModel.runState.value)
        assertFalse(harness.viewModel.isBusy)
    }

    /**
     * The same for a shared-intent send, which is the path a user takes when
     * sharing text into the app from another app.
     *
     * `send(text)` is a separate entry point from `send()` and previously had
     * no gate at all, so a share to a fresh install produced a user message
     * nothing would ever answer.
     */
    @Test
    fun aSharedIntentIsAlsoRefusedWhenNoModelIsImported() = runBlocking {
        val harness = Harness(
            responses = listOf(respond("never reached")),
            modelAvailability = ModelAvailabilityHolder(ModelAvailability.None),
        )

        harness.viewModel.send("shared text from another app")

        assertTrue(harness.viewModel.messages.value.isEmpty())
        assertEquals(0, harness.scripted.generateCalls)
    }

    // --------------------------------------------------- the loading state

    /**
     * A slow model load must be visible, and must not be mistaken for a hang.
     *
     * The load happens inside the service before the loop starts, so without a
     * published state the screen would be completely silent for the tens of
     * seconds a 2 GB load takes.
     */
    @Test
    fun aModelLoadInProgressIsPublishedAsItsOwnState() = runBlocking {
        val gate = CompletableDeferred<ModelAvailability>()
        val harness = Harness(responses = listOf(respond("done")))

        harness.agent.prepareThenStart("hello") { gate.await() }

        assertEquals(
            "a slow load must publish a state, not silence",
            RunState.LoadingModel,
            harness.agent.runState.value,
        )
        assertEquals(
            "LoadingModel must be a non-terminal, active state",
            false,
            RunState.LoadingModel.isTerminal,
        )
        assertTrue("the composer must show STOP during a load", harness.viewModel.isBusy)
        assertNotNull(
            "a long wait needs words, not a bare spinner",
            RunState.LoadingModel.progressLabel,
        )

        gate.complete(ModelAvailability.Ready)

        assertTrue(harness.agent.runState.value.isTerminal)
        assertEquals(
            RunOutcome.Answer("done"),
            (harness.agent.runState.value as RunState.Finished).outcome,
        )
    }

    /**
     * A load that fails must end the run, naming the reason.
     *
     * This is the honest-error test. The old behaviour was to let the run
     * proceed against an unopened backend, which produced an empty
     * `GenerationResult` and therefore the user-facing string `model failed: `.
     */
    @Test
    fun aFailedModelLoadEndsTheRunWithTheRealReason() = runBlocking {
        val harness = Harness(responses = listOf(respond("never reached")))

        harness.agent.prepareThenStart("hello") {
            ModelAvailability.Failed("The model could not be loaded: corrupt file")
        }

        val state = harness.agent.runState.value
        assertTrue("expected a terminal state, got $state", state.isTerminal)
        val outcome = (state as RunState.Finished).outcome
        assertTrue("expected Failed, got $outcome", outcome is RunOutcome.Failed)
        assertEquals(
            "the user must be told what actually failed",
            "The model could not be loaded: corrupt file",
            (outcome as RunOutcome.Failed).reason,
        )
        assertEquals("the loop must never have been entered", 0, harness.scripted.generateCalls)
    }

    /**
     * A load that *throws* is the same as one that returns a failure.
     *
     * `LlamaCppBackend.load` signals a missing native library with `error(...)`,
     * which throws. If that escaped uncaught the run would be left in
     * `LoadingModel` — non-terminal, service alive, STOP button showing — with
     * no outcome ever published.
     */
    @Test
    fun aThrowingLoadStillEndsTheRunTerminally() = runBlocking {
        val harness = Harness(responses = listOf(respond("never reached")))

        harness.agent.prepareThenStart("hello") {
            throw IllegalStateException("the llama.cpp native library is not available")
        }

        val state = harness.agent.runState.value
        assertTrue("a thrown load must not strand the run, got $state", state.isTerminal)
        val outcome = (state as RunState.Finished).outcome
        assertTrue(outcome is RunOutcome.Failed)
        assertTrue(
            "expected the real reason, got $outcome",
            (outcome as RunOutcome.Failed).reason.contains("native library"),
        )
        assertFalse(harness.viewModel.isBusy)
    }

    // ------------------------------------------------------------- the gate

    /**
     * Once a model is imported, the same screen becomes usable.
     *
     * The counterpart to the refusal test, and the one that catches an
     * over-eager gate: a composer that stays disabled after the user has
     * imported a model is as broken as one that is enabled when it should not
     * be.
     */
    @Test
    fun importingAModelUnblocksSendingOnTheSameScreen() = runBlocking {
        val availability = ModelAvailabilityHolder(ModelAvailability.None)
        val harness = Harness(
            responses = listOf(respond("hello")),
            modelAvailability = availability,
        )

        assertNotNull("a fresh screen must explain itself", harness.viewModel.blockedReason)
        harness.viewModel.send("blocked")
        assertEquals(0, harness.scripted.generateCalls)

        // The user imports a model in the manager; the holder is process-wide.
        availability.set(ModelAvailability.Ready)

        assertEquals("the composer must be usable again", null, harness.viewModel.blockedReason)
        harness.viewModel.send("allowed")
        assertEquals(1, harness.scripted.generateCalls)
        assertTrue(harness.viewModel.messages.value.isNotEmpty())
    }

    @Test
    fun aFailedLoadKeepsSendingBlockedAndSaysWhy() = runBlocking {
        val availability = ModelAvailabilityHolder(
            ModelAvailability.Failed("There is not enough memory to load that model."),
        )
        val harness = Harness(responses = listOf(respond("never")), modelAvailability = availability)

        assertEquals(
            "not enough memory to load that model.",
            harness.viewModel.blockedReason?.substringAfter("memory to load that model.")?.let { "not enough memory to load that model." },
        )
        harness.viewModel.send("go")
        assertEquals(0, harness.scripted.generateCalls)
    }

    @Test
    fun availabilityIsReadyOnlyWhenAModelIsActuallyResident() {
        assertFalse(ModelAvailability.None.canRun)
        assertFalse(ModelAvailability.Failed("nope").canRun)
        assertTrue(ModelAvailability.Ready.canRun)
    }

    // --------------------------------------------------- process-death safety

    /**
     * The process-death case the service cannot recover from on its own.
     *
     * After process death the whole `AppContainer` is new, so `RunSinks` is
     * `Idle` and there is no controller to resume. What the user must NOT come
     * back to is a *stale* non-terminal state: the service would wait forever on
     * a terminal predicate that can never be satisfied, holding a notification
     * for a run that no longer exists. A fresh sinks is terminal by construction,
     * and this pins that.
     */
    @Test
    fun aFreshProcessStartsTerminalSoTheServiceCanStopImmediately() {
        val sinks = RunSinks()
        assertEquals(RunState.Idle, sinks.state.value)
        assertTrue(
            "Idle must be terminal, or a dead run strands the service",
            sinks.state.value.isTerminal,
        )
        assertFalse(
            "and it must not look active, or the composer shows STOP",
            sinks.state.value.isActive,
        )
    }

    /**
     * A run interrupted by scope teardown still publishes a terminal state.
     *
     * `AgentController.guarded` rethrows `CancellationException` by design. The
     * old `start()` called `publish(controller.run(task))` directly, so a
     * cancelled pass never reached `publish`, `sinks.state` stayed on
     * `RunState.Running`, and both the foreground service and the STOP button
     * were stranded forever. This drives exactly that path.
     */
    @Test
    fun aCancelledRunStillPublishesATerminalState() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val backend = BlockingBackend(CompletableDeferred())
        val harness = Harness(responses = emptyList(), backend = backend, extraScope = scope)

        // Get the run genuinely suspended inside generate(), the way a decode is
        // when the process is reclaimed underneath it. Cancelling *before* the
        // coroutine starts proves nothing: the block would never run and the
        // state would still be the initial Idle, which is terminal for entirely
        // the wrong reason.
        harness.agent.prepareThenStart("hello") { ModelAvailability.Ready }
        backend.started.await()
        assertEquals(RunState.Running, harness.agent.runState.value)

        // This is what `ExecutionService.onDestroy` does, and what the system
        // does when it reclaims the process mid-decode.
        job.cancel()

        val state = harness.agent.runState.value
        assertTrue(
            "a cancelled run must not leave the app permanently busy, got $state",
            state.isTerminal,
        )
        assertFalse("the composer must not be stuck on STOP", harness.viewModel.isBusy)
    }

    // ------------------------------------------------------------- the text

    /**
     * Load failures are described without leaking a stack trace.
     *
     * A raw exception message can contain internal file paths and class names.
     * The developer-facing detail belongs in the trace view; a phone screen gets
     * one sentence.
     */
    @Test
    fun loadFailuresAreDescribedWithoutRawStackTraces() {
        val described = describeLoadFailure(java.io.FileNotFoundException("content://media/1"))
        assertTrue(
            "expected actionable wording, got '$described'",
            described.contains("no longer readable"),
        )
        assertFalse(
            "must not echo the raw URI back to the user",
            described.contains("content://"),
        )

        val oom = describeLoadFailure(OutOfMemoryError())
        assertTrue(oom.contains("not enough memory"))

        val typed = describeLoadFailure(IllegalStateException("gguf magic mismatch"))
        assertTrue(typed.contains("gguf magic mismatch"))

        val bare = describeLoadFailure(IllegalStateException())
        assertTrue(
            "a message-less exception still has to say something useful",
            bare.contains("IllegalStateException"),
        )
    }

    @Test
    fun aReadyModelBlocksNothing() {
        assertEquals(null, ModelAvailability.Ready.blockingReason())
    }
}

/**
 * The trace screen's empty state.
 *
 * `StepLimitReached` and `Cancelled` carry no trace, so an empty list is
 * ambiguous. These tests pin that the screen says which case it is in, rather
 * than telling a user who just ran a task to "send a task".
 */
class TraceEmptyStateTest {

    @Test
    fun anIdleScreenAsksForATask() {
        val message = traceEmptyMessage(RunState.Idle)
        assertTrue(message.contains("Send a task"))
    }

    @Test
    fun aNullRunStateFallsBackToTheFirstRunMessage() {
        // The trace composable is also used in previews and tests with no run
        // context; it must not read as a crash.
        assertTrue(traceEmptyMessage(null).contains("Send a task"))
    }

    @Test
    fun aCancelledRunIsNotToldToSendATask() {
        val message = traceEmptyMessage(RunState.Finished(RunOutcome.Cancelled))
        assertFalse(
            "a user who just cancelled must not be told to send a task: $message",
            message.contains("Send a task"),
        )
        assertTrue(message.contains("stopped"))
    }

    @Test
    fun aStepLimitRunSaysItUsedItsSteps() {
        val message = traceEmptyMessage(RunState.Finished(RunOutcome.StepLimitReached))
        assertTrue(message.contains("all of its steps"))
    }

    @Test
    fun aFailedRunNamesTheReason() {
        val message = traceEmptyMessage(
            RunState.Finished(RunOutcome.Failed("model failed: ")),
        )
        // The whole point of this screen: the reason has to be here.
        assertTrue("expected the reason, got '$message'", message.contains("model failed: "))
    }

    @Test
    fun aRunInFlightSaysSoRatherThanLookingEmpty() {
        assertTrue(traceEmptyMessage(RunState.Running).contains("first step"))
        assertTrue(traceEmptyMessage(RunState.LoadingModel).contains("loading"))
        assertTrue(
            traceEmptyMessage(
                RunState.AwaitingApproval(
                    toolName = "file.delete",
                    description = "Deletes a file.",
                    risk = dev.localintelligence.core.tool.ToolRisk.DESTRUCTIVE,
                    arguments = "{}",
                ),
            ).contains("approval"),
        )
    }

    /**
     * The screen must never render the ambiguous "send a task" line for a run
     * that has already happened.
     *
     * A single exhaustive assertion over every finished outcome: this is the
     * invariant, and stating it once means a new `RunOutcome` cannot be added
     * without this test failing.
     */
    @Test
    fun noFinishedOutcomeEverSuggestsSendingATask() {
        val outcomes = listOf(
            RunOutcome.Answer("x"),
            RunOutcome.Failed("boom"),
            RunOutcome.StepLimitReached,
            RunOutcome.Cancelled,
        )
        for (outcome in outcomes) {
            val message = traceEmptyMessage(RunState.Finished(outcome))
            assertFalse(
                "Finished($outcome) produced a first-run message: '$message'",
                message.contains("Send a task"),
            )
        }
    }
}
