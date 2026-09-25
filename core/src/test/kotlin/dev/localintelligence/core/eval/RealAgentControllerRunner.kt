// Layer 2: the REAL AgentController adapter.
//
// The reference loop in AgentControllerCases.kt was a faithful double so the 50
// tasks could run before AgentController existed. Now that the real loop is on
// the branch, the default driver is the real thing: a green suite here is
// evidence about AgentController, not about a test double.
//
// ReferenceAgentLoop is kept deliberately. A double nobody runs rots, and
// keeping it lets the harness prove the two layers still agree on every task —
// which is what catches a change to the real loop that silently breaks a
// behaviour the tasks depend on.

package dev.localintelligence.core.eval

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.InMemoryMemoryStore
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.MemoryStore
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.context.DefaultContextBuilder
import dev.localintelligence.core.model.ToolArgs

/**
 * Drives the real [AgentController] over the eval suite.
 *
 * Everything is injected exactly as `:app` will do it. The tasks, scorer and
 * reporter are untouched by the swap — that is what the [AgentRunner] seam is for.
 */
class RealAgentControllerRunner(
    private val maxSteps: Int = 8,
    private val maxVisibleTools: Int = 6,
    private val memoryResults: Int = 5,
) : AgentRunner {

    override suspend fun run(task: EvalTask): CompletedRun {
        val registry = MockToolRegistry(buildTaskTools(task))
        val backend = FakeModelBackend(task.modelScript)
        val session = Session(id = 1L)
        session.start(task.utterance)

        val memory: MemoryStore = InMemoryMemoryStore()
        val controller = AgentController(
            model = backend,
            parser = EvalActionParser(),
            tools = registry,
            toolSelector = registry.selectorFor(task.visibleTools, maxVisibleTools),
            // The harness declares its own GateOutcome so the reference loop can
            // be written before the loop existed. This maps it onto the loop's
            // real seam — validation semantics stay the ones the contract mandates.
            validator = registry.gate().toLoopGate(),
            loopDetector = LoopDetector(),
            // The REAL context builder, so the fake model sees the same prompt a
            // device would produce. A context-budget regression then shows up here.
            contextBuilder = DefaultContextBuilder(workingLimit = 6000),
            memory = memory,
            sessions = session,
            config = AgentConfig(
                maxSteps = maxSteps,
                maxVisibleTools = maxVisibleTools,
                memoryResults = memoryResults,
            ),
        )

        val startedAt = System.nanoTime()
        var result = controller.run(task.utterance)
        var awaitingFor: String? = null

        // The eval asserts on the CONFIRMATION GATE, not on the approval UI.
        //
        // A confirmation-gated tool is NOT auto-approved here: the scorer's
        // contract (EvalScorer.checkConfirmation + the trajectory check) is that a
        // gated run parks before the risky tool and never executes it, because
        // proving the loop STOPS is the property under test. Auto-approving would
        // make every gated task report a spurious extra call and would stop
        // testing the gate at all.
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        if (result is AgentResult.AwaitingConfirmation) awaitingFor = result.toolName

        val trace = result.trace()
        val steps = trace.count { it.kind == StepTrace.Kind.GENERATION }

        // What the runtime ATTEMPTED, from the loop's own trace.
        val attempted = trace
            .filter { it.kind == StepTrace.Kind.TOOL_CALL }
            .map { ExecutedCall(it.toolName() ?: "?", ToolArgs(emptyMap())) }

        // What actually RAN, in the order it happened.
        //
        // Order comes from the loop's trace; arguments and invocation come from
        // the scripted tools, which are the ground truth.
        //
        // NOTE: a TOOL_CALL trace entry is appended for EVERY execution, whether
        // it succeeded or not — `success` on the entry is the TOOL RESULT's
        // success, not "did it run". Filtering on it would silently drop every
        // denied-permission and thrown-tool call, which is precisely the
        // behaviour the failure/recovery category exists to verify.
        val ranOrder = trace
            .filter { it.kind == StepTrace.Kind.TOOL_CALL }
            .map { it.toolName() ?: "?" }
        val ranByTool = registry.allCalls().groupBy { it.name }
        val actuallyRan = ranOrder.map { name ->
            val args = ranByTool[name]?.firstOrNull()?.args ?: ToolArgs(emptyMap())
            ExecutedCall(name, args)
        }
        val executedNames = actuallyRan.map { it.name }.toSet()

        // Rejected before execution: the validator refused, or the loop detector
        // blocked a repeat. Those are calls that never reached a tool.
        val rejected = trace
            .filter { it.kind == StepTrace.Kind.TOOL_CALL && it.toolName() !in executedNames }
            .map { entry ->
                RejectedCall(entry.toolName() ?: "?", ToolArgs(emptyMap()), entry.detail)
            }
            .plus(
                trace.filter { it.kind == StepTrace.Kind.MALFORMED }
                    .map { RejectedCall("<malformed>", ToolArgs(emptyMap()), it.detail) }
            )

        val failedTools = registry.allCalls()
            .map { it.name }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 0 }
            .keys
            .toList()

        val prompts = backend.prompts.toList()
        val maxObservation = prompts
            .flatMap { OBSERVATION.findAll(it).map { m -> m.groupValues[1] } }
            .maxOfOrNull { it.length } ?: 0

        return CompletedRun(
            steps = steps,
            attemptedCalls = attempted,
            executedCalls = actuallyRan,
            invokedCalls = actuallyRan,
            rejectedCalls = rejected,
            prompts = prompts,
            finalAnswer = (result as? AgentResult.Success)?.text,
            stopReason = result.stopReasonText(),
            inputTokens = backend.requests.sumOf { req ->
                req.messages.sumOf { m -> backend.countTokens(m.flatText()) }.coerceAtLeast(1)
            },
            outputTokens = backend.requests.sumOf { backend.countTokens(it.rendered()) / 4 }
                .coerceAtLeast(0),
            totalMs = elapsedMs,
            peakRamBytes = 0L,
            awaitingConfirmationFor = awaitingFor,
            failedTools = failedTools,
            maxObservationChars = maxObservation,
        )
    }
}

private fun AgentResult.trace(): List<StepTrace> = when (this) {
    is AgentResult.Success -> trace
    is AgentResult.AwaitingConfirmation -> trace
    is AgentResult.Stop -> trace
    AgentResult.StepLimitReached -> emptyList()
    AgentResult.Cancelled -> emptyList()
}

private fun AgentResult.stopReasonText(): String = when (this) {
    is AgentResult.Success -> "responded"
    is AgentResult.AwaitingConfirmation -> "awaiting confirmation for $toolName"
    is AgentResult.Stop -> reason
    AgentResult.StepLimitReached -> "step limit reached"
    AgentResult.Cancelled -> "cancelled"
}

/**
 * StepTrace.detail is human-readable text, so the tool name is recovered from it.
 * Isolated in one function so a change to the loop's trace format breaks one line
 * rather than the whole adapter.
 */
private fun StepTrace.toolName(): String? =
    TOOL_NAME.find(detail)?.groupValues?.get(1)

private val TOOL_NAME = Regex("\\b([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)")

private val OBSERVATION = Regex("""<observation tool="[^"]*">([^<]*)<""")

private fun dev.localintelligence.core.model.ChatMessage.flatText(): String = when (this) {
    is dev.localintelligence.core.model.ChatMessage.System -> text
    is dev.localintelligence.core.model.ChatMessage.User -> text
    is dev.localintelligence.core.model.ChatMessage.Assistant -> text
    is dev.localintelligence.core.model.ChatMessage.ToolObservation -> observation
}

/** What the model actually produced for this request, for output-token accounting. */
private fun dev.localintelligence.core.model.GenerationRequest.rendered(): String =
    messages.joinToString("\n") { it.flatText() }

/**
 * Adapt the harness's gate to the loop's validation seam.
 *
 * Both sides exist because two agents were parallel: the harness declared its
 * own outcome type so the reference loop could be written without the real
 * AgentController. Only this mapping bridges them.
 */
private fun ToolCallValidatorGate.toLoopGate() =
    dev.localintelligence.core.agent.ToolCallValidatorGate { name, args, visibleTools ->
        when (val outcome = validate(name, args, visibleTools)) {
            is GateOutcome.Ok -> dev.localintelligence.core.agent.ValidationOutcome.Ok(
                outcome.tool ?: visibleTools.first { it.definition.name == name }
            )
            is GateOutcome.Rejected ->
                dev.localintelligence.core.agent.ValidationOutcome.Rejected(outcome.observation)
        }
    }
