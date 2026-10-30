package dev.localintelligence.app.settings

import dev.localintelligence.core.settings.CoreSettings

/**
 * The one answer to "what n_ctx does this model get on this phone".
 *
 * The RAM gate, the Hub's fit check and the backend load all call this (the load
 * through `ModelSpec.contextLength`), so what is priced is what is allocated.
 *
 * - Technical mode with a chosen length: that length.
 * - Otherwise ([CoreSettings.CONTEXT_AUTO], which is also what Simple mode reads when
 *   the table has no override): the Simple table for this phone.
 * - Never above what the model was trained for.
 * - LiteRT-LM keeps [LITERT_LM_CONTEXT]: its context is fixed in the file.
 */
object ContextLength {
    /** LiteRT-LM models are exported with a fixed window; the setting cannot change it. */
    const val LITERT_LM_CONTEXT = 4096

    /** The smallest option the setting offers, so a tiny trained length never goes below a usable window. */
    private const val FLOOR = 2048

    /**
     * @param setting the raw [CoreSettings.CONTEXT_LENGTH] value as the store returns it.
     * @param trainedContext the model's own context length from its header, when known.
     */
    fun resolve(setting: String, facts: DeviceFacts, trainedContext: Int?, liteRtLm: Boolean = false): Int {
        if (liteRtLm) return LITERT_LM_CONTEXT
        val chosen = setting.toIntOrNull()?.takeIf { it > 0 } ?: SimpleSetup.defaults(facts).contextLength
        val trained = trainedContext?.takeIf { it > 0 }
        return if (trained == null) chosen else minOf(chosen, maxOf(trained, FLOOR))
    }
}
