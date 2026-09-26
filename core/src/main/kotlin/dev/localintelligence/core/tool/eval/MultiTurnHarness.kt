package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.ToolSelector

/**
 * Replays multi-turn scenarios through the real [Session] and scores the real
 * [LexicalToolSelector] at each turn.
 *
 * ## WHAT IS MEASURED, AND WHY IT IS NOT THE SAME NUMBER AS THE SINGLE-TURN ONE
 *
 * [SelectorRecallHarness] scores 176 isolated utterances and reports a recall
 * figure. That figure is real and it is also an answer to a narrower question
 * than it looks: *given this one request, does the selector find the tool?*
 *
 * Real use is not one request. It is a conversation, and the second question
 * about the battery contains no word that identifies the battery tool. This
 * harness measures what the product does across a conversation, which is a
 * strictly larger claim and a much worse number.
 *
 * ## THE REPLAY USES THE PRODUCTION KEYWORDS, NOT A SIMULATION OF THEM
 *
 * This is the part that makes the measurement trustworthy. For every turn the
 * harness builds a real [Session], appends the real user turn through
 * [Session.start], appends the real tool observations through
 * [Session.appendToolObservation], and then asks the session for its keywords
 * via [Session.currentKeywords]. Those keywords go into the real selector, the
 * same way [dev.localintelligence.core.agent.AgentController.selectTools] does
 * it.
 *
 * So if someone changes how keywords are derived, this harness changes with
 * them, and the number below cannot quietly describe a selector the app no
 * longer runs. A harness that hand-fed a list of "what the session probably
 * contains" would be measuring its own imagination — which is the failure that
 * got the previous eval suite deleted.
 *
 * ## THE FAILURE THIS EXISTS TO MAKE VISIBLE
 *
 * `AgentController.selectTools` builds its query from the CURRENT task string
 * and `Session.currentKeywords()`. `currentKeywords()` reads only the latest
 * user turn — the earlier ones were removed from it deliberately, because
 * feeding tool observations back into retrieval is how untrusted page text used
 * to pick the grammar (see `SessionKeywordTrustTest`).
 *
 * The consequence is not a security hole; it is the opposite one. The exclusion
 * is correct, and it means **no prior turn of any kind reaches the selector**:
 * not the user's own earlier words, and not the tool result the follow-up is
 * about. Meanwhile [dev.localintelligence.core.context.DefaultContextBuilder]
 * hands the MODEL up to 64 messages of history, so the model has the referent
 * and the grammar does not.
 *
 * That is a silent, one-directional failure:
 *
 *  - the model is asked to act on something it can see and cannot call;
 *  - [dev.localintelligence.core.model.GrammarBuilder.forActions] makes an
 *    unselected tool unspeakable, so the correct call is not merely discouraged
 *    but unrepresentable;
 *  - nothing throws, nothing is logged, and the run ends with a prose answer
 *    that is wrong in a way the user cannot connect to a cause.
 *
 * ## WHAT THIS DOES NOT MEASURE
 *
 * No model is involved. Nothing here says what the model WOULD have done if the
 * tool had been reachable — only that it was not reachable. That is an upper
 * bound on failure, not a failure rate: a model that gave up and answered in
 * prose is one outcome, and a model that looped until the step limit is another,
 * and which one happens needs a real model on a real device.
 */
class MultiTurnHarness(
    private val selector: ToolSelector = LexicalToolSelector(),
    val tools: List<AgentTool> = AndroidToolSnapshot.tools,
    val scenarios: List<MultiTurnScenario> = MultiTurnDataset.all,
    private val config: AgentConfig = AgentConfig(),
) {

    /** One turn's outcome. */
    data class TurnResult(
        val scenarioId: String,
        val scenarioTitle: String,
        val turnIndex: Int,
        val turnCount: Int,
        val utterance: String,
        val expected: Set<String>,
        val intent: TurnIntent,
        /** Turns back to whatever this turn depends on. 0 when self-contained. */
        val depth: Int,
        val selected: List<String>,
        val hits: Int,
        val missed: Set<String>,
        /** The keywords the production session actually produced this turn. */
        val keywords: List<String>,
        /** True when at least one expected tool was made callable. */
        val callable: Boolean,
        /**
         * True when the turn's subject words appear NOWHERE in the live
         * selection query — the condition under which the tool is being asked
         * to carry a referent it cannot see.
         */
        val subjectAbsentFromQuery: Boolean,
    ) {
        val recall: Double get() = hits.toDouble() / expected.size
        val label: String get() = "$scenarioId#$turnIndex"
    }

    data class Report(
        val k: Int,
        val results: List<TurnResult>,
    ) {
        val total: Int get() = results.size
        val callable: Int get() = results.count { it.callable }
        val recall: Double get() = if (total == 0) 0.0 else callable.toDouble() / total

        /**
         * Turns where AT LEAST ONE expected tool was missing from the grammar.
         *
         * ## Why this is not the same number as `callable`
         *
         * [callable] is a per-TURN boolean: one expected tool surviving is
         * enough. For a turn expecting `{alarm.create, device.battery}` that
         * reads as a clean pass if only `alarm.create` was selected — and it is
         * not one. The user asked to be reminded about the battery, the alarm
         * tool was reachable, and the battery tool was not, so the specific
         * thing they asked to be reminded about is uncallable while the turn
         * still scores 100%.
         *
         * That is precisely the failure this harness exists to catch, and a
         * binary per-turn score hides it by construction. So recall is reported
         * BOTH ways: turns fully served, and turns with anything missing.
         */
        val partial: Int get() = results.count { it.hits < it.expected.size }

        /** Set-level recall: expected tool-slots reached, over expected slots. */
        val slotRecall: Double
            get() {
                val expected = results.sumOf { it.expected.size }
                return if (expected == 0) 0.0 else {
                    results.sumOf { it.hits }.toDouble() / expected
                }
            }
    }

    /**
     * Replay every scenario at one visible-set width.
     *
     * @param k the visible-set width, passed to the selector exactly as
     *   `AgentConfig.maxVisibleTools` is in production.
     */
    fun run(k: Int = config.maxVisibleTools): Report {
        val results = ArrayList<TurnResult>(scenarios.sumOf { it.turns.size })
        for (scenario in scenarios) {
            // A FRESH session per scenario, not per turn. A session is one
            // conversation, and sharing it across scenarios would leak one
            // scenario's referent into another's — which is precisely the bug
            // under measurement, manufactured rather than observed.
            val session = Session()
            scenario.turns.forEachIndexed { index, turn ->
                // EXACTLY what AgentController.run does before the loop:
                // sessions.start(task), then observations as tools return.
                session.start(turn.utterance)

                val keywords = session.currentKeywords()
                val selected = selector
                    .select(turn.utterance, keywords, tools, k)
                    .map { it.definition.name }

                results += TurnResult(
                    scenarioId = scenario.id,
                    scenarioTitle = scenario.title,
                    turnIndex = index,
                    turnCount = scenario.turns.size,
                    utterance = turn.utterance,
                    expected = turn.expected,
                    intent = turn.intent,
                    depth = scenario.depthOf(index),
                    selected = selected,
                    hits = turn.expected.count { it in selected },
                    missed = turn.expected - selected.toSet(),
                    keywords = keywords,
                    callable = turn.expected.any { it in selected },
                    subjectAbsentFromQuery = subjectAbsentFromQuery(turn, keywords),
                )

                // Record what the loop would have appended, so the next turn's
                // session is the one production would really be holding.
                turn.calls?.let { name ->
                    val tool = tools.first { it.definition.name == name }
                    session.appendToolObservation(tool, turn.observation.orEmpty(), true)
                }
                turn.assistant?.let { session.appendAssistant(it) }
            }
        }
        return Report(k, results)
    }

    /**
     * Is the turn's subject missing from everything the selector could read?
     *
     * The query is `utterance` plus `keywords`, and both are the production
     * values. So this asks: does ANY word identifying the expected tool appear
     * in the text the selector actually scored? When it does not, the selector
     * is being asked to select a tool on the strength of a referent it has no
     * access to, and the only reason it can still succeed is a coincidental tag
     * collision. That is the condition the failure lives in.
     */
    private fun subjectAbsentFromQuery(turn: ScenarioTurn, keywords: List<String>): Boolean {
        if (turn.intent == TurnIntent.SELF_CONTAINED) return false
        val query = (turn.utterance + " " + keywords.joinToString(" ")).lowercase()
        val signals = turn.expected.flatMap { name ->
            val tool = tools.first { it.definition.name == name }
            listOf(tool.definition.name) + tool.definition.tags.toList()
        }
        return signals.none { signal ->
            val head = signal.lowercase().substringBefore(' ').substringBefore('.')
            head.length > 2 && query.contains(head)
        }
    }

    /**
     * Recall split by whether the turn refers back.
     *
     * The two numbers side by side are the entire argument for this harness
     * existing. The control figure is what the selector does when the turn
     * stands alone; the referential figure is what it does when the turn is a
     * follow-up. A harness that reported only the second would be reporting a
     * number that a reader has no way to interpret.
     */
    fun byIntent(report: Report): Map<TurnIntent, Pair<Int, Int>> =
        TurnIntent.entries.associateWith { intent ->
            val slice = report.results.filter { it.intent == intent }
            slice.count { it.callable } to slice.size
        }

    /** The same split for referential turns only, by how far back they reach. */
    fun byDepth(report: Report): Map<Int, Pair<Int, Int>> =
        report.results
            .filter { it.intent != TurnIntent.SELF_CONTAINED }
            .groupBy { it.depth }
            .toSortedMap()
            .mapValues { (_, slice) -> slice.count { it.callable } to slice.size }

    /**
     * Per-tool coverage across the SINGLE-TURN corpus and the MULTI-TURN corpus
     * as separate columns, plus a "selected where not expected" count measured
     * WITHIN each corpus.
     *
     * The column split is the point and it was a bug once already. An earlier
     * version printed "expected" from the single-turn corpus, "selected" from
     * the multi-turn replay, and "selected-where-not-expected" from BOTH — so
     * `alarm.cancel` read as 7 expected, 1 multi-expected, 35 selected and 177
     * unwanted, which is four numbers from three populations and supports no
     * conclusion at all. Each corpus now has its own coherent triple, and the
     * two are only ever compared after being put on the same footing.
     *
     * @param singleSelected turns in [SelectorDataset.all] where the tool was
     *   in the visible set.
     * @param singleUnwanted turns in [SelectorDataset.all] where the tool was
     *   selected but the case's expected set did not contain it.
     */
    fun coverage(
        report: Report,
        singleSelected: Map<String, Int>,
        singleUnwanted: Map<String, Int>,
    ): List<ToolCoverage> {
        val singleExpected = SelectorDataset.coverageByTool()
        val multiSelected = report.results
            .flatMap { it.selected }
            .groupingBy { it }
            .eachCount()
        val multiUnwanted = report.results
            .flatMap { it.selected.filterNot { name -> name in it.expected } }
            .groupingBy { it }
            .eachCount()
        val missedBy = report.results
            .filter { !it.callable }
            .flatMap { it.missed }
            .groupingBy { it }
            .eachCount()
        return tools.map { it.definition.name }.sorted().map { name ->
            ToolCoverage(
                tool = name,
                singleExpected = singleExpected[name] ?: 0,
                singleSelected = singleSelected[name] ?: 0,
                singleUnwanted = singleUnwanted[name] ?: 0,
                multiExpected = MultiTurnDataset.coverageByTool()[name] ?: 0,
                multiSelected = multiSelected[name] ?: 0,
                multiUnwanted = multiUnwanted[name] ?: 0,
                multiMissed = missedBy[name] ?: 0,
                reason = MultiTurnDataset.UNCOVERED_BY_DESIGN[name],
            )
        }
    }
}

/**
 * One row of the coverage table.
 *
 * Every column is a count over ONE named population. `single*` columns are over
 * [SelectorDataset.all]; `multi*` columns are over the replayed scenarios.
 * Mixing them is how a table ends up looking alarming while supporting no
 * claim, so the two families never share a column.
 */
data class ToolCoverage(
    val tool: String,
    // --- single-turn corpus -------------------------------------------------
    val singleExpected: Int,
    val singleSelected: Int,
    val singleUnwanted: Int,
    // --- multi-turn corpus --------------------------------------------------
    val multiExpected: Int,
    val multiSelected: Int,
    val multiUnwanted: Int,
    val multiMissed: Int,
    /** Set when the corpus deliberately has no turn for this tool. */
    val reason: String? = null,
) {
    /** True when NO case in EITHER corpus expects this tool: a coverage hole. */
    val uncovered: Boolean get() = singleExpected == 0 && multiExpected == 0

    /** True when the selector never put it in a visible set, in either corpus. */
    val neverSelected: Boolean get() = singleSelected == 0 && multiSelected == 0
}
