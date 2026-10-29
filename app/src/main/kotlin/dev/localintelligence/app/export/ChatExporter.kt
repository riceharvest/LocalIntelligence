package dev.localintelligence.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import dev.localintelligence.app.ui.ChatMessage
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Turns the visible transcript into a file the user can keep or send.
 *
 * WHY THE EXPORT READS THE ROWS AND NOT THE TRACE: the transcript is what the
 * user read. The trace screen already exists for the developer view, and an
 * export that carried a different story than the screen it came from would be
 * worse than no export. So every format here is a rendering of the same
 * [ChatMessage] list the chat draws.
 *
 * WHY THE FILE GOES TO cacheDir: an export is a copy made for one share, not a
 * record the app keeps. The FileProvider path in `res/xml/export_paths.xml`
 * covers exactly this folder, so nothing outside the app can reach it except
 * through the grant the share intent carries.
 *
 * No new dependency: JSON is kotlinx.serialization, which `:core` already
 * exposes, and PDF is the platform's own `PdfDocument`.
 */
object ChatExporter {

    /** One entry per export format, so the menu, the file name and the MIME type cannot drift apart. */
    enum class Format(val label: String, val extension: String, val mimeType: String) {
        MARKDOWN("Export as Markdown", "md", "text/markdown"),
        JSON("Export as JSON", "json", "application/json"),
        PDF("Export as PDF", "pdf", "application/pdf"),
    }

    /** The authority the manifest's FileProvider declares, derived the same way the manifest does. */
    private fun authority(context: Context): String = "${context.packageName}.exports"

    /**
     * Writes the transcript in [format] to `cacheDir/exports/` and returns the file.
     *
     * Blocking file IO. Call it off the main thread.
     */
    fun write(context: Context, messages: List<ChatMessage>, format: Format): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "chat-$stamp.${format.extension}")
        when (format) {
            Format.MARKDOWN -> file.writeText(toMarkdown(messages))
            Format.JSON -> file.writeText(toJson(messages))
            Format.PDF -> FileOutputStream(file).use { out -> toPdf(messages, out) }
        }
        return file
    }

    /**
     * Opens the system share sheet with [file] attached.
     *
     * The URI is granted read-only through the FileProvider. ClipData is set as
     * well as EXTRA_STREAM because a chooser only forwards the grant to the
     * chosen app when the clip data carries the URI.
     */
    fun share(context: Context, file: File, format: Format) {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    // ------------------------------------------------------------- rows

    /**
     * The transcript as plain records, shared by the three formats.
     *
     * [tool] is set only on tool and approval rows. [text] for a tool row is the
     * phone's answer when there is one, else the runtime's own sentence, the same
     * choice the chat row makes for its collapsed view.
     */
    private data class Row(val role: String, val text: String, val tool: String?, val success: Boolean?)

    private fun rows(messages: List<ChatMessage>): List<Row> = messages.map { message ->
        when (message) {
            is ChatMessage.User -> Row("user", message.text, null, null)
            is ChatMessage.Assistant -> Row("assistant", message.text, null, null)
            is ChatMessage.Notice -> Row("notice", message.text, null, null)
            is ChatMessage.ToolStep -> Row(
                role = "tool",
                text = message.observation ?: message.detail,
                tool = message.toolName,
                success = message.success,
            )
            is ChatMessage.Approval -> Row(
                role = "approval",
                text = if (message.approved) "Approved" else "Declined",
                tool = message.toolName,
                success = null,
            )
        }
    }

    // ------------------------------------------------------------ markdown

    /**
     * `## You` and `## Assistant` headings, with tool calls as one quoted line each.
     *
     * Notices and approvals are short lines too, so the record of what the user
     * decided stays in the file. A failed tool call is marked, since the line
     * otherwise reads like a success.
     */
    fun toMarkdown(messages: List<ChatMessage>): String {
        val out = StringBuilder()
        out.append("# Chat export\n\n")
        for (row in rows(messages)) {
            when (row.role) {
                "user" -> out.append("## You\n\n").append(row.text).append("\n\n")
                "assistant" -> out.append("## Assistant\n\n").append(row.text).append("\n\n")
                "tool" -> {
                    val failed = if (row.success == false) " (failed)" else ""
                    out.append("> used ").append(row.tool.orEmpty()).append(failed).append("\n\n")
                }
                "approval" -> out.append("> ")
                    .append(row.text.lowercase())
                    .append(" ")
                    .append(row.tool.orEmpty())
                    .append("\n\n")
                else -> out.append("_").append(row.text).append("_\n\n")
            }
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- json

    /** One exported row. [tool] is omitted for rows that have none. The transcript keeps no timestamps, so there is no timestamp field. */
    @Serializable
    private data class JsonRow(
        val role: String,
        val text: String,
        val tool: String? = null,
    )

    private val json = Json {
        prettyPrint = true
        // A null [JsonRow.tool] is left out, not written as `"tool": null`.
        explicitNulls = false
    }

    private val rowListSerializer: KSerializer<List<JsonRow>> = ListSerializer(JsonRow.serializer())

    /** An array of `{role, text, tool?}`. */
    fun toJson(messages: List<ChatMessage>): String {
        val list = rows(messages).map { JsonRow(role = it.role, text = it.text, tool = it.tool) }
        return json.encodeToString(rowListSerializer, list)
    }

    // ----------------------------------------------------------------- pdf

    private const val PAGE_WIDTH = 595  // A4 in points, 72 per inch
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val BODY_SIZE = 11f
    private const val LABEL_SIZE = 9f
    private const val GAP = 12f
    private const val LABEL_GAP = 4f

    /**
     * Lays the transcript out on A4 pages and writes the document to [out].
     *
     * Each block is wrapped by a [StaticLayout] and then placed line by line, so
     * a block that does not fit on the current page continues on the next one
     * rather than being pushed whole, and nothing is cut in the middle of a line.
     */
    fun toPdf(messages: List<ChatMessage>, out: java.io.OutputStream) {
        val document = PdfDocument()
        try {
            val writer = PdfWriter(document)
            for (row in rows(messages)) {
                when (row.role) {
                    "user" -> writer.block("You", row.text)
                    "assistant" -> writer.block("Assistant", row.text)
                    "tool" -> {
                        val failed = if (row.success == false) " (failed)" else ""
                        writer.line("used ${row.tool.orEmpty()}$failed")
                    }
                    "approval" -> writer.line("${row.text.lowercase()} ${row.tool.orEmpty()}")
                    else -> writer.line(row.text)
                }
            }
            writer.finish()
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    /** Places wrapped text on pages. Holds the current page and the vertical cursor. */
    private class PdfWriter(private val document: PdfDocument) {
        private val contentWidth = PAGE_WIDTH - 2 * MARGIN
        private val bottom = PAGE_HEIGHT - MARGIN

        private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BODY_SIZE
            color = 0xFF000000.toInt()
        }
        private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_SIZE
            isFakeBoldText = true
            color = 0xFF555555.toInt()
        }
        private val italicPaint = TextPaint(bodyPaint).apply {
            textSize = BODY_SIZE
            isFakeBoldText = false
            textSkewX = -0.25f
            color = 0xFF333333.toInt()
        }

        private var pageNumber = 0
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        private var y = MARGIN

        private fun ensurePage() {
            if (page != null) return
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
            val started = document.startPage(info)
            page = started
            canvas = started.canvas
            y = MARGIN
        }

        private fun newPage() {
            page?.let { document.finishPage(it) }
            page = null
            canvas = null
            ensurePage()
        }

        /** A speaker label, then the wrapped body. */
        fun block(label: String, text: String) {
            if (text.isBlank()) return
            ensurePage()
            placeLayout(layoutFor(label, labelPaint))
            y += LABEL_GAP
            wrap(text, bodyPaint)
            y += GAP
        }

        /** One short, italic line such as a tool call. */
        fun line(text: String) {
            ensurePage()
            wrap(text, italicPaint)
            y += GAP / 2
        }

        private fun wrap(text: String, paint: TextPaint) {
            placeLayout(layoutFor(text, paint))
            y += GAP / 2
        }

        private fun layoutFor(text: String, paint: TextPaint): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()

        /**
         * Draws [layout] one line at a time, starting a new page whenever the next
         * line would cross the bottom margin.
         *
         * Each line is drawn by translating the whole layout so that line sits at
         * the cursor, then clipping to that line's band. That keeps StaticLayout's
         * own wrapping and spacing without splitting its internals.
         */
        private fun placeLayout(layout: StaticLayout) {
            for (index in 0 until layout.lineCount) {
                val top = layout.getLineTop(index).toFloat()
                val lineBottom = layout.getLineBottom(index).toFloat()
                val height = lineBottom - top
                if (y + height > bottom) newPage()
                val c = canvas ?: return
                c.save()
                c.translate(MARGIN, y - top)
                c.clipRect(0f, top, contentWidth, lineBottom)
                layout.draw(c)
                c.restore()
                y += height
            }
        }

        fun finish() {
            // A document with no pages is not a valid PDF to every reader, so an
            // empty transcript still gets one blank page.
            ensurePage()
            page?.let { document.finishPage(it) }
            page = null
            canvas = null
        }
    }
}
