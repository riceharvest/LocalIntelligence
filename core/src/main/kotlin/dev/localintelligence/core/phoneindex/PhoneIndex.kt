package dev.localintelligence.core.phoneindex

import dev.localintelligence.core.documents.DocumentIndex
import dev.localintelligence.core.documents.IndexedDocument

/**
 * The kinds of phone data "Search my phone" can index. Every source is off until the person
 * switches it on in Settings, and each one has its own Delete.
 *
 * [playAttendedOnly] marks the sources the Google Play build keeps for attended use: texts, like
 * `sms.search`, are never indexed in the background there and never searched by an unattended run.
 * See docs/adr/0002-play-and-full-builds.md and the Play rule in `AppContainer`.
 */
enum class PhoneIndexSource(val id: String, val title: String, val playAttendedOnly: Boolean) {
    NOTIFICATIONS("notifications", "Notifications", playAttendedOnly = false),
    MESSAGES("messages", "Texts", playAttendedOnly = true),
    CALENDAR("calendar", "Calendar", playAttendedOnly = false),
    CONTACTS("contacts", "Contact names", playAttendedOnly = false),
    FILES("files", "Files in a folder", playAttendedOnly = false),
    PHOTOS("photos", "Photo captions", playAttendedOnly = false);

    companion object {
        fun byId(id: String): PhoneIndexSource? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One item to search. [key] identifies it within its source, so indexing the same item twice
 * replaces it rather than adding a copy. [whenMillis] is the date the item is about (a text's
 * arrival, an event's start, a file's modification time), shown to the model.
 */
data class PhoneIndexRecord(
    val source: PhoneIndexSource,
    val key: String,
    val title: String,
    val text: String,
    val whenMillis: Long,
)

/** One answer: the item it came from and the passage that matched. */
data class PhoneHit(
    val source: PhoneIndexSource,
    val key: String,
    val title: String,
    val passage: String,
    val whenMillis: Long,
)

/**
 * Keyword search over the records of the switched-on sources. Pure and deterministic, with no
 * I/O: the same BM25 index Documents and Photo search use, built over this call's records.
 *
 * Lexical only. The embedding model is not used here, so a question worded differently from the
 * item finds nothing. That limit is stated to the model in the tool's observation.
 */
object PhoneIndexSearch {

    /** Items kept per source. The oldest are dropped first, so the index stays a few thousand rows. */
    const val PER_SOURCE_LIMIT: Int = 2000

    /** Characters kept per item. A long message or file is searched by its first part only. */
    const val MAX_TEXT_CHARS: Int = 1000

    /** The best [limit] items for [query], best first. At most one passage per item. */
    fun search(records: List<PhoneIndexRecord>, query: String, limit: Int): List<PhoneHit> {
        if (records.isEmpty() || limit <= 0 || query.isBlank()) return emptyList()
        val docs = records.mapIndexed { i, r -> IndexedDocument(i.toString(), r.title, r.text) }
        return DocumentIndex.build(docs)
            .search(query, limit * 3)
            .distinctBy { it.docId }
            .take(limit)
            .map { p ->
                val r = records[p.docId.toInt()]
                PhoneHit(r.source, r.key, r.title, p.text, r.whenMillis)
            }
    }

    /** Collapses whitespace and caps the length, so a stored item is one searchable line of text. */
    fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS)
}
