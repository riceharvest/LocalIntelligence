package dev.localintelligence.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comparison. The behaviour under test is not "does the arithmetic work" — it is
 * "does the tool refuse to lie". Every test here is a shape that has produced a
 * wrong conclusion in some agent project: a winner declared on three samples, a
 * story told about identical data, or a regression hidden inside a success.
 */
class RunComparisonTest {

    /** n runs with a given success pattern, and tokens that vary a little. */
    private fun runs(pattern: String, outTokens: Int = 200, decodeMs: Long = 1_000): List<RunMetrics> =
        pattern.mapIndexed { index, succeeded ->
            RunMetrics(
                runId = "run-$index",
                taskId = "task/$index",
                modelId = "fake-3b",
                success = succeeded == '1',
                steps = if (succeeded == '1') 3 else 9,
                toolCalls = if (succeeded == '1') 2 else 5,
                invalidToolCalls = if (succeeded == '1') 0 else 2,
                duplicateCalls = if (succeeded == '1') 0 else 4,
                inputTokens = outTokens * 2,
                outputTokens = outTokens,
                decodeMs = decodeMs,
                totalMs = if (succeeded == '1') 2_000L else 9_000L,
            )
        }

    private val comparer = RunComparer()

    // ------------------------------------------------------------ identical

    @Test
    fun `comparing a run set with itself reports no change`() {
        val data = runs("1101010101")
        val comparison = comparer.compare(data, data)

        assertEquals(Verdict.UNCHANGED, comparison.verdict)
        assertEquals(0.0, comparison.primary!!.absoluteDelta, 0.0)
        // Identical samples have no dispersion to test against, so p is exactly 1.
        assertEquals(1.0, comparison.primary!!.pValue!!, 0.0)
        assertFalse(comparison.isActionable)
        assertTrue(comparison.reason, comparison.reason.contains("identical"))
    }

    @Test
    fun `two disjoint but statistically identical sets also report no change`() {
        // Same distribution, different runs. A mean-only difference of zero must
        // not be dressed up as a finding.
        val comparison = comparer.compare(runs("1010101010"), runs("0101010101"))
        assertEquals(Verdict.UNCHANGED, comparison.verdict)
        assertFalse(comparison.isActionable)
    }

    @Test
    fun `an empty baseline with an empty candidate is a no change, not a crash`() {
        val comparison = comparer.compare(emptyList(), emptyList())
        assertEquals(Verdict.INSUFFICIENT_SAMPLES, comparison.verdict)
        assertTrue(comparison.reason, comparison.reason.contains("need 5"))
    }

    // ------------------------------------------------ minimum sample refusal

    @Test
    fun `three samples never produce a winner`() {
        // Baseline always fails, candidate always succeeds. On three samples that
        // looks like a 100-point improvement, and it is noise: the p-value is
        // exactly 0 only because the variance is 0, not because it is significant.
        val comparison = comparer.compare(runs("000"), runs("111"))

        assertEquals(Verdict.INSUFFICIENT_SAMPLES, comparison.verdict)
        assertFalse(comparison.isActionable)
        assertTrue(comparison.reason, comparison.reason.contains("need 5 runs per side"))
        assertTrue(comparison.reason, comparison.reason.contains("baseline=3"))
        assertNull("no verdict data may be published below the sample floor", comparison.primary)
    }

    @Test
    fun `one side being large does not rescue a small other side`() {
        val comparison = comparer.compare(runs("000"), runs("1" + "0".repeat(99)))
        assertEquals(Verdict.INSUFFICIENT_SAMPLES, comparison.verdict)
        assertTrue(comparison.reason, comparison.reason.contains("candidate=100"))
    }

    @Test
    fun `the sample floor is configurable but must be at least two`() {
        val strict = RunComparer(RunComparisonConfig(minimumSamplesPerSide = 20))
        assertEquals(Verdict.INSUFFICIENT_SAMPLES, strict.compare(runs("1010101010"), runs("1010101010")).verdict)

        val loose = RunComparer(RunComparisonConfig(minimumSamplesPerSide = 2))
        assertEquals(Verdict.UNCHANGED, loose.compare(runs("10"), runs("10")).verdict)

        val thrown = runCatching { RunComparisonConfig(minimumSamplesPerSide = 1) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `alpha is validated rather than silently accepted`() {
        assertNotNull(runCatching { RunComparisonConfig(alpha = 0.01) }.getOrNull())
        assertTrue(runCatching { RunComparisonConfig(alpha = 0.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { RunComparisonConfig(alpha = 1.0) }.exceptionOrNull() is IllegalArgumentException)
    }

    // ------------------------------------------------------ real movement

    @Test
    fun `a large consistent success-rate gain is an improvement`() {
        // 2/10 -> 9/10. A 70-point move is well past the noise floor.
        val comparison = comparer.compare(runs("0100000000"), runs("1111111001"))

        assertEquals(Verdict.IMPROVED, comparison.verdict)
        assertTrue(comparison.isActionable)
        assertTrue("p was ${comparison.primary!!.pValue}", comparison.primary!!.pValue!! < 0.05)
        assertEquals(0.7, comparison.primary!!.absoluteDelta, 1e-4)
        assertTrue(comparison.primary!!.significant)
    }

    @Test
    fun `a large consistent success-rate loss is a regression`() {
        val comparison = comparer.compare(runs("1111111001"), runs("0100000000"))

        assertEquals(Verdict.REGRESSED, comparison.verdict)
        assertTrue(comparison.isActionable)
        assertTrue(comparison.primary!!.signedImprovement < 0)
    }

    @Test
    fun `a move inside the noise is indeterminate, not an improvement`() {
        // 10/20 = 0.50 against 11/21 = 0.524. A 2.4-point move with a p of ~0.88.
        val baseline = List(20) { i -> RunMetrics(runId = "b$i", success = i % 2 == 0, outputTokens = 200) }
        val candidate = List(21) { i -> RunMetrics(runId = "c$i", success = i < 11, outputTokens = 200) }

        val comparison = comparer.compare(baseline, candidate)
        assertEquals(Verdict.INDETERMINATE, comparison.verdict)
        assertFalse(comparison.isActionable)
        assertTrue(comparison.primary!!.absoluteDelta > 0.0)
        assertTrue("p was ${comparison.primary!!.pValue}", comparison.primary!!.pValue!! > 0.05)
        assertFalse(comparison.primary!!.significant)
    }

    // ---------------------------------------------------------- the reporting

    @Test
    fun `both headline metrics and the efficiency metrics are always reported`() {
        val comparison = comparer.compare(runs("0100000000"), runs("1111111001"))
        assertNotNull(comparison.primary)
        assertNotNull(comparison.secondary)
        val names = comparison.deltas.map { it.metric }
        assertTrue(names.toString(), names.containsAll(listOf(
            "outputTokens", "steps", "totalMs", "invalidToolCalls", "duplicateCalls", "decodeTokPerSecond",
        )))
    }

    @Test
    fun `direction is carried so a lower-is-better metric reads correctly`() {
        val comparison = comparer.compare(runs("1111111001"), runs("0100000000"))
        val steps = comparison.deltas.first { it.metric == "steps" }
        assertEquals(MetricDirection.LOWER_IS_BETTER, steps.direction)
        // The candidate regressed, so it took MORE steps. The raw delta is
        // positive and the signed improvement is negative -- which is the whole
        // reason a delta carries its direction.
        assertTrue(steps.absoluteDelta > 0.0)
        assertTrue(steps.signedImprovement < 0.0)
        assertTrue(steps.describe(), steps.describe().contains("steps"))
    }

    @Test
    fun `a success win paired with a significant token regression is called out`() {
        // The candidate succeeds more often but needs 4x the tokens. Reporting
        // this as a clean win would hide the trade the secondary metric exists
        // to surface.
        val baseline = List(20) { i ->
            RunMetrics(runId = "b$i", success = i >= 10, outputTokens = 200, decodeMs = 1_000)
        }
        val candidate = List(20) { i ->
            RunMetrics(runId = "c$i", success = i >= 2, outputTokens = 800, decodeMs = 1_000)
        }
        val comparison = comparer.compare(baseline, candidate)
        assertEquals(Verdict.IMPROVED, comparison.verdict)
        assertTrue(comparison.reason, comparison.reason.contains("1k tokens regressed"))
    }

    @Test
    fun `a relative delta against a zero baseline is zero, not infinity`() {
        // Baseline generated nothing at all, so "x% more tokens" is undefined.
        val baseline = List(6) { RunMetrics(runId = "b$it", success = false, outputTokens = 0) }
        val candidate = List(6) { i -> RunMetrics(runId = "c$i", success = i < 3, outputTokens = 200) }
        val comparison = comparer.compare(baseline, candidate)
        val secondary = comparison.secondary!!
        assertEquals(0.0, secondary.baselineMean, 0.0)
        assertEquals(0.0, secondary.relativeDelta, 0.0)
        assertTrue(secondary.relativeDelta.isFinite())
    }

    @Test
    fun `the description renders both sides and states the verdict`() {
        val text = comparer.compare(runs("0100000000"), runs("1111111001")).describe()
        assertTrue(text, text.contains("baseline"))
        assertTrue(text, text.contains("candidate"))
        assertTrue(text, text.contains("verdict: IMPROVED"))
        assertTrue(text, text.contains("minimum samples per side: 5"))
    }

    @Test
    fun `comparing two run sets is the same as comparing their runs`() {
        val baseline = RunSet(label = "base", runs = runs("0100000000"))
        val candidate = RunSet(label = "cand", runs = runs("1111111001"))
        assertEquals(comparer.compare(baseline, candidate), RunComparer().compare(baseline.runs, candidate.runs))
    }

    @Test
    fun `runs that never decoded do not corrupt the throughput delta`() {
        val comparison = comparer.compare(runs("0100000000", decodeMs = 0), runs("1111111001", decodeMs = 0))
        val throughput = comparison.deltas.first { it.metric == "decodeTokPerSecond" }
        assertEquals(0, throughput.baselineN)
        assertNull("no samples, no p-value", throughput.pValue)
    }
}
