package dev.localintelligence.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.localintelligence.android.phoneindex.PhoneIndexDao
import dev.localintelligence.android.phoneindex.PhoneIndexEntity

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
        PhotoCaptionEntity::class,
        PersonaEntity::class,
        PhoneIndexEntity::class,
    ],
    version = 5,
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
    abstract fun photoCaptionDao(): PhotoCaptionDao
    abstract fun personaDao(): PersonaDao
    abstract fun phoneIndexDao(): PhoneIndexDao

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

        /**
         * 2 to 3: adds `personas` (the saved personas, see [PersonaEntity]) and `sessions.persona_id`
         * (the persona each chat was started with). Both are copied from the 3.json schema. The new
         * column is nullable with no default, so existing chats read as "no persona" and nothing
         * is rewritten.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `personas` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `text` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
                )
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `persona_id` INTEGER")
            }
        }

        /**
         * 3 to 4: adds `photo_captions`, the Photo search index. One row per indexed photo.
         * Room checks the table against [PhotoCaptionEntity] on open, so a mismatch fails
         * loudly instead of silently dropping the index.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photo_captions` (`uri` TEXT NOT NULL, `caption` TEXT NOT NULL, `indexed_at` INTEGER NOT NULL, `embedding` BLOB, `embedding_model` TEXT, PRIMARY KEY(`uri`))",
                )
            }
        }

        /**
         * 4 to 5: adds `phone_index`, the Search my phone index, and its unique (source, source_key)
         * index. Both statements are the ones Room generates for [PhoneIndexEntity] (see
         * android/schemas/.../5.json); Room compares the table and the index with the entity on open.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `phone_index` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `source_key` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `when_at` INTEGER NOT NULL, `indexed_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_phone_index_source_source_key` ON `phone_index` (`source`, `source_key`)",
                )
            }
        }
    }
}

/** Convenience accessors so callers do not have to know the DAO wiring. */
fun LocalIntelligenceDatabase.memoryStore(): RoomMemoryStore = RoomMemoryStore(memoryDao())

fun LocalIntelligenceDatabase.sessionStore(): RoomSessionStore =
    RoomSessionStore(sessionDao(), messageDao(), summaryDao())
