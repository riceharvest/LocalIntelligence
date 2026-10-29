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
            file = "LFM3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1_099_627_808,
            reason = "A 1.7B model at 4-bit, about 1.1 GB: the largest that leaves room on this phone.",
        )
        else -> SuggestedModel(
            repo = "LiquidAI/LFM2.5-230M-GGUF",
            file = "LFM2.5-230M-Q4_K_M.gguf",
            sizeBytes = 153_406_304,
            reason = "A 230M model, about 150 MB, for a phone with under 4 GB of memory.",
        )
    }
}
