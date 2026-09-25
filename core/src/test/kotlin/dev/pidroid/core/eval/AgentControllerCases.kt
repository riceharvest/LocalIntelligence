package dev.pidroid.core.eval

import dev.pidroid.core.agent.ActionParseResult
import dev.pidroid.core.agent.ActionParser
import dev.pidroid.core.agent.AgentAction
import dev.pidroid.core.agent.LoopDetector
import dev.pidroid.core.agent.Memory
import dev.pidroid.core.agent.MemoryStore
import dev.pidroid.core.context.ContextBuilder
import dev.pidroid.core.model.ChatMessage
import dev.pidroid.core.model.GenerationRequest
import dev.pidroid.core.model.SamplingParams
import dev.pidroid.core.model.ToolArgs
import dev.pidroid.core.tool.ObservationTruncator
import dev.pidroid.core.tool.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ===========================================================================
// AgentControllerCases.kt — the loop-level glue.
//
// WHY THIS FILE EXISTS AND WHY IT LOOKS LIKE THIS
// ================================================
//
// `AgentController` is being written in a parallel worktree against the pinned
// signatures in docs/wave1-contract.md. It does not exist on this branch yet, so
// code that references it directly would not compile and the branch would be red.
//
// This file therefore has two layers:
//
//   Layer 1 (below)  ReferenceAgentLoop — a test double that implements the
//                    loop semantics of docs/architecture.md §6 against ONLY the
//                    frozen contracts that DO exist today: ActionParser,
//                    ToolRegistry, ToolSelector, LoopDetector,
//                    ObservationTruncator, MemoryStore, ContextBuilder.
//                    It compiles now, runs now, and is exercised by all 50
//                    tasks. It is a double, not the product.
//
//   Layer 2 (bottom) AgentControllerRunner — the thin adapter that will drive
//                    the real AgentController the moment it lands. It is
//                    written against the pinned signature and is the ONLY thing
//                    that changes at integration time.
//
// The two layers implement the same [AgentRunner] interface, so the 50 tasks,
// the scorer and the reporter are agnostic to which one is driving. Swapping
// is a one-line change in EvalMain.kt.
//
// The reference loop is faithful to the architecture doc, not a stub: it
// honours the step limit, the malformed-retry budget, the confirmation gate,
// loop detection, observation truncation, and turns a thrown tool exception
// into a failed ToolResult rather than taking the step down with it.
// ===========================================================================

/** What the harness needs from a loop, regardless of which one is driving. */
interface AgentRunner {
    suspend fun run(task: EvalTask): CompletedRun
}

// ---------------------------------------------------------------------------
// Layer 1: the reference loop (test double)
// ---------------------------------------------------------------------------

/**
 * Parses the eval action protocol: `{"action":"respond","text":...}` or
 * `{"action":"call","name":...,"args":{...}}`.
 *
 * Total by contract: anything unparseable is [ActionParseResult.Malformed],
 * never an exception. A parser that throws on a bad generation is a parser
 * that turns a 3B model's typo into a crashed app.
 */
class EvalActionParser : ActionParser {
    override fun parse(raw: String, allowedTools: Set<String>): ActionParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return ActionParseResult.Malformed("empty output", raw)
        }
        val obj = try {
            Json.parseToJsonElement(trimmed).jsonObject
        } catch (e: Exception) {
            return ActionParseResult.Malformed("not valid JSON: ${e.message}", raw)
        }

        return when (obj["action"]?.jsonPrimitive?.content) {
            "respond" -> {
                val text = obj["text"]?.jsonPrimitive?.content
                if (text.isNullOrBlank()) {
                    ActionParseResult.Malformed("respond without text", raw)
                } else {
                    ActionParseResult.Parsed(AgentAction.Respond(text))
                }
            }
            "call" -> {
                val name = obj["name"]?.jsonPrimitive?.content
                if (name.isNullOrBlank()) {
                    ActionParseResult.Malformed("call without a tool name", raw)
                } else {
                    val args = (obj["args"] as? JsonObject) ?: EMPTY_ARGS
                    ActionParseResult.Parsed(AgentAction.CallTool(name, args))
                }
            }
            else -> ActionParseResult.Malformed("unknown action shape", raw)
        }
    }

    companion object {
        val EMPTY_ARGS: ToolArgs = buildJsonObject { }
    }
}

/** A minimal [ContextBuilder]. Agent D owns the real one; this keeps the shape. */
internal class ReferenceContextBuilder(
    private val memoryResults: Int = 5,
) : ContextBuilder {
    override fun build(
        task: String,
        history: List<ChatMessage>,
        memories: List<Memory>,
        tools: List<dev.pidroid.core.tool.ToolDefinition>,
    ): List<ChatMessage> = buildList {
        add(ChatMessage.System(dev.pidroid.core.context.SystemPrompts.forTools(tools)))
        add(ChatMessage.User(task))
        if (memories.isNotEmpty()) {
            add(
                ChatMessage.System(
                    "Remembered facts:\n" + memories.take(memoryResults).joinToString("\n") { "- ${it.text}" }
                )
            )
        }
        addAll(history)
    }
}

/** Everything one run accumulated. Mirrors the fields of CompletedRun. */
private class RunState {
    val history = mutableListOf<ChatMessage>()
    val attempted = mutableListOf<ExecutedCall>()
    val invoked = mutableListOf<ExecutedCall>()
    val executed = mutableListOf<ExecutedCall>()
    val rejected = mutableListOf<RejectedCall>()
    val failedTools = mutableListOf<String>()
    var maxObservation = 0
    var awaitingConfirmation: String? = null
    var duplicateExecutions = 0
}

/**
 * A faithful implementation of the loop in docs/architecture.md §6, built on the
 * frozen contracts only.
 *
 * The order of checks is the whole design: a call is validated, then loop-checked,
 * then risk-gated, then executed. A model cannot skip a gate by naming a tool
 * that is not visible, and it cannot re-issue a failing call forever.
 */
class ReferenceAgentLoop(
    private val maxSteps: Int = 8,
    private val maxMalformedRetries: Int = 3,
    private val maxVisibleTools: Int = 8,
    private val memoryResults: Int = 5,
) : AgentRunner {

    override suspend fun run(task: EvalTask): CompletedRun {
        val tools = buildTaskTools(task)
        val registry = MockToolRegistry(tools)
        val backend = FakeModelBackend(task.modelScript)
        val parser = EvalActionParser()
        val detector = LoopDetector()
        val contextBuilder = ReferenceContextBuilder(memoryResults)
        val memory: MemoryStore = dev.pidroid.core.agent.InMemoryMemoryStore()
        val selector = registry.selectorFor(task.visibleTools, maxVisibleTools)
        val gate = registry.gate()

        val state = RunState()
        val visible = selector.select(task.utterance, emptyList(), registry.all(), maxVisibleTools)
        val visibleNames = visible.map { it.definition.name }
        val startedAt = System.nanoTime()
        var malformedCount = 0
        var steps = 0
        var answer: String? = null
        var stopReason: String? = null

        outer@ while (steps < maxSteps) {
            steps++
            val memories = memory.search(task.utterance, memoryResults)
            val messages = contextBuilder.build(
                task = task.utterance,
                history = state.history,
                memories = memories,
                tools = visible.map { it.definition },
            )
            val request = GenerationRequest(
                messages = messages,
                params = SamplingParams(temperature = 0.0f, seed = 1337),
                allowedToolNames = visibleNames,
            )
            val generation = backend.generate(request)

            when (val parsed = parser.parse(generation.text, visibleNames.toSet())) {
                is ActionParseResult.Malformed -> {
                    malformedCount++
                    stopReason = "malformed output: ${parsed.reason}"
                    if (malformedCount > maxMalformedRetries) break@outer
                    state.history += ChatMessage.ToolObservation(
                        toolName = "system",
                        observation = "That was not a valid action. Emit exactly one JSON object: " +
                            "{\"action\":\"respond\",\"text\":\"...\"} or " +
                            "{\"action\":\"call\",\"name\":\"...\",\"args\":{...}}.",
                        success = false,
                    )
                }

                is ActionParseResult.Parsed -> when (val action = parsed.action) {
                    is AgentAction.Respond -> {
                        answer = action.text
                        stopReason = "responded"
                        break@outer
                    }

                    is AgentAction.CallTool -> {
                        state.attempted += ExecutedCall(action.name, action.arguments)

                        // 1. validate against the VISIBLE set, not the registry
                        val validation = gate.validate(action.name, action.arguments, visible)
                        val tool = when (validation) {
                            is GateOutcome.Rejected -> {
                                state.rejected += RejectedCall(
                                    action.name, action.arguments, validation.observation,
                                )
                                observe(state, action.name, validation.observation, false)
                                continue@outer
                            }
                            is GateOutcome.Ok -> validation.tool
                        }

                        // 2. loop detection, before execution
                        val verdict = detector.check(action.name, action.arguments)
                        if (verdict == LoopDetector.Verdict.BLOCK) {
                            stopReason = "loop blocked: ${action.name} repeated too many times"
                            state.rejected += RejectedCall(
                                action.name, action.arguments, stopReason!!,
                            )
                            break@outer
                        }
                        if (verdict == LoopDetector.Verdict.WARN) {
                            val warning = "You already tried exactly this. Change your approach."
                            state.rejected += RejectedCall(action.name, action.arguments, warning)
                            observe(state, action.name, warning, false)
                            continue@outer
                        }

                        // 3. risk gate — the runtime decides, never the model
                        if (tool.definition.risk.requiresConfirmation) {
                            state.awaitingConfirmation = tool.definition.name
                            stopReason = "awaiting confirmation for ${tool.definition.name}"
                            break@outer
                        }

                        // 4. execute. A thrown exception becomes a failed result.
                        val result = try {
                            tool.execute(action.arguments, dev.pidroid.core.tool.ToolContext())
                        } catch (e: Exception) {
                            ToolResult(
                                success = false,
                                observation = "${tool.definition.name} failed: ${e.message ?: e::class.simpleName}",
                                error = dev.pidroid.core.tool.ToolError.Internal(e.message ?: "tool threw"),
                            )
                        }

                        state.invoked += ExecutedCall(action.name, action.arguments)
                        val observation = ObservationTruncator.truncate(result.observation)
                        state.maxObservation = maxOf(state.maxObservation, observation.length)
                        detector.recordResult(tool.definition.name, result.observation, result.success)

                        if (result.success) {
                            state.executed += ExecutedCall(action.name, action.arguments)
                        } else {
                            state.failedTools += tool.definition.name
                        }
                        observe(state, action.name, observation, result.success)

                        // 5. no-progress termination
                        if (detector.hasStalled()) {
                            stopReason = "stalled: repeated calls changed nothing"
                            break@outer
                        }
                    }
                }
            }
        }

        if (answer == null && stopReason == null) stopReason = "step limit reached ($maxSteps)"

        return CompletedRun(
            steps = steps,
            attemptedCalls = state.attempted,
            executedCalls = state.executed,
            invokedCalls = state.invoked,
            rejectedCalls = state.rejected,
            prompts = backend.prompts,
            finalAnswer = answer,
            stopReason = stopReason,
            inputTokens = backend.promptTokensTotal,
            outputTokens = backend.outputTokensTotal,
            totalMs = (System.nanoTime() - startedAt) / 1_000_000,
            peakRamBytes = usedHeapBytes(),
            awaitingConfirmationFor = state.awaitingConfirmation,
            failedTools = state.failedTools,
            maxObservationChars = state.maxObservation,
        )
    }

    private fun observe(state: RunState, tool: String, observation: String, success: Boolean) {
        state.history += ChatMessage.ToolObservation(tool, observation, success)
    }
}

/** Deterministic heap read. Reported only — never asserted on. */
internal fun usedHeapBytes(): Long {
    val rt = Runtime.getRuntime()
    return (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0)
}

// ---------------------------------------------------------------------------
// Task -> tools
// ---------------------------------------------------------------------------

/**
 * Builds the scripted tools for a task: one [ScriptedTool] per visible name,
 * with the failure mode the task asks for (denied / throwing / empty / huge) and
 * any per-task observation override.
 */
internal fun buildTaskTools(task: EvalTask): List<ScriptedTool> =
    task.visibleTools.map { name -> buildOne(name, task) }

private fun buildOne(name: String, task: EvalTask): ScriptedTool {
    val base = TaskToolLibrary.toolFor(name)
    val spec = TaskToolLibrary.specFor(name)
    val denied = name in task.deniedTools
    val throwing = name in task.throwingTools
    val empty = name in task.emptyTools
    val huge = name in task.hugeTools
    val override = task.observations[name]

    // No single failure mode wins: a task asking for both "denied" and
    // "throwing" is a fixture bug, not a scenario.
    check(listOf(denied, throwing, empty, huge).count { it } <= 1) {
        "Task ${task.slug} gives tool \"$name\" more than one scripted failure mode."
    }

    return when {
        throwing -> ScriptedTool(base.definition, emptyList(), throwOnCall = true, throwMessage = "$name provider crashed")
        denied -> ScriptedTool(
            definition = base.definition,
            results = listOf(
                dev.pidroid.core.tool.ToolResult(
                    success = false,
                    observation = "Permission denied: $name needs ${spec.permission ?: "a permission"}. " +
                        "Grant it in Settings to let me do that.",
                    error = dev.pidroid.core.tool.ToolError.PermissionDenied(spec.permission ?: "unknown"),
                )
            ),
        )
        empty -> ScriptedTool(
            definition = base.definition,
            results = listOf(
                dev.pidroid.core.tool.ToolResult(success = true, observation = "No results for \"$name\".")
            ),
        )
        huge -> ScriptedTool(
            definition = base.definition,
            results = listOf(
                dev.pidroid.core.tool.ToolResult(
                    success = true,
                    observation = ScriptedTools.buildHugeObservation(
                        override ?: "1 match for $name: INFLATED RESULT.", 200_000,
                    ),
                )
            ),
        )
        override != null -> ScriptedTool(
            definition = base.definition,
            results = listOf(dev.pidroid.core.tool.ToolResult(success = true, observation = override)),
        )
        else -> base
    }
}

// ---------------------------------------------------------------------------
// Layer 2: the real AgentController adapter
// ---------------------------------------------------------------------------
//
// CHECKLIST — what AgentControllerCases.kt needs in order to run against the
// real loop instead of the reference double:
//
//  1. `dev.pidroid.core.agent.AgentController` exists, with the exact
//     constructor from docs/wave1-contract.md:
//       (model, parser, tools, toolSelector, validator, loopDetector,
//        contextBuilder, memory, sessions, config)
//  2. `dev.pidroid.core.agent.AgentConfig`, `AgentResult`, `StepTrace` exist as
//     pinned, including `StepTrace.Kind` with all five kinds.
//  3. `dev.pidroid.core.agent.Session` exists (or the compact() signature was
//     changed to take (history, summary, model) as the contract allows).
//  4. `dev.pidroid.core.agent.ToolCallValidatorGate` + `ValidationOutcome`
//     exist as pinned, so `MockToolRegistry.gate()` can be adapted by mapping
//     GateOutcome -> ValidationOutcome.
//  5. `dev.pidroid.core.context.DefaultContextBuilder` exists, or the harness
//     keeps ReferenceContextBuilder (it only needs the frozen interface).
//
// Once 1-4 hold, `AgentControllerRunner` below is the only code that changes:
// it maps AgentResult + StepTrace into CompletedRun. The 50 tasks, the scorer
// and the reporter are untouched, which is the point of the seam.
//
// class AgentControllerRunner(private val deps: Deps) : AgentRunner {
//     override suspend fun run(task: EvalTask): CompletedRun { ... }
// }
