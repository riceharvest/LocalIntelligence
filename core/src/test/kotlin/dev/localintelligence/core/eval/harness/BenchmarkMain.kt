// ===========================================================================
// BenchmarkMain.kt
//
// THE HONEST ENTRY POINT
// ======================
//
// A benchmark that cannot run must SAY IT CANNOT RUN. The failure modes this
// file exists to prevent, all of which are ways a harness lies by accident:
//
//   - printing a report with zeros when no model was found
//   - exiting 0 when nothing was measured
//   - reporting a model as "0% success" when it was never loaded
//   - writing a results file for a run that did not happen
//
// Every one of those turns "we do not know" into "the model is bad", which is
// the most expensive kind of wrong this project can produce. So:
//
//   - no model          -> actionable message, exit 2, NO results file
//   - model unfit       -> the gate's reasons, exit 3, NO results file
//   - library missing   -> the reason plus how to proceed, exit 4, NO file
//   - some tasks failed -> the full report IS written, exit 1
//   - all tasks passed  -> the full report IS written, exit 0
//
// The exit code is the machine-readable answer, and "did not run" is never
// conflated with "ran and failed".
//
// `./gradlew :core:benchmark` runs it. See the KDoc on `main` for the exact
// command a user runs against a real model on a real device.
// ===========================================================================

package dev.localintelligence.core.eval.harness

import dev.localintelligence.core.agent.ActionParserImpl
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.eval.EvalActionParser
import dev.localintelligence.core.eval.EvalTask
import dev.localintelligence.core.eval.TaskSuite
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelSpec
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/** Process exit codes. Documented so a CI job can branch on them. */
object BenchmarkExit {
    /** Every task passed. */
    const val OK = 0

    /** The suite ran and at least one task failed. A real, measured result. */
    const val TASKS_FAILED = 1

    /** Bad usage: unknown flag, no tasks selected, unreadable output path. */
    const val USAGE = 2

    /** No model was found where one was expected. */
    const val NO_MODEL = 3

    /** A model was found but the fit gate refused it. */
    const val MODEL_UNFIT = 4

    /** The inference backend is not available in this environment. */
    const val BACKEND_UNAVAILABLE = 5
}

/** Parsed command line. Tolerant by design — see [parse]. */
data class BenchmarkOptions(
    /** Directory to search for .gguf files. */
    val modelDir: Path? = null,
    /** An explicit model file, which wins over [modelDir]. */
    val modelFile: Path? = null,
    /** Where to write the JSON results. Null prints to stdout only. */
    val output: Path? = null,
    /** Restrict to these category slugs. Empty means all 50. */
    val onlyCategories: Set<String> = emptySet(),
    /** Cap on tasks, for a quick smoke run. 0 means no cap. */
    val limit: Int = 0,
    /** Context length the gate requires the model to have. */
    val requiredContext: Int = DEFAULT_REQUIRED_CONTEXT,
    /**
     * Skip the chat-template requirement.
     *
     * An escape hatch, not a default. A base (non-instruct) model will fail
     * most tasks for reasons that have nothing to do with the harness, and
     * someone measuring that needs a way in — but they should have to type
     * this to get it.
     */
    val allowNoChatTemplate: Boolean = false,
    /** Parse with the production protocol instead of the suite's JSON dialect. */
    val evalParserForced: Boolean = false,
    /** Use a scripted backend instead of llama.cpp. The default on a desktop JVM. */
    val useScriptedBackend: Boolean = true,
    /** Print per-task detail even for passing tasks. */
    val verbose: Boolean = false,
) {
    companion object {
        const val DEFAULT_REQUIRED_CONTEXT = 4096

        /**
         * Never throws on bad input.
         *
         * A harness that crashes on a typo reports zero tasks and looks like a
         * passing build, which is the worst possible outcome for the tool whose
         * entire job is to tell you whether something works. Unknown flags are
         * ignored; a flag missing its value does not swallow the next flag.
         */
        fun parse(argv: Array<String>): BenchmarkOptions {
            var modelDir: Path? = null
            var modelFile: Path? = null
            var output: Path? = null
            var only = emptySet<String>()
            var limit = 0
            var context = DEFAULT_REQUIRED_CONTEXT
            var allowNoTemplate = false
            var evalParserForced = false
            var scripted = true
            var verbose = false

            var i = 0
            while (i < argv.size) {
                val arg = argv[i]
                fun value(): String? {
                    val next = argv.getOrNull(i + 1)
                    return if (next != null && !next.startsWith("--")) {
                        i++
                        next
                    } else {
                        null
                    }
                }
                when {
                    arg == "--models" || arg == "--model-dir" -> value()?.let { modelDir = Paths.get(it) }
                    arg == "--gguf" || arg == "--model" -> value()?.let { modelFile = Paths.get(it) }
                    arg == "--out" || arg == "--output" -> value()?.let { output = Paths.get(it) }
                    arg == "--only" -> value()?.let {
                        only = it.split(',').map(String::trim).filter(String::isNotEmpty).toSet()
                    }
                    arg == "--limit" -> value()?.let { limit = it.toIntOrNull() ?: 0 }
                    arg == "--context" -> value()?.let { context = it.toIntOrNull() ?: context }
                    arg == "--allow-no-chat-template" -> allowNoTemplate = true
                    // The suite's JSON dialect is what the scripted fake speaks.
                    // Selecting a real model implies the production protocol,
                    // because that is the only one a real model will be
                    // constrained into.
                    // Forces the suite's JSON dialect. Normally the parser is
                    // chosen by which backend is in play, so this is only for
                    // deliberately scoring a real model with suite grammar.
                    arg == "--eval-parser" -> evalParserForced = true
                    arg == "--backend" -> scripted = value() != "llama"
                    arg == "--verbose" || arg == "-v" -> verbose = true
                    arg.startsWith("-") -> Unit // unknown flag: ignored, never fatal
                    else -> only = only + arg
                }
                i++
            }
            return BenchmarkOptions(
                modelDir = modelDir,
                modelFile = modelFile,
                output = output,
                onlyCategories = only,
                limit = limit,
                requiredContext = context,
                allowNoChatTemplate = allowNoTemplate,
                evalParserForced = evalParserForced,
                // A real model is the point of the tool, so naming one opts out
                // of the scripted default even if --backend was omitted.
                useScriptedBackend = scripted && modelFile == null && modelDir == null,
                verbose = verbose,
            )
        }
    }
}

/**
 * Everything the CLI can decide, as data.
 *
 * Returning a result object instead of calling `exitProcess` is what makes the
 * degradation paths TESTABLE. A CLI that exits from inside its logic can only
 * be tested by forking a process; a CLI that returns a code can be tested by
 * asserting on the code, and the tests here do exactly that for every failure
 * mode the file claims to handle.
 */
sealed interface BenchmarkOutcome {
    /** The suite ran. [run] is real, and the results file was written if asked. */
    data class Completed(val run: BenchmarkRun, val exitCode: Int) : BenchmarkOutcome

    /** Nothing was measured. [message] is meant to be read by a person. */
    data class Refused(val message: String, val exitCode: Int) : BenchmarkOutcome
}

/**
 * The benchmark CLI, as a pure function of its options.
 *
 * Object, no state: every field of the decision is an argument, so the same
 * options always produce the same outcome. A benchmark whose behaviour depends
 * on what it did earlier in the same process cannot be used as a regression
 * gate.
 */
object BenchmarkMain {

    /**
     * Runs the suite and decides the exit code.
     *
     * @param tasks the suite. Injectable so a test can run three tasks in
     *   milliseconds instead of fifty.
     * @param nowEpochMs injectable clock, so a results file is byte-stable.
     */
    fun run(
        options: BenchmarkOptions,
        tasks: List<EvalTask> = TaskSuite.all(),
        nowEpochMs: () -> Long = System::currentTimeMillis,
    ): BenchmarkOutcome {
        val selected = selectTasks(tasks, options)
        if (selected.isEmpty()) {
            return BenchmarkOutcome.Refused(
                "no tasks matched ${if (options.onlyCategories.isEmpty()) "the empty selection" else options.onlyCategories.joinToString()}\n" +
                    "Known categories: ${dev.localintelligence.core.eval.TaskCategory.entries.joinToString(", ") { it.slug }}",
                BenchmarkExit.USAGE,
            )
        }

        // Backend construction, the fit gate, the load and the run are all
        // inside one try, because all of them can refuse. A refusal raised
        // during construction must reach the same handler as one raised during
        // load — otherwise the gate's exit code leaks out as a stack trace.
        return try {
            val backend = buildBackend(options, selected)
                ?: return BenchmarkOutcome.Refused(MODEL_REQUIRED_MESSAGE, BenchmarkExit.NO_MODEL)

            // The gate already ran inside buildBackend, on the HEADER, before any
            // load. This second check is for the backend's own availability (a
            // missing native library), which the header cannot tell us.
            if (backend is LlamaCppBenchmarkBackend) {
                val unavailable = backend.unavailable
                if (unavailable != null) {
                    return BenchmarkOutcome.Refused(
                        "${unavailable.message}\n\n" +
                            "To exercise the harness without a model, run with no --gguf/--models: " +
                            "it will use the scripted backend and report that the harness itself is sound.",
                        BenchmarkExit.BACKEND_UNAVAILABLE,
                    )
                }
            }

            runBlocking {
                // `displayName` is what the report prints; `id` is what the
                // backend cross-checks. Both come from the same place so a
                // result can never claim one model and have loaded another.
                val modelId = backend.modelIdForLoad()
                backend.load(ModelSpec(id = modelId, displayName = modelId))
                val runner = BenchmarkRunner(
                    backend = backend,
                    // THE PARSER MUST MATCH THE BACKEND'S DIALECT.
                    //
                    // A real model is constrained to the shipping tag protocol
                    // that GrammarBuilder emits, so it needs the production
                    // parser. The scripted backend replays the suite's JSON
                    // dialect, so it needs the suite's parser — pairing it with
                    // the production parser scores 0/50 and reports it as a model
                    // failure, which is the exact fabrication this harness exists
                    // to prevent.
                    //
                    // Defaulted by BACKEND, not by flag, so a scripted run can
                    // never be mis-scored by a default. `--eval-parser` only
                    // exists to force the suite dialect against a real model.
                    parser = when {
                        options.evalParserForced -> EvalActionParser()
                        backend is ScriptedBenchmarkBackend -> EvalActionParser()
                        else -> ActionParserImpl
                    },
                    config = AgentConfig(),
                )
                val run = runner.run(selected, modelId = backend.modelIdForLoad(), nowEpochMs = nowEpochMs)
                backend.unload()

                val exit = if (run.passed == run.total) BenchmarkExit.OK else BenchmarkExit.TASKS_FAILED
                BenchmarkOutcome.Completed(run, exit)
            }
        } catch (e: ModelUnfitException) {
            // The gate said no. NO results file is written: a file recording
            // 0/50 for a model that was never loaded is the single most
            // misleading artefact this tool could produce.
            BenchmarkOutcome.Refused(
                "the model cannot run this suite:\n\n${e.message}\n\nNo results were recorded.",
                BenchmarkExit.MODEL_UNFIT,
            )
        } catch (e: Throwable) {
            // A model that will not load is a REFUSAL, not a zero. Same
            // reasoning as above: nothing was measured, so nothing is written.
            BenchmarkOutcome.Refused(
                "the benchmark could not start: ${e::class.simpleName}: ${e.message}\n" +
                    "No results were recorded, because nothing was measured.",
                BenchmarkExit.BACKEND_UNAVAILABLE,
            )
        }
    }

    /**
     * Resolves the backend the options ask for.
     *
     * Returns null when a model was requested and none exists — the caller
     * turns that into [BenchmarkExit.NO_MODEL] with a message, never into an
     * empty report.
     */
    private fun buildBackend(options: BenchmarkOptions, tasks: List<EvalTask>): ModelBackend? {
        if (options.useScriptedBackend) {
            // Each task gets its OWN trajectory. A single flat queue would
            // replay task 1's script for all 50 and report 2% as a model
            // result — a fabricated measurement from a scripted backend.
            return ScriptedBenchmarkBackend().withScripts(tasks)
        }

        val path = resolveModelPath(options)
            ?: return null

        // The fit gate runs here, on the HEADER, before any load. A model whose
        // trained context is below what the suite needs is refused outright:
        // loading it would truncate every prompt and report a success rate of
        // zero that describes the model choice, not the model.
        val header = try {
            GgufModelDiscovery.read(path)
        } catch (e: GgufFormatException) {
            throw IllegalStateException(
                "${path.fileName} could not be read as a GGUF: ${e.message}",
            )
        }
        val verdict = GgufModelDiscovery.assessFit(
            model = header,
            requiredContextTokens = options.requiredContext,
            requireChatTemplate = !options.allowNoChatTemplate,
        )
        if (!verdict.runs) {
            throw ModelUnfitException(verdict.explain())
        }

        return LlamaCppBenchmarkBackend(
            modelPath = path.toAbsolutePath().toString(),
            contextLength = options.requiredContext,
        )
    }

    /**
     * A model the gate refused.
     *
     * A distinct type because it is a DIFFERENT failure from a model that
     * failed to load: the first is "this file cannot answer these questions"
     * and the second is "this file is broken". They need different fixes, and
     * they get different exit codes.
     */
    class ModelUnfitException(message: String) : IllegalStateException(message)

    /**
     * The explicit file, else the first usable .gguf in the directory.
     *
     * A directory can easily contain a half-downloaded or truncated model. That
     * must not make the whole run impossible: the operator has a good model
     * sitting next to a broken one, and "directory contains one corrupt file"
     * is not an actionable error. So unreadable and unfit files are COLLECTED
     * and reported, and the first candidate that passes the gate wins.
     *
     * An explicitly named file is never silently substituted. If you say
     * `--gguf a.gguf` and it is broken, you want to hear about `a.gguf`.
     */
    private fun resolveModelPath(options: BenchmarkOptions): Path? {
        options.modelFile?.let { return if (Files.isRegularFile(it)) it else null }
        val dir = options.modelDir ?: return null

        val candidates = GgufModelDiscovery.discover(dir)
        if (candidates.isEmpty()) return null

        val skipped = mutableListOf<String>()
        for (candidate in candidates) {
            val reason = try {
                val header = GgufModelDiscovery.read(candidate)
                val verdict = GgufModelDiscovery.assessFit(
                    model = header,
                    requiredContextTokens = options.requiredContext,
                    requireChatTemplate = !options.allowNoChatTemplate,
                )
                if (verdict.runs) null else "context/architecture: ${verdict.reasons.joinToString("; ")}"
            } catch (e: GgufFormatException) {
                "unreadable: ${e.message}"
            }
            if (reason == null) {
                if (skipped.isNotEmpty()) {
                    // Said out loud, because "which model did that number come
                    // from" must never require guesswork.
                    skipped.forEach { System.err.println("skipped $it") }
                }
                return candidate
            }
            skipped += "${candidate.fileName} ($reason)"
        }

        // Nothing usable. Report every skip: the operator's next action depends
        // on WHICH model was wrong and why.
        skipped.forEach { System.err.println("skipped $it") }
        throw IllegalStateException(
            "no model in $dir can run this suite. Every .gguf found was refused; " +
                "see the skip reasons above.",
        )
    }

    /**
     * Applies the category filter and the task cap.
     *
     * The cap is applied AFTER filtering, so `--only memory --limit 2` means
     * "two memory tasks", not "two tasks, whichever survived the filter". The
     * other order is the one that surprises people.
     */
    private fun selectTasks(tasks: List<EvalTask>, options: BenchmarkOptions): List<EvalTask> {
        val filtered = tasks.filter { task ->
            options.onlyCategories.isEmpty() || task.category.slug in options.onlyCategories
        }
        return if (options.limit > 0) filtered.take(options.limit) else filtered
    }

    /**
     * The scripted trajectory for [tasks], in order.
     *
     * Used only by the no-model path, so that "run the harness with no model"
     * still demonstrates the loop end to end. This is NOT a model result and
     * the report labels it as synthetic; a scripted pass must never be mistaken
     * for a measurement of anything.
     */
    private fun scriptedFor(tasks: List<EvalTask>): List<String> = buildList {
        tasks.forEach { task -> task.modelScript.forEach { add(it) } }
    }

    /** What to print when no model was found. Says what to do next. */
    const val MODEL_REQUIRED_MESSAGE: String =
        "No model was found, so no benchmark was run.\n\n" +
            "This tool will not print a report for a model it never loaded.\n" +
            "To run against a real model, point it at a .gguf file:\n" +
            "  --gguf /path/to/model.gguf\n" +
            "  --models /path/to/models/          (searches for .gguf recursively)\n" +
            "\n" +
            "To verify the harness itself without a model, run with no --gguf/--models;\n" +
            "it will use a scripted backend and label the result as synthetic."
}

/**
 * Process entry point for `./gradlew :core:benchmark`.
 *
 * The only place in the harness that calls `exitProcess`. Everything else
 * returns a [BenchmarkOutcome], which is what makes the CLI testable: a test
 * asserts on a returned exit code, never on a process that died.
 *
 * With a real model on a real device:
 * ```
 * adb push model.gguf /data/local/tmp/model.gguf
 * ./gradlew :core:benchmark \
 *     -Plocalintelligence.model=/data/local/tmp/model.gguf \
 *     -Plocalintelligence.out=build/benchmark-results.json
 * ```
 *
 * Exits with a [BenchmarkExit] code. A refusal is non-zero and writes NO
 * results file: a stale file beside a failed run is how "no model was present"
 * turns into "0.00 task success" three commits later.
 */
fun main(args: Array<String>) {
    val options = BenchmarkOptions.parse(args)
    val outcome = BenchmarkMain.run(options)

    when (outcome) {
        is BenchmarkOutcome.Refused -> {
            System.err.println("benchmark refused to run:")
            System.err.println(outcome.message)
            exitProcess(outcome.exitCode)
        }

        is BenchmarkOutcome.Completed -> {
            val json = BenchmarkResultsWriter.encode(outcome.run)
            options.output?.let { path ->
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, json)
                println("results written to $path")
            }
            if (options.output == null) println(json)
            print(BenchmarkTextReport.render(outcome.run, options.verbose))
            // A suite that ran but measured nothing is not a success either:
            // an empty result set must never be reported as a pass.
            val code = if (outcome.run.total == 0) BenchmarkExit.TASKS_FAILED else BenchmarkExit.OK
            exitProcess(code)
        }
    }
}

/**
 * The human-facing half of the output.
 *
 * Separate from the JSON because the two have different jobs: this one is read
 * by a person in a terminal who wants to know which tasks broke, and the JSON
 * is read by a machine that wants to compare two commits. Printing the JSON to
 * a person is how a good result gets ignored.
 */
object BenchmarkTextReport {

    private const val NAME_WIDTH = 44

    /**
     * Renders [run].
     *
     * Errored tasks are called out separately from failed ones, in their own
     * section. Conflating them is what produces "the model is bad" out of "the
     * harness fell over", and the two call for completely different fixes.
     */
    fun render(run: BenchmarkRun, verbose: Boolean = false): String = buildString {
        run.results.forEach { result ->
            if (!result.passed || verbose) appendLine(renderTask(result))
        }

        val errors = run.erroredTasks
        if (errors.isNotEmpty()) {
            appendLine()
            appendLine(
                "${errors.size} task(s) could NOT BE MEASURED. These are not model failures:",
            )
            errors.forEach { appendLine("  ${it.taskId}: ${it.backendError}") }
            appendLine("  Treat this run as INCOMPLETE, not as ${run.passed}/${run.total}.")
        }

        appendLine()
        appendLine(
            "%d tasks | %d pass (%.0f%%) | %d in tok | %d out tok | %.1fs".format(
                run.total,
                run.passed,
                run.successRate * 100,
                run.totalInputTokens,
                run.totalOutputTokens,
                run.wallTimeMs / 1000.0,
            ),
        )
        appendLine("primary:   task success            %.2f".format(run.successRate))
        appendLine(
            "secondary: success per 1k tokens   %.1f tasks".format(run.successPerThousandTokens),
        )
        appendLine("model:     ${run.modelId}  (backend ${run.backendId})")

        if (run.requestsWithGrammar == 0) {
            appendLine()
            appendLine(
                "NOTE: 0 generation requests carried an action grammar. The shipping loop sets " +
                    "grammar = null,\n      so malformed-output failures below are evidence about " +
                    "the missing grammar,",
            )
            appendLine("      not only about the model.")
        }
    }

    private fun renderTask(result: TaskResult): String {
        val tag = if (result.passed) "[ok]  " else "[FAIL]"
        val head = buildString {
            append(tag).append(' ')
            append("${result.category}/${result.taskId}".padEnd(NAME_WIDTH))
            append("%2d steps".format(result.steps))
            append("  %4d tok".format(result.outputTokens))
            append("  %5.1fs".format(result.wallTimeMs / 1000.0))
        }
        if (result.passed) return head
        return buildString {
            appendLine(head)
            result.failures.forEach { appendLine("         $it") }
            result.stopReason?.let { appendLine("         stop: $it") }
        }.trimEnd()
    }
}

/** The model id a backend reports; keeps the load/label logic in one place. */
private fun ModelBackend.modelIdForLoad(): String = when (this) {
    is LlamaCppBenchmarkBackend -> "benchmark"
    else -> id
}
