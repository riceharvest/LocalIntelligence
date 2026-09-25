package dev.localintelligence.android.tools

import dev.localintelligence.android.tools.clipboard.ClipboardPlatform
import dev.localintelligence.android.tools.clipboard.ClipboardReadTool
import dev.localintelligence.android.tools.clipboard.ClipboardSnapshot
import dev.localintelligence.android.tools.clipboard.ClipboardText
import dev.localintelligence.android.tools.clipboard.ClipboardWriteTool
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for clipboard.write and clipboard.read.
 *
 * The tools sit on a [ClipboardPlatform] seam so `execute()` itself is exercised here,
 * against a fake. That matters more for this package than for any other: the whole job
 * of `clipboard.read` is to decide what the *model* is allowed to see, and a test that
 * only calls the string builders would never prove that a non-text clip is refused
 * rather than coerced.
 */
class ClipboardToolsTest {

    // =============================================================== fake platform --

    private class FakeClipboardPlatform(
        var clip: ClipboardSnapshot? = null,
        var throwOnWrite: Exception? = null,
    ) : ClipboardPlatform {
        var calls: MutableList<String> = mutableListOf()
        var lastLabel: String? = null
        var lastText: String? = null

        override fun write(label: String, text: String) {
            calls += "write"
            lastLabel = label
            lastText = text
            throwOnWrite?.let { throw it }
        }

        override fun read(): ClipboardSnapshot? {
            calls += "read"
            return clip
        }
    }

    private val denied = ToolContext(permissionGranted = false)
    private val granted = ToolContext(permissionGranted = true)
    private val cancelled = ToolContext(permissionGranted = true, signal = CancellationSignal { true })

    // ============================================================== risk policy --

    @Test
    fun `risk declared on each clipboard tool matches the contract policy`() {
        val platform = FakeClipboardPlatform()

        // Writing replaces whatever was on the clipboard, so it is REVERSIBLE only in
        // the sense the contract means: the previous clip is not permanently gone, but
        // nothing can put it back automatically. It is NOT destructive — no user data
        // is deleted, only a transient buffer is overwritten.
        assertEquals(ToolRisk.REVERSIBLE, ClipboardWriteTool(platform).definition.risk)
        // Reading changes nothing at all.
        assertEquals(ToolRisk.READ_ONLY, ClipboardReadTool(platform).definition.risk)

        // Neither may prompt: overwriting a clipboard is not something a user has to
        // approve a second time, and a read never does.
        assertFalse(ClipboardWriteTool(platform).definition.risk.requiresConfirmation)
        assertFalse(ClipboardReadTool(platform).definition.risk.requiresConfirmation)
    }

    @Test
    fun `every clipboard definition satisfies the tool contract checklist`() {
        val platform = FakeClipboardPlatform()
        val definitions = listOf(
            ClipboardWriteTool(platform).definition,
            ClipboardReadTool(platform).definition,
        )

        for (d in definitions) {
            assertTrue("name: ${d.name}", d.name.matches(NAME_PATTERN))
            assertTrue("name: ${d.name} needs one dot", d.name.count { it == '.' } == 1)
            assertEquals("category of ${d.name}", "clipboard", d.category)
            assertEquals("schema type of ${d.name}", "object", d.schema["type"]?.toString()?.trim('"'))
            assertNotNull("properties of ${d.name}", d.schema["properties"] as? JsonObject)
            assertNotNull("required of ${d.name}", d.schema["required"])
            assertEquals("description of ${d.name} must be one sentence", 1, d.description.count { it == '.' })
            assertTrue("${d.name} has ${d.tags.size} tags", d.tags.size in 4..8)
            assertTrue("tags of ${d.name} must be lowercase", d.tags.all { it == it.lowercase() })
        }

        // clipboard.read declares `format`, so the schema must actually offer it —
        // ToolCallValidator rejects any argument not present in properties.
        val readProperties = ClipboardReadTool(platform).definition.schema["properties"] as JsonObject
        assertTrue(readProperties.containsKey("format"))
        val writeProperties = ClipboardWriteTool(platform).definition.schema["properties"] as JsonObject
        assertTrue(writeProperties.containsKey("text"))
        assertTrue(writeProperties.containsKey("label"))
    }

    // ============================================================ permission gate --

    @Test
    fun `write returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(args { put("text", "hello") }, denied)

        assertFalse(result.success)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue("platform must not be touched", platform.calls.isEmpty())
        assertTrue(result.observation.contains("do not retry"))
    }

    @Test
    fun `read returns PermissionDenied without touching the platform`() = runTest {
        val platform = FakeClipboardPlatform(clip = ClipboardSnapshot("hi", listOf("text/plain")))
        val result = ClipboardReadTool(platform).execute(emptyArgs(), denied)

        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    // =================================================================== write --

    @Test
    fun `write copies plain text and says how much`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(args { put("text", "meeting at 3") }, granted)

        assertTrue(result.success)
        assertEquals("meeting at 3", platform.lastText)
        assertEquals(ClipboardText.DEFAULT_LABEL, platform.lastLabel)
        assertTrue(result.observation, result.observation.contains("12 characters"))
    }

    @Test
    fun `a supplied label is used and clamped, control characters stripped`() {
        assertEquals("notes", ClipboardText.coerceLabel("  notes  "))
        assertEquals(ClipboardText.DEFAULT_LABEL, ClipboardText.coerceLabel(null))
        assertEquals(ClipboardText.DEFAULT_LABEL, ClipboardText.coerceLabel("   "))

        val long = "x".repeat(500)
        assertTrue(ClipboardText.coerceLabel(long).length <= ClipboardText.MAX_LABEL_CHARS + 1)
        assertFalse(ClipboardText.coerceLabel("a\nb").contains('\n'))
    }

    @Test
    fun `a missing text is InvalidArguments and copies nothing`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(emptyArgs(), granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
        assertTrue(result.observation, result.observation.contains("text is required"))
    }

    @Test
    fun `an explicit null text falls back to the missing path, not a crash`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(args { put("text", JsonNull) }, granted)

        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `a wrongly typed text is rejected with a message naming what arrived`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(
            args { put("text", buildJsonArray { }) },
            granted,
        )

        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("must be a string"))
        assertTrue(result.observation, result.observation.contains("an array of 0 items"))
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `a number sent as text is accepted rather than thrown away`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(args { put("text", 42) }, granted)

        assertTrue("got: ${result.observation}", result.success)
        assertEquals("42", platform.lastText)
    }

    @Test
    fun `blank text is refused because an empty clipboard is worse than a failed copy`() = runTest {
        val platform = FakeClipboardPlatform()
        for (blank in listOf("", "   ", "\n\t ")) {
            val result = ClipboardWriteTool(platform).execute(args { put("text", blank) }, granted)
            assertFalse("blank '$blank' must be refused", result.success)
            assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        }
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `a NUL in the text is refused, because pastes would silently truncate`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(
            args { put("text", "before\u0000after") },
            granted,
        )

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("NUL"))
        assertTrue(platform.calls.isEmpty())
    }

    @Test
    fun `an over-long clip is refused outright, never silently truncated`() = runTest {
        val platform = FakeClipboardPlatform()
        val huge = "x".repeat(ClipboardText.MAX_WRITE_CHARS + 1)
        val result = ClipboardWriteTool(platform).execute(args { put("text", huge) }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.InvalidArguments::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("character limit"))
        // A truncated clipboard is a clipboard holding the WRONG content.
        assertTrue("must not write a partial clip", platform.calls.isEmpty())
    }

    @Test
    fun `a clip exactly at the limit is accepted`() = runTest {
        val platform = FakeClipboardPlatform()
        val exact = "x".repeat(ClipboardText.MAX_WRITE_CHARS)
        val result = ClipboardWriteTool(platform).execute(args { put("text", exact) }, granted)

        assertTrue(result.success)
        assertEquals(ClipboardText.MAX_WRITE_CHARS, platform.lastText!!.length)
    }

    @Test
    fun `a platform failure on write becomes a typed error and never escapes`() = runTest {
        val platform = FakeClipboardPlatform(throwOnWrite = SecurityException("clipboard locked"))
        val result = ClipboardWriteTool(platform).execute(args { put("text", "x") }, granted)

        assertFalse(result.success)
        assertEquals(ToolError.PermissionDenied::class, result.error!!::class)
        assertFalse("no exception toString in an observation", result.observation.contains("java.lang"))
    }

    @Test
    fun `cancellation is reported before the clipboard is written`() = runTest {
        val platform = FakeClipboardPlatform()
        val result = ClipboardWriteTool(platform).execute(args { put("text", "x") }, cancelled)

        assertEquals(ToolError.Cancelled::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    // ==================================================================== read --

    @Test
    fun `read returns the clipboard text`() = runTest {
        val platform = FakeClipboardPlatform(
            clip = ClipboardSnapshot("the wifi password is hunter2", listOf("text/plain")),
        )
        val result = ClipboardReadTool(platform).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(result.observation, result.observation.contains("hunter2"))
        assertTrue(result.observation, result.observation.contains("text/plain"))
    }

    @Test
    fun `an empty clipboard is NotFound, and names the Android 10 focus restriction`() = runTest {
        val platform = FakeClipboardPlatform(clip = null)
        val result = ClipboardReadTool(platform).execute(emptyArgs(), granted)

        assertFalse(result.success)
        // NotFound is one of the two errors the contract says the model must handle
        // gracefully, which is exactly what an empty clipboard is.
        assertEquals(ToolError.NotFound::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("focus the app"))
    }

    @Test
    fun `a non-text clip is refused and NEVER coerced to a URI string`() = runTest {
        val platform = FakeClipboardPlatform(
            // What an image clip really looks like: no text, a content:// URI underneath.
            clip = ClipboardSnapshot(text = null, mimeTypes = listOf("image/png")),
        )
        val result = ClipboardReadTool(platform).execute(emptyArgs(), granted)

        assertFalse(result.success)
        assertEquals(ToolError.Unavailable::class, result.error!!::class)
        assertTrue(result.observation, result.observation.contains("image/png"))
        assertTrue(result.observation, result.observation.contains("not text"))
        // The absolute core of this tool's contract.
        assertFalse("no content:// URI may reach the model", result.observation.contains("content://"))
    }

    @Test
    fun `a clip that claims text but carries none is not reported as text`() = runTest {
        val platform = FakeClipboardPlatform(clip = ClipboardSnapshot(text = null, mimeTypes = listOf("text/plain")))
        val result = ClipboardReadTool(platform).execute(emptyArgs(), granted)

        assertFalse(result.success)
        assertEquals(ToolError.Unavailable::class, result.error!!::class)
    }

    @Test
    fun `a clip with no declared mime type is still treated as text`() = runTest {
        val platform = FakeClipboardPlatform(clip = ClipboardSnapshot("plain words", emptyList()))
        val result = ClipboardReadTool(platform).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(result.observation.contains("plain words"))
    }

    @Test
    fun `an html clip is still text and is read as such`() = runTest {
        val platform = FakeClipboardPlatform(
            clip = ClipboardSnapshot("<b>bold</b>", listOf("text/html", "text/plain")),
        )
        val result = ClipboardReadTool(platform).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(result.observation.contains("bold"))
    }

    @Test
    fun `cancellation is reported before the clipboard is read`() = runTest {
        val platform = FakeClipboardPlatform(clip = ClipboardSnapshot("x", listOf("text/plain")))
        val result = ClipboardReadTool(platform).execute(emptyArgs(), cancelled)

        assertEquals(ToolError.Cancelled::class, result.error!!::class)
        assertTrue(platform.calls.isEmpty())
    }

    // ============================================================== sanitising --

    @Test
    fun `control characters in a clip cannot smuggle sequences into the prompt`() {
        val hostile = "safe\u0000\u0007\u001B[31mESC\u001B]0;title\u0007line\u0000\u0007"

        val preview = ClipboardText.previewFor(hostile)

        assertFalse("no ESC may survive", preview.contains('\u001B'))
        assertFalse("no NUL may survive", preview.contains('\u0000'))
        assertFalse("no BEL may survive", preview.contains('\u0007'))
        assertTrue("the harmless words must survive", preview.contains("safe"))
        assertTrue(preview.contains("line"))
    }

    @Test
    fun `newlines and tabs survive, because they are structure not noise`() {
        val preview = ClipboardText.previewFor("line one\n\tindented\r\nline two")
        assertTrue(preview, preview.contains("\n"))
        assertTrue(preview, preview.contains("\t"))
        assertFalse("CR is normalised away", preview.contains('\r'))
    }

    @Test
    fun `the preview is capped and the model is told it is a prefix`() {
        val huge = "y".repeat(ClipboardText.MAX_PREVIEW_CHARS * 4)
        val preview = ClipboardText.previewFor(huge)

        assertTrue("was ${preview.length}", preview.length <= ClipboardText.MAX_PREVIEW_CHARS + 1)

        val text = ClipboardText.describeRead(ClipboardSnapshot(huge, listOf("text/plain")))
        assertTrue("must say it is showing a prefix", text.contains("showing the first"))
        assertTrue(text.contains("${huge.length} characters"))
    }

    @Test
    fun `a worst-case clipboard observation stays under the model budget`() = runTest {
        // A hostile app puts a megabyte of control characters on the clipboard. The
        // observation the model sees must still be short plain text.
        val megabyte = buildString {
            repeat(200_000) { append('a') }
            repeat(5_000) { append("\u0007\u001B[31m") }
        }
        val platform = FakeClipboardPlatform(
            clip = ClipboardSnapshot(megabyte, listOf("text/plain", "text/html", "text/plain")),
        )

        val result = ClipboardReadTool(platform).execute(emptyArgs(), granted)

        assertTrue(result.success)
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
        assertFalse("no JSON dump", result.observation.startsWith("{"))
        assertFalse("no ESC", result.observation.contains('\u001B'))
    }

    @Test
    fun `the write observation stays under budget for the largest legal clip`() = runTest {
        val platform = FakeClipboardPlatform()
        val exact = "x".repeat(ClipboardText.MAX_WRITE_CHARS)
        val result = ClipboardWriteTool(platform).execute(args { put("text", exact) }, granted)

        assertTrue(result.success)
        assertTrue(
            "observation was ${result.observation.length} chars",
            result.observation.length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
        )
    }

    @Test
    fun `mime descriptions stay short even with a long list`() {
        val many = List(50) { "application/octet-stream-$it" }
        val described = ClipboardText.describeMimeTypes(many)!!
        // Every declared type would be 2 KB of pure noise in a 4K context window.
        assertTrue("was ${described.length}", described.length < 200)
    }

    // ============================================================== helpers --

    private fun emptyArgs(): ToolArgs = buildJsonObject { }

    private fun args(block: JsonObjectBuilder.() -> Unit): ToolArgs = buildJsonObject(block)

    private companion object {
        val NAME_PATTERN = Regex("^[a-z]+[._][a-z_]+$")
    }
}
