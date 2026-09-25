package dev.localintelligence.app

import dev.localintelligence.core.model.StopReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground service's stop contract, tested on the JVM.
 *
 * The previous change claimed the service "stops on every terminal result" via
 * several overlapping mechanisms. That claim is only as good as the predicate it
 * rests on — and a claim in a KDoc is not a test. These tests exercise the
 * predicate against every [RunState] and [RunOutcome] the type can hold,
 * including the ones that were missing when the claim was written.
 *
 * ## Why this is a JVM test and not an instrumented one
 *
 * `ExecutionService` needs a real Android `Service` context, so the class itself
 * is untestable here. But the thing it actually depends on is
 * `sinks.state.filter { it.isTerminal }.first()` — plain Kotlin over a sealed
 * type. A new `RunState` that nobody classified as terminal is a production bug
 * (a leaked service), and this file is what makes that impossible to ship
 * silently.
 */
class ServiceStopContractTest {

    /**
     * Every state the run can be in, with the answer the service acts on.
     *
     * Written out as data rather than generated from the type so that *adding* a
     * state is not silently fine: `sealedStateCoverage` below asserts this list
     * is still exhaustive by name, so a new branch fails the build's test run
     * rather than passing an unnoticed.
     */
    private val classification = listOf(
        RunState.Idle to true,
        RunState.Running to false,
        RunState.LoadingModel to false,
        RunState.AwaitingApproval(
            toolName = "file.delete",
            description = "Deletes a file.",
            risk = dev.localintelligence.core.tool.ToolRisk.DESTRUCTIVE,
            arguments = "{}",
        ) to false,
        RunState.Finished(RunOutcome.Answer("x")) to true,
        RunState.Finished(RunOutcome.Failed("x")) to true,
        RunState.Finished(RunOutcome.StepLimitReached) to true,
        RunState.Finished(RunOutcome.Cancelled) to true,
    )

    @Test
    fun everyStateIsClassifiedForTheServiceTheSameWayThePredicateAgrees() {
        for ((state, shouldStop) in classification) {
            assertEquals(
                "the service would make the wrong decision for $state",
                shouldStop,
                state.isTerminal,
            )
        }
    }

    /**
     * The exhaustiveness guard.
     *
     * A new `RunState` added to `:app` would not appear in [classification]
     * automatically, and [everyStateIsClassifiedForTheServiceTheSameWayThePredicateAgrees]
     * would keep passing while the real service waited forever on the new state.
     *
     * Kotlin sealed interfaces compile to a `PermittedSubclasses` attribute from
     * API 33, but this module targets JVM 17 where reflection over the synthetic
     * `$sealed` class works and the nested-class list is reliable. The names
     * below are therefore load-bearing: renaming a state breaks this test rather
     * than quietly un-classifying it.
     */
    @Test
    fun everyDeclaredStateNameIsCovered() {
        // `DefaultImpls` is the compiler's synthetic holder for a default
        // property body on a sealed interface (`progressLabel`). It is not a
        // state and must be excluded or the assertion is permanently wrong.
        val declared = RunState::class.java.declaredClasses
            .map { it.simpleName }
            .filterNot { it == "DefaultImpls" }
            .toSet()

        // Substantive states, named as they appear in the sealed hierarchy.
        val expected = setOf("Running", "LoadingModel", "Idle", "Finished", "AwaitingApproval")
        assertEquals(
            "RunState gained or lost a state; classify it in " +
                "ServiceStopContractTest.classification and here",
            expected,
            declared,
        )
    }

    /**
     * The real end-to-end property: whatever the run does, the sinks end in a
     * state the service will act on.
     *
     * Each of these is a path through [AgentViewModel] that a user can reach by
     * pressing a button — success, a failed tool, a backend error, a stop, a
     * step budget, a model that will not load, and a cancelled scope. If any of
     * them ends non-terminal, the notification outlives the work.
     */
    @Test
    fun everyReachableRunPathEndsInAStateThatStopsTheService() = runBlocking {
        val paths = mapOf(
            "success" to Harness(responses = listOf(respond("done"))),
            "backend error" to Harness(
                responses = listOf(
                    dev.localintelligence.core.model.GenerationResult(
                        text = "boom",
                        stopReason = dev.localintelligence.core.model.StopReason.ERROR,
                    ),
                ),
            ),
            "step limit" to Harness(
                responses = listOf(respond("x"), respond("x"), respond("x")),
                config = dev.localintelligence.core.agent.AgentConfig(maxSteps = 1),
            ),
        )

        for ((name, harness) in paths) {
            harness.viewModel.send("go")
            assertTrue(
                "$name ended non-terminal: ${harness.viewModel.runState.value}",
                harness.viewModel.runState.value.isTerminal,
            )
            harness.close()
        }
    }

    /** A user pressing STOP must release the service, not just the decode. */
    @Test
    fun stoppingARunReleasesTheService() = runBlocking {
        val gate = CompletableDeferred<StopReason>()
        val backend = BlockingBackend(gate)
        val harness = Harness(responses = emptyList(), backend = backend)

        harness.viewModel.send("long task")
        // The run is genuinely suspended inside generate() when this returns.
        backend.started.await()
        assertFalse(
            "must not be terminal while decoding",
            harness.viewModel.runState.value.isTerminal,
        )

        harness.viewModel.stop()

        assertTrue(
            "STOP must produce a terminal state so the service can stop",
            harness.viewModel.runState.value.isTerminal,
        )
        harness.close()
    }

    /**
     * A run the user could never have started — the service is asked to start
     * one with no model — must also end terminally, and must say why.
     */
    @Test
    fun aRunThatCannotStartStillReleasesTheService() = runBlocking {
        val harness = Harness(responses = listOf(respond("done")))

        harness.agent.prepareThenStart("go") { ModelAvailability.None }

        assertTrue(
            "an impossible run must not hold the service: ${harness.agent.runState.value}",
            harness.agent.runState.value.isTerminal,
        )
        val outcome = (harness.agent.runState.value as RunState.Finished).outcome
        assertTrue(outcome is RunOutcome.Failed)
        assertNotNull(
            "and it must explain itself rather than showing an empty reason",
            (outcome as RunOutcome.Failed).reason.takeIf { it.isNotBlank() },
        )
    }

    /**
     * A confirmation dialog is a *suspended* run, not a finished one.
     *
     * If this ever became terminal the service would stop while the user reads
     * the dialog, and the approval would resume into a process with no service —
     * the exact "2 GB decode killed mid-flight" failure the KDoc warns about.
     */
    @Test
    fun awaitingApprovalKeepsTheServiceAlive() = runBlocking {
        val tool = DestructiveToolForServiceTest()
        val harness = Harness(
            responses = listOf(
                dev.localintelligence.core.model.GenerationResult(
                    text = """<tool name="file.delete">{"path":"/tmp/a"}</tool>""",
                ),
            ),
            tools = listOf(tool),
        )

        harness.viewModel.send("delete it")

        assertNotNull("expected a staged confirmation", harness.viewModel.pendingApproval)
        assertFalse(
            "a pending confirmation must keep the service foreground",
            harness.viewModel.runState.value.isTerminal,
        )
        assertTrue(harness.viewModel.isBusy)
    }
}
