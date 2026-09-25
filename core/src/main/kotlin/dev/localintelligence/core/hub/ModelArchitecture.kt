package dev.localintelligence.core.hub

/**
 * The two architecture numbers a KV-cache estimate needs, and the measured
 * evidence behind them.
 *
 * ## Why this file exists
 *
 * The KV cache of a decoder-only transformer is
 *
 * ```
 * kvBytes = 2 * layers * kvWidth * contextLength * bytesPerElement
 * ```
 *
 * where `kvWidth` is `head_count_kv * key_length` — the model's *KV projection
 * width*. `bytesPerElement` is 2 for the f16 cache llama.cpp defaults to. That
 * is the whole formula, and every factor in it except the context length is
 * architecture, not quantisation.
 *
 * Before any bytes are downloaded, none of that architecture is in the file
 * name. The previous implementation invented it from a parameter count with a
 * quadratic root and then split the result with a hard-coded 512 and a
 * hard-coded head dimension of 128. That is two independent guesses multiplied
 * together, and it is wrong in both directions — see [MEASURED] below.
 *
 * ## What is measured, and how
 *
 * Every row of [MEASURED] was read out of a real file's GGUF metadata
 * (`&lt;arch&gt;.block_count`, `&lt;arch&gt;.attention.head_count_kv`,
 * `&lt;arch&gt;.attention.key_length`), from files fetched with a byte-range
 * request against huggingface.co. The exact command and the raw numbers are in
 * `docs/memory-model.md`.
 *
 * MEASURED — KV cache at context 4096, old model vs the file's own metadata,
 * 9 architectures, 160 GGUFs probed:
 *
 * ```
 * architecture                      old      measured   old/measured
 * Qwen2.5-0.5B-Instruct (0.63B)     100.7M     50.3M      2.00x over
 * gemma-3-1b-it           (1.00B)    157.3M    109.1M      1.44x over
 * TinyLlama-1.1B-Chat     (1.10B)    157.3M     92.3M      1.70x over
 * Llama-3.2-1B-Instruct   (1.24B)    163.6M    134.2M      1.22x over
 * gemma-2-2b-it           (2.61B)    346.0M    436.2M      0.79x UNDER
 * Qwen2.5-3B-Instruct     (3.09B)    367.0M    151.0M      2.43x over
 * Phi-3-mini-4k-instruct  (3.82B)    367.0M   1610.6M      0.23x UNDER
 * Mistral-7B-Instruct     (7.24B)    536.9M    536.9M      1.00x
 * Llama-3.1-8B-Instruct   (8.03B)    570.4M    536.9M      1.06x
 * ```
 *
 * Phi-3-mini is the dangerous one: full multi-head attention, `kvWidth` = 3072
 * = its whole embedding width, and the old model charged it for 384 MiB when
 * the file needs 1.5 GiB. A 1.1 GiB hole in a fit verdict is how a phone gets
 * OOM-killed after the user has already spent the download.
 *
 * ## The one number a file name cannot give you
 *
 * `kvWidth` is `embedding_length` divided by the grouped-query ratio
 * `head_count / head_count_kv`. The measured ratios across the nine
 * architectures above are 1, 2, 4, 4, 4, 7, 8, 8, 8 — so the ratio is *not*
 * knowable from a parameter count, and assuming any single one of them is a
 * guess that can be 8x wrong. This is why [forParameterCount] returns a
 * [KvWidthBand] rather than a value, and why [FitGate] can show a range.
 */
internal data class ModelArchitecture(
    val parameters: Long,
    val layers: Int,
    /** `head_count_kv * key_length`: the width of one K (or V) vector per layer. */
    val kvWidth: Int,
    /** `embedding_length`, needed to express the GQA ratio the width implies. */
    val embeddingLength: Int,
    /** Where the numbers came from, for the UI's "estimated from" line. */
    val source: Source,
) {
    enum class Source {
        /** Read from this architecture's own GGUF metadata. See [MEASURED]. */
        MEASURED,

        /**
         * Interpolated between measured rows, or derived from
         * `params ~= 12 * layers * embedding^2` for a size no row covers.
         */
        DERIVED,
    }

    /** `head_count / head_count_kv`, the grouped-query ratio. 1 is full MHA. */
    val groupQueryRatio: Double
        get() = if (kvWidth <= 0) 1.0 else embeddingLength.toDouble() / kvWidth
}

/**
 * A KV width the estimate is not sure of, because a file name does not say
 * whether the model uses grouped-query attention.
 *
 * WHY a band and not an error: refusing to estimate would push every
 * un-probed file onto the "we don't know" path, and a fit gate that always
 * says "we don't know" is a fit gate nobody reads. The three points are the
 * real ends of the measured distribution:
 *
 * - [low]: GQA 8 — measured for Qwen2.5-3B and TinyLlama-1.1B
 * - [central]: GQA 4 — measured for Llama-3.1-8B, Mistral-7B and gemma-2-2b,
 *   and the median ratio of the nine measured architectures
 * - [high]: GQA 1 (plain MHA) — measured for Phi-3-mini
 *
 * [central] is what the verdict is decided on and [high] is what the user is
 * told about, because a model that needs the high figure and was told the low
 * one is the failure this whole feature exists to prevent.
 */
internal data class KvWidthBand(
    val low: Int,
    val central: Int,
    val high: Int,
)

/**
 * Real architectures, read from real files.
 *
 * WHY a table of nine and not a formula: `block_count` is not a smooth function
 * of parameter count. Llama-3.2-1B has 16 layers and Qwen2.5-3B has 36, so any
 * monotone curve through those points is wrong for one of them. And `kvWidth`
 * is not a function of parameter count at all beyond the factor-of-8 spread
 * above. A table of measured values is the only honest form, and a table of
 * measured values is only honest if it says where they came from.
 *
 * Nine architectures is a sample, not a census. [forParameterCount] is written
 * so that a file matching none of them degrades to a *conservative* estimate
 * rather than a confident wrong one, and the UI is told which happened.
 */
internal object MeasuredArchitectures {

    /**
     * Sorted by parameter count. `params` is the sum of the tensor table's
     * element counts, not the number in the model card: for TinyLlama-1.1B the
     * card says 1.1B and the table sums to 1,100,048,384.
     */
    val MEASURED: List<ModelArchitecture> = listOf(
        ModelArchitecture(630_139_776L, layers = 24, kvWidth = 128, embeddingLength = 896, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(999_885_952L, layers = 26, kvWidth = 256, embeddingLength = 1_152, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(1_100_048_384L, layers = 22, kvWidth = 256, embeddingLength = 2_048, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(1_235_814_432L, layers = 16, kvWidth = 512, embeddingLength = 2_048, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(2_614_341_888L, layers = 26, kvWidth = 1_024, embeddingLength = 2_304, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(3_085_846_528L, layers = 36, kvWidth = 256, embeddingLength = 2_048, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(3_821_079_552L, layers = 32, kvWidth = 3_072, embeddingLength = 3_072, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(7_241_732_096L, layers = 32, kvWidth = 1_024, embeddingLength = 4_096, source = ModelArchitecture.Source.MEASURED),
        ModelArchitecture(8_030_261_312L, layers = 32, kvWidth = 1_024, embeddingLength = 4_096, source = ModelArchitecture.Source.MEASURED),
    )

    /**
     * Which measured architecture a parameter count is close enough to that its
     * geometry can stand in.
     *
     * WHY 25%: within a quarter of the parameter count, two released models of
     * the same generation and family differ in layer count by at most a few
     * (Llama-3.1-8B and Mistral-7B, 9% apart in parameters, share both 32
     * layers and a 1024 KV width). Outside a quarter, borrowing the geometry
     * is a guess dressed as a measurement, so [forParameterCount] says
     * [ModelArchitecture.Source.DERIVED] instead.
     */
    const val MATCH_TOLERANCE: Double = 1.25

    /**
     * Architecture for a parameter count: a measured row when one is close
     * enough, otherwise a derived one.
     *
     * ## Why the derived path over-estimates, on purpose
     *
     * The derived embedding width comes from the standard dense-transformer
     * budget `params ~= 12 * layers * embedding^2`. Checked against the nine
     * measured architectures it over-estimates in seven of nine, by 0.3%
     * (TinyLlama: 2041 vs 2048) to 65% (Qwen2.5-0.5B: 1479 vs 896).
     * Over-estimating the embedding width over-estimates the KV cache, and
     * over-estimating the KV cache is the direction that refuses a model that
     * would have loaded — recoverable by the user, unlike the reverse.
     */
    fun forParameterCount(parameters: Long): ModelArchitecture {
        val nearest = MEASURED.minByOrNull { row ->
            val ratio = row.parameters.toDouble() / parameters.toDouble()
            if (ratio >= 1.0) ratio else 1.0 / ratio
        }
        if (nearest != null) {
            val ratio = nearest.parameters.toDouble() / parameters.toDouble()
            val distance = if (ratio >= 1.0) ratio else 1.0 / ratio
            if (distance <= MATCH_TOLERANCE) return nearest
            // Between two measured rows and within tolerance of neither: take
            // the wider of the two, which is the conservative direction.
            val second = MEASURED.filter { it !== nearest }
                .minByOrNull { row ->
                    val r = row.parameters.toDouble() / parameters.toDouble()
                    if (r >= 1.0) r else 1.0 / r
                }
            if (second != null) {
                val secondDistance = (second.parameters.toDouble() / parameters.toDouble())
                    .let { if (it >= 1.0) it else 1.0 / it }
                if (secondDistance <= MATCH_TOLERANCE) {
                    return if (second.kvWidth > nearest.kvWidth) {
                        second.copy(parameters = parameters, source = ModelArchitecture.Source.DERIVED)
                    } else {
                        nearest.copy(parameters = parameters, source = ModelArchitecture.Source.DERIVED)
                    }
                }
            }
        }
        val layers = layersFor(parameters)
        val embedding = embeddingLengthFor(parameters, layers)
        return ModelArchitecture(
            parameters = parameters,
            layers = layers,
            // The median measured GQA ratio, so the derived point estimate sits
            // in the middle of the measured distribution rather than at an end.
            kvWidth = (embedding / CENTRAL_GQA_RATIO).coerceAtLeast(1),
            embeddingLength = embedding,
            source = ModelArchitecture.Source.DERIVED,
        )
    }

    /**
     * The GQA band for an architecture, from its measured GQA ratio when it has
     * one and from the measured extremes when it does not.
     *
     * The [high] end is always plain MHA (`kvWidth == embeddingLength`). That is
     * not a worst case invented for safety: Phi-3-mini-4k-instruct really does
     * ship that way, at 3.8B parameters — inside the range this app targets.
     */
    fun kvWidthBand(architecture: ModelArchitecture): KvWidthBand {
        val embedding = architecture.embeddingLength.coerceAtLeast(1)
        val high = if (architecture.source == ModelArchitecture.Source.MEASURED) {
            // A measured row is measured; its own ratio is the answer, and the
            // band collapses to a point.
            KvWidthBand(low = architecture.kvWidth, central = architecture.kvWidth, high = architecture.kvWidth)
        } else {
            KvWidthBand(
                low = (embedding / WIDE_GQA_RATIO).coerceAtLeast(1),
                central = (embedding / CENTRAL_GQA_RATIO).coerceAtLeast(1),
                high = embedding,
            )
        }
        return high
    }

    /** GQA 8: the widest grouping measured (Qwen2.5-3B, TinyLlama-1.1B). */
    const val WIDE_GQA_RATIO: Int = 8

    /** GQA 4: the median of the nine measured architectures. */
    const val CENTRAL_GQA_RATIO: Int = 4

    /**
     * Layer counts for architectures no [MEASURED] row covers, interpolated on
     * the published `block_count` of the released models either side.
     *
     * WHY interpolate on log parameter count and not on a curve fitted to the
     * anchors: `block_count` is famously non-monotone in parameter count
     * (Qwen2.5-3B has 36 layers, Qwen2.5-7B has 28), so the honest statement
     * is "between these two measured values, we do not know", and a number
     * between them is the least-wrong way to say that.
     */
    private val LAYER_ANCHORS: List<Pair<Long, Int>> = listOf(
        500_000_000L to 24,      // Qwen2.5-0.5B, measured above
        1_100_000_000L to 22,    // TinyLlama-1.1B, measured above
        1_800_000_000L to 28,    // Llama-3.1-8B family scale
        3_000_000_000L to 36,    // Qwen2.5-3B, measured above
        7_000_000_000L to 32,    // Mistral-7B / Llama-3.1-8B, measured above
        13_000_000_000L to 48,   // Qwen2.5-14B
        34_000_000_000L to 60,   // Qwen2.5-32B
        70_000_000_000L to 80,   // Llama-3.1-70B
    )

    /**
     * WHY the table is only consulted for a derived architecture: a measured
     * row already carries its own layer count, and re-interpolating it would
     * replace a real number with an approximation of it.
     */
    fun layersFor(parameters: Long): Int {
        val anchors = LAYER_ANCHORS
        if (parameters <= 0L) return 24
        if (parameters <= anchors.first().first) return anchors.first().second
        if (parameters >= anchors.last().first) return anchors.last().second
        for (i in 0 until anchors.size - 1) {
            val (lowParams, lowLayers) = anchors[i]
            val (highParams, highLayers) = anchors[i + 1]
            if (parameters in lowParams..highParams) {
                val t = (parameters - lowParams).toDouble() / (highParams - lowParams)
                return (lowLayers + t * (highLayers - lowLayers)).toInt().coerceIn(1, 126)
            }
        }
        return anchors.last().second
    }

    /**
     * Embedding width from `params ~= 12 * layers * embedding^2`.
     *
     * WHY the quadratic root and not a table of embedding widths: the embedding
     * width *is* smooth in parameters once the layer count is known, so this
     * degrades gently outside the measured rows instead of snapping to a
     * neighbour's geometry. Measured error on the nine architectures, in the
     * order they appear in [MEASURED]: +65%, +23%, -0.3%, +24%, +26%, +30%,
     * +2.7%, +6%, +12%.
     */
    fun embeddingLengthFor(parameters: Long, layers: Int): Int {
        if (parameters <= 0L || layers <= 0) return 2_048
        val inner = parameters.toDouble() / (12.0 * layers)
        return kotlin.math.sqrt(inner).toInt().coerceIn(512, 16_384)
    }
}
