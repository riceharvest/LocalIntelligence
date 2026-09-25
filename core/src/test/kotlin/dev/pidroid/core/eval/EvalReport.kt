package dev.pidroid.core.eval


/** One tool invocation the loop made, whether or not it was accepted. */
data class ExecutedCall(val name: String, val args: dev.pidroid.core.model.ToolArgs)

/** A call the validator refused before execution. */
data class RejectedCall(val name: String, val args: dev.pidroid.core.model.ToolArgs, val observation: String)

/**
 * Everything one task run produced. Built by the loop runner, consumed by
 * [EvalScorer] and [EvalReport].
 */
data class CompletedRun(
    val steps: Int,
    val attemptedCalls: List<ExecutedCall>,
    val executedCalls: List<ExecutedCall>,
    /**
     * Calls whose tool was actually invoked, whether or not it succeeded.
     *
     * Distinct from [executedCalls], which only holds successful ones. A denied
     * permission is an invocation that returned a failure, and "how many times
     * did the runtime re-run this tool" must be answerable — that is the
     * question a retry bug lives or dies on.
     */
    val invokedCalls: List<ExecutedCall>,
    val rejectedCalls: List<RejectedCall>,
    val prompts: List<String>,
    val finalAnswer: String?,
    val stopReason: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalMs: Long,
    val peakRamBytes: Long,
    /** Non-null when the loop paused for user confirmation on a risky tool. */
    val awaitingConfirmationFor: String? = null,
    /** Tools that returned a failed result. */
    val failedTools: List<String> = emptyList(),
    /** The largest observation the loop handed to the model, post-truncation. */
    val maxObservationChars: Int = 0,
) {
    val toolCallCount: Int get() = executedCalls.size

    /**
     * Duplicate calls, counted over what the MODEL asked for, not over what
     * executed.
     *
     * This distinction is the whole point. A retry storm shows up here as 13
     * duplicates even though the loop detector correctly executed the call once.
     * Hiding that behind an execution count would make a working loop detector
     * look like a model that never loops, and the metric would stop measuring
     * the model at all.
     */
    val duplicateCalls: Int
        get() = attemptedCalls.groupBy { it.name to canonicalArgs(it.args) }
            .values.sumOf { (it.size - 1).coerceAtLeast(0) }

    /** How many times the loop detector actually refused to re-execute a call. */
    val blockedCalls: Int get() = rejectedCalls.count { it.observation.contains("already tried", true) || it.observation.contains("loop blocked", true) }

    private fun canonicalArgs(args: dev.pidroid.core.model.ToolArgs): String =
        args.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}=${it.value}" }
}

/** Aggregate of a whole suite run. */
data class SuiteReport(
    val outcomes: List<TaskOutcome>,
    val totalMs: Long,
    val peakRamBytes: Long,
) {
    /** Diagnostic only: total wall time of the whole suite. */
    val total: Int get() = outcomes.size
    val passed: Int get() = outcomes.count { it.passed }
    val failed: Int get() = total - passed
    val successRate: Double get() = if (total == 0) 0.0 else passed.toDouble() / total

    val meanSteps: Double
        get() = if (outcomes.isEmpty()) 0.0 else outcomes.sumOf { it.metrics.steps }.toDouble() / outcomes.size

    val meanTokens: Double
        get() = if (outcomes.isEmpty()) 0.0
        else outcomes.sumOf { it.metrics.outputTokens }.toDouble() / outcomes.size

    val meanSeconds: Double
        get() = if (outcomes.isEmpty()) 0.0
        else outcomes.sumOf { it.metrics.totalMs }.toDouble() / outcomes.size / 1000.0

    val totalOutputTokens: Int get() = outcomes.sumOf { it.metrics.outputTokens }

    /**
     * Secondary metric from docs/evals.md: task success per 1k generated tokens.
     * Model-size independent, which is what makes it comparable across
     * quantizations, architectures and devices.
     */
    val successPerThousandTokens: Double
        get() = if (totalOutputTokens <= 0) 0.0 else passed * 1000.0 / totalOutputTokens

    fun byCategory(category: TaskCategory): List<TaskOutcome> =
        outcomes.filter { it.task.category == category }

    fun categoryStats(): List<CategoryStat> = TaskCategory.entries.map { category ->
        val subset = byCategory(category)
        CategoryStat(
            category = category,
            total = subset.size,
            passed = subset.count { it.passed },
            meanSteps = if (subset.isEmpty()) 0.0
            else subset.sumOf { it.metrics.steps }.toDouble() / subset.size,
        )
    }

    fun categoryCounts(): Map<TaskCategory, Int> =
        TaskCategory.entries.associateWith { byCategory(it).size }
}

data class CategoryStat(
    val category: TaskCategory,
    val total: Int,
    val passed: Int,
    val meanSteps: Double,
)

/**
 * Plain-text reporter. No JSON, no colour, no dependency on a TTY.
 *
 * Format is fixed by docs/evals.md:
 *   [ok]   single/battery-level        2 steps  38 tok   1.2s
 *   [FAIL] failure/permission-denied  14 steps 380 tok  22.1s
 *            LOOP: calendar.search repeated 14x
 * One line per task, then the summary, then the two headline numbers.
 */
object EvalReport {

    private const val NAME_WIDTH = 46

    fun render(report: SuiteReport): String = buildString {
        report.outcomes.forEach { outcome -> appendLine(renderTask(outcome)) }

        appendLine()
        appendLine(
            "%d tasks | %d pass (%d%%) | mean %.1f steps | mean %.0f tok | mean %.1fs".format(
                report.total,
                report.passed,
                (report.successRate * 100).toInt(),
                report.meanSteps,
                report.meanTokens,
                report.meanSeconds,
            )
        )
        appendLine("primary:   task success            %.2f".format(report.successRate))
        appendLine(
            "secondary: success per 1k tokens   %.1f tasks".format(report.successPerThousandTokens)
        )

        val counts = report.categoryCounts()
        val countsOk = TaskCategory.entries.all { counts[it] == it.expectedCount }
        appendLine()
        appendLine(
            if (countsOk) "category counts: as specified" else
                "category counts: MISMATCH " + TaskCategory.entries.joinToString(", ") {
                    "${it.slug}=${counts[it] ?: 0}/${it.expectedCount}"
                }
        )
        report.categoryStats().filter { it.total > 0 }.forEach { stat ->
            appendLine(
                "  %-10s %2d/%2d  mean %.1f steps".format(
                    stat.category.slug,
                    stat.passed,
                    stat.total,
                    stat.meanSteps,
                )
            )
        }
    }

    private fun renderTask(outcome: TaskOutcome): String {
        val tag = if (outcome.passed) "[ok]  " else "[FAIL]"
        val metrics = outcome.metrics
        val head = buildString {
            append(tag)
            append(' ')
            append(outcome.task.slug.padEnd(NAME_WIDTH))
            append("%2d steps".format(metrics.steps))
            append("  %4d tok".format(metrics.outputTokens))
            append("  %5.1fs".format(metrics.totalMs / 1000.0))
        }
        if (outcome.passed) return head

        // Failures are indented under their task, and each is on its own line:
        // a one-line failure report is a failure report nobody reads.
        return buildString {
            appendLine(head)
            outcome.failures.forEach { appendLine("         $it") }
            outcome.stopReason?.let { appendLine("         stop: $it") }
        }.trimEnd()
    }
}
