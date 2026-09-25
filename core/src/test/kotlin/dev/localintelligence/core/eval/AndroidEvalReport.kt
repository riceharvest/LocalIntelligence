package dev.localintelligence.core.eval

import dev.localintelligence.core.context.SystemPrompts

// ===========================================================================
// AndroidEvalReport.kt — the plain-text rendering.
//
// WHY PLAIN TEXT AND NOT JSON
// ===========================
//
// Because the audience is a tool author who has six other things open, and
// because `docs/evals.md` already pins a plain-text format for the task suite.
// A miss here has to be readable in a CI log in under ten seconds, and it has
// to name the utterance, the tool, and what came back instead. Three columns
// of numbers nobody can act on is not a report.
// ===========================================================================

/**
 * The generalization probe: utterances that were never used to choose a tag.
 *
 * The tuned suite in [AndroidTaskSuite] found the tag defects, and the stubs
 * were then corrected until it scored 100%. That number is real but it is
 * about itself: every tag in [AndroidToolStubs] was chosen with those 34
 * sentences in view. These 25 were written afterwards, deliberately obliquely
 * ("nuke the screenshot", "jot down that the code is 1234", "what did I
 * paste"), and no tag was added to serve them.
 *
 * The gap between the two numbers is the most useful thing this benchmark
 * produces. It is not a defect to be tuned away by adding a tag per probe
 * phrase — that is exactly how the tuned suite reached a meaningless 100%. It
 * is a measurement of where a bag-of-words retriever runs out: its vocabulary
 * is the tool authors' vocabulary, and a user who says "nuke" instead of
 * "delete" is simply invisible to it.
 *
 * Closing that gap is a selector decision (stemming, synonyms, embeddings) or
 * a product decision (show a category picker). It is not a tags decision.
 */
object AndroidGeneralizationProbe {

    val cases: List<AndroidRetrievalCase> = listOf(
        AndroidRetrievalCase("probe/battery", "am I about to run out of power", "device.battery"),
        AndroidRetrievalCase("probe/handset", "what handset is this", "device.info"),
        AndroidRetrievalCase("probe/buzz", "make it go hhh", "device.vibrate"),
        AndroidRetrievalCase("probe/wifi", "show me where wifi is switched on", "device.open_settings"),
        AndroidRetrievalCase("probe/pasteboard", "put this on the pasteboard", "clipboard.write"),
        AndroidRetrievalCase("probe/paste", "what did I paste", "clipboard.read"),
        AndroidRetrievalCase("probe/wake", "remind me to get up at half five", "alarm.create"),
        AndroidRetrievalCase("probe/show-wake", "show my wake ups", "alarm.list"),
        AndroidRetrievalCase("probe/scrap", "scrap the 11pm one", "alarm.cancel"),
        AndroidRetrievalCase("probe/plans", "do I have plans today", "calendar.search"),
        AndroidRetrievalCase("probe/barber", "schedule the barber for friday", "calendar.create"),
        AndroidRetrievalCase("probe/plumber", "mobile number for the plumber", "contacts.search"),
        AndroidRetrievalCase("probe/email", "email address of the plumber", "contacts.get"),
        AndroidRetrievalCase("probe/photos", "where are my photographs", "files.search"),
        AndroidRetrievalCase("probe/nuke", "nuke the screenshot", "files.delete"),
        AndroidRetrievalCase("probe/jot", "jot down that the code is 1234", "files.write_text"),
        AndroidRetrievalCase("probe/whatsapp", "open whatsapp", "apps.open"),
        AndroidRetrievalCase("probe/brother", "send it over to my brother", "apps.share"),
        AndroidRetrievalCase("probe/new", "anything new on my phone", "notifications.list"),
        AndroidRetrievalCase("probe/answer", "answer them", "notifications.reply"),
        AndroidRetrievalCase("probe/banners", "get rid of the banners", "notifications.dismiss"),
        AndroidRetrievalCase("probe/rate", "look up the exchange rate", "web.fetch"),
        AndroidRetrievalCase("probe/apps", "which apps are on here", "apps.list"),
        AndroidRetrievalCase("probe/folder", "what is in my documents", "files.list"),
        AndroidRetrievalCase("probe/readme", "what does the readme say", "files.read_text"),
    )
}

/**
 * The size of the tool set as the model actually sees it.
 *
 * [fullPromptChars] is what it costs to put every tool in the system prompt —
 * the thing the whole selection strategy exists to avoid.
 * [typicalTop6Chars] is what it costs to put the selected six there instead.
 * The ratio is the argument for a selector: if the two numbers were close, the
 * selector would not be worth its complexity.
 */
data class PromptBudget(
    val toolCount: Int,
    val fullPromptChars: Int,
    val typicalSelectionChars: Int,
    val typicalSelectionSize: Int,
) {
    /** Characters per tool, for the full set. */
    val charsPerTool: Double
        get() = if (toolCount == 0) 0.0 else fullPromptChars.toDouble() / toolCount

    fun render(): String = buildString {
        appendLine("tools declared:      $toolCount")
        appendLine("full set prompt:     $fullPromptChars chars (%.0f per tool)".format(charsPerTool))
        appendLine("typical selection:   $typicalSelectionChars chars ($typicalSelectionSize tools)")
        appendLine("saving from selecting: %d chars (%.0f%%)".format(
            fullPromptChars - typicalSelectionChars,
            if (fullPromptChars == 0) 0.0
            else (fullPromptChars - typicalSelectionChars) * 100.0 / fullPromptChars,
        ))
    }

    companion object {
        /**
         * Measures both numbers from the real prompt builder.
         *
         * [SystemPrompts.forTools] is the production path, so a change to it
         * moves these numbers the same day it moves the shipped prompt.
         */
        fun measure(
            toolSet: AndroidToolSet = AndroidToolSet.build(),
            typicalUtterance: String = "what's on my calendar tomorrow",
        ): PromptBudget {
            val all = toolSet.definitions
            val full = SystemPrompts.forTools(all).length
            val selected = LexicalToolSelectorRunner.select(typicalUtterance, toolSet)
            return PromptBudget(
                toolCount = all.size,
                fullPromptChars = full,
                typicalSelectionChars = SystemPrompts.forTools(
                    selected.map { it.definition },
                ).length,
                typicalSelectionSize = selected.size,
            )
        }
    }
}

/** Runs the production selector. Isolated so the budget cannot drift from it. */
object LexicalToolSelectorRunner {
    fun select(utterance: String, toolSet: AndroidToolSet, topN: Int = 6) =
        dev.localintelligence.core.tool.LexicalToolSelector()
            .select(utterance, emptyList(), toolSet.registry.all(), topN)
}

/**
 * The whole benchmark as one renderable value.
 *
 * [hitRate] is the number that matters. [coherence] and [overlap] are the
 * explanations for it: a miss is almost never a selector bug, it is a missing
 * tag on a tool.
 */
data class AndroidEvalReport(
    val retrieval: RetrievalReport,
    val coherence: CoherenceReport,
    val overlaps: List<ToolOverlap>,
    val budget: PromptBudget,
    /** Floor the retrieval rate must clear for the set to be shippable. */
    val hitRateFloor: Double = MIN_HIT_RATE,
    /**
     * Utterances never used to choose a tag, scored on the same tools.
     *
     * Null when the caller did not supply a probe. It is a separate number on
     * purpose: [retrieval] is the tuned suite and [probe] is the honest one, and
     * a report that printed only the first would be a report about itself.
     */
    val probe: RetrievalReport? = null,
) {
    val passed: Boolean
        get() = coherence.coherent && retrieval.hitRate >= hitRateFloor

    companion object {
        /**
         * 85%. Not arbitrary: with a 6-wide window over 25 tools, roughly a
         * fifth of the set is visible by alphabetical accident on a bad query,
         * so anything near 100% means the cases are too easy and anything below
         * 85% means a fifth of real requests are being answered with the wrong
         * tool. The floor is the point where a user starts noticing.
         */
        const val MIN_HIT_RATE = 0.85

        fun build(
            hitRateFloor: Double = MIN_HIT_RATE,
            cases: List<AndroidRetrievalCase> = AndroidTaskSuite.cases,
            probeCases: List<AndroidRetrievalCase>? = AndroidGeneralizationProbe.cases,
        ): AndroidEvalReport {
            val toolSet = AndroidToolSet.build()
            val retrieval = AndroidRetrievalBenchmark(topN = AndroidRetrievalBenchmark.DEFAULT_TOP_N)
                .run(cases, toolSet.registry.all())
            return AndroidEvalReport(
                retrieval = retrieval,
                coherence = AndroidToolCoherence.check(toolSet.definitions),
                overlaps = AndroidOverlapAnalyzer.analyze(),
                budget = PromptBudget.measure(toolSet),
                hitRateFloor = hitRateFloor,
                probe = probeCases?.let { probe ->
                    AndroidRetrievalBenchmark(topN = AndroidRetrievalBenchmark.DEFAULT_TOP_N)
                        .run(probe, toolSet.registry.all())
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

object AndroidEvalReportRenderer {

    private const val NAME_WIDTH = 30

    fun render(report: AndroidEvalReport): String = buildString {
        appendLine("=" .repeat(78))
        appendLine("Android tool coherence + retrieval benchmark")
        appendLine("=".repeat(78))
        appendLine()

        appendCoherence(report.coherence)
        appendRetrieval(report.retrieval, report.hitRateFloor)
        report.probe?.let { appendProbe(it, report.retrieval.hitRate) }
        appendOverlap(report.overlaps)
        appendBudget(report.budget)

        appendLine("=".repeat(78))
        appendLine(
            if (report.passed) {
                "VERDICT: pass  (retrieval %.1f%% >= %.0f%%, %d coherence violations)".format(
                    report.retrieval.hitRate * 100, report.hitRateFloor * 100,
                    report.coherence.violations.size,
                )
            } else {
                "VERDICT: FAIL  (retrieval %.1f%% vs floor %.0f%%, %d coherence violations)".format(
                    report.retrieval.hitRate * 100, report.hitRateFloor * 100,
                    report.coherence.violations.size,
                )
            }
        )
        appendLine("=".repeat(78))
    }

    private fun StringBuilder.appendCoherence(coherence: CoherenceReport) {
        appendLine("COHERENCE")
        appendLine("-".repeat(78))
        if (coherence.coherent) {
            appendLine("all ${AndroidToolStubs.specs.size} tools satisfy every structural rule")
        } else {
            coherence.violations.forEach { appendLine("  [${it.rule}] ${it.render()}") }
            appendLine()
            appendLine("  ${coherence.violations.size} violations, ${coherence.rulesBroken().size} distinct rules")
        }
        appendLine()
    }

    private fun StringBuilder.appendRetrieval(retrieval: RetrievalReport, floor: Double) {
        appendLine("RETRIEVAL  (top-${retrieval.topN}, real LexicalToolSelector)")
        appendLine("-".repeat(78))
        appendLine(
            "hit rate:      %d/%d = %.1f%%   (floor %.0f%%)".format(
                retrieval.hits, retrieval.total, retrieval.hitRate * 100, floor * 100,
            )
        )
        appendLine(
            "earned hits:   %d/%d = %.1f%%   (excludes alphabetical accidents)".format(
                retrieval.earnedHits, retrieval.total, retrieval.earnedRate * 100,
            )
        )
        appendLine("plain cases:   %.1f%%".format(retrieval.hitRateFor { !it.hard } * 100))
        appendLine("hard cases:    %.1f%%".format(retrieval.hitRateFor { it.hard } * 100))
        appendLine("all steps visible: %d/%d".format(retrieval.fullyVisible, retrieval.total))
        appendLine()

        if (retrieval.misses.isEmpty()) {
            appendLine("no misses")
        } else {
            appendLine("MISSES — each is a tag or description defect, not a selector defect")
            appendLine()
            appendLine("  %-46s %-24s %s".format("utterance", "expected", "retrieved instead"))
            appendLine("  " + "-".repeat(74))
            retrieval.misses.forEach { miss ->
                appendLine("  %-46s %-24s %s".format(
                    clip(miss.case.utterance, 46),
                    clip(miss.case.expected, 24),
                    miss.selected.take(6).joinToString(", ").ifEmpty { "<none>" },
                ))
                appendLine("      score ${miss.expectedScore}, top score ${miss.topScore} — add the user's words as tags")
            }
        }

        if (retrieval.weakHits.isNotEmpty()) {
            appendLine()
            appendLine("WEAK HITS (visible by alphabetical accident, score 0 — would break at a larger window)")
            retrieval.weakHits.forEach {
                appendLine("  %-46s %s".format(clip(it.case.utterance, 46), it.case.expected))
            }
        }
        appendLine()
    }

    private fun StringBuilder.appendProbe(probe: RetrievalReport, tunedRate: Double) {
        appendLine("GENERALIZATION PROBE  (same tools, utterances never used to pick a tag)")
        appendLine("-".repeat(78))
        appendLine(
            "hit rate:      %d/%d = %.1f%%   (tuned suite above: %.1f%%)".format(
                probe.hits, probe.total, probe.hitRate * 100, tunedRate * 100,
            )
        )
        appendLine()
        appendLine("This is the number that matters. The tuned suite is a regression guard;")
        appendLine("this is what happens to a user who did not read the tool definitions.")
        appendLine("Closing the gap needs a selector change (stemming, synonyms, embeddings),")
        appendLine("not more tags — see AndroidGeneralizationProbe.")
        appendLine()
        if (probe.misses.isNotEmpty()) {
            appendLine("probe misses:")
            probe.misses.forEach { miss ->
                appendLine("  %-44s -> %s (score %d)".format(
                    clip(miss.case.utterance, 44), miss.case.expected, miss.expectedScore,
                ))
            }
            appendLine()
        }
    }

    private fun StringBuilder.appendOverlap(overlaps: List<ToolOverlap>) {
        appendLine("OVERLAP — pairs a lexical retriever cannot tell apart")
        appendLine("-".repeat(78))
        val confusable = overlaps.filter { it.score >= AndroidOverlapAnalyzer.CONFUSABLE_THRESHOLD }
        if (confusable.isEmpty()) {
            appendLine("no pair at or above ${AndroidOverlapAnalyzer.CONFUSABLE_THRESHOLD}")
        } else {
            appendLine("%-6s %-24s %-24s %s".format("score", "a", "b", "shared tokens"))
            appendLine("  " + "-".repeat(74))
            confusable.forEach { pair ->
                appendLine("  %.2f   %-24s %-24s %s".format(
                    pair.score, pair.a, pair.b, clip(pair.sharedTokens(), 40),
                ))
            }
        }
        appendLine()
        appendLine("worst 8 overall, regardless of threshold")
        appendLine("  " + "-".repeat(74))
        overlaps.take(8).forEach { pair ->
            appendLine("  %.2f   %-24s %-24s %s".format(
                pair.score, pair.a, pair.b, clip(pair.sharedTokens(), 40),
            ))
        }
        appendLine()
    }

    private fun StringBuilder.appendBudget(budget: PromptBudget) {
        appendLine("PROMPT BUDGET")
        appendLine("-".repeat(78))
        append(budget.render())
        appendLine()
    }

    private fun clip(text: String, width: Int): String =
        if (text.length <= width) text else text.take(width - 3) + "..."
}
