package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.tool.LexicalToolSelector

/**
 * Prints the multi-turn report.
 *
 * ## HOW TO RUN IT
 *
 * ```
 * export JAVA_HOME=$HOME/jdk21
 * ./gradlew :core:compileKotlin
 * ./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-multiturn-harness.sh
 * ```
 *
 * A `main()` rather than a JUnit test, for the same reasons as
 * [SelectorRecallHarness]: no test source set, no build-file change, no
 * dependency, and something a human can run with one command and read.
 *
 * ## HOW TO READ IT, INCLUDING THE PARTS THAT ARE UNFLATTERING
 *
 *  - The CONTROL row is multi-turn conversations whose turns each stand alone.
 *    The REFERENTIAL row is conversations where a turn reaches back. The gap
 *    between them is the finding; neither number means anything alone.
 *  - **"Wanted but unreachable"** is the number that matters. It counts turns
 *    where the model was shown the referent and the grammar did not contain
 *    the tool, so the correct call was unrepresentable. Recall is an UPPER
 *    BOUND on task success, not a success rate.
 *  - **Coverage is printed even when it is unflattering.** A tool no turn
 *    reaches is a hole in the corpus, and a tool that is selected on turns
 *    that did not want it is a selector pulling the wrong way. Both are
 *    printed as counts, with the ones the corpus cannot explain marked.
 */
fun main() {
    val harness = MultiTurnHarness()
    val config = AgentConfig()
    val width = config.maxVisibleTools

    println("=".repeat(78))
    println("MULTI-TURN TOOL SELECTION — LexicalToolSelector replayed through Session")
    println("=".repeat(78))
    println("shipped AgentConfig.maxVisibleTools = $width")
    println(
        "scenarios: ${MultiTurnDataset.all.size} " +
            "(${MultiTurnDataset.referential.size} referential, " +
            "${MultiTurnDataset.controls.size} control)   " +
            "turns: ${MultiTurnDataset.turns.size}   tools: ${harness.tools.size}",
    )
    println("keywords come from the real Session.currentKeywords(); no model is involved")

    val report = harness.run(width)

    println()
    println("RECALL BY VISIBLE-SET WIDTH")
    println("    k     turns-ok  recall  slot-recall  control  referential")
    val ks = listOf(3, 6, width, 12, harness.tools.size)
    for (k in ks.distinct()) {
        val r = harness.run(k)
        val byIntent = harness.byIntent(r)
        val control = byIntent[TurnIntent.SELF_CONTAINED] ?: (0 to 0)
        val referentialHits = byIntent
            .filterKeys { it != TurnIntent.SELF_CONTAINED }
            .values
            .fold(0 to 0) { a, b -> (a.first + b.first) to (a.second + b.second) }
        val label = if (k == harness.tools.size) "all" else k.toString()
        println(
            "    ${label.padEnd(4)} " +
                "${r.callable}/${r.total}".padEnd(12) +
                pct(r.recall).padEnd(9) +
                pct(r.slotRecall).padEnd(13) +
                "${control.first}/${control.second}".padEnd(9) +
                "${referentialHits.first}/${referentialHits.second}",
        )
    }
    println()
    println("    turns-ok    = turns where at least ONE expected tool was callable")
    println("    slot-recall = expected tool-slots reached / expected tool-slots.")
    println("                  The lower of the two is the honest figure: a turn")
    println("                  wanting {alarm.create, device.battery} that only got the")
    println("                  alarm is a turns-ok pass and a slot-recall failure.")

    println()
    println("RECALL BY INTENT (at k=$width, the shipped width)")
    println("    intent                  callable")
    for ((intent, pair) in harness.byIntent(report)) {
        val hit = pair.first.toString().padEnd(10)
        println("    ${intent.name.padEnd(22)} $hit${pair.second}")
    }

    val byDepth = harness.byDepth(report)
    if (byDepth.isNotEmpty()) {
        println()
        println("RECALL BY HOW FAR BACK THE TURN REACHES (referential turns only)")
        println("    turns back   callable")
        for ((depth, pair) in byDepth) {
            println("    ${depth.toString().padEnd(12)} ${pair.first}/${pair.second}")
        }
    }

    // THE HEADLINE. Every one of these is a turn where the conversation had
    // already established the subject, the model would have been shown it, and
    // the correct tool was not in the grammar anyway.
    val unreachable = report.results.filter { !it.callable }
    val controlMisses = unreachable.filter { it.intent == TurnIntent.SELF_CONTAINED }
    val referentialMisses = unreachable.filter { it.intent != TurnIntent.SELF_CONTAINED }

    println()
    println("WANTED BUT UNREACHABLE AT k=$width — every expected tool missed")
    println("    ${unreachable.size}/${report.total} turns (${pct(unreachable.size.toDouble() / report.total)})")
    println("    ${referentialMisses.size} referential, ${controlMisses.size} control")
    for (miss in unreachable) {
        val flag = if (miss.subjectAbsentFromQuery) " [subject not in query]" else ""
        println(
            "    ${miss.label.padEnd(28)} \"${miss.utterance}\"$flag\n" +
                "        wanted ${miss.missed.joinToString()}, " +
                "got ${miss.selected.take(4).joinToString()}" +
                if (miss.selected.size > 4) " +${miss.selected.size - 4}" else "",
        )
    }

    // THE PARTIAL FAILURES, and the reason they are printed separately: they
    // are invisible in every recall number above. A turn expecting two tools
    // that got one counts as a pass in the turns-ok column, so without this
    // section the table says 40/43 when the truth is that 6 turns were served
    // incompletely and one of those is the exact battery-reminder case this
    // harness was written to catch.
    val partialOnly = report.results.filter { it.callable && it.hits < it.expected.size }
    println()
    println("PARTIALLY REACHABLE AT k=$width — at least one expected tool MISSED")
    println("    ${partialOnly.size}/${report.total} turns, slot-recall " +
        "${pct(report.slotRecall)} vs turns-ok ${pct(report.recall)}")
    for (p in partialOnly) {
        val flag = if (p.subjectAbsentFromQuery) " [subject not in query]" else ""
        println(
            "    ${p.label.padEnd(28)} \"${p.utterance}\"$flag\n" +
                "        wanted ${p.expected.joinToString()}, " +
                "missing ${p.missed.joinToString()}",
        )
    }
    if (partialOnly.isNotEmpty()) {
        println()
        println("    -> A turn scores as a pass above while part of what the user asked")
        println("       for is uncallable. Because the grammar is what makes a tool")
        println("       speakable, the missing half is not a worse answer — the agent")
        println("       cannot perform that part of the request at all.")
    }

    // The security/recall tension, stated rather than resolved. Feeding tool
    // observations back into retrieval is what SessionKeywordTrustTest forbids,
    // and correctly so; the cost is that observation-dependent follow-ups are
    // unreachable. The harness reports both sides instead of pretending the
    // corpus has no such cases.
    val obsDependent = report.results.filter { it.intent == TurnIntent.OBSERVATION_DEPENDENT }
    if (obsDependent.isNotEmpty()) {
        val obsOk = obsDependent.count { it.callable }
        println()
        println("THE TRUST-BOUNDARY COST, measured rather than argued")
        println("    OBSERVATION_DEPENDENT turns: ${obsDependent.size}, reachable: $obsOk")
        println("    A follow-up that needs a prior RESULT (a file name, a contact id)")
        println("    cannot be served, because Session.currentKeywords() deliberately")
        println("    excludes tool observations — see SessionKeywordTrustTest, where")
        println("    that exclusion is pinned as a security boundary against untrusted")
        println("    page text steering the grammar. The exclusion is correct; the")
        println("    consequence is a product gap. Both are reported; neither is hidden")
        println("    by choosing a different definition of recall.")
    }

    printAsymmetry(harness)
    printCoverage(harness, report)

    println()
    println("=".repeat(78))
}

/** The width the single-turn columns are measured at: the shipped default. */
private const val DEFAULT_K = 10

/**
 * The coverage table, and the reason it is printed unconditionally.
 *
 * A retrieval harness that reports only a headline number can hide two OPPOSITE
 * failures: a tool no case ever reaches (the corpus does not cover the
 * product) and a tool selected on cases that did not want it (the selector is
 * pulling the wrong way). The first flatters recall by shrinking the
 * denominator; the second flatters it by making the numerator look busy.
 * Neither is visible in a recall percentage, so the table prints even on a run
 * where every recall row looks acceptable.
 *
 * ## Every column is over ONE population
 *
 * The `1-turn` block is over [SelectorDataset.all] and the `multi` block is over
 * the replayed scenarios. They are never summed or placed in a shared column,
 * because the first version of this table did exactly that and produced rows
 * like `alarm.cancel: expected 7, selected 35, unwanted 177` — three numbers
 * from three different populations, supporting no conclusion whatsoever. A
 * coverage table that cannot support a conclusion is worse than none, because
 * it looks like evidence.
 */
private fun printCoverage(harness: MultiTurnHarness, report: MultiTurnHarness.Report) {
    val selector = LexicalToolSelector()

    // Single-turn selection, at the shipped width. Measured here rather than
    // taken from the other harness so both columns come from one code path and
    // one k, and cannot drift apart.
    val singleSelected = HashMap<String, Int>()
    val singleUnwanted = HashMap<String, Int>()
    for (case in SelectorDataset.all) {
        val selected = selector
            .select(case.utterance, case.sessionKeywords, harness.tools, DEFAULT_K)
            .map { it.definition.name }
        selected.forEach { name -> singleSelected[name] = (singleSelected[name] ?: 0) + 1 }
        selected.filterNot { it in case.expected }.forEach { name ->
            singleUnwanted[name] = (singleUnwanted[name] ?: 0) + 1
        }
    }

    val rows = harness.coverage(report, singleSelected, singleUnwanted)
    val uncovered = rows.filter { it.uncovered }
    val neverSelected = rows.filter { it.neverSelected }
    val expectedNeverSelected = rows.filter { it.multiExpected > 0 && it.multiSelected == 0 }

    println()
    println("=".repeat(78))
    println("COVERAGE — all ${rows.size} shipped tools, both corpora separately")
    println("=".repeat(78))
    println()
    println("  1-TURN CORPUS (${SelectorDataset.all.size} cases)   MULTI-TURN CORPUS " +
        "(${report.total} turns)")
    println("  tool                  exp  sel  unwant  |  exp  sel  unwant  miss")
    println("  " + "-".repeat(70))

    for (r in rows) {
        val flags = buildList {
            if (r.uncovered) add("NEVER EXPECTED BY ANY CASE")
            if (r.neverSelected) add("NEVER SELECTED")
            if (r.multiExpected > 0 && r.multiSelected == 0) add("MULTI: EXPECTED BUT NEVER SELECTED")
        }
        println(
            "  ${r.tool.padEnd(20)} " +
                "${r.singleExpected.toString().padStart(3)} " +
                "${r.singleSelected.toString().padStart(4)} " +
                "${r.singleUnwanted.toString().padStart(6)}  |" +
                "${r.multiExpected.toString().padStart(4)} " +
                "${r.multiSelected.toString().padStart(4)} " +
                "${r.multiUnwanted.toString().padStart(6)} " +
                "${r.multiMissed.toString().padStart(4)}  " +
                flags.joinToString(", "),
        )
    }
    println("  " + "-".repeat(70))
    println()

    // The three questions the table exists to answer, each answered with a
    // count and a verdict rather than left for the reader to derive.
    println("  Q1  tools NEVER EXPECTED by any case in either corpus: ${uncovered.size}/${rows.size}")
    if (uncovered.isEmpty()) {
        println("      none — every shipped tool is exercised by at least one case")
    } else {
        uncovered.forEach { r ->
            println("      ${r.tool} — ${r.reason ?: "NO REASON RECORDED (corpus hole)"}")
        }
    }

    println()
    println("  Q2  tools the selector NEVER selects, in either corpus: ${neverSelected.size}/${rows.size}")
    if (neverSelected.isEmpty()) {
        println("      none — every tool reaches a grammar at least once")
    } else {
        neverSelected.forEach { println("      ${it.tool}") }
    }

    println()
    println("  Q3  tools a multi-turn turn wants and NEVER gets on ANY turn: " +
        "${expectedNeverSelected.size}")
    if (expectedNeverSelected.isEmpty()) {
        println("      none — every tool some turn wants reaches a grammar on some turn")
    } else {
        println("      ${expectedNeverSelected.joinToString(", ") { it.tool }}")
        println("      -> selector failures, not coverage holes: the corpus states the")
        println("         right tool and no grammar ever contains it.")
    }

    val singleUnwantedTotal = rows.sumOf { it.singleUnwanted }
    val multiUnwantedTotal = rows.sumOf { it.multiUnwanted }
    println()
    println("  Q4  SELECTED WHERE NOT EXPECTED — tools occupying a grammar slot that no")
    println("      reading of the utterance supports")
    println("      1-turn corpus: $singleUnwantedTotal of ${SelectorDataset.all.size * DEFAULT_K} " +
        "selections (${pct(singleUnwantedTotal.toDouble() / (SelectorDataset.all.size * DEFAULT_K))})")
    println("      multi corpus:  $multiUnwantedTotal of ${report.total * report.k} " +
        "selections (${pct(multiUnwantedTotal.toDouble() / (report.total * report.k))})")
    println("      At k=$DEFAULT_K of ${rows.size} tools a high share is arithmetic, not a defect:")
    println("      the selector MUST fill its slots. What matters is whether the")
    println("      unwanted tools DISPLACE a wanted one, which is what the miss")
    println("      column counts.")
}

/**
 * Prints the asymmetry check, including the grammar that makes a miss fatal.
 *
 * This section exists because every other number in the report is an UPPER
 * BOUND whose interpretation depends on one unproven claim — that the model
 * can see the referent the selector cannot. A reader who does not accept that
 * should be able to check it here rather than take it from a KDoc.
 *
 * The grammar printout is the part that converts "a tool was not selected"
 * into "a tool cannot be called": the tool's name simply does not occur in the
 * production the sampler must match, so no amount of model capability produces
 * the call.
 */
private fun printAsymmetry(harness: MultiTurnHarness) {
    val results = AsymmetryProbe(harness.tools).run()
    val asymmetric = results.count { it.asymmetric }

    println()
    println("=".repeat(78))
    println("ASYMMETRY CHECK — can each side of the loop see the referent?")
    println("=".repeat(78))
    println("  model side    = DefaultContextBuilder over a real Session")
    println("  selector side = Session.currentKeywords(), the exact call")
    println("                  AgentController.selectTools makes")
    println()
    println("  probe                              subject  model  selector")
    for (r in results) {
        println(
            "  ${r.name.padEnd(34)} ${r.subject.padEnd(8)} " +
                "${if (r.modelSees) "yes" else "no "}     " +
                "${if (r.selectorSees) "yes" else "NO "}",
        )
    }
    println()
    println("  asymmetric (model sees it, selector does not): $asymmetric/${results.size}")
    if (asymmetric == 0) {
        println("  -> THE FINDING HAS CHANGED. Either the product now carries history")
        println("     into retrieval, or this probe is measuring the wrong thing. The")
        println("     recall numbers above still hold; this section's INTERPRETATION")
        println("     does not. Investigate before quoting the gap as a product bug.")
    } else {
        println("  -> the model is shown the referent and the grammar is not offered the")
        println("     tool that acts on it. That is the mechanism behind the failures above.")
    }

    // Make "unselected" mean "uncallable" concrete, on the real grammar.
    val missed = harness.tools
        .filter { it.definition.name == "device.battery" }
        .firstOrNull()
    if (missed != null) {
        val withBattery = grammarMentions(listOf(missed))
        val withoutBattery = grammarMentions(harness.tools.filterNot { it === missed })
        println()
        println("  GRAMMAR CONSEQUENCE (device.battery, the tool the battery case needs)")
        println("    grammar with it    : ${withBattery.length} chars, mentions it: " +
            "${withBattery.contains("device.battery")}")
        println("    grammar without it : ${withoutBattery.length} chars, mentions it: " +
            "${withoutBattery.contains("device.battery")}")
        println("    -> a tool absent from the grammar has no production the sampler can")
        println("       match, so the correct call is not discouraged, it is UNREPRESENTABLE.")
    }
}
