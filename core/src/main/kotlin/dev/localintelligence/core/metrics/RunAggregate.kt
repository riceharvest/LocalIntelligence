package dev.localintelligence.core.metrics

import kotlinx.serialization.Serializable

/**
 * The five numbers that describe one metric across N runs.
 *
 * WHY a shape instead of a `Map<String, Double>`: a map has no schema, so a
 * typo in a key silently produces a missing stat and a green run. These are
 * fields, they are serialised, and a typo is a compile error.
 *
 * All five are 0.0 when [count] is 0. [count] is the honesty flag: a p95 of
 * 0.0 over 40 samples and a p95 of 0.0 over none are different facts, and only
 * one of them should be quoted in a PR body.
 */
@Serializable
data class MetricSummary(
    val count: Int = 0,
    val mean: Double = 0.0,
    val median: Double = 0.0,
    val p95: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 0.0,
) {
    val isEmpty: Boolean get() = count == 0
}

/**
 * The headline numbers for a whole set of runs, and nothing else.
 *
 * This is the type CI diffs between two commits. It is derived data, always
 * recomputed from [RunSet.runs] — never stored next to them — because two
 * numbers that can disagree are worse than one number.
 */
@Serializable
data class RunAggregate(
    val schemaVersion: Int = MetricsJson.SCHEMA_VERSION,
    val label: String = "",
    val runCount: Int = 0,
    val successCount: Int = 0,
    /** Primary metric: `docs/evals.md` task success. */
    val successRate: Double = 0.0,
    /** Secondary metric: task success per 1,000 model-generated tokens. */
    val successPerThousandTokens: Double = 0.0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val steps: MetricSummary = MetricSummary(),
    val toolCalls: MetricSummary = MetricSummary(),
    val invalidToolCalls: MetricSummary = MetricSummary(),
    val duplicateCalls: MetricSummary = MetricSummary(),
    val outputTokens: MetricSummary = MetricSummary(),
    val totalMs: MetricSummary = MetricSummary(),
    val decodeTokensPerSecond: MetricSummary = MetricSummary(),
) {
    /** The two numbers from `docs/evals.md`, in report order. */
    fun headline(): String = buildString {
        appendLine("primary:   task success          ${"%.3f".format(successRate)}  ($successCount/$runCount)")
        append(
            "secondary: success per 1k tok   ${"%.1f".format(successPerThousandTokens)}",
        )
    }
}

/**
 * Builds a [RunAggregate] from a set of runs.
 *
 * WHY the secondary metric is computed on *totals* rather than averaged from
 * per-run ratios: one run that generated 2 tokens and succeeded would otherwise
 * contribute 500 and drag the mean of a 20-run suite up by 25, making a broken
 * release look like the best one yet. `passed / totalTokens` is the only
 * aggregation that cannot be gamed by a single outlier.
 */
object RunAggregator {

    /** The empty-set aggregate: all zeros, zero runs. Never throws. */
    val EMPTY: RunAggregate = RunAggregate(label = "")

    fun aggregate(runs: List<RunMetrics>, label: String = "", percentile: Double = 0.95): RunAggregate {
        if (runs.isEmpty()) return RunAggregate(schemaVersion = MetricsJson.SCHEMA_VERSION, label = label)

        val successes = runs.count { it.success }
        val totalOutput = runs.sumOf { it.outputTokens }
        return RunAggregate(
            schemaVersion = MetricsJson.SCHEMA_VERSION,
            label = label,
            runCount = runs.size,
            successCount = successes,
            successRate = Statistics.round4(successes.toDouble() / runs.size),
            successPerThousandTokens = Statistics.round4(
                if (totalOutput <= 0) 0.0 else successes * 1000.0 / totalOutput,
            ),
            totalInputTokens = runs.sumOf { it.inputTokens },
            totalOutputTokens = totalOutput,
            steps = Statistics.summarize(runs.map { it.steps.toDouble() }, percentile),
            toolCalls = Statistics.summarize(runs.map { it.toolCalls.toDouble() }, percentile),
            invalidToolCalls = Statistics.summarize(runs.map { it.invalidToolCalls.toDouble() }, percentile),
            duplicateCalls = Statistics.summarize(runs.map { it.duplicateCalls.toDouble() }, percentile),
            outputTokens = Statistics.summarize(runs.map { it.outputTokens.toDouble() }, percentile),
            totalMs = Statistics.summarize(runs.map { it.totalMs.toDouble() }, percentile),
            decodeTokensPerSecond = Statistics.summarize(
                runs.filter { it.decodeMs > 0 }.map { it.decodeTokensPerSecond }, percentile,
            ),
        )
    }
}
