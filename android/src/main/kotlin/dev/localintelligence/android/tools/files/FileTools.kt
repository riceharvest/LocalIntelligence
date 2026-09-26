package dev.localintelligence.android.tools.files

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// =====================================================================================
// WHY SAF AND NOT java.io
//
// A modern Android app cannot read `/storage/emulated/0/Documents/x.pdf`. Since API 29
// that path is not merely restricted, it is invisible to an app that has not been handed
// the directory. Everything user-visible in this file therefore goes through
// ContentResolver against MediaStore (which indexes the *user-visible* Documents and
// Downloads trees) or against a `content://` URI the user granted through the Storage
// Access Framework. There is not one `File`, `FileInputStream` or raw path in this file,
// by design: it would be dead code on a real device and a security problem in review.
//
// WRITE STRATEGY (files.write_text) — ACTION_CREATE_DOCUMENT vs MediaStore insert:
//   chosen = MediaStore.Downloads insert on API 29+, SAF `content://` uri otherwise.
//   A genuinely arbitrary save location (a specific folder, any provider, pre-API-29)
//   needs an ACTION_CREATE_DOCUMENT Activity-result bridge, and an Activity needs an
//   AndroidManifest <activity> entry, which this branch may not add. See the PR report
//   for the exact XML. Overwriting an already-granted document needs no bridge at all,
//   which is why `uri` is the primary argument.
//
// SHARE STRATEGY (apps.share) — SAF content:// URI, not FileProvider:
//   see AppShareTarget in AppTools.kt. A `file://` URI throws FileUriExposedException on
//   Android 7+ (API 24, below this module's minSdk 26) when it crosses a process
//   boundary, so it is rejected at the argument layer with a precise message rather
//   than being handed to startActivity and crashing the caller's agent step.
//
// HEAP: nothing here retains a file. The largest live allocation is one row buffer
// (see FileRow) plus, for files.read_text, a single String of at most
// FileText.READ_CAP_BYTES (8 KiB) — a 1 GB log costs the same as an empty file.
// =====================================================================================

/**
 * The model-visible observation budget. Every tool in this file funnels its final
 * string through [ObservationTruncator] at this width, so a bug in a formatter
 * degrades one observation rather than blowing the agent's context window.
 */
internal const val OBSERVATION_BUDGET = ObservationTruncator.DEFAULT_BUDGET_CHARS

/**
 * The single place an exception is allowed to stop being an exception.
 *
 * Rule 1 of the tool contract is that `execute` never throws for an expected failure.
 * Android's content providers throw for a dozen ordinary situations — a revoked
 * persistable permission, a document deleted by another app between the query and the
 * open, an OEM provider that has no `media_type` column — and each of those is a
 * normal outcome, not a bug. Every tool body is wrapped in [guard] so that
 * "provider absent" and "provider threw" are indistinguishable to the caller.
 *
 * Deliberately catches [Throwable]: a tool that lets an `OutOfMemoryError` or a
 * `NoSuchMethodError` escape takes down an agent step that should have recovered.
 * The observation never carries the message or the stack trace — only the exception's
 * simple name, which is a bug reference, not a transcript.
 *
 * This lives in the `files` package because the assignment for this branch permits
 * exactly two new main-source files. Both files import it; nothing else may.
 */
internal object ToolSafety {

    /**
     * Runs [block] and converts anything it throws into a typed [ToolResult].
     *
     * @param reference short tool name used in error text, e.g. "files.read_text".
     */
    inline fun guard(reference: String, block: () -> ToolResult): ToolResult =
        try {
            val result = block()
            // The final net: a formatter bug that produced a 40 KB observation is
            // still bounded before it reaches the prompt.
            result.copy(observation = ObservationTruncator.truncate(result.observation, OBSERVATION_BUDGET))
        } catch (t: SecurityException) {
            ToolResult(
                success = false,
                observation = "Android denied access for $reference. Grant the file or " +
                    "storage permission and try again; do not retry unchanged.",
                error = ToolError.PermissionDenied("SecurityException during $reference"),
            )
        } catch (t: java.io.FileNotFoundException) {
            ToolResult(
                success = false,
                observation = "The document for $reference no longer exists, or this app " +
                    "was not granted access to it. Re-run the search to get a current URI.",
                error = ToolError.NotFound("FileNotFoundException during $reference"),
            )
        } catch (t: IllegalArgumentException) {
            ToolResult(
                success = false,
                observation = "$reference rejected the request: ${t.message?.take(120) ?: "bad argument"}. " +
                    "Check the URI and argument names.",
                error = ToolError.InvalidArguments("IllegalArgumentException during $reference"),
            )
        } catch (t: IllegalStateException) {
            ToolResult(
                success = false,
                observation = "$reference could not run: the content provider is not " +
                    "available right now. Try again later.",
                error = ToolError.Unavailable("IllegalStateException during $reference"),
            )
        } catch (t: CancellationException) {
            // MUST be rethrown. Cancellation is structured concurrency, not a
            // tool failure: swallowing it here turns a user's Stop into
            // "files.write_text failed unexpectedly", which is both a lie about
            // what happened and a scope that never cancels. This is a standing
            // project rule with no exceptions, and this catch had been the one
            // place in the tool layer that broke it.
            throw t
        } catch (t: Throwable) {
            ToolResult(
                success = false,
                observation = "$reference failed unexpectedly (${t::class.java.simpleName}). " +
                    "This is a bug; try a different approach.",
                error = ToolError.Internal("${t::class.java.simpleName} in $reference"),
            )
        }
}

// ---------------------------------------------------------------------------- arguments

/**
 * Defensive coercion of model-supplied arguments.
 *
 * Small models emit `"8"` for an Int, `null` for a String, `true` for a number, and
 * `"yes"` for a boolean. `ToolCallValidator` checks argument *shape* only, so every
 * semantic decision — is this a limit, is this a usable query, is this boolean true —
 * is made here. Every function is total: it returns a usable value or a documented
 * default, never an exception, and never trusts the input's declared type.
 */
object FileArgs {

    /** A browse tool that answers "what do I have?" with 20 rows is useless at 3. */
    // Aliased from :core's ToolArgumentBounds: these numbers appear in this
    // tool's JSON Schema, which :core owns, and in its `execute()`, below.
    // Aliasing rather than repeating is what stops the advertised bound and
    // the enforced bound from drifting apart.
    const val DEFAULT_LIMIT = ToolArgumentBounds.FILES_DEFAULT_LIMIT

    /** Hard ceiling. 100 rows of file metadata is already ~4 KB of observation. */
    const val MAX_LIMIT = ToolArgumentBounds.FILES_MAX_LIMIT

    /** A query longer than this is a confused model, not a user with a long filename. */
    const val MAX_QUERY_CHARS = ToolArgumentBounds.FILES_MAX_QUERY_CHARS

    /** MIME types come from the provider and from the model; both can be silly. */
    const val MAX_MIME_CHARS = ToolArgumentBounds.FILES_MAX_MIME_CHARS

    /** Filenames are bounded so one hostile provider row cannot eat the heap. */
    const val MAX_NAME_CHARS = 160

    /** Cap on a filename accepted as a write target. */
    const val MAX_WRITE_NAME_CHARS = ToolArgumentBounds.FILES_MAX_WRITE_NAME_CHARS

    /** Largest text payload accepted by files.write_text, in characters. */
    const val MAX_WRITE_CHARS = ToolArgumentBounds.FILES_MAX_WRITE_CHARS

    /**
     * Coerces a limit argument: number, numeric string, or default. Clamped to
     * `[1, MAX_LIMIT]`.
     *
     * A non-positive limit is treated as "the model did not mean it" and falls back to
     * [DEFAULT_LIMIT] rather than returning an empty list — "no documents" is a much
     * more alarming and wrong observation than a full listing of 20.
     */
    fun limit(raw: JsonElement?): Int {
        val n = longOrNull(raw) ?: return DEFAULT_LIMIT
        return when {
            n < 1L -> DEFAULT_LIMIT
            n > MAX_LIMIT -> MAX_LIMIT
            else -> n.toInt()
        }
    }

    /**
     * Coerces an optional string argument.
     *
     * Accepts a JSON string, a bare number, or a boolean coerced to its literal form —
     * all of which occur in practice. Rejects null, objects and arrays, which are
     * always a hallucinated argument shape. The result is trimmed and length-capped.
     */
    fun optionalString(raw: JsonElement?, maxChars: Int = MAX_QUERY_CHARS): String? {
        val text = stringOrNull(raw) ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length > maxChars) trimmed.take(maxChars) else trimmed
    }

    /**
     * A required string argument. Returns null when absent, blank, or not a scalar —
     * the caller turns that into `InvalidArguments` naming the parameter.
     */
    fun requiredString(raw: JsonElement?, maxChars: Int = MAX_QUERY_CHARS): String? =
        optionalString(raw, maxChars)

    /**
     * Coerces a boolean. Accepts a real boolean, the strings "true"/"false"/"1"/"0"/
     * "yes"/"no", and the numbers 1 and 0. Anything else yields [default].
     *
     * The numeric case matters: JSON has no integer literal type, and a model that
     * "means" true often emits 1, which must not silently become false.
     */
    fun boolean(raw: JsonElement?, default: Boolean): Boolean = when (val v = stringOrNull(raw)) {
        null -> default
        else -> when (v.trim().lowercase()) {
            "true", "1", "yes", "y" -> true
            "false", "0", "no", "n" -> false
            else -> default
        }
    }

    /**
     * Coerces an epoch-millis bound, accepting a number or a numeric string.
     * Returns null when absent or unparseable, which callers read as "no bound".
     *
     * Negative values are rejected rather than clamped: a date before 1970 is not a
     * date a user meant, and letting it through would turn "files since last year"
     * into "all files ever".
     */
    fun epochMillis(raw: JsonElement?): Long? {
        val n = longOrNull(raw) ?: return null
        return if (n < 0L) null else n
    }

    /**
     * Returns a scalar element as text, or null for null / object / array.
     *
     * `JsonNull` is a `JsonPrimitive` whose content is the literal string "null", so
     * it has to be rejected explicitly or a missing argument silently becomes the
     * filename "null".
     */
    fun stringOrNull(raw: JsonElement?): String? = when (raw) {
        null, is JsonNull -> null
        is JsonPrimitive -> raw.contentOrNull
        is JsonArray, is JsonObject -> null
    }

    /** A JSON number, or a string that is exactly a number. */
    fun longOrNull(raw: JsonElement?): Long? {
        val prim = raw as? JsonPrimitive ?: return null
        if (prim is JsonNull) return null
        prim.longOrNull?.let { return it }
        val text = prim.contentOrNull?.trim() ?: return null
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.toLong()
    }

    /**
     * Returns the argument only if it is a `content://` URI string.
     *
     * A scheme check on the string, deliberately not `Uri.parse`, so that the decision
     * is testable on the JVM and so that a `file://` URI is refused before any
     * ContentResolver call is attempted.
     */
    fun contentUri(raw: JsonElement?): String? {
        val text = stringOrNull(raw)?.trim() ?: return null
        return if (isContentUri(text)) text else null
    }

    /** True for a syntactically plausible `content://` URI. */
    fun isContentUri(uri: String?): Boolean {
        val s = uri?.trim() ?: return false
        return s.startsWith(CONTENT_SCHEME) && s.length > CONTENT_SCHEME.length
    }

    const val CONTENT_SCHEME = "content://"
}

// ---------------------------------------------------------------------------- querying

/** One indexed document, as the model and the UI need to see it. */
data class FileRow(
    val id: Long,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    /** Epoch MILLIS, normalised from MediaStore's seconds. */
    val modifiedMillis: Long,
    /** `Documents/2026/`, or null below API 29 where the column does not exist. */
    val relativePath: String?,
    /** The `content://` URI to pass back to read_text / write_text / delete. */
    val uri: String,
)

/** A selection plus its bound arguments. The query text never appears in the SQL. */
data class FileSelection(
    val selection: String,
    val args: List<String>,
) {
    val isEmpty: Boolean get() = args.isEmpty()
}

/**
 * MediaStore query construction, as pure data.
 *
 * Every value the caller supplies is bound, never concatenated. A filename containing
 * `'` or `;` becomes an argument, so it cannot alter the statement. The `LIKE`
 * metacharacters `%` and `_` are escaped for the *user's* query so that searching for
 * "100%" does not match every file.
 */
object FileQuery {

    /**
     * MediaStore.Files column for "this is a document, not a photo or a song".
     * Hard-coded rather than referenced from `MediaStore.Files.FileColumns` because
     * that constant is a String on some SDK levels and an Int on others.
     */
    const val MEDIA_TYPE_DOCUMENT = "document"

    /** Columns read on API 29+. `RELATIVE_PATH` was added in Q. */
    private val MODERN_COLUMNS = listOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
        MediaStore.Files.FileColumns.RELATIVE_PATH,
    )

    /** Columns read below API 29, where RELATIVE_PATH does not exist. */
    private val LEGACY_COLUMNS = listOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DISPLAY_NAME,
        MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.SIZE,
        MediaStore.Files.FileColumns.DATE_MODIFIED,
    )

    /**
     * The projection for [sdkInt]. Asking for RELATIVE_PATH on API 26 returns an empty
     * projection from some OEM providers and silently yields zero rows, so the
     * column set is version-dependent rather than a constant.
     */
    fun projection(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.Q) MODERN_COLUMNS else LEGACY_COLUMNS

    /**
     * Builds the WHERE clause. Fixed terms are literals; every caller value is bound.
     *
     * @param query case-insensitive substring of the display name, or null.
     * @param mime exact MIME type, or null. Exact, not a prefix: `text/` would also
     *   match `text/html`, which is a different request.
     * @param fromMillis inclusive lower bound on modification time, or null.
     * @param toMillis exclusive upper bound on modification time, or null.
     */
    fun buildSelection(
        query: String? = null,
        mime: String? = null,
        fromMillis: Long? = null,
        toMillis: Long? = null,
    ): FileSelection {
        val clauses = mutableListOf("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?")
        val args = mutableListOf<String>(MEDIA_TYPE_DOCUMENT)

        if (!query.isNullOrBlank()) {
            clauses += "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            args += likePattern(query)
        }
        if (!mime.isNullOrBlank()) {
            clauses += "${MediaStore.Files.FileColumns.MIME_TYPE} = ?"
            args += mime
        }
        if (fromMillis != null) {
            clauses += "${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ?"
            args += modifiedSecondsFrom(fromMillis).toString()
        }
        if (toMillis != null) {
            clauses += "${MediaStore.Files.FileColumns.DATE_MODIFIED} < ?"
            args += modifiedSecondsFrom(toMillis).toString()
        }
        return FileSelection(clauses.joinToString(" AND "), args)
    }

    /**
     * MediaStore stores `date_modified` in SECONDS. Converting in the tool rather than
     * in the query keeps the argument binding a plain string and makes the conversion
     * unit-testable — an off-by-1000 here silently returns "modified in 1970".
     */
    fun modifiedSecondsFrom(millis: Long): Long =
        if (millis < 0L) 0L else millis / 1000L

    /**
     * Wraps a user query as a LIKE pattern, escaping the metacharacters.
     *
     * A user searching for `report_2026` must not match `reportX2026`, and a user
     * searching for `100%` must not match every file. ESCAPE '\' is declared in
     * [buildSelection] and the backslash itself must therefore be escaped too.
     */
    fun likePattern(query: String): String =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** Newest first. Ties are broken by id so repeated calls agree. */
    const val ORDER_BY = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC, " +
        "${MediaStore.Files.FileColumns._ID} DESC"
}

// -------------------------------------------------------------------------------- mime

/** MIME handling, in plain strings. */
object MimeTypes {

    const val FALLBACK = "unknown"

    /** Types that are plausible to read as text. Anything else is binary to this tool. */
    private val TEXT_PREFIXES = listOf("text/")
    private val TEXT_EXACT = setOf(
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-yaml",
        "application/yaml",
        "application/sql",
        "application/x-sh",
        "application/csv",
        "application/rtf",
        "application/x-tex",
    )

    private val EXTENSION_TYPES = mapOf(
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "pdf" to "application/pdf",
        "log" to "text/plain",
        "yml" to "text/plain",
        "yaml" to "text/plain",
        "toml" to "text/plain",
        "conf" to "text/plain",
        "ini" to "text/plain",
        "sh" to "application/x-sh",
        // Images are not readable text, but they are the single most shared
        // attachment on a phone, so apps.share needs a correct type for them —
        // a wrong declared type makes the chooser filter the attachment out.
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
    )

    /**
     * A short human label for a MIME type, so the observation reads "PDF" rather than
     * "application/pdf". Unknown types fall back to the subtype after the slash,
     * which is still more informative than a blank.
     */
    fun friendly(mime: String?): String {
        val m = mime?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != FALLBACK } ?: return FALLBACK
        val subtype = m.substringAfterLast('/', missingDelimiterValue = "")
        return when {
            subtype.isEmpty() -> m
            subtype == "vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
            subtype == "vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX"
            subtype == "vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX"
            subtype == "vnd.oasis.opendocument.text" -> "ODT"
            subtype.startsWith("vnd.") -> subtype.removePrefix("vnd.").take(24)
            else -> subtype.uppercase().take(24)
        }
    }

    /** The lowercase extension of a filename, without the dot, or null. */
    fun extensionOf(name: String?): String? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (n.endsWith(".")) return null
        val ext = n.substringAfterLast('.', missingDelimiterValue = "")
        if (ext.isEmpty() || ext.length > 12) return null
        // A "extension" containing a path separator or a glob char is not one.
        if (ext.any { it == '/' || it == '\\' || it == '*' || it == '?' }) return null
        return ext.lowercase()
    }

    /** Best-effort MIME type from a filename, for rows the provider did not classify. */
    fun fromExtension(name: String?): String? = extensionOf(name)?.let { EXTENSION_TYPES[it] }

    /**
     * True when the row can plausibly be read as UTF-8 text.
     *
     * Both signals are needed: providers routinely report `application/octet-stream`
     * for a `.txt` on an SD card, and sometimes report `text/plain` for a binary blob.
     * The caller still has to survive a decode error; this only avoids wasting the
     * read on a 200 MB video.
     */
    fun isTextLike(mime: String?, name: String?): Boolean {
        val m = mime?.trim()?.lowercase()
        if (m != null && (TEXT_PREFIXES.any { m.startsWith(it) } || m in TEXT_EXACT)) return true
        val ext = extensionOf(name) ?: return false
        return EXTENSION_TYPES[ext]?.let { it.startsWith("text/") || it in TEXT_EXACT } == true
    }
}

// ---------------------------------------------------------------------------- rendering

/** Human-readable file sizes, for the observation. */
object FileSize {

    private val UNITS = listOf("B", "KB", "MB", "GB", "TB", "PB")

    /**
     * Formats a byte count. Bounded to four significant characters so that a row of
     * sizes cannot dominate an observation.
     */
    fun format(bytes: Long): String {
        if (bytes < 0L) return "unknown"
        if (bytes < 1024L) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < UNITS.size - 1) {
            value /= 1024.0
            unit++
        }
        val rounded = if (value >= 100) String.format("%.0f", value) else String.format("%.1f", value)
        return "$rounded ${UNITS[unit]}"
    }
}

/**
 * Renders rows into model-visible text.
 *
 * The whole point of this file: the model never sees a JSON array. What it sees is a
 * count, a possibly-truncated list of `name (type, size, date)` lines, and — when the
 * result set was cut — an explicit statement of how much was withheld.
 */
object FileListing {

    /**
     * Ceiling on rows rendered in the observation.
     *
     * Not a guarantee on its own: 40 rows of 160-character names is already over 7 KB.
     * [format] therefore also stops on the character budget, and MAX_ROWS only bounds
     * the common case where names are short.
     */
    const val MAX_ROWS = 40

    /**
     * How much of [OBSERVATION_BUDGET] the row block may consume.
     *
     * The head of the string (count line) and the tail (the "and N more" line) are
     * reserved out of this, so a listing can never be cut by the truncator in a way
     * that hides the fact that rows were withheld.
     */
    private const val ROW_BUDGET = OBSERVATION_BUDGET - 320

    /**
     * @param rows the rows to render, already limited by the caller.
     * @param total exact number of matching rows when known, else null.
     * @param withheld number of matching rows not shown (result set was cut).
     * @param what noun phrase for the empty case, e.g. "documents".
     */
    fun format(
        rows: List<FileRow>,
        total: Int?,
        withheld: Int,
        what: String,
    ): String {
        if (rows.isEmpty()) {
            return if (withheld > 0) {
                "No $what matched within the first $withheld checked."
            } else {
                "No $what matched. Widen the query, drop the date filter, or check the " +
                    "file type — this app can only see documents and downloads it has " +
                    "been granted."
            }
        }

        val sb = StringBuilder()
        val countLine = when {
            withheld > 0 && total != null -> "Showing X of $total $what ($withheld more matched)."
            withheld > 0 -> "Showing the first $total $what."
            else -> "${rows.size} $what."
        }
        // The count line is rendered first with a placeholder so the length is known
        // before the rows are appended, and a second time once the real number of
        // shown rows is known. Building it twice is cheaper than being wrong.
        sb.append(countLine.replace("X", rows.size.toString())).append('\n')

        var shown = 0
        var used = 0
        for (row in rows) {
            if (shown >= MAX_ROWS) break
            val line = "- " + describe(row) + "\n"
            if (used + line.length > ROW_BUDGET) break
            sb.append(line)
            used += line.length
            shown++
        }

        // The withheld tally has to account for the rows the caller's limit dropped AND
        // the rows this renderer dropped, or the model is told a smaller number than
        // was actually hidden.
        val hidden = rows.size - shown + withheld
        if (hidden > 0) {
            sb.append("- …and ").append(hidden).append(" more, not listed.")
        }
        return sb.toString().trimEnd()
    }

    /** One row, one line, no URI: the URI belongs in `data`, and it is long. */
    fun describe(row: FileRow): String {
        val sb = StringBuilder()
        sb.append(if (row.displayName.isBlank()) "(unnamed document)" else row.displayName)
        sb.append(" (").append(MimeTypes.friendly(row.mimeType))
        sb.append(", ").append(FileSize.format(row.sizeBytes))
        val path = row.relativePath
        if (!path.isNullOrBlank()) sb.append(", in ").append(path)
        sb.append(", modified ").append(formatDate(row.modifiedMillis))
        sb.append(')')
        return sb.toString()
    }

    /**
     * Formats a MediaStore timestamp as a plain local date. A zero or missing
     * timestamp renders as "unknown" rather than 1 January 1970, which reads as a
     * real answer to the model.
     */
    fun formatDate(millis: Long): String {
        if (millis <= 0L) return "unknown"
        return try {
            FORMATTER.format(Instant.ofEpochMilli(millis))
        } catch (t: RuntimeException) {
            "unknown"
        }
    }

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
}

// ----------------------------------------------------------------------------- reading

/** What a capped read produced. */
data class DecodedText(
    /** At most [FileText.READ_CAP_BYTES] characters of decoded text. */
    val text: String,
    /** True when the source held more bytes than the cap allowed. */
    val truncated: Boolean,
    /** True when a partial multi-byte character was dropped at the cut point. */
    val droppedPartialChar: Boolean,
)

/**
 * The hard read cap and the truncation marker.
 *
 * 8 KiB is the cap because that is what still fits the job: it is roughly 2 000 tokens,
 * enough for the head of a log, a config file or a document's first page, and small
 * enough that the model is not being handed a wall of text it cannot act on. The
 * observation budget (2 048 chars) is a *separate*, tighter limit applied afterwards —
 * the 8 KiB cap bounds the read and the heap, the 2 048 budget bounds the prompt.
 *
 * The cap is enforced by the number of bytes pulled off the stream, not by truncating
 * the decoded string, so a 1 GB file costs one 8 KiB buffer.
 */
object FileText {

    const val READ_CAP_BYTES = 8 * 1024

    /** The exact wording the model sees, and the substring the tests pin. */
    const val TRUNCATION_MARKER = "truncated"

    /**
     * Reads at most [READ_CAP_BYTES] bytes from [input] and decodes them as UTF-8.
     *
     * One extra byte is requested so that "exactly at the cap" is distinguishable from
     * "truncated" without reading to end-of-stream. The stream is NOT closed here: the
     * caller owns it and closes it in its own `finally`, which is what keeps the
     * close-on-every-path property in one place.
     *
     * On truncation a trailing partial UTF-8 sequence is dropped and reported, so the
     * model is never shown a replacement character standing in for a byte it never got.
     */
    fun readCapped(input: InputStream): DecodedText {
        val buffer = ByteArray(READ_CAP_BYTES + 1)
        var filled = 0
        while (filled < buffer.size) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) break
            filled += read
        }
        val truncated = filled > READ_CAP_BYTES
        val usable = if (truncated) READ_CAP_BYTES else filled
        val decoded = String(buffer, 0, usable, Charsets.UTF_8)

        // A multi-byte character cut at the boundary decodes to U+FFFD at the very end.
        // Drop it, and say so, rather than showing a mangled character.
        val endsPartial = truncated && decoded.isNotEmpty() && decoded.last() == REPLACEMENT
        val text = if (endsPartial) decoded.dropLast(1) else decoded
        return DecodedText(text, truncated, endsPartial)
    }

    private const val REPLACEMENT = '�'

    /**
     * How many characters of file text are actually placed in the observation.
     *
     * Distinct from [READ_CAP_BYTES] on purpose. The read cap is a HEAP and I/O bound:
     * it is the largest string this tool will ever materialise, and it is what the
     * tests pin (1 MB in, 8 KiB out). The preview is a PROMPT bound: the whole
     * observation has to fit the 2048-char budget, header and truncation notice
     * included, so the content gets what is left. If the preview and the read cap
     * were the same number, a file that fills the cap would be silently cut by
     * ObservationTruncator with a generic "…[truncated]" and the tool's own, far more
     * useful truncation notice would never reach the model.
     */
    const val PREVIEW_CHARS = 1400

    /** Header + notice + preview, comfortably inside [OBSERVATION_BUDGET]. */
    const val MAX_OBSERVATION_CHARS = OBSERVATION_BUDGET

    /**
     * The header line stating what is being shown and what was withheld.
     *
     * States BOTH caps, because the model needs to know that what it is reading is a
     * prefix and by how much: the 8 KiB read cap, and the preview cut applied to get
     * below the observation budget.
     *
     * @param name display name of the document.
     * @param totalBytes the file's full size when the provider reported one.
     * @param decoded the capped read.
     */
    fun contentObservation(name: String, totalBytes: Long?, decoded: DecodedText): String {
        val size = if (totalBytes != null && totalBytes > 0) {
            FileSize.format(totalBytes)
        } else {
            "unknown size"
        }
        val preview = if (decoded.text.length > PREVIEW_CHARS) {
            decoded.text.take(PREVIEW_CHARS)
        } else {
            decoded.text
        }
        val previewCut = decoded.text.length - preview.length

        val notices = StringBuilder()
        if (decoded.truncated) {
            val dropped = if (decoded.droppedPartialChar) {
                " A partial character at the cut point was dropped."
            } else {
                ""
            }
            notices.append("Only the first ").append(READ_CAP_BYTES)
                .append(" bytes of this file were read").append(dropped)
        }
        if (previewCut > 0) {
            if (notices.isNotEmpty()) notices.append(' ')
            notices.append("A further ").append(previewCut)
                .append(" characters were held back to stay inside the response budget.")
        }
        val note = if (notices.isEmpty()) "" else "\n[$TRUNCATION_MARKER: ${notices}]"

        return "$name ($size) — ${decoded.text.length} characters read$note\n\n$preview"
    }
}

// -------------------------------------------------------------------------- write plan

/** Where files.write_text is going to write. */
sealed interface WritePlan {
    /** Overwrite an already-granted `content://` document. Needs no permission. */
    data class OverwriteUri(val uri: String) : WritePlan

    /** Create a new document in the Downloads collection. No permission on API 29+. */
    data class CreateInDownloads(val displayName: String, val mimeType: String) : WritePlan

    /**
     * Creating a new file is not possible here: either the platform is too old to do it
     * without a storage permission, or the caller gave a name but no usable URI.
     */
    data class Refused(val reason: String) : WritePlan
}

/** Decides the write strategy. Pure, so the API-level and argument rules are testable. */
object WritePlanner {

    /**
     * @param sdkInt the device's API level.
     * @param uri a `content://` URI, or null.
     * @param name a new filename, or null.
     */
    fun plan(sdkInt: Int, uri: String?, name: String?, content: String): WritePlan {
        if (!uri.isNullOrBlank()) {
            return if (FileArgs.isContentUri(uri)) {
                WritePlan.OverwriteUri(uri)
            } else {
                WritePlan.Refused(
                    "'$uri' is not a content:// URI. This app has no filesystem access; " +
                        "pass a content:// URI obtained from files.search or files.list.",
                )
            }
        }
        if (name.isNullOrBlank()) {
            return WritePlan.Refused(
                "files.write_text needs either 'uri' (a content:// document to overwrite) " +
                    "or 'name' (a new file to create).",
            )
        }
        val safeName = sanitiseFileName(name)
        if (safeName == null) {
            return WritePlan.Refused(
                "'$name' is not a usable filename: it is empty after trimming, or contains " +
                    "a path separator.",
            )
        }
        if (sdkInt >= Build.VERSION_CODES.Q) {
            return WritePlan.CreateInDownloads(safeName, MimeTypes.fromExtension(safeName) ?: "text/plain")
        }
        return WritePlan.Refused(
            "Creating a new file needs Android 10 or newer on this build " +
                "(this device is API $sdkInt), or a content:// URI the user picked. " +
                "Pass 'uri' to overwrite a document instead.",
        )
    }

    /**
     * Strips any directory component from a proposed filename.
     *
     * A model asked to "save to Documents/notes.txt" will pass the whole path. Writing
     * it verbatim into a MediaStore DISPLAY_NAME produces a document literally called
     * "Documents/notes.txt", which is a trap for every later listing. Anything with a
     * separator is reduced to its last component; a name that is *only* separators
     * yields null.
     */
    fun sanitiseFileName(name: String?): String? {
        val raw = name?.trim()?.take(FileArgs.MAX_WRITE_NAME_CHARS) ?: return null
        if (raw.isEmpty()) return null
        val last = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (last.isEmpty() || last == "." || last == "..") return null
        // Control characters and NUL in a filename break providers and log output.
        return last.filter { it >= ' ' && it != '' }.takeIf { it.isNotEmpty() }
    }
}

// ---------------------------------------------------------------------- delete safety

/** The outcome of the delete pre-flight check. */
sealed interface DeleteDecision {
    data class Allowed(val uri: String, val row: FileRow?) : DeleteDecision
    data class Refused(val reason: String) : DeleteDecision
}

/**
 * The pre-flight for a DESTRUCTIVE operation.
 *
 * The one rule: exactly one identified file, or nothing. There is no directory
 * deletion and there is no glob expansion, and this object is where that is enforced
 * rather than merely intended. A `content://.../tree/...` URI names a *directory*;
 * deleting one wipes a granted tree. A `*` in a name means the caller believed there
 * was more than one match, which is a search, not a delete.
 */
object DeleteGuard {

    /** SAF directory grants carry this segment. A tree URI is never deletable here. */
    const val TREE_SEGMENT = "/tree/"

    /** Characters that mean the caller is describing a set, not a file. */
    private val GLOB_CHARS = charArrayOf('*', '?', '[', ']')

    /** True when the URI names a directory grant rather than a single document. */
    fun isDirectoryUri(uri: String?): Boolean {
        val u = uri?.trim() ?: return false
        return u.contains(TREE_SEGMENT) || u.endsWith("/") || u.contains("%2Ftree") || u.endsWith(":root:")
    }

    /** True when a name contains a glob metacharacter. */
    fun hasGlob(name: String?): Boolean {
        val n = name ?: return false
        return GLOB_CHARS.any { n.contains(it) }
    }

    /**
     * Decides whether a delete may proceed.
     *
     * @param uri an explicit `content://` URI, or null to resolve from [candidates].
     * @param candidates the rows a search returned. More than one is a refusal: the
     *   caller must disambiguate, because picking the first match of a multi-match
     *   search is how the wrong document gets deleted.
     */
    fun plan(uri: String?, candidates: List<FileRow>): DeleteDecision {
        if (!uri.isNullOrBlank()) {
            if (!FileArgs.isContentUri(uri)) {
                return DeleteDecision.Refused(
                    "'$uri' is not a content:// URI. There is no filesystem delete here.",
                )
            }
            if (isDirectoryUri(uri)) {
                return DeleteDecision.Refused(
                    "That URI names a directory, not a file. files.delete removes exactly " +
                        "one document; delete the individual files instead.",
                )
            }
            if (hasGlob(uri)) {
                return DeleteDecision.Refused(
                    "That URI contains a wildcard. Pass one specific document URI.",
                )
            }
            return DeleteDecision.Allowed(uri, null)
        }

        if (candidates.isEmpty()) {
            return DeleteDecision.Refused("No document matched, so there is nothing to delete.")
        }
        if (candidates.size > 1) {
            val names = candidates.take(5).joinToString(", ") { it.displayName }
            val more = if (candidates.size > 5) " (+${candidates.size - 5} more)" else ""
            return DeleteDecision.Refused(
                "${candidates.size} documents matched ($names$more). Re-run with an exact " +
                    "name or a uri so exactly one file is deleted.",
            )
        }
        val only = candidates.first()
        if (hasGlob(only.displayName)) {
            return DeleteDecision.Refused(
                "'${only.displayName}' contains a wildcard; pass one specific document URI.",
            )
        }
        if (isDirectoryUri(only.uri)) {
            return DeleteDecision.Refused("That row is a directory, not a file.")
        }
        return DeleteDecision.Allowed(only.uri, only)
    }
}

// =====================================================================================
// The tools
// =====================================================================================

private fun jsonOf(vararg pairs: Pair<String, String?>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) if (v != null) put(k, v)
}

private fun JsonObject.str(name: String): JsonElement? = this[name]

private fun ok(observation: String, data: JsonObject? = null) =
    ToolResult(success = true, observation = observation, data = data)

private fun fail(observation: String, error: ToolError) =
    ToolResult(success = false, observation = observation, error = error)

/**
 * The one denial shape for the whole family.
 *
 * Shared because five tools that each invented their own sentence is how four
 * of them ended up saying "grant the storage permission" — which on Android 13+
 * names a permission that does not exist for documents, sending the user to a
 * Settings screen where the toggle they need is not.
 *
 * ## Why it appends [SAF_IS_PER_DOCUMENT]
 *
 * `PermissionDenial.observation` is the shared renderer and it is correct as far
 * as it goes, but it cannot know that the capability it just refused is granted
 * **per document** rather than per app. Without that sentence the model is told
 * "access to your documents is not available to this app" and the natural
 * completion is "you have no documents", which is a false statement about the
 * user's own files — the specific failure this family exists to prevent. Naming
 * the picker is what turns a dead end into something the user can act on.
 */
private fun denied(
    tool: String,
    requirement: dev.localintelligence.core.tool.contracts.PlatformRequirement,
) = fail(
    PermissionDenial.observation(tool, requirement) + SAF_IS_PER_DOCUMENT,
    ToolError.PermissionDenied(PermissionDenial.summary(requirement)),
)

/**
 * The one fact about document access that a permission-shaped sentence cannot
 * carry, because it is not about a permission.
 *
 * SAF grants are per-URI: the user hands over one file (or one tree) at a time
 * through the system picker, and there is no app-wide switch to flip afterwards.
 * So this app cannot pre-check whether a *particular* document is readable, and
 * a refusal here means "nothing this app can reach right now", never "you have
 * no documents".
 */
private const val SAF_IS_PER_DOCUMENT: String =
    " Note: on Android this is granted per document, not per app — the system " +
        "picker hands over one file or folder at a time and there is no app-wide " +
        "switch. Do not tell the user they have no documents; they have documents " +
        "this app was simply not given."

/** Documents visible to this app, newest first. */
class FilesListTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolMeta.FILES_LIST.define(
        schema = ToolSchemas.filesList,
        risk = ToolRisk.READ_ONLY,
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("files.list") {
            // The decisive question. On Android 13+ NO runtime permission lets
            // this app read a document the user has not handed over, so the
            // honest answer is a refusal that explains the file picker rather
            // than a "No documents matched." line the model would report to the
            // user as "you have no documents".
            if (!grant.isGranted(ToolPermissions.DOCUMENT_READ)) {
                return@guard denied("files.list", ToolPermissions.DOCUMENT_READ)
            }
            val limit = FileArgs.limit(args.str("limit"))
            val rows = readRows(appContext, limit = limit)
            if (rows == null) {
                return@guard fail(
                    "The document index is not available right now. Try again shortly.",
                    ToolError.Unavailable("MediaStore query returned null"),
                )
            }
            ok(
                FileListing.format(rows.items, rows.matched, rows.withheld, "documents"),
                jsonOf("count" to rows.items.size.toString(), "withheld" to rows.withheld.toString()),
            )
        }
}

/** Search documents by name, MIME type and modification window. */
class FilesSearchTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolMeta.FILES_SEARCH.define(
        schema = ToolSchemas.filesSearch,
        risk = ToolRisk.READ_ONLY,
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("files.search") {
            // Platform truth, not the unmaintained context flag. A search that
            // silently matches nothing is indistinguishable from a search over
            // an index this app cannot see, and the model will say the file
            // does not exist.
            if (!grant.isGranted(ToolPermissions.DOCUMENT_READ)) {
                return@guard denied("files.search", ToolPermissions.DOCUMENT_READ)
            }
            val query = FileArgs.optionalString(args.str("query"), FileArgs.MAX_NAME_CHARS)
            val mime = FileArgs.optionalString(args.str("mime"), FileArgs.MAX_MIME_CHARS)
            val from = FileArgs.epochMillis(args.str("modified_after"))
            val to = FileArgs.epochMillis(args.str("modified_before"))

            if (query == null && mime == null && from == null && to == null) {
                return@guard fail(
                    "files.search needs at least one of 'query', 'mime', 'modified_after' or " +
                        "'modified_before'. An unfiltered search would return everything.",
                    ToolError.InvalidArguments("no filter supplied"),
                )
            }
            if (from != null && to != null && to <= from) {
                return@guard fail(
                    "'modified_before' (${FileListing.formatDate(to)}) is not after " +
                        "'modified_after' (${FileListing.formatDate(from)}), so no file can match.",
                    ToolError.InvalidArguments("inverted date window"),
                )
            }

            val limit = FileArgs.limit(args.str("limit"))
            val rows = readRows(appContext, limit = limit, query = query, mime = mime, from = from, to = to)
            if (rows == null) {
                return@guard fail(
                    "The document index is not available right now. Try again shortly.",
                    ToolError.Unavailable("MediaStore query returned null"),
                )
            }
            val what = buildString {
                append("document")
                if (query != null) append(" matching '$query'")
                if (mime != null) append(" of type $mime")
            }
            ok(
                FileListing.format(rows.items, rows.matched, rows.withheld, what),
                jsonOf("count" to rows.items.size.toString(), "withheld" to rows.withheld.toString()),
            )
        }
}

/** Read a text document, hard-capped. */
class FilesReadTextTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolMeta.FILES_READ_TEXT.define(
        schema = ToolSchemas.filesReadText,
        risk = ToolRisk.READ_ONLY,
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("files.read_text") {
            if (!grant.isGranted(ToolPermissions.DOCUMENT_READ)) {
                return@guard denied("files.read_text", ToolPermissions.DOCUMENT_READ)
            }
            val raw = FileArgs.stringOrNull(args.str("uri"))
            if (raw == null) {
                return@guard fail(
                    "files.read_text needs a 'uri' (a content:// document URI from files.list " +
                        "or files.search).",
                    ToolError.InvalidArguments("missing uri"),
                )
            }
            if (!FileArgs.isContentUri(raw)) {
                return@guard fail(
                    "'${raw.take(120)}' is not a content:// URI. This app cannot read " +
                        "filesystem paths; use a URI from files.search.",
                    ToolError.InvalidArguments("uri is not a content:// URI"),
                )
            }
            val uri = Uri.parse(raw)

            // Ask the provider what the document is before reading it. Cheap, and it
            // turns a 200 MB video into one sentence instead of 8 KB of binary noise.
            val meta = withContext(Dispatchers.IO) { describeDocument(appContext, uri) }
            if (meta != null && !MimeTypes.isTextLike(meta.mime, meta.name)) {
                return@guard fail(
                    "${meta.name} is ${MimeTypes.friendly(meta.mime)}, not text. " +
                        "files.read_text only reads UTF-8 text documents.",
                    ToolError.InvalidArguments("document is ${meta.mime}"),
                )
            }
            if (context.signal.isCancelled()) {
                return@guard fail("Cancelled before reading.", ToolError.Cancelled("cancelled"))
            }

            val decoded = withContext(Dispatchers.IO) {
                val stream = try {
                    appContext.contentResolver.openInputStream(uri)
                } catch (t: SecurityException) {
                    return@withContext null
                } ?: return@withContext null
                try {
                    FileText.readCapped(stream)
                } finally {
                    runCatching { stream.close() }
                }
            } ?: return@guard fail(
                "Could not open ${meta?.name ?: "that document"}. It may have been deleted, " +
                    "or this app was not granted access. Re-run files.search.",
                ToolError.NotFound("openInputStream returned null or was denied"),
            )

            ok(
                FileText.contentObservation(meta?.name ?: "document", meta?.size, decoded),
                jsonOf(
                    "truncated" to decoded.truncated.toString(),
                    "chars" to decoded.text.length.toString(),
                ),
            )
        }
}

/** Write text to a document. */
class FilesWriteTextTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolMeta.FILES_WRITE_TEXT.define(
        schema = ToolSchemas.filesWriteText,
        // DESTRUCTIVE, escalated from REVERSIBLE, and the catalogue agrees.
        //
        // The `name` branch (create a new file in Downloads) is genuinely
        // reversible. The `uri` branch is not: it overwrites an existing
        // document and the previous contents are unrecoverable — no trash, no
        // undo, no backup. `files.delete`, which is strictly less destructive
        // because at least the file is visibly gone, is already DESTRUCTIVE, so
        // leaving this one at REVERSIBLE meant a silent overwrite auto-executed
        // with no confirmation at all.
        //
        // Schema and escalation note: `content` alone is a creation and is safe.
        // Supplying `uri` alongside it is the destructive act, and the runtime
        // gates on the tool tier, so the confirmation covers both.
        risk = ToolRisk.DESTRUCTIVE,
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("files.write_text") {
            if (!grant.isGranted(ToolPermissions.DOCUMENT_WRITE)) {
                return@guard denied("files.write_text", ToolPermissions.DOCUMENT_WRITE)
            }
            val content = FileArgs.optionalString(args.str("content"), FileArgs.MAX_WRITE_CHARS)
            if (content == null) {
                return@guard fail(
                    "files.write_text needs a non-empty 'content' string.",
                    ToolError.InvalidArguments("missing or empty content"),
                )
            }
            val plan = WritePlanner.plan(
                sdkInt = Build.VERSION.SDK_INT,
                uri = FileArgs.stringOrNull(args.str("uri")),
                name = FileArgs.optionalString(args.str("name"), FileArgs.MAX_WRITE_NAME_CHARS),
                content = content,
            )
            when (plan) {
                is WritePlan.Refused -> return@guard fail(plan.reason, ToolError.Unavailable(plan.reason.take(80)))
                is WritePlan.OverwriteUri -> {
                    val written = withContext(Dispatchers.IO) { overwrite(appContext, Uri.parse(plan.uri), content) }
                    if (!written) {
                        return@guard fail(
                            "Could not open that document for writing. It may be read-only, " +
                                "deleted, or not granted to this app.",
                            ToolError.Unavailable("openOutputStream failed"),
                        )
                    }
                    ok("Wrote ${content.length} characters to ${plan.uri.take(120)}.")
                }
                is WritePlan.CreateInDownloads -> {
                    val written = withContext(Dispatchers.IO) { createInDownloads(appContext, plan, content) }
                    if (!written) {
                        return@guard fail(
                            "Could not create '${plan.displayName}' in Downloads. The storage " +
                                "provider rejected the insert.",
                            ToolError.Unavailable("MediaStore insert failed"),
                        )
                    }
                    ok("Created ${plan.displayName} in Downloads with ${content.length} characters.")
                }
            }
        }
}

/** Delete exactly one document. */
class FilesDeleteTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolMeta.FILES_DELETE.define(
        schema = ToolSchemas.filesDelete,
        risk = ToolRisk.DESTRUCTIVE,
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        ToolSafety.guard("files.delete") {
            if (!grant.isGranted(ToolPermissions.DOCUMENT_WRITE)) {
                return@guard denied("files.delete", ToolPermissions.DOCUMENT_WRITE)
            }
            val explicitUri = FileArgs.contentUri(args.str("uri"))
            val rawUri = FileArgs.stringOrNull(args.str("uri"))
            if (rawUri != null && explicitUri == null) {
                return@guard fail(
                    "'${rawUri.take(120)}' is not a content:// URI. files.delete operates on " +
                        "a single SAF document, not a filesystem path.",
                    ToolError.InvalidArguments("uri is not a content:// URI"),
                )
            }
            val name = FileArgs.optionalString(args.str("name"), FileArgs.MAX_NAME_CHARS)
            if (explicitUri == null && name == null) {
                return@guard fail(
                    "files.delete needs a 'uri' or an exact 'name'. It removes one file and " +
                        "refuses to guess which of several matches to delete.",
                    ToolError.InvalidArguments("neither uri nor name supplied"),
                )
            }

            // Resolve the target before deciding, so a bare name can be checked for
            // ambiguity instead of deleting whichever row happened to come back first.
            val candidates = if (explicitUri == null) {
                readRows(appContext, limit = FileArgs.MAX_LIMIT, query = name)?.items.orEmpty()
            } else {
                emptyList()
            }

            val decision = DeleteGuard.plan(explicitUri, candidates)
            if (decision is DeleteDecision.Refused) {
                val error = if (decision.reason.startsWith("No document")) {
                    ToolError.NotFound(decision.reason.take(80))
                } else {
                    ToolError.InvalidArguments(decision.reason.take(80))
                }
                return@guard fail(decision.reason, error)
            }
            val allowed = decision as DeleteDecision.Allowed
            val deleted = withContext(Dispatchers.IO) {
                appContext.contentResolver.delete(Uri.parse(allowed.uri), null, null)
            }
            if (deleted <= 0) {
                return@guard fail(
                    "The provider refused to delete ${allowed.row?.displayName ?: allowed.uri.take(80)}. " +
                        "It may be read-only or not owned by this app.",
                    ToolError.Unavailable("ContentResolver.delete returned $deleted"),
                )
            }
            ok("Deleted ${allowed.row?.displayName ?: allowed.uri.take(80)}.")
        }
}

// ---------------------------------------------------------------------------- plumbing

/** A materialised, already-capped result set. */
private class RowPage(
    val items: List<FileRow>,
    /** How many rows the provider returned before the caller's limit was applied. */
    val matched: Int,
    /** How many of those were dropped for the limit. */
    val withheld: Int,
)

/**
 * Runs one bounded MediaStore query and materialises at most `limit` rows.
 *
 * The provider is asked for `MAX_LIMIT + 1` rows so that "there are more" is
 * detectable without a second COUNT query: if [materialise] hit its own hard cap, the
 * result set was at least that large. `withheld` is therefore a lower bound when the
 * cap was reached, and the observation says "at least N" in that case.
 *
 * The cursor is always closed. A leaked Cursor pins a CursorWindow, which on some OEM
 * MediaStore builds is a direct mmap of an index file — on a phone, that is a leak the
 * user experiences as the app being killed.
 */
private suspend fun readRows(
    context: Context,
    limit: Int,
    query: String? = null,
    mime: String? = null,
    from: Long? = null,
    to: Long? = null,
): RowPage? = withContext(Dispatchers.IO) {
    val selection = FileQuery.buildSelection(query = query, mime = mime, fromMillis = from, toMillis = to)
    val cursor = context.contentResolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
        FileQuery.projection(Build.VERSION.SDK_INT).toTypedArray(),
        selection.selection,
        selection.args.toTypedArray(),
        FileQuery.ORDER_BY,
    ) ?: return@withContext null
    try {
        val rows = materialise(cursor, FileArgs.MAX_LIMIT)
        val matched = rows.size
        RowPage(rows.take(limit), matched, (matched - limit).coerceAtLeast(0))
    } finally {
        runCatching { cursor.close() }
    }
}

/** Column indices for a projection, resolved once per cursor. */
private class FileColumns(cursor: android.database.Cursor, sdkInt: Int) {
    private val modern = sdkInt >= Build.VERSION_CODES.Q
    val id = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
    val name = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
    val mime = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
    val size = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
    val modified = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
    val path = if (modern) cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH) else -1
}

private fun android.database.Cursor.stringAt(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index).takeIf { it.isNotBlank() }

private fun android.database.Cursor.longAt(index: Int): Long =
    if (index < 0 || isNull(index)) -1L else getLong(index)

/**
 * Materialises at most [hardCap] rows from an open cursor.
 *
 * The cap is the loop bound, so a provider that offers a million rows costs
 * `hardCap` iterations and `hardCap` [FileRow] allocations — the cursor itself is
 * left to be closed by the caller. A row missing an id is skipped rather than
 * aborting the whole listing: one malformed index entry must not empty the result.
 */
private fun materialise(cursor: android.database.Cursor, hardCap: Int): List<FileRow> {
    if (cursor.count == 0) return emptyList()
    val columns = FileColumns(cursor, Build.VERSION.SDK_INT)
    val out = ArrayList<FileRow>(minOf(hardCap, 64))
    while (cursor.moveToNext() && out.size < hardCap) {
        val id = cursor.longAt(columns.id)
        if (id < 0L) continue
        val modifiedSeconds = cursor.longAt(columns.modified)
        out += FileRow(
            id = id,
            displayName = (cursor.stringAt(columns.name) ?: "(unnamed document)")
                .take(FileArgs.MAX_NAME_CHARS),
            mimeType = cursor.stringAt(columns.mime),
            sizeBytes = cursor.longAt(columns.size),
            // MediaStore stores seconds; a zero/absent timestamp stays 0 so the
            // renderer prints "unknown" instead of 1 January 1970.
            modifiedMillis = if (modifiedSeconds > 0L) modifiedSeconds * 1000L else 0L,
            relativePath = cursor.stringAt(columns.path),
            uri = Uri.withAppendedPath(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                id.toString(),
            ).toString(),
        )
    }
    return out
}

/** `OpenableColumns` metadata for one document. */
private class DocumentMeta(val name: String?, val mime: String?, val size: Long?)

private fun describeDocument(context: Context, uri: Uri): DocumentMeta? {
    val projection = arrayOf(
        android.provider.OpenableColumns.DISPLAY_NAME,
        android.provider.OpenableColumns.SIZE,
        "android.intent.extra.MIME_TYPE",
    )
    val cursor = try {
        context.contentResolver.query(uri, projection, null, null, null)
    } catch (t: Exception) {
        null
    } ?: return null
    return try {
        if (!cursor.moveToFirst()) return null
        val name = cursor.stringAt(cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME))
        val size = columnsLongOrNull(cursor, android.provider.OpenableColumns.SIZE)
        val mime = cursor.stringAt(
            cursor.getColumnIndex("android.intent.extra.MIME_TYPE"),
        ) ?: context.contentResolver.getType(uri)
        DocumentMeta(name?.take(FileArgs.MAX_NAME_CHARS), mime, size)
    } finally {
        runCatching { cursor.close() }
    }
}

private fun columnsLongOrNull(cursor: android.database.Cursor, name: String): Long? {
    val i = cursor.getColumnIndex(name)
    return if (i < 0 || cursor.isNull(i)) null else cursor.getLong(i).takeIf { it >= 0L }
}

/**
 * Overwrites a document through SAF. Returns false when the provider refused.
 *
 * The stream is closed in a `finally`; a failed write on a throwaway Uri can otherwise
 * leak a ParcelFileDescriptor, and Android counts those against a per-process limit.
 */
private fun overwrite(context: Context, uri: Uri, content: String): Boolean {
    val stream = try {
        context.contentResolver.openOutputStream(uri, "wt")
    } catch (t: Exception) {
        return false
    } ?: return false
    return try {
        stream.write(content.toByteArray(Charsets.UTF_8))
        stream.flush()
        true
    } catch (t: Exception) {
        false
    } finally {
        runCatching { stream.close() }
    }
}

/**
 * Creates a document in the Downloads collection and writes to it.
 *
 * The two-phase IS_PENDING dance is required from API 29: a document with
 * IS_PENDING=1 is invisible to other apps, so a reader can never observe a
 * half-written file. If the write throws, the pending row is deleted in a `finally`
 * rather than being left behind as a permanent zero-byte ghost.
 */
private fun createInDownloads(context: Context, plan: WritePlan.CreateInDownloads, content: String): Boolean {
    // `MediaStore.Downloads` is API 29. The SDK_INT guard below protected only
    // the two *fields* inside the ContentValues, while the TYPE REFERENCE and
    // `EXTERNAL_CONTENT_URI` on the line after it were outside the guard — so on
    // API 26-28 this method reached a class member that does not exist there.
    // The surrounding `catch (t: Exception)` cannot save it: a missing static
    // field raises NoSuchFieldError, which is an Error and not an Exception, so
    // it escaped and took the run down.
    //
    // minSdk is 26, so this is a real crash on 26-28 rather than a theoretical
    // one. Early-return is the honest fix: this whole strategy needs API 29, and
    // the caller already has a SAF path for older devices.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, plan.displayName)
        put(MediaStore.Downloads.MIME_TYPE, plan.mimeType)
        // No guard needed any more: the early return above means Q is certain.
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (t: Exception) {
        null
    } ?: return false

    var ok = false
    try {
        val stream = resolver.openOutputStream(uri, "wt") ?: return false
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            stream.flush()
            ok = true
        } finally {
            runCatching { stream.close() }
        }
    } finally {
        if (ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
        } else if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
        }
    }
    return ok
}

/** Every file tool, in registry order. */
fun fileTools(context: Context, grant: PlatformGrant): List<AgentTool> = listOf(
    FilesListTool(context, grant),
    FilesSearchTool(context, grant),
    FilesReadTextTool(context, grant),
    FilesWriteTextTool(context, grant),
    FilesDeleteTool(context, grant),
)
