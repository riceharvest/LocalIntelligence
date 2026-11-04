package dev.localintelligence.android.phoneindex

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One item of "Search my phone": a notification, a text, an event, a contact name or a line of
 * a file. Stored in the app's own database and never sent anywhere by the index itself.
 *
 * [source] is the `PhoneIndexSource.id`; [sourceKey] names the item inside its source, and the
 * pair is unique, so indexing an item again replaces it. [indexedAt] is the run that last saw it:
 * a run that reads a whole source deletes the rows of that source it did not see, so a deleted text
 * or event leaves the index with the next run.
 *
 * Photo captions are not copied here. They stay in `photo_captions` and are read in place.
 */
@Entity(
    tableName = "phone_index",
    indices = [Index(name = "index_phone_index_source_source_key", value = ["source", "source_key"], unique = true)],
)
data class PhoneIndexEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "when_at") val whenAt: Long,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)

/** How many items one source holds, from [PhoneIndexDao.counts]. */
data class PhoneIndexSourceCount(
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "n") val n: Int,
)

/**
 * Blocking, like the other DAOs here: every caller works off the main thread (the listener hands
 * its insert to a background scope, and the index run is on Dispatchers.IO), so a wrong caller
 * fails loudly with Room's main-thread check.
 */
@Dao
interface PhoneIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(rows: List<PhoneIndexEntity>)

    @Query("SELECT * FROM phone_index WHERE source IN (:sources) ORDER BY when_at DESC")
    fun forSources(sources: List<String>): List<PhoneIndexEntity>

    @Query("SELECT source, COUNT(*) AS n FROM phone_index GROUP BY source")
    fun counts(): List<PhoneIndexSourceCount>

    @Query("DELETE FROM phone_index WHERE source = :source")
    fun deleteSource(source: String): Int

    /** Drops the rows of [source] that a run which read the whole source did not see. */
    @Query("DELETE FROM phone_index WHERE source = :source AND indexed_at < :runStart")
    fun pruneUnseen(source: String, runStart: Long): Int

    /** Keeps the newest [keep] rows of [source]. */
    @Query(
        "DELETE FROM phone_index WHERE source = :source AND id NOT IN " +
            "(SELECT id FROM phone_index WHERE source = :source ORDER BY when_at DESC LIMIT :keep)",
    )
    fun pruneOverflow(source: String, keep: Int): Int
}
