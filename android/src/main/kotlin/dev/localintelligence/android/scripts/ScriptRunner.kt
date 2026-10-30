package dev.localintelligence.android.scripts

import android.content.Context
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.IsolateTerminatedException
import androidx.javascriptengine.JavaScriptException
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import androidx.javascriptengine.MemoryLimitExceededException
import androidx.javascriptengine.SandboxDeadException
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs one function from a script plugin, inside the AndroidX JavaScript sandbox.
 *
 * WHAT THE SANDBOX GIVES. The JavaScript runs in a separate process with no
 * network, no file system and no Java bridge, so a script cannot reach the phone
 * even if it is hostile. What it can do is spend CPU and memory, and return a
 * string. The limits below bound those two.
 *
 * ONE ISOLATE PER CALL. Each call gets a fresh isolate, so no global state from
 * one call (a counter, a cache a script keeps in a variable) reaches the next, and
 * a script that wedges its isolate cannot wedge the rest. The sandbox process
 * itself is connected once and shared, because connecting it is the slow part.
 *
 * TIMEOUT. [TIMEOUT_MS] covers the evaluation of main.js and the call together.
 * Closing the isolate in `finally` ends a runaway loop, which is the only way to
 * stop one: the engine has no interrupt.
 *
 * Blocking calls are not used: everything is a ListenableFuture awaited as a
 * coroutine, so the caller's thread is never held.
 */
class ScriptRunner(context: Context) {

    private val appContext = context.applicationContext
    private val connectLock = Mutex()

    @Volatile
    private var sandbox: JavaScriptSandbox? = null

    sealed interface Outcome {
        data class Ok(val text: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** False on a WebView that has no JavaScript sandbox. Script plugins are then listed, and their tools are not offered. */
    fun isSupported(): Boolean = JavaScriptSandbox.isSupported()

    /**
     * Evaluates [source] (the plugin's main.js) in a fresh isolate, then calls
     * [function] with [argsJson] parsed to an object, and returns its result as text.
     *
     * A string result is returned as is. Any other value is JSON-encoded, and
     * `undefined` becomes the empty string. Errors come back as [Outcome.Failed]
     * with the script's own error message, never as an exception.
     */
    suspend fun call(source: String, function: String, argsJson: String): Outcome {
        if (!isSupported()) return Outcome.Failed(UNSUPPORTED_REASON)
        val box = try {
            connect()
        } catch (e: TimeoutCancellationException) {
            return Outcome.Failed("the script sandbox did not start in time.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed("the script sandbox could not start (${e.javaClass.simpleName}).")
        }
        val isolate = try {
            newIsolate(box)
        } catch (e: SandboxDeadException) {
            forget(box)
            return Outcome.Failed(STOPPED_REASON)
        } catch (e: Exception) {
            return Outcome.Failed("the sandbox refused a new run (${e.javaClass.simpleName}).")
        }
        return try {
            run(isolate, source, function, argsJson)
        } finally {
            runCatching { isolate.close() }
        }
    }

    private suspend fun connect(): JavaScriptSandbox = connectLock.withLock {
        sandbox ?: withTimeout(CONNECT_TIMEOUT_MS) {
            JavaScriptSandbox.createConnectedInstanceAsync(appContext).await()
        }.also { sandbox = it }
    }

    private fun forget(box: JavaScriptSandbox) {
        if (sandbox === box) sandbox = null
        runCatching { box.close() }
    }

    private fun newIsolate(box: JavaScriptSandbox): JavaScriptIsolate =
        if (box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) {
            box.createIsolate(IsolateStartupParameters().apply { maxHeapSizeBytes = MAX_HEAP_BYTES })
        } else {
            box.createIsolate()
        }

    private suspend fun run(
        isolate: JavaScriptIsolate,
        source: String,
        function: String,
        argsJson: String,
    ): Outcome = try {
        withTimeout(TIMEOUT_MS) {
            isolate.evaluateJavaScriptAsync(source).await()
            Outcome.Ok(isolate.evaluateJavaScriptAsync(invocation(function, argsJson)).await())
        }
    } catch (e: TimeoutCancellationException) {
        Outcome.Failed("it ran longer than ${TIMEOUT_MS / 1000} seconds and was stopped.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: MemoryLimitExceededException) {
        Outcome.Failed("it used more than ${MAX_HEAP_BYTES / (1024 * 1024)} MB of memory and was stopped.")
    } catch (e: IsolateTerminatedException) {
        Outcome.Failed(STOPPED_REASON)
    } catch (e: JavaScriptException) {
        Outcome.Failed(e.message?.trim()?.take(MAX_ERROR_CHARS)?.takeIf { it.isNotEmpty() } ?: "the script threw an error.")
    } catch (e: Exception) {
        Outcome.Failed("the sandbox failed (${e.javaClass.simpleName}).")
    }

    companion object {
        const val TIMEOUT_MS: Long = 5_000L
        private const val CONNECT_TIMEOUT_MS: Long = 10_000L

        /** Enough for any plausible text tool, far less than the phone's RAM. */
        const val MAX_HEAP_BYTES: Long = 32L * 1024 * 1024

        private const val MAX_ERROR_CHARS = 300

        const val UNSUPPORTED_REASON =
            "this phone's WebView has no JavaScript sandbox, so script plugins cannot run here."
        private const val STOPPED_REASON = "the sandbox stopped it. Try again."

        /**
         * The JavaScript that calls the tool. [function] is a checked identifier
         * (see [ScriptPackages]), so it is safe to place in the source; [argsJson]
         * is passed as a JSON string literal, so no argument can end the expression.
         */
        internal fun invocation(function: String, argsJson: String): String {
            val args = Json.encodeToString(String.serializer(), argsJson)
            return "(function () {" +
                "if (typeof $function !== 'function') throw new Error('main.js has no function $function(args)');" +
                "var r = $function(JSON.parse($args));" +
                "if (typeof r === 'string') return r;" +
                "return r === undefined ? '' : JSON.stringify(r);" +
                "})()"
        }
    }
}

/**
 * Suspends until the future completes, then returns its value or throws its cause.
 *
 * The cancellation hook is set before the listener, so a cancelled caller cancels
 * the evaluation (and the caller's `finally` then closes the isolate). The listener
 * runs on the thread that completes the future, which is the sandbox binder thread.
 */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    val future = this
    cont.invokeOnCancellation { future.cancel(true) }
    future.addListener(
        {
            val result = runCatching { future.get() }
            result.onSuccess { cont.resume(it) }
            result.onFailure { error ->
                cont.resumeWithException(if (error is ExecutionException) error.cause ?: error else error)
            }
        },
        Executor { it.run() },
    )
}
