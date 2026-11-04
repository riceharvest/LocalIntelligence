package dev.localintelligence.android.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The last terminal text read from one desktop agent, so the user can read it offline. Added in database
 * version 5 (see `LocalIntelligenceDatabase.MIGRATION_4_5`). One row per agent, replaced on each read.
 *
 * WHY the database and not a file or preferences: this is per-agent durable state the phone already keeps for
 * chats and memories, and Room already handles its migrations. The text stays on the phone only.
 */
@Entity(tableName = "desktop_text")
data class DesktopTextEntity(
    @PrimaryKey @ColumnInfo(name = "target") val target: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "state_seq") val stateSeq: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * A message typed while the desktop could not be reached. Sent in order when it can be. Only text is queued:
 * key presses are never queued, because an approval made long after it was shown would answer a question that
 * may no longer be on the desktop. Version 5, see [DesktopTextEntity].
 */
@Entity(tableName = "desktop_outbox")
data class DesktopOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "target") val target: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * The My desktop DAO, opened on first use. Built here because `:app` cannot call members of
 * [LocalIntelligenceDatabase] (see [lazyPhotoCaptionDao] for the same reason).
 */
fun lazyDesktopDao(context: Context): () -> DesktopDao {
    val app = context.applicationContext
    val dao = lazy { LocalIntelligenceDatabase.build(app).desktopDao() }
    return { dao.value }
}

@Dao
interface DesktopDao {
    @Query("SELECT * FROM desktop_text WHERE target = :target")
    suspend fun text(target: String): DesktopTextEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putText(entity: DesktopTextEntity)

    @Query("SELECT * FROM desktop_outbox ORDER BY id ASC")
    suspend fun outbox(): List<DesktopOutboxEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(entity: DesktopOutboxEntity): Long

    @Query("DELETE FROM desktop_outbox WHERE id = :id")
    suspend fun dequeue(id: Long): Int

    @Query("SELECT COUNT(*) FROM desktop_outbox")
    suspend fun outboxCount(): Int

    @Query("SELECT * FROM desktop_outbox WHERE target = :target ORDER BY id ASC")
    suspend fun outboxFor(target: String): List<DesktopOutboxEntity>
}
