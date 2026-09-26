package dev.localintelligence.core.hub

import dev.localintelligence.core.model.gguf.EstimateBasis
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.MemoryEstimate
import dev.localintelligence.core.model.gguf.ModelMemoryEstimator

/**
 * Bridges the hub's pre-download [MemoryModel] seam to the authoritative
 * post-download estimator in `core.model.gguf`.
 *
 * ## Why this exists when the pre-download model is already good enough
 *
 * Because they answer different questions, and only one of them is exact.
 *
 * [PreDownloadMemoryModel] runs before any bytes have moved. It has a file
 * name, a file size and a context length — not a tensor table — so its weights
 * figure is exact (the file is mostly weights) but its KV-cache term is
 * estimated from a parameter-count proxy. It is deliberately conservative.
 *
 * [ModelMemoryEstimator] runs after the header is readable and computes the
 * weights term from the actual GGUF tensor table, term for term. It is the
 * authority, and it is *not available at the moment the user is deciding
 * whether to spend 2 GB of their data allowance*. So the hub cannot replace the
 * pre-download model with it, and this adapter is what lets the app refine its
 * answer once the file is on disk.
 *
 * ## The intended flow
 *
 * ```
 * plan(file, budget)                 -> PreDownloadMemoryModel, conservative
 * download(file, plan)               -> bytes on disk
 * refine(estimate, budget, header)   -> ModelMemoryEstimator, from the file
 * ```
 *
 * The refinement is what lets the app say, after the fact, "this one turned
 * out to need more than we thought" instead of discovering it as an OOM at
 * load time. It is not wired into [ModelDownloader] on this branch because the
 * downloader does not parse the GGUF header it just wrote, and adding that
 * would mean this branch owning the header-parsing call that
 * `ModelImporter.describe` already makes. The seam is the deliverable; the
 * caller is one line wherever the header is already being parsed.
 *
 * ## "EXACT" IS A SHORTER WORD THAN IT LOOKS
 *
 * Neither estimator produces a *measured* number, and this object used to
 * imply that it did. `ModelMemoryEstimator` computes the weights and the KV
 * cache from the file's own metadata, which is exact — but its third term,
 * `overheadBytes`, is `RUNTIME_BUFFER_FLOOR` (64 MiB) or 2% of the weights,
 * both argued from first principles and never measured on a device. On a
 * 400 MB model that floor is 16% of the total and is pure argument.
 *
 * So the range reported here is exactly the size of the unmeasured term and
 * nothing more. It is not a claim that the whole figure is uncertain, and it
 * is not a claim that the whole figure is certain. It is the one part that is,
 * shown as such.
 */
object GgufMemoryModel {

    /**
     * A [MemoryModel] backed by the real estimator and a fixed header.
     *
     * @param header a header already parsed from the downloaded file. Holding
     *   it here rather than taking a `File` is what keeps this adapter free of
     *   I/O: the hub decides, the caller reads.
     */
    fun from(
        header: GgufHeader,
        estimator: ModelMemoryEstimator = ModelMemoryEstimator(),
    ): MemoryModel = MemoryModel { fileBytes, quant, contextLength, parameterCount ->
        estimator.estimate(
            header = header,
            contextLengthOverride = contextLength.toLong(),
        ).totalBytes
    }

    /**
     * The same thing, but ranged — which is what [FitGate.ramFit] needs in
     * order to report an interval instead of a bare number.
     *
     * WHY THIS IS A SEPARATE FUNCTION RATHER THAN A CHANGE TO [from]: `from`
     * returns the plain [MemoryModel] the seam declares, and a lambda has
     * nowhere to hang a second method. When [FitGate.ramFit] is handed a
     * `from(...)` model it finds no [RangedMemoryModel], falls back to calling
     * `estimate(...)` three times for the low, central and high values, and
     * gets three identical answers — which it then reports as an exact range
     * with `isExact == true`. That is a false claim of precision on the one
     * path that is supposed to be the precise one.
     *
     * [HuggingFaceClient.plan] is the caller that needs this: it hands
     * `header` straight to `from` today.
     */
    fun rangedFrom(
        header: GgufHeader,
        estimator: ModelMemoryEstimator = ModelMemoryEstimator(),
    ): RangedMemoryModel = object : RangedMemoryModel {
        override fun estimate(
            fileBytes: Long,
            quant: GgufQuant?,
            contextLength: Int,
            parameterCount: Long?,
        ): Long = range(estimator, header, contextLength).centralBytes

        override fun estimateRange(
            fileBytes: Long,
            quant: GgufQuant?,
            contextLength: Int,
            parameterCount: Long?,
        ): MemoryRange = range(estimator, header, contextLength)
    }

    /**
     * The honest interval for a file whose header has been read.
     *
     * WHY THE LOW END IS `weights + overhead` AND NOT `weights`: it is the
     * figure with the KV cache removed, which is the floor a model of this
     * weight would reach if the context were never filled. It is a bound, not
     * a prediction, and it never decides the verdict — see [FitGate]'s rule
     * that the decision lands on the central figure.
     */
    private fun range(
        estimator: ModelMemoryEstimator,
        header: GgufHeader,
        contextLength: Int,
    ): MemoryRange {
        val estimate = estimator.estimate(
            header = header,
            contextLengthOverride = contextLength.toLong(),
        )
        val floor = estimate.weightsBytes + estimate.overheadBytes
        return MemoryRange(
            lowBytes = floor,
            centralBytes = estimate.totalBytes,
            highBytes = maxOf(estimate.totalBytes, floor),
        )
    }

    /**
     * Refines a verdict with the estimate built from the file's own header.
     *
     * @param estimate the estimate to refine.
     * @param budget what the device can afford.
     * @param header the same file's header. Needed to re-price at
     *   [expectedContextLength], and kept separate from it so the two
     *   decisions stay visible at the call site.
     * @param expectedContextLength the context the app will actually
     *   allocate. **Pass this.**
     *
     * ## WHY IT IS A PARAMETER, AND WHY IT MATTERS
     *
     * A [MemoryEstimate] carries whatever context it was built at, and
     * `ModelMemoryEstimator` defaults that to the *model's own declared*
     * length. The app allocates `ModelImporter.DEFAULT_CONTEXT_LENGTH` = 4096
     * regardless of what a model was trained on, so an estimate priced at a
     * 32K-trained model's declared context describes a KV cache **eight times
     * larger** than the one that will actually exist.
     *
     * This is the defect the prior audit found in `ModelManagerScreen`, fixed
     * there and still live here. It was live in a second form too: the old
     * `refine(estimate, budget)` had no idea what context its estimate had been
     * built at, so a caller could hand it a 32K estimate and get back a
     * confident "Needs X in memory, Y available" describing a cache the app
     * will never allocate.
     *
     * Passing `null` keeps the old behaviour rather than changing a signature
     * call sites depend on — and the explanation then *says* which context the
     * figure was priced at and flags it as an upper bound, so the mismatch is
     * visible in the UI instead of inferred.
     */
    fun refine(
        estimate: MemoryEstimate,
        budget: DeviceBudget,
        header: GgufHeader? = null,
        expectedContextLength: Int? = null,
        estimator: ModelMemoryEstimator = ModelMemoryEstimator(),
    ): RamFit {
        val target = expectedContextLength?.takeIf { it > 0 }
        val priced = if (header != null && target != null) {
            estimator.estimate(
                header = header,
                contextLengthOverride = target.toLong(),
                kvCacheType = estimate.kvCacheType,
            )
        } else {
            estimate
        }
        val needed = scaled(priced.totalBytes)
        val available = budget.availableRamBytes()
        val fit = RamFit(
            totalBytes = needed,
            availableBytes = available,
            fits = needed <= available,
            explanation = "",
            lowBytes = scaled(priced.weightsBytes + priced.overheadBytes),
            highBytes = needed,
            // WHY TRUE, AND WHY IT IS STILL NOT "CERTAIN": the weights and the
            // KV cache are read term for term out of the file's own tensor
            // table. `overheadBytes` is not, and it is the entire width of the
            // range reported above.
            exactBasis = priced.basis == EstimateBasis.TENSOR_TABLE,
        )
        return fit.copy(explanation = explain(priced, fit, repriced = header != null && target != null))
    }

    /**
     * The verdict text.
     *
     * WHY THE HEDGING APPEARS HERE AS WELL AS IN [FitGate]: the tensor table
     * makes the weights and the KV cache exact, but it does not make
     * `ModelMemoryEstimator.RUNTIME_BUFFER_FLOOR` measured, and it does not
     * make `AndroidDeviceBudget.USABLE_FRACTION` (0.55 of physical RAM) a fact
     * about what the kernel will actually let this process keep. A model
     * needing 95% of the budget still carries an unmeasured 64 MiB term and an
     * unmeasured 45% of RAM given away to the system, and the previous text —
     * "Needs X in memory, Y available" — asserted none of that.
     */
    private fun explain(estimate: MemoryEstimate, fit: RamFit, repriced: Boolean): String {
        if (!fit.fits) {
            return "Needs ${formatBytes(fit.totalBytes)} in memory, " +
                "${formatBytes(fit.availableBytes)} available. Try a smaller " +
                "quantization; context length is fixed at " +
                "${PreDownloadMemoryModel.DEFAULT_CONTEXT_LENGTH} tokens."
        }
        val head = "Needs ${formatBytes(fit.totalBytes)} in memory at context " +
            "${estimate.contextLength}, ${formatBytes(fit.availableBytes)} available — " +
            "${(fit.headroomFraction * 100).coerceAtLeast(0.0).toInt()}% headroom. "
        if (!repriced) {
            return head + "The weights and the context cache were read from the " +
                "model's own header, but this figure is priced at the context the " +
                "model declares rather than the " +
                "${PreDownloadMemoryModel.DEFAULT_CONTEXT_LENGTH} this app " +
                "allocates, so read it as an upper bound."
        }
        return when (FitGate.MarginBand.of(fit.headroomFraction)) {
            FitGate.MarginBand.CONFIDENT ->
                "$head There is more room here than the runtime allowance this " +
                    "figure estimates rather than measures."
            FitGate.MarginBand.TIGHT ->
                "$head The weights and the context cache are read from the " +
                    "model's own header, but the figure includes an estimated " +
                    "runtime allowance that has never been measured on a " +
                    "phone, so it will probably fit rather than certainly."
            FitGate.MarginBand.MARGINAL ->
                "$head Less room than that unmeasured allowance itself, so this " +
                    "is close to a coin flip. A smaller quantization is the safe " +
                    "choice."
        }
    }

    private fun scaled(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        val scaled = bytes.toDouble() * FitGate.DECISION_FACTOR
        return if (scaled >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else scaled.toLong()
    }
}
