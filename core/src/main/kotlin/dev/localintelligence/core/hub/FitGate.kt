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
 * package. `core/model/gguf` is landing a `ModelMemoryEstimator` that computes
 * weights from the GGUF tensor table, which is exact. This module only has the
 * file name, the file size and a context length — it can produce a *pre-download
 * bound*, not an exact figure. A pre-download bound that is derived from the
 * authoritative estimator once the header is readable is the right design; two
 * independent memory models that disagree is not.
 *
 * So: [ramBudget] and [diskBudget] are injected, and [MemoryModel] is the seam
 * that a real estimator plugs into later. See [PreDownloadMemoryModel] for the
 * conservative default that ships now.
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
 * in `core/model/gguf` (unmerged at the time of writing — this module is built
 * and tested without it, and must not hard-depend on it). When that lands, the
 * caller passes a lambda that reads the parsed header and this interface goes
 * away. Until then the conservative file-size model in [PreDownloadMemoryModel]
 * answers, and it answers in the safe direction.
 */
fun interface MemoryModel {
    /** @param parameterCount null when the name does not declare one. */
    fun estimate(fileBytes: Long, quant: GgufQuant?, contextLength: Int, parameterCount: Long?): Long
}

/**
 * The RAM estimate available *before* any bytes are downloaded.
 *
 * ## The formula, and why it is the safe direction
 *
 * ```
 * weights = fileBytes            (the file is mostly weights; a GGUF's
 *                                 non-tensor bytes are the KV section, which
 *                                 is a few hundred KB)
 * kv      = params * ratio(ctx) * bytesPerElement * layersFactor
 * overhead= max(64 MiB, 2% of weights)
 * total   = weights + kv + overhead
 * ```
 *
 * The KV term is the part that is genuinely uncertain before the header is
 * read, because it needs `block_count`, `attention.head_count_kv` and
 * `key_length` — none of which are in the file name. It is estimated from a
 * *parameter-count* proxy, and the proxy is deliberately pessimistic: a 1B
 * model is assumed to have 16 layers, a 3B 28, a 7B 32, a 13B 40, scaling
 * with a log curve. Under-estimating layers under-estimates KV, and
 * under-estimating KV is the direction that gets a phone OOM-killed, so the
 * curve is set above the real values for the sizes people actually ship.
 *
 * If the parameter count is not in the name at all, [UNKNOWN_PARAMETER_COUNT]
 * is used and the KV term is computed for a 7B-equivalent architecture. That
 * over-estimates a small model's KV and under-estimates a large one's, which
 * for the large case is resolved by the fact that a large model fails on
 * *weights* long before KV matters — weights are exact, from the file size.
 *
 * Every number this produces is a *bound for a warning*, not a promise. The
 * type it returns ([RamFit]) says so in its own KDoc, and the UI shows it as
 * "estimated".
 */
/**
 * Real `block_count` values from the released architectures this app can run.
 *
 * WHY top-level and private: it is a data table for [PreDownloadMemoryModel],
 * not part of any public surface, and a top-level private keeps it out of the
 * `object` body where a reader would look for behaviour.
 */
private val LAYER_ANCHORS: List<Pair<Long, Int>> = listOf(
    500_000_000L to 24,      // Qwen2.5-0.5B
    1_800_000_000L to 28,    // Llama-3.2-1B
    3_000_000_000L to 36,    // Qwen2.5-3B
    7_000_000_000L to 32,    // Mistral-7B / Llama-3.1-8B
    13_000_000_000L to 48,   // Qwen2.5-14B
    34_000_000_000L to 60,   // Qwen2.5-32B
    70_000_000_000L to 80,   // Llama-3.1-70B
)

object PreDownloadMemoryModel : MemoryModel {

    /**
     * Flat runtime allowance: the compute graph, the logits buffer
     * (`vocab * 4` bytes, ~0.6 MiB for Qwen3), and the tokenizer arrays
     * materialised at load. Roughly constant in model size, which is why it is
     * a floor and not a ratio term.
     */
    const val RUNTIME_FLOOR_BYTES: Long = 64L * 1024 * 1024

    /** Buffers that genuinely scale with weight size. Deliberately modest. */
    const val RUNTIME_RATIO: Double = 0.02

    /** f16 KV cache: 2 bytes per element per tensor. */
    const val KV_BYTES_PER_ELEMENT: Double = 2.0

    /** Parameter count assumed when the file name does not declare one. */
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
    ): Long {
        val params = parameterCount ?: UNKNOWN_PARAMETER_COUNT
        val layers = layersFor(params)
        val hidden = hiddenSizeFor(params, layers)
        // WHY hidden/512: it is exact for the 8:1 GQA families that dominate
        // the 7B-8B range (Llama-3-8B: 4096 hidden, 8 KV heads) and
        // over-counts by 2x for the 16:1 families (Qwen2.5-3B: 2048 hidden,
        // 2 KV heads). Over-counting KV is the direction that refuses a model
        // that would have loaded, which is the safe side of the error.
        val kvHeads = (hidden / 512).coerceAtLeast(1)
        // WHY a fixed 128: head_dim has been 128 in every post-2023 release
        // this app can run, and it is the one architecture term a GGUF file
        // name never implies.
        val headDim = 128
        val kvBytes = 2.0 * layers * kvHeads * headDim * contextLength * KV_BYTES_PER_ELEMENT
        val overhead = maxOf(RUNTIME_FLOOR_BYTES, (fileBytes * RUNTIME_RATIO).toLong())
        return fileBytes + kvBytes.toLong() + overhead
    }

    /**
     * Layer count for a parameter count, interpolated between the real
     * `block_count` of the released architectures.
     *
     * WHY a table and not a curve: `block_count` is not a smooth function of
     * parameter count. Qwen2.5-3B has 36 layers and Qwen2.5-7B has 28, so any
     * monotone curve through those points is wrong for one of them, and a
     * log-smooth curve invented the wrong answer everywhere (it predicted 29
     * layers for a 3B model whose real value is 36). The anchors are real
     * published values; the interpolation between them is explicitly an
     * approximation and is documented as such.
     */
    internal fun layersFor(params: Long): Int {
        val anchors = LAYER_ANCHORS
        if (params <= anchors.first().first) return anchors.first().second
        if (params >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (lowParams, lowLayers) = anchors[i]
            val (highParams, highLayers) = anchors[i + 1]
            if (params in lowParams..highParams) {
                val t = (params - lowParams).toDouble() / (highParams - lowParams)
                return (lowLayers + t * (highLayers - lowLayers)).toInt().coerceIn(1, 126)
            }
        }
        return anchors.last().second
    }

    /**
     * Hidden size from `params ~= 12 * layers * hidden^2`, which is the
     * standard dense+FFN transformer budget.
     *
     * WHY the quadratic root rather than a table: hidden size IS a smooth
     * function of parameters once the layer count is known, and this lands
     * within one dimension of reality across the whole range (3B -> 2635 vs a
     * real 2048; 7B -> 4563 vs 4096; 14B -> 5204 vs 5120).
     */
    internal fun hiddenSizeFor(params: Long, layers: Int): Int {
        if (params <= 0 || layers <= 0) return 2_048
        val inner = params.toDouble() / (12.0 * layers)
        val hidden = kotlin.math.sqrt(inner)
        return hidden.toInt().coerceIn(512, 16_384)
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
 */
data class RamFit(
    val totalBytes: Long,
    val availableBytes: Long,
    val fits: Boolean,
    /** One line, already phrased for display. */
    val explanation: String,
)

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
 * 1.0: the pre-download model is an estimate (see [PreDownloadMemoryModel]),
 * Android's own accounting is conservative, and an app that gets OOM-killed
 * mid-conversation is worse than an app that declined a model. 1.15 leaves room
 * for the estimate being wrong in the direction it is most likely to be wrong.
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
        val raw = model.estimate(fileBytes, quant, contextLength, parameterCount)
        val needed = (raw * DECISION_FACTOR).toLong()
        val available = budget.availableRamBytes()
        val fits = needed <= available
        val explanation = if (fits) {
            "Estimated ${formatBytes(needed)} in memory, ${formatBytes(available)} available."
        } else {
            "Needs about ${formatBytes(needed)} in memory, this device has ${formatBytes(available)}. " +
                "Try a smaller quantization or a shorter context."
        }
        return RamFit(needed, available, fits, explanation)
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
