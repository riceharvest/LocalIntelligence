package dev.pidroid.core.agent

import dev.pidroid.core.context.ContextBuilder
import dev.pidroid.core.agent.AgentResult.Stop
import dev.pidroid.core.model.GenerationRequest
import dev.pidroid.core.model.ModelBackend
import dev.pidroid.core.model.StopReason
import dev.pidroid.core.model.ToolArgs
import dev.pidroid.core.tool.AgentTool
import dev.pidroid.core.tool.CancellationSignal
import dev.pidroid.core.tool.ObservationTruncator
import dev.pidroid.core.tool.ToolContext
import dev.pidroid.core.tool.ToolError
import dev.pidroid.core.tool.ToolRegistry
import dev.pidroid.core.tool.ToolResult
import dev.pidroid.core.tool.ToolRisk
import dev.pidroid.core.tool.ToolSelector
import kotlinx.coroutines.CancellationException
import kotlin.math.min

/**
 * The agent loop. One `while` loop, under 300 meaningful lines, no graph and no
 * planner (`docs/agent-loop.md`, `docs/architecture.md` §6).
 *
 * The only two things the model may produce are `Respond` and `CallTool`
 * (`AgentAction.kt`). Everything the runtime must reason about — validation,
 * loop detection, risk policy, observation budget, cancellation — is
 * deterministic Kotlin. The model never decides whether it needs permission.
 *
 * Invariants, in the order they are enforced each step:
 *  1. `validate()` runs before `loopDetector.check()`, so an argument error is
 *     reported as an argument error rather than as a loop.
 *  2. The loop detector runs before the confirmation gate, so a user is never
 *     asked to approve the same action twice.
 *  3. `Respond` ends the task immediately.
 *  4. Malformed output is corrected, not fatal, until `maxMalformedRetries`
 *     consecutive failures.
 *  5. A tool that throws becomes a failed `ToolResult` the model can read.
 *  6. Nothing throws out of [run] or [confirmAndResume]; every exit is an
 *     [AgentResult].
 */
class AgentController(
    private val model: ModelBackend,
    private val parser: ActionParser,
    private val tools: ToolRegistry,
    private val toolSelector: ToolSelector,
    private val validator: ToolCallValidatorGate,
    private val loopDetector: LoopDetector,
    private val contextBuilder: ContextBuilder,
    private val memory: MemoryStore,
    private val sessions: Session,
    private val config: AgentConfig = AgentConfig(),
) {
    /**
     * Run state. It outlives a single call because
     * [AgentResult.AwaitingConfirmation] returns from inside the loop and
     * [confirmAndResume] has to continue the very same run.
     */
    private val trace = mutableListOf<StepTrace>()
    private var task: String = ""
    private var step: Int = 0
    private var malformedStreak: Int = 0
    private var pending: PendingCall? = null

    /** Sticky: a cancelled controller stays cancelled. Reuse means a new controller. */
    private var cancelled: Boolean = false

    private data class PendingCall(
        val name: String,
        val tool: AgentTool,
        val args: ToolArgs,
        val step: Int,
    )

    suspend fun run(task: String): AgentResult = guarded {
        this.task = task
        trace.clear()
        step = 0
        malformedStreak = 0
        pending = null
        sessions.clear()
        loopDetector.reset()
        sessions.start(task)
        loop()
    }

    /**
     * Continues a run that suspended on [AgentResult.AwaitingConfirmation].
     * Approving executes the exact pending call and continues; declining does not
     * execute it, tells the model, and continues so it can route around it.
     */
    suspend fun confirmAndResume(approved: Boolean): AgentResult = guarded {
        val call = pending
        pending = null

        if (cancelled) return@guarded AgentResult.Cancelled
        if (call == null) return@guarded Stop("no confirmation is pending", trace.toList())

        if (approved) {
            execute(call)?.let { return@guarded it }
        } else {
            sessions.observe(
                "The user declined ${call.name}. Do not call it again. " +
                    "Do something else, or answer without it.",
            )
        }
        loop()
    }

    /**
     * Cooperative cancellation. Wires [ModelBackend.cancel] so an in-flight
     * generation stops, and is reflected in every [ToolContext] handed to a tool.
     */
    fun cancel() {
        cancelled = true
        model.cancel()
    }

    // ---------------------------------------------------------------- the loop

    private suspend fun loop(): AgentResult {
        while (step < config.maxSteps) {
            if (cancelled) return AgentResult.Cancelled
            step += 1

            // Selection happens every step, not once: after the first tool result
            // the useful tools usually change.
            val visible = selectTools()
            val generation = model.generate(buildRequest(visible))
            trace += StepTrace(
                step, StepTrace.Kind.GENERATION, generation.text.take(TRACE_DETAIL_CHARS),
                generation.prefillMs + generation.decodeMs, generation.stopReason == StopReason.COMPLETED,
            )

            when (generation.stopReason) {
                StopReason.CANCELLED -> return AgentResult.Cancelled
                StopReason.ERROR -> return Stop("model failed: ${generation.text.take(120)}", trace.toList())
                else -> Unit
            }

            when (val parsed = parse(generation.text, visible)) {
                is ActionParseResult.Malformed -> {
                    malformedStreak += 1
                    trace += StepTrace(step, StepTrace.Kind.MALFORMED, parsed.reason, success = false)
                    if (malformedStreak >= config.maxMalformedRetries) {
                        return Stop("no valid action in $malformedStreak attempts", trace.toList())
                    }
                    sessions.observe(
                        "That was not a valid action (${parsed.reason}). " +
                            "Reply with exactly one of: ${actionHint(visible)}",
                    )
                }

                is ActionParseResult.Parsed -> {
                    malformedStreak = 0
                    when (val action = parsed.action) {
                        is AgentAction.Respond -> {
                            sessions.appendAssistant(action.text)
                            return AgentResult.Success(action.text, trace.toList())
                        }

                        is AgentAction.CallTool -> handleCall(action, visible)?.let { return it }
                    }
                }
            }

            compactIfNeeded()
        }
        return AgentResult.StepLimitReached
    }

    /** Returns non-null when the run must stop. */
    private suspend fun handleCall(action: AgentAction.CallTool, visible: List<AgentTool>): AgentResult? {
        // (1) Validation first: an unknown tool or a bad argument is a correction,
        // not a loop, and the model is told what was actually wrong.
        val outcome = validator.validate(action.name, action.arguments, visible)
        val tool = when (outcome) {
            is ValidationOutcome.Rejected -> {
                val detail = "rejected ${action.name}: ${outcome.observation}"
                trace += StepTrace(step, StepTrace.Kind.TOOL_CALL, detail, success = false)
                sessions.observe(outcome.observation)
                return null
            }

            is ValidationOutcome.Ok -> outcome.tool
        }

        // (2) Loop detection before the confirmation gate: a user is never asked
        // to approve the same action twice.
        val call = PendingCall(action.name, tool, action.arguments, step)
        return when (loopDetector.check(action.name, action.arguments)) {
            LoopDetector.Verdict.BLOCK -> Stop(
                if (loopDetector.isUnavailable(action.name)) {
                    "${action.name} is unavailable after repeated failures"
                } else {
                    "loop detected: ${action.name} repeated with identical arguments"
                },
                trace.toList(),
            )

            LoopDetector.Verdict.WARN -> {
                sessions.observe(
                    "You already ran ${action.name} with those exact arguments. " +
                        "Do something different, or answer the user.",
                )
                null
            }

            LoopDetector.Verdict.ALLOW -> when (policyOf(tool)) {
                Policy.EXECUTE -> execute(call)

                Policy.CONFIRM -> {
                    pending = call
                    AgentResult.AwaitingConfirmation(
                        action.name, tool, action.arguments, trace.toList(),
                    )
                }

                Policy.REFUSE -> {
                    trace += StepTrace(
                        step, StepTrace.Kind.TOOL_CALL,
                        "refused ${action.name}: privileged tools are disabled", success = false,
                    )
                    sessions.observe(
                        "${action.name} is disabled in this build and cannot run. " +
                            "Do something else, or answer without it.",
                    )
                    null
                }
            }
        }
    }

    /** Runs one tool. Returns non-null when the run must stop. */
    private suspend fun execute(call: PendingCall): AgentResult? {
        if (cancelled) return AgentResult.Cancelled

        val context = ToolContext(
            userConfirmed = call.tool.definition.risk.requiresConfirmation,
            signal = CancellationSignal { cancelled },
        )

        val startedAt = System.nanoTime()
        val result: ToolResult = try {
            call.tool.execute(call.args, context)
        } catch (e: CancellationException) {
            // A coroutine-level cancellation is structured concurrency, not a bug.
            if (!cancelled) throw e
            failed(call.name, "cancelled")
        } catch (t: Throwable) {
            // A tool that throws is a failed tool, never a dead agent.
            failed(call.name, "${t::class.java.simpleName}: ${t.message}")
        }
        val durationMs = (System.nanoTime() - startedAt) / 1_000_000

        val observation = truncate(result.observation)
        loopDetector.recordResult(call.name, observation, result.success)
        sessions.appendToolObservation(call.tool, observation, result.success)
        trace += StepTrace(
            call.step, StepTrace.Kind.TOOL_CALL, call.name + compactArgs(call.args),
            durationMs, result.success,
        )
        trace += StepTrace(call.step, StepTrace.Kind.OBSERVATION, observation, success = result.success)

        if (cancelled) return AgentResult.Cancelled
        if (loopDetector.hasStalled()) {
            return Stop("no progress: ${call.name} kept returning the same result", trace.toList())
        }
        return null
    }

    // ------------------------------------------------------------ collaborators

    private fun selectTools(): List<AgentTool> = try {
        toolSelector.select(task, sessions.currentKeywords(), tools.all(), config.maxVisibleTools)
    } catch (t: Throwable) {
        tools.all().take(config.maxVisibleTools)
    }

    private suspend fun buildRequest(visible: List<AgentTool>): GenerationRequest {
        val memories = try {
            memory.search(task, config.memoryResults)
        } catch (t: Throwable) {
            emptyList()
        }
        val history = try {
            contextBuilder.build(task, sessions.messages, memories, visible.map { it.definition })
        } catch (t: Throwable) {
            sessions.messages
        }
        return GenerationRequest(
            messages = history,
            // Constrained generation is P0 (`docs/architecture.md` §15). The grammar
            // comes from GrammarBuilder.forActions, which lands with the parser
            // workstream; until then allowedToolNames is what tells a backend which
            // calls are legal. This is the one line that has to change.
            grammar = null,
            allowedToolNames = visible.map { it.definition.name },
        )
    }

    private fun parse(raw: String, visible: List<AgentTool>): ActionParseResult = try {
        parser.parse(raw, visible.map { it.definition.name }.toSet())
    } catch (t: Throwable) {
        // The parser contract says it is total. A throw is malformed output.
        ActionParseResult.Malformed("parser failed: ${t::class.java.simpleName}", raw)
    }

    /**
     * What the model is told it may reply with. Mirrors the shape of
     * `GrammarBuilder.hint` from `docs/wave1-contract.md`; swapped for the real
     * call when the parser workstream lands.
     */
    private fun actionHint(visible: List<AgentTool>): String =
        if (visible.isEmpty()) {
            "a plain answer."
        } else {
            "Respond(text) — or — CallTool(name, arguments), where name is one of: " +
                visible.joinToString(", ") { it.definition.name } + "."
        }

    private fun truncate(observation: String): String {
        val budget = config.observationBudgetChars
        return when {
            observation.length <= budget -> observation
            // ObservationTruncator takes (budget - 40) and throws below 40.
            budget > TRUNCATOR_MIN_SAFE_BUDGET -> ObservationTruncator.truncate(observation, budget)
            else -> observation.take(budget.coerceAtLeast(0))
        }
    }

    private fun compactArgs(args: ToolArgs): String =
        args.entries.take(6)
            .joinToString(", ", "{", "}") { (key, value) -> "$key=${value.toString().take(40)}" }

    /**
     * The runtime decides what needs confirmation — never the model, never the
     * tool (`docs/architecture.md` §8). `requiresConfirmation` is derived from the
     * risk enum, so a tool cannot lie about needing permission.
     */
    private fun policyOf(tool: AgentTool): Policy = when {
        tool.definition.risk == ToolRisk.PRIVILEGED -> Policy.REFUSE
        tool.definition.risk.requiresConfirmation -> Policy.CONFIRM
        else -> Policy.EXECUTE
    }

    private fun compactIfNeeded() {
        val active = sessions.tokens(model)
        val window = model.capabilities.contextLength
        val limit = if (window > 0) {
            min((window * COMPACT_AT).toInt(), config.workingTokenLimit)
        } else {
            config.workingTokenLimit
        }
        if (active <= limit) return
        if (!sessions.compact()) return
        trace += StepTrace(step, StepTrace.Kind.COMPACTION, "compacted $active tokens (limit $limit)")
    }

    private fun failed(toolName: String, detail: String): ToolResult = ToolResult(
        success = false,
        observation = "$toolName failed: $detail. Do not retry it unchanged; " +
            "say what went wrong or take a different approach.",
        error = ToolError.Internal(detail.take(LINE_CHARS)),
    )

    /**
     * The loop must not throw. A coroutine-level cancellation is rethrown,
     * because that is structured concurrency and belongs to the caller.
     */
    private suspend fun guarded(body: suspend () -> AgentResult): AgentResult = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Stop("internal error: ${t::class.java.simpleName}: ${t.message}", trace.toList())
    }

    private enum class Policy { EXECUTE, CONFIRM, REFUSE }

    private companion object {
        const val COMPACT_AT = 0.65
        const val TRACE_DETAIL_CHARS = 512
        const val LINE_CHARS = 200
        const val TRUNCATOR_MIN_SAFE_BUDGET = 64
    }
}
