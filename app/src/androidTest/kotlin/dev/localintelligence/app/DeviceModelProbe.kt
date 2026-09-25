package dev.localintelligence.app

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-side probe, NOT a regression test.
 *
 * WHY: this repo had 2334 JVM tests for an app that could not answer a single
 * question — every one ran on the build machine. This runs on the device,
 * against the real llama.cpp JNI, with a real GGUF file, and prints what
 * actually happened. There is deliberately no assertion on tok/s, because that
 * varies per device: the point is to print reality, not to gate a build on it.
 *
 * Run:  ./gradlew :app:connectedDebugAndroidTest
 * Read: adb logcat -s DeviceProbe
 */
@RunWith(AndroidJUnit4::class)
class DeviceModelProbe {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Copies a pushed GGUF into app storage and returns a file:// URI. */
    private fun stageModel(sourcePath: String): Pair<Uri, Long> {
        val src = File(sourcePath)
        check(src.exists()) { "model not staged at $sourcePath" }
        val dest = File(context.filesDir, "models/${src.name}")
        dest.parentFile?.mkdirs()
        if (!dest.exists() || dest.length() != src.length()) {
            src.copyTo(dest, overwrite = true)
        }
        Log.i(TAG, "staged ${dest.length()} bytes")
        return Uri.fromFile(dest) to dest.length()
    }

    /**
     * The whole point: a real model, loaded by the real backend, generating real
     * tokens on a real CPU. Prints load time, tok/s, stop reason and the text.
     */
    @Test
    fun loadRealModelAndGenerate() = runBlocking {
        val source = InstrumentationRegistry.getArguments()
            .getString("modelPath")
            ?: "/data/local/tmp/models/tinyllama-1.1b.Q4_K_M.gguf"
        val (uri, size) = stageModel(source)

        val container = AppContainer(context)
        val spec = ModelSpec(
            id = uri.toString(),
            displayName = File(source).name,
            sizeBytes = size,
        )

        val loadStart = System.nanoTime()
        container.modelBackend.load(spec)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        Log.i(TAG, "LOADED ok in ${loadMs}ms")

        val caps = container.modelBackend.capabilities
        Log.i(
            TAG,
            "CAPS ctx=${caps.contextLength} tools=${caps.supportsToolCalling} " +
                "grammar=${caps.supportsGrammar}",
        )

        val request = GenerationRequest(
            messages = listOf(
                ChatMessage.System("You are a helpful assistant. Answer in one word."),
                ChatMessage.User("What is the capital of France?"),
            ),
            params = SamplingParams(temperature = 0.0f, maxOutputTokens = 16),
        )

        val genStart = System.nanoTime()
        val result = container.modelBackend.generate(request)
        val genMs = (System.nanoTime() - genStart) / 1_000_000

        Log.i(
            TAG,
            "GENERATED completion=${result.completionTokens} prompt=${result.promptTokens} " +
                "wall=${genMs}ms tok/s=%.2f".format(result.decodeTokensPerSecond),
        )
        Log.i(TAG, "STOP reason=${result.stopReason}")
        Log.i(TAG, "TEXT >>>${result.text}<<<")

        // WHY fail on the REASON: a bare `check(text.isNotBlank())` threw away
        // the one string that says why, which is how an empty-text ERROR cost
        // an hour of guessing.
        if (result.stopReason == StopReason.ERROR) {
            throw AssertionError("generation failed: '${result.text}'")
        }
        check(result.text.isNotBlank()) { "model produced no text" }
        container.modelBackend.unload()
    }

    private companion object {
        const val TAG = "DeviceProbe"
    }
}
