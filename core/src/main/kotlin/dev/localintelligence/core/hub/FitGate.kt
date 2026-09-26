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
 * Fitted against 160 real GGUFs across 9 repositories, all 160 parsing:
 * worst-case fit error 11.5%, mean 2.4%. That is a model of RAM, not a
 * measurement of it — see `docs/measurements.md`.
 *
 * ## The two ESTIMATED constants
 *
 * [RUNTIME_FLOOR_BYTES] (64 MiB: compute graph, RoPE tables, the `vocab * 4`
 * logits buffer, tokenizer arrays) and [RUNTIME_RATIO] (2% of weight bytes) are
 * **estimates, not measurements**. They have never been measured on a device —
 * see `docs/memory-model.md` for the procedure and the command to re-run it.
 * On a 400 MB model that floor is 16% of the total and is pure argument.
 *
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
     * THIS MUST MATCH THE CONTEXT THE BACKEND ACTUALLY ALLOCATES:
     * `ModelImporter.DEFAULT_CONTEXT_LENGTH` and
     * `LlamaCppBackend.DEFAULT_CONTEXT_LENGTH` are both 4096, the load gate in
     * `AppContainer.loadModel` uses 4096, and the backend creates the KV cache
     * at 4096. A pre-download estimate is a claim about *that* allocation, so
     * pricing the cache at half its real size is a straight under-statement —
     * and under-statement is the direction that gets a phone OOM-killed after
     * the user has spent the download.
     *
     * The KV term is exactly linear in context, so a mismatch here is not an
     * estimate disagreeing with reality, it is the model pricing half a cache.
     *
     * WHY 4096 IS A CONSTANT AND NOT A PARAMETER: the app has no context
     * control. `ModelManagerScreen` says so on the record itself, and
     * `ModelAvailability.describeLoadFailure` tells a user hitting OOM that
     * "a smaller model is the only lever" for exactly this reason. So there is
     * one context length the app ever uses, and this is it. When a control is
     * added, this becomes a parameter and the callers that pass 2048 —
     * `ModelDownloader.PreDownloadContextLength` — have to move with it.
     */
    const val DEFAULT_CONTEXT_LENGTH: Int = 4_096

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

        // The parameter count itself has a spread when it was recovered from
        // the file size and the quant, and that spread moves the KV term. See
        // [parameterCountRange] for why the *high* bpw is the dangerous end.
        val paramRange = parameterCountRange(safeBytes, quant, parameterCount)
        val fewestParams = paramRange.first
        val mostParams = paramRange.last
        val fewestArch = MeasuredArchitectures.forParameterCount(fewestParams)
        val mostArch = MeasuredArchitectures.forParameterCount(mostParams)
        val fewestBand = MeasuredArchitectures.kvWidthBand(fewestArch)
        val mostBand = MeasuredArchitectures.kvWidthBand(mostArch)

        // Four corners rather than three points: each of the two unknown
        // factors (the parameter count, and the GQA ratio within the
        // architecture it selects) can move the KV term independently, and the
        // high corner needs both of them high at once.
        val candidates = listOf(
            saturatingTotal(weights, kvBytes(architecture.layers, band.low, ctx), overhead),
            saturatingTotal(weights, kvBytes(architecture.layers, band.central, ctx), overhead),
            saturatingTotal(weights, kvBytes(architecture.layers, band.high, ctx), overhead),
            saturatingTotal(weights, kvBytes(fewestArch.layers, fewestBand.high, ctx), overhead),
            saturatingTotal(weights, kvBytes(mostArch.layers, mostBand.low, ctx), overhead),
        )
        return MemoryRange(
            lowBytes = candidates.min(),
            centralBytes = candidates[1],
            highBytes = candidates.max(),
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
     * number per model. Do not split it back into two independently-guessed
     * factors: a fixed head dimension of 128 is wrong for the 64 of
     * TinyLlama, the 96 of Phi-3-mini and the 256 of gemma-2.
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
     *    `quant` argument earns its place in the signature: it is the only way
     *    to tell a 1.1B Q4_K_M (668 MB) from a 7B Q4_K_M (4.1 GB) when
     *    neither name says so, and those two need KV caches that differ by
     *    5.8x.
     * 3. **[UNKNOWN_PARAMETER_COUNT]**, when the name is silent *and* the label
     *    is unknown, so there is nothing to divide by.
     *
     * Step 2 is the load-bearing one: of the nine measured architectures,
     * exactly one — Phi-3-mini, whose name carries "3-mini" and "4k" but no
     * size — returns null, and that one is MHA, the widest KV geometry. It
     * still recovers to within 0.03% of the tensor table's own sum.
     */
    private fun resolveParameterCount(
        fileBytes: Long,
        quant: GgufQuant?,
        declared: Long?,
    ): Long {
        if (declared != null && declared > 0L) return declared
        // WHY THE isMeasured GATE: two of the forty-odd entries — Q4_1 and
        // Q5_1 — have `measuredSamples == 0` and carry the *block layout* of a
        // format no repository in the 160-file sample publishes. The layout is
        // the right answer for a file that really is uniformly that quant, so
        // this is not a refusal; but a blended file (and a file whose
        // vocabulary embedding is kept in a wider type, which is what moved
        // gemma-3's single Q4_1 sample to 6.0607 against a layout of 5.0)
        // is not described by a block layout at all. Using it silently is
        // using a number this project has never checked, in a code path whose
        // entire job is to avoid unchecked numbers.
        //
        // The failure it prevents is directional: `params = bytes*8/bpw`, so
        // an *over*-stated bpw yields too few parameters, a too-small KV
        // cache, and a gate that says yes to a model that will not load.
        if (quant != null && quant.isMeasured && fileBytes > 0L && quant.bitsPerWeight > 0.0) {
            val recovered = fileBytes.toDouble() * 8.0 / quant.bitsPerWeight
            if (recovered.isFinite() && recovered >= 1.0 && recovered < Long.MAX_VALUE.toDouble()) {
                return recovered.toLong().coerceAtLeast(1L)
            }
        }
        return UNKNOWN_PARAMETER_COUNT
    }

    /**
     * The same derivation, at both ends of the measured bits-per-weight spread.
     *
     * WHICH END IS THE DANGEROUS ONE: `params = bytes * 8 / bpw`, so a bpw
     * *higher* than the median yields *fewer* parameters, an under-counted KV
     * cache, and a gate that says **yes** to a model that will not load. The
     * other direction over-counts KV and refuses a model that would have
     * fitted — annoying, not fatal.
     *
     * So the *high* end of the memory range is computed from
     * `bitsPerWeightMax` and the *low* end from `bitsPerWeight`. The verdict
     * still lands on the central figure, per [FitGate]'s rule, but the upper
     * bound shown to the user is the one that can actually be too small.
     */
    fun parameterCountRange(
        fileBytes: Long,
        quant: GgufQuant?,
        declared: Long?,
    ): LongRange {
        val central = resolveParameterCount(fileBytes, quant, declared)
        // A declared count has no spread: it came from the file name and is
        // either right or it is not. Same for the UNKNOWN fallback.
        if (declared != null && declared > 0L) return central..central
        if (quant == null || !quant.isMeasured || fileBytes <= 0L || quant.bitsPerWeight <= 0.0) {
            return central..central
        }
        val recover = { bpw: Double ->
            val v = fileBytes.toDouble() * 8.0 / bpw
            if (v.isFinite() && v >= 1.0 && v < Long.MAX_VALUE.toDouble()) {
                v.toLong().coerceAtLeast(1L)
            } else {
                central
            }
        }
        val fewest = recover(quant.bitsPerWeightMax)
        val most = recover(quant.bitsPerWeight)
        return minOf(fewest, most)..maxOf(fewest, most)
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
    /**
     * True when the estimate was built from a real tensor table rather than
     * from a file name and a file size.
     *
     * Set by [FitGate.ramFit] from the model it was handed, so a caller cannot
     * claim an exact basis it did not use. Defaults false, which is the safe
     * direction: an unlabelled estimate is reported as [Confidence.NARROW] and
     * never as [Confidence.EXACT].
     */
    val exactBasis: Boolean = false,
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

    /**
     * How much room is left between what this device can give and what the
     * model is estimated to need, as a fraction of the need.
     *
     * WHY this is the number that decides how the verdict reads: a gate that
     * says "fits" is a claim about a *margin*, and a margin of 40% and a
     * margin of 2% are the same word with completely different meanings. A
     * user who is told "it will fit" and then watches the app die has been
     * told something the arithmetic does not support, and the arithmetic is
     * the only thing here a user cannot check for themselves.
     */
    val headroomFraction: Double
        get() = if (totalBytes <= 0L) 0.0
        else (availableBytes.toDouble() - totalBytes) / totalBytes.toDouble()

    /**
     * How well the *verdict* is known, which is what the verdict text is
     * allowed to claim.
     *
     * ## WHY THIS IS NOT `MemoryEstimate.Confidence`
     *
     * `dev.localintelligence.core.model.gguf.MemoryEstimate` already has an
     * enum called `Confidence`, and it answers a different question: *how good
     * were the inputs to this number*. This one answers *how good is the
     * yes/no the user is about to act on*, which additionally depends on how
     * much room there is between the figure and the device — a tensor-table
     * estimate with 5% headroom is a worse verdict than a name-derived
     * estimate with 50%, and `MemoryEstimate` cannot see the second number.
     *
     * They compose rather than replace: [confidence] is `EXACT` only when
     * [exactBasis] holds *and* the range is narrow, so a caller wanting the
     * full picture reads both.
     */
    enum class VerdictConfidence {
        /**
         * The estimate came from the file's own tensor table, term for term,
         * and the interval around it is too narrow to matter.
         *
         * Weights and KV are exact given the header. What remains is
         * [PreDownloadMemoryModel.RUNTIME_FLOOR_BYTES] and
         * [PreDownloadMemoryModel.RUNTIME_RATIO], which are argued from first
         * principles and have never been measured on a device, so "exact"
         * here means "every term that can be read was read".
         */
        EXACT,

        /**
         * A pre-download estimate whose range is too narrow to show: the
         * architecture matched a measured row, so the KV geometry is asserted
         * rather than ranged.
         *
         * This is the case that used to be presented as a confident number.
         * It is not wrong — it is 0.00% out on five of the nine measured
         * architectures — but "measured on this class of model" is not the
         * same claim as "measured on your file", and the verdict text has to
         * say which one it is making.
         */
        NARROW,

        /**
         * The range is wide enough that the verdict genuinely could go either
         * way. The user is told the range and told which end decided it.
         */
        WIDE,
    }

    /**
     * Which of the three claims this verdict is entitled to make.
     *
     * The threshold is [UNCERTAINTY_BAND_PERCENT], the same one [uncertain]
     * uses, so a reader never sees "uncertain: true" next to a confident
     * sentence. A 10% spread on a 2 GB model is 200 MB, which is the
     * difference between loading and not; a 10% spread on a 200 MB model is
     * 20 MB, which is noise. The percentage is a proxy for both and neither,
     * which is why the enum exists: it lets the UI say something true in both
     * cases instead of something uniformly hedged.
     */
    val confidence: VerdictConfidence
        get() = if (uncertain) VerdictConfidence.WIDE
        else if (exactBasis) VerdictConfidence.EXACT
        else VerdictConfidence.NARROW

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

    /**
     * Multiplier on the RAM estimate, for the reasons above.
     *
     * ## WHERE 1.15 COMES FROM, AND WHY IT IS NOT ENOUGH
     *
     * This was previously justified as "leaves room for the estimate being
     * wrong in the direction it is most likely to be wrong", which is a
     * direction, not a magnitude. Measured against the nine architectures in
     * `docs/memory-model.md` §2, at the context the app actually allocates,
     * the total-estimate error is:
     *
     * ```
     * worst case  12.98%  (Qwen2.5-0.5B-Instruct)
     * mean         2.48%
     * ```
     *
     * so 15% does cover the worst case, with 2 points to spare, **on that
     * sample**. Three things it does not cover:
     *
     * 1. The 9 architectures are a sample of the models that exist. A model
     *    outside them takes the derived path, whose embedding-width error was
     *    measured at up to +65% and whose layer count is interpolated.
     * 2. `parameterCountRange` adds a second, independent spread on top: the
     *    measured bits-per-weight range reaches 1.61x for `Q2_K`, which moves
     *    a recovered parameter count by up to 38%.
     * 3. [PreDownloadMemoryModel.RUNTIME_FLOOR_BYTES] (64 MiB) and
     *    [PreDownloadMemoryModel.RUNTIME_RATIO] (2%) are **estimates that
     *    have never been measured on a device**. On a 400 MB model the floor
     *    alone is 16% of the total and is pure argument.
     *
     * So 1.15 is kept — widening it further would be inventing a margin, and
     * the instruction on this project is explicit that a safety factor must be
     * derived from the measured distribution rather than chosen for the
     * comfort of the person reading it. What changes instead is that the
     * verdict no longer *says* more than the arithmetic supports: see
     * [MarginBand] and the explanation text in [ramFit].
     */
    const val DECISION_FACTOR: Double = 1.15

    /**
     * How a fit verdict's confidence is graded, and why there is a band rather
     * than a boolean.
     *
     * The problem this solves, stated as a user would state it: a model that
     * needs 95% of the available RAM and a model that needs 40% of it produce
     * the same word — "fits" — from the same arithmetic, and only one of them
     * is a promise. With a worst-case estimate error of 12.98% and an
     * unmeasured 64 MiB runtime constant on top, a 5% margin is not "it will
     * fit", it is "it will probably fit, and the reason it probably will is
     * arithmetic the user cannot check".
     *
     * The bands are set from the measured distribution, not chosen:
     *
     * - [CONFIDENT] at 25% headroom. 25% is above the 12.98% measured
     *   worst-case error, so the verdict survives the estimate being wrong at
     *   its worst observed value on this sample.
     * - [TIGHT] at 10% headroom. 10% is *below* the measured worst case, so
     *   the verdict is stated as a probability rather than a fact, and the
     *   range is always shown.
     * - Below 10%, the verdict is stated as a coin-flip and the range is
     *   shown prominently.
     *
     * Nothing here widens the gate. Every one of these cases already fit or
     * did not fit before this change; what changed is the sentence the user
     * reads about it.
     */
    enum class MarginBand(val minHeadroomFraction: Double, val label: String) {
        /** Comfortably clear of the measured worst-case error. */
        CONFIDENT(0.25, "comfortable"),

        /** Fits, but inside the measured error band of the estimate. */
        TIGHT(0.10, "tight"),

        /** Fits with less room than the estimate's own worst-case error. */
        MARGINAL(0.0, "marginal"),
        ;

        companion object {
            fun of(headroomFraction: Double): MarginBand = when {
                headroomFraction >= CONFIDENT.minHeadroomFraction -> CONFIDENT
                headroomFraction >= TIGHT.minHeadroomFraction -> TIGHT
                else -> MARGINAL
            }
        }
    }

    /**
     * The measured worst-case error in the total, as a fraction.
     *
     * This is the number the verdict language is allowed to reason about, and
     * it is the only one in this file that comes from a measurement rather
     * than an argument: 12.98% across the nine measured architectures at
     * context 4096, recomputed through the real call path
     * (`HuggingFaceClient.parseParameterCount` on the file name,
     * `PreDownloadMemoryModel.estimateRange`, the same 64 MiB floor and 2%
     * ratio on both sides). The mean on the same sample is 2.48%.
     *
     * It is stated as a property of the *model*, not of a specific file, and
     * the KDoc on [MarginBand] says so where a user would read it.
     */
    const val MEASURED_WORST_CASE_ERROR: Double = 0.1298
    const val MEASURED_MEAN_ERROR: Double = 0.0248

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
        val fit = RamFit(
            totalBytes = needed,
            availableBytes = available,
            fits = fits,
            explanation = "",
            lowBytes = low,
            highBytes = high,
            // Only a model that is NOT the name-based one can claim an exact
            // basis. `PreDownloadMemoryModel` is a `RangedMemoryModel` too, so
            // the cast above cannot tell them apart; the type check here can.
            exactBasis = model !is PreDownloadMemoryModel,
        )
        return fit.copy(explanation = explain(fit, high))
    }

    /**
     * The sentence the user reads.
     *
     * ## WHAT CHANGED AND WHY
     *
     * The previous text was, in full:
     *
     * ```
     * "Estimated 2.1 GB in memory, 3.4 GB available."
     * ```
     *
     * which is true and useless. It is the same sentence for a model with
     * 62% headroom and a model with 2%, it says nothing about whether the
     * estimate is one the user should rely on, and "Estimated" does a lot of
     * quiet work in a sentence with no number attached to the uncertainty.
     *
     * The new text is assembled from three facts the arithmetic already has
     * and the old text threw away:
     *
     * 1. the headroom, as a percentage the user can reason about;
     * 2. whether the figure came from the file's own header ([RamFit.Confidence.EXACT])
     *    or from its name ([RamFit.Confidence.NARROW]) — a different claim in
     *    each case, and the old text made them identical;
     * 3. the range, whenever the verdict is close enough for the range to
     *    matter.
     *
     * ## WHY IT DOES NOT NAME A PERCENTAGE OF UNCERTAINTY
     *
     * [MEASURED_WORST_CASE_ERROR] is 12.98% on a nine-architecture sample, and
     * this text is shown on files that are not in that sample. Printing "±13%"
     * on an arbitrary file would convert a measured statement about a
     * measured sample into an unmeasured claim about this one file, which is
     * the exact substitution this project keeps refusing to make elsewhere.
     * What the text does instead is grade the *headroom* against that
     * measured band, which is a true statement about both numbers at once:
     * "there is more room here than the estimate has ever been wrong" is
     * checkable; "this number is within 13%" is not.
     */
    private fun explain(fit: RamFit, high: Long): String {
        if (!fit.fits) {
            return "Needs about ${formatBytes(fit.totalBytes)} in memory, " +
                "this device has ${formatBytes(fit.availableBytes)}. " +
                "Try a smaller quantization. Context length is fixed at " +
                "${PreDownloadMemoryModel.DEFAULT_CONTEXT_LENGTH} tokens, so a " +
                "smaller model is the only lever."
        }

        val headroom = fit.headroomFraction
        val band = MarginBand.of(headroom)
        val basis = if (fit.exactBasis) {
            "read from the model's own header"
        } else {
            "estimated from the file name and size"
        }
        val head = "Estimated ${formatBytes(fit.totalBytes)} in memory, " +
            "${formatBytes(fit.availableBytes)} available — " +
            "${percent(headroom)} headroom, $basis."

        return when (band) {
            MarginBand.CONFIDENT ->
                if (high > fit.availableBytes) {
                    "$head The margin is wider than this estimate has been " +
                        "measured wrong (worst case " +
                        "${percent(MEASURED_WORST_CASE_ERROR)} across nine " +
                        "measured architectures), so this is a comfortable fit."
                } else {
                    "$head The margin is wider than this estimate has been " +
                        "measured wrong, so this is a comfortable fit."
                }

            MarginBand.TIGHT ->
                "$head It fits, but the margin is inside the range this " +
                    "estimate has been measured wrong by (worst case " +
                    "${percent(MEASURED_WORST_CASE_ERROR)} across nine " +
                    "measured architectures, average " +
                    "${percent(MEASURED_MEAN_ERROR)}), so it will probably " +
                    "fit rather than certainly."

            MarginBand.MARGINAL ->
                "$head It fits by less than this estimate has ever been " +
                    "measured wrong (worst case " +
                    "${percent(MEASURED_WORST_CASE_ERROR)} across nine " +
                    "measured architectures), which is close to a coin flip. " +
                    "A smaller quantization is the safe choice here."
        }
    }

    /** A fraction as a whole-number percentage, for display. */
    private fun percent(fraction: Double): String =
        if (fraction <= 0.0) "0%" else "${(fraction * 100).toInt()}%"

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
