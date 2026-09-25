// ===========================================================================
// BenchmarkRunner.kt
//
// THE HARNESS THAT MAKES THE PROJECT FALSIFIABLE
// ===============================================
//
// Everything else in this repo measures the harness against a fake. That proves
// the harness is internally consistent, which is necessary and not sufficient.
// The question this file exists to answer is the one nobody can currently
// answer: does a real GGUF model, driven through the real AgentController,
// actually complete the tasks?
//
// Three properties make the answer trustworthy:
//
//   1. IT REUSES THE SUITE. The tasks, the scorer and the tool fixtures come
//      from core/src/test/.../eval, unmodified. A second task suite would be a
//      second opinion nobody asked for, and the two would drift.
//
//   2. IT IS MODEL-AGNOSTIC. The runner knows only the ModelBackend interface.
//      A scripted backend and llama.cpp are the same code path, which is what
//      makes the fast JVM test meaningful evidence about the real run rather
//      than a test of a parallel implementation.
//
//   3. IT NEVER INVENTES A RESULT. A backend that throws mid-task produces a
//      FAILED task with the reason attached. A backend that is cancelled
//      produces a CANCELLED task. Neither produces a zero, and neither aborts
//      the suite — a harness that dies at task 3 of 50 has told you nothing
//      about tasks 4 through 50.
//
// The parser matters and is easy to get wrong. A real model emits the SHIPPING
// protocol (`<respond>...</respond>`, `<tool name="x">{...}</tool>`) because
// that is what GrammarBuilder constrains it to. The eval suite's own fake
// speaks a JSON dialect parsed by EvalActionParser. Running a real model
// through the fake's parser would score the model's English, not its tool
// calling, so the parser is injected and defaults to the production one.
// ===========================================================================

package dev.localintelligence.core.eval.harness

import dev.localintelligence.core.agent.ActionParser
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.InMemoryMemoryStore
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.agent.ValidationOutcome
import dev.localintelligence.core.context.DefaultContextBuilder
import dev.localintelligence.core.eval.AgentRunner
import dev.localintelligence.core.eval.CompletedRun
import dev.localintelligence.core.eval.EvalScorer
import dev.localintelligence.core.eval.EvalTask
import dev.localintelligence.core.eval.ExecutedCall
import dev.localintelligence.core.eval.GateOutcome
import dev.localintelligence.core.eval.MockToolRegistry
import dev.localintelligence.core.eval.RejectedCall
import dev.localintelligence.core.eval.SuiteReport
import dev.localintelligence.core.eval.TaskOutcome
import dev.localintelligence.core.eval.ToolCallValidatorGate
import dev.localintelligence.core.eval.buildTaskTools
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.ToolArgs
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/** Why a task's run ended, beyond the pass/fail the scorer computes. */
enum class TaskRunStatus {
    /** The loop produced a final answer. The scorer decides whether it is right. */
    COMPLETED,

    /** The loop parked on a confirmation gate. Expected for risky tools. */
    AWAITING_CONFIRMATION,

    /** The model hit [AgentConfig.maxSteps] without answering. */
    STEP_LIMIT,

    /** `cancel()` was called, or the backend reported StopReason.CANCELLED. */
    CANCELLED,

    /** The loop stopped with a reason. Often "model failed: ...". */
    STOPPED,

    /** The backend threw. The task FAILS; the suite continues. See [TaskResult]. */
    BACKEND_ERROR,
}

/**
 * One task's measured outcome.
 *
 * [backendError] is non-null exactly when the run could not be completed for a
 * reason outside the model's behaviour. It is kept separate from [failures] so
 * that "the model got this wrong" and "the harness could not run it" never
 * share a column: the first is a finding about the model, the second is a
 * broken measurement, and averaging them together produces a number that means
 * nothing.
 */
data class TaskResult(
    val taskId: String,
    val category: String,
    val status: TaskRunStatus,
    val passed: Boolean,
    val steps: Int,
    val toolCalls: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val wallTimeMs: Long,
    val stopReason: String?,
    val failures: List<String>,
    /** Non-null when the backend or the loop threw. The task fails because of this. */
    val backendError: String? = null,
) {
    /**
     * Task success per 1k generated tokens, the project's secondary metric.
     *
     * A task that never generated anything scores 0.0 rather than infinity: a
     * model that fails without emitting a token is not infinitely efficient, and
     * reporting Infinity in a JSON field that downstream tooling will parse as
     * a double is a way to break a comparison pipeline.
     */
    val successPerThousandTokens: Double
        get() = if (outputTokens <= 0) 0.0 else (if (passed) 1000.0 else 0.0) / outputTokens
}

/**
 * The whole run: one model's answer to the suite.
 *
 * [backendId] and [modelId] are recorded because a benchmark result without a
 * subject is an anecdote.
 */
data class BenchmarkRun(
    val backendId: String,
    val modelId: String,
    val results: List<TaskResult>,
    val wallTimeMs: Long,
    /** Epoch millis, for correlating two runs. Never used in comparisons. */
    val startedAtEpochMs: Long,
    /**
     * How many generation requests carried an action grammar.
     *
     * Reported because it is currently ZERO, and that is a finding rather than
     * a detail: `AgentController.buildRequest` hardcodes `grammar = null`, so
     * the shipping loop never constrains a real model's output to the action
     * protocol. A 1-4B model is therefore asked to produce
     * `<tool name="...">{...}</tool>` from the system prompt alone. Every
     * malformed-output retry in such a run is evidence about the missing
     * grammar, not about the model's tool calling. See docs/evals.md.
     */
    val requestsWithGrammar: Int = 0,
) {
    val total: Int get() = results.size
    val passed: Int get() = results.count { it.passed }

    /** Primary metric: fraction of tasks the model completed correctly. */
    val successRate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total

    val totalInputTokens: Int get() = results.sumOf { it.inputTokens }
    val totalOutputTokens: Int get() = results.sumOf { it.outputTokens }

    /**
     * Secondary metric: successes per 1k generated tokens.
     *
     * This is the number that makes a 1B and a 4B comparable, and it is the one
     * that reflects what a phone actually pays for. A model that solves three
     * tasks in 900 tokens beats one that solves the same three in 3000, and on a
     * device constrained by decode latency that difference is the product.
     */
    val successPerThousandTokens: Double
        get() = if (totalOutputTokens <= 0) 0.0 else passed * 1000.0 / totalOutputTokens

    /**
     * Tasks that could not be MEASURED, as opposed to tasks the model failed.
     *
     * Reported separately and loudly. A run where 3 of 50 tasks errored out is
     * not a 47/50 — it is an incomplete run, and reading it as the former is
     * exactly the dishonesty this harness exists to prevent.
     */
    val erroredTasks: List<TaskResult> get() = results.filter { it.backendError != null }
}

/**
 * A backend whose behaviour changes per task.
 *
 * A real model does not need this: one model is resident and it answers every
 * task. A scripted backend does, because each task has its OWN oracle
 * trajectory and a shared script queue would play task 1's script for task 2 —
 * producing a run that looks measured and is not.
 *
 * The runner calls [beginTask] before each task and does nothing else with it,
 * so a backend that does not implement this is unaffected.
 */
interface TaskScopedBackend {
    fun beginTask(task: EvalTask)
}

/**
 * Drives the real [AgentController] over a suite with an INJECTED backend.
 *
 * This is the model-agnostic core. It knows nothing about GGUF, nothing about
 * llama.cpp, and nothing about fakes: it takes a [ModelBackend] and the same
 * [EvalTask]s the deterministic suite uses, and produces a [BenchmarkRun].
 *
 * The construction mirrors `RealAgentControllerRunner` exactly — same registry,
 * same gate, same real context builder, same scorer. That is deliberate. A
 * benchmark that wires the loop differently from the suite measures the wiring
 * instead of the model, and the two would disagree for reasons no one could
 * find.
 *
 * @param parser the wire protocol the backend speaks. Defaults to the
 *   production [dev.localintelligence.core.agent.ActionParserImpl]; tests and
 *   fake-backend runs pass the suite's own `EvalActionParser` instead.
 */
class BenchmarkRunner(
    private val backend: ModelBackend,
    private val parser: ActionParser,
    private val config: AgentConfig = AgentConfig(),
) {

    /**
     * Runs every task in [tasks], in order, against the already-loaded backend.
     *
     * Sequential and single-model on purpose: a phone hosts one model resident,
     * and a parallel suite would interleave decodes into one llama.cpp context,
     * which it does not allow. Concurrency here would produce numbers that do
     * not correspond to any real device.
     *
     * A task that throws is recorded as [TaskRunStatus.BACKEND_ERROR] and the
     * suite continues. The backend is NOT reloaded between tasks: a mid-suite
     * crash that a reload papers over is a crash the benchmark must report.
     */
    fun run(
        tasks: List<EvalTask>,
        modelId: String = backend.id,
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ): BenchmarkRun {
        val scorer = EvalScorer()
        val startedAt = nowEpochMs()
        val suiteStart = System.nanoTime()
        val results = mutableListOf<TaskResult>()

        // One recorder for the whole suite. The per-task runs reset its
        // cursors; the suite-level grammar count accumulates across tasks.
        val recorder = RecordingBackend(backend)
        val scoped = backend as? TaskScopedBackend

        for (task in tasks) {
            recorder.beginTask()
            // Per-task script selection, for the backends that need it. A real
            // model ignores this entirely.
            scoped?.beginTask(task)
            results += runOne(task, scorer, recorder)
        }

        return BenchmarkRun(
            backendId = backend.id,
            modelId = modelId,
            results = results,
            wallTimeMs = (System.nanoTime() - suiteStart) / 1_000_000,
            startedAtEpochMs = startedAt,
            requestsWithGrammar = recorder.requestsWithGrammar,
        )
    }

    /** One task, end to end. Never throws: every failure becomes a result. */
    private fun runOne(task: EvalTask, scorer: EvalScorer, recorder: RecordingBackend): TaskResult =
        try {
            val completed = runBlocking { driveLoop(task, recorder) }
            val outcome: TaskOutcome = scorer.score(task, completed)
            TaskResult(
                taskId = task.id,
                category = task.category.slug,
                status = statusOf(completed.stopReason),
                passed = outcome.passed,
                steps = outcome.metrics.steps,
                toolCalls = outcome.metrics.toolCalls,
                inputTokens = outcome.metrics.inputTokens,
                outputTokens = outcome.metrics.outputTokens,
                wallTimeMs = outcome.metrics.totalMs,
                stopReason = completed.stopReason,
                failures = outcome.failures,
            )
        } catch (t: Throwable) {
            // A backend that throws mid-run is a BROKEN MEASUREMENT, not a model
            // failure. It is reported as an error, the task counts as failed, and
            // the suite carries on so one bad task cannot hide the other 49.
            TaskResult(
                taskId = task.id,
                category = task.category.slug,
                status = TaskRunStatus.BACKEND_ERROR,
                passed = false,
                steps = 0,
                toolCalls = 0,
                // Tokens generated before the throw are real and are kept: a
                // crash on step 3 of a 5-step task still cost those tokens, and
                // dropping them would flatter the model's efficiency.
                inputTokens = recorder.taskInputTokens,
                outputTokens = recorder.taskOutputTokens,
                wallTimeMs = 0,
                stopReason = "harness/backend threw",
                failures = listOf("the run did not complete: ${t::class.simpleName}: ${t.message}"),
                backendError = "${t::class.java.name}: ${t.message}",
            )
        }

    /**
     * The real loop, wired exactly as the suite wires it.
     *
     * The scripted tool fixtures come from `buildTaskTools`, so a task's denied
     * permission, thrown tool and truncated observation are identical whether
     * the model behind the loop is a fake or a 3B on a phone. That identity is
     * the whole basis for comparing the two runs.
     */
    private suspend fun driveLoop(task: EvalTask, recorder: RecordingBackend): CompletedRun {
        val registry = MockToolRegistry(buildTaskTools(task))
        val session = Session(id = 1L)
        session.start(task.utterance)

        val controller = AgentController(
            model = recorder,
            parser = parser,
            tools = registry,
            toolSelector = registry.selectorFor(task.visibleTools, config.maxVisibleTools),
            validator = registry.gate().toLoopGate(),
            loopDetector = LoopDetector(),
            contextBuilder = DefaultContextBuilder(workingLimit = config.workingTokenLimit),
            memory = InMemoryMemoryStore(),
            sessions = session,
            config = config,
        )

        val startedAt = System.nanoTime()
        val result = controller.run(task.utterance)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        return result.toCompletedRun(
            registry = registry,
            backend = backend,
            task = task,
            elapsedMs = elapsedMs,
            inputTokens = recorder.taskInputTokens,
            outputTokens = recorder.taskOutputTokens,
            prompts = recorder.taskPrompts,
        )
    }
}

/**
 * Wraps a backend and records what the loop actually asked it for.
 *
 * This exists because the loop's `StepTrace` stores generation text TRUNCATED
 * to 512 characters. Counting tokens off that trace would undercount every long
 * output, and the undercount would land directly on the project's secondary
 * metric (success per 1k generated tokens) — a metric that would then reward a
 * model for generating less than it did. So the recorder keeps the full prompts
 * and the backend's OWN reported `promptTokens`/`completionTokens`, which for
 * llama.cpp come from `llama_tokenize` and are exact.
 *
 * Delegation only: it adds no behaviour to the backend, so a run measured
 * through it is a run measured through the real backend.
 */
private class RecordingBackend(private val delegate: ModelBackend) : ModelBackend by delegate {

    private var taskInput = 0
    private var taskOutput = 0
    private val recorded = mutableListOf<String>()

    /** Suite-wide count of requests that carried a grammar. */
    var requestsWithGrammar: Int = 0
        private set

    val taskInputTokens: Int get() = taskInput
    val taskOutputTokens: Int get() = taskOutput
    val taskPrompts: List<String> get() = recorded.toList()

    fun beginTask() {
        taskInput = 0
        taskOutput = 0
        recorded.clear()
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val result = delegate.generate(request)
        taskInput += result.promptTokens
        taskOutput += result.completionTokens
        recorded.add(request.renderForRecording())
        if (request.grammar != null) requestsWithGrammar++
        return result
    }
}

/** Flattens a request to the text a backend would actually be handed. */
private fun GenerationRequest.renderForRecording(): String = buildString {
    appendLine("allowedTools=" + allowedToolNames.joinToString(","))
    appendLine("grammar=" + (grammar ?: "<none>"))
    messages.forEach { appendLine(it.flatText()) }
}

/**
 * Maps the harness's argument-validation vocabulary onto the loop's.
 *
 * Both exist because the harness and the loop were written in parallel against
 * a frozen contract; this is the only place the two meet. The loop's
 * ValidationOutcome is the real one, so the production behaviour is what runs.
 */
private fun ToolCallValidatorGate.toLoopGate() =
    dev.localintelligence.core.agent.ToolCallValidatorGate { name, args, visibleTools ->
        when (val outcome = validate(name, args, visibleTools)) {
            is GateOutcome.Ok -> ValidationOutcome.Ok(
                outcome.tool ?: visibleTools.first { it.definition.name == name },
            )
            is GateOutcome.Rejected -> ValidationOutcome.Rejected(outcome.observation)
        }
    }

/**
 * Folds a finished [AgentResult] plus the loop's trace into a [CompletedRun].
 *
 * The trace is the loop's own record, so "what the model was asked to do"
 * comes from the runtime rather than from a parallel bookkeeping path that
 * could disagree with it.
 *
 * Token accounting reads the backend's OWN reported counts when it has a model
 * resident (llama.cpp knows the real prompt/completion split) and falls back to
 * the backend's `countTokens` otherwise. A fabricated split is worse than an
 * approximate one, so the fallback is explicit about which it is.
 */
private fun AgentResult.toCompletedRun(
    registry: MockToolRegistry,
    backend: ModelBackend,
    task: EvalTask,
    elapsedMs: Long,
    inputTokens: Int,
    outputTokens: Int,
    prompts: List<String>,
): CompletedRun {
    val trace = when (this) {
        is AgentResult.Success -> trace
        is AgentResult.AwaitingConfirmation -> trace
        is AgentResult.Stop -> trace
        // These two carry no trace by the pinned wave-1 signature. An empty
        // history here is a KNOWN gap (raised in the wave-1 PR), and it means a
        // step-limited or cancelled run reports zero steps rather than lying
        // about how far it got.
        AgentResult.StepLimitReached -> emptyList()
        AgentResult.Cancelled -> emptyList()
    }

    val attempted = trace
        .filter { it.kind == StepTrace.Kind.TOOL_CALL }
        .map { ExecutedCall(it.toolName() ?: "?", ToolArgs(emptyMap())) }

    // A TOOL_CALL trace entry is appended for every execution regardless of
    // whether the result succeeded, so filtering on `success` would silently
    // drop every denied-permission and thrown-tool call — exactly the calls the
    // failure category exists to observe.
    val ranOrder = trace.filter { it.kind == StepTrace.Kind.TOOL_CALL }.map { it.toolName() ?: "?" }
    val argsByTool = registry.allCalls().groupBy { it.name }
    val actuallyRan = ranOrder.map { name ->
        ExecutedCall(name, argsByTool[name]?.firstOrNull()?.args ?: ToolArgs(emptyMap()))
    }
    val executedNames = actuallyRan.map { it.name }.toSet()

    val rejected = trace
        .filter { it.kind == StepTrace.Kind.TOOL_CALL && it.toolName() !in executedNames }
        .map { RejectedCall(it.toolName() ?: "?", ToolArgs(emptyMap()), it.detail) } +
        trace.filter { it.kind == StepTrace.Kind.MALFORMED }
            .map { RejectedCall("<malformed>", ToolArgs(emptyMap()), it.detail) }

    val steps = trace.count { it.kind == StepTrace.Kind.GENERATION }

    return CompletedRun(
        steps = steps,
        attemptedCalls = attempted,
        executedCalls = actuallyRan,
        invokedCalls = actuallyRan,
        rejectedCalls = rejected,
        prompts = prompts,
        finalAnswer = (this as? AgentResult.Success)?.text,
        stopReason = stopReasonText(),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalMs = elapsedMs,
        peakRamBytes = 0L,
        awaitingConfirmationFor = (this as? AgentResult.AwaitingConfirmation)?.toolName,
        failedTools = registry.allCalls().map { it.name }.distinct(),
        maxObservationChars = maxObservationChars(prompts),
    )
}

/**
 * The largest observation the model was shown, post-truncation.
 *
 * Diagnostic only. Read from the recorder's untruncated prompts, because the
 * loop's own trace stores generation text capped at 512 characters and would
 * under-report any large observation.
 */
private fun maxObservationChars(prompts: List<String>): Int =
    prompts.flatMap { OBSERVATION.findAll(it).map { m -> m.groupValues[1].length } }.maxOrNull() ?: 0

/** The stop reason as the scorer's failure messages will phrase it. */
private fun AgentResult.stopReasonText(): String = when (this) {
    is AgentResult.Success -> "responded"
    is AgentResult.AwaitingConfirmation -> "awaiting confirmation for $toolName"
    is AgentResult.Stop -> reason
    AgentResult.StepLimitReached -> "step limit reached"
    AgentResult.Cancelled -> "cancelled"
}

/** Maps a stop reason string onto the harness's status enum. */
private fun statusOf(stopReason: String?): TaskRunStatus = when {
    stopReason == null -> TaskRunStatus.STOPPED
    stopReason == "responded" -> TaskRunStatus.COMPLETED
    stopReason == "cancelled" -> TaskRunStatus.CANCELLED
    stopReason == "step limit reached" -> TaskRunStatus.STEP_LIMIT
    stopReason?.startsWith("awaiting confirmation") == true -> TaskRunStatus.AWAITING_CONFIRMATION
    else -> TaskRunStatus.STOPPED
}

/**
 * Recovers a tool name from a trace entry's human-readable detail.
 *
 * `StepTrace.detail` is text, so the name is parsed back out of it. Isolated
 * here so that a change to the loop's trace format breaks one function and one
 * test, rather than every metric in the harness.
 */
private fun StepTrace.toolName(): String? =
    TOOL_NAME.find(detail)?.groupValues?.get(1)

private val TOOL_NAME = Regex("\\b([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+)")
private val OBSERVATION = Regex("""<observation tool="[^"]*">([^<]*)<""")

/**
 * A backend for tests: returns the current task's script and counts tokens
 * honestly.
 *
 * Deliberately minimal and deliberately NOT `FakeModelBackend` from the eval
 * suite. The suite's fake is part of the system under test — it must not be
 * extended to serve as this harness's own test double, or a defect in it would
 * be invisible to the test that is supposed to catch it.
 *
 * Implements [TaskScopedBackend] so each task gets its own oracle trajectory.
 * A single shared queue would play task 1's script against task 2, which looks
 * like a measurement and is not one.
 *
 * Throws on demand via [throwOnCall] so the mid-run-crash path can be exercised
 * without a native library. Note that `AgentController` is contractually
 * incapable of throwing — its `guarded()` wrapper absorbs any throwable into an
 * `AgentResult.Stop` — so a thrown exception arrives here as a *stopped* task
 * rather than as [TaskRunStatus.BACKEND_ERROR]. Both are failures; they are
 * reported differently because they mean different things.
 */
class ScriptedBenchmarkBackend(
    private val script: List<String> = emptyList(),
    override val id: String = "scripted",
    override val capabilities: ModelCapabilities = ModelCapabilities(
        contextLength = 4096,
        supportsToolCalling = true,
        supportsGrammar = true,
        supportsVision = false,
        supportsKvCache = false,
    ),
    /** 1-based generation index that throws, or 0 to never throw. */
    private val throwOnCall: Int = 0,
    private val throwMessage: String = "simulated native failure",
) : ModelBackend, TaskScopedBackend {

    /** Task id -> its oracle trajectory. Populated by [beginTask]. */
    private val scriptsByTask = mutableMapOf<String, List<String>>()

    /** Registers a task's trajectory. Call before [BenchmarkRunner.run]. */
    fun withScript(task: EvalTask): ScriptedBenchmarkBackend = apply {
        scriptsByTask[task.id] = task.modelScript
    }

    /** Registers many at once. */
    fun withScripts(tasks: List<EvalTask>): ScriptedBenchmarkBackend = apply {
        tasks.forEach { scriptsByTask[it.id] = it.modelScript }
    }

    private var cursor = 0
    private var active = script
    private val cancelledFlag = AtomicBoolean(false)

    /**
     * Suite-wide generation counter.
     *
     * Deliberately NOT reset per task. [throwOnCall] describes a fault in the
     * backend, and a backend that aborts on its second generation has aborted
     * once for the whole run, not once per task. Counting per task would make
     * a single injected crash look like 50.
     */
    private var suiteCalls = 0

    /** Every prompt the loop built, in order, for the current task. */
    val prompts: MutableList<String> = mutableListOf<String>()

    /** The grammars attached to requests, in order. Empty when none were sent. */
    val grammars: MutableList<String?> = mutableListOf<String?>()

    override fun beginTask(task: EvalTask) {
        cursor = 0
        active = scriptsByTask[task.id] ?: script
        // The cancel flag is deliberately NOT cleared. Cancelling is an
        // operator action ("stop"), not a per-task input; having the next task
        // silently un-cancel it would mean a stop button that works for one
        // task. Only [load] — a fresh run — clears it.
        prompts.clear()
        grammars.clear()
    }

    override suspend fun load(model: ModelSpec) {
        cancelledFlag.set(false)
        suiteCalls = 0
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        if (cancelledFlag.get()) {
            return GenerationResult(text = "", stopReason = StopReason.CANCELLED)
        }
        suiteCalls++
        if (throwOnCall > 0 && suiteCalls == throwOnCall) {
            throw IllegalStateException(throwMessage)
        }
        prompts.add(request.renderForRecording())
        grammars.add(request.grammar)

        val raw = if (cursor < active.size) active[cursor++] else RESPOND_FALLBACK
        return GenerationResult(
            text = raw,
            promptTokens = countTokens(request.renderForRecording()),
            completionTokens = countTokens(raw),
            stopReason = StopReason.COMPLETED,
        )
    }

    override suspend fun unload() {
        cancelledFlag.set(false)
    }

    override fun countTokens(text: String): Int = if (text.isEmpty()) 0 else (text.length + 3) / 4

    override fun cancel() {
        cancelledFlag.set(true)
    }

    private companion object {
        const val RESPOND_FALLBACK =
            "<respond>I have no scripted steps left.</respond>"
    }
}

/** The model-visible text of one message. */
private fun ChatMessage.flatText(): String = when (this) {
    is ChatMessage.System -> text
    is ChatMessage.User -> text
    is ChatMessage.Assistant -> text
    is ChatMessage.ToolObservation -> observation
}
