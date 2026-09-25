package dev.localintelligence.core.agent

import dev.localintelligence.core.context.ContextBuilder
import dev.localintelligence.core.model.GrammarBuilder
import dev.localintelligence.core.agent.AgentResult.Stop
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.metrics.RunMetrics
import dev.localintelligence.core.metrics.RunRecorder
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.policy.ConfirmationOutcome
import dev.localintelligence.core.policy.ConfirmationSession
import dev.localintelligence.core.policy.Decision
import dev.localintelligence.core.policy.PolicyOutcome
import dev.localintelligence.core.policy.RiskPolicy
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolRegistry
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolSelector
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
 *  7. The decision to run, confirm, or refuse a call comes from
 *     [RiskPolicy] and is made against the CALL'S ARGUMENTS, not just its
 *     declared risk tier. `docs/architecture.md` §8 — the runtime decides,
 *     never the model, and never the tool.
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
    /**
     * WHY this is a defaulted tenth parameter and not a seventh collaborator:
     * every existing caller — the eval harness, the pre-wiring
     * [AppContainer], the loop's own unit tests — must keep constructing a
     * controller without editing its call site. A defaulted parameter means
     * the safety gate is on for everyone, and there is no way to build a
     * controller that runs ungated by omission.
     */
    private val riskPolicy: RiskPolicy = RiskPolicy(),
    /**
     * Optional run metrics. WHY optional rather than required: metrics are
     * observability, not correctness, and a caller that has nowhere to write
     * them must not be forced to allocate a recorder to get a working agent.
     * Set this and the loop reports tokens, latencies and duplicate calls for
     * every run without the loop learning what any of those mean.
     */
    private val metrics: RunRecorder? = null,
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

    /**
     * The frozen metrics for the current run, published when it ends.
     *
     * WHY the loop holds this rather than a caller: a run ends at five
     * different return statements, and a recorder that is only finished on
     * the happy path is a recorder that reports nothing about the runs that
     * actually went wrong.
     */
    var lastRunMetrics: RunMetrics? = null
        private set

    /** Sticky: a cancelled controller stays cancelled. Reuse means a new controller. */
    private var cancelled: Boolean = false

    private data class PendingCall(
        val name: String,
        val tool: AgentTool,
        val args: ToolArgs,
        val step: Int,
        /**
         * WHY the session is carried here and not recomputed: the approval the
         * user grants is a capability bound to the exact arguments they were
         * shown. Losing it between [run] returning
         * [AgentResult.AwaitingConfirmation] and [confirmAndResume] would leave
         * a boolean standing in for a security decision, which is the TOCTOU
         * bug [ConfirmationSession] exists to make inexpressible.
         *
         * Null for a call that was not parked, i.e. one the policy allowed to
         * run. There is nothing to confirm in that case, so there is nothing to
         * bind. `confirmAndResume` refuses to execute a null session rather
         * than treating "no gate" as "permission".
         */
        val session: ConfirmationSession?,
        /**
         * True once a human has approved this specific call.
         *
         * WHY it is on the call rather than derived from the risk tier: the
         * previous code set `userConfirmed = risk.requiresConfirmation`, which
         * is a lie for every call the policy gated for a reason the tier does
         * not carry. A tool told "the user confirmed you" when nobody approved
         * it skips its own safety check.
         */
        val userConfirmed: Boolean = false,
        /**
         * Whether the host has granted whatever permission this tool declares.
         *
         * WHY this is per-call and not read from the registry: the policy
         * decides the permission STATE, and the tool renders the denial. Passing
         * it down in [dev.localintelligence.core.tool.ToolContext] is what makes
         * every existing Android tool's permission branch reachable — without
         * it, [ToolContext.permissionGranted] stays at its `true` default and a
         * tool can never tell it was denied anything.
         */
        val permissionGranted: Boolean = true,
    )

    suspend fun run(task: String): AgentResult = measured {
        this.task = task
        trace.clear()
        step = 0
        malformedStreak = 0
        pending = null
        sessions.clear()
        loopDetector.reset()
        // Blast radius is per TASK, not per process. Without this reset the
        // 200-action and 20-destructive-action caps would carry over between
        // conversations, so a user who legitimately deleted 19 things today
        // could not delete one more tomorrow without a reinstall.
        riskPolicy.resetTask()
        metrics?.beginStep(0)
        sessions.start(task)
        loop()
    }

    /**
     * Continues a run that suspended on [AgentResult.AwaitingConfirmation].
     * Approving executes the exact pending call and continues; declining does not
     * execute it, tells the model, and continues so it can route around it.
     */
    suspend fun confirmAndResume(approved: Boolean): AgentResult = measured {
        val call = pending
        pending = null

        if (cancelled) return@measured AgentResult.Cancelled
        if (call == null) return@measured Stop("no confirmation is pending", trace.toList())

        if (approved) {
            // No session means nothing was ever parked, so nothing was ever
            // shown to the user. "Approved" over a call nobody saw is not an
            // approval, and treating it as one is the TOCTOU bug in its purest
            // form — so it executes nothing.
            val session = call.session
            if (session == null) {
                trace += StepTrace(
                    step, StepTrace.Kind.TOOL_CALL,
                    "refused ${call.name}: approved without a confirmation to approve",
                    success = false,
                )
                sessions.observe(
                    "${call.name} was not run. It was never put to the user for approval, so it " +
                        "cannot be treated as approved. Do something else, or answer without it.",
                )
                return@measured loop()
            }
            // The approval is spent against the arguments the user was shown, and
            // `claim` hands back the session's OWN frozen copy — never the
            // caller's. If anything about the proposed call changed while the
            // dialog was up, `claim` returns null and nothing runs. That is the
            // whole point: a Boolean approval cannot be bound to a payload, so
            // "approve sending to Alice" and "execute sending to a stranger"
            // would otherwise be indistinguishable to the runtime.
            when (val outcome = session.resolve(true)) {
                is ConfirmationOutcome.Approved -> {
                    val claimed = outcome.approval.claim(call.name, call.args)
                    if (claimed == null) {
                        // TOCTOU: the proposal no longer matches what was approved.
                        trace += StepTrace(
                            step, StepTrace.Kind.TOOL_CALL,
                            "refused ${call.name}: the call changed after it was approved",
                            success = false,
                        )
                        sessions.observe(
                            "${call.name} could not be run: the call changed after the user " +
                                "approved it, so it was refused. Say what happened, or make the " +
                                "call again with the exact arguments you want.",
                        )
                        return@measured loop()
                    }
                    // A human approved this one, so it counts against the
                    // destructive quota even though it never went through
                    // `recordExecuted` as an automatic call.
                    riskPolicy.recordConfirmed(call.tool.definition.risk)
                    execute(call.copy(args = claimed, userConfirmed = true))?.let { return@measured it }
                }

                is ConfirmationOutcome.Denied ->
                    sessions.observe(outcome.reason)

                is ConfirmationOutcome.AlreadyResolved -> sessions.observe(outcome.reason)
            }
        } else {
            // The loop's own wording, not `ConfirmationSession.Denied.reason`.
            //
            // WHY: the session's reason is phrased for an audit log ("was
            // declined"), while this string is read by a 1-3B model choosing its
            // next move and is pinned by the eval suite. Changing it changes
            // measured behaviour, so it stays exactly as it was.
            //
            // The session IS still resolved, so a double-tap on "decline" is a
            // no-op rather than a second model-visible message.
            call.session?.resolve(false)
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
            metrics?.beginStep(step)

            // Selection happens every step, not once: after the first tool result
            // the useful tools usually change.
            val visible = selectTools()
            val generation = model.generate(buildRequest(visible))
            metrics?.recordGeneration(generation)
            metrics?.endPhase(StepTrace.Kind.GENERATION, generation.stopReason == StopReason.COMPLETED)
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
                metrics?.recordToolCall(action.name, action.arguments, executed = false)
                sessions.observe(outcome.observation)
                return null
            }

            is ValidationOutcome.Ok -> outcome.tool
        }

        // Counted the moment the call is well-formed, before any gate decides
        // its fate. Counting only executed calls would make "the model tried
        // fourteen times" invisible, which is the failure this metric exists
        // to catch.
        //
        // (2) Loop detection before the confirmation gate: a user is never asked
        // to approve the same action twice.
        //
        // The policy is consulted HERE, with the arguments, and not earlier:
        // evaluating before validation would have the policy reason about calls
        // that were never going to run, and evaluating after the loop detector
        // would let a repeated call burn the user's confirmation dialog.
        val decision = riskPolicy.evaluate(tool.definition, action.arguments)
        metrics?.recordToolCall(
            action.name,
            action.arguments,
            executed = decision.outcome == PolicyOutcome.EXECUTE,
        )
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

            LoopDetector.Verdict.ALLOW -> when (decision.outcome) {
                // WHY a non-confirmation outcome gets a null session: a session
                // is a capability to run something after a human says so. For a
                // call the policy already allows there is no human in the loop
                // and nothing to authorise, and handing out a session anyway
                // would mean `confirmAndResume` could be talked into approving a
                // call the user was never shown.
                PolicyOutcome.EXECUTE ->
                    execute(PendingCall(action.name, tool, action.arguments, step, session = null))

                PolicyOutcome.REQUIRE_CONFIRMATION -> {
                    // `park` returns null for a decision that is not a gate. That
                    // is unreachable here — the outcome IS a gate — so the null
                    // is a wiring bug and failing closed is the right direction:
                    // a decision that says "ask a human" must never become "run
                    // it anyway", because that is the direction that loses data.
                    val session = ConfirmationSession.park(decision)
                        ?: error("REQUIRE_CONFIRMATION produced no session for ${action.name}")
                    pending = PendingCall(
                        name = action.name,
                        tool = tool,
                        args = action.arguments,
                        step = step,
                        session = session,
                    )
                    // NO trace entry here on purpose. A TOOL_CALL entry means
                    // "this call was dispatched", and a parked call was not
                    // dispatched. The gate is already observable — it is the
                    // AwaitingConfirmation this returns, and the session carries
                    // the justification the dialog renders.
                    AgentResult.AwaitingConfirmation(
                        action.name, tool, session.proposedArguments, trace.toList(),
                    )
                }

                PolicyOutcome.BLOCK -> refuse(action.name, decision)

                // WHY this executes instead of refusing: the tool, not the
                // runtime, owns the permission-denied answer. Every Android tool
                // in this repo already checks `ToolContext.permissionGranted`
                // and returns `ToolError.PermissionDenied` with a sentence
                // written for the model, which is the contract
                // `docs/architecture.md` §18 pins. The policy's job is to
                // decide the permission STATE; refusing here would replace a
                // tool-specific explanation with a generic one and lose the
                // name of the permission the user has to grant.
                PolicyOutcome.REQUIRE_PERMISSION ->
                    // NO extra trace entry here: `execute` emits the one TOOL_CALL
                    // entry, and two entries for one dispatch make the trace
                    // claim the tool ran twice. The permission decision is
                    // visible in the tool's own observation, which is where the
                    // model reads it from.
                    execute(
                        PendingCall(
                            action.name, tool, action.arguments, step,
                            session = null,
                            permissionGranted = false,
                        ),
                    )
            }
        }
    }

    /**
     * Refuses a call and tells the model why, in a way that invites a different
     * action rather than a retry of the same one.
     *
     * Only [PolicyOutcome.BLOCK] reaches this. The justification is the policy's
     * own sentence rather than a generic refusal, because "your phone cannot do
     * that" and "sending 500 messages at once will not be confirmed" call for
     * different next moves, and a model told the wrong one retries.
     */
    private fun refuse(name: String, decision: Decision): AgentResult? {
        trace += StepTrace(
            step, StepTrace.Kind.TOOL_CALL,
            "refused $name: ${decision.rule} — ${decision.justification}",
            success = false,
        )
        sessions.observe("$name did not run. ${decision.justification} Do something else, or answer without it.")
        return null
    }

    /** Runs one tool. Returns non-null when the run must stop. */
    private suspend fun execute(call: PendingCall): AgentResult? {
        if (cancelled) return AgentResult.Cancelled

        val context = ToolContext(
            userConfirmed = call.userConfirmed,
            // Carried from the policy's decision rather than left at its `true`
            // default. Before this was wired, nothing in the loop could ever set
            // it false, so every Android tool's permission-denied branch was
            // dead code reachable only from a unit test that constructed the
            // context by hand.
            permissionGranted = call.permissionGranted,
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
        // Charge the call to the task's blast radius. This happens on the
        // EXECUTE path only, and a denied call never reaches it, which is what
        // stops a model from starving itself by attempting what it may not do.
        // A confirmed call is charged by `recordConfirmed` at approval time.
        if (!call.userConfirmed) riskPolicy.recordExecuted(call.tool.definition.risk)
        trace += StepTrace(
            call.step, StepTrace.Kind.TOOL_CALL, call.name + compactArgs(call.args),
            durationMs, result.success,
        )
        trace += StepTrace(call.step, StepTrace.Kind.OBSERVATION, observation, success = result.success)
        metrics?.endPhase(StepTrace.Kind.TOOL_CALL, result.success)

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
            // Constrained generation, and this is the line that was the P0.
            //
            // WHY IT CANNOT BE NULL ANY MORE: a 1.1B base model asked to "reply
            // with JSON" will not reply with JSON. It continues the prompt instead,
            // so every run came back Malformed and the agent could never call a
            // tool no matter how many were wired up. GBNF makes the only
            // token-continuations valid be ones that parse as an action.
            //
            // WHY IT IS SAFE: an empty tool list yields an empty grammar, and the
            // JNI sampler chain treats an empty grammar string as "no constraint"
            // rather than a sampler that accepts nothing. A plain question with
            // nothing selected therefore still answers in prose.
            grammar = GrammarBuilder.forActions(visible.map { it.definition }),
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
     * Folds the window when it outgrows the model's context.
     *
     * WHY the trigger is a fraction of the real window rather than the budget:
     * the backend knows its own context length, and a hard-coded token ceiling
     * is wrong for every model that is not the one it was written for.
     */
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
     *
     * WHY metrics are frozen HERE rather than at each `return`: a run ends at
     * six different places and the two most diagnosable outcomes — Cancelled
     * and StepLimitReached — are the easiest to forget. Finishing in one place
     * is the only way a failure is measured at all.
     */
    private suspend fun measured(body: suspend () -> AgentResult): AgentResult {
        val result = guarded { body() }
        lastRunMetrics = metrics?.finish(result is AgentResult.Success)
        return result
    }

    private suspend fun guarded(body: suspend () -> AgentResult): AgentResult = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Stop("internal error: ${t::class.java.simpleName}: ${t.message}", trace.toList())
    }

    private companion object {
        const val COMPACT_AT = 0.65
        const val TRACE_DETAIL_CHARS = 512
        const val LINE_CHARS = 200
        const val TRUNCATOR_MIN_SAFE_BUDGET = 64
    }
}
