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
 * One photo from the device's library, with the caption the local vision model wrote for it.
 *
 * [uri] is the MediaStore content URI, which is the key: a photo is indexed once, and a run
 * that stops part way resumes by skipping the URIs already here. [caption] is the only text
 * a search reads. [embedding] is the caption's meaning vector, little-endian float32, and it
 * is stored only when the embedding model was present at index time; [embeddingModel] names
 * the file it was made with, so a search never mixes vectors from two models.
 *
 * Nothing in this row leaves the phone: the caption is written by the local model and
 * stored in the app's own database.
 */
@Entity(tableName = "photo_captions")
data class PhotoCaptionEntity(
    @PrimaryKey @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "caption") val caption: String,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
    @ColumnInfo(name = "embedding") val embedding: ByteArray?,
    @ColumnInfo(name = "embedding_model") val embeddingModel: String?,
)

/**
 * Blocking, like [ObservationDao]: every caller is already on a background thread (the index
 * run and the search tool both work off the loop's thread), and Room refuses a blocking call
 * on the main thread, so a wrong caller fails loudly.
 */
@Dao
interface PhotoCaptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(row: PhotoCaptionEntity)

    @Query("SELECT uri FROM photo_captions")
    fun uris(): List<String>

    @Query("SELECT * FROM photo_captions")
    fun all(): List<PhotoCaptionEntity>

    @Query("SELECT COUNT(*) FROM photo_captions")
    fun count(): Int

    @Query("DELETE FROM photo_captions")
    fun clear(): Int
}

/**
 * The Photo search DAO, opened on first use. Built here rather than in `:app`: `:app` cannot call
 * members of [LocalIntelligenceDatabase] (its Room supertype is not on `:app`'s classpath), so the
 * factory does the call. Like [lazyObservationStore], it opens its own database instance on the
 * same file, which Room allows; the first use is the only one that builds it.
 */
fun lazyPhotoCaptionDao(context: Context): () -> PhotoCaptionDao {
    val app = context.applicationContext
    val dao = lazy { LocalIntelligenceDatabase.build(app).photoCaptionDao() }
    return { dao.value }
}

