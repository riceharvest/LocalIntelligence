package dev.localintelligence.app.settings

import android.app.ActivityManager
import android.content.Context
import dev.localintelligence.android.documents.DEFAULT_EMBEDDING_FILE
import dev.localintelligence.android.documents.DEFAULT_EMBEDDING_REPO
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
    /** Measured results bundled with the app. Empty means Simple mode uses its table alone. */
    val scores: BundledScores = BundledScores.EMPTY,
) {
    companion object {
        fun of(context: Context): DeviceFacts {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            return DeviceFacts(
                info.totalMem,
                Runtime.getRuntime().availableProcessors(),
                readSpeedClass(),
                BundledScores.load(context),
            )
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
        val localName = (repo.split('/') + file.split('/')).joinToString("__")
        return fileName == file || fileName == localName
    }
}

/** The Simple-mode values that depend on RAM class and CPU speed (research-context-defaults.md 3.3). */
data class SimpleDefaults(
    /** Only the n_ctx for a model with no memory estimate; otherwise [ContextLength] decides. */
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

    /**
     * Values for the settings Simple mode hides. Anything absent reads as its default.
     * The context length is absent on purpose: it reads Automatic, which [ContextLength]
     * resolves from the phone and the model instead of a fixed table value.
     */
    fun values(facts: DeviceFacts): Map<String, Any> {
        val d = defaults(facts)
        return mapOf(
            CoreSettings.WORKING_TOKEN_LIMIT.key to d.workingTokenLimit,
            CoreSettings.MAX_OUTPUT_TOKENS.key to d.maxOutputTokens,
            CoreSettings.MAX_VISIBLE_TOOLS.key to d.maxVisibleTools,
            CoreSettings.OBSERVATION_BUDGET.key to d.observationBudget,
            CoreSettings.MEMORY_RESULTS.key to d.memoryResults,
            // Simple mode runs the ordinary tiers without the prompts (see CoreSettings, Approvals).
            // The guards in RiskPolicy still ask for what the message did not request, a bulk change,
            // a path outside app storage, a bare-link message and the named texts and calls.
            CoreSettings.AUTO_ACCEPT_DESTRUCTIVE.key to true,
            CoreSettings.AUTO_ACCEPT_EXTERNAL.key to true,
            CoreSettings.AUTO_ACCEPT_NETWORK.key to true,
            CoreSettings.AUTO_ACCEPT_IN_BACKGROUND.key to true,
            // The screen stays on while the assistant answers: a Simple user should not have to
            // keep touching the phone during a long answer.
            CoreSettings.KEEP_SCREEN_AWAKE.key to true,
        )
    }

    /** What the prompt may hold, from the context a model was actually loaded with. */
    data class PromptBudget(val workingTokenLimit: Int, val observationBudget: Int)

    /**
     * The prompt budget for a model loaded with [nContext] tokens, Simple mode only.
     *
     * A bigger n_ctx is no use while the working limit stays at the 4K rows' 2600, but a prompt
     * the CPU cannot read in reasonable time is no use either: 65% of the window, capped by
     * what this speed class prefills (2600 / 8000 / 16000 tokens). The table's rows are the
     * floor, so a model that comes out at 4096 keeps what it always had. The observation
     * budget (characters) is 40% of the working limit, in hundreds.
     *
     * Null when the window is unknown: the table's values stand.
     */
    fun promptBudget(facts: DeviceFacts, nContext: Int): PromptBudget? {
        if (nContext <= 0) return null
        val d = defaults(facts)
        val cap = when (facts.speedClass) {
            SpeedClass.SLOW -> 2600
            SpeedClass.MID -> 8000
            SpeedClass.FAST -> 16000
        }
        val share = (0.65 * nContext).toInt()
        val working = maxOf(minOf(share, cap), minOf(d.workingTokenLimit, share))
        val observation = maxOf((working * 0.4).toInt() / 100 * 100, d.observationBudget)
        return PromptBudget(working, observation)
    }

    /** RAM from which the 4B is offered as the opt-in alternative (it needs 3.7-3.9 GiB at 4K-8K context). */
    private const val SPARK_MIN_RAM = (9.5 * GIB).toLong()

    // QAD Q4_0 over Q4_K_M: Q4_0 gets the ARM repack/KleidiAI kernels.
    // MEASURED Pixel 10 Pro, llama-bench 6 threads: pp256 254.6 vs 211.0
    // t/s, tg64 25.3 vs 23.7 t/s; and it is quantisation-aware trained.
    private val LFM3_1_7B = SuggestedModel(
        repo = "LiquidAI/LFM3-1.7B-GGUF",
        file = "LFM3-1.7B-QAD-Q4_0.gguf",
        name = "LFM3 1.7B",
        sizeBytes = 1_049_615_680,
        reason = "Fast on this phone, and good at getting things done in its apps.",
    )

    // QAD Q4_0, like LFM3's: the fastest format on phone CPUs.
    private val LFM2_5_230M = SuggestedModel(
        repo = "LiquidAI/LFM2.5-230M-GGUF",
        file = "LFM2.5-230M-QAD-Q4_0.gguf",
        name = "LFM2.5 230M",
        sizeBytes = 149_081_056,
        reason = "Small enough for this phone's memory. It handles simple requests.",
    )

    /** The smallest starter: what a phone that cannot run the pick is moved to (a 149 MB file). */
    fun smallestStarter(): SuggestedModel = LFM2_5_230M

    /**
     * The device class a measured score is filed under: the RAM band and the CPU speed class the
     * table uses. scripts/run-evals.py and docs/scores/build_scores.py compute the same string,
     * so the thresholds below must stay in step with them.
     */
    fun deviceClass(facts: DeviceFacts): String {
        val ram = facts.totalRamBytes
        val band = when {
            ram < 3.2 * GIB -> "ram<3.2"
            ram < 4.6 * GIB -> "ram3.2-4.6"
            ram < 9.5 * GIB -> "ram4.6-9.5"
            else -> "ram9.5+"
        }
        return "$band/${facts.speedClass.name}"
    }

    /**
     * The model Simple mode suggests. A measured row for this device class wins when one exists;
     * otherwise the RAM table decides, as it always has. Bundled scores are empty today, so the
     * table decides on every phone.
     */
    fun suggestedModel(facts: DeviceFacts): SuggestedModel {
        val model = measuredPick(facts) ?: ramPick(facts)
        // The 4B stays the opt-in on phones with room, whichever model was picked.
        return if (model.file == LFM3_1_7B.file) {
            LFM3_1_7B.copy(alternative = if (facts.totalRamBytes >= SPARK_MIN_RAM) SPARK_4B else null)
        } else {
            model
        }
    }

    /**
     * The best measured model for this device class: highest pass rate, then decode speed. Only
     * the three models Simple mode may offer count, and one this phone's RAM cannot run is never
     * picked. Null when no row matches, so the table decides.
     */
    private fun measuredPick(facts: DeviceFacts): SuggestedModel? {
        val cls = deviceClass(facts)
        val ram = facts.totalRamBytes
        val candidates = listOf(
            LFM3_1_7B to (ram >= LFM3_MIN_RAM),
            LFM2_5_230M to true,
            SPARK_4B to (ram >= SPARK_MIN_RAM),
        )
        val best = facts.scores.rows
            .filter { it.deviceClass == cls && it.runs > 0 }
            .mapNotNull { row ->
                candidates.firstOrNull { (model, fits) ->
                    fits && model.repo.equals(row.repo, ignoreCase = true) && model.file == row.file
                }?.let { row to it.first }
            }
            .maxWithOrNull(
                compareBy<Pair<ScoreRow, SuggestedModel>> { it.first.passRate }
                    .thenBy { it.first.decodeTokPerS ?: 0.0 },
            )
        return best?.second
    }

    /** The RAM table: the suggestion when no measurement exists. */
    private fun ramPick(facts: DeviceFacts): SuggestedModel = when {
        // 3.2 GiB, not 4: LFM3 needs about 1.1 GiB at 4K, which leaves a 3.6 GB
        // phone over 2 GiB for Android. What kept it off those phones was speed:
        // every message re-prefilled the whole conversation at ~11 tok/s. With
        // the prompt now growing only at its end, a message prefills only
        // itself, so the agent model is usable there too.
        facts.totalRamBytes >= LFM3_MIN_RAM -> LFM3_1_7B
        else -> LFM2_5_230M
    }

    /**
     * A model Simple mode may offer. [fitsThisPhone] orders the list; [note] is the one line the
     * card adds about this phone, or null. A model that does not fit gets its real cut-off as a
     * note, never a "too big" claim: the 4B is recommended from 12 GB, and is not refused below it.
     */
    data class RecommendedModel(val model: SuggestedModel, val fitsThisPhone: Boolean, val note: String?)

    /**
     * Every model the Play build's Simple mode may download. The phone's suggestion comes first,
     * then the rest, and a model this phone's RAM cannot run comes last and is marked. The fit
     * here is the same RAM rule the suggestion uses; the Hub's own fit verdict still decides at
     * download time.
     */
    fun recommendedModels(facts: DeviceFacts): List<RecommendedModel> {
        val suggestion = suggestedModel(facts)
        val ram = facts.totalRamBytes
        return listOf(
            RecommendedModel(
                LFM3_1_7B,
                fitsThisPhone = ram >= LFM3_MIN_RAM,
                note = if (ram >= LFM3_MIN_RAM) null else "Too big for this phone.",
            ),
            RecommendedModel(LFM2_5_230M, fitsThisPhone = true, note = null),
            RecommendedModel(
                SPARK_4B,
                fitsThisPhone = ram >= SPARK_MIN_RAM,
                note = if (ram >= SPARK_MIN_RAM) null else "Best on phones with 12 GB or more.",
            ),
        ).sortedWith(
            compareBy<RecommendedModel> { it.model.repo != suggestion.repo || it.model.file != suggestion.file }
                .thenBy { !it.fitsThisPhone },
        )
    }

    /**
     * True when Simple mode may download [repo] / [file]: a recommended model, or the default
     * embedding model that document search needs. Match on both, so another file in an
     * allowed repository is not allowed.
     */
    fun allowsDownload(facts: DeviceFacts, repo: String, file: String): Boolean {
        if (repo.equals(DEFAULT_EMBEDDING_REPO, ignoreCase = true) && file == DEFAULT_EMBEDDING_FILE) return true
        return recommendedModels(facts).any { option ->
            option.model.repo.equals(repo, ignoreCase = true) && option.model.file == file
        }
    }
}
