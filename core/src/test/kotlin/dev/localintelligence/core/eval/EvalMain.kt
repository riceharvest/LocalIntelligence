package dev.localintelligence.core.eval

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Entry point for `./gradlew :core:evals`.
 *
 * Runs the 50-task suite against fakes and prints one line per task plus a
 * summary, in the format pinned by docs/evals.md. Exits non-zero when the
 * suite fails, so CI can gate on it.
 *
 * `--model <path>` is accepted and ignored: this build has fakes only. A real
 * GGUF backend is agent D's `LlamaModelBackend`; when it lands, the same suite
 * runs against it with no change to the tasks, the scorer or this file.
 */
fun main(args: Array<String>) {
    val options = EvalMainOptions.parse(args)
    val report = EvalMain.run(options)
    print(EvalReport.render(report))
    if (report.failed > 0) exitProcess(1)
}

data class EvalMainOptions(
    /** Accepted for forward compatibility. Unused while the suite runs on fakes. */
    val modelPath: String? = null,
    /** Restrict the run to these category slugs. Empty means all of them. */
    val onlyCategories: Set<String> = emptySet(),
    /** Print per-task failure detail even for passing tasks. */
    val verbose: Boolean = false,
) {
    companion object {
        /**
         * Tolerant by design. A missing value, a stray flag or a bare `--model`
         * must never crash the suite: a broken eval runner reports zero tasks
         * and looks like a passing build, which is the worst possible outcome.
         */
        fun parse(argv: Array<String>): EvalMainOptions {
            var model: String? = null
            val categories = mutableSetOf<String>()
            var verbose = false
            var i = 0
            while (i < argv.size) {
                val arg = argv[i]
                when {
                    arg == "--model" -> {
                        // Consume the value if there is one. Skipping it matters:
                        // otherwise the path falls through the bare-argument branch
                        // below and gets treated as a category name.
                        val next = argv.getOrNull(i + 1)
                        if (next != null && !next.startsWith("--")) {
                            model = next
                            i++
                        }
                    }
                    arg == "--only" -> {
                        val next = argv.getOrNull(i + 1)
                        if (next != null && !next.startsWith("--")) {
                            categories += next.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            i++
                        }
                    }
                    arg == "--verbose" || arg == "-v" -> verbose = true
                    arg.startsWith("-") -> Unit // unknown flag: ignored, never fatal
                    else -> categories += arg
                }
                i++
            }
            return EvalMainOptions(model, categories, verbose)
        }
    }
}

object EvalMain {

    /**
     * Runs every task and scores it. Sequential on purpose: the suite must be
     * reproducible, and a shared static clock is harder to reason about than a
     * slow test run nobody parallelises.
     */
    fun run(options: EvalMainOptions = EvalMainOptions()): SuiteReport {
        val tasks = TaskSuite.all().filter { task ->
            options.onlyCategories.isEmpty() || task.category.slug in options.onlyCategories
        }
        // The REAL agent loop, not the reference double. A green suite here is
        // evidence about AgentController. ReferenceAgentLoop stays available so
        // HarnessSelfTest can prove the two layers still agree.
        val runner: AgentRunner = RealAgentControllerRunner()
        val scorer = EvalScorer()
        val startedAt = System.nanoTime()

        val outcomes = tasks.map { task ->
            val run = try {
                runBlocking { runner.run(task) }
            } catch (e: Throwable) {
                // A harness that dies mid-task must report the task as failed,
                // not abort the suite. A missing report is a silent lie.
                CompletedRun(
                    steps = 0,
                    attemptedCalls = emptyList(),
                    executedCalls = emptyList(),
                    invokedCalls = emptyList(),
                    rejectedCalls = emptyList(),
                    prompts = emptyList(),
                    finalAnswer = null,
                    stopReason = "harness threw: ${e::class.simpleName}: ${e.message}",
                    inputTokens = 0,
                    outputTokens = 0,
                    totalMs = 0,
                    peakRamBytes = 0,
                )
            }
            scorer.score(task, run)
        }

        return SuiteReport(
            outcomes = outcomes,
            totalMs = (System.nanoTime() - startedAt) / 1_000_000,
            peakRamBytes = usedHeapBytes(),
        )
    }
}
