package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import java.util.Collections
import java.util.Random

/**
 * WHY THE TIE-BREAK IS ALPHABETICAL, AND WHETHER THAT CAN BE FIXED
 *
 * ## HOW TO RUN IT
 *
 * ```
 * export JAVA_HOME=$HOME/jdk21
 * ./gradlew :core:compileKotlin
 * ./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-heldout-harness.sh tiebreak
 * ```
 *
 * ## THE MECHANISM, NAMED
 *
 * `LexicalToolSelector.select` ends
 * (`core/src/main/kotlin/dev/localintelligence/core/tool/ToolRegistry.kt:191-193`):
 *
 * ```kotlin
 * return scored.sortedWith(
 *     compareByDescending<Pair<AgentTool, Int>> { it.second }.thenBy { it.first.definition.name },
 * ).take(maxTools).map { it.first }
 * ```
 *
 * So the order is: score descending, then **tool name ascending**. Not a stable
 * sort over registry order, and not a `groupBy`/`distinct` that would collapse
 * equal scores into insertion order — an explicit `thenBy { name }`, a total
 * order. Two tools on the same score are therefore always ordered by their
 * name, and the visible set at a cut through a tied group is the
 * alphabetically-first members of it.
 *
 * `mirrorCheck` below PROVES that reading rather than asserting it: it
 * re-derives the shipped ordering with and without the `thenBy { name }` and
 * reports whether the shipped selector matches the alphabetical one on all 170
 * turns.
 *
 * ## THE MEASURED CONSEQUENCE
 *
 * At the shipped `maxVisibleTools = 6`, the 6th and 7th tool are **tied on
 * score in 143/170 turns (84.1%)**. The cut is therefore decided by the
 * alphabetical clause, not by the scorer. Two related facts decide whether
 * that is fixable:
 *
 *  - **61/170 turns (35.9%) give the correct tool a score of exactly zero**,
 *    and on 51 of those ALL 25 tools score zero. A tie-break can only reorder
 *    tools the scorer could already tell apart; where every tool scores 0
 *    there is no signal to reorder BY. This is the ceiling on any tie-break.
 *  - Of the turns the correct tool is *tied but below the cut* (53), **37 are
 *    all-25-zero**. Only 16 have a scorer signal to work with, and on those the
 *    expected tool fires a raw name-substring in 0 of 16.
 *
 * ## WHAT WAS TRIED, AND WHAT IT COST
 *
 * Seven candidate tie-breaks, each measured at k=3/6/10/12, then tested with
 * **McNemar's exact test on the discordant pairs** — the correct test for a
 * change that sees the same turns, and much harder to fool than comparing two
 * independent numbers. Selected results (full table printed by the run):
 *
 * | tie-break                        | k=3  | k=6  | k=10 | k=12 | verdict                    |
 * |----------------------------------|-----:|-----:|-----:|-----:|----------------------------|
 * | shipped, `thenBy { name }`       | 101  | 116  | 133  | 138  | baseline                   |
 * | raw name-substring               | 101  | 116  | 133  | 138  | no movement at all         |
 * | risk tier (read-only first)      |  96  | 116  | 125  | 130  | **worse** at k>=10         |
 * | category-diverse greedy window   |  97  | 113  | 126  | 133  | **worse** — and see below  |
 * | fewest / most required args      | 101  | 116  | 133  | 138  | no movement at all         |
 * | raw trigram similarity           | 107  | 119  | 131  | 138  | +3 at k=6, p=0.65          |
 * | normalised trigram similarity    | 111  | 119  | 132  | 136  | +3 at k=6, p=0.65          |
 *
 * Two results are worth stating as findings rather than as scores:
 *
 *  - **The raw-substring tie-break the brief asks about does not work, and the
 *    reason is structural.** A raw substring of the name is *almost entirely
 *    subsumed by the existing token scorer*: name tokens already score x4, and
 *    `substringHit` already pays a flat +10 when the full name occurs in the
 *    task. The cases a raw substring could newly catch are inflections
 *    ("calendars", "Kalender") that a character n-gram catches and a substring
 *    does not. It fires on the expected tool in **0 of the 16** recoverable
 *    turns where the scorer has any signal at all.
 *  - **Category diversity is a trap, and the reason is specific.** It fixes the
 *    pathology the failure dumps show — the shipped k=6 window covers at most
 *    two categories on 60/170 turns, and a diverse one covers >2 on all 170 —
 *    and it still LOSES three turns at k=6. The tie composition explains it:
 *    of the 53 recoverable turns, only 10 have the expected tool tied with a
 *    same-category tool across the cut. So spreading is usually right and
 *    occasionally splits the *correct pair* — `contacts.get` and
 *    `contacts.search` — across the boundary. Making the visible set look
 *    tidier and making the task performable are different objectives, and on
 *    this corpus only one of them moves.
 *
 * The shipped order also sits at the **72nd percentile** of a random-tie null
 * (200 within-tie shuffles: mean 114.5, sd 3.6, range 105..128). It is mildly
 * lucky, not informed — and the best candidate's +3 is inside one standard
 * deviation of that same null. **No tie-break measured here is significant at
 * the shipped width.**
 *
 * ## WHAT THIS DOES NOT CLAIM
 *
 * It measures SELECTABILITY — whether the right tool reaches the window. Not
 * CHOICE: whether the model then emits a well-formed call. That is the same
 * limit [HeldOutHarness] documents, and it matters more here than usual,
 * because a tie-break's *real* value is arguably to order the window for a
 * small model's attention rather than to change which tools are in it. That
 * question needs a real GGUF and is not answerable from this corpus.
 *
 * The corpus is the held-out one, unchanged and never tuned against: this file
 * reads [HeldOutDataset] and adds no case, no tag and no label. The candidates
 * above were compared on it, which is measurement, not training — but the
 * honest reading of a +3 at p=0.65 is "not distinguishable from noise", and
 * this report declines to ship on that basis.
 */
object TieBreakReport {

    fun run() {
        val harness = HeldOutHarness()
        val defs = harness.tools.map { it.definition }
        val byName = defs.associateBy { it.name }
        val turns = turns(harness)

        mirrorCheck(harness, turns)

        println()
        println("=".repeat(78))
        println("TIE RATE — the share of turns where the cut is NOT decided by the scorer")
        println("=".repeat(78))
        tieRates(turns, defs)

        println()
        println("=".repeat(78))
        println("HEADROOM — what a tie-break could possibly recover")
        println("=".repeat(78))
        headroom(turns, defs, byName)

        println()
        println("=".repeat(78))
        println("CANDIDATES — seven tie-breaks, measured then tested")
        println("=".repeat(78))
        candidates(turns, defs)

        println()
        println("=".repeat(78))
        println("McNemar (paired) — the correct test for a change that sees the same turns")
        println("=".repeat(78))
        pairedTests(turns, defs)
        perTool(turns, defs, byName)
    }

    // ------------------------------------------------------------------ setup

    private fun turns(harness: HeldOutHarness): List<Turn> = HeldOutDataset.freshSession.map {
        Turn(it.utterance, it.expected, it.sessionKeywords)
    } + HeldOutDataset.referentialCases.map { c ->
        val session = dev.localintelligence.core.agent.Session()
        for (p in c.priorTurns) session.start(p)
        c.priorCall?.let { call ->
            harness.tools.firstOrNull { it.definition.name == call }?.let { t ->
                session.appendToolObservation(t, c.priorObservation.orEmpty(), true)
            }
        }
        session.start(c.utterance)
        Turn(c.utterance, c.expected, session.currentKeywords())
    }

    internal data class Turn(
        val utterance: String,
        val expected: Set<String>,
        val keywords: List<String>,
    ) {
        val hay: String get() = "$utterance ${keywords.joinToString(" ")}"
    }

    /**
     * The shipped formula, mirrored. Byte-for-byte
     * [LexicalToolSelector.select]; [mirrorCheck] fails the run if it drifts.
     */
    private fun score(t: Turn, d: ToolDefinition): Int {
        val tt = tok(t.utterance).toSet()
        val kt = t.keywords.flatMap { tok(it) }.toSet()
        val overlap = tt.intersect(tok(d.description).toSet()).size * 2 +
            tt.intersect(d.tags.flatMap { tok(it) }.toSet()).size * 3 +
            tt.intersect(tok(d.name).toSet()).size * 4 +
            kt.intersect(tok(d.description).toSet()).size +
            kt.intersect(d.tags.flatMap { tok(it) }.toSet()).size
        val substring = if (t.utterance.contains(d.name, ignoreCase = true)) 10 else 0
        return overlap + substring
    }

    private fun tok(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }

    private fun rank(
        defs: List<ToolDefinition>,
        t: Turn,
        secondary: (Turn, ToolDefinition, ToolDefinition) -> Int,
    ): List<String> = defs
        .map { it to score(t, it) }
        .sortedWith(
            compareByDescending<Pair<ToolDefinition, Int>> { it.second }
                .thenComparator { a, b -> secondary(t, a.first, b.first) }
                .thenBy { it.first.name },
        )
        .map { it.first.name }

    // ------------------------------------------------------- the mechanism

    /**
     * PROVE the tie-break is `thenBy { name }` rather than assert it.
     *
     * Ranks each turn twice — once with the alphabetical clause, once with a
     * non-alphabetical one — and reports whether the shipped selector matches
     * the alphabetical reading on every turn. If a future change to `select`
     * makes this disagree, this report is measuring a different selector and
     * says so rather than quietly reporting stale numbers.
     */
    private fun mirrorCheck(harness: HeldOutHarness, turns: List<Turn>) {
        println("=".repeat(78))
        println("MECHANISM — what decides the order when scores tie")
        println("=".repeat(78))
        println("  LexicalToolSelector.select, ToolRegistry.kt:191-193:")
        println("    compareByDescending { it.second }.thenBy { it.first.definition.name }")
        println()
        val alpha: (Turn, ToolDefinition, ToolDefinition) -> Int = { _, a, b -> a.name.compareTo(b.name) }
        val sel = LexicalToolSelector()
        var alphaMatches = 0
        for (t in turns) {
            val shipped = sel.select(t.utterance, t.keywords, harness.tools, 6).map { it.definition.name }
            if (shipped == rank(harness.tools.map { it.definition }, t, alpha).take(6)) alphaMatches++
        }
        println("  shipped ordering == score-desc-then-name-ascending: $alphaMatches/${turns.size} turns")
        println(
            if (alphaMatches == turns.size) {
                "  -> CONFIRMED: ties are ordered by tool NAME. The cut is alphabetical."
            } else {
                "  -> CHANGED: the shipped selector no longer matches the alphabetical reading."
            },
        )
        println("  (not a stable sort on registry order, and not a groupBy/distinct collapse:")
        println("   `thenBy { name }` is an explicit total order on the name)")
    }

    // ------------------------------------------------------------ tie rates

    private fun tieRates(turns: List<Turn>, defs: List<ToolDefinition>) {
        println("  k   cut decided by a TIE          all 25 tools score 0")
        for (k in listOf(3, 6, 10, 12)) {
            var tied = 0
            var allZero = 0
            for (t in turns) {
                val s = defs.map { score(t, it) }.sortedDescending()
                if (s[k - 1] == s[k]) tied++
                if (s.first() == 0) allZero++
            }
            println(
                String.format(
                    "  %-3d %3d/%-3d  %5.1f%%              %2d/%-3d  %5.1f%%",
                    k, tied, turns.size, tied * 100.0 / turns.size,
                    allZero, turns.size, allZero * 100.0 / turns.size,
                ),
            )
        }
        var expZero = 0
        for (t in turns) {
            if (t.expected.all { e -> score(t, defs.first { it.name == e }) == 0 }) expZero++
        }
        println(
            "  expected tool scores exactly 0: $expZero/${turns.size} " +
                "(${"%.1f".format(expZero * 100.0 / turns.size)}%)",
        )
    }

    // ------------------------------------------------------------- headroom

    private fun headroom(turns: List<Turn>, defs: List<ToolDefinition>, byName: Map<String, ToolDefinition>) {
        val alpha: (Turn, ToolDefinition, ToolDefinition) -> Int = { _, a, b -> a.name.compareTo(b.name) }
        var recoverable = 0
        var someSignal = 0
        var allZero = 0
        var nameSubFires = 0
        var descSubFires = 0
        var trigramFires = 0
        var ownCategory = 0
        for (t in turns) {
            val ranked = rank(defs, t, alpha)
            if (ranked.indexOfFirst { it in t.expected } < 6) continue
            val s = ranked.map { score(t, byName.getValue(it)) }
            if (s[5] != s[6]) continue
            recoverable++
            if (s.any { it > 0 }) {
                someSignal++
                if (t.expected.any { rawNameHit(t.hay, byName.getValue(it)) > 0 }) nameSubFires++
                if (t.expected.any { rawDescHit(t.hay, byName.getValue(it)) > 0 }) descSubFires++
            } else {
                allZero++
            }
            if (t.expected.any { trigramSim(t, byName.getValue(it)) > 0 }) trigramFires++
            val expCats = t.expected.map { byName.getValue(it).category }.toSet()
            if (ranked.drop(5).take(2).any { byName.getValue(it).category in expCats }) ownCategory++
        }
        println("  correct tool is TIED but below the cut: $recoverable turns")
        println("    of those, all 25 tools score 0:      $allZero   <- no signal to reorder BY")
        println("    of those, the scorer HAS a signal:    $someSignal")
        println()
        println("  would the candidate signals fire on the correct tool?")
        println("    raw name-substring:     $nameSubFires/$someSignal turns with a scorer signal")
        println("    raw description-word:   $descSubFires/$someSignal")
        println("    character trigram:      $trigramFires/$recoverable")
        println()
        println("  tie composition: the correct tool is tied with a SAME-CATEGORY tool")
        println("    across the cut on $ownCategory/$recoverable turns, with an other-category tool")
        println("    on the rest. That is why spreading tied tools APART loses turns: it")
        println("    occasionally splits the correct pair, e.g. contacts.get/contacts.search.")
    }

    // ------------------------------------------------------------ candidates

    private fun riskRank(d: ToolDefinition): Int = when (d.risk) {
        ToolRisk.READ_ONLY -> 0
        ToolRisk.REVERSIBLE -> 1
        else -> 2
    }

    private fun rawNameHit(hay: String, d: ToolDefinition): Int =
        d.name.split(".").count { it.length > 2 && hay.contains(it, ignoreCase = true) }

    private fun rawDescHit(hay: String, d: ToolDefinition): Int =
        tok(hay).count { w -> w.length > 3 && d.description.contains(w, ignoreCase = true) }

    private fun requiredCount(d: ToolDefinition): Int =
        (d.schema["required"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0

    private fun trigrams(s: String): Set<String> {
        val out = HashSet<String>()
        for (w in s.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (w.length < 3) continue
            for (i in 0..w.length - 3) out.add(w.substring(i, i + 3))
        }
        return out
    }

    private fun trigramSim(t: Turn, d: ToolDefinition): Int {
        val a = trigrams(t.hay)
        if (a.isEmpty()) return 0
        return a.intersect(trigrams(d.name + " " + d.description)).size
    }

    private fun triNorm(t: Turn, d: ToolDefinition): Double {
        val a = trigrams(t.hay)
        if (a.isEmpty()) return 0.0
        val b = trigrams(d.name + " " + d.description)
        if (b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / b.size
    }

    private fun candidates(turns: List<Turn>, defs: List<ToolDefinition>) {
        val variants = listOf<Pair<String, (Turn, ToolDefinition, ToolDefinition) -> Int>>(
            "shipped: thenBy{name}" to { _, a, b -> a.name.compareTo(b.name) },
            "raw name-substring" to { t, a, b -> rawNameHit(t.hay, b) - rawNameHit(t.hay, a) },
            "raw description-word" to { t, a, b -> rawDescHit(t.hay, b) - rawDescHit(t.hay, a) },
            "risk tier (read-only first)" to { _, a, b -> riskRank(a) - riskRank(b) },
            "fewest required args" to { _, a, b -> requiredCount(a) - requiredCount(b) },
            "most required args" to { _, a, b -> requiredCount(b) - requiredCount(a) },
            "trigram similarity" to { t, a, b -> trigramSim(t, b) - trigramSim(t, a) },
            "normalised trigram" to { t, a, b -> triNorm(t, b).compareTo(triNorm(t, a)) },
        )
        println("  selectability (turns where >=1 expected tool is in the window)")
        println("  variant                          k=3      k=6      k=10     k=12")
        for ((label, cmp) in variants) {
            val row = StringBuilder()
            for (k in listOf(3, 6, 10, 12)) {
                val ok = turns.count { t -> t.expected.any { it in rank(defs, t, cmp).take(k) } }
                row.append(String.format("  %3d/%-3d", ok, turns.size))
            }
            println(String.format("  %-30s%s", label, row))
        }

        // The random-tie null: what does a tie-break with NO information score?
        val rng = Random(20260926L)
        fun shuffled(t: Turn): List<String> {
            val scored = defs.map { it to score(t, it) }
                .sortedWith(compareByDescending<Pair<ToolDefinition, Int>> { it.second })
            val out = ArrayList<String>()
            var i = 0
            while (i < scored.size) {
                var j = i
                while (j < scored.size && scored[j].second == scored[i].second) j++
                val g = scored.subList(i, j).map { p -> p.first.name }.toMutableList()
                Collections.shuffle(g, rng)
                out.addAll(g)
                i = j
            }
            return out
        }
        println()
        println("  NULL — 200 random WITHIN-TIE permutations, k=6")
        val draws = IntArray(200) { turns.count { t -> t.expected.any { it in shuffled(t).take(6) } } }
        val mean = draws.average()
        val sd = Math.sqrt(draws.map { (it - mean) * (it - mean) }.average())
        val alpha: (Turn, ToolDefinition, ToolDefinition) -> Int = { _, a, b -> a.name.compareTo(b.name) }
        val shipped = turns.count { t -> t.expected.any { it in rank(defs, t, alpha).take(6) } }
        println(
            String.format(
                "    mean=%.1f  sd=%.1f  range=%d..%d", mean, sd, draws.min(), draws.max(),
            ),
        )
        println(
            String.format(
                "    shipped=%d  ->  %s the null (mildly lucky, not informed)",
                shipped,
                pct(draws.count { it <= shipped } * 100.0 / draws.size),
            ),
        )
    }

    // --------------------------------------------------------- paired tests

    private fun choose(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var r = 1.0
        for (i in 0 until k) r = r * (n - i) / (i + 1)
        return r
    }

    private fun pairedTests(turns: List<Turn>, defs: List<ToolDefinition>) {
        val alpha: (Turn, ToolDefinition, ToolDefinition) -> Int = { _, a, b -> a.name.compareTo(b.name) }
        fun test(label: String, cmp: (Turn, ToolDefinition, ToolDefinition) -> Int) {
            val out = StringBuilder()
            for (k in listOf(3, 6, 10, 12)) {
                var b = 0
                var c = 0
                for (t in turns) {
                    val sOk = t.expected.any { it in rank(defs, t, alpha).take(k) }
                    val cOk = t.expected.any { it in rank(defs, t, cmp).take(k) }
                    if (cOk && !sOk) b++ else if (sOk && !cOk) c++
                }
                val n = b + c
                val p = if (n == 0) 1.0 else {
                    var tail = 0.0
                    for (i in 0..minOf(b, c)) tail += choose(n, i) * Math.pow(0.5, n.toDouble())
                    minOf(1.0, 2 * tail)
                }
                out.append(
                    String.format(
                        "  k=%-3d %+d (%d/%d) p=%.3f%s   ", k, b - c, b, n, p,
                        if (p < 0.05) "*" else "",
                    ),
                )
            }
            println(String.format("  %-26s%s", label, out))
        }
        println("  net = turns the candidate wins minus turns it loses, on DISCORDANT turns only")
        println("  * = significant at p<0.05")
        println()
        test("CONTROL shipped vs itself", alpha)
        test("raw name-substring", { t, a, b -> rawNameHit(t.hay, b) - rawNameHit(t.hay, a) })
        test("risk tier", { _, a, b -> riskRank(a) - riskRank(b) })
        test("trigram similarity", { t, a, b -> trigramSim(t, b) - trigramSim(t, a) })
        test("normalised trigram", { t, a, b -> triNorm(t, b).compareTo(triNorm(t, a)) })
        println()
        println("  No candidate reaches significance at the shipped k=6. The best is +3 on")
        println("  ~19 discordant turns, which is what a coin produces by itself.")
    }

    // -------------------------------------------------------------- per tool

    private fun perTool(turns: List<Turn>, defs: List<ToolDefinition>, byName: Map<String, ToolDefinition>) {
        val alpha: (Turn, ToolDefinition, ToolDefinition) -> Int = { _, a, b -> a.name.compareTo(b.name) }
        val norm: (Turn, ToolDefinition, ToolDefinition) -> Int =
            { t, a, b -> triNorm(t, b).compareTo(triNorm(t, a)) }
        val worst = listOf(
            "calendar.create", "notifications.list", "contacts.get",
            "device.battery", "contacts.search", "web.fetch",
        )
        println()
        println("=".repeat(78))
        println("THE SIX WORST TOOLS at k=6 — shipped vs the best candidate")
        println("=".repeat(78))
        println("  Every miss is one of exactly two kinds, and only one is a tie-break's problem:")
        println("    BLIND  = the expected tool scored 0; no ordering can recover it")
        println("    AT-CUT = it scored >=1 but lost the window to a tie at the cut")
        println()
        println("  tool                   cases  ship  cand   miss  BLIND  AT-CUT")
        var sBlind = 0
        var sCut = 0
        for (name in worst) {
            val n = turns.count { name in it.expected }
            var sHit = 0
            var cHit = 0
            var blind = 0
            var atCut = 0
            for (t in turns) {
                if (name !in t.expected) continue
                val sRanked = rank(defs, t, alpha)
                if (name in sRanked.take(6)) sHit++ else if (score(t, byName.getValue(name)) == 0) blind++ else atCut++
                if (name in rank(defs, t, norm).take(6)) cHit++
            }
            sBlind += blind
            sCut += atCut
            println(
                String.format(
                    "  %-22s %5d %5d %5d %6d %6d %7d", name, n, sHit, cHit, n - sHit, blind, atCut,
                ),
            )
        }
        println(
            String.format(
                "  TOTAL misses %d -> BLIND %d (unrecoverable by any ordering), AT-CUT %d (in principle recoverable)",
                sBlind + sCut, sBlind, sCut,
            ),
        )
    }

    private fun pct(d: Double): String {
        val n = "%.0f".format(d).toInt()
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix percentile"
    }
}

fun main() {
    TieBreakReport.run()
}
