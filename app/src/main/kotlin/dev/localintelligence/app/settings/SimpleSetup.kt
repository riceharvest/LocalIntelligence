package dev.localintelligence.app.settings

import android.app.ActivityManager
import android.content.Context
import dev.localintelligence.core.settings.CoreSettings

/** What Simple mode decides from: the phone, not the user. */
data class DeviceFacts(val totalRamBytes: Long, val cpuCores: Int) {
    companion object {
        fun of(context: Context): DeviceFacts {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            return DeviceFacts(info.totalMem, Runtime.getRuntime().availableProcessors())
        }
    }
}

/** A model Simple mode suggests, by Hugging Face repo and file. */
data class SuggestedModel(
    val repo: String,
    val file: String,
    /** What the user sees: "LFM3 1.7B", not the file name with its quant. */
    val name: String,
    val sizeBytes: Long,
    val reason: String,
)

/**
 * Simple mode's choices, made deterministically from [DeviceFacts].
 *
 * These are suggestions, not measurements: no speed or memory figure has been
 * taken on a phone yet (see docs/measurements.md), so the table picks by RAM
 * class and file size and says so.
 */
object SimpleSetup {
    private const val GIB = 1024L * 1024 * 1024

    /** Values for the settings Simple mode hides. Anything absent reads as its default. */
    fun values(facts: DeviceFacts): Map<String, Any> = buildMap {
        // A smaller working window on a small phone: compaction starts sooner,
        // so the KV cache and the prompt stay small.
        if (facts.totalRamBytes < 6 * GIB) put(CoreSettings.WORKING_TOKEN_LIMIT.key, 3000)
    }

    fun suggestedModel(facts: DeviceFacts): SuggestedModel = when {
        facts.totalRamBytes >= 4 * GIB -> SuggestedModel(
            repo = "LiquidAI/LFM3-1.7B-GGUF",
            // QAD Q4_0 over Q4_K_M: Q4_0 gets the ARM repack/KleidiAI kernels.
            // MEASURED Pixel 10 Pro, llama-bench 6 threads: pp256 254.6 vs 211.0
            // t/s, tg64 25.3 vs 23.7 t/s; and it is quantisation-aware trained.
            file = "LFM3-1.7B-QAD-Q4_0.gguf",
            name = "LFM3 1.7B",
            sizeBytes = 1_049_615_680,
            reason = "Fast on this phone, and good at using its apps and tools.",
        )
        else -> SuggestedModel(
            repo = "LiquidAI/LFM2.5-230M-GGUF",
            file = "LFM2.5-230M-Q4_K_M.gguf",
            name = "LFM2.5 230M",
            sizeBytes = 153_406_304,
            reason = "Small enough for this phone's memory. It handles simple requests.",
        )
    }
}
