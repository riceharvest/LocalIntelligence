package dev.localintelligence.app.settings

import dev.localintelligence.android.inference.ModelFootprint
import dev.localintelligence.android.inference.RamEstimate
import dev.localintelligence.core.model.gguf.MemoryEstimate

/**
 * The one answer to "what n_ctx does this model get on this phone".
 *
 * The RAM gate, the Hub's fit check and the backend load all call this (the load
 * through `ModelSpec.contextLength`), so what is priced is what is allocated.
 *
 * - Technical mode with a chosen length: that length.
 * - Otherwise ([dev.localintelligence.core.settings.CoreSettings.CONTEXT_AUTO], which is
 *   also what Simple mode always reads): the largest of [STEPS] that the model's trained
 *   context allows, that its estimated memory (KV cache of the type the load allocates,
 *   including hybrid and sliding-window layers) fits in the phone's usable RAM with
 *   headroom, and that this CPU can read a prompt of in a tolerable time ([speedCap]).
 *   So the context scales with the phone AND the model: a hybrid model with few attention
 *   layers gets a long window where a full-attention one of the same size does not.
 * - No memory estimate (LiteRT-LM, or a header that does not price): the Simple table's value.
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
     * @param setting the raw [dev.localintelligence.core.settings.CoreSettings.CONTEXT_LENGTH]
     *   value as the store returns it.
     * @param trainedContext the model's own context length from its header, when known.
     * @param footprint what the model costs in memory, priced with the cache type the load
     *   will allocate. Null when there is no estimate.
     * @param availableRamBytes the budget the load gate compares against
     *   (`DeviceBudget.availableRamBytes`).
     */
    fun resolve(
        setting: String,
        facts: DeviceFacts,
        trainedContext: Int?,
        footprint: ModelFootprint? = null,
        availableRamBytes: Long = 0L,
        liteRtLm: Boolean = false,
    ): Int {
        if (liteRtLm) return LITERT_LM_CONTEXT
        val trained = trainedContext?.takeIf { it > 0 }
        val chosen = setting.toIntOrNull()?.takeIf { it > 0 }
        if (chosen != null) return clampToTrained(chosen, trained)

        val priced = footprint?.takeIf { hasEstimate(it) }
        if (priced == null || availableRamBytes <= 0L) {
            return clampToTrained(SimpleSetup.defaults(facts).contextLength, trained)
        }

        val budget = (availableRamBytes * RAM_HEADROOM).toLong()
        val cap = minOf(speedCap(facts.speedClass), maxOf(trained ?: Int.MAX_VALUE, FLOOR))
        STEPS.filter { it <= cap }.lastOrNull { priced.totalBytes(it) <= budget }?.let { return it }

        // Not even the smallest step fits (or the model's own window is shorter than it):
        // the largest multiple of 1024 that does, never below the floor. The RAM gate then
        // reports a model that is still too big, as it always did.
        var n = minOf(cap, STEPS.first()) / 1024 * 1024
        while (n > FLOOR && priced.totalBytes(n) > budget) n -= 1024
        return maxOf(n, FLOOR)
    }

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
