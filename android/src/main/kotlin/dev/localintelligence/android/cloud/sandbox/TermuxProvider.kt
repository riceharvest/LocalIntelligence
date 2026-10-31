package dev.localintelligence.android.cloud.sandbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import dev.localintelligence.core.tool.ToolError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ===========================================================================
// Termux on this phone, the free provider. No account and no network: a command goes to Termux
// through its RUN_COMMAND intent, and Termux sends the result back to a receiver in this app.
//
// Facts this file relies on, from Termux's own RUN_COMMAND wiki and TermuxConstants (checked
// 2026-10-31):
//   - The service is com.termux/com.termux.app.RunCommandService, action com.termux.RUN_COMMAND.
//   - The permission com.termux.permission.RUN_COMMAND is protectionLevel "dangerous" in Termux's
//     manifest, so the user must switch it on (Android Settings, Additional permissions). It is
//     checked before every command.
//   - Termux refuses commands unless allow-external-apps=true in ~/.termux/termux.properties.
//     This app cannot read that file, so a refusal shows Termux's own error text.
//   - Background mode returns stdout, stderr and the exit code separately. Termux cuts stdout to
//     100 KB combined, from the start, and reports the original length, so a cut is detected.
//   - The result comes in the PendingIntent's "result" bundle. The PendingIntent is built here,
//     FLAG_ONE_SHOT, with a request code unique per command.
//
// Termux has no sandbox to create or stop: the phone is always "started". A command runs under
// `timeout`, so a runaway process is killed by Termux's own coreutils, not left behind.
// ===========================================================================

/** The id the Choice setting stores for this provider. */
internal const val TERMUX_ID: String = "termux"

private const val TERMUX_PACKAGE = "com.termux"
private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
private const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
private const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"

/** Termux expands a leading $PREFIX/ in the command path to its own prefix folder. */
private const val TERMUX_SH = "\$PREFIX/bin/sh"
private const val TERMUX_TIMEOUT = "\$PREFIX/bin/timeout"

// RUN_COMMAND_SERVICE extras, exactly as TermuxConstants defines them.
private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
private const val EXTRA_PENDING_INTENT = "pendingIntent"

/** Our own extra on the PendingIntent's intent, so the receiver knows which command finished. */
private const val EXTRA_EXECUTION_ID = "dev.localintelligence.sandbox.execution_id"

// The result bundle, exactly as TermuxConstants defines it.
private const val RESULT_BUNDLE = "result"
private const val RESULT_STDOUT = "stdout"
private const val RESULT_STDOUT_LENGTH = "stdout_original_length"
private const val RESULT_STDERR = "stderr"
private const val RESULT_EXIT_CODE = "exitCode"
private const val RESULT_ERR = "err"
private const val RESULT_ERRMSG = "errmsg"

/** Errno.ERRNO_SUCCESS is Activity.RESULT_OK, which is -1. */
private const val ERR_SUCCESS = -1

/** Reads above this are refused rather than cut: a cut file would look complete to the model. */
private const val MAX_TERMUX_READ_BYTES = 64 * 1024

/** The script that writes stdin to a path, creating its folder. The path is an argument, never spliced in. */
private const val WRITE_SCRIPT = """mkdir -p -- "${'$'}(dirname -- "${'$'}1")" && cat > "${'$'}1""""

/** The script that prints a file, or exits 3 when it is over the read cap. */
private const val READ_SCRIPT =
    """if [ "${'$'}(wc -c < "${'$'}1")" -gt $MAX_TERMUX_READ_BYTES ]; then exit 3; fi; cat -- "${'$'}1""""

/** Whether Termux is installed and this app holds the Termux permission. The tools are offered only then. */
internal fun termuxReady(context: Context): Boolean = termuxInstalled(context) && termuxGranted(context)

private fun termuxInstalled(context: Context): Boolean = try {
    @Suppress("DEPRECATION")
    context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}

private fun termuxGranted(context: Context): Boolean =
    context.checkSelfPermission(TERMUX_PERMISSION) == PackageManager.PERMISSION_GRANTED

/** The Termux provider. Built per call from the context; no state is kept between calls. */
internal class TermuxProvider(private val context: Context) : SandboxProvider {
    override suspend fun create(idleMinutes: Int): SandboxInfo = SandboxInfo(TERMUX_ID, "started", null)

    override suspend fun info(id: String): SandboxInfo = SandboxInfo(TERMUX_ID, "started", null)

    override suspend fun start(id: String) = Unit

    override suspend fun stop(id: String) = Unit

    override suspend fun exec(info: SandboxInfo, command: String, timeoutSeconds: Int): ExecResult {
        val run = run(
            path = TERMUX_TIMEOUT,
            args = listOf("-k", "5", timeoutSeconds.toString(), "sh", "-c", command),
            stdin = null,
            waitSeconds = timeoutSeconds + 30,
        )
        val output = buildString {
            append(run.stdout)
            if (run.stderr.isNotEmpty()) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append(run.stderr)
            }
            if (run.stdoutCut) append("\n(Termux kept only the last 100 KB of output.)")
        }
        return ExecResult(exitCode = run.exitCode, output = output)
    }

    override suspend fun writeFile(info: SandboxInfo, path: String, bytes: ByteArray) {
        val run = run(
            path = TERMUX_SH,
            args = listOf("-c", WRITE_SCRIPT, "sh", path),
            stdin = String(bytes, Charsets.UTF_8),
            waitSeconds = WRITE_WAIT_SECONDS,
        )
        if (run.exitCode != 0) {
            throw SandboxFailure(
                ToolError.Unavailable("termux write exit ${run.exitCode}"),
                "Termux could not write $path: ${run.stderr.trim().take(ERROR_CHARS).ifBlank { "exit ${run.exitCode}" }}",
            )
        }
    }

    override suspend fun readFile(info: SandboxInfo, path: String): ByteArray {
        val run = run(
            path = TERMUX_SH,
            args = listOf("-c", READ_SCRIPT, "sh", path),
            stdin = null,
            waitSeconds = WRITE_WAIT_SECONDS,
        )
        return when {
            run.exitCode == 3 || run.stdoutCut -> throw SandboxFailure(
                ToolError.Unavailable("file too large"),
                "$path is larger than ${MAX_TERMUX_READ_BYTES / 1024} KB, which is too large to read through Termux.",
            )
            run.exitCode != 0 -> throw SandboxFailure(
                ToolError.NotFound("termux read exit ${run.exitCode}"),
                "Termux could not read $path: ${run.stderr.trim().take(ERROR_CHARS).ifBlank { "exit ${run.exitCode}" }}",
            )
            else -> run.stdout.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * One command through the RUN_COMMAND intent. Suspends until Termux calls back, or until
     * [waitSeconds] runs out. The request code is unique per call, so each PendingIntent is its own.
     */
    private suspend fun run(path: String, args: List<String>, stdin: String?, waitSeconds: Int): TermuxRun {
        if (!termuxInstalled(context)) {
            throw SandboxFailure(ToolError.Unavailable("termux missing"), "Termux is not installed on this phone.")
        }
        if (!termuxGranted(context)) {
            throw SandboxFailure(
                ToolError.PermissionDenied("termux permission"),
                "Termux will not run commands for this app yet. In Android Settings, open this app's Permissions, " +
                    "then Additional permissions, and turn on Run commands in Termux environment.",
            )
        }
        val id = nextExecutionId.incrementAndGet()
        val waiting = CompletableDeferred<Bundle>()
        TermuxResults.waiting[id] = waiting
        try {
            val receiver = Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_EXECUTION_ID, id)
            val flags = PendingIntent.FLAG_ONE_SHOT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, receiver, flags)
            val command = Intent(TERMUX_ACTION)
                .setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
                .putExtra(EXTRA_COMMAND_PATH, path)
                .putExtra(EXTRA_ARGUMENTS, args.toTypedArray())
                .putExtra(EXTRA_BACKGROUND, true)
                .putExtra(EXTRA_PENDING_INTENT, pending)
            if (stdin != null) command.putExtra(EXTRA_STDIN, stdin)
            try {
                context.startService(command)
            } catch (e: Exception) {
                // A background app can be refused a service start on newer Android. Say what to do, not the exception.
                throw SandboxFailure(
                    ToolError.Unavailable("termux start ${e.javaClass.simpleName}"),
                    "Termux could not start the command. Open this app in the foreground and try again.",
                )
            }
            val bundle = withTimeoutOrNull(waitSeconds * 1000L) { waiting.await() }
                ?: throw SandboxFailure(
                    ToolError.Timeout("termux result"),
                    "Termux did not answer within $waitSeconds seconds. The command may still be running in Termux.",
                )
            return parse(bundle)
        } finally {
            TermuxResults.waiting.remove(id)
        }
    }

    private fun parse(bundle: Bundle): TermuxRun {
        val err = bundle.intOf(RESULT_ERR) ?: ERR_SUCCESS
        if (err != ERR_SUCCESS) {
            val reason = bundle.getString(RESULT_ERRMSG).orEmpty().replace(Regex("\\s+"), " ").trim().take(ERROR_CHARS)
            throw SandboxFailure(
                ToolError.Unavailable("termux error $err"),
                "Termux refused or failed to run the command" + (if (reason.isBlank()) "" else ": $reason") +
                    ". Check that allow-external-apps=true is set in ~/.termux/termux.properties.",
            )
        }
        val stdout = bundle.getString(RESULT_STDOUT).orEmpty()
        val originalLength = bundle.intOf(RESULT_STDOUT_LENGTH) ?: stdout.length
        return TermuxRun(
            exitCode = bundle.intOf(RESULT_EXIT_CODE) ?: -1,
            stdout = stdout,
            stderr = bundle.getString(RESULT_STDERR).orEmpty(),
            stdoutCut = originalLength > stdout.length,
        )
    }

    private companion object {
        val nextExecutionId = AtomicInteger(1000)
        const val WRITE_WAIT_SECONDS = 60
        const val ERROR_CHARS = 160
    }
}

/**
 * A number from the result bundle, whether Termux stored it as an int or as text. Reading only
 * getInt would quietly return the default for a text value, and that would hide a cut or an error.
 */
private fun Bundle.intOf(key: String): Int? {
    val value = get(key) ?: return null
    return (value as? Number)?.toInt() ?: value.toString().trim().toIntOrNull()
}

/** One command's exit code and output, as Termux reported it. */
private class TermuxRun(val exitCode: Int, val stdout: String, val stderr: String, val stdoutCut: Boolean)

/** Commands waiting for Termux to call back, by execution id. An entry lives only while its call runs. */
internal object TermuxResults {
    val waiting = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
}

/**
 * Receives Termux's result for one command. It is declared in the manifest and not exported, so
 * only the PendingIntent this app made can reach it.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra(RESULT_BUNDLE) ?: return
        TermuxResults.waiting.remove(id)?.complete(bundle)
    }
}
