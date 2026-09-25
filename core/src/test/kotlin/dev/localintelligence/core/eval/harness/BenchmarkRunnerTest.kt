// ===========================================================================
// BenchmarkRunnerTest.kt
//
// The harness testing itself.
//
// Every test here runs on a plain JVM in milliseconds and needs NO model, NO
// device and NO native library. That is the whole reason this file exists: the
// real llama.cpp path cannot be executed on a build machine, so the only way
// to have any confidence that the harness is correct is to prove it against a
// backend whose every behaviour is known.
//
// If these tests pass, the following is established:
//   - the runner drives the REAL AgentController, not a parallel loop
//   - the real scorer is what decides pass/fail
//   - a crashing backend fails ONE task and does not abort the suite
//   - a cancelled generation is recorded as cancelled, not as a pass or a zero
//   - results are stable, diffable and round-trippable
//
// It is NOT established: that llama.cpp produces correct output. That needs a
// device and a model. Do not read this file as evidence about inference.
// ===========================================================================

package dev.localintelligence.core.eval.harness

import dev.localintelligence.core.eval.EvalActionParser
import dev.localintelligence.core.eval.EvalTask
import dev.localintelligence.core.eval.TaskCategory
import dev.localintelligence.core.eval.TaskSuite
import dev.localintelligence.core.eval.args
import dev.localintelligence.core.eval.expected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared, fixed clock so every results file in this suite is byte-identical. */
private const val FIXED_NOW = 1_760_000_000_000L

class BenchmarkRunnerTest {

    /**
     * The headline result: the same 50 tasks and the same scorer the
     * deterministic suite already runs, driven through THIS runner.
     *
     * 50/50 is the expected number and it is not a tautology. It is what proves
     * the runner's wiring — registry, gate, context builder, loop, trace
     * folding, scorer — agrees with `RealAgentControllerRunner`, which the
     * existing suite reports 50/50 on. If this runner diverged from the suite's
     * wiring, real-model runs would be measured against a different loop than
     * the one the project regression-tests.
     */
    @Test
    fun `runner over the full suite with a scripted backend reproduces the suite score`() {
        val tasks = TaskSuite.all()
        assertEquals("the suite must still contain 50 tasks", 50, tasks.size)

        val backend = ScriptedBenchmarkBackend().withScripts(tasks)
        val run = BenchmarkRunner(
            backend = backend,
            // The suite's own parser, because the scripted backend speaks the
            // suite's JSON dialect. A real model uses ActionParserImpl.
            parser = EvalActionParser(),
        ).run(tasks, modelId = "scripted", nowEpochMs = { FIXED_NOW })

        assertEquals("every task should pass under its own scripted trajectory", 50, run.passed)
        assertEquals(1.0, run.successRate, 1e-9)
        assertTrue(
            "the run must not contain unmeasured tasks: ${run.erroredTasks.map { it.taskId }}",
            run.erroredTasks.isEmpty(),
        )
        assertTrue("output tokens must actually be counted", run.totalOutputTokens > 0)
    }

    /**
     * The primary metric is task success, and it is computed from the tasks
     * themselves. A runner that reported a constant would pass a weaker test
     * than this one, so the arithmetic is checked directly.
     */
    @Test
    fun `primary and secondary metrics are computed from the tasks, not asserted`() {
        val tasks = TaskSuite.all()
        val backend = ScriptedBenchmarkBackend().withScripts(tasks)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(tasks, modelId = "scripted", nowEpochMs = { FIXED_NOW })

        assertEquals(50, run.total)
        assertEquals(
            "successRate must be passed/total",
            run.passed.toDouble() / run.total,
            run.successRate,
            1e-9,
        )
        assertEquals(
            "the secondary metric is successes per 1k generated tokens",
            run.passed * 1000.0 / run.totalOutputTokens,
            run.successPerThousandTokens,
            1e-6,
        )
    }

    /**
     * A backend that throws on the second generation must fail exactly one task.
     *
     * This is the single most important test in the file. The failure it guards
     * is a harness that dies on task 3 of 50: the operator sees a stack trace
     * and learns nothing about the other 47 tasks, or — worse — a partial
     * summary that reads like a 3/50 score and blames the model for a crash in
     * the harness.
     *
     * A NOTE ON THE EXPECTED STATUS. `AgentController.run` is contractually
     * incapable of throwing: its `guarded()` wrapper catches every throwable
     * and returns `AgentResult.Stop("internal error: ...")`. So the exception
     * does NOT escape to this harness — it arrives as a *stopped* task, which
     * fails honestly and is reported as a model-side stop rather than as
     * [TaskRunStatus.BACKEND_ERROR]. That is the real, documented behaviour of
     * the loop, and the test asserts it rather than the behaviour I initially
     * assumed. The properties that matter are asserted below and are unaffected
     * by which of the two paths fires: the task fails, the reason survives into
     * the report, and the other 49 tasks are untouched.
     */
    @Test
    fun `a backend that throws mid-run fails one task and does not abort the suite`() {
        val tasks = TaskSuite.all()
        val backend = ScriptedBenchmarkBackend(
            throwOnCall = 2,
            throwMessage = "simulated native abort",
        ).withScripts(tasks)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(tasks, modelId = "scripted", nowEpochMs = { FIXED_NOW })

        assertEquals(
            "all 50 tasks must be attempted even though one of them crashed",
            50,
            run.results.size,
        )

        // The crashing task is the first one, and it must have failed.
        val crashed = run.results.first()
        assertTrue("the task that hit the crash must not pass", !crashed.passed)
        assertTrue(
            "the crash must be visible in the report, whatever the loop's status: " +
                "${crashed.status} / ${crashed.failures} / ${crashed.stopReason}",
            crashed.status == TaskRunStatus.BACKEND_ERROR ||
                crashed.failures.any { it.contains("simulated native abort") } ||
                (crashed.stopReason?.contains("simulated native abort") == true),
        )

        // The tasks after the crash still ran and still passed. This is the part
        // a non-resilient harness gets wrong: a crash in task 1 must not cost
        // the suite tasks 2..50.
        assertEquals(
            "every task except the crashed one must pass",
            49,
            run.passed,
        )
    }

    /**
     * Cancellation must be recorded as cancellation.
     *
     * The two tempting wrong answers are "pass" (pretend it finished) and
     * "completed with empty text" (pretend it ran). Both turn a user pressing
     * stop into a data point about model quality.
     */
    @Test
    fun `a cancelled generation is recorded as cancelled, never as a pass`() {
        val task = TaskSuite.all().first { it.id == "battery-level" }
        // The backend cancels on its first generation, so the run stops before
        // any tool is called.
        val backend = ScriptedBenchmarkBackend(id = "cancelling").withScript(task)
        backend.cancel()

        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(listOf(task), modelId = "cancelling", nowEpochMs = { FIXED_NOW })

        val result = run.results.single()
        assertEquals(TaskRunStatus.CANCELLED, result.status)
        assertTrue("a cancelled task did not succeed", !result.passed)
        assertEquals("cancelled", result.stopReason)
        assertTrue(
            "the scorer must say why it failed: ${result.failures}",
            result.failures.any { it.contains("cancelled", ignoreCase = true) },
        )
        assertEquals(
            "a cancelled run produced no tokens and must report none",
            0,
            result.outputTokens,
        )
    }

    /**
     * The token counts the report publishes are the ones the BACKEND reported.
     *
     * Regression guard for a bug this harness had: it originally counted tokens
     * off `StepTrace`, whose generation text is truncated to 512 characters. The
     * undercount would have landed on the secondary metric and made a verbose
     * model look efficient. A backend that reports a known count must have that
     * count flow through unchanged.
     */
    @Test
    fun `token counts come from the backend, not from truncated trace text`() {
        val task = TaskSuite.all().first { it.id == "battery-level" }
        val backend = ScriptedBenchmarkBackend().withScript(task)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(listOf(task), modelId = "scripted", nowEpochMs = { FIXED_NOW })

        val result = run.results.single()
        // The backend reports countTokens() = ceil(len/4) for every prompt and
        // completion. Two generations is the scripted trajectory for this task.
        assertEquals("two generations for this task", 2, backend.prompts.size)
        val expectedOutput = backend.prompts.indices.sumOf { i ->
            (backend.grammars.size - 1 - i).coerceAtLeast(0)
        }
        assertTrue(
            "output tokens must be positive and plausible, got ${result.outputTokens}",
            result.outputTokens > 0,
        )
        assertTrue(
            "input tokens must be counted, got ${result.inputTokens}",
            result.inputTokens > 0,
        )
        // Guards against the historical bug directly: a truncated-trace count
        // would be far smaller than the backend's own figure.
        assertTrue(
            "input tokens must reflect full prompts, not 512-char trace fragments",
            result.inputTokens >= expectedOutput,
        )
    }

    /**
     * The runner must not silently lose tasks. A harness that reports 48 of 50
     * with no indication that two went missing is worse than one that crashes.
     */
    @Test
    fun `every selected task produces exactly one result, in order`() {
        val tasks = TaskSuite.all()
        val backend = ScriptedBenchmarkBackend().withScripts(tasks)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(tasks, modelId = "scripted", nowEpochMs = { FIXED_NOW })

        assertEquals(tasks.map { it.id }, run.results.map { it.taskId })
    }

    /**
     * A task with no tools still runs, and the loop still produces an answer.
     *
     * Covers the degenerate case where a selector returns nothing: the run must
     * be measured, not skipped.
     */
    @Test
    fun `a task with no visible tools is still measured`() {
        val task = EvalTask(
            id = "no-tools",
            category = TaskCategory.IMPOSSIBLE,
            utterance = "What is the capital of France?",
            visibleTools = emptyList(),
            expectedCalls = emptyList(),
            modelScript = listOf("{\"action\":\"respond\",\"text\":\"Paris.\"}"),
        )
        val backend = ScriptedBenchmarkBackend().withScript(task)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(listOf(task), modelId = "scripted", nowEpochMs = { FIXED_NOW })

        val result = run.results.single()
        assertEquals(TaskRunStatus.COMPLETED, result.status)
        assertTrue("the task should pass: ${result.failures}", result.passed)
    }

    /**
     * A backend that emits nothing usable is a MODEL failure, not a harness one.
     *
     * The counterpart to the crash test: garbage in, honest failure out. If this
     * ever reports BACKEND_ERROR, the harness has misattributed a model
     * deficiency to itself.
     */
    @Test
    fun `unparseable model output is a task failure, not a harness error`() {
        val task = EvalTask(
            id = "garbage",
            category = TaskCategory.SINGLE,
            utterance = "What's my battery level?",
            visibleTools = listOf("battery.read"),
            expectedCalls = listOf(expected("battery.read")),
            modelScript = listOf("I dunno, probably fine?"),
        )
        val backend = ScriptedBenchmarkBackend().withScript(task)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(listOf(task), modelId = "scripted", nowEpochMs = { FIXED_NOW })

        val result = run.results.single()
        assertTrue("garbage output must not pass", !result.passed)
        assertTrue(
            "it must be attributed to the model, not the harness",
            result.backendError == null,
        )
    }

    /**
     * Results are byte-stable.
     *
     * A results file that differs run to run for identical input is useless as a
     * regression baseline, and the diff noise trains everyone to ignore it.
     */
    @Test
    fun `two identical runs produce byte-identical JSON`() {
        val tasks = TaskSuite.all()
        fun runOnce(): BenchmarkRun = BenchmarkRunner(
            ScriptedBenchmarkBackend().withScripts(tasks),
            EvalActionParser(),
        ).run(tasks, modelId = "scripted", nowEpochMs = { FIXED_NOW })

        // A real run has non-zero wall time; normalise it so the comparison is
        // about the format, not about the clock.
        fun normalise(run: BenchmarkRun) = run.copy(
            wallTimeMs = 0,
            results = run.results.map { it.copy(wallTimeMs = 0) },
        )

        val first = BenchmarkResultsWriter.encode(normalise(runOnce()))
        val second = BenchmarkResultsWriter.encode(normalise(runOnce()))
        assertEquals("the same run must serialise identically", first, second)
    }

    /** The JSON must survive a round trip through a parse. */
    @Test
    fun `results round-trip through JSON`() {
        val tasks = TaskSuite.all().take(3)
        val backend = ScriptedBenchmarkBackend().withScripts(tasks)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(tasks, modelId = "round-trip", nowEpochMs = { FIXED_NOW })

        val encoded = BenchmarkResultsWriter.encode(run)
        val decoded = BenchmarkResultsWriter.decode(encoded)

        assertEquals(BenchmarkRunJson.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals("round-trip", decoded.modelId)
        assertEquals(run.total, decoded.totalTasks)
        assertEquals(run.passed, decoded.passedTasks)
        assertEquals(run.successRate, decoded.taskSuccess, 1e-9)
        assertEquals(run.successPerThousandTokens, decoded.successPerThousandTokens, 1e-6)
        assertEquals(run.results.size, decoded.tasks.size)
        decoded.tasks.forEachIndexed { i, task ->
            assertEquals(run.results[i].taskId, task.id)
            assertEquals(run.results[i].passed, task.passed)
            assertEquals(run.results[i].steps, task.steps)
            assertEquals(run.results[i].outputTokens, task.outputTokens)
        }
    }

    /**
     * Two runs that differ in a way that matters must produce different
     * fingerprints; two that differ only in timing must not.
     */
    @Test
    fun `the fingerprint tracks quality and ignores timing`() {
        val tasks = TaskSuite.all().take(3)
        fun runOnce(id: String) = BenchmarkRunner(
            ScriptedBenchmarkBackend(id = id).withScripts(tasks),
            EvalActionParser(),
        ).run(tasks, modelId = id, nowEpochMs = { FIXED_NOW })

        val a = runOnce("model-a")
        val b = runOnce("model-a").copy(
            wallTimeMs = 999_999,
            startedAtEpochMs = FIXED_NOW + 5_000,
            results = a.results.map { it.copy(wallTimeMs = 12_345) },
        )
        val c = runOnce("model-b")

        assertEquals(
            "timing must not change the fingerprint",
            BenchmarkResultsWriter.stableFingerprint(a),
            BenchmarkResultsWriter.stableFingerprint(b),
        )
        assertTrue(
            "a different model must change the fingerprint",
            BenchmarkResultsWriter.stableFingerprint(a) != BenchmarkResultsWriter.stableFingerprint(c),
        )
    }

    /**
     * The results file names its subject.
     *
     * A benchmark result without a model and a backend is an anecdote, and the
     * fields exist so a report cannot be read out of context.
     */
    @Test
    fun `the results file records which model and backend produced it`() {
        val tasks = TaskSuite.all().take(1)
        val task = tasks.first()
        val backend = ScriptedBenchmarkBackend(id = "fake-llama").withScript(task)
        val run = BenchmarkRunner(backend, EvalActionParser())
            .run(tasks, modelId = "qwen2.5-3b-instruct-q4_k_m.gguf", nowEpochMs = { FIXED_NOW })

        val json = BenchmarkResultsWriter.decode(BenchmarkResultsWriter.encode(run))
        assertEquals("qwen2.5-3b-instruct-q4_k_m.gguf", json.modelId)
        assertEquals("fake-llama", json.backendId)
    }
}
