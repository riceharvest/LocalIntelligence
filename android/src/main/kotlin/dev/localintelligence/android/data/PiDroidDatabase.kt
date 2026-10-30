package dev.localintelligence.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The LocalIntelligence database. Four tables — `sessions`, `messages`, `memories`,
 * `session_summaries` — per docs/architecture.md section 14, and since version 2
 * `observations`, the full text of long tool results ([ObservationEntity]).
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
        ObservationEntity::class,
    ],
    version = 2,
    // Exported to android/schemas and committed: each migration's SQL is copied from
    // there, so a migrated table matches what Room validates on open.
    exportSchema = true,
)
@TypeConverters(LocalIntelligenceTypeConverters::class)
abstract class LocalIntelligenceDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun summaryDao(): SummaryDao
    abstract fun observationDao(): ObservationDao

    companion object {
        const val NAME: String = "localintelligence.db"

        /**
         * On-disk LocalIntelligence database.
         *
         * `fallbackToDestructiveMigration` is off on purpose: silently dropping a
         * user's memories on a schema bump is not an acceptable failure mode for the
         * one store that is supposed to be durable. So every version bump adds a
         * [Migration] here, and a missing one fails on open instead of wiping data.
         *
         * No `addCallback` here, deliberately. Room opens the file lazily on first
         * query, not at `build()` time, so a caller cannot treat a successful `build()`
         * as proof the database works — see `ResilientMemoryStore` for how a failure
         * to actually open is handled.
         */
        fun build(context: Context, name: String = NAME): LocalIntelligenceDatabase =
            Room.databaseBuilder(context.applicationContext, LocalIntelligenceDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * 1 to 2: adds `observations`. The statement is copied verbatim from
         * `android/schemas/dev.localintelligence.android.data.LocalIntelligenceDatabase/2.json`
         * (`createSql`, with Room's table-name placeholder filled in). Room checks the
         * migrated table against the entity on open and refuses to start on any
         * difference, so this is not hand-written SQL.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `observations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tool_name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `text` TEXT NOT NULL, `original_chars` INTEGER NOT NULL)",
                )
            }
        }
    }
}

/** Convenience accessors so callers do not have to know the DAO wiring. */
fun LocalIntelligenceDatabase.memoryStore(): RoomMemoryStore = RoomMemoryStore(memoryDao())

fun LocalIntelligenceDatabase.sessionStore(): RoomSessionStore =
    RoomSessionStore(sessionDao(), messageDao(), summaryDao())
