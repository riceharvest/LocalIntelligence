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
 * One change the assistant made to the memory block: the text before, the text after, and the note it
 * kept. This is the Undo ledger, kept so the "Remembered: ..." row still has its Undo after the app
 * restarts. Added in database version 8 (see `LocalIntelligenceDatabase.MIGRATION_7_8`).
 *
 * The id is the change id the chat rows already hold, so it is not generated here.
 */
@Entity(tableName = "memory_changes")
data class MemoryChangeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "text_before") val before: String,
    @ColumnInfo(name = "text_after") val after: String,
    @ColumnInfo(name = "note") val note: String,
    @ColumnInfo(name = "undone") val undone: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface MemoryChangeDao {
    @Query("SELECT * FROM memory_changes ORDER BY id ASC")
    suspend fun all(): List<MemoryChangeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: MemoryChangeEntity)

    @Query("UPDATE memory_changes SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long): Int

    /** Keeps the newest [keep] rows. An old change is never undone in practice, and the table stays small. */
    @Query("DELETE FROM memory_changes WHERE id NOT IN (SELECT id FROM memory_changes ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int): Int
}

/**
 * The memory-change DAO, opened on first use. Built here because `:app` cannot call members of
 * [LocalIntelligenceDatabase] (see [lazyPhotoCaptionDao] for the same reason).
 */
fun lazyMemoryChangeDao(context: Context): () -> MemoryChangeDao {
    val app = context.applicationContext
    val dao = lazy { LocalIntelligenceDatabase.build(app).memoryChangeDao() }
    return { dao.value }
}
