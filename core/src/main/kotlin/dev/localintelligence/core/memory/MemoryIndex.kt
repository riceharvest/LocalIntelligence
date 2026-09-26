package dev.localintelligence.core.memory

/**
 * How one memory scored against one query, kept so a UI or a trace can say
 * *why* something came back.
 */
data class ScoredMemory<T>(
    val item: T,
    val score: Double,
    /** How many of the query's terms this memory matched. 0 means it is noise. */
    val matchedTerms: Int,
    /** The query's terms, after normalisation, that this memory did not have. */
    val missingTerms: List<String>,
)

/**
 * Retrieval over a set of memories. Pure, deterministic, no I/O.
 *
 * ## WHY RANKING IS NOT "importance first"
 *
 * The previous implementation sorted by `importance` and then by id, and
 * accepted a memory when *any* query term appeared in it. Measured against a
 * five-memory corpus, the consequences were:
 *
 * - `"how much is the gym"` returned the wifi memory, the router-admin memory
 *   and the meeting, and put the gym memory *third*, because "the" is three
 *   characters long and the length filter let it through. The correct answer
 *   was present and was third of four.
 * - `"when is my meeting with Anna"` worked, but only because "anna" happened
 *   to be unique. Nothing in the ranking *preferred* it.
 * - `"where do I live"` returned nothing at all, because "live" and "address"
 *   are different tokens. No lexical index can fix that; see [MemoryText].
 *
 * Sorting by importance first is therefore the wrong order: importance is a
 * property of a memory and says nothing about whether it answers *this*
 * question. A memory that matches every term of the query is a better answer
 * than a more important memory that matches one of them, so term coverage is
 * the primary key and importance only breaks ties between memories that cover
 * the same number of terms.
 *
 * ## THE SCORE
 *
 * ```
 * score = (matchedTerms / queryTerms) * COVERAGE_WEIGHT
 *       + matchedTerms / max(1, totalTermsInMemory) * DENSITY_WEIGHT
 *       + importance * IMPORTANCE_WEIGHT
 * ```
 *
 * WHY coverage is first and dominant: it is the only term that cannot be
 * gamed by a long memory, and it is the only one that distinguishes "answers
 * the question" from "shares a word with the question".
 *
 * WHY density second: two memories can both cover every query term, and the
 * one where those terms are most of the content is the more on-topic. It is
 * bounded at 1.0 so it can outrank a difference of one coverage step, and no
 * more.
 *
 * WHY importance last: it is a tiebreak, not a ranking. It is retained because
 * a caller that stored something at importance 0.9 meant "prefer this", and
 * dropping the signal entirely would ignore a stated intent.
 *
 * ## WHAT THIS DOES NOT FIX
 *
 * Stated plainly because it is the honest limit of the approach: a query whose
 * terms appear in *no* memory returns nothing. `"where do I live"` does not
 * retrieve `"home address"`. Fixing that needs semantic matching — embeddings,
 * a synonym table, or a model — none of which exist here and none of which can
 * be evaluated without a corpus, which this repository does not have. The
 * numbers reported for this change were produced against a synthetic five-item
 * corpus built to contain the specific known failures, which is a regression
 * check and **not** a measurement of real retrieval quality.
 */
object MemoryIndex {

    /** Weight on the fraction of query terms matched. Dominant by design. */
    const val COVERAGE_WEIGHT: Double = 1.0

    /** Weight on term density within the memory. Bounded, so it cannot dominate. */
    const val DENSITY_WEIGHT: Double = 0.30

    /** Weight on the stored importance, used only as a tiebreak. */
    const val IMPORTANCE_WEIGHT: Double = 0.10

    /**
     * Scores [items] against [query] and returns the best [limit].
     *
     * @param termsOf the indexed terms of an item. Injected rather than
     *   re-derived so a store that already holds a persisted keyword column
     *   does not re-tokenise on every query.
     * @param importanceOf the stored importance of an item.
     */
    fun <T> rank(
        query: String,
        items: List<T>,
        termsOf: (T) -> Collection<String>,
        importanceOf: (T) -> Float,
        limit: Int,
    ): List<ScoredMemory<T>> {
        if (limit <= 0) return emptyList()
        val queryTerms = MemoryText.terms(query)
        // A query of only stopwords and short fragments carries no signal.
        // Returning everything here would be the "no filter, so return it all"
        // bug the SQL path already guards against.
        if (queryTerms.isEmpty()) return emptyList()

        val querySet = queryTerms.toSet()
        val scored = ArrayList<ScoredMemory<T>>(items.size)
        for (item in items) {
            val itemTerms = termsOf(item)
            if (itemTerms.isEmpty()) continue
            val itemSet = if (itemTerms is Set) itemTerms else itemTerms.toSet()
            val matched = querySet.count { it in itemSet }
            if (matched == 0) continue
            val coverage = matched.toDouble() / querySet.size
            val density = matched.toDouble() / itemSet.size.coerceAtLeast(1)
            val score = coverage * COVERAGE_WEIGHT +
                density * DENSITY_WEIGHT +
                importanceOf(item).toDouble() * IMPORTANCE_WEIGHT
            scored += ScoredMemory(
                item = item,
                score = score,
                matchedTerms = matched,
                missingTerms = queryTerms.filterNot { it in itemSet },
            )
        }
        // Deterministic: score, then coverage, then recency handled by the
        // caller's own ordering inside equal scores (id descending is newer
        // first for both backends). `id` is deliberately not referenced here
        // so this stays generic over T.
        return scored
            .sortedWith(
                compareByDescending<ScoredMemory<T>> { it.score }
                    .thenByDescending { it.matchedTerms },
            )
            .take(limit)
    }
}
