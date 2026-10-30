package dev.localintelligence.android.documents

import dev.localintelligence.core.documents.DocumentIndex
import dev.localintelligence.core.documents.IndexedDocument
import dev.localintelligence.core.documents.Passage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One imported document, as listed on the Documents screen. */
@Serializable
data class DocumentEntry(
    val id: String,
    val title: String,
    val sizeChars: Int,
    val addedAt: Long,
)

/**
 * The documents the user added, kept as plain text in app storage.
 *
 * Layout under [root]: `index.json` holds the list of [DocumentEntry], and each
 * document's extracted text is `<id>.txt` beside it. Nothing else is kept. The
 * passages are not stored: they are cut from the text when the index is built, so
 * the only persisted copy of a document is the text the user already had.
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
 */
class DocumentStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = File(root, "index.json")
    private val lock = Any()

    @Volatile
    private var cached: DocumentIndex? = null

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
        val entry = DocumentEntry(
            id = id,
            title = title.trim().ifEmpty { "Document" }.take(MAX_TITLE_CHARS),
            sizeChars = text.length,
            addedAt = clock(),
        )
        File(root, "$id.txt").writeText(text, Charsets.UTF_8)
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
        cached = null
        true
    }

    /** The best [limit] passages for [query] across every document. */
    fun search(query: String, limit: Int): List<Passage> = index().search(query, limit)

    private fun index(): DocumentIndex {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: build().also { cached = it }
        }
    }

    private fun build(): DocumentIndex {
        val docs = readIndex().mapNotNull { entry ->
            val file = File(root, "${entry.id}.txt")
            if (!file.isFile) null
            else IndexedDocument(entry.id, entry.title, file.readText(Charsets.UTF_8))
        }
        return DocumentIndex.build(docs)
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

        private val ID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
