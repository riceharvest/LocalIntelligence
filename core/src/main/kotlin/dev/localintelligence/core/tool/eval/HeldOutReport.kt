package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.model.GrammarBuilder
import dev.localintelligence.core.tool.LexicalToolSelector

/**
 * Prints the held-out report.
 *
 * ## HOW TO RUN IT
 *
 * ```
 * export JAVA_HOME=$HOME/jdk21
 * ./gradlew :core:compileKotlin
 * ./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-heldout-harness.sh
 * ```
 *
 * A `main()` rather than a JUnit test, for the same reasons as the sibling
 * harnesses: no test source set, no build-file change, no dependency, and one
 * command a human can read and verify.
 *
 * ## HOW TO READ IT, INCLUDING THE PARTS THAT ARE UNFLATTERING
 *
 * - **SELECTABILITY is the headline, CHOICE is not measured.** See
 *   [HeldOutHarness] for why an unselected tool is not a worse answer but an
 *   impossible one, and why the second half of the product is simply absent from
 *   this report.
 * - **The contamination line is in the header, every run.** If it says
 *   CONTAMINATED, every number below is a training-set score and the file says
 *   so itself rather than relying on anyone remembering.
 * - **A single percentage is not the deliverable.** The per-tool table, the
 *   per-dialect table and the blind-spot section are the deliverable; the
 *   headline is one line of that.
 */
fun main() {
    val harness = HeldOutHarness()
    val config = AgentConfig()
    val tools = harness.tools
    val results = harness.scoredTurns()

    println("=".repeat(78))
    println("HELD-OUT TOOL SELECTABILITY — LexicalToolSelector over the 25 shipped tools")
    println("=".repeat(78))
    println("corpus: ${HeldOutDataset.total} turns " +
        "(${HeldOutDataset.totalSingleTurn} fresh-session, ${HeldOutDataset.totalReferential} referential)")
    println("authored blind (no tag read): ${HeldOutDataset.AUTHORED_BLIND_ON}")
    println("contamination state: ${HeldOutDataset.CONTAMINATION}")
    if (HeldOutDataset.CONTAMINATION == HeldOutDataset.Contamination.CONTAMINATED) {
        println("  *** THIS CORPUS IS CONTAMINATED. Every number below is a training-set")
        println("  *** score, not a held-out measurement. Do not quote it as one.")
    }
    println("shipped AgentConfig.maxVisibleTools = ${config.maxVisibleTools}   (the constant under test)")

    // ---- integrity: is the tool set the one that ships? --------------------
    println()
    println("TOOL-SET INTEGRITY (a snapshot that drifted makes every number below a fiction)")
    val catalogueProblems = HeldOutToolSnapshot.verifyAgainstCatalogue()
    if (catalogueProblems.isEmpty()) {
        println("  vs V0ToolCatalogue: AGREE (names, categories, risk tiers)")
    } else {
        println("  vs V0ToolCatalogue: ${catalogueProblems.size} DISAGREEMENT(S)")
        catalogueProblems.forEach { println("      $it") }
        println("      -> every number below describes a tool set that may not ship.")
    }
    val sourceProblems = HeldOutToolSnapshot.verifyAgainstAndroidSources()
    val skipped = sourceProblems.filter { it.startsWith("SKIPPED") }
    val realDisagreements = sourceProblems - skipped.toSet()
    skipped.forEach { println("  vs :android sources: $it") }
    if (realDisagreements.isNotEmpty()) {
        println("  vs :android sources: ${realDisagreements.size} DISAGREEMENT(S)")
        realDisagreements.take(10).forEach { println("      $it") }
        println("      -> tags carry 3x the weight of description tokens in the scorer, so a")
        println("         tag drift MOVES the headline. Treat everything below as unverified.")
    } else if (skipped.isEmpty()) {
        println("  vs :android sources: AGREE (names, categories, risk, PERMISSIONS, and all 185 TAGS)")
    }

    val mirrorProblems = harness.assertMirrorsSelector(results)
    if (mirrorProblems.isEmpty()) {
        println("  score mirror: AGREES with LexicalToolSelector on all ${results.size} turns")
    } else {
        println("  score mirror: ${mirrorProblems.size} DISAGREEMENT(S) — the mirrored formula is")
        println("      STALE. Re-derive it from ToolRegistry.kt before quoting any score")
        println("      in this report, including the tautology and zero-signal sections.")
        mirrorProblems.take(5).forEach { println("      $it") }
    }

    // ---- the headline ------------------------------------------------------
    val ks = listOf(3, 6, config.maxVisibleTools, 10, 12, tools.size).distinct().sorted()
    val reports = ks.associateWith { k -> harness.reportAt(k, results) }

    println()
    println("=".repeat(78))
    println("SELECTABILITY BY VISIBLE-SET WIDTH  (was the right tool REACHABLE?)")
    println("=".repeat(78))
    println("    k     turns-ok   selectability   slot-recall   first-choice   mean prompt tok")
    for ((k, report) in reports) {
        val label = if (k == tools.size) "all" else k.toString()
        println(
            "    ${label.padEnd(4)} " +
                "${report.callable}/${report.total}".padEnd(12) +
                heldOutPct(report.recall).padEnd(16) +
                heldOutPct(report.slotRecall).padEnd(14) +
                "${report.expectedToolHit}/${report.total}".padEnd(15) +
                report.meanSystemPromptTokens,
        )
    }
    println()
    println("    turns-ok       = turns where >=1 expected tool was in the visible set")
    println("    selectability  = turns-ok / turns. THIS IS THE HEADLINE. An upper bound on")
    println("                     task success, NOT a success rate: it says the tool was")
    println("                     reachable, never that anything called it correctly.")
    println("    slot-recall    = expected tool-slots reached / expected tool-slots.")
    println("    first-choice   = turns where the obvious tool specifically survived the cut.")
    println("    'all'          = no cut. Every tool is reachable by construction, so this row")
    println("                     is the SCORER's ceiling and isolates scoring from width.")

    val shipped = reports.getValue(config.maxVisibleTools)
    val ceiling = reports.getValue(tools.size)

    // ---- what the number does not tell you ---------------------------------
    println()
    println("=".repeat(78))
    println("THE HALF THAT IS NOT MEASURED — SELECTABLE vs CHOSEN")
    println("=".repeat(78))
    val perTurnChance = 1.0 / config.maxVisibleTools
    println("  SELECTABLE  measured here: was a correct tool in the ${config.maxVisibleTools}-slot grammar?")
    println("  CHOSEN      NOT measured:  did the model emit a well-formed call to it, with")
    println("                           good arguments, in the right order, without looping?")
    println()
    println("  A perfect score in this report is compatible with an agent that never makes a")
    println("  single correct call, because the model is not in this loop at all. There is no")
    println("  device, no emulator and no inference in this harness.")
    println()
    println("  If a 1-3B model picked uniformly at random among the ${config.maxVisibleTools} visible")
    println("  tools, the expected number of turns it would get right is roughly")
    println("  ${heldOutPct(perTurnChance)} of turns at most, and in practice lower, because a")
    println("  small model's choice is far from uniform. So the honest reading of a")
    println("  ${heldOutPct(shipped.recall)} selectability figure is a CEILING, and the real product")
    println("  number is bounded above by it and below by nothing we have measured.")
    println()
    println("  CLOSING THAT GAP NEEDS: a real GGUF on a real device, a real multi-turn")
    println("  conversation, and a scorer that checks the emitted call rather than the")
    println("  grammar. None of that exists in this repository, and no number here should")
    println("  be read as a claim about it.")

    // ---- per-tool ---------------------------------------------------------
    // Every selection-based question below is asked of `shipped.cases`, the turns
    // TRUNCATED TO THE SHIPPED WIDTH. `results` holds the untruncated ranking
    // (all 25 tools), so scoring a case's `callable` flag off `results` measures
    // the ceiling and reports 25/25 for every tool — which is exactly the bug
    // this table shipped with on its first run. The ceiling is a separate,
    // deliberately labelled column.
    println()
    println("=".repeat(78))
    println("PER-TOOL BREAKDOWN at the shipped k=${config.maxVisibleTools}")
    println("=".repeat(78))
    println("  tool                   cases    hit/miss   blind  en/nl/de    flags")
    println("  " + "-".repeat(74))
    val coverage = HeldOutDataset.coverageByTool()
    val rows = tools.map { it.definition.name }.sorted().map { name ->
        val expectedHere = shipped.cases.filter { name in it.expected }
        val ok = expectedHere.count { it.callable }
        val dialectSplit = listOf(
            expectedHere.count { it.dialect == HeldOutCase.Dialect.EN },
            expectedHere.count { it.dialect == HeldOutCase.Dialect.NL },
            expectedHere.count { it.dialect == HeldOutCase.Dialect.DE },
        )
        // Every miss is exactly one of two kinds, and the split is the useful
        // part of this table:
        //   SCORER-BLIND — the expected tool scored 0, so it shared no word with
        //     its own name, description or tags. No width and no re-weighting
        //     recovers it; only new vocabulary in the definition would.
        //   LOST-AT-CUT  — it WAS scored and still fell outside the k slots,
        //     which is the width/ranking failure and the one a width change can
        //     trade against prompt tokens.
        // Both are counted over MISSES ONLY. Counting them over every case that
        // wants the tool conflates "this tool was useless here" with "this tool
        // cost us the turn", and on a row with zero misses it printed
        // "LOST-AT-CUT x2" beside "miss= 0", which is self-contradictory.
        val missedHere = expectedHere.filter { !it.callable }
        val blind = missedHere.count { turn ->
            harness.mirrorScoreOf(turn.utterance, turn.sessionKeywords, name) == 0
        }
        val lostAtCut = missedHere.size - blind
        // The four judgements are computed as booleans and rendered as flags,
        // rather than matching flag STRINGS afterwards. An earlier draft filtered
        // `rows` with `flags.contains("THIN n=")` and silently reported 0/25 for
        // a condition the table itself was printing — a summary that disagrees
        // with the table above it is worse than no summary.
        val flags = buildList {
            if ((coverage[name] ?: 0) == 0) add("NEVER EXERCISED")
            if (expectedHere.isEmpty()) add("NO CASES")
            if (expectedHere.isNotEmpty() && ok == 0) add("UNREACHABLE")
            if (blind > 0) add("SCORER-BLIND x$blind")
            if (lostAtCut > 0) add("LOST-AT-CUT x$lostAtCut")
            if (expectedHere.size < 5) add("THIN")
        }
        ToolRow(
            tool = name,
            covered = coverage[name] ?: 0,
            ok = ok,
            expected = expectedHere.size,
            blind = blind,
            lostAtCut = lostAtCut,
            thin = expectedHere.size < 5,
            unreachable = expectedHere.isNotEmpty() && ok == 0,
            dialects = dialectSplit,
            flags = flags,
        )
    }
    for (row in rows) {
        println(
            "  ${row.tool.padEnd(23)} " +
                "${row.covered.toString().padStart(4)}   " +
                "${row.ok.toString().padStart(2)}/${row.expected.toString().padStart(2)} miss=${(row.expected - row.ok).toString().padStart(2)}  " +
                "${row.blind.toString().padStart(2)}      " +
                "${row.dialects.joinToString("/").padEnd(12)} " +
                row.flags.joinToString(", "),
        )
    }
    println("  " + "-".repeat(74))
    println("  cases = turns that WANT this tool.  hit/miss = of those, how many had it")
    println("           in the k=${config.maxVisibleTools} grammar.  blind = how many of the misses were not")
    println("           even SCORED (expected tool scores 0 on the full 25 ranking), so NO")
    println("           width can recover them; the rest were scored and lost at the cut.")
    val unreachable = rows.count { it.unreachable }
    val scorerBlind = rows.count { it.blind > 0 }
    val totalBlind = rows.sumOf { it.blind }
    val totalLostAtCut = rows.sumOf { it.lostAtCut }
    val totalMisses = rows.sumOf { it.expected - it.ok }
    val thin = rows.count { it.thin }
    println()
    println("  tools with ZERO reachable turns at k=${config.maxVisibleTools}: $unreachable/25 " +
        "(${rows.filter { it.unreachable }.joinToString(", ") { it.tool }})")
    println("  tools the SCORER is blind to on >=1 turn: $scorerBlind/25")
    println("  every miss, split by cause: $totalMisses tool-misses = $totalBlind SCORER-BLIND " +
        "(expected tool scored 0, unreachable at ANY width) + $totalLostAtCut LOST-AT-CUT (scored, fell off)")
    println("  tools with fewer than 5 cases (cannot support a claim): $thin/25 " +
        "(${rows.filter { it.thin }.joinToString(", ") { "${it.tool}(n=${it.expected})" }})")
    val worst = rows.filter { it.expected >= 5 }.minByOrNull { it.ok }
    if (worst != null) {
        println("  weakest well-covered tool: ${worst.tool} at ${worst.ok}/${worst.expected}")
    }

    // ---- dialects ---------------------------------------------------------
    println()
    println("=".repeat(78))
    println("BY LANGUAGE — the ASCII tokeniser cliff, measured not asserted")
    println("=".repeat(78))
    println("  dialect   turns-ok   selectability   note")
    for (dialect in listOf(HeldOutCase.Dialect.EN, HeldOutCase.Dialect.NL, HeldOutCase.Dialect.DE)) {
        val subset = shipped.cases.filter { it.dialect == dialect }
        val ok = subset.count { it.callable }
        val pct = if (subset.isEmpty()) 0.0 else ok.toDouble() / subset.size
        val note = if (dialect == HeldOutCase.Dialect.EN) {
            "the language the tags were written in"
        } else {
            "tags are English; the scorer drops every non-ASCII token"
        }
        println(
            "  ${dialect.label.padEnd(8)} " +
                "${ok}/${subset.size}".padEnd(12) +
                heldOutPct(pct).padEnd(16) + note,
        )
    }
    println()
    println("  LexicalToolSelector tokenises on [^a-z0-9]+ with a length>2 floor, so an")
    println("  accented or compound word contributes NOTHING. A German utterance is not")
    println("  scored badly; it is mostly not scored at all. The gap below is the size of")
    println("  that, and it is a property of the shipped selector, not of this corpus.")

    // ---- by intent --------------------------------------------------------
    println()
    println("=".repeat(78))
    println("BY INTENT — the populations that make the corpus hard")
    println("=".repeat(78))
    println("  intent            turns-ok   selectability   what it is")
    val intentNotes = mapOf(
        HeldOutCase.Intent.PLAIN to "an ordinary unambiguous request",
        HeldOutCase.Intent.NEAR_MISS to "shares vocabulary with a rival tool",
        HeldOutCase.Intent.AMBIGUOUS to "two tools are defensible",
        HeldOutCase.Intent.ELLIPTICAL to "noun omitted; user assumes context",
        HeldOutCase.Intent.CHAINED to "needs one tool to find another's input",
        HeldOutCase.Intent.REFERENTIAL to "subject established in an EARLIER turn",
    )
    for (intent in HeldOutCase.Intent.entries) {
        val subset = shipped.cases.filter { it.intent == intent }
        if (subset.isEmpty()) continue
        val ok = subset.count { it.callable }
        val pct = ok.toDouble() / subset.size
        println(
            "  ${intent.name.padEnd(17)} " +
                "${ok}/${subset.size}".padEnd(12) +
                heldOutPct(pct).padEnd(16) +
                (intentNotes[intent] ?: ""),
        )
    }
    println()
    // The intent table and the control/referential split below PARTITION THE SAME
    // TURNS DIFFERENTLY, and adding them up will not work. A referential turn
    // that is also elliptical is filed under ELLIPTICAL here and under
    // REFERENTIAL below, because the question being asked is different: "what
    // makes this turn hard" versus "does this turn reach backwards".
    println("  (a referential turn that is ALSO elliptical appears under ELLIPTICAL here")
    println("   and under REFERENTIAL below — the two tables partition differently.)")
    val referential = shipped.cases.filter { it.referential }
    val referentialOk = referential.count { it.callable }
    val fresh = shipped.cases.filter { !it.referential }
    val freshOk = fresh.count { it.callable }
    val freshPct = freshOk.toDouble() / fresh.size
    val refPct = referentialOk.toDouble() / referential.size
    val gapPoints = (freshPct - refPct) * 100
    println("  CONTROL    (self-contained turns): ${freshOk}/${fresh.size} (${heldOutPct(freshPct)})")
    println("  REFERENTIAL(turns reaching back):  ${referentialOk}/${referential.size} (${heldOutPct(refPct)})")
    println("  GAP: ${"%.1f".format(gapPoints)} points (control minus referential)")
    println()
    // The narrative follows the measurement. An earlier draft of this file
    // asserted here that "the gap between those two rows is the finding",
    // written before the numbers existed, and on this corpus the gap turned out
    // to be ~0. Asserting a finding in a report is how a report starts lying,
    // so the text is now generated from the measurement and the interpretation
    // is stated conditionally.
    if (gapPoints > 10) {
        println("  -> referential turns are materially WORSE, which is the predicted failure:")
        println("     Session.currentKeywords() reads ONLY the latest user turn, so a follow-up")
        println("     reaches the selector with no word from the turn that set its subject,")
        println("     while the model is shown retained history. Widening the grammar does not")
        println("     fix that; carrying subject words into retrieval would.")
    } else if (gapPoints < -10) {
        println("  -> referential turns are BETTER than self-contained ones, which is not")
        println("     plausible and is itself worth investigating before quoting either row.")
    } else {
        println("  -> THE GAP IS ~0 ON THIS CORPUS. That is a real result, and it is NOT the")
        println("     predicted one: a referential turn should be much harder, because")
        println("     Session.currentKeywords() reads ONLY the latest user turn.")
        println("     Two reasons this may be flattering, both about the harness rather than")
        println("     the product: (a) a referential turn's prior context is often LEXICALLY")
        println("     rich in the subject, so some signal survives in the follow-up's own words;")
        println("     (b) n=${referential.size} is small, and a few turns is a large fraction of it.")
        println("     Do NOT read 'referential is fine' off this row. The asymmetry is real in")
        println("     the production code and this corpus is too small to resolve it.")
    }

    // ---- BLIND SPOTS ------------------------------------------------------
    println()
    println("=".repeat(78))
    println("BLIND SPOTS — where this harness is NOT measuring what it looks like")
    println("=".repeat(78))
    printBlindSpots(harness, reports, config, results, ceiling, shipped)

    // ---- per-case failure detail -----------------------------------------
    println()
    println("=".repeat(78))
    println("EVERY TURN THAT FAILED at the shipped k=${config.maxVisibleTools}")
    println("=".repeat(78))
    val misses = shipped.cases.filter { !it.callable }
    if (misses.isEmpty()) {
        println("  none")
    } else {
        misses.forEach { m ->
            println("  \"${m.utterance}\"")
            println("      [${m.dialect.label}/${m.intent.name}] wanted ${m.missed.joinToString()}")
            println("      got ${m.selected.joinToString()}")
            println("      best expected score: ${m.topExpectedScore}" +
                if (m.topExpectedScore == 0) "  <- ZERO: shares no word with name, description or tag" else "")
            if (m.sessionKeywords.isNotEmpty()) {
                println("      session keywords the selector saw: ${m.sessionKeywords.joinToString(" ")}")
            }
        }
    }

    // ---- what wider buys --------------------------------------------------
    println()
    println("=".repeat(78))
    println("WIDTH — what a wider grammar actually buys, and what it costs")
    println("=".repeat(78))
    val byK = reports
    for (pair in listOf(3 to 6, 6 to config.maxVisibleTools, config.maxVisibleTools to 12, 6 to tools.size)) {
        val (from, to) = pair
        if (from !in byK || to !in byK || from == to) continue
        val gained = byK.getValue(to).cases.filter { it.callable }
            .map { it.utterance }.toSet() - byK.getValue(from).cases.filter { it.callable }.map { it.utterance }.toSet()
        val lost = byK.getValue(from).cases.filter { it.callable }
            .map { it.utterance }.toSet() - byK.getValue(to).cases.filter { it.callable }.map { it.utterance }.toSet()
        println("  $from -> $to:  +${gained.size} newly callable, -${lost.size} lost   " +
            "(mean prompt ${byK.getValue(from).meanSystemPromptTokens} -> ${byK.getValue(to).meanSystemPromptTokens} tok)")
    }
    println()
    println("  Widening is NOT free and NOT clearly good: it trades prompt tokens, and a")
    println("  small model is known to choose less reliably from a longer list. The")
    println("  selector side of that trade is measured here. The model side is not")
    println("  measured anywhere, which is why the shipped constant stays at")
    println("  ${config.maxVisibleTools} and this report does not propose raising it.")

    println()
    println("WIDTH SAFETY — does a wider grammar push the prompt past the working limit?")
    for (k in ks) print(widthSafety(harness, k, config))

    println()
    println("=".repeat(78))
}

/**
 * The section whose job is to make the harness attack itself.
 *
 * Every item here is a way the number above could be flattering and is not.
 * They are printed as counts with a verdict, and the run continues afterwards
 * rather than exiting, because a reader needs the failures and the caveats in
 * one place.
 */
private fun printBlindSpots(
    harness: HeldOutHarness,
    reports: Map<Int, HeldOutHarness.Report>,
    config: AgentConfig,
    results: List<HeldOutHarness.CaseResult>,
    ceiling: HeldOutHarness.Report,
    shipped: HeldOutHarness.Report,
) {
    val tools = harness.tools
    val coverage = HeldOutDataset.coverageByTool()
    val shippedTurns = shipped.cases
    val neverExpected = tools.map { it.definition.name }.filter { (coverage[it] ?: 0) == 0 }
    val neverSelected = tools.map { it.definition.name }.filter { name ->
        shippedTurns.none { name in it.selected }
    }
    val expectedNeverSelected = tools.map { it.definition.name }.filter { name ->
        shippedTurns.any { name in it.expected } && shippedTurns.none { name in it.expected && name in it.selected }
    }

    println()
    println("  B1  tools NEVER EXPECTED by any held-out turn: ${neverExpected.size}/${tools.size}")
    if (neverExpected.isEmpty()) {
        println("      none — every shipped tool is exercised at least once")
    } else {
        neverExpected.forEach { println("      $it — NO CASE ASKS FOR IT. Any claim about this tool is unsupported.") }
    }

    println()
    println("  B2  tools the selector NEVER puts in a visible set: ${neverSelected.size}/${tools.size}")
    if (neverSelected.isEmpty()) {
        println("      none — every tool reaches a grammar at least once")
    } else {
        neverSelected.forEach { println("      $it") }
    }

    println()
    println("  B3  tools a turn WANTS and never gets on any turn: ${expectedNeverSelected.size}")
    if (expectedNeverSelected.isEmpty()) {
        println("      none — every wanted tool reaches a grammar somewhere")
    } else {
        println("      ${expectedNeverSelected.joinToString(", ")}")
        println("      -> selector failures, not coverage holes: the corpus states the right")
        println("         tool and no grammar ever contains it.")
    }

    // ---- the tautology check ---------------------------------------------
    val tautological = shipped.cases.filter { it.tautological }
    val tautologicalReachable = tautological.count { it.callable }
    println()
    println("  B4  TAUTOLOGY — turns reachable ONLY through a tag, not a name/description")
    println("      ${tautological.size}/${shipped.total} turns flagged, of which " +
        "${tautologicalReachable} are actually reachable")
    println("      A flagged turn shares a word with a tool TAG and nothing else. The tags")
    println("      were written by whoever wrote the tools; this corpus was written WITHOUT")
    println("      reading them (R1). So a high count here means retrieval is carried by")
    println("      the tag lists, NOT that the corpus is overfitted — but it does mean the")
    println("      headline partly measures an artefact no user ever reads.")
    if (tautological.isNotEmpty()) {
        println("      examples:")
        tautological.take(6).forEach { println("        \"${it.utterance}\" -> ${it.expected.joinToString()}") }
    }

    // ---- zero-signal cases ------------------------------------------------
    val zero = ceiling.cases.filter { it.topExpectedScore == 0 }
    println()
    println("  B5  ZERO-SIGNAL — turns where the expected tool scores EXACTLY 0 at full width")
    println("      ${zero.size}/${ceiling.total} turns (${heldOutPct(zero.size.toDouble() / ceiling.total)})")
    println("      These share no word with the tool's name, description or tags. No")
    println("      re-weighting can fix them, because there is no signal to re-weight.")
    if (zero.isNotEmpty()) {
        zero.forEach {
            val dialect = if (it.dialect == HeldOutCase.Dialect.EN) "" else " [${it.dialect.label}: ASCII tokeniser]"
            println("        \"${it.utterance}\" -> ${it.expected.joinToString()}$dialect")
        }
    }

    // ---- rank distribution (replaces a vacuous "ceiling failure" check) ----
    // A "did it fail at FULL WIDTH" check is not a check: `select` short-circuits
    // to `return available` when maxTools >= available.size, so with no cut every
    // tool is in the visible set for every turn and the check reports 0/170 by
    // construction. It shipped that way in an earlier draft. What actually
    // distinguishes a near miss from a hopeless one is WHERE the expected tool
    // ranked, which is a real measurement and says whether the case was ever
    // winnable at this width.
    println()
    println("  B6  RANK OF THE BEST EXPECTED TOOL (1 = first choice, 25 = last)")
    val rankBuckets = listOf(
        1..6 to "in the shipped cut (callable)",
        7..10 to "just outside — a slightly wider k would fix it",
        11..18 to "far outside",
        19..25 to "hopeless at any plausible width",
    )
    val ranks = results.map { result ->
        val order = harness.fullRanking(result.utterance, result.sessionKeywords)
        // +1: indexOf is 0-based and the buckets below are 1-based ranks. Without
        // it every turn whose expected tool ranked FIRST fell outside all four
        // buckets, and the section silently accounted for 92 of 170 turns.
        result.expected.map { order.indexOf(it) + 1 }.filter { it in 1..order.size }.minOrNull()
            ?: Int.MAX_VALUE
    }
    val bucketed = rankBuckets.sumOf { (range, _) -> ranks.count { it in range } }
    if (bucketed + ranks.count { it == Int.MAX_VALUE } != ranks.size) {
        println("      WARNING: rank buckets account for ${bucketed}/${ranks.size} turns. " +
            "The buckets do not partition the ranking — treat the numbers below as suspect.")
    }
    for ((range, label) in rankBuckets) {
        val n = ranks.count { it in range }
        println("      ranks ${range.first}-${range.last}".padEnd(22) +
            "${n.toString().padStart(3)}/${ranks.size} (${heldOutPct(n.toDouble() / ranks.size)})  $label")
    }
    val unranked = ranks.count { it == Int.MAX_VALUE }
    if (unranked > 0) {
        println("      UNRANKED (no expected tool in the full ranking at all): $unranked")
    }
    println("      Read this with B5: a turn whose expected tool ranks 20th was never")
    println("      close, and no width change rescues it. A turn that ranks 7th is a")
    println("      width decision, which is a different conversation from a tag fix.")

    // ---- tie rate ---------------------------------------------------------
    println()
    println("  B7  TIE RATE — turns where the k-th and (k+1)-th tools score IDENTICALLY,")
    println("      so the cut is decided by the selector's alphabetical tie-break")
    for (k in listOf(3, 6, config.maxVisibleTools, 10, 12).distinct()) {
        var tied = 0
        var scorable = 0
        for (result in results) {
            val order = harness.fullRanking(result.utterance, result.sessionKeywords)
            if (order.size <= k) continue
            scorable++
            if (harness.mirrorScoreOf(result.utterance, result.sessionKeywords, order[k - 1]) ==
                harness.mirrorScoreOf(result.utterance, result.sessionKeywords, order[k])
            ) {
                tied++
            }
        }
        if (scorable == 0) continue
        println(
            "      k=${k.toString().padEnd(3)} $tied/$scorable turns " +
                "(${heldOutPct(tied.toDouble() / scorable)}) decided by NAME, not by score",
        )
    }
    println("      A high tie rate means the ranking is largely arbitrary at the cut, and")
    println("      it is the single biggest reason a miss here is not obviously fixable by")
    println("      better tags.")

    // ---- displacement -----------------------------------------------------
    val slots = shipped.total * config.maxVisibleTools
    val unwanted = shipped.cases.sumOf { turn -> turn.selected.count { it !in turn.expected } }
    println()
    println("  B8  DISPLACEMENT — visible slots filled by tools no reading of the turn wants")
    println("      $unwanted of $slots slots (${heldOutPct(unwanted.toDouble() / slots)})")
    println("      At k=${config.maxVisibleTools} of ${tools.size} tools a high share is arithmetic,")
    println("      not a defect: the selector MUST fill its slots. What matters is whether")
    println("      an unwanted tool DISPLACES a wanted one, which is the miss column above.")

    // ---- what the corpus cannot express ------------------------------------
    println()
    println("  B9  WHAT THIS CORPUS CANNOT MEASURE, stated rather than left implied")
    println("      - CHOICE: no model, no device, no inference. See the section above.")
    println("      - ARGUMENT QUALITY: only 'which tool', never 'called it with the right")
    println("        arguments'. A tool can be reachable and be given nonsense.")
    println("      - MULTI-HOP CHAINS: CHAINED cases score the FIRST call only, because")
    println("        selection is per-turn. Whether the follow-up is expressible is a")
    println("        separate measurement that does not exist yet.")
    println("      - PROSE ANSWERS: turns that need no tool are absent, so the selector is")
    println("        never scored on 'correctly reached for nothing'.")
    println("      - REAL USER PHRASING: hand-written plausible utterances, NOT a")
    println("        conversation log. This repo has no telemetry; a 'real users say this'")
    println("        claim would be a fiction.")
    println("      - NON-ASCII BEYOND NL/DE: the tokeniser drops anything outside")
    println("        [a-z0-9], so Turkish, Polish, Greek, Cyrillic and emoji-bearing")
    println("        requests are unrepresented here and would be worse still.")
    println("      - A 95% Wilson interval at n=${shipped.total} is roughly +/- " +
        "${(1.96 * Math.sqrt(0.95 * 0.05 / shipped.total) * 100).toInt()} points, so a")
    println("        difference of a few points between two variants is NOT significant.")
}

/** The width/ceiling check, priced the way ContextBudget prices it. */
private fun widthSafety(harness: HeldOutHarness, k: Int, config: AgentConfig): String {
    val results = harness.scoredTurns()
    val promptCeiling = config.workingTokenLimit - HeldOutHarness.CONTEXT_BUDGET_OUTPUT_RESERVE
    val worstPrompt = results.maxOf { result ->
        val visible = harness.tools.filter { it.definition.name in result.selected }
        dev.localintelligence.core.model.token.DefaultTokenCounter.count(
            dev.localintelligence.core.context.SystemPrompts.forTools(visible.map { it.definition }),
        ) + WORST_CASE_TASK_TOKENS
    }
    val worstToolSet = harness.budgetedCostPerTool().take(k).sumOf { it.second }
    val grammarSize = GrammarBuilder.forActions(
        harness.tools.take(k).map { it.definition },
    ).length
    return buildString {
        appendLine("  k=$k")
        appendLine("    worst-case system prompt + task: $worstPrompt tokens")
        appendLine("    prompt ceiling ($promptCeiling): " +
            if (worstPrompt < promptCeiling) "FITS, headroom ${promptCeiling - worstPrompt}" else "OVERRUNS")
        appendLine("    worst-case k-tool budget cost (schemas included): $worstToolSet tokens")
        appendLine("    grammar size: $grammarSize chars (sampler constraint; no context cost)")
    }
}

/** A two-sentence request, deliberately pessimistic about the tail. */
private const val WORST_CASE_TASK_TOKENS = 40

/** One line of the per-tool table, with the judgements precomputed. */
private data class ToolRow(
    val tool: String,
    val covered: Int,
    val ok: Int,
    val expected: Int,
    /** Misses where the expected tool was not scored at all, so no width recovers it. */
    val blind: Int,
    /** Misses where it WAS scored and still fell outside the k slots. */
    val lostAtCut: Int,
    val thin: Boolean,
    val unreachable: Boolean,
    val dialects: List<Int>,
    val flags: List<String>,
)
