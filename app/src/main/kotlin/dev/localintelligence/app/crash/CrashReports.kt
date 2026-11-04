package dev.localintelligence.app.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.localintelligence.app.BuildConfig
import java.io.File
import java.io.InputStream
import java.time.Instant
import kotlin.concurrent.thread

/**
 * Keeps a record of the crashes and freezes on this phone, so the person can send one to the
 * developer if they choose to. Nothing here touches the network. A report leaves the phone only
 * when the person shares it from the Settings row.
 *
 * WHY THREE KINDS: a Java crash is caught in-process by [install]. An ANR or a native crash kills
 * the process before any handler can run, so [importExitReasons] reads those back from the system's
 * ApplicationExitInfo on the next start. All three are written to one folder in the same format, so
 * the Settings row shows the newest of them and one Delete clears them.
 *
 * WHY NO EXCEPTION MESSAGE: a message can hold a prompt or a tool result. A report keeps exception
 * class names and stack frames, which carry no values. An ANR or native trace is kept as the system
 * wrote it, capped at [TRACE_LIMIT] bytes. The share preview shows the text that would be sent.
 *
 * WHY THE WATERMARK FILE: a system exit record stays in the history for a while. Without a record of
 * how far the last import reached, every start would re-import old ANRs, and a report the person had
 * deleted would come back. The watermark is one timestamp in the same folder, not a setting.
 */
object CrashReports {

    /** The newest reports kept. Each is a few kilobytes; three covers the crash that just happened. */
    const val KEEP = 3

    private const val DIR_NAME = "crash"
    private const val REPORT_SUFFIX = ".txt"
    private const val WATERMARK = ".exit-read-until"

    /** Enough for the thread dump that matters in an ANR, small enough to write during a crash. */
    private const val TRACE_LIMIT = 64 * 1024

    /** The system keeps a bounded history; asking for a fixed window avoids depending on how 0 is read. */
    private const val EXIT_LIMIT = 32

    /** Causes past this depth are cut, so a cycle in the chain cannot loop the handler. */
    private const val MAX_CAUSES = 5

    /** How much of a report the preview shows. The share always sends the whole file. */
    private const val PREVIEW_CHARS = 4000

    private enum class Kind(val fileTag: String, val title: String) {
        JAVA("java", "Crash"),
        ANR("anr", "ANR (app not responding)"),
        NATIVE("native", "Native crash"),
    }

    /**
     * Installs the in-process handler. Call it first in `Application.onCreate`, so a crash while the
     * rest of the app is being built is still written down.
     *
     * The previous handler runs after the write, so the process still dies the way it always did.
     * The write is best effort: a failure here must not hide the crash itself.
     */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { crashed, error ->
            runCatching {
                write(app, Kind.JAVA, System.currentTimeMillis(), "thread: ${crashed.name}\n", error, trace = null)
            }
            if (previous != null) previous.uncaughtException(crashed, error) else error.printStackTrace()
        }
    }

    /**
     * Reads the ANRs and native crashes the system recorded since the last start, on a background
     * thread. Java crashes are skipped here because [install] already wrote them.
     */
    fun importExitReasons(app: Application) {
        // ApplicationExitInfo starts at API 30. Below that there is nothing to read, and the
        // in-process handler still catches Java crashes.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        thread(name = "crash-exit-import", isDaemon = true) {
            runCatching { importFrom(app) }
        }
    }

    /** The newest report on the phone, or null when there is none. Blocking file IO: call it off the main thread. */
    fun newest(context: Context): File? = reports(context).firstOrNull()

    /** The start of the report, for the preview. Blocking file IO: call it off the main thread. */
    fun preview(file: File): String {
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.length <= PREVIEW_CHARS) return text
        return text.take(PREVIEW_CHARS) +
            "\n\n... ${text.length - PREVIEW_CHARS} more characters. The share includes the whole report."
    }

    /** Removes every saved report. The watermark stays, so a deleted ANR is not imported again. */
    fun deleteAll(context: Context) {
        reports(context).forEach { it.delete() }
    }

    /**
     * Opens the share sheet with [file] attached, read-only, for this one share.
     *
     * The authority is the existing export provider, whose paths now include the crash folder. ClipData
     * is set as well as EXTRA_STREAM, because a chooser only passes the grant on when the clip carries the URI.
     */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LocalIntelligence crash report")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** Newest first. The file names start with a zero-padded timestamp, so name order is time order. */
    private fun reports(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(REPORT_SUFFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    private fun importFrom(app: Application) {
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        dir(app).mkdirs()
        val mark = File(dir(app), WATERMARK)
        val since = runCatching { mark.readText().trim().toLong() }.getOrDefault(0L)
        var newest = since
        am.getHistoricalProcessExitReasons(app.packageName, 0, EXIT_LIMIT)
            .sortedBy { it.timestamp }
            .forEach { exit ->
                if (exit.timestamp <= since) return@forEach
                newest = maxOf(newest, exit.timestamp)
                val kind = when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> Kind.ANR
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> Kind.NATIVE
                    else -> return@forEach
                }
                // One bad record must not stop the rest, and the watermark still moves past it.
                runCatching {
                    // WHY no trace for a native crash: a tombstone can hold raw process memory, and here
                    // that memory can hold a prompt. It is also binary, so the preview could not show it.
                    val trace = if (kind == Kind.ANR) exit.traceInputStream?.use { readCapped(it) } else null
                    write(app, kind, exit.timestamp, "description: ${exit.description}\n", error = null, trace = trace)
                }
            }
        if (newest > since) mark.writeText(newest.toString())
    }

    private fun write(
        app: Context,
        kind: Kind,
        time: Long,
        detail: String,
        error: Throwable?,
        trace: String?,
    ) {
        dir(app).mkdirs()
        val text = buildString {
            append("LocalIntelligence crash report\n")
            append("kind: ").append(kind.title).append('\n')
            append("time: ").append(Instant.ofEpochMilli(time)).append('\n')
            append("app: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("flavor: ").append(BuildConfig.FLAVOR).append('\n')
            append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append(detail)
            error?.let { appendThrowable(it) }
            trace?.let { append('\n').append(it) }
        }
        val name = "${time.toString().padStart(13, '0')}-${kind.fileTag}$REPORT_SUFFIX"
        File(dir(app), name).writeText(text)
        reports(app).drop(KEEP).forEach { it.delete() }
    }

    /** Class names and frames only. The message is left out on purpose; see the object's KDoc. */
    private fun StringBuilder.appendThrowable(error: Throwable) {
        generateSequence(error) { it.cause }.take(MAX_CAUSES).forEachIndexed { index, cause ->
            append(if (index == 0) "exception: " else "caused by: ").append(cause.javaClass.name).append('\n')
            cause.stackTrace.forEach { append("\tat ").append(it).append('\n') }
        }
    }

    /** Reads up to [TRACE_LIMIT] bytes. A short read is normal for a stream, so the loop keeps going until full or at end. */
    private fun readCapped(input: InputStream): String {
        val buffer = ByteArray(TRACE_LIMIT)
        var filled = 0
        while (filled < TRACE_LIMIT) {
            val n = input.read(buffer, filled, TRACE_LIMIT - filled)
            if (n < 0) break
            filled += n
        }
        return String(buffer, 0, filled, Charsets.UTF_8)
    }
}
