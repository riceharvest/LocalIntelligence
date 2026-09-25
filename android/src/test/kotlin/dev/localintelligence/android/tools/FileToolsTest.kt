package dev.localintelligence.android.tools

import dev.localintelligence.android.tools.apps.AppShareTarget
import dev.localintelligence.android.tools.files.DeleteDecision
import dev.localintelligence.android.tools.files.DeleteGuard
import dev.localintelligence.android.tools.files.FileArgs
import dev.localintelligence.android.tools.files.FileListing
import dev.localintelligence.android.tools.files.FileQuery
import dev.localintelligence.android.tools.files.FileRow
import dev.localintelligence.android.tools.files.FileSelection
import dev.localintelligence.android.tools.files.FileSize
import dev.localintelligence.android.tools.files.FileText
import dev.localintelligence.android.tools.files.MimeTypes
import dev.localintelligence.android.tools.files.WritePlan
import dev.localintelligence.android.tools.files.WritePlanner
import dev.localintelligence.core.tool.ObservationTruncator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * JVM-only tests for the file tools' pure logic.
 *
 * There is no device and Robolectric is not on the classpath, so
 * `ContentResolver`, `PackageManager` and `Uri` cannot execute here. Everything that
 * can be decided without a platform object is therefore decided in a helper object
 * and proven below: argument coercion, MediaStore selection building, the read cap
 * and its truncation notice, the search truncation wording, the delete pre-flight,
 * and the size/date/mime formatting.
 *
 * What this file does NOT prove, and says so plainly: that
 * `ContentResolver.query` on a real device returns rows, that a SAF write succeeds,
 * or that a cursor is closed. Those need instrumentation. The selection SQL, the
 * argument binding and the cap arithmetic — the parts that are actually wrong-able
 * without a device — are all here.
 */
class FileToolsTest {

    // ===================================================================== coercion

    @Test
    fun `a limit given as the string 8 is read as the integer 8`() {
        // The single most common model mistake. "8" must be 8, not a parse failure.
        assertEquals(8, FileArgs.limit(JsonPrimitive("8")))
    }

    @Test
    fun `a limit given as a JSON number is used as-is`() {
        assertEquals(1, FileArgs.limit(JsonPrimitive(1)))
        assertEquals(42, FileArgs.limit(JsonPrimitive(42)))
    }

    @Test
    fun `a limit is clamped at both ends`() {
        assertEquals(FileArgs.MAX_LIMIT, FileArgs.limit(JsonPrimitive(100_000)))
        assertEquals(FileArgs.MAX_LIMIT, FileArgs.limit(JsonPrimitive(Int.MAX_VALUE)))
        assertEquals(FileArgs.DEFAULT_LIMIT, FileArgs.limit(JsonPrimitive(0)))
        assertEquals(FileArgs.DEFAULT_LIMIT, FileArgs.limit(JsonPrimitive(-7)))
    }

    @Test
    fun `an unusable limit falls back to the default rather than failing`() {
        // A model that emits "many" for a limit is confused, not broken. Returning
        // the default keeps the tool useful; returning an error teaches it nothing.
        for (junk in listOf<JsonElement?>(null, JsonPrimitive("many"), JsonPrimitive(""),
            JsonPrimitive("eight"), JsonPrimitive("8.5.1"), JsonPrimitive(true))) {
            assertEquals("expected default for $junk", FileArgs.DEFAULT_LIMIT, FileArgs.limit(junk))
        }
    }

    @Test
    fun `an absent limit uses the default`() {
        assertEquals(FileArgs.DEFAULT_LIMIT, FileArgs.limit(null))
        assertEquals(FileArgs.DEFAULT_LIMIT, FileArgs.limit(JsonNull))
    }

    @Test
    fun `a float limit is truncated toward zero rather than rejected`() {
        assertEquals(8, FileArgs.limit(JsonPrimitive(8.9)))
    }

    @Test
    fun `a string argument is trimmed and blank becomes absent`() {
        assertEquals("invoice", FileArgs.optionalString(JsonPrimitive("  invoice  ")))
        assertNull(FileArgs.optionalString(JsonPrimitive("   ")))
        assertNull(FileArgs.optionalString(JsonPrimitive("")))
        assertNull(FileArgs.optionalString(JsonNull))
        assertNull(FileArgs.optionalString(null))
    }

    @Test
    fun `a missing required string is absent, not the literal null`() {
        // JsonNull is a JsonPrimitive whose content is "null". Missing it explicitly is
        // what stops a model asking for a file named "null" from finding one.
        assertNull(FileArgs.requiredString(null))
        assertNull(FileArgs.requiredString(JsonNull))
        assertNull(FileArgs.stringOrNull(JsonNull))
    }

    @Test
    fun `an object or array where a string belongs is rejected, not stringified`() {
        val obj = buildJsonArray { } // a JsonArray, used as a wrong-typed value
        assertNull(FileArgs.stringOrNull(obj))
        assertNull(FileArgs.stringOrNull(JsonObject(emptyMap())))
    }

    @Test
    fun `a number supplied where a string belongs is coerced to its text`() {
        // Models do this constantly. Coercing is better than failing: the value is
        // still a usable string, and the schema is not a security boundary.
        assertEquals("8", FileArgs.stringOrNull(JsonPrimitive(8)))
    }

    @Test
    fun `a long string argument is truncated to its cap`() {
        val long = "x".repeat(FileArgs.MAX_QUERY_CHARS + 500)
        assertEquals(FileArgs.MAX_QUERY_CHARS, FileArgs.optionalString(JsonPrimitive(long))!!.length)
    }

    @Test
    fun `a boolean argument accepts the forms a model actually emits`() {
        assertTrue(FileArgs.boolean(JsonPrimitive(true), false))
        assertTrue(FileArgs.boolean(JsonPrimitive("true"), false))
        assertTrue(FileArgs.boolean(JsonPrimitive("YES"), false))
        // JSON has no int literal type; a model meaning true often emits 1.
        assertTrue(FileArgs.boolean(JsonPrimitive(1), false))
        assertFalse(FileArgs.boolean(JsonPrimitive(false), true))
        assertFalse(FileArgs.boolean(JsonPrimitive("0"), true))
        assertFalse(FileArgs.boolean(JsonPrimitive("no"), true))
    }

    @Test
    fun `an unparseable boolean takes the documented default`() {
        assertTrue(FileArgs.boolean(JsonPrimitive("maybe"), true))
        assertFalse(FileArgs.boolean(JsonPrimitive("maybe"), false))
        assertTrue(FileArgs.boolean(null, true))
        assertFalse(FileArgs.boolean(null, false))
    }

    @Test
    fun `an epoch bound accepts a number or a numeric string and rejects nonsense`() {
        assertEquals(1_700_000_000_000L, FileArgs.epochMillis(JsonPrimitive(1_700_000_000_000L)))
        assertEquals(42L, FileArgs.epochMillis(JsonPrimitive("42")))
        assertNull(FileArgs.epochMillis(JsonPrimitive("yesterday")))
        assertNull(FileArgs.epochMillis(null))
    }

    @Test
    fun `a negative epoch bound is dropped rather than clamped`() {
        // "Modified since 1969" is not a request. Clamping to 0 would silently turn
        // it into "every file ever", which is the opposite of what was asked.
        assertNull(FileArgs.epochMillis(JsonPrimitive(-1L)))
    }

    // ============================================================== selection / SAF

    @Test
    fun `only content URIs are accepted as document identifiers`() {
        assertTrue(FileArgs.isContentUri("content://media/external/downloads/12"))
        assertFalse(FileArgs.isContentUri("file:///storage/emulated/0/Documents/a.pdf"))
        assertFalse(FileArgs.isContentUri("/storage/emulated/0/Documents/a.pdf"))
        assertFalse(FileArgs.isContentUri("content://"))
        assertFalse(FileArgs.isContentUri(""))
        assertFalse(FileArgs.isContentUri(null))
    }

    @Test
    fun `contentUri refuses a file path that a model built by hand`() {
        assertNull(FileArgs.contentUri(JsonPrimitive("file:///data/a.txt")))
        assertEquals("content://x/1", FileArgs.contentUri(JsonPrimitive("content://x/1")))
        assertNull(FileArgs.contentUri(JsonPrimitive("/sdcard/x.txt")))
    }

    @Test
    fun `an unfiltered selection pins media_type to a document and binds it`() {
        val selection = FileQuery.buildSelection()

        // The media_type filter is a bound argument, never inlined text.
        assertEquals(1, selection.args.size)
        assertEquals(FileQuery.MEDIA_TYPE_DOCUMENT, selection.args[0])
        assertTrue(selection.selection.contains("media_type = ?"))
    }

    @Test
    fun `every caller value is bound and never appears in the selection text`() {
        val hostile = "'; DROP TABLE files; --"
        val selection = FileQuery.buildSelection(query = hostile, mime = "application/pdf", fromMillis = 1000L)

        // The load-bearing property: user text lives in args, so it cannot become SQL.
        assertFalse(selection.selection.contains(hostile))
        assertFalse(selection.selection.contains("DROP"))
        // Four clauses: media_type, display_name LIKE, mime_type, date_modified >=.
        assertEquals(4, selection.args.size)
        assertTrue(selection.args.contains("%'; DROP TABLE files; --%"))
        assertEquals("application/pdf", selection.args[2])
        assertEquals("1", selection.args[3])
        // One placeholder per clause, and never inside a literal.
        assertEquals(4, selection.selection.count { it == '?' })
    }

    @Test
    fun `a LIKE metacharacter in a query is escaped so it matches literally`() {
        // "100%" must not match every file, and "report_2026" must not match
        // "reportX2026".
        assertEquals("%100\\%%", FileQuery.likePattern("100%"))
        assertEquals("%report\\_2026%", FileQuery.likePattern("report_2026"))
        // A backslash in the query is itself escaped, or the ESCAPE clause breaks.
        assertEquals("%a\\\\b%", FileQuery.likePattern("a\\b"))
    }

    @Test
    fun `an escaped LIKE pattern is usable as a bound argument`() {
        val selection = FileQuery.buildSelection(query = "50%")
        assertEquals("%50\\%%", selection.args[1])
        assertTrue(selection.selection.contains("ESCAPE"))
    }

    @Test
    fun `the date window is converted from millis to the seconds MediaStore stores`() {
        // An off-by-1000 here returns "modified in 1970" and is invisible in review.
        assertEquals(1_700_000_000L, FileQuery.modifiedSecondsFrom(1_700_000_000_999L))
        assertEquals(0L, FileQuery.modifiedSecondsFrom(0L))
        assertEquals(0L, FileQuery.modifiedSecondsFrom(-1L))
    }

    @Test
    fun `a date window becomes two independent bounds`() {
        val selection = FileQuery.buildSelection(fromMillis = 2_000L, toMillis = 3_000L)
        assertEquals(listOf(FileQuery.MEDIA_TYPE_DOCUMENT, "2", "3"), selection.args)
        assertTrue(selection.selection.contains("date_modified >= ?"))
        assertTrue(selection.selection.contains("date_modified < ?"))
    }

    @Test
    fun `an empty selection is recognisable so the caller can skip the bind`() {
        assertFalse(FileQuery.buildSelection().isEmpty)
        assertTrue(FileSelection("", emptyList()).isEmpty)
    }

    @Test
    fun `a blank query and a blank mime contribute no clauses`() {
        val selection = FileQuery.buildSelection(query = "  ", mime = "")
        assertEquals(1, selection.args.size)
        assertFalse(selection.selection.contains("LIKE"))
    }

    @Test
    fun `the projection does not request a column that is absent on old API levels`() {
        // RELATIVE_PATH was added in API 29; some providers return an empty projection
        // rather than an error, which silently yields zero rows.
        val modern = FileQuery.projection(34)
        val legacy = FileQuery.projection(26)
        assertTrue(modern.contains("relative_path"))
        assertFalse(legacy.contains("relative_path"))
        assertTrue(legacy.contains("_id"))
        assertTrue(legacy.contains("_display_name"))
        assertTrue(modern.contains("_display_name"))
    }

    // ===================================================================== read cap

    @Test
    fun `a one megabyte file yields at most the eight kilobyte read cap`() {
        val megabyte = "A".repeat(1_000_000)
        val decoded = FileText.readCapped(ByteArrayInputStream(megabyte.toByteArray()))

        assertTrue(
            "read ${decoded.text.length} chars, cap is ${FileText.READ_CAP_BYTES}",
            decoded.text.length <= FileText.READ_CAP_BYTES,
        )
        assertEquals(FileText.READ_CAP_BYTES, decoded.text.length)
        assertTrue(decoded.truncated)
    }

    @Test
    fun `a file at or under the cap is not reported as truncated`() {
        val small = "hello world".toByteArray()
        val decoded = FileText.readCapped(ByteArrayInputStream(small))

        assertFalse(decoded.truncated)
        assertEquals("hello world", decoded.text)
        assertFalse(decoded.droppedPartialChar)
    }

    @Test
    fun `a file exactly at the cap is not truncated but one byte more is`() {
        val exact = "B".repeat(FileText.READ_CAP_BYTES).toByteArray()
        assertFalse(FileText.readCapped(ByteArrayInputStream(exact)).truncated)

        val over = "B".repeat(FileText.READ_CAP_BYTES + 1).toByteArray()
        assertTrue(FileText.readCapped(ByteArrayInputStream(over)).truncated)
    }

    @Test
    fun `a multi-byte character cut at the boundary is dropped and reported`() {
        // "€" is three bytes. Ending the cap mid-character must not leave a
        // replacement character standing in for a byte that was never read.
        val prefix = "A".repeat(FileText.READ_CAP_BYTES - 1)
        val decoded = FileText.readCapped(
            ByteArrayInputStream((prefix + "€€").toByteArray()),
        )

        assertTrue(decoded.truncated)
        assertTrue(decoded.droppedPartialChar)
        assertFalse("no replacement char may survive", decoded.text.contains('�'))
        assertEquals(prefix, decoded.text)
    }

    @Test
    fun `the observation for a huge file is under budget and states the truncation`() {
        val decoded = FileText.readCapped(ByteArrayInputStream("C".repeat(1_000_000).toByteArray()))
        val observation = FileText.contentObservation("huge.log", 1_000_000L, decoded)

        assertTrue(
            "observation was ${observation.length} chars",
            observation.length < ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertTrue(observation.contains(FileText.TRUNCATION_MARKER))
        assertTrue(observation.contains("truncated"))
        // The model must learn HOW MUCH was withheld, not just that it happened.
        assertTrue(observation.contains("${FileText.READ_CAP_BYTES}"))
        // 1_000_000 B is 976.5625 KB, which the formatter rounds to 977 KB.
        assertTrue(observation.contains("977 KB"))
    }

    @Test
    fun `the read cap survives the observation truncator doing nothing`() {
        // Guards the ordering bug: if the observation were NOT pre-sized, the
        // truncator would cut it and the tool's own notice would never be seen.
        val decoded = FileText.readCapped(ByteArrayInputStream("D".repeat(1_000_000).toByteArray()))
        val observation = FileText.contentObservation("huge.log", null, decoded)
        val after = ObservationTruncator.truncate(observation)

        assertEquals(observation, after)
    }

    @Test
    fun `a short file produces an observation with no truncation notice`() {
        val decoded = FileText.readCapped(ByteArrayInputStream("short note".toByteArray()))
        val observation = FileText.contentObservation("note.txt", 10L, decoded)

        assertFalse(observation.contains(FileText.TRUNCATION_MARKER))
        assertTrue(observation.contains("short note"))
        assertTrue(observation.contains("note.txt"))
    }

    @Test
    fun `an empty file yields an empty read and still a usable observation`() {
        val decoded = FileText.readCapped(ByteArrayInputStream(ByteArray(0)))
        val observation = FileText.contentObservation("empty.txt", 0L, decoded)

        assertEquals("", decoded.text)
        assertFalse(decoded.truncated)
        assertTrue(observation.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
    }

    // ==================================================================== formatting

    @Test
    fun `file sizes are human readable and bounded`() {
        assertEquals("0 B", FileSize.format(0L))
        assertEquals("512 B", FileSize.format(512L))
        assertEquals("1.0 KB", FileSize.format(1024L))
        assertEquals("1.0 MB", FileSize.format(1024L * 1024L))
        assertEquals("1.5 KB", FileSize.format(1536L))
        assertEquals("unknown", FileSize.format(-1L))
    }

    @Test
    fun `an enormous size does not produce an enormous string`() {
        // Long.MAX_VALUE is 19 digits; the formatter must not echo all of them.
        val formatted = FileSize.format(Long.MAX_VALUE)
        assertTrue("got '$formatted'", formatted.length <= 10)
    }

    @Test
    fun `an unknown date is never rendered as 1970`() {
        assertEquals("unknown", FileListing.formatDate(0L))
        assertEquals("unknown", FileListing.formatDate(-1L))
        val real = FileListing.formatDate(1_700_000_000_000L)
        assertTrue("got '$real'", real.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `a mime type becomes a short readable label`() {
        assertEquals("PDF", MimeTypes.friendly("application/pdf"))
        assertEquals("PNG", MimeTypes.friendly("image/png"))
        assertEquals("unknown", MimeTypes.friendly(null))
        assertEquals("unknown", MimeTypes.friendly(""))
        assertEquals("unknown", MimeTypes.friendly("unknown"))
        // An enormous vendor subtype must not eat the observation.
        assertTrue(MimeTypes.friendly("application/" + "x".repeat(500)).length <= 24)
    }

    @Test
    fun `an extension is extracted only when one plausibly exists`() {
        assertEquals("pdf", MimeTypes.extensionOf("invoice.pdf"))
        assertEquals("TXT", MimeTypes.extensionOf("NOTE.TXT")?.uppercase())
        assertNull(MimeTypes.extensionOf("noextension"))
        assertNull(MimeTypes.extensionOf("trailing."))
        assertNull(MimeTypes.extensionOf(null))
        // A path separator in the "extension" means it is a directory, not a suffix.
        assertNull(MimeTypes.extensionOf("weird.a/b"))
    }

    @Test
    fun `text detection trusts either the mime type or the extension`() {
        // Providers routinely report octet-stream for a .txt on an SD card.
        assertTrue(MimeTypes.isTextLike("application/octet-stream", "notes.txt"))
        // And sometimes report text/plain for something that is not.
        assertTrue(MimeTypes.isTextLike("text/html", "page.html"))
        assertFalse(MimeTypes.isTextLike("image/png", "photo.png"))
        assertFalse(MimeTypes.isTextLike("application/pdf", "doc.pdf"))
        assertFalse(MimeTypes.isTextLike(null, null))
    }

    // ========================================================== list / search output

    @Test
    fun `a truncated result set says so with a showing N of M notice`() {
        val rows = (1..3).map { row(it.toLong()) }
        val text = FileListing.format(rows, total = 57, withheld = 54, what = "documents")

        assertTrue("got: $text", text.contains("Showing 3 of 57 documents"))
        assertTrue(text.contains("54 more matched"))
        assertTrue(text.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
    }
    @Test
    fun `a complete result set does not claim anything was withheld`() {
        val text = FileListing.format(listOf(row(1L)), total = 1, withheld = 0, what = "documents")
        assertFalse(text.contains("Showing"))
        assertFalse(text.contains("more matched"))
    }

    @Test
    fun `an empty result is stated in words, not as an empty list`() {
        val text = FileListing.format(emptyList(), total = 0, withheld = 0, what = "documents")

        assertTrue(text.startsWith("No documents matched"))
        // The model must be told what to do next, or it retries identically.
        assertTrue(text.contains("Widen the query"))
    }

    @Test
    fun `a listing is capped and always states how many rows were hidden`() {
        val rows = (1..60).map { row(it.toLong()) }
        val text = FileListing.format(rows, total = 60, withheld = 40, what = "documents")

        val rowLines = text.lines().count { it.startsWith("- ") && !it.startsWith("- …") }
        assertTrue("rendered $rowLines rows", rowLines <= FileListing.MAX_ROWS)
        assertTrue(text.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
        // 60 matched, 20 shown, so 40 hidden — and the tool has to say so rather than
        // letting the model believe it saw everything.
        assertTrue(text.contains("not listed"))
    }

    @Test
    fun `a listing of long names still fits the budget`() {
        // 40 rows x 160-char names is over 7 KB. The character budget, not the row
        // count, is the real bound, and the withheld tally must still be correct.
        val rows = (1..60).map { row(it.toLong(), name = "n".repeat(160) + it) }
        val text = FileListing.format(rows, total = 60, withheld = 0, what = "documents")

        assertTrue("observation was ${text.length} chars", text.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
        assertTrue(text.contains("not listed"))
        assertEquals(ObservationTruncator.truncate(text), text)
    }

    @Test
    fun `one row renders as a single line with type size and date`() {
        val text = FileListing.describe(row(1L))
        assertTrue(text.contains("invoice.pdf"))
        assertTrue(text.contains("PDF"))
        assertTrue(text.contains("12.0 KB"))
        assertTrue(text.contains("modified"))
    }

    @Test
    fun `a hostile filename cannot break out of a line or the observation`() {
        val hostile = row(1L).copy(displayName = "a\nb\r\nc".repeat(30).take(FileArgs.MAX_NAME_CHARS))
        val text = FileListing.format(listOf(hostile), total = 1, withheld = 0, what = "documents")

        // A newline in a display name would split one row into two lines and could
        // make the model believe there is a second document.
        assertEquals(1, text.lines().count { it.startsWith("- ") })
        assertTrue(text.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
    }

    @Test
    fun `a file with no name still renders as one row`() {
        val text = FileListing.describe(row(1L).copy(displayName = ""))
        assertTrue(text.contains("unnamed document"))
    }

    // =================================================================== delete guard

    @Test
    fun `a single identified file may be deleted`() {
        val decision = DeleteGuard.plan("content://media/external/downloads/7", emptyList())

        assertTrue(decision is DeleteDecision.Allowed)
        assertEquals("content://media/external/downloads/7", (decision as DeleteDecision.Allowed).uri)
    }

    @Test
    fun `a SAF tree URI is refused because it names a directory`() {
        // Deleting a tree wipes every document under the grant. This is the single
        // most destructive thing a naive implementation can do.
        val decision = DeleteGuard.plan(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            emptyList(),
        )

        assertTrue(decision is DeleteDecision.Refused)
        assertTrue((decision as DeleteDecision.Refused).reason.contains("directory"))
    }

    @Test
    fun `a URI ending in a slash is refused as a directory`() {
        assertTrue(DeleteGuard.isDirectoryUri("content://x/folder/"))
        assertTrue(DeleteGuard.isDirectoryUri("content://x/tree/primary"))
        assertTrue(DeleteGuard.isDirectoryUri("content://x%2Ftree"))
        assertFalse(DeleteGuard.isDirectoryUri("content://x/document/7"))
    }

    @Test
    fun `an ambiguous name is refused and both candidates are named`() {
        val candidates = listOf(
            row(1L).copy(displayName = "notes.txt"),
            row(2L).copy(displayName = "notes.md"),
        )
        val decision = DeleteGuard.plan(null, candidates)

        assertTrue(decision is DeleteDecision.Refused)
        val reason = (decision as DeleteDecision.Refused).reason
        assertTrue(reason.contains("2 documents matched"))
        assertTrue(reason.contains("notes.txt"))
        assertTrue(reason.contains("notes.md"))
    }

    @Test
    fun `a name matching nothing is refused rather than deleting something else`() {
        val decision = DeleteGuard.plan(null, emptyList())
        assertTrue(decision is DeleteDecision.Refused)
        assertTrue((decision as DeleteDecision.Refused).reason.contains("No document matched"))
    }

    @Test
    fun `a unique name is allowed to resolve to its own URI`() {
        val only = row(42L).copy(uri = "content://media/external/file/42")
        val decision = DeleteGuard.plan(null, listOf(only))

        assertTrue(decision is DeleteDecision.Allowed)
        assertEquals("content://media/external/file/42", (decision as DeleteDecision.Allowed).uri)
    }

    @Test
    fun `a wildcard in a URI is refused because it describes a set`() {
        val decision = DeleteGuard.plan("content://x/file/*.pdf", emptyList())
        assertTrue(decision is DeleteDecision.Refused)
        assertTrue((decision as DeleteDecision.Refused).reason.contains("wildcard"))
    }

    @Test
    fun `a wildcard in a name is refused`() {
        assertTrue(DeleteGuard.hasGlob("*.txt"))
        assertTrue(DeleteGuard.hasGlob("notes?.txt"))
        assertTrue(DeleteGuard.hasGlob("a[bc]d"))
        assertFalse(DeleteGuard.hasGlob("notes.txt"))
        assertFalse(DeleteGuard.hasGlob(null))

        val decision = DeleteGuard.plan(null, listOf(row(1L).copy(displayName = "*.txt")))
        assertTrue(decision is DeleteDecision.Refused)
    }

    @Test
    fun `a non content URI is refused outright by the delete guard`() {
        val decision = DeleteGuard.plan("file:///sdcard/Documents/a.pdf", emptyList())
        assertTrue(decision is DeleteDecision.Refused)
        assertTrue((decision as DeleteDecision.Refused).reason.contains("not a content:// URI"))
    }

    @Test
    fun `a resolved row that is itself a directory is refused`() {
        val decision = DeleteGuard.plan(
            null,
            listOf(row(1L).copy(uri = "content://x/tree/primary")),
        )
        assertTrue(decision is DeleteDecision.Refused)
    }

    // ======================================================================= write

    @Test
    fun `a content URI is overwritten regardless of platform version`() {
        for (sdk in intArrayOf(26, 28, 29, 34)) {
            val plan = WritePlanner.plan(sdk, "content://x/document/1", null, "text")
            assertTrue("sdk $sdk", plan is WritePlan.OverwriteUri)
        }
    }

    @Test
    fun `a new file is created in Downloads on Android 10 and newer`() {
        val plan = WritePlanner.plan(29, null, "notes.txt", "hello")

        assertTrue(plan is WritePlan.CreateInDownloads)
        val create = plan as WritePlan.CreateInDownloads
        assertEquals("notes.txt", create.displayName)
        assertEquals("text/plain", create.mimeType)
    }

    @Test
    fun `a new file is refused on Android 9 and older with an actionable reason`() {
        val plan = WritePlanner.plan(28, null, "notes.txt", "hello")

        assertTrue(plan is WritePlan.Refused)
        val reason = (plan as WritePlan.Refused).reason
        assertTrue(reason.contains("Android 10"))
        // The refusal must name the alternative, or the model retries the same call.
        assertTrue(reason.contains("uri"))
    }

    @Test
    fun `a file URI is refused with the SAF explanation`() {
        val plan = WritePlanner.plan(34, "file:///sdcard/notes.txt", null, "hello")

        assertTrue(plan is WritePlan.Refused)
        assertTrue((plan as WritePlan.Refused).reason.contains("content://"))
    }

    @Test
    fun `writing with neither a URI nor a name is refused`() {
        val plan = WritePlanner.plan(34, null, null, "hello")
        assertTrue(plan is WritePlan.Refused)
    }

    @Test
    fun `a proposed filename is stripped of any directory component`() {
        // "save to Documents/notes.txt" must not create a document literally named
        // "Documents/notes.txt", which would be a trap for every later listing.
        assertEquals("notes.txt", WritePlanner.sanitiseFileName("Documents/notes.txt"))
        assertEquals("notes.txt", WritePlanner.sanitiseFileName("  notes.txt  "))
        assertEquals("notes.txt", WritePlanner.sanitiseFileName("a\\b\\notes.txt"))
    }

    @Test
    fun `a filename that is only separators is rejected`() {
        assertNull(WritePlanner.sanitiseFileName("/"))
        assertNull(WritePlanner.sanitiseFileName(".."))
        assertNull(WritePlanner.sanitiseFileName("."))
        assertNull(WritePlanner.sanitiseFileName(""))
        assertNull(WritePlanner.sanitiseFileName("   "))
        assertNull(WritePlanner.sanitiseFileName(null))
    }

    @Test
    fun `a control character in a filename is stripped`() {
        // A NUL in a DISPLAY_NAME breaks providers and mangles every later listing.
        val cleaned = WritePlanner.sanitiseFileName("no tes.txt")
        assertEquals("notes.txt", cleaned)

        val allControl = WritePlanner.sanitiseFileName("abc.txt")
        assertEquals("abc.txt", allControl)
    }

    @Test
    fun `the mime type of a new file is inferred from its extension`() {
        val plan = WritePlanner.plan(34, null, "report.pdf", "text")
        assertEquals("application/pdf", (plan as WritePlan.CreateInDownloads).mimeType)

        val unknown = WritePlanner.plan(34, null, "data.bin", "text")
        assertEquals("text/plain", (unknown as WritePlan.CreateInDownloads).mimeType)
    }

    // ============================================================== observation size

    @Test
    fun `every worst case observation stays inside the budget`() {
        val cases = listOf(
            FileListing.format(
                (1..200).map { row(it.toLong(), name = "x".repeat(200)) },
                total = 200,
                withheld = 180,
                what = "document matching '${"y".repeat(200)}'",
            ),
            FileListing.format(emptyList(), 0, 0, "document matching '${"y".repeat(200)}'"),
            FileText.contentObservation("z".repeat(200), Long.MAX_VALUE, FileText.readCapped(ByteArrayInputStream("w".repeat(50_000).toByteArray()))),
            FileArgs.contentUri(JsonPrimitive("content://" + "p".repeat(3000))).orEmpty(),
            AppShareTarget.fileUriRefusal("file://" + "p".repeat(3000)),
        )

        for (case in cases) {
            val after = ObservationTruncator.truncate(case)
            assertTrue(
                "observation of ${case.length} chars exceeds the budget",
                after.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
            )
        }
    }

    // ========================================================================= utils

    private fun row(id: Long, name: String = "invoice.pdf"): FileRow = FileRow(
        id = id,
        displayName = name,
        mimeType = "application/pdf",
        sizeBytes = 12_288L,
        modifiedMillis = 1_700_000_000_000L,
        relativePath = "Documents/",
        uri = "content://media/external/file/$id",
    )
}
