package dev.localintelligence.android.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.localintelligence.core.agent.Memory
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
 * ## WHY THIS IS NOT FTS5 — read this before "optimising" it back
 *
 * The obvious way to do lexical search on SQLite is an FTS5 virtual table. On Android
 * that does not exist. AOSP builds the platform `libsqlite.so` from
 * `external/sqlite/dist/Android.bp`, and that file defines `-DSQLITE_ENABLE_FTS3`,
 * `-DSQLITE_ENABLE_FTS3_BACKWARDS` and `-DSQLITE_ENABLE_FTS4` — and **no**
 * `SQLITE_ENABLE_FTS5` — on every release branch checked, from `android8.0.0_r1`
 * (API 26, this module's minSdk) through `main`. `CREATE VIRTUAL TABLE ... USING fts5`
 * therefore fails with `no such module: fts5` on a real device, and because that DDL
 * ran from a `RoomDatabase.Callback.onCreate`, the failure took the *whole database*
 * down rather than degrading one query.
 *
 * `androidx.sqlite`'s bundled driver does ship FTS5, but adopting it means a
 * third-party SQLite on a device with the RAM budget of docs/architecture.md §16, to
 * accelerate a scan over a table already capped at [MAX_ALL_RESULTS] rows. Not a trade
 * worth making.
 *
 * So search is a token-set match over the `keywords` projection, which is the same
 * thing [dev.localintelligence.core.agent.InMemoryMemoryStore] does in RAM: a memory
 * matches when *any* query term is one of its tokens, compared on token boundaries so
 * "nas" cannot match "nasty". Both stores therefore return the same rows for the same
 * corpus, which is the property that actually matters.
 *
 * ## Injection model
 *
 * Search input is untrusted (it is model- or user-generated text). Two independent
 * layers, and the first one alone is sufficient:
 *
 * 1. **Allowlist tokenisation.** [tokenize] keeps only `[a-z0-9]` runs longer than 2
 *    characters, copied from `InMemoryMemoryStore`. Every other character — quote,
 *    semicolon, whitespace, `--`, `%`, `_`, and C-style comment openers — is a
 *    separator and is destroyed. Nothing that could close a pattern or a statement
 *    survives to reach step 2.
 * 2. **Bound parameter.** The finished LIKE pattern is passed as a `?` bind argument to
 *    SQLite, never concatenated into the statement text. Even a hostile expression is
 *    a *value* here, so it cannot alter statement structure.
 *
 * Layers 1 and 2 together mean the attack is dead twice over.
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
     * FTS5 literal quoting is gone with FTS5. What remains is `LIKE` metacharacter
     * escaping: SQLite treats `%` and `_` as wildcards and honours a backslash escape.
     *
     * [tokenize] has already deleted both characters — a surviving term is strictly
     * `[a-z0-9]{3,}` — so this is dead code in practice. It stays because the property
     * it guarantees ("no term can widen its own pattern") is exactly the kind of thing
     * a future loosening of [tokenize] would silently break, and it is cheaper to keep
     * the guarantee than to re-derive that it still holds.
     */
    fun escapeLikeLiteral(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * The SQL `ESCAPE` character [escapeLikeLiteral] emits, declared in the statement
     * as `ESCAPE '\'`. If the two ever disagree, every pattern silently under-matches
     * rather than erroring — the kind of bug that stays invisible until a memory fails
     * to come back.
     */
    const val LIKE_ESCAPE_CHAR: String = "\\"

    /** Clamps a caller-supplied limit into `0..MAX_SEARCH_RESULTS`. */
    fun coerceSearchLimit(limit: Int): Int = limit.coerceIn(0, MAX_SEARCH_RESULTS)

    /** Clamps a caller-supplied limit into `0..MAX_ALL_RESULTS`. */
    fun coerceAllLimit(limit: Int): Int = limit.coerceIn(0, MAX_ALL_RESULTS)

    /**
     * One `LIKE` predicate matching a whole token, never a fragment of one.
     *
     * The surrounding spaces are the whole point. `keywords` is a space-joined token
     * list, so padding both sides turns substring containment back into token
     * membership: without them `' nas ' LIKE '%nas%'` matches `nasty`, which is the
     * exact false positive `InMemoryMemoryStore` refuses to produce.
     *
     * The `?` is a placeholder, never an interpolated value — see the injection model
     * on [MemoryQueries].
     */
    private const val LIKE_PREDICATE: String =
        "(' ' || m.keywords || ' ') LIKE ? ESCAPE '$LIKE_ESCAPE_CHAR'"

    /**
     * The full search statement, with one placeholder per query term plus one for the
     * limit.
     *
     * `OR` rather than `AND`, matching `InMemoryMemoryStore.search`, which accepts a
     * row when *any* query term is present. Ordering is importance first, then
     * recency, then id as a deterministic final tiebreak so pagination cannot flap
     * between equal rows.
     *
     * The statement is built per query because the predicate count is the term count.
     * Only ever `?` placeholders are generated here; no caller text reaches this
     * function.
     */
    fun searchSql(termCount: Int): String {
        val predicates = List(termCount) { LIKE_PREDICATE }.joinToString(" OR ")
        return """
            SELECT m.id, m.text, m.keywords, m.importance, m.created_at
            FROM memories m
            WHERE $predicates
            ORDER BY m.importance DESC, m.created_at DESC, m.id DESC
            LIMIT ?
        """.trimIndent()
    }

    /**
     * The bound arguments for [searchSql], or null when there is nothing to search for.
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
        val terms = tokenize(query).distinct()
        if (terms.isEmpty()) return null
        val patterns = terms.map { "% ${escapeLikeLiteral(it)} %" }
        return (patterns + coerceSearchLimit(limit)).toTypedArray()
    }

    /** The bound query, or null when there is nothing to search for. */
    fun buildSearchQuery(query: String, limit: Int): SupportSQLiteQuery? {
        val terms = tokenize(query).distinct()
        if (terms.isEmpty()) return null
        return SimpleSQLiteQuery(searchSql(terms.size), searchArgs(query, limit))
    }

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
}

/**
 * [MemoryStore] backed by Room.
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
) : dev.localintelligence.core.agent.MemoryStore {

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
            memoryDao.search(raw)
                .let(MemoryQueries::orderByImportanceThenRecency)
                .take(MemoryQueries.MAX_SEARCH_RESULTS)
                .map(MemoryQueries::toMemory)
        }
    }

    override suspend fun forget(id: Long): Boolean = withContext(ioDispatcher) {
        memoryDao.deleteById(id) > 0
    }

    override suspend fun all(limit: Int): List<Memory> = withContext(ioDispatcher) {
        memoryDao.allRows(MemoryQueries.coerceAllLimit(limit)).map(MemoryQueries::toMemory)
    }

    /** Not part of [dev.localintelligence.core.agent.MemoryStore]; used by "forget all" UI. */
    suspend fun clear() = withContext(ioDispatcher) { memoryDao.clear() }

    /** Not part of the interface; lets a caller keep the id sequence monotonic. */
    suspend fun count(): Int = withContext(ioDispatcher) { memoryDao.count() }
}
