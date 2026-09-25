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
 * `memories_fts` is intentionally absent from [entities]: it is an FTS5 *virtual*
 * table created by [MemoryFtsCallback], because Room 2.7.2 has no `@Fts5` annotation.
 * Declaring it as an entity is not an option; a virtual table is not an ordinary table.
 *
 * @see MemoryFtsCallback for why search uses `@RawQuery`.
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
         */
        fun build(context: Context, name: String = NAME): LocalIntelligenceDatabase =
            Room.databaseBuilder(context.applicationContext, LocalIntelligenceDatabase::class.java, name)
                .addCallback(MemoryFtsCallback())
                .build()
    }
}

/** Convenience accessors so callers do not have to know the DAO wiring. */
fun LocalIntelligenceDatabase.memoryStore(): RoomMemoryStore = RoomMemoryStore(memoryDao())

fun LocalIntelligenceDatabase.sessionStore(): RoomSessionStore =
    RoomSessionStore(sessionDao(), messageDao(), summaryDao())
