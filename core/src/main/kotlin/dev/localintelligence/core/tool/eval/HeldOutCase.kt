package dev.localintelligence.core.tool.eval

/**
 * One held-out user utterance, and the tool set that should be reachable for it.
 *
 * ## Why this type exists next to [EvalCase]
 *
 * It does not. [EvalCase] in the sibling single-turn corpus already models a
 * canonical utterance, a SET of defensible tools, an intent, and session
 * keywords. This file re-declares the shape because the held-out corpus is
 * deliberately a separate artefact with separate rules, and a shared type
 * invites a shared author. See [HeldOutDataset] for the rules.
 *
 * The one addition over [EvalCase] is [dialect]: the shipped token counter in
 * [dev.localintelligence.core.tool.LexicalToolSelector] splits on `[^a-z0-9]+`
 * and drops fragments of length <= 2, so a non-English utterance tokenises to
 * whatever ASCII survives. That is a property of the SHIPPED selector, not of
 * the data, and recording which utterances are non-English is what lets the
 * report separate "the selector cannot read this" from "the selector read it
 * and got it wrong".
 */
data class HeldOutCase(
    val utterance: String,
    /** Every tool that could correctly serve this. Order is not significant. */
    val expected: Set<String>,
    val intent: Intent = Intent.PLAIN,
    /**
     * Session keywords carried over from earlier turns, in the exact shape
     * [dev.localintelligence.core.agent.Session.currentKeywords] supplies.
     *
     * Empty is the honest default: most turns are the first of a session, and a
     * harness that always supplies keywords measures a different selector than
     * the one a fresh conversation gets.
     */
    val sessionKeywords: List<String> = emptyList(),
    val dialect: Dialect = Dialect.EN,
) {
    init {
        require(expected.isNotEmpty()) { "a case with no expected tool cannot be scored: $utterance" }
        require(expected.none { it !in ALL_TOOL_NAMES }) {
            "unknown expected tool in \"$utterance\": " + expected.filter { it !in ALL_TOOL_NAMES }
        }
    }

    /** The first tool a sensible implementation would reach for. */
    val firstChoice: String get() = expected.first()

    /**
     * Why this turn is hard, so the next reader does not have to reverse-engineer
     * it and so a coverage report can break the number down honestly.
     */
    enum class Intent {
        /** An ordinary, unambiguous request. */
        PLAIN,

        /**
         * Two or more tools are genuinely defensible, and choosing either is
         * not wrong.
         */
        AMBIGUOUS,

        /**
         * Lexically adjacent to a different tool: shares vocabulary with a
         * neighbour and must not be pulled to it. These are where the selector's
         * alphabetical tie-break gets caught.
         */
        NEAR_MISS,

        /**
         * Needs one tool to discover the input of another — a contact id, a
         * notification key, a content:// URI. Scored on the FIRST call, because
         * selection happens per turn and the follow-up happens on the next one.
         */
        CHAINED,

        /**
         * Elliptical: a real user omits the noun because they assume it is
         * obvious. "make it 7 instead" carries no word from the tool it needs.
         */
        ELLIPTICAL,

        /**
         * The subject was established in an EARLIER turn. The follow-up carries
         * no noun of its own, so this only scores if [sessionKeywords] bridge
         * the gap. This is the population the single-turn corpus cannot express
         * and the one that decides whether a conversation works at all.
         */
        REFERENTIAL,
    }

    /**
     * The language the utterance is written in.
     *
     * Recorded because the shipped selector is ASCII-only by construction. NL
     * and DE are not decoration: they are the languages the team actually
     * speaks, and a number that is quietly averaged over them hides a real
     * capability cliff at the point where it matters.
     */
    enum class Dialect {
        EN,
        NL,
        DE,
        ;

        val label: String
            get() = when (this) {
                EN -> "en"
                NL -> "nl"
                DE -> "de"
            }
    }

    companion object {
        val ALL_TOOL_NAMES: Set<String> = HeldOutToolSnapshot.tools
            .map { it.definition.name }
            .toSet()
    }
}
