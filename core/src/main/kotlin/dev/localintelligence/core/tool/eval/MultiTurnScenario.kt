package dev.localintelligence.core.tool.eval

/**
 * One user turn inside a [MultiTurnScenario], plus what the loop did next.
 *
 * ## Why a scenario is a LIST of turns and the single-turn corpus is not
 *
 * `SelectorDataset` holds 176 isolated utterances, which is the right shape for
 * "can the selector find the tool for THIS request" and the wrong shape for the
 * question that decides whether the product works at all: **can the selector
 * find the tool for a request whose subject was established two turns ago.**
 *
 * The gap is not hypothetical. `AgentController.selectTools` is
 *
 * ```
 * toolSelector.select(task, sessions.currentKeywords(), tools.all(), config.maxVisibleTools)
 * ```
 *
 * and `Session.currentKeywords()` reads ONLY the latest `ChatMessage.User`
 * turn. So the selector's entire memory of a conversation is one user message,
 * no matter how long the conversation is or how much of it the model has been
 * shown. Meanwhile `DefaultContextBuilder` hands the model up to
 * `ContextLimits.MAX_HISTORY_SCAN` messages of retained history.
 *
 * The two views therefore diverge on the second turn of any conversation that
 * refers back, and they never re-converge. A follow-up like "remind me to check
 * that again at 7" arrives at the selector with no word from the turn that
 * established what "that" is, so the tool that answered the original question
 * scores zero and drops out of the visible set — where, because
 * `GrammarBuilder.forActions` makes an unselected tool unspeakable, the agent
 * cannot perform the task at all. Nothing throws and nothing is logged.
 *
 * ## What [calls] and [observation] are for
 *
 * A follow-up that needs a prior RESULT is not a follow-up that needs a prior
 * QUESTION. "Text him I'm on my way" is answerable only if the contact lookup
 * two turns back actually returned something. So a turn records the tool the
 * loop called and what it returned, and the harness appends both to the real
 * [dev.localintelligence.core.agent.Session] through its public API. The replay
 * is therefore the production path, not a story about it: every keyword the
 * selector sees is produced by `Session` itself.
 *
 * @param utterance what the user says this turn.
 * @param expected every tool that could correctly serve it. A SET, for the
 *   same reason [EvalCase.expected] is one: scoring a defensible alternative
 *   wrong understates the selector and then gets quoted as truth.
 * @param intent why this turn is hard, so the next reader does not have to
 *   reverse-engineer it.
 * @param calls the tool the loop invokes this turn, or null if the turn is
 *   answered in prose. Recorded so the NEXT turn inherits a real observation.
 * @param observation what [calls] returned. Realistic, short, and in the shape
 *   [dev.localintelligence.core.tool.ObservationTruncator] would allow.
 * @param assistant the prose reply appended after the tool result.
 * @param refersTo how many turns back the subject of THIS turn was
 *   established. 0 for a [TurnIntent.SELF_CONTAINED] turn.
 *
 *   AUTHORED, NOT INFERRED, and this is the whole reason it is a field. An
 *   earlier version derived it as "the last turn that called a tool", which is
 *   a guess dressed as a measurement: in the five-turn session whose last turn
 *   reopens the FIRST turn's question, the last tool call was turn 3, so the
 *   harness cheerfully reported a depth of 1 for a turn that actually reaches
 *   back four. The number that decides whether recall decays with depth would
 *   have been quietly wrong, and wrong in the direction that hides the bug.
 *
 *   The corpus is hand-written, so the author is the one who knows which turn a
 *   follow-up refers to. Asking them is exact; inferring it is not.
 */
data class ScenarioTurn(
    val utterance: String,
    val expected: Set<String>,
    val intent: TurnIntent,
    val refersTo: Int = 0,
    val calls: String? = null,
    val observation: String? = null,
    val assistant: String? = null,
) {
    init {
        require(expected.isNotEmpty()) { "a turn with no expected tool cannot be scored: $utterance" }
        require(expected.none { it !in EvalCase.ALL_TOOL_NAMES }) {
            "unknown expected tool in \"$utterance\": " +
                expected.filter { it !in EvalCase.ALL_TOOL_NAMES }
        }
        require(calls == null || calls in EvalCase.ALL_TOOL_NAMES) {
            "unknown tool call in \"$utterance\": $calls"
        }
        // A tool that returns nothing leaves the next turn with no result to
        // depend on, which is almost always a corpus bug rather than a
        // deliberately empty tool result. Both are permitted; the asymmetry is
        // recorded here so it is a decision and not an oversight.
        require(calls == null || observation != null) {
            "turn \"$utterance\" calls $calls but records no observation; the next " +
                "turn would depend on a result that does not exist"
        }
        require(refersTo >= 0) { "refersTo cannot be negative: \"$utterance\"" }
        // A self-contained turn refers to nothing, and a referential turn must
        // refer to SOMETHING. Either the two disagree or a turn is mislabelled,
        // and both make the by-intent table a lie.
        require((intent == TurnIntent.SELF_CONTAINED) == (refersTo == 0)) {
            "turn \"$utterance\" is $intent but refersTo=$refersTo; a self-contained " +
                "turn refers to nothing and a referential turn must refer to something"
        }
    }

    /** True when this turn is scored for retrieval. Prose-only turns are not. */
    val scored: Boolean get() = true
}

/**
 * Why a turn is hard.
 *
 * The distinction that matters is not "hard" versus "easy" — it is whether the
 * turn can be answered from its OWN words. A [SELF_CONTAINED] turn is the
 * control group, and it is the reason the failure rate below is not just a
 * number this harness invented by writing hard turns.
 */
enum class TurnIntent {
    /**
     * The utterance names everything needed. A correct selector gets this right
     * with no history at all.
     *
     * THIS IS THE CONTROL GROUP AND IT IS NOT OPTIONAL. Without it, a high
     * failure rate would be indistinguishable from a corpus of deliberately
     * impossible turns. The harness prints the two groups' recall side by side
     * for exactly that reason: the gap between them is the measurement, and a
     * corpus with no control cannot produce one.
     */
    SELF_CONTAINED,

    /**
     * "that", "it", "him", "again" — the referent lives in an earlier user
     * turn. The selector does not get to see that turn.
     */
    PRONOUN_REFERENCE,

    /**
     * The turn is only answerable using what an earlier TOOL returned: an id, a
     * file name, a contact, a count. The observation is in the model's context
     * and, by design, is NOT in the selector's keywords —
     * `SessionKeywordTrustTest` pins that exclusion as a security boundary, so
     * this is a case where the correct behaviour and the safe behaviour are in
     * genuine tension and the harness measures what actually happens.
     */
    OBSERVATION_DEPENDENT,

    /**
     * The referent is several turns back, past the point where a user would
     * still expect it to be held. The deepest form of the same failure, and the
     * one that survives into real sessions rather than two-line exchanges.
     */
    ELAPSED_REFERENCE,
}

/**
 * A whole conversation: the turns in order, and the depth at which the
 * scenario's referent was established.
 *
 * @param id a stable short name. Quoted in the failure table, so it has to be
 *   stable and it has to be readable.
 * @param title one line saying what the user is trying to do.
 * @param turns the conversation, in order. Must not be empty.
 * @param selfContainedEverywhere true when EVERY turn is
 *   [TurnIntent.SELF_CONTAINED]. Such a scenario is a control and is reported
 *   in the control group rather than the failure group.
 */
data class MultiTurnScenario(
    val id: String,
    val title: String,
    val turns: List<ScenarioTurn>,
) {
    init {
        require(turns.isNotEmpty()) { "scenario $id has no turns" }
        require(turns.map { it.utterance }.distinct().size == turns.size) {
            "scenario $id repeats an utterance; a conversation that says the same " +
                "thing twice is a different scenario and should be written as one"
        }
    }

    /** True when no turn refers back to an earlier one. */
    val isControl: Boolean
        get() = turns.all { it.intent == TurnIntent.SELF_CONTAINED }

    /**
     * How many turns back the thing this turn depends on was established.
     *
     * Read straight from [ScenarioTurn.refersTo], with one check that it is
     * physically possible: a turn cannot refer to a turn that has not happened
     * yet. A corpus that got this wrong would otherwise print a depth table
     * describing conversations that could not occur.
     */
    fun depthOf(turnIndex: Int): Int {
        val depth = turns[turnIndex].refersTo
        require(depth <= turnIndex) {
            "scenario $id turn $turnIndex claims to refer $depth turns back, but only " +
                "$turnIndex turns precede it"
        }
        return depth
    }
}
