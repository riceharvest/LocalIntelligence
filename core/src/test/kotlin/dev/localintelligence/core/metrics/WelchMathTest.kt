package dev.localintelligence.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The statistics behind a comparison.
 *
 * A p-value implementation nobody has tested against published critical values is
 * a p-value nobody should quote. These pin the t-distribution against the standard
 * table, and pin the degenerate cases the table has nothing to say about.
 */
class WelchMathTest {

    // ------------------------------------------------- against the t table

    @Test
    fun `two-tailed p-values match the published t critical values`() {
        // t(0.95, df=10) = 2.228, t(0.99, df=10) = 3.169,
        // t(0.95, df=20) = 2.086, t(0.95, df=30) = 2.042.
        assertEquals(0.05, studentTPValue(2.228, 10.0), 5e-4)
        assertEquals(0.01, studentTPValue(3.169, 10.0), 5e-4)
        assertEquals(0.05, studentTPValue(2.086, 20.0), 5e-4)
        assertEquals(0.05, studentTPValue(2.042, 30.0), 5e-4)
    }

    @Test
    fun `a t of zero has a p-value of exactly one`() {
        assertEquals(1.0, studentTPValue(0.0, 10.0), 0.0)
    }

    @Test
    fun `the t distribution degenerates to the Cauchy at one degree of freedom`() {
        // Cauchy(0,1): P(|T| > 1) = 0.5. If the maths were wrong this would drift.
        assertEquals(0.5, studentTPValue(1.0, 1.0), 1e-6)
        assertEquals(0.0, studentTPValue(1e6, 1.0), 1e-6)
    }

    @Test
    fun `p-values stay inside zero and one for absurd inputs`() {
        for (t in listOf(0.5, 2.0, 100.0, 1e9)) {
            for (df in listOf(1.0, 2.5, 10.0, 1e4)) {
                val p = studentTPValue(t, df)
                assertTrue("t=$t df=$df gave $p", p in 0.0..1.0)
            }
        }
    }

    @Test
    fun `a zero degree of freedom is no evidence at all`() {
        assertEquals(1.0, studentTPValue(2.0, 0.0), 0.0)
        assertEquals(1.0, studentTPValue(2.0, -1.0), 0.0)
    }

    // ----------------------------------------------------- welch on real data

    @Test
    fun `identical samples are indistinguishable`() {
        val a = listOf(0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0, 0.0)
        assertEquals(1.0, welchPValue(a, a)!!, 1e-9)
        assertEquals(0.0, effectSize(a, a)!!, 1e-9)
    }

    @Test
    fun `a large, consistent shift is significant`() {
        val baseline = List(20) { 100.0 } + List(20) { 0.0 }
        val candidate = List(20) { 140.0 } + List(20) { 40.0 }
        val p = welchPValue(baseline, candidate)!!
        assertTrue("p was $p", p < 0.01)
        assertTrue(effectSize(baseline, candidate)!! > 0.5)
    }

    @Test
    fun `a shift well inside the noise is not significant`() {
        val baseline = List(20) { 100.0 } + List(20) { 0.0 }
        val candidate = List(20) { 101.0 } + List(20) { 1.0 }
        assertTrue(welchPValue(baseline, candidate)!! > 0.5)
    }

    @Test
    fun `a single sample on one side yields no p-value rather than a confident zero`() {
        assertNull(welchPValue(listOf(1.0), listOf(1.0, 2.0, 3.0)))
        assertNull(welchPValue(emptyList(), listOf(1.0, 2.0)))
        assertNull(effectSize(listOf(1.0), listOf(1.0, 2.0, 3.0)))
    }

    @Test
    fun `two constant samples that differ are a consistent difference, not noise`() {
        // Zero variance on both sides makes the t statistic undefined. Reporting
        // p=1.0 here would call "every single run got 5 points worse" unchanged.
        val baseline = List(6) { 10.0 }
        val candidate = List(6) { 12.0 }
        assertEquals(0.0, welchPValue(baseline, candidate)!!, 0.0)
        // ...and identical constants really are unchanged.
        assertEquals(1.0, welchPValue(baseline, baseline)!!, 0.0)
        // The effect size is undefined with no spread, and says so.
        assertNull(effectSize(baseline, candidate))
    }

    @Test
    fun `effect size is null when there is no pooled spread to divide by`() {
        assertNull(effectSize(List(5) { 3.0 }, List(5) { 3.0 }))
    }

    @Test
    fun `the fraction of runs at or above the best baseline run is a plain count`() {
        assertEquals(0.0, fractionOfRunsImproved(listOf(10.0, 20.0), listOf(1.0, 2.0)), 1e-9)
        assertEquals(0.5, fractionOfRunsImproved(listOf(10.0, 20.0), listOf(20.0, 5.0)), 1e-9)
        assertEquals(1.0, fractionOfRunsImproved(listOf(10.0, 20.0), listOf(25.0, 20.0)), 1e-9)
        assertEquals(0.0, fractionOfRunsImproved(emptyList(), listOf(1.0)), 0.0)
    }

    // --------------------------------------------------------- the beta helper

    @Test
    fun `the regularised incomplete beta is bounded and exact at its endpoints`() {
        assertEquals(0.0, regularizedIncompleteBeta(0.0, 2.0, 3.0), 0.0)
        assertEquals(1.0, regularizedIncompleteBeta(1.0, 2.0, 3.0), 0.0)
        // Symmetry: I_x(a,b) = 1 - I_{1-x}(b,a).
        val x = 0.37
        assertEquals(
            1.0 - regularizedIncompleteBeta(1 - x, 3.0, 2.0),
            regularizedIncompleteBeta(x, 2.0, 3.0),
            1e-12,
        )
    }

    @Test
    fun `lgamma matches published values`() {
        // lnGamma(n) == ln((n-1)!): 0, 0, ln2, ln6, ln24.
        assertEquals(0.0, lnGamma(1.0), 1e-10)
        assertEquals(0.0, lnGamma(2.0), 1e-10)
        assertEquals(0.6931471805599453, lnGamma(3.0), 1e-10)
        assertEquals(1.7917594692280550, lnGamma(4.0), 1e-10)
        assertEquals(3.1780538303479458, lnGamma(5.0), 1e-10)
        // The reflection branch, for z < 0.5.
        assertEquals(0.5723649429247001, lnGamma(0.5), 1e-9)
    }

    @Test
    fun `a computed p-value is always reportable`() {
        val p = welchPValue(listOf(1.0, 2.0, 3.0), listOf(4.0, 5.0, 6.0))
        assertNotNull(p)
        assertTrue(p!! in 0.0..1.0)
    }
}
