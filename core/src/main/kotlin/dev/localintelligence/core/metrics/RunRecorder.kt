package dev.localintelligence.core.metrics

import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ToolArgs

/**
 * Accumulates one run's metrics while it happens, and hands back a [RunMetrics].
 *
 * WHY a recorder instead of the loop writing fields: the loop is already the
 * most contested 300 lines in the project, and eight metric assignments inside
 * it would be eight merge conflicts, eight chances to skip one on an early
 * return, and a reason to touch `AgentController` from a metrics package. Here
 * the loop calls three intent-revealing methods and never learns what a
 * duplicate is.
 *
 * WHY it owns duplicate detection: counting them correctly requires seeing every
 * *attempted* call, which is exactly the information a completed-run record in
 * the eval harness reconstructs after the fact. Recording it as it happens is
 * the only way to get it right without re-deriving it.
 *
 * Not thread-safe, and deliberately so: a run is one coroutine. A recorder that
 * synchronized would be admitting that two runs share one, which is a bug in
 * the caller.
 */
class RunRecorder(
    val runId: String = "",
    val taskId: String = "",
    val modelId: String = "",
    private val time: TimeSource = SystemTimeSource,
) {
    private val stepTimings = mutableListOf<StepTiming>()
    private val attempted = mutableListOf<ToolCallKey>()

    private var step: Int = 0
    private var phaseStartedAt: Long = time.nanoTime()

    private var toolCalls: Int = 0
    private var invalidToolCalls: Int = 0
    private var inputTokens: Int = 0
    private var outputTokens: Int = 0
    private var prefillMs: Long = 0
    private var decodeMs: Long = 0

    private val runWatch = Stopwatch(time)

    /** Marks the boundary of a step. Resets the phase watch so step durations are per-step. */
    fun beginStep(step: Int) {
        this.step = step
        phaseStartedAt = time.nanoTime()
    }

    /**
     * Folds one real generation's numbers into the run.
     *
     * Token counts and prefill/decode come from the backend rather than the
     * clock: only the model knows how many tokens it produced, and a locally
     * estimated count would quietly change the secondary metric's denominator.
     */
    fun recordGeneration(result: GenerationResult) {
        inputTokens += result.promptTokens
        outputTokens += result.completionTokens
        prefillMs += result.prefillMs
        decodeMs += result.decodeMs
    }

    /**
     * Records one tool call the model asked for.
     *
     * Returns true when this exact call — same name, same canonical arguments —
     * was already attempted in this run. The caller gets the answer for free,
     * which is the only way a loop is going to actually use it.
     */
    fun recordToolCall(name: String, args: ToolArgs, executed: Boolean): Boolean {
        val key = ToolCallKey.of(name, args)
        val duplicate = attempted.count { it == key } > 0
        attempted += key
        if (executed) toolCalls += 1 else invalidToolCalls += 1
        return duplicate
    }

    /**
     * Closes the current phase and files its duration.
     *
     * The phase is [StepTrace.Kind] so the timing file and the run trace stay
     * in one vocabulary; `endPhase` after a matching `beginPhase` is the only
     * legal way to record a duration, which is what keeps a timing from being
     * attributed to the wrong step.
     */
    fun endPhase(phase: StepTrace.Kind, success: Boolean = true) {
        val now = time.nanoTime()
        stepTimings += StepTiming(
            step = step,
            phase = phase,
            durationMs = (now - phaseStartedAt) / NANOS_PER_MS,
            success = success,
        )
        phaseStartedAt = now
    }

    /** Convenience: runs [block] as a single timed phase. */
    fun <T> phase(kind: StepTrace.Kind, success: Boolean = true, block: () -> T): T {
        val value = block()
        endPhase(kind, success)
        return value
    }

    /** The [ToolCallKey]s this run attempted, in order. Diagnostics for a bad run. */
    fun attemptedCalls(): List<ToolCallKey> = attempted.toList()

    /**
     * Freezes the run. Callable once; the result is a plain immutable value and
     * the recorder holds no reference back into it.
     */
    fun finish(success: Boolean): RunMetrics = RunMetrics(
        runId = runId,
        taskId = taskId,
        modelId = modelId,
        success = success,
        steps = step,
        toolCalls = toolCalls,
        invalidToolCalls = invalidToolCalls,
        duplicateCalls = attempted.size - attempted.distinct().size,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        totalMs = runWatch.elapsedMs(),
        stepTimings = stepTimings.toList(),
    )

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
