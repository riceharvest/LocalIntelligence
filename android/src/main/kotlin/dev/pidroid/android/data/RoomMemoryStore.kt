package dev.pidroid.android.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.pidroid.core.agent.Memory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ALL of the search logic as pure functions over Strings and Ints.
 *
 * Why this exists as a separate object: `android.database.sqlite` is a stub in JVM unit
 * tests, so a DAO round-trip cannot be executed without a device. Every decision that
 * can be wrong — tokenising, escaping, ordering, capping — lives here and is unit
 * tested on the JVM. The DAO is then a thin `withContext` wrapper that binds these
 * results and trusts them.
 *
 * ## Injection model
 *
 * Search input is untrusted (it is model- or user-generated text). Three independent
 * layers, and the first one alone is sufficient:
 *
 * 1. **Allowlist tokenisation.** [tokenize] keeps only `[a-z0-9]` runs longer than 2
 *    characters, copied from `InMemoryMemoryStore`. Every other character — quote,
 *    semicolon, whitespace, `--`, and C-style comment openers — is a separator and is
 *    destroyed. A double quote cannot survive tokenisation, so it cannot reach step 2
 *    as structure.
 * 2. **FTS5 literal quoting.** Each surviving token is emitted as a double-quoted FTS5
 *    string literal, with `"` doubled per the FTS5 escaping rules. Belt and braces for
 *    step 1; correct in isolation even if [tokenize] is ever loosened.
 * 3. **Bound parameter.** The finished MATCH expression is passed as a `?` bind
 *    argument to SQLite, never concatenated into the statement text. Even a hostile
 *    expression is a *value* here, so it cannot alter statement structure.
 *
 * Layers 1 and 3 together mean the attack is dead twice over.
 */
object MemoryQueries {

    /** Hard cap on search results. docs/architecture.md section 14: return 3-5 maximum. */
    const val MAX_SEARCH_RESULTS: Int = 5

    /** Hard cap on `all()`, so a caller cannot ask the DB to materialise everything. */
    const val MAX_ALL_RESULTS: Int = 500

    /**
     * Identical to `InMemoryMemoryStore`: lowercase, split on non-alphanumerics, drop
     * anything <= 2 chars. Both stores must agree on what a "term" is, or the same
     * corpus returns different results depending on which store is wired in.
     */
    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")

    fun tokenize(text: String): List<String> =
        text.lowercase().split(TOKEN_SPLIT).filter { it.length > 2 }

    /** The keyword projection persisted in [MemoryEntity.keywords]. */
    fun keywords(text: String): String = tokenize(text).joinToString(" ")

    /**
     * Builds the FTS5 MATCH expression, or null when the query has no usable terms.
     *
     * Tokens are OR-joined to match `InMemoryMemoryStore.search`, which accepts a row
     * when *any* query term is present. Quoting each token makes it a literal, so a
     * token that happens to spell an FTS5 operator (`or`, `near`, `not`) stays a word.
     */
    fun buildMatchExpression(query: String): String? {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { term -> "\"${escapeFtsLiteral(term)}\"" }
    }

    /**
     * FTS5 string-literal escaping: a double quote is written as two. Applied to
     * already-tokenised input this is a no-op, but it keeps this function correct for
     * any caller rather than silently depending on its caller's guarantees.
     */
    fun escapeFtsLiteral(raw: String): String = raw.replace("\"", "\"\"")

    /** Clamps a caller-supplied limit into `0..MAX_SEARCH_RESULTS`. */
    fun coerceSearchLimit(limit: Int): Int = limit.coerceIn(0, MAX_SEARCH_RESULTS)

    /** Clamps a caller-supplied limit into `0..MAX_ALL_RESULTS`. */
    fun coerceAllLimit(limit: Int): Int = limit.coerceIn(0, MAX_ALL_RESULTS)

    /**
     * The full search statement. Ordering is importance first, then recency, then id as
     * a deterministic final tiebreak so pagination cannot flap between equal rows.
     *
     * The FTS table is external-content (`content='memories'`), so it holds only the
     * inverted index and `rowid` is `memories.id`. Joining back to `memories` is what
     * lets us sort on columns the index does not carry.
     */
    val SEARCH_SQL: String = """
        SELECT m.id, m.text, m.keywords, m.importance, m.created_at
        FROM memories_fts
        JOIN memories m ON m.id = memories_fts.rowid
        WHERE memories_fts MATCH ?
        ORDER BY m.importance DESC, m.created_at DESC, m.id DESC
        LIMIT ?
    """.trimIndent()

    /**
     * The bound arguments for [SEARCH_SQL], or null when there is nothing to search for.
     *
     * Null is load-bearing: a query of only punctuation or 1-2 character tokens must
     * return *empty*, never fall through to "no filter, so return everything".
     *
     * This is a separate function from [buildSearchQuery] on purpose. A
     * `SupportSQLiteQuery` only exposes `sql`, `bindTo` and `argCount` — the bound
     * values are not readable back — so if the arguments were only ever materialised
     * inside the wrapper, the claim "the cap is the thing actually sent to SQLite"
     * would be untestable. Returning them as a plain array keeps that claim honest.
     */
    fun searchArgs(query: String, limit: Int): Array<Any?>? {
        val match = buildMatchExpression(query) ?: return null
        return arrayOf<Any?>(match, coerceSearchLimit(limit))
    }

    /** The bound query, or null when there is nothing to search for. */
    fun buildSearchQuery(query: String, limit: Int): SupportSQLiteQuery? =
        searchArgs(query, limit)?.let { SimpleSQLiteQuery(SEARCH_SQL, it) }

    /**
     * Mirrors the SQL `ORDER BY` above, for rows already in hand.
     *
     * Present so the ordering rule is executable and testable without a database, and
     * so a caller that has a candidate set can apply the same rule the SQL applies.
     */
    fun orderByImportanceThenRecency(memories: List<MemoryEntity>): List<MemoryEntity> =
        memories.sortedWith(
            compareByDescending<MemoryEntity> { it.importance }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id },
        )

    /** Row -> domain mapping. */
    fun toMemory(entity: MemoryEntity): Memory = Memory(
        id = entity.id,
        text = entity.text,
        keywords = entity.keywords,
        importance = entity.importance,
    )

    /** DDL + triggers for the FTS5 virtual table. */
    object MemoryFtsSchema {
        /**
         * External-content FTS5 over `memories`: the index does not store a second copy
         * of the text, which is why the triggers below must fire on every write. The
         * `IF NOT EXISTS` guards make this safe to re-run.
         */
        val CREATE_STATEMENTS: List<String> = listOf(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                text,
                keywords,
                content='memories',
                content_rowid='id'
            )
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS memories_fts_insert AFTER INSERT ON memories BEGIN
                INSERT INTO memories_fts(rowid, text, keywords)
                VALUES (new.id, new.text, new.keywords);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS memories_fts_delete AFTER DELETE ON memories BEGIN
                INSERT INTO memories_fts(memories_fts, rowid, text, keywords)
                VALUES ('delete', old.id, old.text, old.keywords);
            END
            """.trimIndent(),
            """
            CREATE TRIGGER IF NOT EXISTS memories_fts_update AFTER UPDATE ON memories BEGIN
                INSERT INTO memories_fts(memories_fts, rowid, text, keywords)
                VALUES ('delete', old.id, old.text, old.keywords);
                INSERT INTO memories_fts(rowid, text, keywords)
                VALUES (new.id, new.text, new.keywords);
            END
            """.trimIndent(),
        )
    }
}

/**
 * [MemoryStore] backed by Room + SQLite FTS5.
 *
 * No embeddings, no vectors, no semantic search: lexical only, per the v0 decision in
 * docs/architecture.md section 14. The database is the cache — this class holds no
 * in-memory collection, so its resident size is one DAO reference plus one result list
 * of at most [MemoryQueries.MAX_SEARCH_RESULTS] small objects.
 */
class RoomMemoryStore(
    private val memoryDao: MemoryDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : dev.pidroid.core.agent.MemoryStore {

    override suspend fun remember(text: String, importance: Float): Memory =
        withContext(ioDispatcher) {
            val entity = MemoryEntity(
                id = 0L,
                text = text,
                keywords = MemoryQueries.keywords(text),
                importance = importance,
                createdAt = clock(),
            )
            val id = memoryDao.insert(entity)
            Memory(id = id, text = text, keywords = entity.keywords, importance = importance)
        }

    override suspend fun search(query: String, limit: Int): List<Memory> {
        // No usable terms => no results. Never "everything".
        val raw = MemoryQueries.buildSearchQuery(query, limit) ?: return emptyList()
        return withContext(ioDispatcher) {
            memoryDao.searchFts(raw)
                .let(MemoryQueries::orderByImportanceThenRecency)
                .take(MemoryQueries.MAX_SEARCH_RESULTS)
                .map(MemoryQueries::toMemory)
        }
    }

    override suspend fun forget(id: Long): Boolean = withContext(ioDispatcher) {
        // The AFTER DELETE trigger removes the FTS row, so a forgotten memory cannot
        // resurface through search.
        memoryDao.deleteById(id) > 0
    }

    override suspend fun all(limit: Int): List<Memory> = withContext(ioDispatcher) {
        memoryDao.allRows(MemoryQueries.coerceAllLimit(limit)).map(MemoryQueries::toMemory)
    }

    /** Not part of [dev.pidroid.core.agent.MemoryStore]; used by tests and "forget all" UI. */
    suspend fun clear() = withContext(ioDispatcher) { memoryDao.clear() }

    /** Not part of the interface; lets a caller keep the id sequence monotonic. */
    suspend fun count(): Int = withContext(ioDispatcher) { memoryDao.count() }
}
