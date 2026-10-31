package dev.localintelligence.app.settings

import dev.localintelligence.android.inference.ModelFootprint
import dev.localintelligence.android.inference.RamEstimate
import dev.localintelligence.core.model.gguf.KvCacheType
import dev.localintelligence.core.model.gguf.MemoryEstimate
import dev.localintelligence.core.settings.CoreSettings

/** The chosen context window and KV cache storage type for a model on this device. */
data class ContextChoice(
    val nCtx: Int,
    val kvCacheType: KvCacheType,
)

/**
 * The one answer to "what n_ctx and KV cache type does this model get on this phone".
 *
 * The RAM gate, the Hub's fit check and the backend load all call this (the load
 * through `ModelSpec.contextLength` and `ModelSpec.kvCacheType`), so what is priced
 * is what is allocated.
 *
 * - Technical mode with an explicit KV cache type (f16, q8_0, q4_0): that type, and the
 *   window resolved with its footprint.
 * - Automatic KV cache ([CoreSettings.KV_AUTO], the default):
 *   a) resolve the window with an f16 footprint;
 *   b) if that window already equals the cap (speed cap, trained length, or the chosen
 *      length in Technical), use f16;
 *   c) else resolve with q8_0: if q8_0 buys a LARGER step, use q8_0 with that step;
 *      otherwise f16.
 * - No memory estimate (LiteRT-LM, or a header that does not price): f16 and the Simple table's value.
 * - Never above what the model was trained for.
 * - LiteRT-LM keeps [LITERT_LM_CONTEXT]: its context is fixed in the file.
 */
object ContextLength {
    /** LiteRT-LM models are exported with a fixed window; the setting cannot change it. */
    const val LITERT_LM_CONTEXT = 4096

    /** The smallest option the setting offers, so a tiny trained length never goes below a usable window. */
    private const val FLOOR = 2048

    /** The windows Automatic picks from, smallest first. */
    val STEPS = listOf(4096, 8192, 12288, 16384, 24576, 32768)

    /**
     * Share of the phone's usable RAM a model may take. Android kills big apps hard (Xiaomi and
     * Samsung most), so the estimate gets headroom the plain gate does not need.
     */
    const val RAM_HEADROOM = 0.85

    /**
     * Longest window this CPU can fill without a message taking minutes: prefill speed is what
     * the speed class stands for, and a long context is only useful if it can be read.
     */
    fun speedCap(speed: SpeedClass): Int = when (speed) {
        SpeedClass.SLOW -> 4096
        SpeedClass.MID -> 16384
        SpeedClass.FAST -> 32768
    }

    /**
     * Decides both the context window and the KV cache storage type for a model.
     *
     * @param setting the raw [CoreSettings.CONTEXT_LENGTH] value as the store returns it.
     * @param kvSetting the raw [CoreSettings.KV_CACHE_TYPE] value as the store returns it.
     * @param trainedContext the model's own context length from its header, when known.
     * @param footprintFor produces the model's memory footprint for a given cache type.
     * @param availableRamBytes the budget the load gate compares against (`DeviceBudget.availableRamBytes`).
     */
    fun decide(
        setting: String,
        kvSetting: String,
        facts: DeviceFacts,
        trainedContext: Int?,
        footprintFor: (KvCacheType) -> ModelFootprint? = { null },
        availableRamBytes: Long = 0L,
        liteRtLm: Boolean = false,
    ): ContextChoice {
        if (liteRtLm) return ContextChoice(LITERT_LM_CONTEXT, KvCacheType.F16)
        val trained = trainedContext?.takeIf { it > 0 }
        val chosen = setting.toIntOrNull()?.takeIf { it > 0 }
        if (chosen != null) {
            val window = clampToTrained(chosen, trained)
            val type = if (isAuto(kvSetting)) KvCacheType.F16 else KvCacheType.fromId(kvSetting)
            return ContextChoice(window, type)
        }

        val auto = isAuto(kvSetting)
        val targetType = if (auto) KvCacheType.F16 else KvCacheType.fromId(kvSetting)

        if (availableRamBytes <= 0L) {
            return ContextChoice(clampToTrained(SimpleSetup.defaults(facts).contextLength, trained), targetType)
        }

        val budget = (availableRamBytes * RAM_HEADROOM).toLong()
        val cap = minOf(speedCap(facts.speedClass), maxOf(trained ?: Int.MAX_VALUE, FLOOR))

        if (!auto) {
            val priced = footprintFor(targetType)?.takeIf { hasEstimate(it) }
                ?: return ContextChoice(clampToTrained(SimpleSetup.defaults(facts).contextLength, trained), targetType)
            return ContextChoice(resolveWindow(priced, budget, cap), targetType)
        }

        // Automatic KV cache: f16 unless memory limits the context, where q8_0 buys more.
        val f16Footprint = footprintFor(KvCacheType.F16)?.takeIf { hasEstimate(it) }
            ?: return ContextChoice(clampToTrained(SimpleSetup.defaults(facts).contextLength, trained), KvCacheType.F16)

        val windowF16 = resolveWindow(f16Footprint, budget, cap)
        if (windowF16 >= cap) return ContextChoice(windowF16, KvCacheType.F16)

        val q80Footprint = footprintFor(KvCacheType.Q8_0)?.takeIf { hasEstimate(it) }
            ?: return ContextChoice(windowF16, KvCacheType.F16)

        val windowQ80 = resolveWindow(q80Footprint, budget, cap)
        return if (windowQ80 > windowF16) ContextChoice(windowQ80, KvCacheType.Q8_0) else ContextChoice(windowF16, KvCacheType.F16)
    }

    private fun resolveWindow(priced: ModelFootprint, budget: Long, cap: Int): Int {
        STEPS.filter { it <= cap }.lastOrNull { priced.totalBytes(it) <= budget }?.let { return it }

        // Not even the smallest step fits (or the model's own window is shorter than it):
        // the largest multiple of 1024 that does, never below the floor. The RAM gate then
        // reports a model that is still too big, as it always did.
        var n = minOf(cap, STEPS.first()) / 1024 * 1024
        while (n > FLOOR && priced.totalBytes(n) > budget) n -= 1024
        return maxOf(n, FLOOR)
    }

    private fun isAuto(kvSetting: String): Boolean =
        kvSetting == CoreSettings.KV_AUTO || kvSetting == "auto" || kvSetting.isBlank()

    /**
     * Preserved as a wrapper for callers that need only the window size.
     */
    fun resolve(
        setting: String,
        facts: DeviceFacts,
        trainedContext: Int?,
        footprint: ModelFootprint? = null,
        availableRamBytes: Long = 0L,
        liteRtLm: Boolean = false,
        kvSetting: String = CoreSettings.KV_AUTO,
    ): Int = decide(
        setting = setting,
        kvSetting = kvSetting,
        facts = facts,
        trainedContext = trainedContext,
        footprintFor = { type ->
            (footprint as? RamEstimate)?.let { ram -> RamEstimate.from(ram.header, kvCacheType = { type }) } ?: footprint
        },
        availableRamBytes = availableRamBytes,
        liteRtLm = liteRtLm,
    ).nCtx

    private fun clampToTrained(chosen: Int, trained: Int?): Int =
        if (trained == null) chosen else minOf(chosen, maxOf(trained, FLOOR))

    /**
     * Only a complete GGUF header prices a window: tensor table and attention geometry all read.
     * Anything weaker (a LiteRT-LM file, a header with no layer or head counts, whose KV cache
     * would read as zero and so fit any window) has no estimate, and the table's value stands.
     */
    private fun hasEstimate(footprint: ModelFootprint): Boolean =
        (footprint as? RamEstimate)?.estimate()?.confidence == MemoryEstimate.Confidence.MEASURED
}
