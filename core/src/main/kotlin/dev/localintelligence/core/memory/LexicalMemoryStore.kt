package dev.localintelligence.core.memory

import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.agent.MemoryStore

/**
 * The store the agent should actually be given: a [MemoryStore] that can be
 * *written to*, with retrieval that ranks by relevance.
 *
 * ## WHY THIS EXISTS
 *
 * Every part of the memory feature was already implemented and none of it was
 * reachable. Verified on `b9defbf`:
 *
 * ```
 * $ grep -rn '\.remember(' --include=*.kt . | grep -v androidTest
 * android/.../ResilientMemoryStore.kt:126:  override suspend fun remember(...)
 * android/.../RoomMemoryStore.kt:206:       override suspend fun remember(...)
 * core/.../agent/MemoryStore.kt:17:         suspend fun remember(...)
 * core/.../agent/MemoryStore.kt:36:         override suspend fun remember(...)
 * ```
 *
 * Four hits, all of them declarations and implementations. **Zero call sites.**
 * The loop read memory at `AgentController.kt:1036` (`memory.search(task,
 * config.memoryResults)`, inside `buildRequest`) and never wrote any, so the
 * table was empty forever, the prompt block was always empty, and the product
 * advertised an agent that remembers while containing an agent that could
 * consult a memory it had no way to form.
 *
 * This class is the missing half. It is a [MemoryStore], so it drops into the
 * existing `AppContainer.memoryStore` slot unchanged, and it adds the one
 * method the interface does not have: [recordTurn].
 *
 * ## WHY IT WRAPS RATHER THAN REPLACES
 *
 * Durability is not this class's business. [delegate] is the existing
 * `ResilientMemoryStore` — Room when the database opens, RAM when it does not,
 * with the fallback and the `CancellationException` handling already correct.
 * Reimplementing any of that here would mean two answers to "is this durable",
 * and the wrong one is the one a user finds out about at the worst moment.
 *
 * What this class owns is the part that was wrong: what gets written, and how
 * a query is ranked against what was.
 *
 * ## WHAT [recordTurn] DOES AND DOES NOT DO
 *
 * It applies [MemoryWritePolicy] and nothing else. There is no model call, no
 * summarisation step, no second inference, and no background work — a
 * `recordTurn` is a regex match and, if it matches, one insert on a background
 * dispatcher the delegate already owns.
 *
 * The reason is RAM, which is this product's primary metric. An unconditional
 * "remember every turn" is a table that grows without bound, and an unbounded
 * table is a RAM regression with no ceiling and a latency cost. The filter
 * exists so the table stays small enough that the resource argument never has
 * to be had, and its cost — a bounded recall, stated in [MemoryWritePolicy] —
 * is the right thing to pay for it.
 */
class LexicalMemoryStore(
    private val delegate: MemoryStore,
) : MemoryStore {

    /**
     * Considers one finished turn for storage, and stores it if it qualifies.
     *
     * ## WHY THE AGENT'S OWN OUTPUT IS NEVER STORED
     *
     * The signature takes the *user's* text, not the transcript. A model asked
     * "what is my wifi password" answers "your wifi password is hunter2" —
     * and storing that would (a) reinforce a hallucination as a fact on every
     * later query, and (b) make the memory table a record of what the model
     * claimed rather than what the user said. A memory has to be something the
     * user asserted, or it is not a memory.
     *
     * ## WHY IT NEVER THROWS
     *
     * A memory write is a side effect of a conversation that already
     * succeeded. Failing the run — or surfacing an error to the user — because
     * a regex did not match, or because SQLite was briefly unavailable, would
     * be trading a completed answer for a cosmetic bookkeeping step. The
     * delegate is `ResilientMemoryStore`, which already degrades to RAM and
     * rethrows only `CancellationException`.
     *
     * @return the stored [Memory], or null when the turn did not qualify. The
     *   return is for a trace or a test; the agent loop ignores it.
     */
    suspend fun recordTurn(userText: String): Memory? {
        val kept = MemoryWritePolicy.extract(userText) ?: return null
        val importance = MemoryWritePolicy.importanceOf(kept)
        return delegate.remember(kept, importance)
    }

    /**
     * Lexical search, ranked by [MemoryIndex] rather than by importance alone.
     *
     * ## WHY THE CANDIDATE SET IS `all()` AND NOT `search()`
     *
     * The obvious implementation delegates to `MemoryStore.search` and re-ranks
     * what comes back. That is wrong, and it was wrong in a way this class
     * exists to fix: the delegate is a SQL `LIKE` prefilter over a `keywords`
     * column built by `MemoryQueries.tokenize`, which is still the *old* rule
     * (split on non-alphanumerics, drop anything not longer than two
     * characters). A query for `"passwords"` does not match a row keyed
     * `"password"`, so the delegate returns nothing and no amount of re-ranking
     * downstream can recover a row that was never fetched.
     *
     * Measured, before this line changed: a store holding
     * `"My wifi password is hunter2-kiwi"`, queried for `"passwords"`, returned
     * **0 results** — while [MemoryText] and [MemoryIndex] between them score
     * that pair as a match. The ranking was correct and unreachable.
     *
     * So the candidate set is [all] instead. The cost is bounded and stated:
     * [MAX_CANDIDATES] rows, each a short string, are scored on every query.
     * That is tens of kilobytes of transient allocation against a process
     * holding a multi-gigabyte model, and it buys retrieval that does not
     * depend on a second implementation of the tokeniser staying in sync.
     *
     * The durable fix is the one-line `MemoryQueries.tokenize` change specified
     * in the PR description; once the delegate's prefilter uses
     * [MemoryText.terms], this method can go back to delegating a `search` and
     * the scan can be deleted. It is kept working here in the meantime because
     * a memory feature that silently returns nothing is the exact failure this
     * whole change exists to remove.
     */
    override suspend fun search(query: String, limit: Int): List<Memory> {
        if (limit <= 0) return emptyList()
        val candidates = delegate.all(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyList()
        return MemoryIndex.rank(
            query = query,
            items = candidates,
            termsOf = { MemoryText.terms(it.text) },
            importanceOf = { it.importance },
            limit = limit,
        ).map { it.item }
    }

    override suspend fun remember(text: String, importance: Float): Memory =
        delegate.remember(text, importance)

    override suspend fun forget(id: Long): Boolean = delegate.forget(id)

    override suspend fun all(limit: Int): List<Memory> = delegate.all(limit)

    private companion object {
        /**
         * How many rows are scored per query. See [search] for why this is a
         * scan rather than a delegate `search`.
         *
         * 500 matches `MemoryQueries.MAX_ALL_RESULTS`, the cap the Room DAO
         * already applies to `allRows`, so this never asks the database for
         * more than it is willing to hand over.
         */
        const val MAX_CANDIDATES = 500
    }
}
