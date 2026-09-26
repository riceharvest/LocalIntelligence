package dev.localintelligence.core.agent

import dev.localintelligence.core.context.CompactedState
import dev.localintelligence.core.context.ContextBuilder
import dev.localintelligence.core.context.ContextCompactor
import dev.localintelligence.core.context.SUMMARY_PREFIX
import dev.localintelligence.core.execution.NoProgressWatchdog
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GrammarBuilder
import dev.localintelligence.core.model.StreamingModelBackend
import dev.localintelligence.core.agent.AgentResult.Stop
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.metrics.MemoryProbe
import dev.localintelligence.core.metrics.RunMetrics
import dev.localintelligence.core.metrics.RunRecorder
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.model.token.BudgetAction
import dev.localintelligence.core.model.token.ContextBudget
import dev.localintelligence.core.model.token.ContextState
import dev.localintelligence.core.model.token.ProposedStep
import dev.localintelligence.core.model.token.StepEnforcer
import dev.localintelligence.core.model.token.StepPlan
import dev.localintelligence.core.model.token.UsageBucket
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
import dev.localintelligence.core.compaction.RetainedHistory

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
 *  8. A step is priced before its tool runs ([StepEnforcer]), and the
 *     adjustments that pricing asks for are APPLIED to the live window, not
 *     logged. A step that still does not fit afterwards is refused rather than
 *     run and then discovered.
 *  9. Compaction is structured. When the trigger fires, the history is folded
 *     into a [CompactedState] — task, progress, facts, actions, failures,
 *     remaining work — and the next prompt is built from that state, so the
 *     model keeps what matters instead of a truncated transcript.
 */
/**
 * How [NoProgressWatchdog]'s terminate sentence begins.
 *
 * WHY A CONSTANT AND NOT A SUBSTRING TEST SCATTERED THROUGH THE LOOP: the loop
 * has to tell "tell the model to try something else" from "end the run", and the
 * watchdog returns both as a String. Matching on prose breaks the moment that
 * sentence is reworded. It is still fragile, but it is fragile in one place
 * beside the only consumer instead of in every branch that handles a stall.
 */
private const val TERMINATE_MARKER = "You have made no progress"

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
     * Invoked after any step that changed the message window.
     *
     * WHY THIS EXISTS: a durable store cannot be written only at run boundaries
     * and still be correct. `foldWindow` and `dropOldest*` remove messages MID
     * -run, and once removed they are unrecoverable — no count and no identity
     * can reconstruct a message that no longer exists. With persistence at the
     * end of a run only, a long run that compacted would permanently lose every
     * message the fold dropped, silently.
     *
     * Invoked BEFORE each trim, while the window is still intact. This ordering
     * is the entire mechanism and is not interchangeable: a checkpoint after
     * the trim finds the dropped messages already gone, and simulation showed
     * it losing 3 of 18 while the pre-trim version stored all 18 cleanly.
     *
     * Every trim site calls this first — `foldWindow` and both
     * `dropOldest*` — because a message that has left the window cannot be
     * recovered by any later write.
     *
     * NOT suspend on purpose. Making it suspend cascades through
     * applyAdjustments -> dropOldest* -> foldWindow, because the budget
     * adjustment path is not itself suspend, and a persistence callback should
     * not dictate the shape of the whole loop. The caller owns its own scope,
     * so the write happens off the loop's critical path and a slow database
     * cannot stall generation.
     *
     * `null` (the default) costs one null check per step.
     */
    private val onWindowChanged: (() -> Unit)? = null,
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
     * Catches the stall [loopDetector] cannot see.
     *
     * WHY BOTH: loopDetector compares what the model DID, so it catches
     * device.battery four times in a row. It does not catch four DIFFERENT calls
     * that all return the same text - reading the clock, listing an empty
     * contacts list, checking battery again - which is how a 1.1B model burns a
     * step budget without ever converging. The fingerprint is the observation,
     * not the tool name, precisely so that case registers.
     */
    // WHY stallTimeoutMs IS NULL HERE: the watchdog's time rule measures wall
    // clock between two observations, and between them sits a model generation.
    // Its 60s default is calibrated for a tool call, not for the generation that
    // precedes the next one. On a phone-sized CPU a small model can spend longer
    // than that inside a single decode, so the time rule would fire on a
    // perfectly healthy run and kill it with "no progress" while the model was
    // mid-answer. The counting rule is the one that detects an actual loop: it
    // compares observations and is immune to how slow the hardware is.
    private val noProgress: NoProgressWatchdog =
        NoProgressWatchdog(stallTimeoutMs = null),
    /**
     * Called with each decoded token, on the decoding thread.
     *
     * WHY THIS IS NULL BY DEFAULT: a caller that does not render tokens should
     * not pay for the callback, and every existing construction site keeps
     * working unchanged. It is a parameter rather than a global because the loop
     * is single-use - a sink is a property of one run, not of the process.
     *
     * WHY IT MATTERS: on a phone CPU a small model takes tens of seconds to
     * answer. generate() returns one blob at the end, so the user watched a
     * blank composer for the whole run and then saw text appear, which is
     * indistinguishable from a hung app. Tokens arriving as they decode are the
     * difference between "slow" and "broken".
     */
    private val onToken: ((String) -> Unit)? = null,
    /**
     * Optional run metrics. WHY optional rather than required: metrics are
     * observability, not correctness, and a caller that has nowhere to write
     * them must not be forced to allocate a recorder to get a working agent.
     * Set this and the loop reports tokens, latencies and duplicate calls for
     * every run without the loop learning what any of those mean.
     */
    private val metrics: RunRecorder? = null,
    /**
     * Structured compaction. WHY a collaborator and not a call into
     * [ContextCompactor]'s statics: the compactor is the seam a model-based
     * summariser plugs into, and the loop has to hold the instance that seam
     * configures — a compactor built inside [compactIfNeeded] could never be
     * swapped without editing this file.
     *
     * Defaulted for the same reason as [riskPolicy]: every existing caller
     * builds a controller without naming it, and a controller that could be
     * built ungated by omission is a controller that eventually will be.
     */
    private val compactor: ContextCompactor = ContextCompactor(
        workingLimit = config.workingTokenLimit,
        triggerFraction = COMPACT_AT,
    ),
    /**
     * Optional per-step budget gate. WHY NULLABLE AND NOT A DEFAULT INSTANCE:
     * the ceiling a step is checked against is derived from the BACKEND's real
     * context length (`ModelCapabilities.contextLength`), which is not known
     * until a model is loaded. A defaulted instance would have to hard-code a
     * window and be wrong for every model it was not written for, so the loop
     * builds one per call from the loaded model instead. Non-null means the
     * caller supplied a fixed budget and it is used verbatim — the seam the
     * eval harness and a device-specific ceiling both need.
     */
    private val stepEnforcer: StepEnforcer? = null,
    /**
     * Offers finished user turns to durable memory, or null to record nothing.
     *
     * WHY NULLABLE WHEN [riskPolicy] IS NOT: [riskPolicy]'s default is a real
     * instance because a controller built without it would run UNGATED, and
     * running ungated is a safety failure. Not recording a turn is a missing
     * feature rather than a missing safety gate.
     *
     * THE APP MUST PASS THIS. `:core` cannot reach the durable store on its own
     * — the delegate lives in `:android` — so "the app forgot to pass a
     * recorder" is a real and silent failure mode: the loop can read a memory it
     * has no way to form. The write itself is bounded by `MemoryWritePolicy`, so
     * a wired recorder cannot turn every turn into a row. See [TurnRecorder] for
     * the seam and `AppContainer.newController` for the one place that passes it.
     */
    private val turnRecorder: TurnRecorder? = null,
    /**
     * Optional memory instrumentation. WHY NULLABLE AND NOT A DEFAULT NO-OP:
     * [dev.localintelligence.core.metrics.NoMemoryProbe] would also be
     * zero-cost, but a nullable field lets the loop short-circuit at the call
     * site — one reference comparison — instead of a virtual dispatch into a
     * method that does nothing. On a path that runs once per step, on a phone,
     * the difference between "not called" and "called and returned Unit" is
     * worth having, and the default keeps every existing construction site
     * compiling unchanged.
     *
     * WHY THE LOOP OWNS IT RATHER THAN A GLOBAL: the readings are properties of
     * one run, and a process-scoped singleton would be the wrong lifetime for
     * them — the same reason [onToken] is a parameter and not a field on a
     * companion.
     *
     * A probe can never fail a run. Every call goes through [sampleMemory],
     * which swallows everything: instrumentation that can take down the run it
     * is measuring is instrumentation nobody will enable.
     */
    private val memoryProbe: MemoryProbe? = null,
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
     * The prompt the backend was last handed — in practice, the prompt for the
     * step in flight, because the loop builds it at the top of every
     * iteration, generates, and only then reaches a decision about a tool.
     *
     * WHY THE LOOP HOLDS IT: [StepEnforcer] prices a step against the context
     * as it stands, and the context that stands is the one the model was just
     * given — system prompt with its tools, working summary, memories, task,
     * kept turns. The loop could re-derive all of that, but a re-derivation is
     * a second opinion about what the builder would have emitted, and a
     * builder that changes would silently stop being priced. Holding the list
     * that was actually sent means the guard is enforced against exactly what
     * the model saw.
     *
     * A reference, never a copy: the messages are the session's own, so this
     * costs one list header.
     */
    private var lastPrompt: List<ChatMessage> = emptyList()

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

    /**
     * Publishes one memory reading, if a probe is attached.
     *
     * ## WHY EVERY FAILURE IS SWALLOWED
     *
     * This runs inside the agent loop. A probe that throws — an OOM while
     * building a sample, a platform API that faults on an unusual device — would
     * otherwise surface as a failed run, and the correct response to "the
     * memory reporter crashed" is not "your task failed". Every other optional
     * collaborator in this loop follows the same rule for the same reason
     * (`onWindowChanged`, `turnRecorder`, `metrics`), and this one is the most
     * dangerous of them to let throw because it is the most likely to be
     * forgotten about.
     *
     * ## WHY THE ARGS ARE COMPUTED INSIDE THE GUARD
     *
     * `sessions.exactRetainedChars()` walks the window. Computing it before
     * the null check would mean paying for it on every step of every run
     * forever, with no probe attached, to produce numbers nobody reads. Inside
     * the guard, an absent probe costs one reference comparison.
     */
    private fun sampleMemory(label: String) {
        val probe = memoryProbe ?: return
        try {
            probe.record(
                label,
                sessions.messages.size,
                sessions.exactRetainedChars(),
                // The prompt the model was last handed, not a re-render of it.
                // `lastPrompt` holds references to the session's own messages, so
                // this is a sum of lengths over that list — no copy, and no
                // second opinion about what the builder would have emitted.
                lastPrompt.sumOf { message ->
                    when (message) {
                        is ChatMessage.System -> message.text.length
                        is ChatMessage.User -> message.text.length
                        is ChatMessage.Assistant -> message.text.length
                        is ChatMessage.ToolObservation ->
                            message.toolName.length + message.observation.length
                    }
                },
            )
        } catch (_: Throwable) {
            // See the KDoc. Measuring a run must never be able to end it.
        }
    }

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
        // The session is NOT cleared here, and this is load-bearing.
        //
        // `sessions` is ONE long-lived conversation shared by every run (see
        // `AppContainer.session`), so clearing it would empty the conversation
        // before the new turn is appended, and the model would start from
        // nothing on every run.
        //
        // Per-run state that genuinely must reset lives above: trace, step,
        // malformedStreak, pending, lastPrompt, loopDetector, memoisedMemories.
        lastPrompt = emptyList()
        // The task changed, so the previous run's memory rows are for a
        // different question. See [memoriesForRun].
        memoisedMemories = null
        loopDetector.reset()
        // Blast radius is per TASK, not per process. Without this reset the
        // 200-action and 20-destructive-action caps would carry over between
        // conversations, so a user who legitimately deleted 19 things today
        // could not delete one more tomorrow without a reinstall.
        riskPolicy.resetTask()
        metrics?.beginStep(0)
        sessions.start(task)
        sampleMemory("run.start")
        val outcome = loop()
        // The session is long-lived now - one instance per app, shared by chat
        // and scheduled runs - so it has to be bounded here rather than left to
        // compaction, which only fires while a loop is running and only inspects
        // the list once per step. A day of scheduled runs with the app open
        // would otherwise grow the conversation without limit on a device whose
        // primary metric is RAM.
        //
        // Trimmed after the run, not during it: mid-run the messages being
        // written are the run's own, and dropping any of them would corrupt the
        // context the loop is still working from. Runs that end by throwing
        // leave the list untrimmed until the next one, which is deliberate -
        // a finally block here would trim a session whose run is still being
        // unwound and whose messages are still being read by the trace.
        RetainedHistory.bound(sessions.messages)
        // The identity maps are pruned HERE and not inside `bound`, because
        // they are `Session`'s private state and `bound` is a `:core` function
        // that is handed a bare list. Between this line and the next run's, the
        // session holds nothing it cannot name.
        sessions.releaseUnreachableIdentities()
        // Sampled after the bound and the prune, so the figure describes the
        // steady state a long-lived process settles into rather than the peak
        // of a run that has just finished. A release gate wants the steady
        // state; `run.end` is it.
        sampleMemory("run.end")
        outcome
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
            // Every projection of `visible` this step needs, derived ONCE here.
            //
            // WHY: the previous version called `visible.map { it.definition }`
            // twice inside `buildRequest` and `visible.map { it.definition.name }`
            // twice more — once for `allowedToolNames` and once more inside
            // `parse` on the same list — so every step allocated four
            // short-lived collections to describe the same handful of tools. At
            // `maxVisibleTools` = 6 those are tiny, which is exactly why this
            // survived review; but they are per-STEP, so an 8-step run churns 32
            // of them, upstream of a registry that has already allocated a fresh
            // redacting wrapper per tool. One derivation, shared by the builder,
            // the grammar, the allow-list and the parser.
            val names = visible.map { it.definition.name }
            val definitions = visible.map { it.definition }
            val nameSet = names.toHashSet()
            // Stream only when somebody is listening. generate() is the same
            // code path with a null sink, so this is not a second implementation
            // to keep in sync - it is the same call with the callback attached.
            val request = buildRequest(definitions, names)
            // StreamingModelBackend is a separate, OPTIONAL interface, so the
            // loop asks whether this backend has it rather than assuming. A
            // backend that cannot stream still works, it just returns one blob:
            // capability detection, not a cast that throws on a cold path.
            val streaming = model as? StreamingModelBackend
            val generation = if (onToken != null && streaming != null) {
                streaming.generateStreaming(request) { token ->
                    // A sink that throws must not kill the run: the model has
                    // already produced the token and the transcript still needs
                    // it. Swallowing here keeps a UI bug from turning into a
                    // failed generation the user cannot explain.
                    runCatching { onToken(token) }
                }
            } else {
                model.generate(request)
            }
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

            when (val parsed = parse(generation.text, nameSet)) {
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
                            // The one place a turn is offered to durable memory.
                            // See [rememberTurn] for why it is here and nowhere
                            // else.
                            rememberTurn()
                            return AgentResult.Success(action.text, trace.toList())
                        }

                        is AgentAction.CallTool ->
                            handleCall(action, visible, generation.text)?.let { return it }
                    }
                }
            }

            compactIfNeeded()
            // Sampled at the BOTTOM of the step, after every message this step
            // appended is in the window. Sampling at the top would report the
            // window as it was before the step's own tool result — which is the
            // number that makes a growing conversation look flat.
            sampleMemory("step.$step")
        }
        return AgentResult.StepLimitReached
    }

    /**
     * Returns non-null when the run must stop.
     *
     * @param reply the model's raw output for this step, carried down to
     *   [execute] so the step enforcer can price what the model actually
     *   produced. Empty for a resumed call — the generation that proposed it
     *   happened before the confirmation dialog, and re-pricing a string the
     *   loop no longer holds would be inventing a number.
     */
    private suspend fun handleCall(
        action: AgentAction.CallTool,
        visible: List<AgentTool>,
        reply: String,
    ): AgentResult? {
        // (1) Validation first: an unknown tool or a bad argument is a correction,
        // not a loop, and the model is told what was actually wrong.
        val outcome = validator.validate(action.name, action.arguments, visible)
        val tool = when (outcome) {
            is ValidationOutcome.Rejected -> {
                val detail = "rejected ${action.name}: ${outcome.observation}"
                trace += StepTrace(
                    step, StepTrace.Kind.TOOL_CALL, detail,
                    success = false,
                    toolName = action.name,
                )
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
                    execute(PendingCall(action.name, tool, action.arguments, step, session = null), reply)

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
                        reply,
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
            toolName = name,
        )
        sessions.observe("$name did not run. ${decision.justification} Do something else, or answer without it.")
        return null
    }

    /**
     * Runs one tool. Returns non-null when the run must stop.
     *
     * @param reply the model's raw output for this step, priced by
     *   [enforceStep] before the tool runs. Empty for a resumed call.
     */
    private suspend fun execute(call: PendingCall, reply: String = ""): AgentResult? {
        if (cancelled) return AgentResult.Cancelled

        // The moment the step's cost is committed. BEFORE `call.tool.execute`,
        // because the whole point of pricing a step is that a step which cannot
        // fit must not be discovered after the tool has already run — a
        // four-second prefill on an overflow is the failure this exists to
        // prevent. Returns non-null only when the step must not run at all.
        refuseUnaffordableStep(call, reply)?.let { return it }

        val context = ToolContext(
            userConfirmed = call.userConfirmed,
            // Carried from the policy's decision rather than left at its `true`
            // default, so a tool can actually see that a human said no and take
            // its permission-denied path.
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
        // Built once and used for both the detail and the `toolArgs` field.
        //
        // WHY: these were two calls to the same pure function on the same
        // argument, one line apart, producing two identical strings — the first
        // interpolated into the detail, the second passed as `toolArgs`. It is a
        // per-tool-call allocation of a string capped at six arguments of forty
        // characters each, and it doubled for no reason.
        val args = compactArgs(call.args)
        trace += StepTrace(
            call.step, StepTrace.Kind.TOOL_CALL, call.name + args,
            durationMs, result.success,
            toolName = call.name,
            toolArgs = args,
        )
        trace += StepTrace(call.step, StepTrace.Kind.OBSERVATION, observation, success = result.success)
        metrics?.endPhase(StepTrace.Kind.TOOL_CALL, result.success)

        // The real observation has arrived, so the estimate the step was
        // approved against is replaced with the thing itself. A tool that
        // returned far more than the budget predicted is exactly the case the
        // pre-tool check could not see, and it is cheaper to fold the window
        // here than to discover the overflow at the next prefill.
        repriceWithRealObservation(call, reply, observation)

        if (cancelled) return AgentResult.Cancelled
        if (loopDetector.hasStalled()) {
            return Stop("no progress: ${call.name} kept returning the same result", trace.toList())
        }
        // A nudge rather than a stop, because the model may still recover: the
        // wording is the whole point and lives in the watchdog so it is one
        // string rather than a sentence rebuilt at each call site. Only when the
        // watchdog says terminate does the run end, and then it says why.
        // nudgeIfStalling, NOT record: it calls record itself. Calling both
        // counted every observation twice, so a two-step stall looked like a
        // four-step one and the loop stopped while it was still making progress.
        //
        // It returns null on progress, a nudge on a stall, and the terminate
        // sentence when the run must end - so the string is the signal, and it
        // is the watchdog's own wording rather than one rebuilt here.
        val nudge = noProgress.nudgeIfStalling(observation)
        if (nudge != null) {
            if (nudge.startsWith(TERMINATE_MARKER)) {
                return Stop(
                    "no progress: ${call.name} kept returning the same result",
                    trace.toList(),
                )
            }
            sessions.observe(nudge)
        }
        return null
    }

    // ------------------------------------------------- the per-step budget gate

    /**
     * The enforcer for this step, or null when there is nothing worth
     * enforcing against.
     *
     * Built per call rather than held, because the ceiling it enforces is a
     * function of the LOADED model: two runs of the same controller with
     * different models have different windows, and a cached budget would price
     * the second run against the first one's. [StepEnforcer] holds a
     * [ContextBudget] and a counter and nothing else, so this is two small
     * objects per step, not state.
     *
     * Null in two cases, and both mean "the gate has nothing to offer":
     *
     *  - the window is too small for the gate to mean anything. Below
     *    [MIN_ENFORCEABLE_LIMIT] there is no room for a request plus a step,
     *    and enforcing would refuse every call on principle.
     *  - the request does not fit on its own, i.e. [ContextBudget.floorFor] is
     *    already over the prompt budget. Nothing the gate drops can change
     *    that, and the next prefill overflows whether or not this tool runs, so
     *    refusing every call would disable the agent rather than protect the
     *    window. A small-context model with a verbose tool list lands here, and
     *    it keeps behaving exactly as it did before this gate existed — which
     *    is the right thing for a guard whose remedy does not apply.
     *
     * An injected [stepEnforcer] is used verbatim: a caller that supplied a
     * budget meant it, and this function cannot inspect a budget it does not
     * own.
     */
    private fun enforcement(state: ContextState): StepEnforcer? {
        stepEnforcer?.let { return it }
        val limit = workingLimit(model.capabilities.contextLength)
        if (limit < MIN_ENFORCEABLE_LIMIT) return null
        val budget = ContextBudget(limitTokens = limit)
        if (budget.floorFor(state) > budget.promptTokens) return null
        return StepEnforcer(budget)
    }

    /**
     * Prices the step about to run, applies the plan, and stops the run if it
     * still does not fit.
     *
     * This is [StepEnforcer.cheapestReduction]'s stop condition, taken
     * literally: a step that cannot be made to fit is not retried, and it is
     * not run. The run ends with the arithmetic in the reason, because "the
     * agent gave up" is not a thing the user can act on and "this call needed
     * 4180 tokens and the window holds 3644" is.
     *
     * WHY THIS IS RARE RATHER THAN IMPOSSIBLE: the observation estimate alone
     * is [config.observationBudgetChars] (512 tokens by default), so the step
     * has to overflow the window on the reply and the arguments alone — a
     * runaway generation, or a 12KB argument blob. Those are the cases where
     * a stop is the right answer: the model is not out of ideas, it is out of
     * room, and no amount of further thinking changes that.
     */
    private fun refuseUnaffordableStep(call: PendingCall, reply: String): AgentResult? {
        val verdict = priceStep(call, reply, observationSample) ?: return null
        val trim = verdict.plan as? StepPlan.Trim ?: return null
        val detail = "${call.name} needs ${trim.projectedTokens} tokens and the window holds " +
            "${trim.limit}; ${verdict.dropped} message(s) were trimmed and nothing else is droppable"
        trace += StepTrace(
            call.step, StepTrace.Kind.TOOL_CALL, "refused ${call.name}: step does not fit — $detail",
            success = false,
            toolName = call.name,
        )
        // The phase ends here, and it ends as a failure: `recordToolCall` has
        // already counted this attempt as dispatched (the policy allowed it),
        // so without this the run's tool timings would show a phase that
        // succeeded and a trace that says it did not.
        metrics?.endPhase(StepTrace.Kind.TOOL_CALL, success = false)
        // No `sessions.observe` here: the run is over, so there is no model left
        // to read it. The reason goes in the Stop, which is what the UI shows.
        return Stop("context window too small for this step: $detail", trace.toList())
    }

    /**
     * Re-prices the step now that the observation is real.
     *
     * Adjustments are still applied — that is the point. A tool that returned
     * six times its estimate is exactly the case the pre-tool check could not
     * see, and folding the window here costs nothing next to discovering the
     * overflow at the next prefill. A step is never refused from here: the tool
     * already ran, so refusing now would report a real result to the user as a
     * failure and there is nothing left to protect.
     */
    private fun repriceWithRealObservation(call: PendingCall, reply: String, observation: String) {
        priceStep(call, reply, observation)
    }

    /**
     * Evaluate, apply, re-evaluate, until it fits or nothing is left to give.
     *
     * The re-evaluation is the load-bearing line, and it exists because
     * [StepEnforcer]'s own KDoc is honest about the limit of its guarantee: the
     * plan's drops are priced as whole buckets, and the state it re-plans
     * against does not contain the reply or the arguments. So "apply the list"
     * can leave the step over the ceiling, and the only way to know it fits is
     * to ask again with the window the drops actually produced.
     *
     * The loop terminates because every iteration either removes a message or
     * breaks; [MAX_BUDGET_ADJUSTMENTS] is a second bound on top of that, and it
     * is a QUALITY bound, not a correctness one — a step that needed more than
     * eight messages given up to fit is a run whose window is structurally too
     * big, and the answer to that is a stop, not a gutted conversation.
     */
    private fun priceStep(call: PendingCall, reply: String, observation: String): StepVerdict? {
        var state = contextOf(lastPrompt)
        val enforcer = enforcement(state) ?: return null

        val proposed = ProposedStep(
            replyCandidate = reply,
            toolArguments = compactArgs(call.args),
            observationCandidate = observation,
            // The summary this step WILL cost is unknown before the tool runs,
            // and it is not guessed. Once a compaction has happened the working
            // state is already priced — it is in the prompt the model was sent
            // — and the next compaction REPLACES it rather than adding to it,
            // so passing it a second time would double-charge the one case
            // where the loop actually knows the answer.
            summaryCandidate = "",
        )

        var plan = enforcer.evaluate(state, proposed)
        var applied = 0
        var labels = ""

        while (plan is StepPlan.Trim && applied < MAX_BUDGET_ADJUSTMENTS) {
            // The allowance is passed down, not just checked here: a plan can
            // legitimately list one drop per item in a bucket, and applying the
            // whole list in a single pass would gut the window past the cap
            // this loop is supposed to hold it to.
            val outcome = applyAdjustments(state, plan.adjustments, MAX_BUDGET_ADJUSTMENTS - applied)
            if (outcome.dropped == 0) break
            state = outcome.state
            applied += outcome.dropped
            labels = if (labels.isEmpty()) outcome.label else "$labels; ${outcome.label}"
            plan = enforcer.evaluate(state, proposed)
        }

        if (applied > 0) {
            trace += StepTrace(
                call.step, StepTrace.Kind.COMPACTION,
                "step budget for ${call.name}: dropped $applied message(s) " +
                    "($labels) — projected ${plan.projectedTokens}/${plan.limit} tokens",
            )
        }
        return StepVerdict(plan, applied)
    }

    /**
     * Applies [StepPlan.adjustments] to the live window and returns the state
     * that results, so the caller can re-price against what happened rather
     * than against what was planned.
     *
     * Each leg, and why it does what it does:
     *
     *  - **Observations** are removed from [sessions.messages] as well as from
     *    the priced state. They are the cheapest thing in a window to lose:
     *    the model already acted on them and the compaction trigger is about to
     *    fold the rest into labelled slots anyway.
     *  - **Turns** are removed from the session too, oldest first, and
     *    observations are excluded from this leg so the two legs cannot claim
     *    the same message. User turns are excluded outright: a user turn is an
     *    instruction, and instructions are the last thing this system gives up.
     *  - **Memories** are removed from the priced state only. The block is
     *    re-rendered from the store on every step, so what is actually given up
     *    is the room the builder spends on it — and the builder drops exactly
     *    that block when its own budget is exceeded, which is the only
     *    situation in which a plan asks for it.
     *  - **Tool definitions** cannot be applied. They are inside the system
     *    prompt, which is the one component [ContextBudget] never drops, and
     *    the step gate has no cheaper place to take them from. Treated as
     *    "nothing left" rather than silently ignored, so an inapplicable plan
     *    ends as a refusal instead of a lie.
     *
     * @param allowance how many messages this pass may give up, so one plan
     *   cannot drop the whole window in a single call.
     * @return the reduced state, how many messages were actually given up, and
     *   a label for the trace.
     */
    private fun applyAdjustments(
        state: ContextState,
        actions: List<BudgetAction>,
        allowance: Int,
    ): Adjustments {
        var working = state
        var dropped = 0
        val labels = StringBuilder()

        for (action in actions) {
            if (dropped >= allowance) break
            val given: Boolean
            val next: ContextState
            when (action.component) {
                UsageBucket.OBSERVATION -> {
                    given = dropOldestObservation(working)
                    next = working.copy(observations = working.observations.drop(1))
                }

                UsageBucket.TURN -> {
                    given = dropOldestTurn(working)
                    next = working.copy(recentTurns = working.recentTurns.drop(1))
                }

                UsageBucket.MEMORY -> {
                    // NOT a real drop, and must not pretend to be one.
                    //
                    // `buildRequest` re-runs `memory.search(task, config.memoryResults)`
                    // on every step and hands the full result to the builder, so
                    // shortening `working.memories` here changes the PRICED state
                    // and nothing else. The next step re-fetches the same rows and
                    // the prompt is exactly as long as before, while the gate has
                    // already recorded a drop it was credited with.
                    //
                    // That is falsifying in the permissive direction: reproduced
                    // at 2625 real tokens against a 2406 ceiling, where this leg
                    // supplied the one phantom drop that flipped the verdict to
                    // FITS. Reporting `given = false` makes the gate say DOES NOT
                    // FIT instead, which is the honest answer and is the same
                    // direction every other leg already fails in.
                    given = false
                    next = working
                }

                UsageBucket.SUMMARY, UsageBucket.TASK, UsageBucket.TOOL -> {
                    given = false
                    next = working
                }
            }
            if (!given) continue
            if (dropped > 0) labels.append("; ")
            labels.append(action.label)
            dropped++
            working = next
        }
        return Adjustments(working, dropped, labels.toString())
    }

    /**
     * Removes the oldest tool observation from the live window.
     *
     * Guarded on BOTH the priced state and the session: the state says how many
     * the plan was entitled to drop, and the session says whether there is
     * still one there to drop. Removing a message that is not in the window
     * would shrink nothing while the plan believed it had helped.
     */
    private fun dropOldestObservation(state: ContextState): Boolean {
        if (state.observations.isEmpty()) return false
        val index = sessions.messages.indexOfFirst { it is ChatMessage.ToolObservation }
        if (index < 0) return false
        // Checkpoint BEFORE the removal, for the same reason as foldWindow.
        onWindowChanged?.invoke()
        sessions.messages.removeAt(index)
        return true
    }

    /** Removes the oldest droppable turn. See [applyAdjustments] for the exclusions. */
    private fun dropOldestTurn(state: ContextState): Boolean {
        if (state.recentTurns.isEmpty()) return false
        val index = (1 until sessions.messages.size).firstOrNull { i ->
            val message = sessions.messages[i]
            message !is ChatMessage.ToolObservation && message !is ChatMessage.User
        } ?: return false
        // Checkpoint BEFORE the removal, for the same reason as foldWindow.
        onWindowChanged?.invoke()
        sessions.messages.removeAt(index)
        return true
    }

    /**
     * The prompt the model was last given, as a priced [ContextState].
     *
     * The mapping is the only place in the loop that decides which prompt slot
     * a message prices into, and it mirrors [ContextBudget]'s drop order: the
     * system prompt and the task are the request and are never droppable; the
     * working state has its own slot; observations are the cheapest thing to
     * lose; the memory block is re-searchable; everything else the builder
     * emitted is a turn.
     *
     * Three details that look like double counting and are not:
     *  - the builder re-emits the task as its own user turn, so the turn that
     *    matches [task] is skipped and priced in `task` instead;
     *  - the memory block is a `User` message that is not the task, so it goes
     *    to `memories` rather than to turns — [ContextBudget] already classes
     *    memories as re-searchable and droppable;
     *  - the working state arrives as a `System` message carrying
     *    [SUMMARY_PREFIX], and pricing it as part of the system prompt would
     *    make the one component the drop order never reaches look undroppable
     *    in fact as well as in principle.
     */
    private fun contextOf(prompt: List<ChatMessage>): ContextState {
        val system = StringBuilder()
        var summary = ""
        val turns = ArrayList<ChatMessage>(prompt.size)
        val observations = ArrayList<String>(OBSERVATION_LEDGER_SLOTS)
        val memories = ArrayList<String>(MEMORY_LEDGER_SLOTS)

        for (message in prompt) {
            when (message) {
                is ChatMessage.System ->
                    if (summary.isEmpty() && message.text.startsWith(SUMMARY_PREFIX)) {
                        summary = message.text
                    } else {
                        system.append(message.text).append('\n')
                    }

                // A PRIOR user turn is a TURN, not a memory.
                //
                // It used to go into `memories`, which made the budget gate lie
                // in the permissive direction: the MEMORY leg drops from the
                // priced fiction only, because a real memory block is re-rendered
                // from the store each step. A prior user turn is a live message
                // in the window, so dropping it from the fiction freed tokens
                // that were never actually freed. Reproduced: 2625 real tokens
                // against a 2406 ceiling reported FITS, because one phantom
                // MEMORY drop moved the projection under the limit.
                //
                // `recentTurns` is the bucket whose drop leg really does remove
                // from the live window (`dropOldestTurn`), so the fiction and
                // reality stay in agreement.
                is ChatMessage.User ->
                    if (message.text == task) Unit else turns += message

                is ChatMessage.Assistant -> turns += message

                is ChatMessage.ToolObservation -> observations += message.observation
            }
        }

        return ContextState(
            systemPrompt = system.toString(),
            task = task,
            workingSummary = summary,
            memories = memories,
            recentTurns = turns,
            observations = observations,
        )
    }

    /**
     * A representative observation at the full budget length, built once.
     *
     * WHY ORDINARY WORDS AND NOT A RUN OF ONE CHARACTER: the default estimator
     * is character-based (4 chars per token) but a caller may hand
     * [StepEnforcer] a real BPE counter, and 2048 copies of a single character
     * price at ~512 tokens under one and ~2048 under the other. The estimate
     * would then swing by 4x depending on which model happened to be loaded,
     * which is a budget check that moves for reasons unrelated to the budget.
     * Real words price within a few percent of the heuristic under both.
     *
     * Held for the controller's lifetime: at most [config.observationBudgetChars]
     * characters, and it is the only buffer the gate owns.
     */
    private val observationSample: String by lazy {
        buildString(config.observationBudgetChars.coerceAtLeast(0)) {
            while (length < config.observationBudgetChars) append(OBSERVATION_SAMPLE_WORDS)
        }
    }

    private data class StepVerdict(val plan: StepPlan, val dropped: Int)

    private data class Adjustments(val state: ContextState, val dropped: Int, val label: String)

    // ------------------------------------------------------------ collaborators

    private fun selectTools(): List<AgentTool> = try {
        toolSelector.select(task, sessions.currentKeywords(), tools.all(), config.maxVisibleTools)
    } catch (t: Throwable) {
        tools.all().take(config.maxVisibleTools)
    }

    /**
     * The memory rows for [task], fetched at most once per run.
     *
     * ## WHAT WAS WRONG, AND WHY IT IS NOT A CACHE
     *
     * `buildRequest` used to call `memory.search(task, config.memoryResults)`
     * on every step. Two problems, one of them much larger than the other.
     *
     * The large one is what `search` does with the result. The wired store is
     * `LexicalMemoryStore`, whose `search` deliberately ignores the delegate's
     * indexed query and calls `delegate.all(500)` instead — a full table scan,
     * documented at length as the price of retrieval that does not depend on a
     * second tokeniser staying in sync. So one step cost one SQLite scan of up
     * to 500 rows plus a `MemoryIndex.rank` over all of them, and a run of up
     * to [AgentConfig.maxSteps] steps paid it that many times to retrieve a
     * result keyed on a string that does not change for the whole run.
     *
     * The small one is that the same call was made from two places, so the
     * scan happened even on the step where the builder threw.
     *
     * Caching it for the run's lifetime is not an approximation, and this is
     * the part worth being careful about. The query is `task`, [task] is
     * assigned once in [run] and never reassigned, and the only writer of the
     * memory table during a run is [rememberTurn] — which runs on the
     * `AgentAction.Respond` branch, the branch that RETURNS from the loop
     * immediately afterwards. So between the first step and the last, the
     * table a search reads cannot change, and the result of step 1 is the
     * result of step N. A run that never reaches `Respond` never writes at
     * all, so the "invalidate on write" case does not exist to handle.
     *
     * ## WHY IT IS RESET IN [run] AND NOT ONLY CLEARED
     *
     * A controller is single-use, but "single-use" is a convention rather than
     * a type, and a reused controller serving a second task would otherwise
     * hand the model the FIRST task's memories. [run] assigns the memo, so a
     * second run with a different task cannot read the first one's.
     *
     * ## WHY A FAILED SEARCH IS ALSO MEMOISED
     *
     * The catch returns `emptyList()` and that is memoised too, deliberately.
     * A store that throws on step 1 and would have succeeded on step 5 is not a
     * case worth retrying eight times inside one run: if the database is not
     * readable now it will not be readable in the time it takes to decode
     * another token, and retrying turns a transient failure into eight
     * database round-trips on the critical path.
     */
    private suspend fun memoriesForRun(): List<Memory> {
        memoisedMemories?.let { return it }
        val fetched = try {
            memory.search(task, config.memoryResults)
        } catch (t: Throwable) {
            emptyList()
        }
        memoisedMemories = fetched
        return fetched
    }

    /** Set by [memoriesForRun], cleared by [run]. Null means "not fetched yet". */
    private var memoisedMemories: List<Memory>? = null

    private suspend fun buildRequest(
        definitions: List<ToolDefinition>,
        names: List<String>,
    ): GenerationRequest {
        val memories = memoriesForRun()
        // The working state goes in BEFORE the builder, because
        // [DefaultContextBuilder] recognises a summary by position and prefix
        // and reserves slot 2 for it. That is a contract the builder's own KDoc
        // assigns to "the agent loop", and this is the line that keeps it:
        // without it the compacted state is built, stored, counted by the
        // trigger — and never shown to the model.
        val history = withWorkingSummary(sessions.messages)
        // `definitions` and `names` are derived ONCE by the caller, per step,
        // and threaded down rather than recomputed here. See the note in the
        // loop: the previous shape projected the same tool list four times per
        // step and this is the same value with a quarter of the allocations.
        val prompt = try {
            contextBuilder.build(task, history, memories, definitions)
        } catch (t: Throwable) {
            // The fallback keeps the summary, because losing the working state
            // to a builder that threw is the one thing worse than an untrimmed
            // prompt.
            history
        }
        // Retained so the step gate prices the context the model is about to be
        // given rather than a reconstruction of it. See [lastPrompt].
        lastPrompt = prompt
        return GenerationRequest(
            messages = prompt,
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
            grammar = GrammarBuilder.forActions(definitions),
            allowedToolNames = names,
        )
    }

    /**
     * @param visibleNames the names the model was allowed to call, derived once
     *   per step in the loop. Passing the name SET rather than re-deriving it
     *   from the tool list is the point: the old shape called
     *   `visible.map { it.definition.name }.toSet()` inside here, on a list the
     *   caller had already walked twice.
     */
    private fun parse(
        raw: String,
        visibleNames: Set<String>,
    ): ActionParseResult = try {
        parser.parse(raw, visibleNames)
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
     * Folds the window when it is over the working limit, into a structured
     * [CompactedState] rather than a transcript summary.
     *
     * ## Why this is not a count, and not a summary turn
     *
     * The trigger counts TOKENS, but what it produces is structured state:
     * derived over the whole history, kept in [Session.workingSummary], and
     * handed to the builder through [withWorkingSummary] so it lands in the
     * slot the builder budgets first and keeps last.
     *
     * Folding into an ordinary summary turn instead would put the summary in
     * the slot that is dropped first when the prompt is over budget — exactly
     * when it is most needed.
     */
    private suspend fun compactIfNeeded() {
        val window = model.capabilities.contextLength
        val limit = workingLimit(window)
        // The cheap bound first. `sessions.tokens` concatenates the entire
        // window into a fresh StringBuilder and hands it to the backend's
        // counter — with a model resident that is a real `llama_tokenize` over
        // every character in the conversation — and the loop asks this question
        // once per step, on every step, whether or not the window is anywhere
        // near the limit.
        //
        // The bound is a hard one (see `Session.cannotReachTokenLimit`): a BPE
        // tokenizer never emits more tokens than there are characters, so a
        // window whose character count is below the limit cannot have reached
        // it in tokens either. When that holds, the exact count would have come
        // back at or under the limit and this function would have returned
        // without doing anything — so returning here is the same answer, not an
        // approximation of it. On the steps where compaction is genuinely near,
        // the bound says no and the exact count runs exactly as before.
        if (sessions.cannotReachTokenLimit(limit)) return
        val active = sessions.tokens(model)
        if (active <= limit) return

        // Nothing to fold, nothing to compact. A window that trips the trigger
        // holding two messages is a model whose context is smaller than its own
        // request, and folding would only duplicate the task.
        val keep = sessions.keepRecent.coerceAtLeast(1)
        if (sessions.messages.size <= keep + 1) return

        // Order matters and is not interchangeable: the slots are derived from
        // the messages [foldWindow] is about to delete, so compacting after the
        // fold would summarise a transcript that is already gone.
        val state: CompactedState =
            compactor.compact(sessions.messages, sessions.workingSummary, model)
        sessions.workingSummary = state
        foldWindow(keep)

        trace += StepTrace(
            step, StepTrace.Kind.COMPACTION,
            "compacted $active tokens into working state: ${state.progress.size} progress, " +
                "${state.actionsTaken.size} actions, ${state.failures.size} failures, " +
                "${state.knownFacts.size} facts, ${state.remainingWork.size} remaining " +
                "(limit $limit)",
        )
    }

    /**
     * Folds the window down to the task plus the newest [keep] messages.
     *
     * WHY THE LOOP DOES THIS AND NOT [Session.compact]: the session's own
     * compaction has to write its summary into the message list, because it
     * owns nothing else, and a summary in the list is a turn the builder is
     * free to drop. The loop owns the prompt, so the loop keeps the state
     * outside the list where the builder will lift it into its reserved slot.
     *
     * Nothing is lost that the [CompactedState] does not already carry: the
     * caller derived it from these very messages one line earlier, and it
     * carries the task, the model's turns, every tool outcome and every
     * failure forward across successive compactions.
     */
    private fun foldWindow(keep: Int) {
        val messages = sessions.messages
        val head = messages.first()
        val tail = messages.subList(messages.size - keep, messages.size).toList()
        // A MIDDLE trim, not a front trim: everything between the head and the
        // kept tail is removed, which is why durable persistence keys on message
        // identity (Session.writtenIds) rather than on a count of what is new.
        //
        // Checkpoint BEFORE the clear, not after. This ordering is the whole fix
        // and was verified by simulation: firing after the fold left 3 of 18
        // messages permanently unwritten, because they were already gone from the
        // window by the time anything looked. Firing first stores all 18 with no
        // duplicates. See onWindowChanged.
        onWindowChanged?.invoke()
        messages.clear()
        messages += head
        messages += tail
    }

    /**
     * The history handed to [ContextBuilder], with the working state in front of
     * it. Returns the session's own list untouched when there is no state, so
     * the common path allocates nothing and the builder's `subList` views stay
     * views.
     */
    private fun withWorkingSummary(messages: List<ChatMessage>): List<ChatMessage> {
        val state = sessions.workingSummary ?: return messages
        return listOf(compactor.summaryMessage(state)) + messages
    }

    /**
     * `min(modelContext * 0.65, workingTokenLimit)` — architecture §12.
     *
     * ONE copy of the formula, shared by the compaction trigger and the step
     * gate, because the two are two halves of one policy: compaction keeps the
     * window from growing past this, and the gate keeps a single step from
     * crossing it. Two copies would be two numbers that eventually disagree,
     * and the disagreement would look like a budget bug.
     *
     * The model's window stops us overflowing the KV cache; the working limit
     * stops us paying for a prefill §9 says we should never pay.
     */
    private fun workingLimit(window: Int): Int =
        if (window > 0) min((window * COMPACT_AT).toInt(), config.workingTokenLimit)
        else config.workingTokenLimit

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

    /**
     * Offers this run's user turn to durable memory. Called from exactly one
     * place: the `AgentAction.Respond` branch, which is where a run ENDS with
     * an answer.
     *
     * ## WHY HERE, AND NOT ON EVERY TURN
     *
     * Three points in this loop could host a write, and only this one is
     * defensible.
     *
     * **Not in [run]**, next to `sessions.start(task)`. That is the first thing
     * a run does, so it is the most convenient line in the file, and it is
     * wrong: it fires before the model has seen the turn, before any tool has
     * run, and — because it is the first line — it fires for runs that then
     * produce no answer at all. A user who typed "my wifi password is X" into
     * a run that immediately hit the step limit would have that row written
     * even though the run never concluded. What makes a statement worth keeping
     * is that the exchange around it finished, and at `start` it has not.
     *
     * **Not in [buildRequest]**, where memory is READ. That is tempting because
     * the read is right there and one method would then own both halves. It is
     * wrong in the direction that matters most: `buildRequest` runs once per
     * STEP, so a capped eight-step run would offer the same turn eight times,
     * and every one of those offers is a row. This is the "remember every turn"
     * failure `MemoryWritePolicy` was written to prevent, arriving through the
     * other door. `buildRequest` reads a *different* turn's memories too — it
     * queries with `task`, but a multi-step run's later steps query the same
     * task, so the read side already has the step-multiplicity property the
     * write side must not have.
     *
     * **Here**, on the `Respond` branch. `Respond` ends the task immediately
     * (invariant 3 above), so this is reached at most ONCE per run however many
     * steps it took: a one-step run and an eight-step run each produce exactly
     * one offer. It is reached only when the run produced an answer, so a
     * cancelled run, a `Stop`, an `AwaitingConfirmation` and a step-limit exit
     * all record nothing. And it is reached after `sessions.appendAssistant`, so
     * the session has the whole exchange by the time the decision to remember
     * is made.
     *
     * ## WHY THE USER'S TEXT AND NOT THE ANSWER
     *
     * `task` is the user's own words. `action.text` — the model's reply — is
     * not passed, and [TurnRecorder]'s KDoc says why at length: a model asked
     * for a password will supply one, and storing that would promote a
     * hallucination into a fact every later query re-asserts. The user saying
     * "my wifi password is X" is evidence; the model saying "your wifi password
     * is X" is a repeat of the question.
     *
     * ## WHY IT IS AWAITED RATHER THAN FIRED AND FORGOTTEN
     *
     * The write is a suspending database insert on the run's own coroutine, so
     * it completes before `AgentResult.Success` is returned and before the UI
     * is told the run finished. That costs one insert on a run that has already
     * spent seconds decoding, and it buys a guarantee a detached coroutine
     * cannot: the store is never written by a coroutine whose scope the service
     * tears down in `onDestroy`, which is the normal ending for a foreground
     * service. A memory written on a scope that is cancelled microseconds later
     * is a memory that exists or does not depending on a race the user cannot
     * see. It is also bounded: `MemoryWritePolicy` rejects a turn that is not a
     * first-person declarative fact, so the common case is one regex and no
     * write at all.
     *
     * ## WHY A FAILED WRITE IS NOT A FAILED RUN
     *
     * Everything but [CancellationException] is swallowed. A memory write is
     * bookkeeping on a conversation that has already succeeded, and the answer
     * is in the user's hands — trading it for a failed insert would be
     * discarding real work for a cosmetic step. `CancellationException` is
     * rethrown, because that is structured concurrency arriving from outside
     * and the standing project rule is that it stays cancellation.
     */
    private suspend fun rememberTurn() {
        val recorder = turnRecorder ?: return
        try {
            recorder.recordTurn(task)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // See above: the run has already produced its answer. The delegate
            // is a ResilientMemoryStore, which degrades to RAM rather than
            // throwing, so reaching this is not an expected path — but the cost
            // of being wrong here is a lost memory, and the cost of being wrong
            // the other way is a user who loses an answer.
        }
    }

    private companion object {
        const val COMPACT_AT = 0.65
        const val TRACE_DETAIL_CHARS = 512
        const val LINE_CHARS = 200
        const val TRUNCATOR_MIN_SAFE_BUDGET = 64

        /**
         * Most messages the step gate may give up in a single step.
         *
         * WHY EIGHT: the loop's whole quality argument for a window is recency
         * ([ContextBudget]'s drop order), so a gate willing to gut a
         * conversation is worse than one that admits the window is too small.
         * Eight is roughly the working target's worth of observations at the
         * default budget — past that the run is not trimming, it is forgetting.
         */
        const val MAX_BUDGET_ADJUSTMENTS = 8

        /**
         * Below this ceiling the step gate does not run.
         *
         * WHY: [ContextBudget] holds back [ContextBudget.DEFAULT_OUTPUT_RESERVE]
         * for the reply, so a small ceiling leaves a prompt budget too small to
         * hold a system prompt, a task and a step. A gate that refuses every
         * call for that reason is not protecting the window, it is disabling the
         * agent. Compaction still runs at the same number.
         */
        const val MIN_ENFORCEABLE_LIMIT = 1024

        /** Initial capacity of the observation ledger in [contextOf]. */
        const val OBSERVATION_LEDGER_SLOTS = 8

        /** Initial capacity of the memory ledger in [contextOf]. */
        const val MEMORY_LEDGER_SLOTS = 4

        /**
         * What the pre-tool observation estimate is made of.
         *
         * Ordinary prose so it prices the same under the default character
         * estimator and under a real BPE counter. See [observationSample].
         */
        const val OBSERVATION_SAMPLE_WORDS = "The device reported a result. "
    }
}
