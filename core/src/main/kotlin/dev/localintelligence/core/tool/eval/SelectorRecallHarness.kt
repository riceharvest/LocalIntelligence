package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.context.SystemPrompts
import dev.localintelligence.core.model.GrammarBuilder
import dev.localintelligence.core.model.token.DefaultTokenCounter
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolSelector
import dev.localintelligence.core.model.token.TokenCounter
import dev.localintelligence.core.model.token.renderTool

/**
 * Top-k recall for [LexicalToolSelector], over [SelectorDataset], against the
 * 25 tools `:android` ships.
 *
 * ## HOW TO RUN IT
 *
 * ```
 * export JAVA_HOME=$HOME/jdk21
 * ./gradlew :core:compileKotlin
 * ./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-recall-harness.sh
 * ```
 *
 * The script resolves the classpath from the Gradle cache at run time, so the
 * command survives a Kotlin or kotlinx version bump. A `JavaExec` Gradle task
 * would be tidier and is deliberately NOT used: it needs a line in
 * `core/build.gradle.kts`, which another agent owns concurrently, and this
 * change-set adds no dependency and edits no build file.
 *
 * There is no JUnit, no `core/src/test`, no device and no emulator. It is a
 * `main()` on a pure-JVM classpath.
 *
 * ## The one number that matters, and why it is not "accuracy"
 *
 * [dev.localintelligence.core.tool.AgentController] hands the SAME selected
 * list to the system prompt and to `GrammarBuilder.forActions`, and the
 * grammar makes an unselected tool unspeakable. So a retrieval miss is not a
 * worse answer — it is a task the agent cannot perform at all. The honest
 * metric is therefore **"was any correct tool in the grammar"**, and it is an
 * UPPER BOUND on task success, not a component of it. Nothing here measures
 * whether the model then emits a well-formed call with good arguments; that is
 * a different measurement against a real model on a real device, and
 * `docs/evals.md` is right that it does not exist yet.
 *
 * ## What this harness does NOT claim
 *
 * It measures the SELECTOR, on ONE hand-written dataset, with NO model in the
 * loop. It cannot detect that a 1-3B model chooses less reliably from a 10-item
 * grammar than a 6-item one, because there is no model here. Widening the set
 * raises recall and therefore the ceiling; whether the model can actually
 * exploit a wider set is the unmeasured half of the trade, and it stays
 * unmeasured rather than being assumed either way.
 *
 * It is also a snapshot, so re-running it measures the CURRENT selector, not
 * the one that produced any number quoted in a KDoc. That is the point: the
 * numbers in comments were previously unfalsifiable, and these are not.
 */
class SelectorRecallHarness(
    private val selector: ToolSelector = LexicalToolSelector(),
    private val counter: TokenCounter = DefaultTokenCounter,
    val tools: List<AgentTool> = AndroidToolSnapshot.tools,
    private val cases: List<EvalCase> = SelectorDataset.all,
) {

    /** One case's outcome at one k. */
    data class CaseResult(
        val utterance: String,
        val k: Int,
        /** The set the case expects, carried so scoring needs no lookup. */
        val expected: Set<String>,
        /** The names the selector made reachable this turn. */
        val selected: List<String>,
        /** How many expected tools survived the cut. */
        val hits: Int,
        /** How many were expected. */
        val expectedCount: Int,
        /** The tools that fell off the edge. */
        val missed: Set<String>,
        /** The expected tool's own score, to expose the zero-signal cases. */
        val topExpectedScore: Int,
    ) {
        val recall: Double get() = hits.toDouble() / expectedCount

        /** The decisive one: is the task performable at all? */
        val callable: Boolean get() = hits > 0
    }

    data class Report(
        val k: Int,
        val callable: Int,
        val total: Int,
        val expectedToolHit: Int,
        /** Mean tokens of the real production system prompt at this k. */
        val meanSystemPromptTokens: Int,
        val maxSystemPromptTokens: Int,
        val cases: List<CaseResult>,
    ) {
        val recall: Double get() = callable.toDouble() / total
        val expectedToolRecall: Double get() = expectedToolHit.toDouble() / total
    }

    /**
     * Score every case at one k.
     *
     * @param k the visible-set width. Larger than the tool count means "no
     *   cut", which is the honest ceiling row.
     */
    fun run(k: Int): Report {
        val results = cases.map { case -> score(case, k) }
        val promptTokens = results.map { costOfSystemPrompt(it) }
        return Report(
            k = k,
            callable = results.count { it.callable },
            total = results.size,
            // "First choice" is the tool a sensible implementation reaches for
            // first, which is the FIRST name in the expected set. It is
            // reported next to recall because the two answer different
            // questions: recall asks whether the task is possible, this asks
            // whether the obvious tool is the one that survived.
            expectedToolHit = results.count { it.expected.first() in it.selected },
            meanSystemPromptTokens = promptTokens.average().toInt(),
            maxSystemPromptTokens = promptTokens.maxOrNull() ?: 0,
            cases = results,
        )
    }

    private fun score(case: EvalCase, k: Int): CaseResult {
        val selected = selector.select(
            task = case.utterance,
            sessionKeywords = case.sessionKeywords,
            available = tools,
            maxTools = k,
        ).map { it.definition.name }

        // Score the expected tools with the SAME scorer the selector uses, so
        // "the correct tool scored zero" is measured and not asserted. This is
        // the number that says a case is unwinnable by any re-weighting.
        val topExpectedScore = case.expected.maxOf { name ->
            scoreOf(case, name)
        }

        return CaseResult(
            utterance = case.utterance,
            k = k,
            expected = case.expected,
            selected = selected,
            hits = case.expected.count { it in selected },
            expectedCount = case.expected.size,
            missed = case.expected - selected.toSet(),
            topExpectedScore = topExpectedScore,
        )
    }

    /**
     * The selector's own score for one tool, reproduced rather than exposed.
     *
     * [LexicalToolSelector] exposes no score accessor and this harness does not
     * refactor it to add one — that is a finding to report, not a licence to
     * change production code for the benefit of a measurement. So the formula
     * is mirrored here from the shipped source, and [assertMirrorsSelector]
     * proves the mirror agrees with the real thing on every tool in every case.
     * If the selector's scoring ever changes, that check fails and this
     * duplicate has to be re-derived rather than left quietly lying.
     */
    internal fun scoreOf(case: EvalCase, toolName: String): Int {
        val taskTokens = tokenize(case.utterance).toSet()
        val keywordTokens = case.sessionKeywords.flatMap { tokenize(it) }.toSet()
        val tool = tools.first { it.definition.name == toolName }
        val def = tool.definition
        val overlap = taskTokens.intersect(tokenize(def.description).toSet()).size * 2 +
            taskTokens.intersect(def.tags.flatMap { tokenize(it) }.toSet()).size * 3 +
            taskTokens.intersect(tokenize(def.name).toSet()).size * 4 +
            keywordTokens.intersect(tokenize(def.description).toSet()).size +
            keywordTokens.intersect(def.tags.flatMap { tokenize(it) }.toSet()).size
        val substring = if (case.utterance.contains(def.name, ignoreCase = true)) 10 else 0
        return overlap + substring
    }

    /**
     * Mirrors [LexicalToolSelector]'s tokenizer: NFC-normalised, Unicode
     * word-character class, script-dependent `> 2` floor.
     *
     * Duplicated on purpose: [assertMirrorsSelector] compares the ordering
     * this produces against the real selector on every one of the 176 cases,
     * so a divergence here fails the run loudly instead of quietly making
     * every "scored zero" claim in the report fiction. The mirror was
     * updated in lockstep when the selector moved off `Regex("[^a-z0-9]+")`
     * — the check exists precisely so that this comment is true. A
     * `BreakIterator` version of this mirror was tried and reverted for the
     * same reason it was reverted in the selector: it merges `one-time`
     * into one token and loses 2 cases at k=10.
     */
    private fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = java.text.Normalizer.normalize(
            text.lowercase(java.util.Locale.ROOT),
            java.text.Normalizer.Form.NFC,
        )
        val out = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            val charCount = Character.charCount(cp)
            if (isWordCharacter(cp)) {
                current.appendCodePoint(cp)
            } else if (current.isNotEmpty()) {
                out += current.toString()
                current.setLength(0)
            }
            i += charCount
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.filter { token ->
            if (isWordLike(token)) token.length > 2 else true
        }
    }

    private fun isWordCharacter(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.UPPERCASE_LETTER.toInt() ||
            type == Character.LOWERCASE_LETTER.toInt() ||
            type == Character.TITLECASE_LETTER.toInt() ||
            type == Character.MODIFIER_LETTER.toInt() ||
            type == Character.OTHER_LETTER.toInt() ||
            type == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
            type == Character.LETTER_NUMBER.toInt() ||
            type == Character.OTHER_NUMBER.toInt() ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun isWordLike(token: String): Boolean =
        Character.getType(token.codePointAt(0)) != Character.OTHER_LETTER.toInt() ||
            !isUnspacedScript(token.codePointAt(0))

    private fun isUnspacedScript(codePoint: Int): Boolean =
        when (codePoint) {
            in 0x3040..0x30FF -> true
            in 0x3400..0x4DBF -> true
            in 0x4E00..0x9FFF -> true
            in 0xF900..0xFAFF -> true
            in 0xAC00..0xD7AF -> true
            in 0x0E00..0x0E7F -> true
            in 0x20000..0x2FA1F -> true
            else -> false
        }

    /**
     * Every tool in RELEVANCE ORDER, for one case.
     *
     * This used to need a workaround. `LexicalToolSelector.select` opened with
     * `if (available.size <= maxTools) return available`, so asking for the
     * whole set returned the registry's own order with no scoring at all, and
     * the last tool had to be recovered by subtraction. That branch is gone —
     * see the [ToolSelector] KDoc for why removing it is set-preserving — so
     * this is now just the selector asked for everything it has.
     *
     * The workaround is kept in git history rather than here, because its
     * existence was the clearest evidence that the branch was a real trap: a
     * measurement harness had to carry a special case solely to observe a
     * ranking the production code was not actually performing.
     */
    fun fullRanking(case: EvalCase): List<String> =
        selector.select(case.utterance, case.sessionKeywords, tools, tools.size)
            .map { it.definition.name }

    /**
     * Prove the mirrored score in [scoreOf] still matches the real selector.
     *
     * The mirror exists only to expose a number `LexicalToolSelector` does not
     * return. The moment the shipped formula changes, the mirror is wrong and
     * every "scored zero" claim in the report becomes fiction — so this runs
     * on every invocation and returns a description of any disagreement.
     */
    fun assertMirrorsSelector(): List<String> {
        val disagreements = ArrayList<String>()
        for (case in cases) {
            // Ordering the selector produces is (score desc, name asc), so the
            // mirrored score must reproduce that exact order. See
            // [fullRanking] for why this cannot use maxTools = tools.size.
            //
            val actual = fullRanking(case)
            val mirrored = tools
                .map { it.definition.name to scoreOf(case, it.definition.name) }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .map { it.first }
            if (actual != mirrored) {
                disagreements += "score mirror disagrees for \"${case.utterance}\": " +
                    "selector=${actual.take(5)} mirror=${mirrored.take(5)}"
            }
        }
        return disagreements
    }

    /**
     * Price the REAL system prompt at this k, through the real renderer.
     *
     * The KDoc table in `ToolRegistry` quotes "mean system-prompt tokens", and
     * that figure is only meaningful if it comes from
     * [SystemPrompts.forTools] — the string the model actually reads. Pricing
     * [renderTool] instead would include schemas the prompt never carries and
     * overstate the cost, which is the safe direction for a budget and the
     * wrong one for a claim.
     */
    private fun costOfSystemPrompt(case: CaseResult): Int {
        val visible = tools.filter { it.definition.name in case.selected }
        return counter.count(SystemPrompts.forTools(visible.map { it.definition }))
    }

    /**
     * The cost of the whole tool set as the BUDGET sees it, per tool.
     *
     * [renderTool] is what `ContextBudget` prices in its `TOOL` bucket, so this
     * is the number that matters for "can this overrun the working limit" —
     * not the system-prompt figure, which excludes schemas.
     */
    fun budgetedCostPerTool(): List<Pair<String, Int>> =
        tools.map { it.definition.name to counter.count(renderTool(it.definition)) }
            .sortedByDescending { it.second }

    /**
     * The safety check for raising `AgentConfig.maxVisibleTools`.
     *
     * Answers the question the constant change actually raises: at the new
     * width, does the prompt the loop builds still fit inside
     * `AgentConfig.workingTokenLimit` with room for a step? A selector that
     * recalls better but pushes the prompt past the ceiling has traded an
     * uncallable tool for an unrunnable turn, which is not an improvement.
     *
     * The ceiling is priced as [dev.localintelligence.core.model.token.ContextBudget]
     * prices it: system prompt + task, plus the reply reserve. Reported rather
     * than asserted, because the honest answer depends on the model's real
     * context length, which this harness does not have.
     */
    fun widthSafetyReport(k: Int): String {
        val config = AgentConfig()
        val promptCeiling = config.workingTokenLimit - CONTEXT_BUDGET_OUTPUT_RESERVE
        val selectedPerCase = cases.map { case ->
            selector.select(case.utterance, case.sessionKeywords, tools, k)
        }
        val worstPrompt = selectedPerCase.maxOf { visible ->
            counter.count(SystemPrompts.forTools(visible.map { it.definition })) +
                WORST_CASE_TASK_TOKENS
        }
        val worstToolSet = budgetedCostPerTool().take(k).sumOf { it.second }
        return buildString {
            appendLine("  k=$k")
            appendLine("    worst-case system prompt + task: $worstPrompt tokens")
            appendLine("    prompt ceiling (workingLimit ${config.workingTokenLimit} - $CONTEXT_BUDGET_OUTPUT_RESERVE reserve): $promptCeiling")
            appendLine("    headroom: ${promptCeiling - worstPrompt} tokens")
            appendLine("    worst-case k-tool budget cost (schemas included): $worstToolSet tokens")
            appendLine("    -> system prompt ${if (worstPrompt < promptCeiling) "FITS" else "OVERRUNS"} the ceiling")
        }
    }

    companion object {
        /**
         * Mirrors [dev.localintelligence.core.model.token.ContextBudget.DEFAULT_OUTPUT_RESERVE].
         *
         * Duplicated rather than imported because that constant is public and
         * importing it is correct; this is spelled out only to keep the
         * arithmetic visible at the point of use. If it ever moves, the
         * harness reports a wrong ceiling — check both.
         */
        const val CONTEXT_BUDGET_OUTPUT_RESERVE = 256

        /**
         * A plausible task length for the safety check.
         *
         * The dataset's real utterances are short, and using them would make
         * the check optimistic. 40 tokens is roughly a two-sentence request and
         * is deliberately pessimistic about the tail.
         */
        const val WORST_CASE_TASK_TOKENS = 40
    }
}

/**
 * Prints the report. A `main()` so the harness runs with no test task, no
 * JUnit, and no build-file change — see the file header for the exact command.
 *
 * ## Why a main() and not a Gradle JavaExec task
 *
 * A `JavaExec` task would be the tidier invocation and needs a line in
 * `core/build.gradle.kts`, which another agent owns concurrently. A `main()`
 * needs nothing, and `:core:compileKotlin` plus `java -cp` is a command anyone
 * can read and verify. The cost is that the classpath is spelled out in the
 * header instead of being resolved by Gradle; the benefit is that the harness
 * cannot be broken by a build change it does not own.
 *
 * ## Reading the output honestly
 *
 *  - **Recall** is an upper bound on task success, not a success rate. A case
 *    counted as a hit has a CALLABLE tool; nothing here says the model then
 *    calls it correctly.
 *  - **`all`** is the no-cut ceiling. If a case fails there, the dataset
 *    disagrees with the tool set, not the selector.
 *  - **Scored zero** counts cases where the best expected tool shares no word
 *    with any description or tag. No re-weighting fixes those, and they are
 *    the ones worth a tag-list fix.
 *  - **Tie rate** is the fraction of turns where the k-th and (k+1)-th tools
 *    score identically, which is what makes an alphabetical tie-break decide
 *    most turns.
 */
fun main() {
    val harness = SelectorRecallHarness()
    val config = AgentConfig()
    val tools = AndroidToolSnapshot.tools

    println("=".repeat(78))
    println("TOOL SELECTION RECALL — LexicalToolSelector over the shipped 25-tool set")
    println("=".repeat(78))
    println("shipped AgentConfig.maxVisibleTools = ${config.maxVisibleTools}   (the constant under test)")
    println("cases: ${SelectorDataset.all.size}   tools: ${AndroidToolSnapshot.tools.size}")

    val catalogueProblems = AndroidToolSnapshot.verifyAgainstCatalogue()
    if (catalogueProblems.isEmpty()) {
        println("snapshot vs V0ToolCatalogue: AGREE (names, categories, risk tiers)")
    } else {
        println("snapshot vs V0ToolCatalogue: ${catalogueProblems.size} DISAGREEMENT(S)")
        catalogueProblems.forEach { println("    $it") }
        println("    -> every number below describes a tool set that may not ship.")
    }

    val mirrorProblems = harness.assertMirrorsSelector()
    if (mirrorProblems.isEmpty()) {
        println("score mirror: AGREES with LexicalToolSelector on all ${SelectorDataset.all.size} cases")
    } else {
        println("score mirror: ${mirrorProblems.size} DISAGREEMENT(S) — the mirrored")
        println("    score in this harness is STALE. Re-derive it from ToolRegistry.kt")
        println("    before quoting any 'scored zero' number.")
        mirrorProblems.take(5).forEach { println("    $it") }
    }

    println()
    println("COVERAGE (cases whose expected set includes each tool)")
    val coverage = SelectorDataset.coverageByTool()
    val minCoverage = coverage.values.minOrNull() ?: 0
    for ((tool, count) in coverage) {
        println("    ${tool.padEnd(24)} $count")
    }
    println("    min coverage: $minCoverage")

    val ks = listOf(3, 6, 10, 12, AndroidToolSnapshot.tools.size)
    println()
    println("RECALL BY VISIBLE-SET WIDTH")
    println("    k    callable    recall   first-choice    mean system-prompt tokens")
    val reports = ks.map { k -> k to harness.run(k) }
    for ((k, report) in reports) {
        val label = if (k == AndroidToolSnapshot.tools.size) "all" else k.toString()
        println(
            "    ${label.padEnd(4)} " +
                "${report.callable}/${report.total}".padEnd(12) +
                "${pct(report.recall)}".padEnd(9) +
                "${report.expectedToolHit}/${report.total}".padEnd(15) +
                report.meanSystemPromptTokens,
        )
    }

    println()
    println("WHAT WIDER ACTUALLY BUYS (cases that flip from unreachable to callable)")
    val byK = reports.toMap()
    for (pair in listOf(3 to 6, 6 to 10, 10 to 12, 6 to AndroidToolSnapshot.tools.size)) {
        val (from, to) = pair
        val gained = byK[to]!!.cases.filter { it.callable }.map { it.utterance }.toSet() -
            byK[from]!!.cases.filter { it.callable }.map { it.utterance }.toSet()
        println("    $from -> $to: ${gained.size} newly callable")
    }

    println()
    println("WHERE IT STILL FAILS AT k=${config.maxVisibleTools} (the shipped default)")
    val shipped = byK[config.maxVisibleTools]!!
    val misses = shipped.cases.filter { !it.callable }
    if (misses.isEmpty()) {
        println("    none")
    } else {
        misses.forEach { m ->
            println("    \"${m.utterance}\"  -> wanted ${m.missed.joinToString()}, got ${m.selected.joinToString()}")
        }
    }

    println()
    println("CEILING CHECK — cases no width can fix (expected tool scores ZERO)")
    val zero = byK[AndroidToolSnapshot.tools.size]!!.cases.filter { it.topExpectedScore == 0 }
    println("    ${zero.size}/${SelectorDataset.all.size} cases (${pct(zero.size.toDouble() / SelectorDataset.all.size)})")
    zero.forEach { println("    \"${it.utterance}\"") }

    println()
    println("TIE RATE — turns where the k-th and (k+1)-th tools score IDENTICALLY,")
    println("so the cut is decided by the selector's alphabetical `thenBy { name }`")
    for (k in listOf(3, 6, 10, 12)) {
        var tied = 0
        var scorable = 0
        for (case in SelectorDataset.all) {
            val order = harness.fullRanking(case)
            if (order.size <= k) continue
            scorable++
            if (harness.scoreFor(case, order[k - 1]) == harness.scoreFor(case, order[k])) {
                tied++
            }
        }
        println(
            "    k=$k: $tied/$scorable (${pct(tied.toDouble() / scorable)}) of turns " +
                "decided by name, not by score",
        )
    }

    println()
    println("WIDTH SAFETY — does a wider grammar push the prompt past the working limit?")
    ks.forEach { k -> print(harness.widthSafetyReport(k)) }

    println()
    println("GRAMMAR SIZE (chars; the grammar is a SAMPLER constraint and costs")
    println("no context tokens, so this is prefill/parse overhead only)")
    for (k in ks) {
        val widest = SelectorDataset.all.maxBy { it.utterance.length }
        val visible = LexicalToolSelector().select(widest.utterance, widest.sessionKeywords, tools, k)
        println("    k=$k: ${GrammarBuilder.forActions(visible.map { it.definition }).length} chars")
    }

    println()
    println("=".repeat(78))
}

private fun pct(fraction: Double): String = "${(fraction * 100).let { "%.1f".format(it) }}%"

/**
 * Public accessor for one tool's mirrored score.
 *
 * Needed by the tie-rate block, which is outside the class. Kept public and
 * named for what it is — a MIRROR — so nobody reads it as the selector's own
 * API, which does not expose scores at all.
 */
fun SelectorRecallHarness.scoreFor(case: EvalCase, toolName: String): Int = scoreOf(case, toolName)
