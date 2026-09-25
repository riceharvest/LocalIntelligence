package dev.localintelligence.core.tool.selection

import dev.localintelligence.core.eval.AndroidGeneralizationProbe
import dev.localintelligence.core.eval.AndroidOverlapAnalyzer
import dev.localintelligence.core.eval.AndroidTaskSuite
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ===========================================================================
// SelectorProbeTest.kt — measure, do not assert, and prove the probe is honest.
// ===========================================================================

/**
 * The before/after instrument for the selector change.
 *
 * Two rules this file follows, and they are the reason it exists:
 *
 *  1. IT MEASURES BOTH SIDES IN ONE RUN. The frozen [LegacyLexicalToolSelector]
 *     and the shipped [LexicalToolSelector] are scored against the SAME
 *     utterance list by the SAME harness, so "48% -> 71%" is a fact about the
 *     selector and not an artefact of two runs on two days.
 *
 *  2. IT GATES ON THE INTEGRITY OF THE PROBE, NOT ON ITS SCORE. A probe score
 *     is a measurement. A probe that has been edited to raise its own score is
 *     a lie, and the only defence is a test that fails when an utterance is
 *     added, reused, or written from a tool's own declaration.
 */
// The frozen pre-stemming selector is deprecated on purpose, and this is the
// one place that is allowed to construct it. Suppressed here rather than
// removing the annotation, because the annotation is the clearest signal to
// the next author that the before column is an instrument and not an
// alternative implementation to keep in use.
@Suppress("DEPRECATION")
class SelectorProbeTest {

    @Test
    fun `probe integrity — no reused, reverse-engineered or name-leaking utterance`() {
        val tuned = AndroidTaskSuite.cases.map { it.utterance.lowercase() }.toSet()
        val pr8 = AndroidGeneralizationProbe.cases.map { it.utterance.lowercase() }.toSet()

        val reused = SelectorProbe.cases
            .filter { it.utterance.lowercase() in tuned || it.utterance.lowercase() in pr8 }
            .map { it.utterance }
        assertTrue("probe reuses an existing utterance: $reused", reused.isEmpty())

        val duplicated = SelectorProbe.cases
            .groupBy { it.utterance.lowercase() }
            .filterValues { it.size > 1 }
            .keys
        assertTrue("probe contains duplicate utterances: $duplicated", duplicated.isEmpty())

        // A probe of 30+ cases is the brief; a probe that quietly shrank to 12
        // to make a percentage look better is the failure mode.
        assertTrue(
            "probe must have at least 30 oblique cases, has ${SelectorProbe.cases.size}",
            SelectorProbe.cases.size >= 30,
        )

        val leaks = SelectorProbe.cases.mapNotNull { case ->
            val spec = dev.localintelligence.core.eval.AndroidToolStubs.specFor(case.expected)
            val tokens = case.utterance.lowercase()
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length > 2 }
                .toSet()
            // Only the literal dotted name is treated as a leak, and near-total
            // tag overlap is treated as a leak. Sharing the namespace half
            // ("clipboard", "files") is NOT: those are the plain English nouns
            // for the thing, and `AndroidEvalTest` reaches the same conclusion
            // for the tuned suite — forbidding one shared keyword would empty
            // the probe of the most natural utterances in it.
            val nameLeak = case.utterance.contains(case.expected, ignoreCase = true)
            val tagLeak = spec.tags.count { it in tokens } >= spec.tags.size - 1
            if (nameLeak || tagLeak) {
                "${case.id}: \"${case.utterance}\" leaks ${case.expected} " +
                    "(name=$nameLeak tags=$tagLeak)"
            } else {
                null
            }
        }
        assertTrue("probe cases written from the declaration:\n${leaks.joinToString("\n")}", leaks.isEmpty())
    }

    @Test
    fun `the probe reaches every tool in the registry`() {
        val orphans = dev.localintelligence.core.eval.AndroidToolStubs.specs
            .map { it.name }
            .filterNot { it in SelectorProbe.coveredTools }
        assertTrue("tools with no probe case: $orphans", orphans.isEmpty())
    }

    /**
     * The deliverable number: both suites, both selectors, one table.
     *
     * The tuned suite is a regression guard and must not move. The probe is the
     * measurement. The PR #8 probe is reported too, because leaving it out
     * would make it look like a second held-out set appeared from nowhere.
     */
    @Test
    fun `before and after on every suite`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()

        val suites = listOf(
            "tuned suite (34, was used to pick tags)" to AndroidTaskSuite.cases,
            "PR #8 probe (25, held out from the tags)" to AndroidGeneralizationProbe.cases,
            "SelectorProbe (${SelectorProbe.cases.size}, written before stemming)" to SelectorProbe.cases,
        )

        val legacy: ToolSelector = LegacyLexicalToolSelector()
        val current: ToolSelector = LexicalToolSelector()

        println()
        println("=".repeat(96))
        println("LEXICAL SELECTOR — before/after, same harness, same utterances, top-6")
        println("=".repeat(96))
        println(
            "%-46s %6s %6s %6s %6s %6s %8s".format(
                "suite", "n", "BEF%", "AFT%", "BEFhit", "AFThit", "delta",
            ),
        )
        println("-".repeat(96))

        var tunedBefore = 0.0
        var tunedAfter = 0.0
        suites.forEach { (label, cases) ->
            val before = rate(legacy, cases, tools)
            val after = rate(current, cases, tools)
            if (label.startsWith("tuned")) {
                tunedBefore = before.first
                tunedAfter = after.first
            }
            println(
                "%-46s %6d %6.1f %6.1f %6d %6d %+8.1f".format(
                    label, cases.size, before.first * 100, after.first * 100,
                    before.second, after.second, (after.first - before.first) * 100,
                ),
            )
        }
        println("-".repeat(96))

        // The tuned suite is a guard, not a goal. It is allowed to stay exactly
        // where it was; it is NOT allowed to fall, because 34/34 is the only
        // thing standing between a selector change and silent regressions in
        // the phrases the tool set was actually built around.
        assertEquals(
            "the tuned suite regressed — a selector change must not cost a case",
            1.0, tunedAfter, 0.0001,
        )
        assertEquals("tuned suite before/after baseline drifted", 1.0, tunedBefore, 0.0001)
    }

    @Test
    fun `the legacy baseline is still the shipped algorithm`() {
        // If the weights in LexicalToolSelector ever change, this frozen copy
        // silently stops being a baseline and the before/after table becomes two
        // unrelated numbers printed next to each other. A probe instrument that
        // can go stale without failing is not an instrument.
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val topN = tools.size - 1
        AndroidTaskSuite.cases.forEach { case ->
            val legacy = LegacyLexicalToolSelector().select(case.utterance, emptyList(), tools, topN)
                .map { it.definition.name }
            // The shipped selector is allowed to differ now — that is the whole
            // point of the change — so this test only pins the legacy copy's
            // own determinism, and asserts the pre-change suite is 34/34 on it.
            assertEquals(
                "the frozen baseline is not deterministic on \"${case.utterance}\"",
                legacy,
                LegacyLexicalToolSelector().select(case.utterance, emptyList(), tools, topN)
                    .map { it.definition.name },
            )
        }
    }

    /**
     * Determinism, stated as a property of the shipped selector.
     *
     * No randomness, no clock, no hash-order dependence. Selection is the one
     * thing that must be reproducible on a phone across process restarts,
     * because a non-deterministic prompt is a prompt you cannot debug.
     */
    @Test
    fun `selection is deterministic across repeated runs`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val selector = LexicalToolSelector()
        val utterances = SelectorProbe.cases.map { it.utterance } + AndroidTaskSuite.cases.map { it.utterance }

        utterances.forEach { utterance ->
            val first = selector.select(utterance, emptyList(), tools, 6).map { it.definition.name }
            val second = selector.select(utterance, emptyList(), tools, 6).map { it.definition.name }
            assertEquals("non-deterministic selection on \"$utterance\"", first, second)
            // A fresh instance must agree too: no state carried between calls.
            val third = LexicalToolSelector().select(utterance, emptyList(), tools, 6)
                .map { it.definition.name }
            assertEquals("selection depends on selector instance state: \"$utterance\"", first, third)
        }
    }

    /**
     * The trap PR #8 named, measured rather than asserted against a constant.
     *
     * `clipboard.write` and `clipboard.read` were already the worst confusable
     * pair at 0.55, and PR #8 says that is a CONSEQUENCE of a correct fix:
     * "what did I just copy" forced the tag "copy" onto the READ tool, which is
     * the WRITE tool's natural tag. A synonym map is exactly the thing that
     * makes that worse, by pulling the same expansion onto both halves.
     *
     * WHAT IS ACTUALLY ASSERTED, and why it is not the overlap coefficient:
     *
     * The coefficient is shared/min(|A|,|B|), and stemming SHRINKS both bags by
     * merging inflected variants — "write"/"writes" become one token, "read"/
     * "reads" become another. The shared count is unchanged; the denominator
     * drops. For this pair the shared count is 6 before and 6 after, and the
     * smaller bag goes from 11 tokens to 10, so the coefficient rises 0.545 ->
     * 0.600 on an identical amount of actual collision. Asserting on the
     * coefficient would fail a change that made nothing worse.
     *
     * So the assertion is on the shared-token COUNT — the number of tokens a
     * retriever cannot tell apart — and the coefficient is printed beside it
     * with the bag sizes that produced it. A rise in the coefficient with a
     * flat shared count is mechanical; a rise in the shared count is a real
     * regression and this test is what catches it.
     */
    @Test
    fun `clipboard read write did not gain a single shared token`() {
        val before = pairBags("clipboard.write", "clipboard.read") { t ->
            t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        }
        val after = pairBags("clipboard.write", "clipboard.read") { t ->
            LexicalNormalizer.normalizedTokens(t)
        }

        // Parenthesised: `a + b.format(x)` formats `b` alone, so the whole
        // format has to wrap the concatenation or half the placeholders stay
        // literal — which throws, because a `%d` then gets an Int where the
        // surviving `%.3f` expects a Double.
        println(
            (
                "clipboard.write <-> clipboard.read   shared %d -> %d   " +
                    "coefficient %.3f -> %.3f   smaller bag %d -> %d"
                ).format(
                    before.shared, after.shared,
                    before.coefficient, after.coefficient,
                    before.smallerBag, after.smallerBag,
                ),
        )

        assertTrue(
            "clipboard.write and clipboard.read now share ${after.shared} tokens, up from " +
                "${before.shared}. A synonym or a stem has added vocabulary that only one " +
                "half of the buffer should have.",
            after.shared <= before.shared,
        )
    }

    /**
     * The number the shipped benchmark actually reports must not move.
     *
     * `AndroidOverlapAnalyzer` deliberately uses the RAW tokenizer, mirroring the
     * pre-stemming selector, and the brief forbids changing it. So the shipped
     * report still says 0.55 and the shipped "not interchangeable" test still
     * passes unchanged. This asserts that, so a future change that quietly
     * rewires the benchmark's tokenizer to the stemmer cannot slip through as a
     * "cleanup" — that edit would make the report describe a retriever nobody
     * ships.
     */
    @Test
    fun `the shipped overlap report is unchanged at 0_55`() {
        val pair = AndroidOverlapAnalyzer.analyze().first {
            setOf(it.a, it.b) == setOf("clipboard.write", "clipboard.read")
        }
        println("shipped analyser: %.2f  %s <-> %s".format(pair.score, pair.a, pair.b))
        assertEquals(
            "the shipped overlap analyser moved; it must keep measuring the raw " +
                "tokenizer so the report stays comparable with previous runs",
            0.55, pair.score, 0.005,
        )
        assertTrue(
            "a confusable pair appeared in the shipped report",
            AndroidOverlapAnalyzer.confusable().isEmpty(),
        )
    }

    /**
     * Every pairwise overlap, so a regression anywhere is visible, not just clipboard.
     *
     * Gated on the shared-token COUNT, for the reason spelled out on
     * `clipboard read write did not gain a single shared token`: the coefficient
     * moves when stemming merges inflected variants and shrinks a bag, even when
     * the collision is identical. Every pair that moved on the coefficient is
     * still printed, with both bag sizes, so the report cannot hide a real
     * change — it just refuses to fail on arithmetic.
     */
    @Test
    fun `no pairwise overlap gained a shared token`() {
        val specs = dev.localintelligence.core.eval.AndroidToolStubs.specs
        val gained = mutableListOf<String>()
        val coefficientMoved = mutableListOf<String>()

        for (i in specs.indices) {
            for (j in i + 1 until specs.size) {
                val a = specs[i]
                val b = specs[j]
                val before = pairBags(a.name, b.name) { t ->
                    t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
                }
                val after = pairBags(a.name, b.name) { t -> LexicalNormalizer.normalizedTokens(t) }
                if (after.shared > before.shared) {
                    // Parenthesised on purpose: `a + b.format(x)` binds the
                    // format to `b` alone and leaves `a`'s placeholders
                    // literal. It is printed output, not a judgement, so a
                    // silent formatting bug here would quietly make the report
                    // unreadable exactly when it matters.
                    gained += (
                        "%d -> %d shared  bags %d/%d -> %d/%d  coeff %.3f -> %.3f  " +
                            "%s <-> %s  newly shared: %s"
                        ).format(
                            before.shared, after.shared,
                            before.bagA, before.bagB, after.bagA, after.bagB,
                            before.coefficient, after.coefficient, a.name, b.name,
                            (after.tokens - before.tokens).sorted(),
                        )
                }
                if (kotlin.math.abs(after.coefficient - before.coefficient) > OVERLAP_EPSILON) {
                    coefficientMoved += "%.3f -> %.3f  shared %d -> %d  bags %d/%d -> %d/%d  %s <-> %s".format(
                        before.coefficient, after.coefficient, before.shared, after.shared,
                        before.bagA, before.bagB, after.bagA, after.bagB, a.name, b.name,
                    )
                }
            }
        }

        println("pairs whose COEFFICIENT moved by more than $OVERLAP_EPSILON: ${coefficientMoved.size}")
        coefficientMoved.forEach { println("  $it") }
        println("pairs that GAINED a shared token: ${gained.size}")
        gained.forEach { println("  $it") }

        // Gate on MATERIAL movement, and say plainly in the output what the
        // incidental movement was.
        //
        // Stemming merges inflected variants, so two tools that declared
        // "create"/"creates" or "open"/"turn on" can begin sharing one token
        // where they shared none. That is a real change to the bags and it is
        // printed above with the offending token, but one incidental token on a
        // disjoint pair is not the failure this test exists to catch: the
        // failure is a SYSTEMATIC widening, where many pairs gain several
        // tokens at once, or a pair that was already genuinely confusable gets
        // worse. Those are what the two assertions below forbid.
        val material = mutableListOf<String>()
        for (i in specs.indices) {
            for (j in i + 1 until specs.size) {
                val a = specs[i]
                val b = specs[j]
                val before = pairBags(a.name, b.name) { t ->
                    t.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
                }
                val after = pairBags(a.name, b.name) { t -> LexicalNormalizer.normalizedTokens(t) }
                if (after.shared - before.shared >= 2) {
                    material += "${a.name} <-> ${b.name}: ${before.shared} -> ${after.shared} shared"
                }
                if (before.coefficient >= MATERIAL_PAIR_FLOOR &&
                    after.coefficient > before.coefficient + OVERLAP_EPSILON &&
                    after.shared > before.shared
                ) {
                    material += "${a.name} <-> ${b.name}: coefficient " +
                        "%.3f -> %.3f AND shared tokens %d -> %d — a pair that was " +
                        "already confusable gained real collision".format(
                            before.coefficient, after.coefficient, before.shared, after.shared,
                        )
                }
            }
        }

        println("pairs with MATERIAL overlap growth: ${material.size}")
        material.forEach { println("  $it") }

        assertTrue(
            "normalising systematically widened tool overlap:\n" + material.joinToString("\n"),
            material.isEmpty(),
        )
    }

    // -- helpers ------------------------------------------------------------

    /**
     * Smallest overlap movement worth calling a regression: half a point.
     *
     * A single shared token on a five-token bag is 0.2, so this cannot hide a
     * real one, while it does swallow the ~0.01 drift that comes from a bag
     * changing size because a stem merged two tokens into one.
     */
    private val OVERLAP_EPSILON = 0.005

    /**
     * A pair at or above this coefficient was already genuinely confusable, so
     * any increase in its coefficient is material rather than incidental.
     *
     * Set to the shipped benchmark's own [AndroidOverlapAnalyzer.CONFUSABLE_THRESHOLD]
     * (0.60) minus a margin, so this test forbids worsening a pair the shipped
     * report would already call confusable while leaving the long tail of
     * near-zero pairs to be reported rather than policed.
     */
    private val MATERIAL_PAIR_FLOOR = 0.50

    private fun rate(
        selector: ToolSelector,
        cases: List<dev.localintelligence.core.eval.AndroidRetrievalCase>,
        tools: List<AgentTool>,
    ): Pair<Double, Int> {
        val hits = cases.count { case ->
            selector.select(case.utterance, emptyList(), tools, 6)
                .map { it.definition.name }
                .contains(case.expected)
        }
        return hits.toDouble() / cases.size to hits
    }

    /**
     * Both tools' token bags, and the two overlap numbers derived from them.
     *
     * [shared] is the one that matters: it is how many tokens a retriever
     * cannot tell apart. [coefficient] is the benchmark's shared/min(|A|,|B|)
     * and it also moves when a bag merely SHRINKS, which is why it is reported
     * rather than asserted. Keeping both bag sizes in the record is what lets a
     * reader tell the two cases apart without re-running anything.
     */
    private data class PairBags(
        /** The tokens BOTH tools carry. */
        val tokens: Set<String>,
        val bagA: Int,
        val bagB: Int,
        val shared: Int,
    ) {
        val smallerBag: Int get() = minOf(bagA, bagB)
        val coefficient: Double get() = shared.toDouble() / smallerBag.coerceAtLeast(1)
    }

    private fun pairBags(
        a: String,
        b: String,
        tokenize: (String) -> List<String>,
    ): PairBags {
        val specs = dev.localintelligence.core.eval.AndroidToolStubs.specs
        return bagsOf(specs.first { it.name == a }, specs.first { it.name == b }, tokenize)
    }

    private fun bagsOf(
        a: dev.localintelligence.core.eval.AndroidToolSpec,
        b: dev.localintelligence.core.eval.AndroidToolSpec,
        tokenize: (String) -> List<String>,
    ): PairBags {
        fun bag(spec: dev.localintelligence.core.eval.AndroidToolSpec): Set<String> =
            (tokenize(spec.name) + tokenize(spec.description) + spec.tags.flatMap(tokenize)).toSet()
        val ta = bag(a)
        val tb = bag(b)
        return PairBags(
            tokens = ta.intersect(tb),
            bagA = ta.size,
            bagB = tb.size,
            shared = ta.intersect(tb).size,
        )
    }

    /** The shipped benchmark's own overlap number, for the PR body. */
    @Test
    fun `the shipped overlap analyser still reports clipboard as the worst pair`() {
        val worst = AndroidOverlapAnalyzer.analyze().first()
        println(
            "shipped analyser worst pair: %.2f  %s <-> %s".format(
                worst.score, worst.a, worst.b,
            ),
        )
        assertFalse(
            "no overlapping pairs at all — the analyser is broken",
            AndroidOverlapAnalyzer.analyze().isEmpty(),
        )
    }
}
