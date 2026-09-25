package dev.localintelligence.core.metrics

import kotlinx.serialization.Serializable

/**
 * The answer a comparison gives. Five states, not two.
 *
 * WHY there is no `BETTER`/`WORSE` binary: "did this change help" has three
 * common wrong answers — a difference that is noise, a difference measured on
 * too few samples, and a difference that went the wrong way. Collapsing them
 * into "worse" is how a team learns to ignore the tool. [INSUFFICIENT_SAMPLES]
 * and [INDETERMINATE] exist to be the *default* answers.
 */
enum class Verdict {
    /** Same number, to the precision reported. */
    UNCHANGED,

    /** Moved the right way, with enough samples to believe it. */
    IMPROVED,

    /** Moved the wrong way, with enough samples to believe it. */
    REGRESSED,

    /**
     * Moved, but not by more than the noise. The honest answer to "we ran it
     * once and it looked better".
     */
    INDETERMINATE,

    /**
     * Not enough runs on one side to compute a variance. The default answer for
     * anything under [RunComparisonConfig.minimumSamplesPerSide].
     */
    INSUFFICIENT_SAMPLES,
}

/** Which direction is good for a metric. */
enum class MetricDirection {
    /** More is better: success rate, success per 1k tokens. */
    HIGHER_IS_BETTER,

    /** Less is better: tokens, steps, milliseconds, wasted calls. */
    LOWER_IS_BETTER,
}

/**
 * One metric, before and after, with the statistics that qualify the difference.
 *
 * [pValue] is nullable on purpose. A t-test on three samples produces a number,
 * and a number is exactly what a reader is tempted to quote. `null` says "there
 * is no dispersion to test against" instead of dressing a guess as a result.
 */
@Serializable
data class MetricDelta(
    val metric: String,
    val direction: MetricDirection,
    val baselineMean: Double = 0.0,
    val candidateMean: Double = 0.0,
    val baselineMedian: Double = 0.0,
    val candidateMedian: Double = 0.0,
    val baselineP95: Double = 0.0,
    val candidateP95: Double = 0.0,
    val baselineN: Int = 0,
    val candidateN: Int = 0,
    /** candidateMean - baselineMean, signed. */
    val absoluteDelta: Double = 0.0,
    /**
     * absoluteDelta / baselineMean, or 0.0 when the baseline mean is 0.
     *
     * Null-by-zero rather than Infinity: a metric going from 0 to 5 has no
     * meaningful percentage, and printing `Infinity%` in a PR body is how a
     * team learns to skim past the comparison output.
     */
    val relativeDelta: Double = 0.0,
    /** Two-tailed Welch p-value, null when it could not be computed. */
    val pValue: Double? = null,
    /** Standardised effect size, null when there is no pooled spread. */
    val effectSize: Double? = null,
    /** Share of candidate runs that match or beat the best baseline run. */
    val fractionAtOrAboveBestBaseline: Double = 0.0,
    val significant: Boolean = false,
) {
    /** Positive when the change is good, whatever the metric's raw direction. */
    val signedImprovement: Double
        get() = when (direction) {
            MetricDirection.HIGHER_IS_BETTER -> absoluteDelta
            MetricDirection.LOWER_IS_BETTER -> -absoluteDelta
        }

    /** One line for a CI log. */
    fun describe(): String {
        val arrow = when {
            absoluteDelta == 0.0 -> "  ==  "
            signedImprovement > 0 -> "  ->  "
            else -> "  <-  "
        }
        val p = pValue?.let { "  p=%.4f".format(it) } ?: "  p=n/a"
        return "%-22s %9.3f %s %9.3f%s%s".format(metric, baselineMean, arrow, candidateMean, p, sig())
    }

    private fun sig(): String = if (significant) "  *" else ""
}

/** Thresholds for a comparison. Exposed so CI can widen them deliberately, not by accident. */
data class RunComparisonConfig(
    /**
     * Runs required on each side before any winner is declared.
     *
     * WHY 5: below it, one flipped run moves a success rate by 20 points, and a
     * 20-point swing is exactly the size of the effects being chased. A CI job
     * that declares "IMPROVED" on 3 runs is worse than no CI job, because it
     * manufactures a green light for noise. Override it only with a number.
     */
    val minimumSamplesPerSide: Int = 5,
    /** Two-tailed significance threshold. 0.05 is the conventional default. */
    val alpha: Double = 0.05,
) {
    init {
        require(minimumSamplesPerSide >= 2) { "a t-test needs at least 2 samples per side" }
        require(alpha > 0.0 && alpha < 1.0) { "alpha must be in (0, 1), got $alpha" }
    }
}

/** The full result. Serializable, so CI can publish it as an artifact. */
@Serializable
data class RunComparison(
    val schemaVersion: Int = MetricsJson.SCHEMA_VERSION,
    val verdict: Verdict = Verdict.INSUFFICIENT_SAMPLES,
    val reason: String = "",
    val minimumSamplesPerSide: Int = 5,
    val alpha: Double = 0.05,
    val baselineLabel: String = "",
    val candidateLabel: String = "",
    val baselineRunCount: Int = 0,
    val candidateRunCount: Int = 0,
    val primary: MetricDelta? = null,
    val secondary: MetricDelta? = null,
    val deltas: List<MetricDelta> = emptyList(),
) {
    /**
     * `true` only when this comparison is entitled to gate a merge.
     *
     * WHY gate on this rather than on `verdict != UNCHANGED`: a regression that
     * is indistinguishable from noise is not a merge blocker, and a
     * non-significant improvement is not a merge approver. The gate is explicit
     * so "is this a real change" is answered in code and not in a reviewer's
     * judgement call at 2am.
     */
    val isActionable: Boolean
        get() = verdict == Verdict.IMPROVED || verdict == Verdict.REGRESSED

    fun describe(): String = buildString {
        appendLine("baseline  $baselineLabel  ($baselineRunCount runs)")
        appendLine("candidate $candidateLabel  ($candidateRunCount runs)")
        appendLine()
        primary?.let { appendLine("  primary   ${it.describe()}") }
        secondary?.let { appendLine("  secondary ${it.describe()}") }
        deltas.forEach { appendLine("  ${it.describe()}") }
        appendLine()
        appendLine("verdict: $verdict — $reason")
        append("minimum samples per side: $minimumSamplesPerSide (alpha $alpha)")
    }
}

/**
 * Compares two sets of runs and says whether the difference means anything.
 *
 * The rules, in order:
 *  1. Fewer than [RunComparisonConfig.minimumSamplesPerSide] on either side is
 *     [Verdict.INSUFFICIENT_SAMPLES], full stop. No "promising", no "preliminary".
 *  2. A primary delta of exactly 0.0 is [Verdict.UNCHANGED] regardless of
 *     anything else — identical data must never produce a story.
 *  3. Otherwise the primary metric decides, and only if its p-value clears
 *     [RunComparisonConfig.alpha]. A real-looking delta that fails the test is
 *     [Verdict.INDETERMINATE].
 *  4. The secondary metric is always reported, and a primary win that moves the
 *     secondary the wrong way is called out in [RunComparison.reason] rather
 *     than being quietly averaged away.
 *
 * WHY the primary metric decides alone: `docs/evals.md` fixes task success as
 * primary. A tool that quietly substituted a metric of its own choosing would be
 * the tool that decides what "better" means, and then every comparison it makes
 * is unfalsifiable.
 */
class RunComparer(private val config: RunComparisonConfig = RunComparisonConfig()) {

    fun compare(baseline: List<RunMetrics>, candidate: List<RunMetrics>): RunComparison {
        val baselineLabel = baseline.firstOrNull()?.modelId.orEmpty()
        val candidateLabel = candidate.firstOrNull()?.modelId.orEmpty()

        if (baseline.size < config.minimumSamplesPerSide || candidate.size < config.minimumSamplesPerSide) {
            return RunComparison(
                verdict = Verdict.INSUFFICIENT_SAMPLES,
                reason = "need ${config.minimumSamplesPerSide} runs per side, " +
                    "got baseline=${baseline.size}, candidate=${candidate.size}",
                minimumSamplesPerSide = config.minimumSamplesPerSide,
                alpha = config.alpha,
                baselineLabel = baselineLabel,
                candidateLabel = candidateLabel,
                baselineRunCount = baseline.size,
                candidateRunCount = candidate.size,
            )
        }

        val primary = delta(
            "successRate",
            MetricDirection.HIGHER_IS_BETTER,
            baseline.map { if (it.success) 1.0 else 0.0 },
            candidate.map { if (it.success) 1.0 else 0.0 },
        )
        val secondary = delta(
            "successPer1kTokens",
            MetricDirection.HIGHER_IS_BETTER,
            baseline.map { it.successPerThousandTokens },
            candidate.map { it.successPerThousandTokens },
        )
        val deltas = listOf(
            delta("outputTokens", MetricDirection.LOWER_IS_BETTER,
                baseline.map { it.outputTokens.toDouble() }, candidate.map { it.outputTokens.toDouble() }),
            delta("steps", MetricDirection.LOWER_IS_BETTER,
                baseline.map { it.steps.toDouble() }, candidate.map { it.steps.toDouble() }),
            delta("totalMs", MetricDirection.LOWER_IS_BETTER,
                baseline.map { it.totalMs.toDouble() }, candidate.map { it.totalMs.toDouble() }),
            delta("invalidToolCalls", MetricDirection.LOWER_IS_BETTER,
                baseline.map { it.invalidToolCalls.toDouble() }, candidate.map { it.invalidToolCalls.toDouble() }),
            delta("duplicateCalls", MetricDirection.LOWER_IS_BETTER,
                baseline.map { it.duplicateCalls.toDouble() }, candidate.map { it.duplicateCalls.toDouble() }),
            delta("decodeTokPerSecond", MetricDirection.HIGHER_IS_BETTER,
                baseline.filter { it.decodeMs > 0 }.map { it.decodeTokensPerSecond },
                candidate.filter { it.decodeMs > 0 }.map { it.decodeTokensPerSecond }),
        )

        val verdict: Verdict
        val reason: String
        when {
            primary.absoluteDelta == 0.0 -> {
                verdict = Verdict.UNCHANGED
                reason = "success rate is identical at the reported precision"
            }

            !primary.significant -> {
                verdict = Verdict.INDETERMINATE
                reason = "success rate moved ${signed(primary.absoluteDelta, primary.direction)} " +
                    "but p=${p(primary)} is not below alpha ${config.alpha}"
            }

            primary.signedImprovement > 0 -> {
                verdict = Verdict.IMPROVED
                reason = "success rate ${signed(primary.absoluteDelta, primary.direction)} " +
                    "with p=${p(primary)} below alpha ${config.alpha}"
            }

            else -> {
                verdict = Verdict.REGRESSED
                reason = "success rate ${signed(primary.absoluteDelta, primary.direction)} " +
                    "with p=${p(primary)} below alpha ${config.alpha}"
            }
        }

        val caveat = secondary.takeIf {
            it.significant && it.signedImprovement < 0.0
        }?.let { " (but success per 1k tokens regressed significantly — check this is a trade, not a regression)" }
            .orEmpty()

        return RunComparison(
            verdict = verdict,
            reason = reason + caveat,
            minimumSamplesPerSide = config.minimumSamplesPerSide,
            alpha = config.alpha,
            baselineLabel = baselineLabel,
            candidateLabel = candidateLabel,
            baselineRunCount = baseline.size,
            candidateRunCount = candidate.size,
            primary = primary,
            secondary = secondary,
            deltas = deltas,
        )
    }

    fun compare(baseline: RunSet, candidate: RunSet): RunComparison =
        compare(baseline.runs, candidate.runs)

    private fun delta(
        name: String,
        direction: MetricDirection,
        baseline: List<Double>,
        candidate: List<Double>,
    ): MetricDelta {
        if (baseline.isEmpty() || candidate.isEmpty()) return MetricDelta(metric = name, direction = direction)
        val absolute = Statistics.mean(candidate) - Statistics.mean(baseline)
        val p = welchPValue(baseline, candidate)
        return MetricDelta(
            metric = name,
            direction = direction,
            baselineMean = Statistics.round4(Statistics.mean(baseline)),
            candidateMean = Statistics.round4(Statistics.mean(candidate)),
            baselineMedian = Statistics.round4(Statistics.medianInterpolated(baseline)),
            candidateMedian = Statistics.round4(Statistics.medianInterpolated(candidate)),
            baselineP95 = Statistics.round4(Statistics.percentile(baseline, 0.95)),
            candidateP95 = Statistics.round4(Statistics.percentile(candidate, 0.95)),
            baselineN = baseline.size,
            candidateN = candidate.size,
            absoluteDelta = Statistics.round4(absolute),
            relativeDelta = Statistics.round4(
                if (Statistics.mean(baseline) == 0.0) 0.0 else absolute / Statistics.mean(baseline),
            ),
            pValue = p,
            effectSize = effectSize(baseline, candidate)?.let { Statistics.round4(it) },
            fractionAtOrAboveBestBaseline = Statistics.round4(fractionOfRunsImproved(baseline, candidate)),
            significant = p != null && p < config.alpha,
        )
    }

    private fun signed(value: Double, direction: MetricDirection): String = when {
        value == 0.0 -> "by 0"
        direction == MetricDirection.HIGHER_IS_BETTER && value > 0 -> "up by %.4f".format(value)
        direction == MetricDirection.HIGHER_IS_BETTER -> "down by %.4f".format(-value)
        value > 0 -> "up by %.4f (worse)".format(value)
        else -> "down by %.4f (better)".format(-value)
    }

    private fun p(delta: MetricDelta): String = delta.pValue?.let { "%.4f".format(it) } ?: "n/a"
}
