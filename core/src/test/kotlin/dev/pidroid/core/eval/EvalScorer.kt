package dev.pidroid.core.eval

import dev.pidroid.core.tool.ObservationTruncator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The metrics docs/evals.md requires, collected per task. */
data class TaskMetrics(
    val success: Boolean = false,
    val steps: Int = 0,
    val toolCalls: Int = 0,
    val invalidToolCalls: Int = 0,
    val duplicateCalls: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalMs: Long = 0,
    val peakRamBytes: Long = 0,
) {
    /** Secondary metric: task success per 1k model-generated tokens. */
    val successPerThousandTokens: Double
        get() = if (outputTokens <= 0) 0.0 else (if (success) 1000.0 else 0.0) / outputTokens
}

/** Everything one task run produced, ready to assert on. */
data class TaskOutcome(
    val task: EvalTask,
    val metrics: TaskMetrics,
    val failures: List<String>,
    val finalAnswer: String?,
    val stopReason: String?,
) {
    val passed: Boolean get() = failures.isEmpty()
}

/**
 * Turns a completed run into a pass/fail plus metrics.
 *
 * The rules, in order of decisiveness:
 *   1. correct tool        — the decisive signal
 *   2. correct arguments   — semantically right, not string-equal
 *   3. correct sequence    — order and count
 *   4. task completed      — the user got what they asked for
 *   5. no loops, no wasted work
 *
 * Never asserts on the wording of the answer. See [AnswerPredicate].
 */
class EvalScorer(private val config: ScorerConfig = ScorerConfig()) {

    data class ScorerConfig(
        /** Observation budget the loop should honour. Mirrors AgentConfig. */
        val observationBudgetChars: Int = ObservationTruncator.DEFAULT_BUDGET_CHARS,
    )

    fun score(
        task: EvalTask,
        run: CompletedRun,
    ): TaskOutcome {
        val failures = mutableListOf<String>()

        failures += checkToolSequence(task, run)
        failures += checkArguments(task, run)
        failures += checkForbiddenTools(task, run)
        failures += checkLimits(task, run)
        failures += checkLoops(task, run)
        failures += checkPrompts(task, run)
        failures += checkConfirmation(task, run)
        val answerFailures = checkAnswer(task, run)
        failures += answerFailures

        val metrics = TaskMetrics(
            success = failures.isEmpty(),
            steps = run.steps,
            toolCalls = run.toolCallCount,
            invalidToolCalls = run.rejectedCalls.size,
            duplicateCalls = run.duplicateCalls,
            inputTokens = run.inputTokens,
            outputTokens = run.outputTokens,
            totalMs = run.totalMs,
            peakRamBytes = run.peakRamBytes,
        )

        return TaskOutcome(
            task = task,
            metrics = metrics,
            failures = failures,
            finalAnswer = run.finalAnswer,
            stopReason = run.stopReason,
        )
    }

    // -- 1. tool sequence ---------------------------------------------------

    private fun checkToolSequence(task: EvalTask, run: CompletedRun): List<String> {
        val executed = run.executedCalls
        val expected = task.expectedCalls
        if (executed.isEmpty() && expected.isEmpty()) return emptyList()

        // A call the runtime gated for confirmation is deliberately NOT executed:
        // the run pauses before the risky tool and the harness counts that as the
        // correct behaviour, not as a missing call. The gated call is verified
        // separately by checkConfirmation, so it is excluded from the sequence
        // comparison here.
        val gate = task.requiresConfirmation
        val comparable = if (gate != null && expected.lastOrNull()?.tool == gate) {
            expected.dropLast(1)
        } else {
            expected
        }

        // A call the tool actually RAN but that came back failed (denied, threw)
        // still belongs in the expected trajectory: the model did the right thing
        // by calling it, and the failure is the scenario being tested. Verified
        // against invocations, not successes.
        val failedExpectations = comparable.filter { want ->
            executed.none { it.name == want.tool } &&
                run.invokedCalls.any { it.name == want.tool }
        }
        val mustHaveExecuted = comparable.filterNot { it in failedExpectations }

        val failures = mutableListOf<String>()
        if (executed.size < mustHaveExecuted.size) {
            failures += "expected ${mustHaveExecuted.size} tool call(s) " +
                "(${mustHaveExecuted.joinToString(", ") { it.tool }}), got ${executed.size} " +
                "(${executed.joinToString(", ") { it.name }.ifEmpty { "none" }})"
            return failures
        }

        mustHaveExecuted.forEachIndexed { index, want ->
            val got = executed[index]
            if (got.name != want.tool) {
                failures += "step ${index + 1}: expected tool ${want.tool}, got ${got.name}"
            }
        }
        if (executed.size > mustHaveExecuted.size) {
            val extra = executed.drop(mustHaveExecuted.size).joinToString(", ") { it.name }
            failures += "unexpected extra tool call(s) after the expected trajectory: $extra"
        }
        return failures
    }

    // -- 2. arguments -------------------------------------------------------

    private fun checkArguments(task: EvalTask, run: CompletedRun): List<String> {
        val failures = mutableListOf<String>()
        task.expectedCalls.forEachIndexed { index, want ->
            if (index >= run.executedCalls.size) return@forEachIndexed
            val got = run.executedCalls[index]
            if (got.name != want.tool) return@forEachIndexed

            val actualArgs = got.args.flatten()
            want.args.forEach { (key, expectedValue) ->
                val actual = actualArgs[key]
                if (actual == null) {
                    failures += "step ${index + 1} (${want.tool}): missing argument \"$key\""
                } else if (!argsEquivalent(expectedValue, actual)) {
                    failures += "step ${index + 1} (${want.tool}): argument \"$key\" was " +
                        "\"$actual\", expected \"$expectedValue\""
                }
            }
            want.forbiddenArgs.forEach { key ->
                if (actualArgs.containsKey(key)) {
                    failures += "step ${index + 1} (${want.tool}): argument \"$key\" should not be sent"
                }
            }
        }

        // Global forbidden-argument map: "never send a body to sms.send".
        task.limits.forbiddenArgs.forEach { (tool, keys) ->
            run.executedCalls.filter { it.name == tool }.forEachIndexed { i, call ->
                val present = keys.intersect(call.args.flatten().keys)
                if (present.isNotEmpty()) {
                    failures += "call $i to $tool sent forbidden argument(s): ${present.joinToString(", ")}"
                }
            }
        }
        return failures
    }

    /**
     * Value equality that tolerates the coercions models actually make:
     * "07:30" vs "7:30", 43 vs "43", 43.0 vs "43".
     */
    private fun argsEquivalent(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        if (expected.equals(actual, ignoreCase = true)) return true
        val expectedNum = expected.toDoubleOrNull()
        val actualNum = actual.toDoubleOrNull()
        if (expectedNum != null && actualNum != null) return expectedNum == actualNum
        // Time-like: 7:30 == 07:30
        val e = expected.trim().removePrefix("0")
        val a = actual.trim().removePrefix("0")
        if (e == a) return true
        // Numeric token equivalence: "30" == "30 minutes" is NOT accepted, but
        // "30" == "30.0" is.
        return false
    }

    // -- 3. forbidden tools -------------------------------------------------

    private fun checkForbiddenTools(task: EvalTask, run: CompletedRun): List<String> {
        val forbidden = task.limits.forbiddenTools
        if (forbidden.isEmpty()) return emptyList()
        val attempted = run.attemptedCalls.map { it.name }.filter { it in forbidden }
        if (attempted.isEmpty()) return emptyList()
        return listOf("called forbidden tool(s): ${attempted.distinct().joinToString(", ")}")
    }

    // -- 4. limits ----------------------------------------------------------

    private fun checkLimits(task: EvalTask, run: CompletedRun): List<String> {
        val failures = mutableListOf<String>()
        if (run.steps > task.limits.maxSteps) {
            failures += "took ${run.steps} steps, limit is ${task.limits.maxSteps}"
        }
        if (run.toolCallCount > task.limits.maxToolCalls) {
            failures += "made ${run.toolCallCount} tool calls, limit is ${task.limits.maxToolCalls}"
        }
        return failures
    }

    // -- 5. loops -----------------------------------------------------------

    private fun checkLoops(task: EvalTask, run: CompletedRun): List<String> {
        val limit = task.limits.maxDuplicateCalls
        if (run.duplicateCalls <= limit) return emptyList()

        // Name the loop, the way docs/evals.md shows it: "LOOP: x repeated 14x".
        // Counted over ATTEMPTS, so a retry storm is visible even when the loop
        // detector stopped it from executing. A detector that works should be
        // visible in `tool_calls`, not hidden by it.
        val repeats = run.attemptedCalls
            .groupBy { it.name to canonical(it.args) }
            .filterValues { it.size > 1 }
        val described = repeats.entries.joinToString(", ") { (key, group) ->
            "LOOP: ${key.first} repeated ${group.size}x"
        }
        return listOf("$described (limit $limit)")
    }

    private fun canonical(args: JsonObject): String =
        args.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }

    // -- 6. prompts ---------------------------------------------------------

    private fun checkPrompts(task: EvalTask, run: CompletedRun): List<String> {
        if (task.promptAssertions.isEmpty() || run.prompts.isEmpty()) return emptyList()
        val first = run.prompts.first()
        val failures = mutableListOf<String>()
        task.promptAssertions.forEach { assertion ->
            val ok = when (assertion) {
                is PromptAssertion.AnyPromptContains -> run.prompts.any { assertion.holdsOn(it) }
                is PromptAssertion.FirstPromptContains -> assertion.holdsOn(first)
                is PromptAssertion.NoPromptContains -> run.prompts.none { it.contains(assertion.fragment, true) }
            }
            if (!ok) failures += "prompt assertion failed: ${assertion.describe()}"
        }
        return failures
    }

    // -- 7. confirmation ----------------------------------------------------

    private fun checkConfirmation(task: EvalTask, run: CompletedRun): List<String> {
        val tool = task.requiresConfirmation ?: return emptyList()
        if (run.awaitingConfirmationFor == tool) return emptyList()
        return listOf("expected a confirmation gate before $tool, but the loop executed it directly")
    }

    // -- 8. the answer ------------------------------------------------------

    private fun checkAnswer(task: EvalTask, run: CompletedRun): List<String> {
        // A run that never produced an answer is only acceptable for tasks that
        // do not ask for one (pure confirmation gates).
        val answer = run.finalAnswer
        if (answer == null) {
            return if (task.requiresConfirmation != null) {
                emptyList()
            } else {
                listOf("run produced no final answer (stopped: ${run.stopReason})")
            }
        }
        if (task.requiresConfirmation != null) return emptyList()

        return task.answerPredicates.filterNot { it.satisfiedBy(answer) }
            .map { "final answer does not satisfy: ${it.describe()} (got: \"$answer\")" }
    }
}

/** Flattens a JSON object to plain strings, for argument comparison. */
fun JsonObject.flatten(): Map<String, String> = entries.mapNotNull { (key, value) ->
    val primitive = value as? JsonPrimitive ?: return@mapNotNull null
    key to primitive.content
}.toMap()
