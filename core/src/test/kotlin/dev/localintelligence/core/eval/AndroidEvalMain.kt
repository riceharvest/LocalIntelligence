package dev.localintelligence.core.eval

import kotlin.system.exitProcess

// ===========================================================================
// AndroidEvalMain.kt — run the benchmark by hand.
//
//   ./gradlew :core:androidEvals
//
// The task itself is registered in `core/build.gradle.kts`. If this branch is
// merged alongside a change to that file, the task name is `androidEvals` and
// the main class is `dev.localintelligence.core.eval.AndroidEvalMainKt`.
//
// The same report is produced, and the same exit code returned, by the JUnit
// test `AndroidEvalTest`, so CI and a developer at a terminal are looking at
// identical output. Printing is a convenience; the test is the gate.
// ===========================================================================

/**
 * Prints the coherence and retrieval report in plain text.
 *
 * Exits 0 on pass, 1 on fail, so this is usable as a CI step on its own.
 * `--verbose` adds the full selection for every case, which is what you want
 * when a single tool is under-retrieved and you need to see its neighbours.
 */
fun main(args: Array<String>) {
    val verbose = args.any { it == "--verbose" || it == "-v" }

    val report = AndroidEvalReport.build()
    print(AndroidEvalReportRenderer.render(report))

    if (verbose) {
        println()
        println("FULL SELECTIONS")
        println("-".repeat(78))
        report.retrieval.outcomes.forEach { outcome ->
            val mark = if (outcome.hit) "ok  " else "MISS"
            println(
                "  [$mark] ${outcome.case.id.padEnd(NAME_WIDTH)} " +
                    "score=${outcome.expectedScore.toString().padStart(3)} " +
                    "rank=${(outcome.rank?.toString() ?: "-").padStart(2)}  " +
                    outcome.selected.joinToString(", ")
            )
        }
    }

    if (!report.passed) {
        println()
        println("Tool set is not shippable. Fix the violations above before shipping.")
        exitProcess(1)
    }
}

private const val NAME_WIDTH = 30
