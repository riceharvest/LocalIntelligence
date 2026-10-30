package dev.localintelligence.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * The full text of one tool result that was too long for the model, kept so the
 * model can read the rest with `observations.read`.
 * See [dev.localintelligence.core.tool.ObservationStore].
 *
 * [id] is AUTOINCREMENT on purpose: old rows are deleted to keep the table at the
 * newest [dev.localintelligence.core.tool.ObservationStore.MAX_KEPT], and a plain
 * rowid could then hand a deleted row's id to a new result. A stale `obs#41` in
 * the chat must read nothing rather than a different result.
 *
 * No foreign key to `sessions`: a result stays readable from a later chat through
 * `history.search`, the same way past messages do.
 */
@Entity(tableName = "observations")
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** At most [dev.localintelligence.core.tool.ObservationStore.MAX_STORED_CHARS] characters. */
    @ColumnInfo(name = "text") val text: String,
    /** Length of the tool's output before the cap, so a reader can say what was not kept. */
    @ColumnInfo(name = "original_chars") val originalChars: Int,
)

/**
 * One `history.search` candidate from [ObservationEntity]: everything but the text,
 * plus how many query terms it contains and a short window around the first one.
 * A row can hold 256 K characters, so a search never loads the text itself.
 */
data class ObservationHit(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "original_chars") val originalChars: Int,
    @ColumnInfo(name = "hits") val hits: Int,
    @ColumnInfo(name = "snippet") val snippet: String?,
)

/**
 * Blocking on purpose, except [search]. [dev.localintelligence.core.tool.ObservationStore]
 * is a plain interface and its callers are already off the main thread; Room refuses
 * a blocking call on the main thread, so a wrong caller fails loudly rather than
 * janking. [search] is `suspend` like its sibling `MessageDao.searchText`, because
 * `history.search` calls both from the same coroutine.
 */
@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(row: ObservationEntity): Long

    @Query("SELECT * FROM observations WHERE id = :id")
    fun byId(id: Long): ObservationEntity?

    /**
     * Deletes every row older than the newest [keep]. The subquery is the id of the
     * first row past the limit; with [keep] rows or fewer it is NULL, and
     * `id <= NULL` deletes nothing.
     */
    @Query(
        "DELETE FROM observations WHERE id <= " +
            "(SELECT id FROM observations ORDER BY id DESC LIMIT 1 OFFSET :keep)",
    )
    fun keepNewest(keep: Int): Int

    /** Statement built by [ObservationQueries.buildSearch]; only `?` placeholders reach it. */
    @RawQuery(observedEntities = [ObservationEntity::class])
    suspend fun search(query: SupportSQLiteQuery): List<ObservationHit>
}

/**
 * SQL for the saved-results half of `history.search`.
 *
 * Same prefilter as [HistoryQueries]: `LIKE '%term%'` OR'd across the query's terms.
 * Two differences, both because one row can be 256 K characters:
 *  - no `lower(text)` in the filter. SQLite's LIKE already ignores ASCII case, and
 *    `lower()` would copy every row's text during the scan;
 *  - the text is never selected. The statement returns a [SNIPPET_CHARS] window
 *    starting a little before the first term, and the number of terms the row
 *    contains, which is the ranking. Only the [SCAN_LIMIT] newest matching rows
 *    pay for the `lower()` the window needs.
 */
object ObservationQueries {

    /** Candidate rows per search, newest first. */
    const val SCAN_LIMIT: Int = 30

    /** Characters of text returned around the first term. Room for a 160-char snippet. */
    const val SNIPPET_CHARS: Int = 240

    /** How far before the first term the window starts. */
    private const val LEAD_CHARS: Int = 60

    /**
     * The bound statement for [terms] (lowercase, as `MemoryText.terms` returns them),
     * or null when there is nothing to search for, so a query of stopwords returns no
     * rows rather than every row.
     */
    fun buildSearch(terms: List<String>): SupportSQLiteQuery? {
        if (terms.isEmpty()) return null
        val patterns = terms.map { "%${MemoryQueries.escapeLikeLiteral(it)}%" }
        val like = "text LIKE ? ESCAPE '${MemoryQueries.LIKE_ESCAPE_CHAR}'"
        val hits = patterns.joinToString(" + ") { "($like)" }
        val any = patterns.joinToString(" OR ") { like }
        val sql = """
            SELECT id, tool_name, created_at, original_chars, ($hits) AS hits,
                substr(text, max(1, instr(lower(text), ?) - $LEAD_CHARS), $SNIPPET_CHARS) AS snippet
            FROM observations
            WHERE ($any)
            ORDER BY id DESC
            LIMIT ?
        """.trimIndent()
        // Placeholders bind left to right: the hit count, the first term, the filter, the limit.
        val args: Array<Any?> = (patterns + terms.first() + patterns + SCAN_LIMIT).toTypedArray()
        return SimpleSQLiteQuery(sql, args)
    }
}
