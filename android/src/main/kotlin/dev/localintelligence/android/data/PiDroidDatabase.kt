package dev.localintelligence.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The LocalIntelligence database. Four tables — `sessions`, `messages`, `memories`,
 * `session_summaries` — per docs/architecture.md section 14.
 *
 * There is no FTS index and no `RoomDatabase.Callback`. A previous revision created an
 * FTS5 virtual table from `onCreate`; AOSP's platform SQLite does not enable
 * `SQLITE_ENABLE_FTS5` at any API level, so that DDL raised `no such module: fts5`
 * while the database was still being created and left the store permanently
 * unopenable. Search now runs against the `memories.keywords` projection instead — see
 * the KDoc on [MemoryQueries] for the full reasoning.
 *
 * @see MemoryQueries for why search is a token-set `LIKE` scan and not FTS5.
 */
@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        MemoryEntity::class,
        SessionSummaryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(LocalIntelligenceTypeConverters::class)
abstract class LocalIntelligenceDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun summaryDao(): SummaryDao

    companion object {
        const val NAME: String = "localintelligence.db"

        /**
         * On-disk LocalIntelligence database.
         *
         * `fallbackToDestructiveMigration` is off on purpose: silently dropping a
         * user's memories on a schema bump is not an acceptable failure mode for the
         * one store that is supposed to be durable. v0 has no migrations because v0 has
         * no v1 to migrate from; the next version adds them deliberately.
         *
         * No `addCallback` here, deliberately. Room opens the file lazily on first
         * query, not at `build()` time, so a caller cannot treat a successful `build()`
         * as proof the database works — see `ResilientMemoryStore` for how a failure
         * to actually open is handled.
         */
        fun build(context: Context, name: String = NAME): LocalIntelligenceDatabase =
            Room.databaseBuilder(context.applicationContext, LocalIntelligenceDatabase::class.java, name)
                .build()
    }
}

/** Convenience accessors so callers do not have to know the DAO wiring. */
fun LocalIntelligenceDatabase.memoryStore(): RoomMemoryStore = RoomMemoryStore(memoryDao())

fun LocalIntelligenceDatabase.sessionStore(): RoomSessionStore =
    RoomSessionStore(sessionDao(), messageDao(), summaryDao())
