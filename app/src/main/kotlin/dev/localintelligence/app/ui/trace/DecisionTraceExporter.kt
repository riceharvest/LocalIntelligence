package dev.localintelligence.app.ui.trace

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.localintelligence.core.trace.DecisionLine
import dev.localintelligence.core.trace.DecisionTraceJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes a decision trace to a file the user can actually get off the phone.
 *
 * ## WHY THE EXPORT GOES THROUGH A [FileProvider] AND NOT A PLAIN FILE
 *
 * Writing to `filesDir` and handing the user a path accomplishes nothing: the
 * file is app-private, so there is no way to attach it to a bug report, email
 * it, or open it anywhere. `FileProvider` grants a *single* URI to a *single*
 * receiving app for a *single* intent, which is the only mechanism Android
 * offers for exactly this, and it means the trace is shareable without ever
 * becoming world-readable.
 *
 * ## WHY THE FILE IS OVERWRITTEN, NEVER APPENDED
 *
 * One trace file, replaced each export. An append-only file in app storage is a
 * leak with extra steps: it grows on every run, nothing prunes it, and it
 * eventually holds the history of a conversation the user thought they had
 * deleted. The export is an explicit act by the user, and the file it produces
 * should be exactly the run they were looking at.
 *
 * ## THE SUBJECT, STATED PLAINLY
 *
 * The share sheet will send this file to another app, which may be a cloud
 * service. [DecisionTrace]'s default policy keeps no text at all, so the default
 * export is structure and arithmetic - tool names, scores, verdicts, token
 * counts - and carries nothing a user would mind another app reading. That is
 * the reason the body cap defaults to zero, and it is stated on the screen that
 * offers the button.
 */
object DecisionTraceExporter {

    /** The one file, in the app's own cache directory. See the class note. */
    const val FILE_NAME = "agent-decision-trace.jsonl"

    /** `application/x-ndjson`; a `.jsonl` file with no declared type gets "unknown". */
    private const val MIME = "application/x-ndjson"

    /**
     * Writes [lines] and returns an intent that shares them.
     *
     * Returns null rather than throwing when the file cannot be written. A share
     * sheet is a convenience on a debug screen, and a read-only cache directory
     * must not take the screen down with it.
     */
    suspend fun shareIntent(context: Context, lines: List<DecisionLine>): Intent? =
        withContext(Dispatchers.IO) {
            val file = write(context, lines) ?: return@withContext null
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull() ?: return@withContext null
            Intent(Intent.ACTION_SEND).apply {
                type = MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "LocalIntelligence agent decision trace")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Agent decision trace: ${lines.size} records. " +
                        "Tool selection, budget-gate arithmetic, raw model output, " +
                        "parsed actions, tool calls and context lifecycle.",
                )
                // The receiving app gets read access to THIS uri, for THIS
                // intent only. Without the flag the share sheet opens and the
                // chosen app then fails to read the attachment, which presents
                // to the user as a broken export.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

    /**
     * The raw JSON Lines, for a screen that wants to show or copy it.
     *
     * Prefers the single-line-per-record form over pretty printing for the same
     * reason the rest of this artifact is JSON Lines: it is diffable, and a
     * developer comparing two runs is the case this exists for.
     */
    fun render(lines: List<DecisionLine>): String = if (lines.isEmpty()) {
        ""
    } else {
        DecisionTraceJson.encodeAll(lines)
    }

    /** Writes the trace to the cache directory, overwriting any previous one. */
    private fun write(context: Context, lines: List<DecisionLine>): File? = runCatching {
        val dir = File(context.cacheDir, "traces").apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        // Written whole and then moved into place, so a share that starts while
        // the write is in progress cannot read a half-written file.
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeText(render(lines))
        if (file.exists()) file.delete()
        temp.renameTo(file)
        file
    }.getOrNull()
}
