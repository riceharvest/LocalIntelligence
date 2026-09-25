package dev.localintelligence.core.hub

/**
 * The device's memory and storage budget, as the hub needs to see it.
 *
 * ## Why an interface instead of reading /proc/meminfo here
 *
 * Two reasons, one of which is the important one.
 *
 * The unimportant one is testability: a fit check whose input is the host's
 * physical RAM cannot be asserted on a build machine.
 *
 * The important one is that the *authority* for the memory model is not this
 * package. `core/model/gguf` computes weights from the GGUF tensor table, which
 * is exact. This module only has the file name, the file size and a context
 * length — it can produce a *pre-download bound*, not an exact figure. A
 * pre-download bound that is derived from the authoritative estimator once the
 * header is readable is the right design; two independent memory models that
 * disagree is not.
 *
 * So: [ramBudget] and [diskBudget] are injected, and [MemoryModel] is the seam
 * that the real estimator plugs into. See [PreDownloadMemoryModel] for the
 * bound that ships when no header could be fetched.
 */
interface DeviceBudget {
    /** Bytes this app may realistically keep resident before the OS kills it. */
    fun availableRamBytes(): Long

    /** Free bytes on the volume that will hold the model. */
    fun freeDiskBytes(): Long

    /** Total physical RAM, for the "why won't this work" message. */
    fun totalRamBytes(): Long
}

/**
 * Turns a candidate file into a RAM verdict.
 *
 * WHY this is a seam and not a function: the exact tensor-table estimate lives
 * in `core/model/gguf` and needs a parsed [dev.localintelligence.core.model.gguf.GgufHeader],
 * which does not exist until some bytes arrive. [HuggingFaceClient.probeHeader]
 * fetches one for 4 MiB of the file, and [GgufMemoryModel] plugs it into this
 * seam; when that probe fails — offline, a gated repo, a non-GGUF body — the
 * caller gets [PreDownloadMemoryModel] instead.
 */
fun interface MemoryModel {
    /** @param parameterCount null when the name does not declare one. */
    fun estimate(fileBytes: Long, quant: GgufQuant?, contextLength: Int, parameterCount: Long?): Long
}

/**
 * A [MemoryModel] that knows how sure of itself it is.
 *
 * WHY this is additive rather than a change to [MemoryModel]: the single
 * `Long` is what [HuggingFaceClient.plan], [GgufMemoryModel] and the UI already
 * consume, and changing its shape would ripple through all of them for no gain.
 * But a model that answers with one number is asserting a precision it does not
 * have. Before the header is readable, the KV term rests on an assumption
 * about grouped-query attention that no file name can confirm, and asserting a
 * precision you do not have is how a fit verdict becomes a product defect.
 *
 * An implementation returns its point estimate from [estimate] and its honest
 * range from [estimateRange]. A model with no range — the exact post-download
 * estimator — is treated as a point.
 */
interface RangedMemoryModel : MemoryModel {
    fun estimateRange(
        fileBytes: Long,
        quant: GgufQuant?,
        contextLength: Int,
        parameterCount: Long?,
    ): MemoryRange
}

/**
 * A total, and the interval it is really worth.
 *
 * WHY an interval and not a single number: the pre-download estimate's largest
 * source of error is neither the weights (the file size, exact) nor the quant
 * (a measured table) but the KV cache, whose size depends on whether the model
 * uses grouped-query attention — a property of the architecture that is in the
 * file's metadata and in no file name. Measured across nine real
 * architectures, that one unknown moves the KV term by 8x.
 */
data class MemoryRange(
    val lowBytes: Long,
    val centralBytes: Long,
    val highBytes: Long,
) {
    init {
        require(lowBytes >= 0L) { "lowBytes must not be negative" }
        require(highBytes >= lowBytes) { "highBytes $highBytes below lowBytes $lowBytes" }
    }

    /** True when the interval carries no information, i.e. the estimate is exact. */
    val isExact: Boolean get() = highBytes == lowBytes
}

/**
 * The RAM estimate available *before* any bytes are downloaded.
 *
 * ## The formula
 *
 * ```
 * weights  = fileBytes                                    exact: the file IS the
 *                                                        mmap'd weights plus a
 *                                                        header (1,709,436 bytes
 *                                                        measured on a 668 MB file)
 * params   = from the file name, else fileBytes*8/bpw     bpw is measured per
 *                                                        quant, not assumed
 * arch     = measured table by parameter count           9 real architectures,
 *                                                        else derived
 * kv       = 2 * layers * kvWidth * contextLength * 2     the only uncertain term
 * overhead = max(64 MiB, 2% of weights)                    ESTIMATE, see below
 * total    = weights + kv + overhead
 * ```
 *
 * ## What changed, and what was wrong
 *
 * The previous version took a `quant` argument and **never used it**, and it
 * split an invented hidden size into KV heads with a hard-coded divisor of 512
 * and a hard-coded head dimension of 128. Two independent guesses multiplied
 * together. This is the end-to-end effect, through the real call path —
 * `parseParameterCount` on the file name, the app's 4096-token default context,
 * the same [RUNTIME_FLOOR_BYTES] and [RUNTIME_RATIO] on both sides — against
 * the KV size computed from each file's own metadata:
 *
 * ```
 * file (Q4_K_M, ctx 4096)        old total  new total   old/true   KV old   KV true
 * Qwen2.5-0.5B-Instruct            659.2M      687.9M       0.96     100.7M     50.3M
 * gemma-3-1b-it                   1011.6M      982.2M       1.03     157.3M    109.1M
 * TinyLlama-1.1B-Chat               874.3M      828.2M       1.06     157.3M     92.3M
 * Llama-3.2-1B-Instruct            1013.2M      983.9M       1.03     163.6M    134.2M
 * gemma-2-2b-it                   2019.0M     2060.3M       0.98     346.0M    436.2M
 * Qwen2.5-3B-Instruct              2374.5M     2148.0M       1.11     367.0M    151.0M
 * Phi-3-mini-4k-instruct           2997.2M     4071.0M       0.74     367.0M   1610.6M
 * Mistral-7B-Instruct-v0.2         4992.7M     4992.7M       1.00     536.9M    536.9M
 * Meta-Llama-3.1-8B-Instruct       5589.6M     5556.0M       1.01     570.4M    536.9M
 * ```
 *
 * Phi-3-mini is plain multi-head attention with a KV width equal to its whole
 * embedding width. It is also the one architecture of the nine whose file name
 * carries no parameter count (`4k` and `3-mini` are not a size), so it was
 * priced as a 7B *and* charged a 7B's KV geometry: 367 MiB against the 1.5 GiB
 * the file needs, a 1.2 GiB hole and a 41.5% under-statement of the number the
 * user was shown. That is the case where a wrong estimate kills the process
 * rather than merely annoying the user.
 *
 * Measured over these nine, the worst-case error in the *total* fell from 41.5%
 * to 11.5%, and the mean from 8.7% to 2.4%. The remaining 11.5% is
 * Qwen2.5-0.5B, where 79 MB of KV is missed on a 688 MB estimate — and it is
 * missed *high*, which refuses rather than OOMs.
 *
 * The fix has two halves. The point estimate now comes from a table of
 * architectures whose `block_count`, `head_count_kv` and `key_length` were read
 * out of real files ([MeasuredArchitectures]), which makes five of the nine
 * exact. And because that table covers nine architectures rather than every
 * model ever released, the result is a *range*: for an architecture with no
 * measured row the interval spans GQA 8 (measured, e.g. Qwen2.5-3B) to plain
 * MHA (measured, Phi-3-mini), the verdict is decided on the central figure, and
 * the upper end is reported to the user.
 *
 * ## The two ESTIMATED constants
 *
 * [RUNTIME_FLOOR_BYTES] (64 MiB: compute graph, RoPE tables, the `vocab * 4`
 * logits buffer, tokenizer arrays) and [RUNTIME_RATIO] (2% of weight bytes) are
 * **estimates, not measurements**. They were not measured on a device — see
 * `docs/memory-model.md` for the procedure, for what an on-device
 * `dumpsys meminfo` does and does not settle, and for the command to re-run it.
 * They are `const` so the next person holding a device replaces them with a
 * number instead of reverse-engineering a magic value. The same two constants
 * exist in [dev.localintelligence.core.model.gguf.ModelMemoryEstimator] and are
 * deliberately identical, so the pre-download and post-download answers cannot
 * disagree about them.
 */
object PreDownloadMemoryModel : RangedMemoryModel {

    /**
     * Flat runtime allowance: the compute graph, the logits buffer
     * (`vocab * 4` bytes, ~0.6 MiB for Qwen3), and the tokenizer arrays
     * materialised at load. Roughly constant in model size, which is why it is
     * a floor and not a ratio term.
     *
     * ESTIMATE. Not measured on a device.
     */
    const val RUNTIME_FLOOR_BYTES: Long = 64L * 1024 * 1024

    /** Buffers that genuinely scale with weight size. Deliberately modest. ESTIMATE. */
    const val RUNTIME_RATIO: Double = 0.02

    /** f16 KV cache: 2 bytes per element per tensor. */
    const val KV_BYTES_PER_ELEMENT: Double = 2.0

    /**
     * Parameter count assumed when the file name declares none *and* the quant
     * label is unrecognised, so no measured bits-per-weight figure exists to
     * recover one from the file size.
     *
     * WHY 7B: it is the middle of the range this app can plausibly load on a
     * phone, and the failure it causes is the recoverable one. A 1B file
     * mis-priced at 7B is charged a KV cache it will not allocate and is
     * offered at a smaller quant than it deserves; a 30B file mis-priced at 7B
     * fails on weights long before KV is consulted, and weights are exact.
     */
    const val UNKNOWN_PARAMETER_COUNT: Long = 7_000_000_000L

    /**
     * The context length assumed when the caller does not have one yet.
     *
     * WHY 2048: the smallest window that can hold a system prompt, a tool
     * result and a reply. Assuming more inflates every estimate for a model
     * the user has not configured; assuming less hides the real problem.
     */
    const val DEFAULT_CONTEXT_LENGTH: Int = 2_048

    override fun estimate(
        fileBytes: Long,
        quant: GgufQuant?,
        contextLength: Int,
        parameterCount: Long?,
    ): Long = estimateRange(fileBytes, quant, contextLength, parameterCount).centralBytes

    override fun estimateRange(
        fileBytes: Long,
        quant: GgufQuant?,
        contextLength: Int,
        parameterCount: Long?,
    ): MemoryRange {
        val safeBytes = fileBytes.coerceAtLeast(0L)
        val parameters = resolveParameterCount(safeBytes, quant, parameterCount)
        val architecture = MeasuredArchitectures.forParameterCount(parameters)
        val band = MeasuredArchitectures.kvWidthBand(architecture)
        val ctx = contextLength.coerceAtLeast(0)
        // WHY the file size and not the file size minus a header: llama.cpp maps
        // the file, so the header is inside the mapped range and the resident
        // cost really is the whole file. Using the full size is also the safe
        // direction.
        val weights = safeBytes
        val overhead = overheadFor(safeBytes)
        return MemoryRange(
            lowBytes = saturatingTotal(weights, kvBytes(architecture.layers, band.low, ctx), overhead),
            centralBytes = saturatingTotal(weights, kvBytes(architecture.layers, band.central, ctx), overhead),
            highBytes = saturatingTotal(weights, kvBytes(architecture.layers, band.high, ctx), overhead),
        )
    }

    /**
     * `2 * layers * kvWidth * contextLength * 2 bytes`.
     *
     * WHY `kvWidth` and not `kvHeads * headDim`: it is the same product, but
     * `kvWidth` is the single number the only architectural unknown controls.
     * Grouped-query attention is a *ratio* (`head_count / head_count_kv`), and
     * the ratio is what varies — 1, 2, 4, 7 and 8 all occur among the nine
     * measured architectures — while the product of the two factors is one
     * number per model. Splitting it into two independently-guessed factors was
     * the original defect: a fixed head dimension of 128 is wrong for the 64 of
     * TinyLlama, the 96 of Phi-3-mini and the 256 of gemma-2, in three of the
     * nine cases by 2x or more.
     *
     * The leading 2 is K and V. A q8_0 KV cache (1.0625 bytes/element) would
     * want 2.125; expressing that is `KvCacheType`'s job in
     * `core/model/gguf`, and the pre-download path assumes the f16 default.
     */
    private fun kvBytes(layers: Int, kvWidth: Int, contextLength: Int): Long {
        if (layers <= 0 || kvWidth <= 0 || contextLength <= 0) return 0L
        val exact = 2.0 * layers * kvWidth * contextLength * KV_BYTES_PER_ELEMENT
        return if (!exact.isFinite() || exact >= Long.MAX_VALUE.toDouble()) {
            Long.MAX_VALUE
        } else {
            exact.toLong()
        }
    }

    private fun overheadFor(fileBytes: Long): Long =
        maxOf(RUNTIME_FLOOR_BYTES, (fileBytes.toDouble() * RUNTIME_RATIO).toLong())

    private fun saturatingTotal(weights: Long, kv: Long, overhead: Long): Long {
        var total = weights
        if (total > Long.MAX_VALUE - kv) return Long.MAX_VALUE
        total += kv
        if (total > Long.MAX_VALUE - overhead) return Long.MAX_VALUE
        return total + overhead
    }

    /**
     * The parameter count to price the KV cache with, in order of trust.
     *
     * 1. **Declared in the file name** — `Qwen3-4B`, `Llama-3.2-1B`. Free, and
     *    accurate when it is there: TinyLlama's "1.1b" is within 0.004% of the
     *    1,100,048,384 the tensor table actually sums to.
     * 2. **Recovered from the file size and the quant** —
     *    `params = bytes * 8 / bitsPerWeight`, with a measured
     *    bits-per-weight per [GgufQuant.bitsPerWeight]. This is where the
     *    `quant` argument starts earning its place in the signature: it is the
     *    only way to tell a 1.1B Q4_K_M (668 MB) from a 7B Q4_K_M (4.1 GB)
     *    when neither name says so, and those two need KV caches that differ
     *    by 5.8x.
     * 3. **[UNKNOWN_PARAMETER_COUNT]**, when the name is silent *and* the label
     *    is unknown, so there is nothing to divide by.
     *
     * Why this matters more than it looks: of the nine measured architectures,
     * exactly one — Phi-3-mini, whose name carries "3-mini" and "4k" but no
     * size — returns null, and that one is the architecture the old model got
     * most dangerously wrong. Step 2 recovered `Phi-3-mini-4k-instruct-Q4_K_M`
     * (2,393,231,360 bytes) to 3,822,290,053 parameters against a true
     * 3,821,079,552: **0.03% out**, from a file size and a measured 5.009 bits
     * per weight, with nothing in the file name to go on.
     *
     * The uncertainty in the recovered count is [GgufQuant.bitsPerWeightMax]
     * against [GgufQuant.bitsPerWeight], up to 1.61x for the blended quants,
     * and it is deliberately not propagated into the verdict: a count that is
     * 60% high over-charges the KV, which fails toward *refusing* a model. The
     * previous behaviour of pricing every unnamed file as 7B was 636% out in
     * that same direction for Phi-3-mini (7e9 against 3.82e9).
     */
    private fun resolveParameterCount(
        fileBytes: Long,
        quant: GgufQuant?,
        declared: Long?,
    ): Long {
        if (declared != null && declared > 0L) return declared
        if (quant != null && fileBytes > 0L && quant.bitsPerWeight > 0.0) {
            val recovered = fileBytes.toDouble() * 8.0 / quant.bitsPerWeight
            if (recovered.isFinite() && recovered >= 1.0 && recovered < Long.MAX_VALUE.toDouble()) {
                return recovered.toLong().coerceAtLeast(1L)
            }
        }
        return UNKNOWN_PARAMETER_COUNT
    }
}

/**
 * The verdict on whether a file will fit, with the arithmetic kept so the UI
 * can show it.
 *
 * WHY this is a data class and not a Boolean: "will this fit" is a question a
 * user asks *because* they do not trust the answer, and the only thing that
 * earns the trust is showing the numbers next to it. [totalBytes] against
 * [availableBytes] with [fits] and a "why" is the whole disagreement surface,
 * and it is four fields.
 *
 * [lowBytes] and [highBytes] are the honest interval around [totalBytes]. They
 * default to [totalBytes] so an exact estimate (one built from a real tensor
 * table) reports no range at all rather than a fake one.
 */
data class RamFit(
    val totalBytes: Long,
    val availableBytes: Long,
    val fits: Boolean,
    /** One line, already phrased for display. */
    val explanation: String,
    /** The low end of the estimate's honest range. Defaults to [totalBytes]. */
    val lowBytes: Long = totalBytes,
    /** The high end. Defaults to [totalBytes]. */
    val highBytes: Long = totalBytes,
) {
    /**
     * True when the range is wide enough that the verdict could flip on a
     * detail the file name does not carry.
     *
     * WHY a threshold and not just "is the range non-empty": a 2% spread is
     * noise, and telling a user their fit is uncertain when the answer is 2%
     * either way trains them to ignore the warning.
     */
    val uncertain: Boolean
        get() = highBytes > lowBytes + (lowBytes / UNCERTAINTY_BAND_PERCENT)

    private companion object {
        /** A range wider than a tenth of its low end counts as uncertain. */
        const val UNCERTAINTY_BAND_PERCENT = 10L
    }
}

/**
 * Decides RAM and disk fit, and refuses before the first byte moves.
 *
 * ## Why this gate is the point of the whole feature
 *
 * Downloading a model is cheap to start and expensive to finish: a 4 GB file on
 * a metered connection, into a device that may not have the RAM to load it.
 * Once the user has spent that, the cost is sunk and the app has no leverage.
 * Before the download, the app has every option — say no, pick a smaller quant,
 * pick a smaller context.
 *
 * [DECISION_FACTOR] is the headroom demanded on top of the estimate. WHY not
 * 1.0: the pre-download model is an estimate, Android's own accounting is
 * conservative, and an app that gets OOM-killed mid-conversation is worse than
 * an app that declined a model. 1.15 leaves room for the estimate being wrong
 * in the direction it is most likely to be wrong.
 *
 * ## Why the verdict is decided on the central estimate and not the high one
 *
 * The alternative is to refuse anything whose *upper* bound does not fit. That
 * is defensible in the abstract and bad in practice: for a 7B at 4K context the
 * upper bound assumes plain multi-head attention, which no 7B model uses, and
 * refusing it would cost the user a model their phone runs. The failure this
 * gate must not have is telling a user "yes" and then being OOM-killed, so the
 * two are both reported: [RamFit.fits] is the decision, and [RamFit.highBytes]
 * plus the explanation text say what it would take if the architecture turns
 * out to be the awkward one. A user who is told "this fits, unless the model
 * has no grouped-query attention, in which case it needs 7.4 GB and you have
 * 6" can act on that; a user who is told a bare "yes" cannot.
 */
object FitGate {

    /** Multiplier on the RAM estimate, for the reasons above. */
    const val DECISION_FACTOR: Double = 1.15

    /**
     * Extra free space required beyond the file size, as a ratio plus a floor.
     *
     * WHY any headroom at all: a filesystem with zero free space cannot
     * complete a write, and the failure surfaces as ENOSPC partway through a
     * 2 GB transfer, which is the worst possible moment to discover it. 5% or
     * 64 MiB, whichever is larger.
     */
    const val DISK_HEADROOM_RATIO: Double = 0.05
    const val DISK_HEADROOM_FLOOR: Long = 64L * 1024 * 1024

    /**
     * Computes the RAM verdict.
     *
     * @param budget what the device can afford.
     * @param model the estimator; pass [PreDownloadMemoryModel] for a
     *   pre-download bound or a tensor-table model once the header is readable.
     */
    fun ramFit(
        fileBytes: Long,
        quant: GgufQuant?,
        contextLength: Int,
        budget: DeviceBudget,
        model: MemoryModel = PreDownloadMemoryModel,
        parameterCount: Long? = null,
    ): RamFit {
        val range = (model as? RangedMemoryModel)?.estimateRange(
            fileBytes = fileBytes,
            quant = quant,
            contextLength = contextLength,
            parameterCount = parameterCount,
        ) ?: MemoryRange(
            lowBytes = model.estimate(fileBytes, quant, contextLength, parameterCount),
            centralBytes = model.estimate(fileBytes, quant, contextLength, parameterCount),
            highBytes = model.estimate(fileBytes, quant, contextLength, parameterCount),
        )
        val low = scaled(range.lowBytes)
        val needed = scaled(range.centralBytes)
        val high = scaled(range.highBytes)
        val available = budget.availableRamBytes()
        val fits = needed <= available
        val explanation = if (fits && high > available) {
            "Estimated ${formatBytes(needed)} in memory, ${formatBytes(available)} available. " +
                "A model of this size that does not use grouped-query attention would need " +
                "${formatBytes(high)}; the file's own header settles it for 8 MB of download."
        } else if (fits) {
            "Estimated ${formatBytes(needed)} in memory, ${formatBytes(available)} available."
        } else {
            "Needs about ${formatBytes(needed)} in memory, this device has ${formatBytes(available)}. " +
                "Try a smaller quantization or a shorter context."
        }
        return RamFit(
            totalBytes = needed,
            availableBytes = available,
            fits = fits,
            explanation = explanation,
            lowBytes = low,
            highBytes = high,
        )
    }

    private fun scaled(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        val scaled = bytes.toDouble() * DECISION_FACTOR
        return if (scaled >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else scaled.toLong()
    }

    /**
     * Computes the disk verdict, or returns the [HubError.InsufficientStorage]
     * to report instead.
     *
     * @param alreadyOnDisk bytes already present in the partial file, which do
     *   not need to be re-allocated. Getting this wrong makes a resumed
     *   download fail its own space check.
     */
    fun diskFit(
        fileBytes: Long,
        alreadyOnDisk: Long,
        budget: DeviceBudget,
    ): HubError? {
        val stillNeeded = (fileBytes - alreadyOnDisk).coerceAtLeast(0L)
        val headroom = maxOf((fileBytes * DISK_HEADROOM_RATIO).toLong(), DISK_HEADROOM_FLOOR)
        val required = stillNeeded + headroom
        val free = budget.freeDiskBytes()
        return if (required <= free) {
            null
        } else {
            HubError.InsufficientStorage(required, free)
        }
    }
}
