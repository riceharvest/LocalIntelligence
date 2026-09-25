package dev.localintelligence.core.model.gguf

/**
 * How llama.cpp stores the K and V caches, and what one element therefore costs.
 *
 * WHY bytes per element and not bits: the KV cache is a dense, fully-populated tensor
 * in practice, so there is no partial-block saving to make — the block-averaged figure
 * is exact. [Q8_0] and [Q4_0] correspond to `--cache-type-k`/`--cache-type-v`, which is
 * the knob a phone user actually turns when a 4 GiB model does not fit: halving or
 * quartering the cache is usually the difference between "will not load" and "loads".
 */
enum class KvCacheType(val label: String, val bytesPerElement: Double) {
    /** The default. 2 bytes per element per tensor. */
    F16("f16", 2.0),

    /** ~1.06 bytes per element — a 47% cache saving for a small quality cost. */
    Q8_0("q8_0", 34.0 / 32.0),

    /** ~0.56 bytes per element — a 72% cache saving; visible quality cost on long context. */
    Q4_0("q4_0", 18.0 / 32.0),
}

/**
 * One input that went into a [MemoryEstimate], with where it came from.
 *
 * WHY the estimate carries its own inputs: "will this fit" is a question a user asks
 * when they do not trust the answer, and the only thing that earns trust is showing the
 * arithmetic. [name] is what the UI labels, [value] is the number as displayed, and
 * [source] is the GGUF key or constant it came from — so "448 MiB" can be expanded to
 * "2 x 36 layers x 8 kv heads x 128 dim x 4096 ctx x 2.0 B" with nothing invented at
 * display time.
 */
data class EstimateInput(
    val name: String,
    val value: String,
    val source: String,
)

/**
 * How the weights figure was arrived at, weakest first.
 *
 * WHY expose the grade rather than a boolean: "1.83 GB" computed from the tensor table
 * and "1.83 GB" computed from a parameter count and a bits-per-word guess are not the
 * same claim, and collapsing them is how a memory model ends up confidently wrong for
 * exactly the models nobody tested. [TENSOR_TABLE] is measurement; [FILE_SIZE] is
 * arithmetic on a file we already have; [UNKNOWN] means we should refuse to answer.
 */
enum class EstimateBasis {
    /** Summed from the tensor table. The weight section's exact byte count. */
    TENSOR_TABLE,

    /** Parameter count x bits per weight / 8, per the documented formula. */
    PARAMETER_COUNT,

    /** The file on disk, which is mostly weights. Crude, but never an over-estimate. */
    FILE_SIZE,

    /** Nothing usable was declared. The total is meaningless and the UI must say so. */
    UNKNOWN,
}

/**
 * A memory estimate, with everything needed to argue with it.
 *
 * The three components are the whole model, and they are deliberately *not* collapsed
 * into one number:
 *
 * ```
 * weights  = sum over tensors of ceil(elements / blockElements) * blockBytes
 * kv cache = 2 * layers * kvHeads * headDim * contextLength * bytesPerElement
 * overhead = max(RUNTIME_BUFFER_FLOOR, RUNTIME_BUFFER_RATIO * weights)
 * total    = weights + kvCache + overhead
 * ```
 *
 * `2` is K and V. [overheadBytes] is the one genuinely hand-picked number here, and it
 * is isolated in [ModelMemoryEstimator.RUNTIME_BUFFER_FLOOR] and
 * [RUNTIME_BUFFER_RATIO] with the reasoning attached, because a constant nobody can
 * trace is how a memory model becomes a superstition. Everything else is either read
 * from the file or exact block arithmetic.
 *
 * [isEstimate] is `true` by construction. This type never claims to be a measurement
 * of what a runtime will allocate, because it cannot be: allocator behaviour, page-in
 * strategy, and backend graph sizes are all outside a header parse. What it *is* is a
 * defensible lower bound with a stated method, and [confidence] says how defensible.
 */
data class MemoryEstimate(
    val basis: EstimateBasis,
    /** Bytes of weights. 0 when [basis] is [EstimateBasis.UNKNOWN]. */
    val weightsBytes: Long,
    val kvCacheBytes: Long,
    val overheadBytes: Long,
    /** Sum of the three, saturating rather than wrapping. */
    val totalBytes: Long,
    /** Bytes of the weights section as the file itself declares it, when the tensor table was readable. */
    val declaredWeightBytes: Long?,
    val parameterCount: Long?,
    /** The quantisation the weight figure is for. Null when the file declared none this build knows. */
    val quantType: GgufQuantType?,
    /** Bits per weight for [quantType], for display. Null when unknown. */
    val bitsPerWeight: Double?,
    val kvCacheType: KvCacheType,
    /** Bytes per K or V element, so a UI can recompute for a different cache type. */
    val bytesPerKvElement: Double,
    val contextLength: Long,
    val layers: Long?,
    val kvHeads: Long?,
    val headDimension: Long?,
    /** True when the head dimension came from a division rather than a declared key. */
    val headDimensionDerived: Boolean,
    val inputs: List<EstimateInput>,
    val warnings: List<GgufWarning>,
) {
    /** Always true. Present so a UI cannot accidentally present this as a measurement. */
    val isEstimate: Boolean get() = true

    /**
     * How much to trust this number, derived from [basis] and the metadata behind it.
     *
     * [Confidence.MEASURED] is reserved for a complete tensor table: that is the only
     * path where every input is a fact from the file. [Confidence.GUESS] is the honest
     * label for a number built on an assumed context length.
     */
    val confidence: Confidence
        get() {
            if (basis == EstimateBasis.UNKNOWN) return Confidence.NONE
            if (basis != EstimateBasis.TENSOR_TABLE) return Confidence.GUESS
            if (layers == null || kvHeads == null || headDimension == null) return Confidence.GUESS
            // An assumed or clamped context means the KV figure is modelled, not known.
            val contextAssumed = warnings.any {
                it is GgufWarning.ContextLengthMissing || it is GgufWarning.ContextLengthClamped
            }
            return if (contextAssumed) Confidence.GUESS else Confidence.MEASURED
        }

    enum class Confidence {
        /** Every input read from a complete file. The weight figure is exact; the KV and overhead figures are still modelled. */
        MEASURED,

        /** Real numbers, but built on at least one assumed or derived input. */
        GUESS,

        /** Nothing usable in the header. Do not show a total. */
        NONE,
    }

    /** True when the estimate is at or below a given budget. Meaningless when [confidence] is [Confidence.NONE]. */
    fun fitsWithin(budgetBytes: Long): Boolean =
        confidence != Confidence.NONE && totalBytes <= budgetBytes

    /** One-line human summary. Deliberately says "estimate"; see [isEstimate]. */
    fun describe(): String =
        "${formatBytes(totalBytes)} estimated (${confidence.name.lowercase()}, " +
            "${weightsLabel()} weights, ${formatBytes(kvCacheBytes)} KV, ${formatBytes(overheadBytes)} overhead)"

    private fun weightsLabel(): String = quantType?.label ?: "unquantized"

    companion object {
        /** Binary units, because that is what every OS memory readout uses. */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KiB", "MiB", "GiB", "TiB")
            var value = bytes.toDouble()
            var unit = -1
            while (value >= 1024 && unit < units.size - 1) {
                value /= 1024
                unit++
            }
            return String.format("%.2f %s", value, units[unit])
        }
    }
}
