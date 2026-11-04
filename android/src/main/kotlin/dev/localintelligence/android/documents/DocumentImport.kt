package dev.localintelligence.android.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.localintelligence.android.io.readUpTo
import dev.localintelligence.core.documents.PageText
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

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

    private const val MIME_PDF = "application/pdf"
    private const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val MIME_EPUB = "application/epub+zip"

    /** The types the picker offers. Anything else is refused by name, not guessed at. */
    val PICKER_TYPES: Array<String> = arrayOf("text/plain", "text/markdown", "text/csv", MIME_PDF, MIME_DOCX, MIME_EPUB)

    /**
     * Largest input read, in bytes. A text file this size is a few hundred pages; a PDF
     * is held in memory while it is parsed, so its cap is lower.
     */
    private const val MAX_TEXT_BYTES: Long = 20L * 1024 * 1024
    private const val MAX_PDF_BYTES: Long = 50L * 1024 * 1024

    /**
     * DOCX and EPUB are zip archives, and the whole archive is held in memory while it is
     * read, so they share the PDF cap.
     */
    private const val MAX_ARCHIVE_BYTES: Long = MAX_PDF_BYTES

    /**
     * Zip bomb limits. A small archive can declare gigabytes of content, so every read counts
     * the bytes it inflates (skipped parts included) and stops at these caps. A book has a few
     * hundred parts; a Word file has a handful.
     */
    private const val MAX_ZIP_ENTRIES: Int = 2_000
    private const val MAX_ZIP_EXPANDED_BYTES: Long = 100L * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES: Long = 20L * 1024 * 1024

    /** The Word body. Headers, footers, footnotes and comments are other parts and are not read. */
    private const val DOCX_BODY = "word/document.xml"
    private const val WML_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private const val EPUB_CONTAINER = "META-INF/container.xml"
    private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"

    private val TEXT_EXTENSIONS = listOf(".txt", ".md", ".markdown", ".csv")

    @Volatile
    private var pdfReady = false

    /** A title and the extracted text, ready for [DocumentStore.add]. */
    class Extracted(val title: String, val text: String, val truncated: Boolean)

    private enum class Kind { PDF, DOCX, EPUB, TEXT }

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
        val kind = when {
            mime == MIME_PDF || lowerName.endsWith(".pdf") -> Kind.PDF
            mime == MIME_DOCX || lowerName.endsWith(".docx") -> Kind.DOCX
            mime == MIME_EPUB || lowerName.endsWith(".epub") -> Kind.EPUB
            mime?.startsWith("text/") == true || TEXT_EXTENSIONS.any { lowerName.endsWith(it) } -> Kind.TEXT
            else -> throw DocumentImportException("$title is not a PDF, Word, EPUB, text, Markdown or CSV file.")
        }

        val stream = resolver.openInputStream(uri)
            ?: throw DocumentImportException("Could not open $title. Pick it again.")
        val raw = stream.use { input ->
            when (kind) {
                Kind.PDF -> extractPdf(app, input, title)
                Kind.DOCX -> extractDocx(readArchive(input, title), title)
                Kind.EPUB -> extractEpub(readArchive(input, title), title)
                Kind.TEXT -> readText(input, title)
            }
        }
        val truncated = raw.length > DocumentStore.MAX_STORED_CHARS
        val text = if (truncated) raw.take(DocumentStore.MAX_STORED_CHARS) else raw
        if (text.isBlank()) {
            val why = if (kind == Kind.PDF) {
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

    /** The whole zip archive, capped. DOCX and EPUB are read from memory; nothing is written to disk. */
    private fun readArchive(input: InputStream, title: String): ByteArray {
        val bytes = input.readUpTo((MAX_ARCHIVE_BYTES + 1).toInt())
        if (bytes.size > MAX_ARCHIVE_BYTES) {
            throw DocumentImportException("$title is larger than 50 MB. Add a smaller file.")
        }
        return bytes
    }

    /**
     * The text of a .docx: the paragraphs of the body in order, and each table row on one line
     * with its cells joined by " | ". Only the w:t runs are read, so tracked deletions (w:delText)
     * and field codes (w:instrText) are not in the text.
     */
    private fun extractDocx(archive: ByteArray, title: String): String {
        var text: String? = null
        try {
            forEachZipEntry(archive, title, setOf(DOCX_BODY)) { _, bytes -> text = docxText(bytes) }
        } catch (e: IOException) {
            throw DocumentImportException("$title is not a valid Word document.")
        } catch (e: XmlPullParserException) {
            throw DocumentImportException("$title is not a valid Word document.")
        }
        return text ?: throw DocumentImportException("$title is not a valid Word document.")
    }

    private fun docxText(xml: ByteArray): String {
        val parser = xmlParser(xml)
        val out = ArrayList<String>()
        val paragraph = StringBuilder()
        // Open table cells and rows, innermost last. A table inside a cell nests: its lines go into the cell.
        val cells = ArrayList<StringBuilder>()
        val rows = ArrayList<MutableList<String>>()

        fun place(line: String) {
            if (cells.isEmpty()) {
                out.add(line)
            } else {
                val cell = cells[cells.size - 1]
                if (cell.isNotEmpty()) cell.append(' ')
                cell.append(line)
            }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (parser.namespace == WML_NS && event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "t" -> paragraph.append(parser.nextText())
                    "tab" -> paragraph.append('\t')
                    "br", "cr" -> paragraph.append(' ')
                    "tr" -> rows.add(ArrayList())
                    "tc" -> cells.add(StringBuilder())
                }
            } else if (parser.namespace == WML_NS && event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "p" -> {
                        val line = paragraph.toString().trim()
                        paragraph.setLength(0)
                        if (line.isNotEmpty()) place(line)
                    }
                    "tc" -> if (cells.isNotEmpty()) {
                        val cell = cells.removeAt(cells.size - 1).toString().trim()
                        rows.lastOrNull()?.add(cell)
                    }
                    "tr" -> if (rows.isNotEmpty()) {
                        val row = rows.removeAt(rows.size - 1)
                        if (row.any { it.isNotEmpty() }) place(row.joinToString(" | "))
                    }
                }
            }
            event = parser.next()
        }
        return out.joinToString("\n")
    }

    /**
     * The text of an .epub: the XHTML documents named by the OPF spine, in spine order, each
     * through [PageText.fullText] so the same page rules apply as to a web page.
     */
    private fun extractEpub(archive: ByteArray, title: String): String {
        val invalid = DocumentImportException("$title is not a valid EPUB book.")
        try {
            val packagePath = findPackagePath(archive, title) ?: throw invalid
            var opf: ByteArray? = null
            forEachZipEntry(archive, title, setOf(packagePath)) { _, bytes -> opf = bytes }
            val items = readingOrder(opf ?: throw invalid)
            val names = items.map { entryName(packagePath, it) }
            // An href is percent-encoded in the OPF but the zip entry name is not, so both spellings are looked for.
            val wanted = names.flatMap { listOf(it, Uri.decode(it)) }.toSet()
            val bodies = HashMap<String, String>()
            forEachZipEntry(archive, title, wanted) { name, bytes ->
                bodies[name] = PageText.fullText(String(bytes, Charsets.UTF_8))
            }
            if (names.isNotEmpty() && names.none { bodies.containsKey(it) || bodies.containsKey(Uri.decode(it)) }) {
                throw invalid
            }
            return names.mapNotNull { bodies[it] ?: bodies[Uri.decode(it)] }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        } catch (e: IOException) {
            throw invalid
        } catch (e: XmlPullParserException) {
            throw invalid
        }
    }

    /** The package document's path, from META-INF/container.xml. The OPF rootfile is preferred when there is more than one. */
    private fun findPackagePath(archive: ByteArray, title: String): String? {
        var container: ByteArray? = null
        forEachZipEntry(archive, title, setOf(EPUB_CONTAINER)) { _, bytes -> container = bytes }
        val parser = xmlParser(container ?: return null)
        var first: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) {
                    if (parser.getAttributeValue(null, "media-type") == OPF_MEDIA_TYPE) return path
                    if (first == null) first = path
                }
            }
            event = parser.next()
        }
        return first
    }

    /** The XHTML items of the spine, in spine order. Images, stylesheets and fonts are not in the spine. */
    private fun readingOrder(opf: ByteArray): List<String> {
        val parser = xmlParser(opf)
        val hrefs = HashMap<String, String>()
        val spine = ArrayList<String>()
        val mediaTypes = HashMap<String, String?>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) {
                            hrefs[id] = href
                            mediaTypes[id] = parser.getAttributeValue(null, "media-type")
                        }
                    }
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                }
            }
            event = parser.next()
        }
        return spine.mapNotNull { id ->
            val href = hrefs[id] ?: return@mapNotNull null
            val media = mediaTypes[id]?.lowercase()
            if (media == "application/xhtml+xml" || media == "text/html") href else null
        }
    }

    /**
     * The zip name of [href], which is relative to the directory of the package document, with
     * any fragment removed and "." and ".." resolved as a path would be.
     */
    private fun entryName(packagePath: String, href: String): String {
        val raw = href.substringBefore('#')
        val joined = if (packagePath.contains('/')) packagePath.substringBeforeLast('/') + "/" + raw else raw
        val parts = ArrayList<String>()
        for (segment in joined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    /**
     * Calls [visit] with the bytes of each zip entry named in [wanted], and no others. Every other
     * entry is read through and counted, so the caps hold whether or not an entry is used. Stops
     * as soon as every wanted entry has been seen. Throws [DocumentImportException] past a cap.
     */
    private fun forEachZipEntry(archive: ByteArray, title: String, wanted: Set<String>, visit: (String, ByteArray) -> Unit) {
        val seen = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            val buf = ByteArray(8 * 1024)
            var entries = 0
            var expanded = 0L
            while (seen.size < wanted.size) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ZIP_ENTRIES) {
                    throw DocumentImportException("$title has too many parts to read. Add a smaller file.")
                }
                val out = if (!entry.isDirectory && entry.name in wanted) ByteArrayOutputStream() else null
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    expanded += n
                    if (expanded > MAX_ZIP_EXPANDED_BYTES) {
                        throw DocumentImportException("$title unpacks to more than 100 MB. Add a smaller file.")
                    }
                    if (out != null) {
                        if (out.size() + n > MAX_ZIP_ENTRY_BYTES) {
                            throw DocumentImportException("$title has a part larger than 20 MB. Add a smaller file.")
                        }
                        out.write(buf, 0, n)
                    }
                }
                if (out != null) {
                    seen.add(entry.name)
                    visit(entry.name, out.toByteArray())
                }
            }
        }
    }

    /**
     * A pull parser with namespaces on, so a name is matched by its namespace and not by whatever
     * prefix the file chose. DTD processing is left off, so no external entity is fetched; the
     * platform default is off too, and the call that says so is allowed to fail on a parser that
     * does not offer the switch.
     */
    private fun xmlParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
        return parser
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
