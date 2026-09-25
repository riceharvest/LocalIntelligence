package dev.localintelligence.core.tool.selection

import dev.localintelligence.core.eval.AndroidRetrievalCase
import dev.localintelligence.core.tool.AgentTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ===========================================================================
// SynonymAblationTest.kt — does each entry earn its place, or is it a guess?
//
// THE BRIEF'S OWN INSTRUCTION
// ==========================
//
// "If a synonym entry does not earn its place in the probe results, DELETE it
// and say so in your report."
//
// "Earn its place" has to mean something checkable, or the sentence is a
// preference. Here it means exactly one thing: removing the entry must not
// increase hits on a HELD-OUT utterance set. This test measures that by
// rebuilding the ranking with one entry disabled at a time and re-scoring.
//
// It is printed, not asserted, on purpose for the score. The assertions are on
// the two things that must always hold: the ablation is reproducible, and
// enabling the whole table is not worse than disabling all of it. A test that
// gated on every entry being useful would push the table toward a synonym for
// every word in the tag list, which is the failure the brief warns about.
// ===========================================================================

class SynonymAblationTest {

    /** One held-out set, plus the label it is reported under. */
    private data class Suite(val name: String, val cases: List<AndroidRetrievalCase>)

    /**
     * What removing one entry cost.
     *
     * A negative cost means removing the entry IMPROVED the score, which is
     * the case the brief says must be answered with a deletion.
     */
    private data class AblationRow(
        val key: String,
        val pr8Cost: Int,
        val probeCost: Int,
    ) {
        val total: Int get() = pr8Cost + probeCost
    }

    private val tools: List<AgentTool> =
        dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()

    private fun suites(): List<Suite> = listOf(
        Suite("PR #8 probe", dev.localintelligence.core.eval.AndroidGeneralizationProbe.cases),
        Suite("SelectorProbe", SelectorProbe.cases),
    )

    /**
     * Hits over a case set, with [disabled] synonym entries suppressed.
     *
     * Suppression hands a filtered table to the same [LexicalScorer.rank] the
     * shipped selector uses, rather than mutating the shipped object. A test
     * that edited production state to measure it would leave the table
     * mutilated for every test that ran afterwards, and the resulting bug would
     * only ever surface as an unrelated failure somewhere else.
     */
    private fun hits(cases: List<AndroidRetrievalCase>, disabled: Set<String>): Int {
        val table = LexicalNormalizer.synonymTable.filterKeys { it !in disabled }
        var count = 0
        for (case in cases) {
            val selected = LexicalScorer.rank(case.utterance, emptyList(), tools, 6, table)
                .map { it.definition.name }
            if (selected.contains(case.expected)) count += 1
        }
        return count
    }

    /**
     * The deliverable table: what each entry is worth on a held-out set.
     *
     * Two held-out sets, reported separately, because they are not equally
     * trustworthy. `SelectorProbe` was written by the same author as the table,
     * so an entry "earning" a hit there is weak evidence. The PR #8 probe was
     * written by somebody else, before this change, and is the stronger signal
     * — which is exactly why it is neither extended nor reworded.
     */
    @Test
    fun `leave-one-out ablation of every synonym entry`() {
        val all = suites()
        val full: List<Int> = all.map { hits(it.cases, emptySet()) }
        val none: List<Int> = all.map { hits(it.cases, LexicalNormalizer.synonymTable.keys) }

        println()
        println("=".repeat(96))
        println("SYNONYM ABLATION — held-out hits with each entry REMOVED, vs the full table")
        println("=".repeat(96))
        for (i in all.indices) {
            println(
                "  %-16s full table %2d/%d    no synonyms at all %2d/%d".format(
                    all[i].name, full[i], all[i].cases.size, none[i], all[i].cases.size,
                ),
            )
        }
        println("-".repeat(96))
        println("  %-18s %-12s %-12s %s".format("entry removed", "PR #8 cost", "probe cost", "verdict"))
        println("-".repeat(96))

        val rows: List<AblationRow> = LexicalNormalizer.synonymKeys.sorted().map { key ->
            AblationRow(
                key = key,
                pr8Cost = full[0] - hits(all[0].cases, setOf(key)),
                probeCost = full[1] - hits(all[1].cases, setOf(key)),
            )
        }

        for (row in rows) {
            val verdict = when {
                row.total > 0 -> "EARNED (costs ${row.total} hits if removed)"
                row.total == 0 -> "neutral"
                else -> "HARMFUL (removing it GAINS ${-row.total} hits) -> DELETE"
            }
            println(
                "  %-18s %-12s %-12s %s".format(
                    row.key, "-${row.pr8Cost}", "-${row.probeCost}", verdict,
                ),
            )
        }

        println("-".repeat(96))
        val earned = rows.filter { it.total > 0 }
        val neutral = rows.filter { it.total == 0 }
        val harmful = rows.filter { it.total < 0 }
        println("  earned: ${earned.size}   neutral: ${neutral.size}   HARMFUL: ${harmful.size}")
        println("  EARNED:  ${earned.map { it.key }}")
        println("  NEUTRAL: ${neutral.map { it.key }}")
        if (harmful.isNotEmpty()) {
            println("  MUST DELETE: ${harmful.map { it.key }}")
        }
        println("=".repeat(96))
    }

    /**
     * The floor: the shipped table must beat no table at all on at least one
     * held-out set.
     *
     * If it does not, the table is not earning the resident memory it costs on
     * a phone and it should be deleted wholesale rather than trimmed.
     */
    @Test
    fun `the table as a whole is worth more than no table at all`() {
        val all = suites()
        val full: List<Int> = all.map { hits(it.cases, emptySet()) }
        val off: List<Int> = all.map { hits(it.cases, LexicalNormalizer.synonymTable.keys) }

        println("synonyms ON   PR#8 ${full[0]}/${all[0].cases.size}   probe ${full[1]}/${all[1].cases.size}")
        println("synonyms OFF  PR#8 ${off[0]}/${all[0].cases.size}   probe ${off[1]}/${all[1].cases.size}")

        assertTrue(
            "the synonym table scores no better than no synonym table on either held-out " +
                "set (PR#8 ${full[0]} vs ${off[0]}, probe ${full[1]} vs ${off[1]}). Delete it.",
            full[0] > off[0] || full[1] > off[1],
        )
    }

    /**
     * The ablation is the basis for a deletion decision, so it has to give the
     * same answer twice. An ablation that drifts is worse than none: it would
     * authorise deleting an entry that was perfectly fine.
     */
    @Test
    fun `the ablation is reproducible`() {
        val probe = SelectorProbe.cases
        assertEquals(hits(probe, emptySet()), hits(probe, emptySet()))
        assertEquals(hits(probe, setOf("nuke")), hits(probe, setOf("nuke")))
    }
}
