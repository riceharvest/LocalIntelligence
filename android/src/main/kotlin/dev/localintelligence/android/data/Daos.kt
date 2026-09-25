package dev.localintelligence.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("UPDATE sessions SET last_active_at = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long): Int

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    @Query("DELETE FROM sessions")
    suspend fun clear()
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity): Long

    /**
     * Insertion order. [MessageEntity.id] is an autoincrement rowid, so ascending id
     * is exactly the order the conversation happened in. Compaction depends on this:
     * it decides which messages fall off the end of the window.
     */
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY id ASC")
    suspend fun forSession(sessionId: Long): List<MessageEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM messages WHERE session_id = :sessionId")
    suspend fun maxId(sessionId: Long): Long

    @Query("SELECT COUNT(*) FROM messages WHERE session_id = :sessionId")
    suspend fun countIn(sessionId: Long): Int

    @Query("DELETE FROM messages WHERE session_id = :sessionId AND id < :exclusiveUpperBound")
    suspend fun deleteBefore(sessionId: Long, exclusiveUpperBound: Long): Int

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(memory: MemoryEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: Long): MemoryEntity?

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    /**
     * Insertion order, matching `InMemoryMemoryStore.all()`.
     *
     * The LIMIT is bound, not interpolated, and the caller has already clamped it to
     * `MemoryQueries.coerceAllLimit`. A bounded query is the RAM budget of
     * docs/architecture.md section 16 expressed in SQL: we never materialise more
     * rows than we are willing to hold.
     */
    @Query("SELECT * FROM memories ORDER BY id ASC LIMIT :limit")
    suspend fun allRows(limit: Int): List<MemoryEntity>

    /**
     * Lexical search over the `keywords` projection, built by `MemoryQueries`.
     *
     * `@RawQuery` is not an optimisation here, it is a requirement: the number of
     * `LIKE` predicates is the number of query terms, which is not known at compile
     * time, so no fixed `@Query` string can express it. `MemoryQueries.searchSql`
     * emits the statement and `MemoryQueries.searchArgs` the matching bindings; the
     * user's text is bound as a parameter and never concatenated into the statement.
     */
    @RawQuery(observedEntities = [MemoryEntity::class])
    suspend fun search(query: SupportSQLiteQuery): List<MemoryEntity>

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface SummaryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(summary: SessionSummaryEntity): Long

    @Query("SELECT * FROM session_summaries WHERE session_id = :sessionId ORDER BY from_message_id ASC")
    suspend fun forSession(sessionId: Long): List<SessionSummaryEntity>

    @Query("DELETE FROM session_summaries WHERE session_id = :sessionId")
    suspend fun clearSession(sessionId: Long): Int

    @Query("DELETE FROM session_summaries")
    suspend fun clear()
}
