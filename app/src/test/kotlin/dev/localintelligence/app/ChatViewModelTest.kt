package dev.localintelligence.app

import dev.localintelligence.app.ui.ChatMessage
import dev.localintelligence.app.ui.ChatViewModel
import dev.localintelligence.app.ui.ToolApproval
import dev.localintelligence.app.ui.traceEmptyMessage
import dev.localintelligence.core.agent.ActionParserImpl
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.InMemoryMemoryStore
import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.agent.ToolCallValidatorGate
import dev.localintelligence.core.context.ContextBuilder
import dev.localintelligence.core.context.SystemPrompts
import dev.localintelligence.core.model.ChatMessage as CoreChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ViewModel, driven by the **real** `AgentController`.
 *
 * Only three things are doubled: the model backend, the tools, and the context
 * builder. Everything that decides anything — the loop, the loop detector, the
 * frozen `ToolCallValidator`, the shipped `ActionParserImpl`, the risk policy —
 * is the production implementation. So a regression in any of those fails these
 * tests too, which is the point: the UI's job is to report the loop's output
 * faithfully, and it can only be checked against a loop that really runs.
 *
 * The transcript assertions go through [ChatViewModel] and [AgentViewModel]
 * exactly as the composables do, so what is verified here is what the screen
 * renders.
 *
 * ## Why `runBlocking` and not `runTest`
 *
 * `kotlinx-coroutines-test` is **not** a declared test dependency of `:app`
 * (see the PR description — it needs adding to `app/build.gradle.kts`, which
 * this change was not permitted to touch). `kotlinx-coroutines-core` *is*
 * available, transitively via `:core`'s `api` dependency, so these tests use
 * `runBlocking` plus `Dispatchers.Unconfined` as the injected scope.
 *
 * That combination is deterministic here for a specific reason worth stating:
 * `Dispatchers.Unconfined` runs a coroutine eagerly on the calling thread until
 * its first real suspension, and the fake backend never truly suspends except in
 * the cancellation test, where the resumption is driven explicitly. So
 * `send()` has completed the whole run by the time it returns, and there is no
 * scheduler to race.
 *
 * The composables themselves are **compile-only**. There is no Robolectric and no
 * `androidTest` source set wired for UI assertions in this module.
 */
class ChatViewModelTest {

    // ------------------------------------------------------------- happy path

    @Test
    fun sendingAMessageStartsARunAndEndsInSuccess() = runBlocking {
        val harness = Harness(responses = listOf(respond("42%")))

        harness.viewModel.send("how much battery do I have?")

        val state = harness.viewModel.runState.value
        assertTrue("expected Finished, got $state", state is RunState.Finished)
        val outcome = (state as RunState.Finished).outcome
        assertTrue("expected Answer, got $outcome", outcome is RunOutcome.Answer)
        assertEquals("42%", (outcome as RunOutcome.Answer).text)

        // The transcript the user sees: their message, then the answer.
        val transcript = harness.viewModel.messages.value
        assertEquals(2, transcript.size)
        assertEquals(
            ChatMessage.User("how much battery do I have?"),
            transcript[0],
        )
        assertEquals(ChatMessage.Assistant("42%"), transcript[1])
        assertFalse("must not be busy after a terminal result", harness.viewModel.isBusy)
    }

    @Test
    fun anEmptyOrBusyViewModelDoesNotStartASecondRun() = runBlocking {
        val harness = Harness(responses = listOf(respond("hi")))

        harness.viewModel.send("   ")   // blank

        assertEquals(0, harness.scripted.generateCalls)
        assertTrue(harness.viewModel.messages.value.isEmpty())
        assertEquals(RunState.Idle, harness.viewModel.runState.value)
    }

    // ------------------------------------------------------------ cancellation

    /**
     * The STOP button, end to end.
     *
     * The fake backend mirrors `LlamaCppBackend`: `cancel()` completes the
     * in-flight generation with `StopReason.CANCELLED`, and the loop turns that
     * into `AgentResult.Cancelled`. That is the real cancellation path, not a
     * simulated one.
     */
    @Test
    fun theStopButtonCancelsTheRunAndItEndsCancelled() = runBlocking {
        val gate = CompletableDeferred<StopReason>()
        val backend = BlockingBackend(gate)
        val harness = Harness(responses = emptyList(), backend = backend)

        harness.viewModel.send("take your time")
        // The run is suspended inside generate(), waiting on the gate.
        // Resumes once the controller is genuinely suspended inside generate().
        backend.started.await()
        assertTrue("must be busy while running", harness.viewModel.isBusy)
        assertEquals(RunState.Running, harness.viewModel.runState.value)

        harness.viewModel.stop()

        val state = harness.viewModel.runState.value
        assertTrue("expected Finished, got $state", state is RunState.Finished)
        assertEquals(RunOutcome.Cancelled, (state as RunState.Finished).outcome)
        assertEquals(1, backend.cancelCalls)
        assertFalse(harness.viewModel.isBusy)

        // The user is told, rather than the UI silently going quiet.
        val last = harness.viewModel.messages.value.last()
        assertTrue(
            "expected a cancellation notice, got $last",
            last is ChatMessage.Notice && last.text.contains("your request"),
        )
    }

    // ------------------------------------------------------- the risk gate

    @Test
    fun aRiskyToolSurfacesAwaitingApprovalAndSetsTheDialogState() = runBlocking {
        val tool = FakeTool(def("file.delete", ToolRisk.DESTRUCTIVE)) { _, _ ->
            ToolResult(success = true, observation = "deleted 1 file")
        }
        val harness = Harness(
            responses = listOf(call("file.delete", """{"path":"/tmp/notes.txt"}""")),
            tools = listOf(tool),
        )

        harness.viewModel.send("delete that file")

        // The run is SUSPENDED, not finished: this is the assertion that keeps
        // the foreground service alive, since isTerminal is false here.
        val pending = harness.viewModel.pendingApproval
        assertNotNull("expected a staged confirmation, got ${harness.viewModel.runState.value}", pending)
        assertEquals("file.delete", pending!!.toolName)
        assertEquals(ToolRisk.DESTRUCTIVE, pending.risk)
        assertTrue("risk must be derived, not chosen by the UI", pending.risk.requiresConfirmation)
        assertTrue(
            "the dialog must show the real arguments, got '${pending.arguments}'",
            pending.arguments.contains("notes.txt"),
        )
        // Nothing ran: the gate is before execution.
        assertEquals(0, tool.executionCount)
        assertTrue(harness.viewModel.isBusy)
        assertFalse("AwaitingApproval is not terminal", harness.viewModel.runState.value.isTerminal)
    }

    @Test
    fun approvingRunsTheExactStagedCallAndCompletes() = runBlocking {
        val tool = FakeTool(def("file.delete", ToolRisk.DESTRUCTIVE)) { _, _ ->
            ToolResult(success = true, observation = "deleted 1 file")
        }
        val harness = Harness(
            responses = listOf(
                call("file.delete", """{"path":"/tmp/notes.txt"}"""),
                respond("I deleted it."),
            ),
            tools = listOf(tool),
        )

        harness.viewModel.send("delete that file")
        assertNotNull(harness.viewModel.pendingApproval)

        harness.viewModel.resolveConfirmation(approved = true)

        assertNull("the dialog must close after answering", harness.viewModel.pendingApproval)
        assertEquals(1, tool.executionCount)
        // The tool knows a human said yes.
        assertTrue(tool.contexts.single().userConfirmed)
        val state = harness.viewModel.runState.value
        assertTrue("expected Finished, got $state", state is RunState.Finished)
        assertEquals(
            RunOutcome.Answer("I deleted it."),
            (state as RunState.Finished).outcome,
        )
        assertEquals(listOf(ToolApproval("file.delete", approved = true)), harness.viewModel.approvals.value)
    }

    @Test
    fun decliningTellsTheModelTheUserSaidNoAndTheRunContinues() = runBlocking {
        val tool = FakeTool(def("file.delete", ToolRisk.DESTRUCTIVE)) { _, _ ->
            ToolResult(success = true, observation = "deleted 1 file")
        }
        val harness = Harness(
            responses = listOf(
                call("file.delete", """{"path":"/tmp/notes.txt"}"""),
                respond("Understood, I left it alone."),
            ),
            tools = listOf(tool),
        )

        harness.viewModel.send("delete that file")
        harness.viewModel.resolveConfirmation(approved = false)

        // The action did not happen.
        assertEquals("declining must not execute the tool", 0, tool.executionCount)
        // And the model was told, so it can route around the action rather than
        // retrying it or stalling.
        val told = harness.scripted.seenMessages.last()
            .filterIsInstance<CoreChatMessage.Assistant>()
        assertTrue(
            "the model was not told the user declined; got $told",
            told.any { it.text.contains("declined file.delete") },
        )
        // The run resumed and finished normally — declining one action is not
        // cancelling the task.
        val state = harness.viewModel.runState.value
        assertTrue("expected Finished, got $state", state is RunState.Finished)
        assertEquals(
            RunOutcome.Answer("Understood, I left it alone."),
            (state as RunState.Finished).outcome,
        )
        assertEquals(listOf(ToolApproval("file.delete", approved = false)), harness.viewModel.approvals.value)
    }

    @Test
    fun readOnlyToolsNeverReachTheDialog() = runBlocking {
        val tool = FakeTool(def("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(call("device.battery", "{}"), respond("42%")),
            tools = listOf(tool),
        )

        harness.viewModel.send("how much battery?")

        assertNull(harness.viewModel.pendingApproval)
        assertEquals(1, tool.executionCount)
        assertTrue(harness.viewModel.approvals.value.isEmpty())
    }

    // ------------------------------------------------------------- the trace

    @Test
    fun toolActivityAppearsInTheTraceInOrder() = runBlocking {
        val first = FakeTool(def("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "battery: 42%")
        }
        val second = FakeTool(def("clock.now", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "it is 14:05")
        }
        val harness = Harness(
            responses = listOf(
                call("device.battery", "{}"),
                call("clock.now", "{}"),
                respond("42% at 14:05"),
            ),
            tools = listOf(first, second),
        )

        harness.viewModel.send("battery and time?")

        val trace = harness.viewModel.trace.value
        assertEquals(
            listOf(
                StepTrace.Kind.GENERATION,
                StepTrace.Kind.TOOL_CALL,
                StepTrace.Kind.OBSERVATION,
                StepTrace.Kind.GENERATION,
                StepTrace.Kind.TOOL_CALL,
                StepTrace.Kind.OBSERVATION,
                StepTrace.Kind.GENERATION,
            ),
            trace.map { it.kind },
        )
        // In the order the loop made them, not sorted by anything.
        val calls = trace.filter { it.kind == StepTrace.Kind.TOOL_CALL }
        assertEquals(2, calls.size)
        assertTrue(calls[0].detail.startsWith("device.battery"))
        assertTrue(calls[1].detail.startsWith("clock.now"))
        assertEquals(listOf(1, 1, 1, 2, 2, 2, 3), trace.map { it.step })
        assertTrue("no failures in this run", trace.all { it.success })
    }

    @Test
    fun aFailingToolIsRecordedInTheTraceAndDoesNotKillTheRun() = runBlocking {
        val tool = FakeTool(def("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = false, observation = "sensor bus offline")
        }
        val harness = Harness(
            responses = listOf(call("device.battery", "{}"), respond("I could not read it.")),
            tools = listOf(tool),
        )

        harness.viewModel.send("how much battery?")

        val call = harness.viewModel.trace.value.single { it.kind == StepTrace.Kind.TOOL_CALL }
        assertFalse("a failed tool must not be traced as success", call.success)
        val state = harness.viewModel.runState.value
        assertEquals(RunOutcome.Answer("I could not read it."), (state as RunState.Finished).outcome)
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun aBackendThatThrowsEndsInACleanErrorStateRatherThanACrash() = runBlocking {
        val backend = ThrowingBackend()
        val harness = Harness(responses = emptyList(), backend = backend)

        harness.viewModel.send("hello")

        val state = harness.viewModel.runState.value
        assertTrue("expected Finished, got $state", state is RunState.Finished)
        val outcome = (state as RunState.Finished).outcome
        assertTrue("expected Failed, got $outcome", outcome is RunOutcome.Failed)
        assertTrue(
            "the reason should name the failure, got $outcome",
            (outcome as RunOutcome.Failed).reason.contains("internal error"),
        )
        // The user sees the reason, and the screen is no longer busy.
        val last = harness.viewModel.messages.value.last()
        assertTrue(last is ChatMessage.Notice)
        assertFalse(harness.viewModel.isBusy)
    }

    @Test
    fun aModelErrorStopReasonBecomesAFailedOutcomeNotAnException() = runBlocking {
        val harness = Harness(
            responses = listOf(
                GenerationResult(text = "cuda out of memory", stopReason = StopReason.ERROR),
            ),
        )

        harness.viewModel.send("hello")

        val outcome = (harness.viewModel.runState.value as RunState.Finished).outcome
        assertTrue(outcome is RunOutcome.Failed)
        assertTrue(
            "the model-visible reason should be surfaced, got $outcome",
            (outcome as RunOutcome.Failed).reason.contains("cuda out of memory"),
        )
    }

    // ------------------------------------------------------------- leak check

    /**
     * The one that matters for a foreground service.
     *
     * After every terminal result, the controller must not be holding a
     * coroutine: a leaked job means a leaked service, a leaked notification, and
     * an app the system eventually kills. Checked for each of the four terminal
     * outcomes, not just the happy one, because the leak would only ever show up
     * on the error path.
     *
     * The scope-children check is a **baseline comparison**, not "zero children".
     * [ChatViewModel] legitimately owns one long-lived collector on the injected
     * scope (the one that mirrors terminal outcomes into the transcript), and it
     * lives as long as the ViewModel does. Asserting zero would be asserting that
     * the ViewModel does not exist. What matters is that the count does not grow
     * — so each harness records its own baseline before the run and the test
     * compares against that.
     */
    @Test
    fun noRunLeaksACoroutineJob() = runBlocking {
        // Success.
        val success = Harness(responses = listOf(respond("done")))
        val successBaseline = success.activeChildren()
        success.viewModel.send("go")
        assertNoLeakedJobs("Success", success, successBaseline)

        // Cancelled.
        val gate = CompletableDeferred<StopReason>()
        val blocking = BlockingBackend(gate)
        val cancelled = Harness(responses = emptyList(), backend = blocking)
        val cancelledBaseline = cancelled.activeChildren()
        cancelled.viewModel.send("go")
        blocking.started.await()
        cancelled.viewModel.stop()
        assertNoLeakedJobs("Cancelled", cancelled, cancelledBaseline)

        // Stop (backend error).
        val stopped = Harness(
            responses = listOf(GenerationResult(text = "bad", stopReason = StopReason.ERROR)),
        )
        val stoppedBaseline = stopped.activeChildren()
        stopped.viewModel.send("go")
        assertNoLeakedJobs("Stop", stopped, stoppedBaseline)

        // StepLimitReached: the budget is exhausted before any Respond.
        val looping = Harness(
            responses = listOf(
                GenerationResult(
                    text = """<tool name="device.battery">{"path":"/a"}</tool>""",
                ),
            ),
            tools = listOf(
                FakeTool(def("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
                    ToolResult(success = true, observation = "42%")
                },
            ),
            config = AgentConfig(maxSteps = 2),
        )
        val loopingBaseline = looping.activeChildren()
        looping.viewModel.send("go")
        assertEquals(
            "the loop should have hit its step budget",
            RunOutcome.StepLimitReached,
            (looping.viewModel.runState.value as RunState.Finished).outcome,
        )
        assertNoLeakedJobs("StepLimitReached", looping, loopingBaseline)
    }

    /**
     * Repeated runs on one screen must not accumulate coroutines.
     *
     * This is the shape of the actual production bug: a user sends five messages
     * in a row, and the service — which is never destroyed between them — ends
     * up holding five stale jobs and a foreground notification for a task that
     * finished two turns ago.
     */
    @Test
    fun repeatedRunsDoNotAccumulateJobs() = runBlocking {
        val harness = Harness(responses = listOf(respond("ok")))
        harness.viewModel.send("first")
        val baseline = harness.activeChildren()

        repeat(5) { i ->
            harness.viewModel.send("message $i")
            assertNoLeakedJobs("run $i", harness, baseline)
        }
    }

    @Test
    fun aToolCallLeavesNoRunningJobBehindIt() = runBlocking {
        val seen = mutableListOf<Job?>()
        val tool = FakeTool(def("device.battery", ToolRisk.READ_ONLY)) { _, context ->
            // The tool's own coroutine context, captured at execution time.
            seen += kotlinx.coroutines.currentCoroutineContext()[Job]
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(call("device.battery", "{}"), respond("42%")),
            tools = listOf(tool),
        )

        harness.viewModel.send("how much battery?")

        assertEquals(1, seen.size)
        val job = seen.single()
        assertNotNull("the tool ran with no Job in its context", job)
        assertFalse("the tool's coroutine is still active", job!!.isActive)
        assertTrue(harness.viewModel.messages.value.none { it is ChatMessage.Notice })
    }

    // ------------------------------------------- the service's stop predicate

    /**
     * The guarantee behind `ExecutionService.stopSelf()`, tested where it can
     * actually run: on the JVM.
     *
     * The service itself is untestable without Robolectric, but the predicate it
     * waits on is plain Kotlin. If a new `RunState` were ever added that is not
     * covered by these five assertions, the service could wait forever on a state
     * that is not terminal.
     */
    @Test
    fun onlyFinishedAndIdleAreTerminal() {
        assertTrue(RunState.Idle.isTerminal)
        assertTrue(RunState.Finished(RunOutcome.Answer("x")).isTerminal)
        assertTrue(RunState.Finished(RunOutcome.Cancelled).isTerminal)
        assertTrue(RunState.Finished(RunOutcome.StepLimitReached).isTerminal)
        assertTrue(RunState.Finished(RunOutcome.Failed("x")).isTerminal)

        // These three must NOT stop the service. Getting either wrong is a real
        // production bug in opposite directions:
        //  - treating LoadingModel as terminal kills a 2 GB load mid-flight and
        //    leaves the user staring at a run that dies for no stated reason;
        //  - treating Running or AwaitingApproval as terminal leaks a foreground
        //    service and a pinned notification.
        assertFalse(RunState.Running.isTerminal)
        assertFalse(RunState.LoadingModel.isTerminal)
        assertFalse(
            RunState.AwaitingApproval(
                toolName = "file.delete",
                description = "Deletes a file.",
                risk = ToolRisk.DESTRUCTIVE,
                arguments = "{}",
            ).isTerminal,
        )
    }

    /**
     * The inverse predicate, and the one a screen actually branches on.
     *
     * `isActive` exists so a screen cannot accidentally compute
     * `state is Running` and forget `LoadingModel` / `AwaitingApproval` — the
     * bug that shows a SEND button during a run already in flight.
     */
    @Test
    fun everyNonTerminalStateIsActive() {
        assertFalse(RunState.Idle.isActive)
        assertFalse(RunState.Finished(RunOutcome.Answer("x")).isActive)
        assertTrue(RunState.Running.isActive)
        assertTrue(RunState.LoadingModel.isActive)
        assertTrue(
            RunState.AwaitingApproval(
                toolName = "t",
                description = "d",
                risk = ToolRisk.DESTRUCTIVE,
                arguments = "{}",
            ).isActive,
        )
    }

    @Test
    fun aFreshSinksStartsIdleAndResetsCleanly() {
        val sinks = RunSinks()
        assertEquals(RunState.Idle, sinks.state.value)
        assertTrue(sinks.trace.value.isEmpty())
        assertEquals("", sinks.streamingText.value)

        sinks.state.value = RunState.Running
        sinks.streamingText.value = "partial"
        sinks.reset()

        assertEquals(RunState.Idle, sinks.state.value)
        assertEquals("", sinks.streamingText.value)
    }

    // ---------------------------------------------------------------- helpers

    private fun assertNoLeakedJobs(label: String, harness: Harness, baselineChildren: Int) {
        val state = harness.viewModel.runState.value
        assertTrue("$label: expected a terminal state, got $state", state.isTerminal)
        assertTrue("$label: the agent still holds a running job", harness.agent.isIdle)
        assertEquals(
            "$label: the scope accumulated coroutines (baseline $baselineChildren, " +
                "now ${harness.activeChildren()})",
            baselineChildren,
            harness.activeChildren(),
        )
        assertFalse("$label: the screen still reports busy", harness.viewModel.isBusy)
    }
}

// --------------------------------------------------------------------- doubles

internal fun respond(text: String): GenerationResult =
    GenerationResult(text = "<respond>$text</respond>")

internal fun call(name: String, args: String): GenerationResult =
    GenerationResult(text = """<tool name="$name">$args</tool>""")

internal fun def(name: String, risk: ToolRisk) = ToolDefinition(
    name = name,
    description = "Does $name.",
    category = name.substringBefore('.'),
    schema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) })
        putJsonArray("required") { }
    },
    risk = risk,
    tags = setOf(name.substringAfter('.')),
)

/** A tool whose behaviour is a lambda, recording every call and its context. */
internal class FakeTool(
    override val definition: ToolDefinition,
    private val behavior: suspend (ToolArgs, ToolContext) -> ToolResult,
) : AgentTool {
    val contexts = mutableListOf<ToolContext>()

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        contexts += context
        return behavior(args, context)
    }

    val executionCount: Int get() = contexts.size
}

/** Scripted backend. Once the script runs out the last response repeats. */
internal open class ScriptedBackend(
    private val responses: List<GenerationResult>,
) : ModelBackend {
    override val id: String = "fake"
    override val capabilities: ModelCapabilities = ModelCapabilities(
        contextLength = 8192,
        supportsToolCalling = true,
        supportsGrammar = true,
        supportsVision = false,
        supportsKvCache = false,
    )

    val seenMessages = mutableListOf<List<CoreChatMessage>>()
    var generateCalls = 0
        protected set
    var cancelCalls = 0
        protected set

    override suspend fun load(model: ModelSpec) = Unit
    override suspend fun unload() = Unit
    override fun countTokens(text: String): Int = text.length / 4
    override fun cancel() {
        cancelCalls += 1
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        seenMessages += request.messages
        generateCalls += 1
        return responses.getOrElse(generateCalls - 1) { responses.last() }
    }
}

/**
 * Blocks inside `generate` until cancelled, the way `LlamaCppBackend` does while
 * a decode is in flight.
 *
 * The `cancel()` override is the important part: it completes the gate with
 * `StopReason.CANCELLED`, which is exactly how the real backend reports a
 * cooperative stop, and the loop turns that into `AgentResult.Cancelled`.
 */
internal class BlockingBackend(
    /** Completed with the reason to report; [StopReason.CANCELLED] on a stop. */
    private val gate: CompletableDeferred<StopReason>,
) : ScriptedBackend(emptyList()) {
    /** Resolves once the controller is actually suspended inside generate(). */
    val started = CompletableDeferred<Unit>()

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        seenMessages += request.messages
        generateCalls += 1
        started.complete(Unit)
        // Suspends until cancel() completes the gate, or the test releases it.
        val reason = gate.await()
        return GenerationResult(text = "partial output", stopReason = reason)
    }

    override fun cancel() {
        super.cancel()
        gate.complete(StopReason.CANCELLED)
    }
}

/**
 * Fails the way a real inference backend can: by throwing, despite the contract.
 *
 * `IllegalStateException` is what `LlamaCppBackend.load` raises via `error(...)`
 * when the native library is missing, so this is the production failure mode and
 * not an invented one.
 */
internal class ThrowingBackend : ScriptedBackend(emptyList()) {
    override suspend fun generate(request: GenerationRequest): GenerationResult =
        throw IllegalStateException("inference backend died")
}

/** Minimal context builder: system prompt, task, then history. */
internal object FlatContextBuilder : ContextBuilder {
    override fun build(
        task: String,
        history: List<CoreChatMessage>,
        memories: List<Memory>,
        tools: List<ToolDefinition>,
    ): List<CoreChatMessage> = listOf(
        CoreChatMessage.System(SystemPrompts.forTools(tools)),
        CoreChatMessage.User(task),
    ) + history
}

/**
 * Wires the real controller, the real parser, the real validator, the real loop
 * detector and the real tool selector; only the backend and the tools are
 * doubles.
 */
internal class Harness(
    responses: List<GenerationResult>,
    tools: List<AgentTool> = emptyList(),
    backend: ModelBackend = ScriptedBackend(responses),
    config: AgentConfig = AgentConfig(),
    modelAvailability: ModelAvailabilityHolder = ModelAvailabilityHolder(
        // Ready by default: these tests are about the run, not the model. The
        // model-gating tests pass their own holder explicitly.
        ModelAvailability.Ready,
    ),
    /**
     * Runs the agent on a scope the test owns, instead of the shared one.
     *
     * This is what makes the collector-scope tests possible: the ViewModel's
     * collector has to be a child of a scope the test can cancel and observe,
     * and it must be that scope alone — the service scope stands in for the
     * foreground service, which must *survive* the ViewModel being cleared.
     */
    extraScope: CoroutineScope? = null,
) {
    /**
     * The scripted backend, when this harness was given one.
     *
     * Null for tests that supply a bespoke backend (a blocking one, a throwing
     * one) and never read the script. Exposed as nullable so those tests are not
     * forced to satisfy a cast they have no use for.
     */
    val backend: ScriptedBackend? = backend as? ScriptedBackend

    /**
     * The scripted backend, for tests that assert on its call counters.
     *
     * A separate accessor so the nullable [backend] stays honest and the
     * `!!`-free call sites keep reading as assertions rather than as plumbing.
     */
    val scripted: ScriptedBackend
        get() = requireNotNull(backend) {
            "this harness was built with a bespoke backend and has no script"
        }
    private val registry = SimpleToolRegistry(tools)

    /** Stands in for the foreground service's scope: long-lived, process-wide. */
    val serviceJob: Job = SupervisorJob()
    val serviceScope = CoroutineScope(serviceJob + Dispatchers.Unconfined)

    /** Stands in for `viewModelScope`: dies with the ViewModel. */
    private val screenJob: Job = SupervisorJob()
    private val screenScope = CoroutineScope(screenJob + Dispatchers.Unconfined)

    private val agentScope = extraScope ?: screenScope

    val agent = AgentViewModel(
        controller = AgentController(
            model = backend,
            parser = ActionParserImpl,
            tools = registry,
            toolSelector = LexicalToolSelector(),
            validator = ToolCallValidatorGate.forRegistry(registry),
            loopDetector = LoopDetector(),
            contextBuilder = FlatContextBuilder,
            memory = InMemoryMemoryStore(),
            sessions = dev.localintelligence.core.agent.Session(),
            config = config,
        ),
        scope = agentScope,
    )

    val viewModel = ChatViewModel(
        gateway = agent,
        scopeOverride = screenScope,
        modelAvailability = modelAvailability,
    )

    /**
     * Active child coroutines of the harness scope.
     *
     * The ViewModel's own transcript collector is one of these and stays for the
     * ViewModel's lifetime; that is why the leak assertions compare against a
     * baseline rather than against zero.
     */
    fun activeChildren(): Int = screenJob.children.count { it.isActive }

    /**
     * Clears the ViewModel the way the framework does on `onCleared`.
     *
     * `onCleared` is protected, so the test cancels the scope that stands in for
     * `viewModelScope` instead. That is the same effect and the same lifecycle
     * event, which is what matters for a leak assertion.
     */
    fun clearViewModel() {
        screenJob.cancel()
    }

    /**
     * True while the agent's scope is still usable.
     *
     * The counterpart to [clearViewModel]: a rotation or a backgrounded app must
     * not take a live decode down with it.
     */
    fun serviceAlive(): Boolean = serviceJob.isActive

    fun close() {
        screenJob.cancel()
        serviceJob.cancel()
    }
}
