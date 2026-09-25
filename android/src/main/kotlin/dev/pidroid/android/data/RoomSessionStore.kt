package dev.pidroid.android.data

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.pidroid.core.model.ChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [dev.pidroid.core.agent.SessionStore] backed by Room.
 *
 * Holds no in-memory transcript. `messages()` streams a bounded window back from
 * SQLite, which is what makes this safe on a phone (docs/architecture.md section 16):
 * the database *is* the cache, so an idle session costs nothing but a few rows on disk.
 */
class RoomSessionStore(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val summaryDao: SummaryDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : dev.pidroid.core.agent.SessionStore {

    override suspend fun createSession(): Long = withContext(ioDispatcher) {
        val now = clock()
        sessionDao.insert(
            SessionEntity(
                id = 0L,
                createdAt = now,
                lastActiveAt = now,
                title = null,
            ),
        )
    }

    override suspend fun appendMessage(sessionId: Long, message: ChatMessage) {
        withContext(ioDispatcher) {
            // Touching first means an append to a non-existent session fails loudly on
            // the message insert (FK constraint) instead of silently orphaning a row.
            sessionDao.touch(sessionId, clock())
            messageDao.insert(MessageMapping.toColumns(message, sessionId, clock()))
        }
    }

    /**
     * Full history in insertion order. Compaction reads exactly this to decide which
     * messages to collapse, so the ordering is load-bearing, not cosmetic.
     */
    override suspend fun messages(sessionId: Long): List<ChatMessage> = withContext(ioDispatcher) {
        messageDao.forSession(sessionId).map(MessageMapping::fromColumns)
    }

    /**
     * Wipes sessions, messages and summaries. Memories are deliberately NOT cleared:
     * a cleared conversation history is not the same request as "forget everything I
     * know", and conflating them would destroy durable memory the user asked to keep.
     * `RoomMemoryStore.clear()` is the explicit path for that.
     */
    override suspend fun clear() = withContext(ioDispatcher) {
        // Child rows first: explicit and correct even though the FK cascade would
        // handle it, and it keeps the intent readable at the call site.
        messageDao.clear()
        summaryDao.clear()
        sessionDao.clear()
    }

    // --- Not part of SessionStore. Everything below is for compaction and tests. ---

    suspend fun countMessages(sessionId: Long): Int =
        withContext(ioDispatcher) { messageDao.countIn(sessionId) }

    suspend fun maxMessageId(sessionId: Long): Long =
        withContext(ioDispatcher) { messageDao.maxId(sessionId) }

    suspend fun summarise(sessionId: Long, fromMessageId: Long, toMessageId: Long, summary: String) =
        withContext(ioDispatcher) {
            summaryDao.insert(
                SessionSummaryEntity(
                    id = 0L,
                    sessionId = sessionId,
                    fromMessageId = fromMessageId,
                    toMessageId = toMessageId,
                    summary = summary,
                    createdAt = clock(),
                ),
            )
        }

    suspend fun summaries(sessionId: Long): List<SessionSummaryEntity> =
        withContext(ioDispatcher) { summaryDao.forSession(sessionId) }

    /** Drops the oldest messages once a summary has absorbed them. */
    suspend fun pruneCompactedMessages(sessionId: Long, exclusiveUpperBound: Long): Int =
        withContext(ioDispatcher) { messageDao.deleteBefore(sessionId, exclusiveUpperBound) }
}

/**
 * Creates the FTS5 virtual table and its sync triggers.
 *
 * Room 2.7.2 ships `@Fts3`/`@Fts4` annotations but **no `@Fts5`**, so there is no
 * annotation-driven way to declare this table. The supported alternative is a
 * `RoomDatabase.Callback` that executes the DDL on create, which is what this is.
 * The consequence, and it is not a small one: because the table is invisible to the
 * Room schema, KSP cannot resolve queries against it, so search must go through
 * `@RawQuery` (`MemoryDao.searchFts`) rather than a checked `@Query`.
 */
class MemoryFtsCallback : androidx.room.RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        MemoryQueries.MemoryFtsSchema.CREATE_STATEMENTS.forEach(db::execSQL)
    }
}
