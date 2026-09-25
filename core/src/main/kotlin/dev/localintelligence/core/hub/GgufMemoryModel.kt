package dev.localintelligence.core.hub

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
 * recheck(file, header, budget)      -> ModelMemoryEstimator, exact
 * ```
 *
 * The recheck is what lets the app say, after the fact, "this one turned out
 * to need more than we thought" instead of discovering it as an OOM at load
 * time. It is not wired into [ModelDownloader] on this branch because the
 * downloader does not parse the GGUF header it just wrote, and adding that
 * would mean this branch owning the header-parsing call that
 * `ModelImporter.describe` already makes. The seam is the deliverable; the
 * caller is one line wherever the header is already being parsed.
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
     * Refines a verdict with the exact estimate, keeping the same 1.15
     * decision factor.
     *
     * WHY this does not simply reuse [FitGate.ramFit]: that takes a [MemoryModel]
     * and re-estimates from scratch, but here the estimate already exists and
     * carrying it over avoids throwing away the inputs that produced it. It
     * also means the UI can show the refined figure with the estimate's own
     * basis ([MemoryEstimate.basis]) attached, so a `FILE_SIZE`-basis estimate
     * is visibly weaker than a `TENSOR_TABLE` one.
     */
    fun refine(
        estimate: MemoryEstimate,
        budget: DeviceBudget,
    ): RamFit {
        val needed = (estimate.totalBytes * FitGate.DECISION_FACTOR).toLong()
        val available = budget.availableRamBytes()
        val fits = needed <= available
        val explanation = if (fits) {
            "Needs ${formatBytes(needed)} in memory, ${formatBytes(available)} available."
        } else {
            "Needs ${formatBytes(needed)} in memory, this device has ${formatBytes(available)}. " +
                "Try a smaller quantization or a shorter context."
        }
        return RamFit(needed, available, fits, explanation)
    }
}
