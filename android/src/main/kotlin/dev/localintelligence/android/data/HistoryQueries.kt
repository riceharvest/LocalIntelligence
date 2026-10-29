package dev.localintelligence.android.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import dev.localintelligence.core.memory.MemoryText

/**
 * SQL for `history.search`: a bounded lexical prefilter over past chat messages.
 *
 * ## WHY A LIKE SCAN AND NOT FTS5
 *
 * Same reason as [MemoryQueries]: platform SQLite has no FTS5 module, so the virtual
 * table cannot be created. The prefilter is `LIKE '%term%'` over `messages.text`, OR'd
 * across the query's stemmed terms, so a candidate is any message containing at least
 * one term. The exact ranking is then done in Kotlin by [dev.localintelligence.core.memory.MemoryIndex],
 * the same ranker the memory store uses, so the two features agree on what a match is.
 *
 * Stems are suffix-stripped forms, so a stem is almost always a substring of the word the
 * user typed; the one known miss is a stem that changes an inner letter (for example
 * "cities" to "citi" is fine, but a stem that rewrites "ies" to "y" would not match).
 *
 * ## WHY THE SCAN IS CAPPED
 *
 * [SCAN_LIMIT] rows of a long history are loaded per query, newest first. Assistant
 * replies can be long, so an unbounded scan would be the RAM regression
 * `docs/architecture.md` section 16 forbids. A search older than the newest
 * [SCAN_LIMIT] matching messages is not found, and the tool says so.
 */
object HistoryQueries {

    /** Candidate rows loaded per search, newest first. */
    const val SCAN_LIMIT: Int = 300

    /** The two roles a past-chat search reads. Tool observations are excluded on purpose. */
    private val TEXT_KINDS: List<String> = listOf(MessageKind.USER.wire, MessageKind.ASSISTANT.wire)

    /** The query's terms as LIKE patterns, or empty when the query has no usable term. */
    fun patternsFor(query: String): List<String> =
        MemoryText.terms(query).distinct().map { "%${MemoryQueries.escapeLikeLiteral(it)}%" }

    /**
     * The bound statement for [patterns], or null when there is nothing to search for.
     * Null is load-bearing: a query of stopwords only must return no rows, never every
     * row in the history.
     */
    fun buildSearch(patterns: List<String>): SupportSQLiteQuery? {
        if (patterns.isEmpty()) return null
        val kindPlaceholders = TEXT_KINDS.joinToString(",") { "?" }
        val termPredicates = patterns.joinToString(" OR ") { "lower(text) LIKE ? ESCAPE '${MemoryQueries.LIKE_ESCAPE_CHAR}'" }
        val sql = """
            SELECT id, kind, text, created_at FROM messages
            WHERE kind IN ($kindPlaceholders) AND text IS NOT NULL AND ($termPredicates)
            ORDER BY id DESC
            LIMIT ?
        """.trimIndent()
        val args: Array<Any?> = (TEXT_KINDS + patterns + SCAN_LIMIT).toTypedArray()
        return SimpleSQLiteQuery(sql, args)
    }
}
