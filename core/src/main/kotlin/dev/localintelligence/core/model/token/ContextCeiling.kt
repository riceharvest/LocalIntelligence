package dev.localintelligence.core.model.token

/**
 * The ONE place a context ceiling is defined. Everything else derives from here.
 *
 * ## The defect this exists to end
 *
 * Two numbers described the same window and disagreed by 2.25x:
 *
 *  - `DefaultContextBuilder` and `ContextBudget` were built with a hard-coded
 *    **6000** ("the working target from architecture §9").
 *  - `LlamaCppBackend` created the KV cache at **4096** — the number actually
 *    passed to `cparams.n_ctx` in `llama_jni.cpp`.
 *
 * So the builder would happily assemble a prompt of up to 6000 tokens and hand
 * it to a context that holds 4096. Measured on the real builder with 8 tools,
 * 5 memories and a full 64-turn window: **4259 prompt tokens, 4771 including
 * the 512-token `maxOutputTokens` default — 675 tokens past the KV cache.**
 * Nothing throws. llama.cpp clamps the prefill and the agent sees a truncated
 * context it cannot tell apart from a model that simply ignored half the
 * prompt.
 *
 * The two numbers were each individually defensible, which is why they drifted:
 * 6000 came from a *cost* rule (§9: "a routine action should never require a
 * 20K prefill") and 4096 from an *allocation* rule (the RAM fit gate). A cost
 * rule is an upper bound on what we are willing to spend; an allocation is a
 * fact about hardware. Only one of them can be the ceiling, and it is not the
 * cost rule — it is whatever the loaded model reports.
 *
 * ## The one rule
 *
 *     workingLimit = min( modelWindow x [TRIGGER_FRACTION], [PREFILL_COST_CAP] )
 *
 * The model's real window binds first, because it is the thing that physically
 * cannot be exceeded. The prefill cost cap binds on a model big enough that
 * obeying the window would mean paying a prefill §9 forbids. On this app's
 * actual 4096 allocation the first term wins and the cap never applies — which
 * is correct, and is the whole point: 6000 was never a number the hardware had
 * agreed to.
 *
 * ## Why the window, and not the trained length
 *
 * `ModelCapabilities.contextLength` is what the backend reports *after* load,
 * which is the size of the cache it actually created. The GGUF header's trained
 * length is a capability the model has and the app did not buy: the loader caps
 * allocation at [ALLOCATED_CONTEXT_TOKENS] on purpose (see
 * `LlamaCppBackend.MAX_CONTEXT_LENGTH`), so budgeting against the trained
 * length is budgeting against memory that does not exist.
 *
 * ## Why an unknown window is not "assume the cap"
 *
 * `ModelCapabilities.UNKNOWN.contextLength` is 0, and a backend that has not
 * been asked yet reports 0. Treating that as "no limit" produced the original
 * bug in a second costume: `min(0 * 0.65, 6000)` used to be handled by
 * *ignoring the model term entirely* and returning 6000. The honest reading of
 * "the backend will not say" is "assume the smallest cache this app can
 * create", which is [FALLBACK_WINDOW_TOKENS] — the allocation that happens
 * whenever the GGUF header is silent. Assuming the largest number available is
 * how a missing capability became a silent overflow.
 *
 * Stateless, pure, and safe to call from any layer: `:core` is a pure JVM
 * module, so this is where the number has to live for `:android` and `:app` to
 * be able to derive from it without depending on each other.
 */
object ContextCeiling {

    /**
     * The context this app allocates when the GGUF header is silent.
     *
     * THIS IS THE ALLOCATION NUMBER, and it is the only one. The KV cache the
     * loader creates is sized from it, and every RAM estimate in the product is
     * priced at it, so a second copy of this figure anywhere is a second
     * promise the hardware was never asked to keep.
     */
    const val ALLOCATED_CONTEXT_TOKENS: Int = 4096

    /**
     * What a backend that will not report its window is assumed to have.
     *
     * Equal to [ALLOCATED_CONTEXT_TOKENS] on purpose. 0 is not "unlimited" and
     * is not "the cap" — it is "nobody has asked the loader yet", and the
     * loader's answer when nobody asks is this number. See the type KDoc.
     */
    const val FALLBACK_WINDOW_TOKENS: Int = ALLOCATED_CONTEXT_TOKENS

    /**
     * Ceiling on what we will SPEND on a prefill, independent of what fits.
     *
     * Architecture §9: "a routine Android action should never require a 20K
     * prefill." Prefill is latency and battery, so this is a product decision
     * about cost and stays a constant.
     *
     * It is a CAP, never a ceiling on its own. A model with a 32K window gets
     * `min(32768 * 0.65, 6000)` = 6000, which it can hold; a model with a 4K
     * window gets 2662, which it can also hold. The bug was using this number
     * for a 4K model, which it cannot.
     */
    const val PREFILL_COST_CAP: Int = 6000

    /**
     * Fraction of the window the loop is willing to fill, architecture §12.
     *
     * 0.65 leaves headroom for the reply and for the next step's observation
     * before the next prefill, which is what keeps a tool-using loop from
     * walking itself into the ceiling one turn at a time.
     */
    const val TRIGGER_FRACTION: Double = 0.65

    /**
     * The real window, with 0 and negatives resolved to the fallback.
     *
     * A non-positive window is [ModelCapabilities.UNKNOWN], not an absence of a
     * limit. Returning 0 here and letting a caller multiply it would put the
     * arithmetic in the one place least equipped to notice.
     */
    fun windowTokensOrFallback(reportedWindowTokens: Int?): Int =
        if (reportedWindowTokens == null || reportedWindowTokens <= 0) {
            FALLBACK_WINDOW_TOKENS
        } else {
            reportedWindowTokens
        }

    /**
     * The working limit for a model with this window. The one formula.
     *
     * @param reportedWindowTokens what the loaded model reports, or 0/null when
     *   it does not know yet.
     * @param costCap the prefill cost cap; overridable so a caller with real
     *   eval evidence can move it, and so the default is stated in exactly one
     *   place.
     */
    fun workingLimit(
        reportedWindowTokens: Int?,
        costCap: Int = PREFILL_COST_CAP,
    ): Int {
        val window = windowTokensOrFallback(reportedWindowTokens)
        // The window term is computed in Int, not Double: `(window * 0.65)`
        // as a Double rounds differently across the range and a ceiling that
        // moves by one token depending on rounding is a ceiling nobody can
        // reason about. Integer division truncates toward zero, which is the
        // conservative direction — the limit lands a token lower, not higher.
        val byModel = (window * TRIGGER_FRACTION).toInt()
        return if (byModel <= 0) costCap.coerceAtMost(window) else minOf(byModel, costCap)
    }

    /**
     * The [ContextBudget] for a model with this window.
     *
     * The budget's `limitTokens` is the working limit, so the reply reserve is
     * subtracted on top of it: a 4096-window model gets a 2662 limit and a
     * 2406-token prompt budget, and neither number can exceed the cache the
     * loader created.
     */
    fun budget(
        reportedWindowTokens: Int?,
        costCap: Int = PREFILL_COST_CAP,
        reserveForOutputTokens: Int = ContextBudget.DEFAULT_OUTPUT_RESERVE,
    ): ContextBudget = ContextBudget(
        limitTokens = workingLimit(reportedWindowTokens, costCap),
        reserveForOutputTokens = reserveForOutputTokens,
    )
}
