package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.context.SystemPrompts
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.token.DefaultTokenCounter
import dev.localintelligence.core.model.token.renderTool
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolSelector

/**
 * Top-k SELECTABILITY for [LexicalToolSelector] over [HeldOutDataset].
 *
 * ## HOW TO RUN IT
 *
 * ```
 * export JAVA_HOME=$HOME/jdk21
 * ./gradlew :core:compileKotlin
 * ./core/src/main/kotlin/dev/localintelligence/core/tool/eval/run-heldout-harness.sh
 * ```
 *
 * A `main()` on a pure-JVM classpath: no JUnit, no `core/src/test`, no device,
 * no emulator, no model, and no build-file change. The run script resolves the
 * classpath from the Gradle cache at run time, so it survives a Kotlin or
 * kotlinx version bump.
 *
 * ## THE ONE QUESTION THIS ANSWERS, AND THE ONE IT DOES NOT
 *
 * This measures **selectability**: was at least one tool that could correctly
 * serve the request inside the visible set the loop would show the model?
 *
 * It does NOT measure **choice**: whether the model then emitted a well-formed
 * call to that tool, with good arguments. Those are different questions and
 * conflating them is how a corpus ends up reporting 100% for a selector that
 * works. The reason selectability is nevertheless the number worth leading with
 * is that it is the one that bounds the other:
 *
 *  - [dev.localintelligence.core.agent.AgentController] hands the SAME selected
 *    list to the system prompt and to [dev.localintelligence.core.model.GrammarBuilder],
 *    and the grammar makes an unselected tool *unspeakable*. A retrieval miss is
 *    not a worse answer, it is a task the agent cannot perform at all.
 *  - So `choice_rate <= selectability`, always. Selectability is an UPPER BOUND
 *    on task success, not a component of it, and the ceiling is only tight if
 *    the model is good — which is the unmeasured half.
 *
 * Every number in the report is labelled as one or the other. There is no
 * "accuracy" column, and adding one would be a category error.
 *
 * ## WHY THE MULTI-TURN CASES REPLAY A REAL SESSION
 *
 * [HeldOutDataset.ReferentialCase] carries a prior turn and a prior tool
 * result. Those are pushed through the real [Session] and the keywords are read
 * back from `Session.currentKeywords()` — the exact call
 * `AgentController.selectTools` makes. Hand-writing the keyword list instead
 * would grant the selector a memory it does not have, and the referential
 * population is the one place that difference is the whole result.
 *
 * ## WHY THE SCORE IS MIRRORED AND THE MIRROR IS PROVEN
 *
 * [LexicalToolSelector] returns a list and no score, and this harness does not
 * refactor production code to expose one — a missing accessor is a finding to
 * report, not a licence to change the thing under measurement. So the formula
 * is mirrored in [mirrorScoreOf] and [assertMirrorsSelector] proves the mirror
 * reproduces the shipped ordering on every tool of every case. If the selector
 * changes, that check fails loudly instead of the "scored zero" claims quietly
 * becoming fiction.
 *
 * The mirror is also what makes the TAUTOLOGY CHECK possible, which is the
 * check that keeps this corpus honest.
 */
class HeldOutHarness(
    private val selector: ToolSelector = LexicalToolSelector(),
    val tools: List<AgentTool> = HeldOutToolSnapshot.tools,
) {

    /** One scored turn, carrying everything the report needs to be honest. */
    data class CaseResult(
        val utterance: String,
        val dialect: HeldOutCase.Dialect,
        val intent: HeldOutCase.Intent,
        val referential: Boolean,
        val expected: Set<String>,
        val selected: List<String>,
        val hits: Int,
        val expectedCount: Int,
        val missed: Set<String>,
        /** Best score any expected tool earned, via the mirrored formula. */
        val topExpectedScore: Int,
        /**
         * Whether the expected tool is reachable ONLY because of a tag.
         *
         * The tautology signal. See [tautological].
         */
        val tagDependent: Boolean,
        /** Session keywords actually used, carried for the failure dump. */
        val sessionKeywords: List<String>,
    ) {
        val recall: Double get() = hits.toDouble() / expectedCount

        /** The decisive one: is the task performable at all this turn? */
        val callable: Boolean get() = hits > 0

        /**
         * True when every expected tool's score comes from tag overlap alone.
         *
         * ## What this does and does not accuse
         *
         * A case whose expected tool is reachable only because the utterance
         * happens to contain a word from a tag is a case that measures the tag
         * list, not the selector's judgement. That is not automatically bad —
         * tags are retrieval fuel by design, and
         * [dev.localintelligence.core.tool.catalogue.V0ToolCatalogue] says the
         * tag is where the inflection a user types is supposed to live. It
         * becomes a problem when the corpus was written while reading those
         * tags, because then the tag and the case were written to fit each
         * other.
         *
         * This corpus was written without reading a tag (R1 in
         * [HeldOutDataset]), so a high tag-dependence count is a property of the
         * selector and the tag lists, not evidence of overfitting. But it is
         * still the number a reader needs, because it says how much of the
         * headline is carried by the part of the definition nobody reads.
         */
        val tautological: Boolean get() = tagDependent
    }

    data class Report(
        val k: Int,
        val callable: Int,
        val total: Int,
        val expectedToolHit: Int,
        val slotCallable: Int,
        val slotTotal: Int,
        val meanSystemPromptTokens: Int,
        val cases: List<CaseResult>,
    ) {
        val recall: Double get() = callable.toDouble() / total
        val slotRecall: Double get() = if (slotTotal == 0) 0.0 else slotCallable.toDouble() / slotTotal
    }

    // ---------------------------------------------------------------- scoring

    /**
     * Every scored turn, in the order the report prints it.
     *
     * Single-turn cases carry no keywords; referential cases carry the ones the
     * real [Session] produced. Both go through the same scoring path, so the two
     * populations are comparable rather than merely adjacent.
     */
    fun scoredTurns(): List<CaseResult> {
        val single = HeldOutDataset.freshSession.map { case ->
            score(
                utterance = case.utterance,
                expected = case.expected,
                keywords = case.sessionKeywords,
                referential = false,
                intent = case.intent,
                dialect = case.dialect,
            )
        }
        val referential = HeldOutDataset.referentialCases.map { case ->
            score(
                utterance = case.utterance,
                expected = case.expected,
                keywords = keywordsFor(case),
                referential = true,
                intent = case.intent,
                dialect = case.dialect,
            )
        }
        return single + referential
    }

    /**
     * The keywords a referential turn ACTUALLY gets, from the real Session.
     *
     * The prior tool result goes in as a [ChatMessage.ToolObservation] exactly
     * as the loop would append it, and is then read back through
     * `currentKeywords()`. It is in the session for the model's benefit and,
     * by design, absent from the selector's view — which is the asymmetry this
     * population measures.
     */
    private fun keywordsFor(case: HeldOutDataset.ReferentialCase): List<String> {
        val session = Session()
        for (turn in case.priorTurns) session.start(turn)
        val call = case.priorCall
        if (call != null) {
            val tool = tools.firstOrNull { it.definition.name == call }
            if (tool != null) {
                session.appendToolObservation(tool, case.priorObservation.orEmpty(), true)
            } else {
                // A prior call naming a tool this snapshot does not carry would
                // silently drop the observation and quietly turn a referential
                // case into a self-contained one. Fail loudly instead.
                error(
                    "held-out referential case \"${case.utterance}\" names priorCall=$call, " +
                        "which is not in the snapshot. The scenario cannot be replayed faithfully.",
                )
            }
        }
        session.start(case.utterance)
        return session.currentKeywords()
    }

    private fun score(
        utterance: String,
        expected: Set<String>,
        keywords: List<String>,
        referential: Boolean,
        intent: HeldOutCase.Intent,
        dialect: HeldOutCase.Dialect,
    ): CaseResult {
        val selected = selector.select(utterance, keywords, tools, tools.size)
            .map { it.definition.name }
        val topExpectedScore = expected.maxOf { mirrorScoreOf(utterance, keywords, it) }
        val tagDependent = expected.all { name ->
            val def = tools.first { it.definition.name == name }.definition
            val taskTokens = tokenize(utterance).toSet()
            val keywordTokens = keywords.flatMap { tokenize(it) }.toSet()
            val fromTags = taskTokens.intersect(def.tags.flatMap { tokenize(it) }.toSet()).size * 3 +
                keywordTokens.intersect(def.tags.flatMap { tokenize(it) }.toSet()).size
            val fromEverythingElse = mirrorScoreOf(utterance, keywords, name) - fromTags
            fromTags > 0 && fromEverythingElse == 0
        }
        return CaseResult(
            utterance = utterance,
            dialect = dialect,
            intent = intent,
            referential = referential,
            expected = expected,
            selected = selected,
            hits = expected.count { it in selected },
            expectedCount = expected.size,
            missed = expected - selected.toSet(),
            topExpectedScore = topExpectedScore,
            tagDependent = tagDependent,
            sessionKeywords = keywords,
        )
    }

    /**
     * The shipped scorer's tokeniser, mirrored.
     *
     * `[^a-z0-9]+` with a length > 2 floor, copied exactly. This is the line
     * that makes every non-ASCII word invisible to the selector, so it is worth
     * stating plainly rather than paraphrasing: Dutch and German utterances lose
     * every token that carries an accent, and the dialect table further down is
     * a direct measurement of that, not a guess about it.
     */
    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }

    /**
     * The shipped scorer's formula, mirrored.
     *
     * Byte-for-byte the arithmetic in
     * [dev.localintelligence.core.tool.LexicalToolSelector.select]: description
     * overlap x2, tag overlap x3, name overlap x4, session-keyword overlap on
     * description x1 and tags x1, plus a flat +10 when the tool's full name is a
     * substring of the task. [assertMirrorsSelector] fails the run if this stops
     * matching.
     */
    internal fun mirrorScoreOf(utterance: String, keywords: List<String>, toolName: String): Int {
        val taskTokens = tokenize(utterance).toSet()
        val keywordTokens = keywords.flatMap { tokenize(it) }.toSet()
        val def = tools.first { it.definition.name == toolName }.definition
        val overlap = taskTokens.intersect(tokenize(def.description).toSet()).size * 2 +
            taskTokens.intersect(def.tags.flatMap { tokenize(it) }.toSet()).size * 3 +
            taskTokens.intersect(tokenize(def.name).toSet()).size * 4 +
            keywordTokens.intersect(tokenize(def.description).toSet()).size +
            keywordTokens.intersect(def.tags.flatMap { tokenize(it) }.toSet()).size
        val substring = if (utterance.contains(def.name, ignoreCase = true)) 10 else 0
        return overlap + substring
    }

    /**
     * Every tool in RELEVANCE ORDER for one turn.
     *
     * `select` opens with `if (available.size <= maxTools) return available`, so
     * asking for the whole set returns registry order with no scoring at all.
     * The ranking path is only reachable at `maxTools < available.size`; the
     * last tool is recovered by subtraction. The same shape is documented on
     * the sibling harness, and it is a real property of the shipped selector
     * rather than a quirk of this file.
     */
    fun fullRanking(utterance: String, keywords: List<String>): List<String> {
        val top = selector.select(utterance, keywords, tools, tools.size - 1)
            .map { it.definition.name }
        val remainder = tools.map { it.definition.name }.filterNot { it in top }
        return top + remainder
    }

    /**
     * Prove the mirror still matches the shipped selector, on every tool of
     * every case. Returns a description of each disagreement, empty when clean.
     */
    fun assertMirrorsSelector(results: List<CaseResult>): List<String> {
        val disagreements = ArrayList<String>()
        for (result in results) {
            val actual = fullRanking(result.utterance, result.sessionKeywords)
            val mirrored = tools
                .map { it.definition.name to mirrorScoreOf(result.utterance, result.sessionKeywords, it.definition.name) }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
                .map { it.first }
            if (actual != mirrored) {
                disagreements += "mirror disagrees for \"${result.utterance}\": " +
                    "selector=${actual.take(4)} mirror=${mirrored.take(4)}"
            }
        }
        return disagreements
    }

    /** Score the whole corpus at one visible-set width. */
    fun reportAt(k: Int, results: List<CaseResult> = scoredTurns()): Report {
        val trimmed = results.map { result ->
            val selected = selector
                .select(result.utterance, result.sessionKeywords, tools, k)
                .map { it.definition.name }
            result.copy(
                selected = selected,
                hits = result.expected.count { it in selected },
                missed = result.expected - selected.toSet(),
            )
        }
        val promptTokens = trimmed.map { turn ->
            val visible = tools.filter { it.definition.name in turn.selected }
            DefaultTokenCounter.count(SystemPrompts.forTools(visible.map { it.definition }))
        }
        return Report(
            k = k,
            callable = trimmed.count { it.callable },
            total = trimmed.size,
            expectedToolHit = trimmed.count { it.expected.first() in it.selected },
            slotCallable = trimmed.sumOf { it.hits },
            slotTotal = trimmed.sumOf { it.expectedCount },
            meanSystemPromptTokens = promptTokens.average().toInt(),
            cases = trimmed,
        )
    }

    /**
     * The FULL ranking at the shipped width, which is the tautology-free
     * ceiling: with no cut, every tool is reachable, so this row isolates "the
     * scorer found the right tool" from "it found it and then lost it at the
     * cut". A case that fails here is a scoring failure; a case that passes here
     * and fails at k is a width failure.
     */
    fun ceiling(results: List<CaseResult> = scoredTurns()): Report = reportAt(tools.size, results)

    /** The real system prompt cost, through the real renderer. */
    fun budgetedCostPerTool(): List<Pair<String, Int>> =
        tools.map { it.definition.name to DefaultTokenCounter.count(renderTool(it.definition)) }
            .sortedByDescending { it.second }

    companion object {
        const val CONTEXT_BUDGET_OUTPUT_RESERVE = 256

        val DUTCH_MARKERS = setOf(
            "het", "niet", "mijn", "wat", "waar", "welke", "hoe", "van", "voor", "met", "zijn",
            "heb", "hebben", "kan", "kun", "graag", "nog", "weer", "aan", "uit", "naar", "over",
            "onder", "staat", "gegevens", "bestand", "telefoon", "afspraak", "morgen", "vandaag",
        )
        val GERMAN_MARKERS = setOf(
            "ich", "sie", "der", "die", "das", "und", "nicht", "mit", "für", "auf", "ist", "sind",
            "was", "wann", "wie", "wo", "noch", "bitte", "zeig", "öffne", "mach", "schick", "lies",
            "haben", "wird", "mein", "meine", "heute", "morgen", "telefon", "datei", "kalender",
        )
    }
}

/**
 * Formats a fraction as a percentage.
 *
 * Local to this file rather than shared with the sibling single-turn harness:
 * they live on different branches, and duplicating a rounding helper is better
 * than the two harnesses disagreeing by a rounding step.
 */
internal fun heldOutPct(fraction: Double): String = "${(fraction * 100).let { "%.1f".format(it) }}%"
