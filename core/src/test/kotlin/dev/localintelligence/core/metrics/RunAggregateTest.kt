package dev.localintelligence.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aggregation across N runs. The secondary metric is the one worth reading twice:
 * it is the project's stated reason for caring about generated tokens at all, and
 * it is the metric most easily broken by a single outlier.
 */
class RunAggregateTest {

    private fun run(
        success: Boolean = false,
        steps: Int = 2,
        toolCalls: Int = 1,
        invalid: Int = 0,
        duplicates: Int = 0,
        inTok: Int = 100,
        outTok: Int = 50,
        decodeMs: Long = 0,
        totalMs: Long = 1_000,
    ) = RunMetrics(
        runId = "r",
        success = success,
        steps = steps,
        toolCalls = toolCalls,
        invalidToolCalls = invalid,
        duplicateCalls = duplicates,
        inputTokens = inTok,
        outputTokens = outTok,
        decodeMs = decodeMs,
        totalMs = totalMs,
    )

    // ------------------------------------------------------------------ empty

    @Test
    fun `an empty run set aggregates to zeros and does not throw`() {
        val aggregate = RunAggregator.aggregate(emptyList())
        assertEquals(0, aggregate.runCount)
        assertEquals(0, aggregate.successCount)
        assertEquals(0.0, aggregate.successRate, 0.0)
        assertEquals(0.0, aggregate.successPerThousandTokens, 0.0)
        assertEquals(0, aggregate.totalOutputTokens)
        assertTrue(aggregate.steps.isEmpty)
        assertTrue(aggregate.totalMs.isEmpty)
    }

    @Test
    fun `an empty run set still carries the schema version`() {
        assertEquals(MetricsJson.SCHEMA_VERSION, RunAggregator.aggregate(emptyList()).schemaVersion)
        assertEquals(MetricsJson.SCHEMA_VERSION, RunAggregator.EMPTY.schemaVersion)
    }

    // ------------------------------------------------------------------ single

    @Test
    fun `a single run aggregates to itself`() {
        val aggregate = RunAggregator.aggregate(listOf(run(success = true, outTok = 400, steps = 3)))
        assertEquals(1, aggregate.runCount)
        assertEquals(1, aggregate.successCount)
        assertEquals(1.0, aggregate.successRate, 0.0)
        assertEquals(400, aggregate.totalOutputTokens)
        assertEquals(2.5, aggregate.successPerThousandTokens, 1e-9)
        assertEquals(3.0, aggregate.steps.mean, 0.0)
        assertEquals(3.0, aggregate.steps.p95, 0.0)
    }

    // ------------------------------------------------------------------- suite

    @Test
    fun `success rate is successes over runs`() {
        val aggregate = RunAggregator.aggregate(
            listOf(run(success = true), run(success = false), run(success = true), run(success = true)),
        )
        assertEquals(4, aggregate.runCount)
        assertEquals(3, aggregate.successCount)
        assertEquals(0.75, aggregate.successRate, 1e-9)
    }

    @Test
    fun `the secondary metric uses totals, so one cheap success cannot inflate the average`() {
        // 3 successes. One of them generated 2 tokens and "scored" 500 per 1k.
        // Averaging per-run ratios would report ~167; the aggregate ratio is
        // 3000 / (100 + 100 + 2) = 14.85, which is the honest number.
        val runs = listOf(
            run(success = true, outTok = 100),
            run(success = true, outTok = 100),
            run(success = true, outTok = 2),
        )
        val aggregate = RunAggregator.aggregate(runs)
        assertEquals(202, aggregate.totalOutputTokens)
        assertEquals(3000.0 / 202.0, aggregate.successPerThousandTokens, 1e-4)
    }

    @Test
    fun `per-metric summaries carry count mean median and a real p95`() {
        val runs = (1..10).map { run(steps = it, totalMs = it * 1_000L) }
        val aggregate = RunAggregator.aggregate(runs)
        assertEquals(10, aggregate.steps.count)
        assertEquals(5.5, aggregate.steps.mean, 1e-9)
        assertEquals(5.5, aggregate.steps.median, 1e-9)
        // ceil(0.95 * 10) = 10 -> the 10th of 10.
        assertEquals(10.0, aggregate.steps.p95, 0.0)
        assertEquals(1.0, aggregate.steps.min, 0.0)
        assertEquals(10.0, aggregate.steps.max, 0.0)
        assertEquals(5_500L, aggregate.totalMs.mean.toLong())
    }

    @Test
    fun `decode throughput skips runs that never decoded`() {
        // A run with decodeMs = 0 has no throughput; folding its 0.0 into the
        // mean would drag a real rate down by a number that does not exist.
        val runs = listOf(
            run(decodeMs = 1_000, outTok = 20), // 20 tok/s
            run(decodeMs = 1_000, outTok = 30), // 30 tok/s
            run(decodeMs = 0, outTok = 0),
        )
        val aggregate = RunAggregator.aggregate(runs)
        assertEquals(2, aggregate.decodeTokensPerSecond.count)
        assertEquals(25.0, aggregate.decodeTokensPerSecond.mean, 1e-9)
    }

    @Test
    fun `zero generated tokens yields a zero secondary metric, not infinity`() {
        val aggregate = RunAggregator.aggregate(
            listOf(run(success = true, outTok = 0), run(success = true, outTok = 0)),
        )
        assertEquals(0, aggregate.totalOutputTokens)
        assertEquals(0.0, aggregate.successPerThousandTokens, 0.0)
        assertTrue(aggregate.successPerThousandTokens.isFinite())
        // The primary metric is unaffected: both runs did succeed.
        assertEquals(1.0, aggregate.successRate, 0.0)
    }

    @Test
    fun `headline reports both metrics from docs_evals_md`() {
        val aggregate = RunAggregator.aggregate(
            listOf(run(success = true, outTok = 500), run(success = false, outTok = 500)),
            label = "baseline",
        )
        val text = aggregate.headline()
        assertTrue(text, text.contains("task success"))
        assertTrue(text, text.contains("0.500"))
        assertTrue(text, text.contains("1/2"))
        assertTrue(text, text.contains("success per 1k tok"))
    }
}
