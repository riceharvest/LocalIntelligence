package dev.localintelligence.core.metrics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The distribution functions this project needs, and nothing else.
 *
 * WHY hand-rolled rather than pulled in: a statistics library is a dependency,
 * and the one rule `:core` cannot break is staying small and JVM-pure. Median,
 * nearest-rank percentile and sample variance are the entire requirement set.
 *
 * WHY p95 is nearest-rank and not an average of two neighbours: the point of a
 * p95 on a phone is "the run that was painful". An interpolated percentile
 * returns a value that no run actually produced, so it cannot be pointed at in
 * a bug report — the worst-case latency you report is one nobody experienced.
 */
object Statistics {

    /**
     * Nearest-rank percentile. [quantile] is in `0.0..1.0`.
     *
     * rank = ceil(q * n), clamped to `1..n`, and the answer is the element at
     * that 1-based rank. For `n = 100, q = 0.95` that is the 95th smallest
     * sample — a value a run really produced — not a blend of the 95th and 96th.
     *
     * Returns 0.0 for an empty input. Callers get 0.0 rather than NaN because
     * `Json` refuses to encode NaN without opting into special float handling,
     * and a metrics file that cannot be written is worse than one reporting zero
     * for "nothing measured". Check the count, not the value, to tell the two
     * apart — [MetricSummary.count] carries it.
     */
    fun percentile(values: List<Double>, quantile: Double): Double {
        require(quantile > 0.0 && quantile <= 1.0) { "quantile must be in (0, 1], got $quantile" }
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** p50 by the same nearest-rank rule. */
    fun median(values: List<Double>): Double = percentile(values, 0.5)

    /**
     * The median, averaging the two central values on an even count.
     *
     * WHY this one interpolates when [percentile] does not: the median of an
     * even-sized sample is *defined* as the mean of the two central values —
     * anything else is not the median, it is an arbitrary choice from the
     * nearest-rank family. The p95 has no such definition, which is why it stays
     * on a real observed sample.
     */
    fun medianInterpolated(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Arithmetic mean. 0.0 for an empty input, for the same reason as [percentile]. */
    fun mean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.sum() / values.size
    }

    /** Smallest value, or 0.0 when there is none. */
    fun min(values: List<Double>): Double = values.minOrNull() ?: 0.0

    /** Largest value, or 0.0 when there is none. */
    fun max(values: List<Double>): Double = values.maxOrNull() ?: 0.0

    /**
     * Unbiased (n-1) sample variance. Returns 0.0 for fewer than two samples
     * because variance is genuinely unknown there, not zero — callers that need
     * to tell the two apart must check the sample count first, which is why
     * [welchPValue] reports `null` rather than a number in that case.
     */
    fun variance(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        val sumSquares = values.sumOf { (it - m) * (it - m) }
        return sumSquares / (values.size - 1)
    }

    fun stdDev(values: List<Double>): Double = sqrt(variance(values))

    /** Full five-number summary of one metric, in the shape the JSON schema stores. */
    fun summarize(values: List<Double>, percentile: Double = 0.95): MetricSummary {
        if (values.isEmpty()) return MetricSummary()
        return MetricSummary(
            count = values.size,
            mean = round4(mean(values)),
            median = round4(medianInterpolated(values)),
            p95 = round4(percentile(values, percentile)),
            min = round4(min(values)),
            max = round4(max(values)),
        )
    }

    /**
     * Rounds to 4 decimals.
     *
     * WHY: a p-value or a tok/s rate carries ~15 significant digits of fake
     * precision, and two commits that differ by 1e-15 then show up as a diff in
     * CI. Four decimals is far finer than any measurement this project makes.
     */
    fun round4(value: Double): Double {
        if (!value.isFinite()) return 0.0
        val scaled = value * 10_000.0
        val rounded = if (scaled >= 0) kotlin.math.floor(scaled + 0.5) else -kotlin.math.floor(-scaled + 0.5)
        return rounded / 10_000.0
    }
}

/**
 * Welch's two-sample t-test, two-tailed p-value.
 *
 * WHY Welch and not Student's: the two run sets almost never have equal variance
 * — a change that speeds up the happy path and slows the failure path is the
 * normal case — and Student's pooled-variance test silently loses validity there.
 *
 * WHY not a t-distribution table: tables are unreadable at runtime df, and a
 * dependency is not available in `:core`. The regularised incomplete beta
 * function is ~30 lines and is what any statistics library does internally.
 *
 * Returns `null` when fewer than two samples were given on a side, because the
 * variance of a single observation is not zero, it is unknown. Returning 0.0
 * would manufacture a confident verdict out of no data.
 */
fun welchPValue(baseline: List<Double>, candidate: List<Double>): Double? {
    if (baseline.size < 2 || candidate.size < 2) return null
    val v1 = Statistics.variance(baseline)
    val v2 = Statistics.variance(candidate)
    val seSquared = v1 / baseline.size + v2 / candidate.size
    val delta = Statistics.mean(candidate) - Statistics.mean(baseline)
    if (seSquared == 0.0) {
        // Zero variance on both sides. The t statistic is 0 or ±infinity, and
        // the caller decides what that means; returning 0.0 here would claim
        // "no significant difference" for two constants that differ.
        return if (delta == 0.0) 1.0 else 0.0
    }
    val t = delta / sqrt(seSquared)
    val df = (seSquared * seSquared) /
        ((v1 / baseline.size).let { it * it } / (baseline.size - 1) +
            (v2 / candidate.size).let { it * it } / (candidate.size - 1))
    val x = df / (df + t * t)
    return regularizedIncompleteBeta(x, df / 2.0, 0.5).coerceIn(0.0, 1.0)
}

/** Cohen-style standardized effect size, `(mean2 - mean1) / pooledSD`. */
fun effectSize(baseline: List<Double>, candidate: List<Double>): Double? {
    if (baseline.size < 2 || candidate.size < 2) return null
    val n1 = baseline.size
    val n2 = candidate.size
    val v1 = Statistics.variance(baseline)
    val v2 = Statistics.variance(candidate)
    val pooled = ((n1 - 1) * v1 + (n2 - 1) * v2) / (n1 + n2 - 2)
    if (pooled <= 0.0) return null
    return (Statistics.mean(candidate) - Statistics.mean(baseline)) / sqrt(pooled)
}

/** The fraction of [candidate] that is at least as good as the best of [baseline]. */
fun fractionOfRunsImproved(baseline: List<Double>, candidate: List<Double>): Double {
    if (baseline.isEmpty() || candidate.isEmpty()) return 0.0
    val best = Statistics.max(baseline)
    return candidate.count { it >= best }.toDouble() / candidate.size
}

/**
 * Two-tailed p-value for a t statistic with [df] degrees of freedom, via the
 * regularised incomplete beta function (Lentz's continued fraction).
 *
 * Exposed for its own sake: a p-value implementation you cannot test is a
 * p-value you cannot trust, so [WelchMathTest] pins it against published
 * critical values.
 */
fun studentTPValue(t: Double, df: Double): Double {
    if (df <= 0.0) return 1.0
    if (t == 0.0) return 1.0
    val x = df / (df + t * t)
    return regularizedIncompleteBeta(x, df / 2.0, 0.5).coerceIn(0.0, 1.0)
}

/** Regularised incomplete beta `I_x(a, b)`, the workhorse behind the t distribution. */
internal fun regularizedIncompleteBeta(x: Double, a: Double, b: Double): Double {
    if (x <= 0.0) return 0.0
    if (x >= 1.0) return 1.0
    val front = exp(
        lnGamma(a + b) - lnGamma(a) - lnGamma(b) +
            a * kotlin.math.ln(x) + b * kotlin.math.ln(1.0 - x),
    )
    return if (x < (a + 1.0) / (a + b + 2.0)) {
        front * betaContinuedFraction(a, b, x) / a
    } else {
        1.0 - front * betaContinuedFraction(b, a, 1.0 - x) / b
    }
}

/** Lentz's modified continued fraction. 200 iterations is past double precision. */
private fun betaContinuedFraction(a: Double, b: Double, x: Double): Double {
    val tiny = 1e-30
    val qab = a + b
    val qap = a + 1.0
    val qam = a - 1.0
    var c = 1.0
    var d = 1.0 - qab * x / qap
    if (abs(d) < tiny) d = tiny
    d = 1.0 / d
    var h = d
    for (m in 1..MAX_ITERATIONS) {
        val m2 = 2.0 * m
        var numerator = m * (b - m) * x / ((qam + m2) * (a + m2))
        d = 1.0 + numerator * d
        if (abs(d) < tiny) d = tiny
        c = 1.0 + numerator / c
        if (abs(c) < tiny) c = tiny
        d = 1.0 / d
        h *= d * c
        numerator = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
        d = 1.0 + numerator * d
        if (abs(d) < tiny) d = tiny
        c = 1.0 + numerator / c
        if (abs(c) < tiny) c = tiny
        d = 1.0 / d
        val delta = d * c
        h *= delta
        if (abs(delta - 1.0) < 3e-16) break
    }
    return h
}

/** Lanczos log-gamma, so `lgamma` is not a JDK-version gamble. */
internal fun lnGamma(z: Double): Double {
    val coefficients = doubleArrayOf(
        676.5203681218851, -1259.1392167224028, 771.32342877765313,
        -176.61502916214059, 12.507343278686905, -0.13857109526572012,
        9.9843695780195716e-6, 1.5056327351493116e-7,
    )
    if (z < 0.5) {
        // Reflection formula: gamma(z)gamma(1-z) = pi / sin(pi z)
        return kotlin.math.ln(kotlin.math.PI / kotlin.math.sin(kotlin.math.PI * z)) - lnGamma(1.0 - z)
    }
    var x = 0.99999999999980993
    val zz = z - 1.0
    for (i in coefficients.indices) x += coefficients[i] / (zz + i + 1)
    val t = zz + coefficients.size - 0.5
    return 0.5 * kotlin.math.ln(2.0 * kotlin.math.PI) + (zz + 0.5) * kotlin.math.ln(t) - t + kotlin.math.ln(x)
}

private const val MAX_ITERATIONS = 200
