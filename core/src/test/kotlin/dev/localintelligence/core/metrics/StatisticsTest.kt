package dev.localintelligence.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The distribution functions. What matters here is that p95 is a value a run
 * actually produced, and that every edge case returns a number instead of NaN —
 * a metric layer that returns NaN is a metric layer whose output cannot be
 * written to JSON at all.
 */
class StatisticsTest {

    private fun d(vararg values: Number) = values.map { it.toDouble() }
    private fun d(values: Iterable<Number>) = values.map { it.toDouble() }

    // ------------------------------------------------------------------ empty

    @Test
    fun `an empty sample yields zero rather than NaN`() {
        val empty = emptyList<Double>()
        assertEquals(0.0, Statistics.mean(empty), 0.0)
        assertEquals(0.0, Statistics.median(empty), 0.0)
        assertEquals(0.0, Statistics.medianInterpolated(empty), 0.0)
        assertEquals(0.0, Statistics.percentile(empty, 0.95), 0.0)
        assertEquals(0.0, Statistics.min(empty), 0.0)
        assertEquals(0.0, Statistics.max(empty), 0.0)
        assertEquals(0.0, Statistics.variance(empty), 0.0)
        assertTrue(Statistics.mean(empty).isFinite())
    }

    @Test
    fun `an empty summary reports a zero count so a zero is not mistaken for a measurement`() {
        val summary = Statistics.summarize(emptyList())
        assertEquals(0, summary.count)
        assertTrue(summary.isEmpty)
        assertEquals(0.0, summary.p95, 0.0)
    }

    // ------------------------------------------------------------------ single

    @Test
    fun `a single sample is its own mean median p95 min and max`() {
        val one = d(7.0)
        assertEquals(7.0, Statistics.mean(one), 0.0)
        assertEquals(7.0, Statistics.median(one), 0.0)
        assertEquals(7.0, Statistics.percentile(one, 0.95), 0.0)
        assertEquals(7.0, Statistics.min(one), 0.0)
        assertEquals(7.0, Statistics.max(one), 0.0)
    }

    @Test
    fun `a single sample has no variance to report`() {
        assertEquals(0.0, Statistics.variance(d(7.0)), 0.0)
    }

    // ------------------------------------------------------------------ median

    @Test
    fun `median of an odd count is the middle value`() {
        // 1..5, n=5, middle index 2.
        assertEquals(3.0, Statistics.medianInterpolated(d(1, 2, 3, 4, 5)), 0.0)
        assertEquals(30.0, Statistics.medianInterpolated(d(10, 20, 30, 40, 50)), 0.0)
    }

    @Test
    fun `median of an even count averages the two central values`() {
        // 1..4, n=4, (2+3)/2. The definition of the median, not a choice.
        assertEquals(2.5, Statistics.medianInterpolated(d(1, 2, 3, 4)), 0.0)
        assertEquals(2.5, Statistics.medianInterpolated(d(4, 3, 2, 1)), 0.0)
        // Order must not matter.
        assertEquals(2.5, Statistics.medianInterpolated(d(3, 1, 4, 2)), 0.0)
        assertEquals(100.0, Statistics.medianInterpolated(d(1, 199)), 0.0)
    }

    @Test
    fun `nearest-rank median of an even count takes the lower of the two central values`() {
        // Deliberately different from the interpolated median above, and the
        // difference is the point: the two functions are not the same function.
        // Nearest rank takes the lower of the two central values (2), never 2.5.
        assertEquals(2.0, Statistics.median(d(1, 2, 3, 4)), 0.0)
        assertEquals(2.0, Statistics.percentile(d(1, 2, 3, 4), 0.5), 0.0)
    }

    // --------------------------------------------------------------------- p95

    @Test
    fun `p95 on a small set is a real observed value, not a blend`() {
        // n=4, rank = ceil(0.95*4) = 4 -> the largest sample, not a blend of the
        // 4th and 5th. A blend here would report 4.0-something that no run hit.
        assertEquals(4.0, Statistics.percentile(d(1, 2, 3, 4), 0.95), 0.0)
        // n=3, rank = ceil(2.85) = 3.
        assertEquals(3.0, Statistics.percentile(d(1, 2, 3), 0.95), 0.0)
        // n=2, rank = ceil(1.9) = 2.
        assertEquals(2.0, Statistics.percentile(d(1, 2), 0.95), 0.0)
        assertEquals(1.0, Statistics.percentile(d(1), 0.95), 0.0)
    }

    @Test
    fun `p95 on twenty samples is the 19th of twenty`() {
        // ceil(0.95 * 20) = 19.
        assertEquals(19.0, Statistics.percentile(d((1..20).toList()), 0.95), 0.0)
    }

    @Test
    fun `p95 on a hundred samples is the 95th of a hundred`() {
        // ceil(0.95 * 100) = 95. The textbook value; the whole reason this test
        // exists is that a mean-of-neighbours implementation gets 95.05 here and
        // would pass a "close enough" assertion.
        assertEquals(95.0, Statistics.percentile(d((1..100).toList()), 0.95), 0.0)
    }

    @Test
    fun `p95 is unaffected by input order`() {
        val ordered = d(5, 1, 9, 3, 7, 2, 8, 4, 6, 10)
        val shuffled = d(10, 3, 7, 1, 9, 4, 2, 8, 5, 6)
        assertEquals(Statistics.percentile(ordered, 0.95), Statistics.percentile(shuffled, 0.95), 0.0)
    }

    @Test
    fun `percentile rejects a quantile outside zero to one`() {
        val samples = d(1, 2, 3)
        for (bad in listOf(0.0, -0.1, 1.1)) {
            val thrown = runCatching { Statistics.percentile(samples, bad) }.exceptionOrNull()
            assertTrue("quantile $bad should be rejected", thrown is IllegalArgumentException)
        }
    }

    // ------------------------------------------------------- mean and variance

    @Test
    fun `mean is the arithmetic mean`() {
        assertEquals(2.0, Statistics.mean(d(1, 2, 3)), 1e-12)
        assertEquals(2.5, Statistics.mean(d(1, 2, 3, 4)), 1e-12)
    }

    @Test
    fun `variance is the unbiased n minus one estimator`() {
        // 1..4: mean 2.5, squared deviations 2.25+0.25+0.25+2.25 = 5, / 3.
        assertEquals(5.0 / 3.0, Statistics.variance(d(1, 2, 3, 4)), 1e-12)
        // A constant sample has no spread.
        assertEquals(0.0, Statistics.variance(d(3, 3, 3)), 0.0)
    }

    @Test
    fun `round4 kills false precision without changing the number`() {
        assertEquals(0.1235, Statistics.round4(0.123456789), 1e-12)
        assertEquals(-0.1235, Statistics.round4(-0.123456789), 1e-12)
        // Non-finite inputs must not reach the JSON encoder, which rejects them.
        assertEquals(0.0, Statistics.round4(Double.NaN), 0.0)
        assertEquals(0.0, Statistics.round4(Double.POSITIVE_INFINITY), 0.0)
    }

    // ----------------------------------------------------------------- summary

    @Test
    fun `summarize reports count mean median p95 min and max together`() {
        val summary = Statistics.summarize(d(1, 2, 3, 4, 100))
        assertEquals(5, summary.count)
        assertEquals(22.0, summary.mean, 1e-12)
        assertEquals(3.0, summary.median, 1e-12)
        assertEquals(100.0, summary.p95, 1e-12)
        assertEquals(1.0, summary.min, 1e-12)
        assertEquals(100.0, summary.max, 1e-12)
        assertTrue(!summary.isEmpty)
    }
}
