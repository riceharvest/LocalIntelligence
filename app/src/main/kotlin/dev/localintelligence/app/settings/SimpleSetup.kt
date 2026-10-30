package dev.localintelligence.app.settings

import android.app.ActivityManager
import android.content.Context
import dev.localintelligence.core.settings.CoreSettings

/** How fast this CPU reads a prompt, from its instruction set. The speed test is never run in Simple mode. */
enum class SpeedClass {
    /** Neither dot-product nor int8 matrix multiply (A55-class cores). */
    SLOW,

    /** Dot-product (`asimddp`): Cortex-A76 and newer. */
    MID,

    /** Int8 matrix multiply (`i8mm`): the kernels llama.cpp's Q4_0 repack is built for. */
    FAST,
}

/** What Simple mode decides from: the phone, not the user. */
data class DeviceFacts(
    val totalRamBytes: Long,
    val cpuCores: Int,
    val speedClass: SpeedClass = SpeedClass.MID,
) {
    companion object {
        fun of(context: Context): DeviceFacts {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            return DeviceFacts(info.totalMem, Runtime.getRuntime().availableProcessors(), readSpeedClass())
        }

        /** `/proc/cpuinfo` Features: `i8mm` is fast, `asimddp` is mid, neither is slow. Unreadable: mid. */
        internal fun readSpeedClass(): SpeedClass = try {
            val features = java.io.File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(' ')?.toSet()
            }
            when {
                features == null -> SpeedClass.MID
                "i8mm" in features -> SpeedClass.FAST
                "asimddp" in features -> SpeedClass.MID
                else -> SpeedClass.SLOW
            }
        } catch (_: Exception) {
            SpeedClass.MID
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
    /** An opt-in on phones with room: bigger, slower, and not the default. Shown as a second, smaller card. */
    val alternative: SuggestedModel? = null,
) {
    /**
     * True when [fileName] is this model's file: the Hub's own name, or the local name the
     * downloader saves it under. A downloaded model sits on the phone as `owner__name__file`
     * (GgufQuant.localFileName), so a comparison with [file] alone never finds it, and the
     * suggestion kept offering a download for a model that was already there.
     */
    fun isFile(fileName: String): Boolean {
        val (owner, name) = repo.split('/')
        val localName = listOf(owner, name, file).joinToString("__")
        return fileName == file || fileName == localName
    }
}

/** The Simple-mode values that depend on RAM class and CPU speed (research-context-defaults.md 3.3). */
data class SimpleDefaults(
    val contextLength: Int,
    val workingTokenLimit: Int,
    val maxOutputTokens: Int,
    val maxVisibleTools: Int,
    val observationBudget: Int,
    val memoryResults: Int,
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
    private const val LFM3_MIN_RAM = (3.2 * GIB).toLong()

    private val SPARK_4B = SuggestedModel(
        repo = "XHToken/Spark-X2.6-4B-GGUF",
        file = "Spark-X2.6-4B-Q4_K_M.gguf",
        name = "Spark X2.6 4B",
        sizeBytes = 3_543_441_312,
        reason = "Smarter, about 3x slower.",
    )

    /**
     * The table. RAM classes are by what the phone reports (a "4 GB" phone reports about
     * 3.6 GiB), so the cut-offs sit between the typical figures.
     *
     * The speed class stands in for the speed test, which Simple users never run. The table
     * is the agreed target from the research, not a phone measurement.
     */
    fun defaults(facts: DeviceFacts): SimpleDefaults {
        val ram = facts.totalRamBytes
        val speed = facts.speedClass
        // The working limit of the 4096-context rows is 2600 tokens. WHY: the fixed prefix
        // (system prompt and the native core tool list, about 1,500 tokens) is cached, so a
        // longer prompt costs only its new tokens. 2600 stays under 0.65 x 4096.
        return when {
            ram < 3.2 * GIB -> SimpleDefaults(4096, 2600, 256, 4, 1200, 3)
            ram < 4.6 * GIB ->
                if (speed == SpeedClass.SLOW) SimpleDefaults(4096, 2600, 256, 4, 1200, 3)
                else SimpleDefaults(4096, 2600, 384, 5, 1600, 3)
            speed == SpeedClass.SLOW -> SimpleDefaults(4096, 2600, 256, 5, 1600, 3)
            // 12 GB and up, fast: the roomy row. A slower CPU on a big phone keeps the 6-8 GB row.
            ram >= 9.5 * GIB && speed == SpeedClass.FAST -> SimpleDefaults(8192, 4000, 512, 6, 3000, 5)
            else -> SimpleDefaults(8192, if (speed == SpeedClass.FAST) 4000 else 3000, 512, 6, 2048, 5)
        }
    }

    /** Values for the settings Simple mode hides. Anything absent reads as its default. */
    fun values(facts: DeviceFacts): Map<String, Any> {
        val d = defaults(facts)
        return mapOf(
            CoreSettings.CONTEXT_LENGTH.key to d.contextLength.toString(),
            CoreSettings.WORKING_TOKEN_LIMIT.key to d.workingTokenLimit,
            CoreSettings.MAX_OUTPUT_TOKENS.key to d.maxOutputTokens,
            CoreSettings.MAX_VISIBLE_TOOLS.key to d.maxVisibleTools,
            CoreSettings.OBSERVATION_BUDGET.key to d.observationBudget,
            CoreSettings.MEMORY_RESULTS.key to d.memoryResults,
        )
    }

    fun suggestedModel(facts: DeviceFacts): SuggestedModel = when {
        // 3.2 GiB, not 4: LFM3 needs about 1.1 GiB at 4K, which leaves a 3.6 GB
        // phone over 2 GiB for Android. What kept it off those phones was speed:
        // every message re-prefilled the whole conversation at ~11 tok/s. With
        // the prompt now growing only at its end, a message prefills only
        // itself, so the agent model is usable there too.
        facts.totalRamBytes >= LFM3_MIN_RAM -> SuggestedModel(
            repo = "LiquidAI/LFM3-1.7B-GGUF",
            // QAD Q4_0 over Q4_K_M: Q4_0 gets the ARM repack/KleidiAI kernels.
            // MEASURED Pixel 10 Pro, llama-bench 6 threads: pp256 254.6 vs 211.0
            // t/s, tg64 25.3 vs 23.7 t/s; and it is quantisation-aware trained.
            file = "LFM3-1.7B-QAD-Q4_0.gguf",
            name = "LFM3 1.7B",
            sizeBytes = 1_049_615_680,
            reason = "Fast on this phone, and good at using its apps and tools.",
            // 12 GB and up has room for the 4B: smarter, about 3x slower, so an opt-in
            // (it needs 3.7-3.9 GiB at 4K-8K context).
            alternative = if (facts.totalRamBytes >= 9.5 * GIB) SPARK_4B else null,
        )
        else -> SuggestedModel(
            repo = "LiquidAI/LFM2.5-230M-GGUF",
            // QAD Q4_0, like LFM3's: the fastest format on phone CPUs.
            file = "LFM2.5-230M-QAD-Q4_0.gguf",
            name = "LFM2.5 230M",
            sizeBytes = 149_081_056,
            reason = "Small enough for this phone's memory. It handles simple requests.",
        )
    }
}
