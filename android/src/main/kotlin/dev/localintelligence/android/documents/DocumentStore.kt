package dev.localintelligence.android.documents

import dev.localintelligence.android.inference.EmbeddingModel
import dev.localintelligence.core.documents.DocumentChunker
import dev.localintelligence.core.documents.DocumentIndex
import dev.localintelligence.core.documents.IndexedDocument
import dev.localintelligence.core.documents.Passage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** One imported document, as listed on the Documents screen. */
@Serializable
data class DocumentEntry(
    val id: String,
    val title: String,
    val sizeChars: Int,
    val addedAt: Long,
    /**
     * The embedding model the passage vectors were made with, or null when the document
     * has none and is searched by keyword only. Vectors from another model are not
     * comparable, so a different name means they are made again.
     */
    val embeddingModel: String? = null,
)

/**
 * The documents the user added, kept as plain text in app storage.
 *
 * Layout under [root]: `index.json` holds the list of [DocumentEntry], and each
 * document's extracted text is `<id>.txt` beside it. When an embedding model is
 * available, its passage vectors are `<id>.vec` (float32, little endian, one row per
 * passage, in passage order). The passages themselves are not stored: they are cut
 * from the text, so the only persisted copy of a document is the text the user had.
 *
 * ## WHY FILES AND NOT ROOM
 *
 * Room is the right home for rows that change one at a time and are queried. A
 * document is written once, read whole, and removed whole, and the index is
 * rebuilt from scratch on every change. A JSON list and one text file per
 * document are what that access pattern needs, and they add no schema to the
 * database version the other stores share.
 *
 * ## WHY THE INDEX IS CACHED
 *
 * A search runs once per model step that asks for one. Re-reading every document
 * on each search would make a large PDF cost a file read per question, so the
 * built index is kept until a document is added or removed. Writes go through
 * this class, so the cache cannot go stale from outside it.
 *
 * ## WHY THE EMBEDDING MODEL IS A LAMBDA
 *
 * The model is large and loaded on demand, so this class asks for it when it needs
 * it: at add time, and at search time in hybrid mode. Without a model every path is
 * the keyword path it always was.
 */
class DocumentStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val embedder: () -> EmbeddingModel? = { null },
    private val hybridSearch: () -> Boolean = { false },
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = File(root, "index.json")
    private val lock = Any()

    /** The built index, with the vectors for [vectorsModel] when they were made. */
    private class Snapshot(
        val docs: List<IndexedDocument>,
        val index: DocumentIndex,
        val vectors: FloatArray?,
        /** The model [vectors] belong to. Set even when embedding failed, so a broken model is not retried on every search. */
        val vectorsModel: String?,
    )

    @Volatile
    private var cached: Snapshot? = null

    /** Every document, newest first. Never throws: an unreadable index reads as empty. */
    fun list(): List<DocumentEntry> = synchronized(lock) { readIndex() }

    fun isEmpty(): Boolean = list().isEmpty()

    /**
     * Stores [text] as a new document named [title] and rebuilds the index.
     * Returns the new entry. The caller has already checked [text] is not blank.
     */
    fun add(title: String, text: String): DocumentEntry = synchronized(lock) {
        root.mkdirs()
        val id = UUID.randomUUID().toString()
        // Embedding happens here, under the lock: a search that arrives meanwhile waits
        // for the vectors rather than ranking half a document. The text is written
        // first, then the vectors, then the index entry that names them, so a crash
        // leaves at worst an orphan file that no entry points at.
        val model = embedder()
        val vectors = model?.let { embedAll(it, DocumentChunker.chunk(text), title.trim()) }
        val stored = if (model != null && vectors != null) model.fileName else null
        val entry = DocumentEntry(
            id = id,
            title = title.trim().ifEmpty { "Document" }.take(MAX_TITLE_CHARS),
            sizeChars = text.length,
            addedAt = clock(),
            embeddingModel = stored,
        )
        File(root, "$id.txt").writeText(text, Charsets.UTF_8)
        if (vectors != null) writeVectors(id, vectors)
        writeIndex(listOf(entry) + readIndex())
        cached = null
        entry
    }

    /** Removes the document and its text. False when no document has that id. */
    fun remove(id: String): Boolean = synchronized(lock) {
        // Ids are UUIDs we generated. Anything else is not ours to delete, and checking the
        // shape keeps a crafted id from naming a file outside the store.
        if (!ID_SHAPE.matches(id)) return@synchronized false
        val current = readIndex()
        if (current.none { it.id == id }) return@synchronized false
        writeIndex(current.filterNot { it.id == id })
        File(root, "$id.txt").delete()
        File(root, "$id.vec").delete()
        cached = null
        true
    }

    /**
     * The best [limit] passages for [query] across every document. In [hybridSearch]
     * mode with an embedding model loaded, the score is keyword and meaning together;
     * otherwise it is keyword only, as before.
     */
    fun search(query: String, limit: Int): List<Passage> {
        val model = if (hybridSearch()) embedder() else null
        val snap = snapshot(model)
        if (model == null || snap.vectors == null) return snap.index.search(query, limit)
        val queryVector = model.embedQuery(query) ?: return snap.index.search(query, limit)
        return snap.index.searchHybrid(query, queryVector, snap.vectors, limit)
    }

    /**
     * The cached index, made ready for [model]: its vectors are read from disk, or made
     * again for any document whose vectors came from a different model.
     */
    private fun snapshot(model: EmbeddingModel?): Snapshot = synchronized(lock) {
        val base = cached ?: buildSnapshot().also { cached = it }
        val next = if (model == null || base.vectorsModel == model.fileName) {
            base
        } else {
            Snapshot(base.docs, base.index, vectorsFor(model, base.docs), model.fileName)
        }
        cached = next
        next
    }

    private fun buildSnapshot(): Snapshot {
        val docs = readDocuments()
        return Snapshot(docs, DocumentIndex.build(docs), null, null)
    }

    private fun readDocuments(): List<IndexedDocument> = readIndex().mapNotNull { entry ->
        val file = File(root, "${entry.id}.txt")
        if (!file.isFile) null
        else IndexedDocument(entry.id, entry.title, file.readText(Charsets.UTF_8))
    }

    /**
     * One vector per passage of [docs], in the order the index holds them, or null when
     * any document cannot be embedded. A document whose stored vectors match [model] is
     * read; any other is embedded again and its index entry is updated.
     */
    private fun vectorsFor(model: EmbeddingModel, docs: List<IndexedDocument>): FloatArray? {
        val dim = model.dimension
        val parts = ArrayList<FloatArray>(docs.size)
        for (doc in docs) {
            val passages = DocumentChunker.chunk(doc.text)
            val stored = readIndex().firstOrNull { it.id == doc.id }
            val reused = if (stored?.embeddingModel == model.fileName) {
                readVectors(doc.id, passages.size * dim)
            } else {
                null
            }
            val flat = reused ?: run {
                // Clear the entry before the file is replaced: a crash in between must not
                // leave vectors from one model under the name of another.
                setEmbeddingModel(doc.id, null)
                val fresh = embedAll(model, passages, doc.title) ?: return null
                writeVectors(doc.id, fresh)
                setEmbeddingModel(doc.id, model.fileName)
                fresh
            }
            parts += flat
        }
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    /** Sets the model name on one index entry. Rewrites the index, so it is called once per changed document. */
    private fun setEmbeddingModel(id: String, model: String?) {
        writeIndex(readIndex().map { if (it.id == id) it.copy(embeddingModel = model) else it })
    }

    /**
     * Vectors for [passages], flat: one row of `model.dimension` floats each. Null when
     * any batch fails, so a partly embedded document never gets stored.
     */
    private fun embedAll(model: EmbeddingModel, passages: List<String>, title: String?): FloatArray? {
        val dim = model.dimension
        val out = FloatArray(passages.size * dim)
        var at = 0
        for (batch in passages.chunked(EMBED_BATCH)) {
            val vectors = model.embedPassages(batch, title) ?: return null
            for (v in vectors) {
                v.copyInto(out, at)
                at += dim
            }
        }
        return out
    }

    private fun writeVectors(id: String, flat: FloatArray) {
        val bytes = ByteBuffer.allocate(flat.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(flat)
        File(root, "$id.vec").writeBytes(bytes.array())
    }

    /** The stored vectors for [id], or null when the file is missing or the wrong size. */
    private fun readVectors(id: String, floats: Int): FloatArray? {
        val file = File(root, "$id.vec")
        if (!file.isFile || file.length() != floats.toLong() * Float.SIZE_BYTES) return null
        val out = FloatArray(floats)
        ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun readIndex(): List<DocumentEntry> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(DocumentEntry.serializer()), indexFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    /** Written to a temp file and renamed, so a crash mid-write leaves the previous index whole. */
    private fun writeIndex(entries: List<DocumentEntry>) {
        root.mkdirs()
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(DocumentEntry.serializer()), entries), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    companion object {
        /** Longest title kept; the file name is often long and the screen has one line for it. */
        const val MAX_TITLE_CHARS: Int = 80

        /**
         * Longest text stored for one document. A 300-page book is about 600,000 characters;
         * the cap keeps the in-memory index bounded on a phone and says so when it bites.
         */
        const val MAX_STORED_CHARS: Int = 1_500_000

        /** Passages embedded per native call. Keeps one call's arrays small on a phone. */
        private const val EMBED_BATCH: Int = 16

        private val ID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
