package dev.localintelligence.android.tools.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.catalogue.ToolMeta
import dev.localintelligence.core.tool.catalogue.ToolArgumentBounds
import dev.localintelligence.core.tool.catalogue.ToolSchemas
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =====================================================================================
// ClipboardTools — clipboard.write (REVERSIBLE) and clipboard.read (READ_ONLY).
//
// Read this before adding a third clipboard tool: on Android 10 (API 29) and later a
// background app cannot read the clipboard at all unless it is the focused window or
// the default keyboard. `ClipboardManager.getPrimaryClip` simply returns null in that
// case — it does not throw and it does not tell you why. This tool therefore reports
// an empty clipboard as NotFound with an observation that names the real reason, so the
// model asks the user to focus the app rather than telling the user their clipboard is
// empty when it is not.
//
// Everything that decides a sentence lives in [ClipboardText] and is pure Kotlin.
// =====================================================================================

/** What the platform put on the clipboard, flattened for pure decision-making. */
data class ClipboardSnapshot(
    /** null when the clipboard holds nothing at all. */
    val text: String?,
    /** MIME types advertised by the clip, e.g. ["image/png"]. */
    val mimeTypes: List<String>,
)

/**
 * The platform seam, so `execute()` can be driven end to end by a JVM test with a fake.
 * See the DevicePlatform comment for the same reasoning.
 */
interface ClipboardPlatform {
    /** Throws if the clipboard is unavailable to this app; the tool maps that to an error. */
    fun write(label: String, text: String)

    fun read(): ClipboardSnapshot?
}

/**
 * All clipboard validation and rendering. Pure Kotlin, no android.* — this is the part
 * the JVM tests execute.
 */
object ClipboardText {

    /**
     * Largest clip this tool will write.
     *
     * The system clipboard is shared process-wide and every paste target must hold the
     * string; a model that tries to put a 4 MB document on the clipboard would make
     * every other app pay for it. Rejecting is better than silently truncating, because
     * a truncated clipboard is a clipboard holding the wrong content.
     */
    // Aliased from :core's ToolArgumentBounds: these numbers appear in this
    // tool's JSON Schema, which :core owns, and in its `execute()`, below.
    // Aliasing rather than repeating is what stops the advertised bound and
    // the enforced bound from drifting apart.
    const val MAX_WRITE_CHARS = ToolArgumentBounds.CLIPBOARD_MAX_WRITE_CHARS

    /** How much of a read clip is shown to the model. The rest is summarised by count. */
    const val MAX_PREVIEW_CHARS = 1_200

    const val DEFAULT_LABEL = "LocalIntelligence"

    /** A clipboard label is a UI hint, never content. */
    const val MAX_LABEL_CHARS = 64

    /**
     * Rejects text the platform clipboard would mangle.
     *
     * There is deliberately NO sanitising step before this. An earlier draft replaced
     * NUL with a space and trimmed leading whitespace, which meant the NUL check below
     * could never fire and the user silently received different text than they asked to
     * copy. A copy tool that quietly edits its input is worse than one that refuses, so
     * every rejection here is a rejection, not a repair.
     *
     * NUL in particular: some clipboard backends and many pastable targets treat it as a
     * terminator and drop everything after it, so a clip containing one is worse than
     * no clip.
     */
    fun validateWrite(text: String): ArgResult<String> = when {
        text.isBlank() ->
            ArgResult.Unusable("text", "an empty string")

        text.contains('\u0000') ->
            ArgResult.Unusable("text", "text containing a NUL character")

        text.length > MAX_WRITE_CHARS ->
            ArgResult.Unusable("text", "a ${text.length}-character string, over the ${MAX_WRITE_CHARS} character limit")

        else -> ArgResult.Present(text)
    }

    fun coerceLabel(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_LABEL
        val cleaned = trimmed.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        return ArgCoerce.ellipsize(cleaned, MAX_LABEL_CHARS)
    }

    /**
     * Renders text for the model.
     *
     * Control characters are collapsed so a clip of terminal output or a binary-ish
     * payload cannot smuggle control sequences into the prompt. Tab and newline survive
     * because they are structure, not noise. The preview is hard-capped at
     * [MAX_PREVIEW_CHARS] and the original length is always stated, so the model knows
     * it is looking at a prefix rather than the whole thing.
     */
    fun previewFor(text: String, max: Int = MAX_PREVIEW_CHARS): String {
        val cleaned = buildString(minOf(text.length, max * 2)) {
            for (ch in text) {
                when {
                    ch == '\n' || ch == '\t' -> append(ch)
                    ch == '\r' -> Unit
                    ch.isISOControl() -> append(' ')
                    else -> append(ch)
                }
            }
        }.trim()
        return if (cleaned.length <= max) cleaned else cleaned.take(max).trimEnd() + "…"
    }

    fun describeWrite(label: String, text: String): String {
        val noun = if (text.length == 1) "character" else "characters"
        val via = if (label == DEFAULT_LABEL) "" else " as \"$label\""
        return "Copied ${text.length} $noun to the clipboard$via."
    }

    fun describeRead(snapshot: ClipboardSnapshot): String {
        val text = snapshot.text
            ?: return "The clipboard says it holds text but the text could not be read."
        val kind = describeMimeTypes(snapshot.mimeTypes)
        val preview = previewFor(text)
        return "Clipboard holds $preview"
            .let { base ->
                if (preview.length < text.trim().length) {
                    "$base (showing the first ${MAX_PREVIEW_CHARS} of ${text.trim().length} characters)."
                } else {
                    base + "."
                }
            }
            .let { if (kind == null) it else "$it ($kind)" }
    }

    /** null when there is nothing useful to say about the MIME types. */
    fun describeMimeTypes(mimeTypes: List<String>): String? {
        val plain = mimeTypes.filter { it.startsWith("text/") }
        return when {
            plain.isEmpty() -> mimeTypes.firstOrNull()?.let { "format $it" }
            plain.size == 1 -> "format ${plain.first()}"
            else -> "formats ${plain.joinToString(", ")}"
        }
    }

    fun describeNonText(mimeTypes: List<String>): String {
        val kind = mimeTypes.joinToString(", ").ifEmpty { "an unknown format" }
        return "The clipboard holds $kind, not text. This tool only reads plain text. " +
            "Ask the user to copy some text and try again."
    }

    fun describeEmpty(): String =
        "The clipboard reads as empty. This is the same result Android returns when the app is " +
            "in the background, which it is not allowed to read from Android 10 onward, so the " +
            "clipboard may well hold something this app simply cannot see right now. " +
            "Ask the user to open the app and try again before telling them the clipboard is empty."

    /**
     * The focus-rule denial, in the shared shape.
     *
     * This is the one clipboard failure that is genuinely a refusal, and it is
     * the failure Android gives you NO signal for: `getPrimaryClip` returns
     * null for a background app exactly as it does for an empty clipboard. The
     * only honest answer names the focus rule, because "your clipboard is
     * empty" is a statement about the user's phone that this app cannot
     * actually support.
     */
    fun describeBackgroundDenied() =
        PermissionDenial.observation("clipboard.read", ToolPermissions.CLIPBOARD_BACKGROUND_READ)

    fun writeData(label: String, text: String): ToolArgs = buildJsonObject {
        put("written", true)
        put("label", label)
        put("chars", text.length)
    }

    fun readData(snapshot: ClipboardSnapshot): ToolArgs = buildJsonObject {
        put("text_chars", snapshot.text?.length ?: 0)
        put("mime_types", snapshot.mimeTypes.joinToString(","))
    }
}

// =====================================================================================
// Schemas
// =====================================================================================// =====================================================================================
// Tools
// =====================================================================================

class ClipboardWriteTool(private val platform: ClipboardPlatform) : AgentTool {

    override val definition = ToolMeta.CLIPBOARD_WRITE.define(
        schema = ToolSchemas.clipboardWrite,
        risk = ToolRisk.REVERSIBLE,
        // No runtime permission exists for writing the clipboard. The `label` is a hint
        // the system shows and is not content; requiredPermission is documentation only,
        // so it is null rather than a permission string that does not exist.
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        // No gate, and deliberately so. `ClipboardManager.setPrimaryClip`
        // requires no permission on any API level this app supports, so the
        // old `permissionGranted` check could only ever be a fabricated denial
        // — and it told the user "No permission to use the clipboard", naming
        // a permission that does not exist and that they therefore cannot
        // grant. A user asking to copy a number was told to give up.

        val rawText = when (val parsed = ArgCoerce.string(args, "text")) {
            is ArgResult.Present -> parsed.value
            is ArgResult.Missing -> {
                return ToolResult(
                    success = false,
                    observation = "text is required. Send the text to copy, for example " +
                        "{\"text\": \"hello\"}.",
                    error = ToolError.InvalidArguments("text missing"),
                )
            }

            is ArgResult.Unusable -> {
                return ToolResult(
                    success = false,
                    observation = "text must be a string, but was ${parsed.got}. Send the text " +
                        "to copy as a single string value.",
                    error = ToolError.InvalidArguments("text unusable: ${parsed.got}"),
                )
            }
        }

        val validated = ClipboardText.validateWrite(rawText)
        val text = when (validated) {
            is ArgResult.Present -> validated.value
            is ArgResult.Unusable -> {
                return ToolResult(
                    success = false,
                    observation = "text cannot be copied because it is ${validated.got}. " +
                        "Send different text.",
                    error = ToolError.InvalidArguments(validated.reason()),
                )
            }

            is ArgResult.Missing -> return ToolResult(
                success = false,
                observation = "text is required.",
                error = ToolError.InvalidArguments("text missing"),
            )
        }

        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the clipboard could be written.",
                error = ToolError.Cancelled("cancelled before clipboard.write"),
            )
        }

        val label = ClipboardText.coerceLabel(
            (ArgCoerce.string(args, "label") as? ArgResult.Present)?.value,
        )

        return guarded("clipboard.write") {
            platform.write(label, text)
            ToolResult(
                success = true,
                observation = ClipboardText.describeWrite(label, text),
                data = ClipboardText.writeData(label, text),
            )
        }
    }

    private fun ArgResult.Unusable.reason(): String = when (got) {
        "an empty string" -> "blank"
        else -> got
    }
}

class ClipboardReadTool(private val platform: ClipboardPlatform) : AgentTool {

    override val definition = ToolMeta.CLIPBOARD_READ.define(
        schema = ToolSchemas.clipboardRead,
        risk = ToolRisk.READ_ONLY,
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        // No permission gate: there is no clipboard permission to hold. What
        // gates a read is Android 10's focus rule, and that is reported by
        // describeEmpty() below, which is already the honest shape — it says
        // the app could not read the clipboard rather than that it is empty.
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "Cancelled before the clipboard could be read.",
                error = ToolError.Cancelled("cancelled before clipboard.read"),
            )
        }

        return guarded("clipboard.read") {
            val snapshot = platform.read()
                ?: return@guarded ToolResult(
                    success = false,
                    observation = ClipboardText.describeEmpty(),
                    error = ToolError.NotFound("clipboard is empty or unreadable"),
                )

            val isText = snapshot.mimeTypes.any { it.startsWith("text/") } ||
                snapshot.mimeTypes.isEmpty()
            if (!isText || snapshot.text == null) {
                // A non-text clip is NOT coerced. Coercing an image or a file URI to text
                // produces a content:// URI string that the model will happily read as
                // if it were the user's words.
                return@guarded ToolResult(
                    success = false,
                    observation = ClipboardText.describeNonText(snapshot.mimeTypes),
                    data = ClipboardText.readData(snapshot),
                    error = ToolError.Unavailable("clipboard holds a non-text item"),
                )
            }

            ToolResult(
                success = true,
                observation = ObservationTruncator.truncate(ClipboardText.describeRead(snapshot)),
                data = ClipboardText.readData(snapshot),
            )
        }
    }
}

// =====================================================================================
// The Android implementation
// =====================================================================================

class AndroidClipboardPlatform(private val context: Context) : ClipboardPlatform {

    /**
     * Resolved on first use, not at construction.
     *
     * WHY: a tool's `definition` is a constructor-level `val` built from
     * literals, so a tool ought to be constructible without touching the
     * platform at all — that is what makes the whole shipped tool set
     * assertable on a plain JVM, with no emulator and no Robolectric
     * (`docs/architecture.md` §2). Eagerly calling
     * `context.applicationContext` here put a live `Context` call in the
     * constructor of every tool in the family and broke that property for
     * no benefit: the app context is wanted by the first platform call,
     * not by the constructor.
     *
     * It also keeps a `Context` from being captured by a long-lived
     * singleton tool when the caller passed an Activity.
     */
    private val appContext: Context by lazy { context.applicationContext }

    private fun manager(): ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    override fun write(label: String, text: String) {
        val clipboard = manager()
            ?: throw IllegalStateException("ClipboardManager unavailable")
        // Plain text only, enforced at the source rather than only in the reader: a rich
        // clip written here would be handed to whatever app the user pastes into.
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    override fun read(): ClipboardSnapshot? {
        val clipboard = manager() ?: return null
        val description = clipboard.primaryClipDescription ?: return null
        val mimeTypes = readMimeTypes(description)
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        // getItemAt(0).text is null for every non-text clip, which is exactly the
        // "never read a non-text clip" rule: the platform does the discrimination and
        // we never call coerceToText(), which is what would turn an image into a URI.
        val text = clip.getItemAt(0).text?.toString()
        return ClipboardSnapshot(text = text, mimeTypes = mimeTypes)
    }

    /** getMimeTypeCount()/getMimeType(i) rather than getMimeTypes(): the no-arg form
     *  is not in the compile SDK's stub surface, and the indexed form is API 16. */
    private fun readMimeTypes(description: android.content.ClipDescription): List<String> {
        val count = description.mimeTypeCount
        if (count <= 0) return emptyList()
        return (0 until count).mapNotNull { index ->
            try {
                description.getMimeType(index)
            } catch (e: Exception) {
                null
            }
        }
    }
}

fun clipboardTools(context: Context): List<AgentTool> = listOf(
    ClipboardWriteTool(AndroidClipboardPlatform(context)),
    ClipboardReadTool(AndroidClipboardPlatform(context)),
)
