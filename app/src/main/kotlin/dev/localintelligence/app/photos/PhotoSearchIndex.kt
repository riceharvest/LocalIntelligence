package dev.localintelligence.app.photos

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.localintelligence.android.data.PhotoCaptionDao
import dev.localintelligence.android.data.PhotoCaptionEntity
import dev.localintelligence.android.inference.EmbeddingModel
import dev.localintelligence.app.media.encodePickedPicture
import dev.localintelligence.core.documents.DocumentIndex
import dev.localintelligence.core.documents.IndexedDocument
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.MediaInput
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The tool plugin id for `photos.search`. Full build only; the Settings screen checks it before showing the row. */
const val PHOTO_SEARCH_PLUGIN_ID: String = "tools.photos"

/** Newest photos one run looks at that are not indexed yet. A run resumes where the last one stopped. */
const val PHOTO_RUN_LIMIT: Int = 500

/** A caption is one short sentence. The cap keeps a rambling answer from filling the index. */
private const val CAPTION_MAX_CHARS = 300
private const val CAPTION_MAX_TOKENS = 80

/** The question put to the vision model for each photo. Short, so the caption stays a caption. */
private const val CAPTION_PROMPT =
    "Describe this photo in one short plain sentence: the main subject, the place, and any " +
        "text you can read. No preamble."

/** One photo a search found: its MediaStore URI and the caption that matched. */
data class PhotoHit(val uri: String, val caption: String)

/** What one caption attempt produced. */
sealed interface Described {
    data class Caption(val text: String) : Described

    /** The model answered, but with nothing usable, or it errored. The photo is skipped, not retried in this run. */
    data object Failed : Described

    /** The stop request reached the model mid-caption. */
    data object Stopped : Described

    /** No local model that can read pictures is loaded. The run cannot start. */
    data object NoVisionModel : Described
}

/** How a run ended. [FINISHED] also covers "nothing new to index". */
enum class PhotoRunEnd { FINISHED, STOPPED, CHAT_BUSY, NO_VISION_MODEL, NO_ACCESS }

data class PhotoRunReport(val captioned: Int, val failed: Int, val end: PhotoRunEnd)

/**
 * The Photo search index: each photo in the library gets a caption from the loaded local
 * vision model, stored with its MediaStore URI in the `photo_captions` table, and searched by
 * the captions.
 *
 * ## WHY CAPTIONS AND NOT IMAGE EMBEDDINGS
 *
 * The phone runs no image encoder, and this feature adds none. The vision model that already
 * answers questions about a picture writes the caption; the text then goes through the same
 * machinery Documents uses. A caption can only find what it says, so a photo whose caption
 * misses a detail is not found by that detail. That limit is stated to the model in the tool.
 *
 * ## WHAT DOES NOT LEAVE THE PHONE
 *
 * Captioning runs only on a local model (the caller refuses a cloud chat model), the captions
 * and vectors are written to the app's own database, and nothing here opens a network
 * connection. The search tool refuses while a cloud model answers, because its results would
 * then be sent to that model.
 *
 * Seams are lambdas so this class holds no model, no database and no platform object of its
 * own: the composition root decides which model and which table it reads.
 */
class PhotoSearchIndex(
    private val appContext: Context,
    private val dao: () -> PhotoCaptionDao,
    private val visionReady: () -> Boolean,
    private val describe: suspend (MediaInput) -> Described,
    private val cancelDescribe: () -> Unit,
    private val embedder: () -> EmbeddingModel?,
) {
    @Volatile
    private var stopRequested = false

    /** Asks a run in progress to stop after the photo it is on, and stops that caption now. */
    fun stop() {
        stopRequested = true
        cancelDescribe()
    }

    /**
     * Captions up to [PHOTO_RUN_LIMIT] of the newest photos that are not indexed yet.
     *
     * [chatBusy] is read before each photo: a chat run that starts mid-run ends this one, so
     * the chat never waits behind a long caption queue. Rows written before a stop stay, so
     * the next run skips them.
     */
    suspend fun run(chatBusy: () -> Boolean, onProgress: (done: Int, total: Int) -> Unit): PhotoRunReport =
        withContext(Dispatchers.IO) {
            stopRequested = false
            if (chatBusy()) return@withContext PhotoRunReport(0, 0, PhotoRunEnd.CHAT_BUSY)
            if (!visionReady()) return@withContext PhotoRunReport(0, 0, PhotoRunEnd.NO_VISION_MODEL)

            val todo = try {
                unindexedNewest(dao().uris().toHashSet(), PHOTO_RUN_LIMIT)
            } catch (e: SecurityException) {
                // The photo permission was refused or revoked. Nothing is read.
                return@withContext PhotoRunReport(0, 0, PhotoRunEnd.NO_ACCESS)
            }

            // Loaded once per run: the embedding model is optional, and a missing one means
            // the captions are stored without vectors and searched by keyword only.
            val model = embedder()
            val resolver = appContext.contentResolver
            var captioned = 0
            var failed = 0
            for ((i, uri) in todo.withIndex()) {
                if (stopRequested) return@withContext PhotoRunReport(captioned, failed, PhotoRunEnd.STOPPED)
                if (chatBusy()) return@withContext PhotoRunReport(captioned, failed, PhotoRunEnd.CHAT_BUSY)
                onProgress(i, todo.size)

                val media = runCatching { encodePickedPicture(resolver, Uri.parse(uri)) }.getOrNull()
                if (media == null) {
                    failed++
                    continue
                }
                when (val result = describe(media.media)) {
                    Described.NoVisionModel -> return@withContext PhotoRunReport(captioned, failed, PhotoRunEnd.NO_VISION_MODEL)
                    Described.Stopped -> return@withContext PhotoRunReport(captioned, failed, PhotoRunEnd.STOPPED)
                    Described.Failed -> failed++
                    is Described.Caption -> {
                        val vector = model?.embedPassages(listOf(result.text), null)?.firstOrNull()
                        dao().insert(
                            PhotoCaptionEntity(
                                uri = uri,
                                caption = result.text,
                                indexedAt = System.currentTimeMillis(),
                                embedding = vector?.toBytes(),
                                embeddingModel = model?.fileName.takeIf { vector != null },
                            ),
                        )
                        captioned++
                    }
                }
            }
            onProgress(todo.size, todo.size)
            PhotoRunReport(captioned, failed, PhotoRunEnd.FINISHED)
        }

    /** Photos indexed so far. */
    suspend fun count(): Int = withContext(Dispatchers.IO) { dao().count() }

    /** Deletes the whole index. The photos themselves are not touched. */
    suspend fun clear(): Int = withContext(Dispatchers.IO) { dao().clear() }

    /**
     * The best [limit] photos for [query], best first, each with its URI so the answer can show
     * it. Hybrid when the embedding model is present and every row has a vector from that same
     * model; otherwise keyword only, through the same BM25 index Documents uses.
     */
    suspend fun search(query: String, limit: Int): List<PhotoHit> = withContext(Dispatchers.IO) {
        val rows = dao().all()
        if (rows.isEmpty() || limit <= 0) return@withContext emptyList()
        val index = DocumentIndex.build(rows.map { IndexedDocument(it.uri, "photo", it.caption) })

        val model = embedder()
        val vectored = model != null && rows.all { it.embedding != null && it.embeddingModel == model.fileName }
        val queryVector = if (vectored) model?.embedQuery(query) else null
        val passages = if (queryVector != null) {
            val flat = FloatArray(rows.sumOf { it.embedding?.size ?: 0 } / Float.SIZE_BYTES)
            var at = 0
            for (row in rows) {
                val v = row.embedding?.toFloats() ?: FloatArray(0)
                v.copyInto(flat, at)
                at += v.size
            }
            index.searchHybrid(query, queryVector, flat, limit)
        } else {
            index.search(query, limit)
        }
        // The document id is the photo's URI (see the IndexedDocument above).
        passages.map { PhotoHit(uri = it.docId, caption = it.text) }
    }

    /**
     * URIs of the newest photos not in [known], newest first, at most [limit]. Reads only the
     * IDs. The sort is the MediaStore date the photo was added, which is what "newest" means
     * to the phone's own gallery.
     */
    private fun unindexedNewest(known: Set<String>, limit: Int): List<String> {
        val out = ArrayList<String>()
        appContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (out.size < limit && cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn))
                    .toString()
                if (uri !in known) out += uri
            }
        }
        return out
    }

    private fun FloatArray.toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(this)
        return buffer.array()
    }

    private fun ByteArray.toFloats(): FloatArray {
        val floats = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }
}

/**
 * One caption from the loaded model. A caller passes null when no local picture-reading model
 * is loaded, which is the same answer as "no vision model" from the run.
 *
 * `reusePrefix = false`: the caption prompt shares no prefix with the chat, so keeping it
 * would only evict the chat's own cached prompt.
 */
suspend fun describePhoto(backend: ModelBackend?, media: MediaInput): Described {
    if (backend == null) return Described.NoVisionModel
    val result = backend.generate(
        GenerationRequest(
            messages = listOf(ChatMessage.User(CAPTION_PROMPT)),
            params = SamplingParams(temperature = 0f, maxOutputTokens = CAPTION_MAX_TOKENS),
            media = listOf(media),
            reusePrefix = false,
        ),
    )
    return when (result.stopReason) {
        StopReason.CANCELLED -> Described.Stopped
        StopReason.ERROR -> Described.Failed
        StopReason.COMPLETED, StopReason.MAX_TOKENS -> result.text.trim().take(CAPTION_MAX_CHARS)
            .takeIf { it.isNotBlank() }?.let { Described.Caption(it) } ?: Described.Failed
    }
}
