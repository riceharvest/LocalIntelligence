package dev.localintelligence.android.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.localintelligence.android.io.readUpTo
import java.io.InputStream

/** A failure the Documents screen shows as one sentence. */
class DocumentImportException(message: String) : Exception(message)

/**
 * Turns a document the user picked into plain text.
 *
 * The picker grants read access to the one file the user chose, for as long as
 * this code runs. The text is copied out here and the URI is not kept, so a
 * document removed from the phone later is still answerable from the copy.
 */
object DocumentImport {

    /** The types the picker offers. Anything else is refused by name, not guessed at. */
    val PICKER_TYPES: Array<String> = arrayOf("text/plain", "text/markdown", "text/csv", "application/pdf")

    /**
     * Largest input read, in bytes. A text file this size is a few hundred pages; a PDF
     * is held in memory while it is parsed, so its cap is lower.
     */
    private const val MAX_TEXT_BYTES: Long = 20L * 1024 * 1024
    private const val MAX_PDF_BYTES: Long = 50L * 1024 * 1024

    private val TEXT_EXTENSIONS = listOf(".txt", ".md", ".markdown", ".csv")

    @Volatile
    private var pdfReady = false

    /** A title and the extracted text, ready for [DocumentStore.add]. */
    class Extracted(val title: String, val text: String, val truncated: Boolean)

    /**
     * Reads [uri] and returns its text. Blocking: call from an IO dispatcher. Throws
     * [DocumentImportException] with the reason for a file that cannot be used.
     */
    fun extract(context: Context, uri: Uri): Extracted {
        val app = context.applicationContext
        val resolver = app.contentResolver
        val title = displayName(app, uri)
        val mime = resolver.getType(uri)?.lowercase()
        val lowerName = title.lowercase()
        val isPdf = mime == "application/pdf" || lowerName.endsWith(".pdf")
        val isText = mime?.startsWith("text/") == true || TEXT_EXTENSIONS.any { lowerName.endsWith(it) }
        if (!isPdf && !isText) {
            throw DocumentImportException("$title is not a PDF, text, Markdown or CSV file.")
        }

        val stream = resolver.openInputStream(uri)
            ?: throw DocumentImportException("Could not open $title. Pick it again.")
        val raw = stream.use { input ->
            if (isPdf) extractPdf(app, input, title)
            else readText(input, title)
        }
        val truncated = raw.length > DocumentStore.MAX_STORED_CHARS
        val text = if (truncated) raw.take(DocumentStore.MAX_STORED_CHARS) else raw
        if (text.isBlank()) {
            val why = if (isPdf) {
                "$title has no text the app can read. A scanned PDF has no text layer."
            } else {
                "$title is empty."
            }
            throw DocumentImportException(why)
        }
        return Extracted(title = title.substringBeforeLast('.', title), text = text, truncated = truncated)
    }

    private fun readText(input: InputStream, title: String): String {
        val bytes = input.readUpTo((MAX_TEXT_BYTES + 1).toInt())
        if (bytes.size > MAX_TEXT_BYTES) {
            throw DocumentImportException("$title is larger than 20 MB. Split it and add the parts.")
        }
        // A UTF-8 byte-order mark is not text; it would otherwise show as a stray character in every excerpt.
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    private fun extractPdf(context: Context, input: InputStream, title: String): String {
        ensurePdfBox(context)
        val bytes = input.readUpTo((MAX_PDF_BYTES + 1).toInt())
        if (bytes.size > MAX_PDF_BYTES) {
            throw DocumentImportException("$title is larger than 50 MB. Add a smaller file.")
        }
        return try {
            PDDocument.load(bytes).use { doc -> PDFTextStripper().getText(doc) }
        } catch (e: InvalidPasswordException) {
            throw DocumentImportException("$title is password-protected. Save an unlocked copy and add that.")
        }
    }

    /** PDFBox needs its font and CMap resources registered once per process before the first parse. */
    @Synchronized
    private fun ensurePdfBox(context: Context) {
        if (pdfReady) return
        PDFBoxResourceLoader.init(context.applicationContext)
        pdfReady = true
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return (fromProvider ?: uri.lastPathSegment ?: "Document").trim().ifEmpty { "Document" }
    }
}
