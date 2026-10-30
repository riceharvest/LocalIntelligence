package dev.localintelligence.core.model.gguf

/**
 * Turns a parsed [GgufHeader] into a memory estimate a UI can show *and* justify.
 *
 * ## The formula
 *
 * ```
 * weights  = sum over tensors:  ceil(elements / blockElements) * blockBytes
 *          (fallback: parameterCount * bitsPerWeight / 8, then: fileBytes)
 * kv cache = sum over attention layers of kvHeads_i * (keyDim + valueDim) * cells_i * bytesPerElement
 * recurrent = fixed state of the conv / Gated DeltaNet layers
 * overhead = compute buffer = n_vocab * n_outputs_max * 4 + fitted base
 * total    = weights + kvCache + recurrent + overhead
 * ```
 *
 * The `2` is the K and V tensors. Every term is a read from the file or exact block
 * arithmetic except the overhead constant, which is argued below.
 *
 * ## Why the tensor table is the primary source for weights
 *
 * The task's baseline formula is `parameters x bits-per-weight / 8`, and it is kept as
 * a documented fallback — but for k-quants it is wrong in a way that matters. A Q4_K
 * tensor stores 144 bytes per 256 weights: 4.5 bits each *including* the scales, and
 * rounded up to a whole block. Multiplying a parameter count by 4.5 bits and dividing by
 * 8 ignores every partial block and every scale, and it cannot represent a Q4_K_M file
 * at all, since that format's effective bit width depends on which tensors got promoted
 * to Q6_K. The tensor table has none of those problems: the byte count is the byte
 * count, and [GgufQuantType.bytesFor] reproduces llama.cpp's own `ceil(elements/256)*
 * 144` rule term for term.
 *
 * ## How the KV cache is priced
 *
 * The app creates every llama.cpp context with `swa_full = false`, `n_ubatch = 512`
 * and `n_outputs_max = 1` (the constructor parameters), so the cache is sized the way
 * `llama_kv_cache_iswa` sizes it:
 *
 * - a full-attention layer keeps `pad256(n_ctx)` cells;
 * - a sliding-window layer keeps `min(that, pad256(n_swa + n_ubatch))` cells;
 * - layers past `shared_kv_layers` store nothing (they read an earlier layer's cache);
 * - recurrent layers (short-conv, Gated DeltaNet) have no per-token cache but a fixed
 *   f32 state, priced into [MemoryEstimate.recurrentStateBytes].
 *
 * Checked against llama.cpp b11988 with `memprobe` (research-context-defaults.md 1.2):
 * LFM3-1.7B at 4K is 56 MiB there and 56 MiB here, Spark-4B 252 vs 252, Gemma 4 E2B
 * 36 vs 36, Qwen3.5-2B 67 vs 67.
 *
 * ## The compute buffer, fitted
 *
 * `n_vocab * n_outputs_max * 4` is the logits tensor. The rest is the graph's
 * activations, which llama.cpp does not size from anything in the header, so it is a
 * fit to the nine measured models: [COMPUTE_BASE_BYTES] plus [COMPUTE_BYTES_PER_WEIGHT_GIB]
 * per GiB of tensors, clamped to [COMPUTE_BASE_MIN_BYTES]..[COMPUTE_BASE_MAX_BYTES],
 * plus 1 MiB per 1K of context for the attention mask (2 MiB when sliding-window
 * layers keep a second mask). The fit is within about 20 MiB of every measured row.
 *
 * ## Lookup-only tensors
 *
 * An untied `token_embd` and `per_layer_token_embd` are read one row per token. llama.cpp
 * mmaps them, so only the touched pages are resident and the kernel can drop them. They
 * are reported in [MemoryEstimate.lookupOnlyBytes] and left out of the total.
 *
 */
class ModelMemoryEstimator(
    private val limits: GgufLimits = GgufLimits(),
    /** `swa_full` as the app creates its contexts. False: SWA layers keep a window, not the context. */
    private val swaFull: Boolean = false,
    /** `n_ubatch` the app creates its contexts with. */
    private val ubatch: Int = 512,
    /** `n_outputs_max` the app creates its contexts with (it only ever reads the last token's logits). */
    private val outputsMax: Int = 1,
) {

    /**
     * Context length assumed when the file declares none.
     *
     * WHY 2048: it is the smallest window that can hold a system prompt, a tool result
     * and a reply, so it is the smallest assumption that would not obviously understate
     * a real session. Choosing something larger would inflate every estimate for files
     * that simply omit the key, and choosing something smaller would hide the problem.
     * The assumption is always reported as [GgufWarning.ContextLengthMissing].
     */
    val assumedContextLength: Long = 2_048

    /**
     * Estimates the resident memory of a model at a given context length and cache type.
     *
     * [contextLengthOverride] lets a caller answer "would this fit at 8K" without
     * pretending the file says so. It is clamped by [GgufLimits.maxContextLength] for
     * the same reason a declared value is: this number is a multiplier in a product
     * with six other attacker-influenced factors.
     *
     * [quantTypeOverride] is the "what if I re-quantise to Q8" switch. It recomputes
     * the weights at that type using the parameter count from the tensor table, which is
     * why the table is parsed at all even when [quantTypeOverride] is null.
     */
    fun estimate(
        header: GgufHeader,
        contextLengthOverride: Long? = null,
        kvCacheType: KvCacheType = KvCacheType.F16,
        quantTypeOverride: GgufQuantType? = null,
    ): MemoryEstimate {
        val warnings = ArrayList<GgufWarning>(header.warnings)
        val inputs = ArrayList<EstimateInput>(12)
        val m = header.metadata

        // ---- context length ----------------------------------------------------
        val declaredCtx = m.contextLength ?: m.trainContextLength
        var contextLength = contextLengthOverride ?: declaredCtx ?: assumedContextLength
        if (declaredCtx == null && contextLengthOverride == null) {
            warnings += GgufWarning.ContextLengthMissing(contextLength)
        }
        if (contextLengthOverride == null && declaredCtx != null && declaredCtx > limits.maxContextLength) {
            warnings += GgufWarning.ContextLengthClamped(declaredCtx, limits.maxContextLength)
        }
        if (contextLength > limits.maxContextLength) {
            contextLength = limits.maxContextLength
        }
        inputs += EstimateInput(
            "Context length",
            "$contextLength tokens",
            if (contextLengthOverride != null) "caller override"
            else declaredCtx?.let { "<arch>.context_length" } ?: "assumed default",
        )

        // ---- geometry ----------------------------------------------------------
        val layers = m.blockCount
        // Per-layer KV heads (hybrid models store one entry per layer). The single figure
        // reported as "KV heads" is the largest, so a verdict that reads it stays conservative.
        val kvHeadsPerLayer = m.kvHeadsPerLayer
        val kvHeads = kvHeadsPerLayer?.maxOrNull()
        val headDim = m.resolveHeadDimension()
        val headDimValue = headDim?.value
        // K and V use separate widths only when the file declares both key_length and
        // value_length; otherwise both are the one head dimension, as they always were.
        val keyLengthDeclared = m.keyLength?.takeIf { it > 0 }
        val valueLengthDeclared = m.valueLength?.takeIf { it > 0 }
        val valueDimValue = if (keyLengthDeclared != null && valueLengthDeclared != null) {
            valueLengthDeclared
        } else {
            headDimValue
        }
        layers?.let { inputs += EstimateInput("Layers", it.toString(), "<arch>.block_count") }
        if (kvHeadsPerLayer != null) {
            val distinct = kvHeadsPerLayer.distinct()
            val arrayUsed = m.headCountKvArray?.size?.toLong() == layers
            val value = if (distinct.size == 1) distinct[0].toString()
            else "${kvHeadsPerLayer.min()}-${kvHeadsPerLayer.max()} per layer"
            val source = when {
                arrayUsed -> "<arch>.attention.head_count_kv (per layer)"
                m.headCountKv != null -> "<arch>.attention.head_count_kv"
                m.headCountKvArrayUnusable -> "<arch>.attention.head_count (per-layer array unusable)"
                else -> "<arch>.attention.head_count (MHA)"
            }
            inputs += EstimateInput("KV heads", value, source)
        }
        if (headDim != null) {
            inputs += EstimateInput("Head dimension", headDim.value.toString(), headDimSourceKey(headDim.source))
        }
        if (valueDimValue != null && valueDimValue != headDimValue) {
            inputs += EstimateInput("Value dimension", valueDimValue.toString(), "<arch>.attention.value_length")
        }
        m.expertCount?.let { inputs += EstimateInput("Experts", it.toString(), "<arch>.expert_count") }

        // ---- kv cache ----------------------------------------------------------
        val bytesPerElement = kvCacheType.bytesPerElement
        val kvPricing = if (layers == null || kvHeadsPerLayer == null || headDimValue == null || valueDimValue == null) {
            inputs += EstimateInput("KV cache", "not computable", "missing layer/head metadata")
            null
        } else {
            priceKv(m, layers.toInt(), kvHeadsPerLayer, headDimValue, valueDimValue, contextLength, bytesPerElement, inputs)
        }
        val kvCacheBytes = kvPricing?.kvBytes ?: 0L
        val recurrentBytes = recurrentStateBytes(m, kvPricing?.recurrent, inputs)

        // ---- weights -----------------------------------------------------------
        val tableParams = header.tensorTableParameterCount
        val parameterCount = tableParams ?: m.declaredParameterCount
        val effectiveQuant = quantTypeOverride ?: header.dominantQuantType
        val (weights, basis) = computeWeights(
            header = header,
            quantType = effectiveQuant,
            quantIsOverride = quantTypeOverride != null,
            parameterCount = parameterCount,
            inputs = inputs,
        )
        parameterCount?.let {
            inputs += EstimateInput("Parameters", formatCount(it), "tensor table" + if (tableParams != null) "" else " / general.parameter_count")
        }
        effectiveQuant?.let {
            inputs += EstimateInput(
                "Bits per weight",
                "%.2f".format(it.averageBitsPerWeight),
                if (quantTypeOverride != null) "caller override" else it.label,
            )
        }

        // ---- lookup-only tensors, compute buffer ---------------------------------
        val lookupOnly = if (quantTypeOverride == null && basis == EstimateBasis.TENSOR_TABLE) {
            lookupOnlyBytes(header)
        } else {
            0L
        }
        val residentWeights = (weights - lookupOnly).coerceAtLeast(0L)
        if (lookupOnly > 0) {
            inputs += EstimateInput(
                "Lookup-only tables",
                MemoryEstimate.formatBytes(lookupOnly),
                "mmap-backed, not resident (untied token_embd / per_layer_token_embd)",
            )
        }
        val overhead = computeBufferBytes(m, weights, contextLength, kvPricing?.hasSwa == true)
        inputs += EstimateInput(
            "Compute buffer",
            MemoryEstimate.formatBytes(overhead),
            "n_vocab x $outputsMax x 4 B + fitted base",
        )

        val total = saturatingAdd(
            saturatingAdd(residentWeights, kvCacheBytes),
            saturatingAdd(recurrentBytes, overhead),
        )

        return MemoryEstimate(
            basis = basis,
            weightsBytes = residentWeights,
            kvCacheBytes = kvCacheBytes,
            overheadBytes = overhead,
            totalBytes = total,
            declaredWeightBytes = header.declaredWeightBytes,
            parameterCount = parameterCount,
            quantType = effectiveQuant,
            bitsPerWeight = effectiveQuant?.averageBitsPerWeight,
            kvCacheType = kvCacheType,
            bytesPerKvElement = bytesPerElement,
            contextLength = contextLength,
            layers = layers,
            kvHeads = kvHeads,
            headDimension = headDimValue,
            headDimensionDerived = headDim?.source == GgufMetadata.HeadDimensionSource.EMBEDDING_LENGTH_OVER_HEAD_COUNT,
            inputs = inputs,
            warnings = warnings,
            recurrentStateBytes = recurrentBytes,
            lookupOnlyBytes = lookupOnly,
        )
    }

    /**
     * Convenience for "this exact file, two quantisations" — the comparison a user is
     * making when they wonder whether a Q8 copy is worth the space.
     *
     * WHY it needs the tensor table: to re-price the weights at another quantisation we
     * need the parameter count, and the only trustworthy source for that is the sum of
     * the tensor shapes. Re-deriving it from a file's `general.parameter_count` would
     * make the comparison depend on a field that is frequently absent.
     */
    fun estimateAlternatives(
        header: GgufHeader,
        quantTypes: List<GgufQuantType> = listOf(GgufQuantType.Q4_K, GgufQuantType.Q6_K, GgufQuantType.Q8_0),
        contextLengthOverride: Long? = null,
        kvCacheType: KvCacheType = KvCacheType.F16,
    ): List<MemoryEstimate> = quantTypes.map {
        estimate(header, contextLengthOverride, kvCacheType, quantTypeOverride = it)
    }

    /**
     * Resolves the weight figure, degrading through three sources and recording which
     * one was used.
     *
     * The order is not arbitrary. The tensor table is ground truth. A declared parameter
     * count is the documented formula and is exact only for uniform, unquantised
     * models. The file size is a floor — a GGUF is weights plus a header, so it never
     * over-states — and is the right answer when a file's header is readable but its
     * tensor table is not.
     */
    private fun computeWeights(
        header: GgufHeader,
        quantType: GgufQuantType?,
        quantIsOverride: Boolean,
        parameterCount: Long?,
        inputs: MutableList<EstimateInput>,
    ): Pair<Long, EstimateBasis> {
        val fromTable = header.declaredWeightBytes
        if (fromTable != null && !quantIsOverride) {
            inputs += EstimateInput(
                "Weights",
                MemoryEstimate.formatBytes(fromTable),
                "tensor table (${header.tensors.size} tensors)",
            )
            return fromTable to EstimateBasis.TENSOR_TABLE
        }
        if (parameterCount != null && quantType != null) {
            // parameters x bits-per-weight / 8, in floating point so the intermediate
            // never wraps. A 2^40 parameter count must not become a small number here.
            val bytes = (parameterCount.toDouble() * quantType.averageBitsPerWeight / 8.0)
                .coerceAtMost(Double.MAX_VALUE)
                .let { if (it >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else it.toLong() }
            inputs += EstimateInput(
                "Weights",
                MemoryEstimate.formatBytes(bytes),
                "%.3f B/weight x %s parameters".format(quantType.averageBitsPerWeight, formatCount(parameterCount)),
            )
            return bytes to EstimateBasis.PARAMETER_COUNT
        }
        if (header.fileBytes > 0) {
            inputs += EstimateInput(
                "Weights",
                MemoryEstimate.formatBytes(header.fileBytes),
                "file size (header only; tensor table unusable)",
            )
            return header.fileBytes to EstimateBasis.FILE_SIZE
        }
        inputs += EstimateInput("Weights", "unknown", "no tensor table, no parameter count, no file size")
        return 0L to EstimateBasis.UNKNOWN
    }

    /** What [priceKv] found: the cache bytes, whether any layer is sliding-window, and which layers are recurrent. */
    private class KvPricing(val kvBytes: Long, val hasSwa: Boolean, val recurrent: List<Boolean>?)

    private fun pad256(n: Long): Long = (n + 255L) / 256L * 256L

    /**
     * KV bytes the way llama.cpp sizes them with this estimator's `swa_full` / `n_ubatch`.
     * See the class KDoc. A layer is skipped when it has no KV heads, is recurrent, or is
     * one of the trailing `shared_kv_layers`.
     */
    private fun priceKv(
        m: GgufMetadata,
        layers: Int,
        kvHeadsPerLayer: List<Long>,
        keyDim: Long,
        valueDim: Long,
        contextLength: Long,
        bytesPerElement: Double,
        inputs: MutableList<EstimateInput>,
    ): KvPricing {
        val recurrent = m.recurrentLayers
        val swa = m.swaLayers
        val window = m.slidingWindow
        val keySwa = m.keyLengthSwa ?: keyDim
        val valueSwa = m.valueLengthSwa ?: valueDim
        val storing = layers - (m.sharedKvLayers ?: 0L).coerceIn(0L, layers.toLong()).toInt()
        val fullCells = pad256(contextLength)
        val swaCells = if (window == null || swaFull) fullCells else minOf(fullCells, pad256(window + ubatch))
        var total = 0.0
        var globalLayers = 0
        var swaLayerCount = 0
        for (i in 0 until layers) {
            if (i >= storing) continue
            if (recurrent != null && recurrent[i]) continue
            val heads = kvHeadsPerLayer[i]
            if (heads <= 0L) continue
            val isSwa = swa != null && swa[i]
            if (isSwa) {
                swaLayerCount++
                total += heads.toDouble() * (keySwa + valueSwa).toDouble() * swaCells * bytesPerElement
            } else {
                globalLayers++
                total += heads.toDouble() * (keyDim + valueDim).toDouble() * fullCells * bytesPerElement
            }
        }
        inputs += EstimateInput("Bytes per KV element", "%.3f".format(bytesPerElement), "cache type")
        inputs += EstimateInput(
            "KV layers",
            "$globalLayers full-attention" + if (swaLayerCount > 0) " + $swaLayerCount sliding-window ($swaCells cells)" else "",
            "block_count, head_count_kv, sliding_window_pattern, shared_kv_layers, full_attention_interval",
        )
        val bytes = if (!total.isFinite() || total >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else total.toLong()
        return KvPricing(bytes, swaLayerCount > 0, recurrent)
    }

    /**
     * Fixed per-sequence state of the non-attention layers, f32.
     *
     * - Short-conv (LFM2/LFM3): `(l_cache - 1) * n_embd` per layer.
     * - Gated DeltaNet (qwen35): `(conv_kernel - 1) * (inner + 2 * groups * state)` conv
     *   state plus `state_size * inner` recurrent state per layer.
     */
    private fun recurrentStateBytes(m: GgufMetadata, recurrent: List<Boolean>?, inputs: MutableList<EstimateInput>): Long {
        val n = recurrent?.count { it } ?: return 0L
        if (n == 0) return 0L
        val perLayer: Long = run {
            val inner = m.ssmInnerSize
            val state = m.ssmStateSize
            val kernel = m.ssmConvKernel
            if (inner != null && state != null && kernel != null) {
                val groups = m.ssmGroupCount ?: 1L
                ((kernel - 1).coerceAtLeast(0) * (inner + 2 * groups * state) + state * inner) * 4L
            } else {
                val l = m.shortConvCache
                val embd = m.embeddingLength
                if (l != null && embd != null) (l - 1) * embd * 4L else 0L
            }
        }
        val bytes = perLayer * n
        if (bytes > 0) {
            inputs += EstimateInput("Recurrent state", MemoryEstimate.formatBytes(bytes), "$n recurrent layers x f32 state")
        }
        return bytes
    }

    /** Tensor bytes that are only ever row-looked-up, so the OS need not keep them resident. */
    private fun lookupOnlyBytes(header: GgufHeader): Long {
        val untied = header.tensors.any { it.name == "output.weight" }
        var total = 0L
        for (t in header.tensors) {
            if (t.name == "per_layer_token_embd.weight" || (untied && t.name == "token_embd.weight")) {
                total = saturatingAdd(total, t.bytes ?: 0L)
            }
        }
        return total
    }

    /** `n_vocab * n_outputs_max * 4` plus the fitted graph base. See the class KDoc. */
    private fun computeBufferBytes(m: GgufMetadata, tensorBytes: Long, contextLength: Long, hasSwa: Boolean): Long {
        val vocab = m.vocabularySize ?: DEFAULT_VOCAB
        val logits = vocab * outputsMax * 4L
        val gib = tensorBytes.toDouble() / (1024.0 * 1024 * 1024)
        val base = (COMPUTE_BASE_BYTES + COMPUTE_BYTES_PER_WEIGHT_GIB * gib).toLong()
            .coerceIn(COMPUTE_BASE_MIN_BYTES, COMPUTE_BASE_MAX_BYTES)
        val mask = contextLength / 1024 * MIB * (if (hasSwa) 2 else 1)
        return logits + base + mask
    }

    private fun headDimSourceKey(source: GgufMetadata.HeadDimensionSource): String =
        when (source) {
            GgufMetadata.HeadDimensionSource.KEY_LENGTH -> "<arch>.attention.key_length"
            GgufMetadata.HeadDimensionSource.ROPE_DIMENSION_COUNT -> "<arch>.rope.dimension_count"
            GgufMetadata.HeadDimensionSource.EMBEDDING_LENGTH_OVER_HEAD_COUNT ->
                "<arch>.embedding_length / <arch>.attention.head_count"
        }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000_000L -> "%.1fB".format(n / 1e9)
        n >= 1_000_000L -> "%.1fM".format(n / 1e6)
        n >= 1_000L -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }

    companion object {
        private const val MIB: Long = 1024L * 1024

        /** Vocabulary assumed when the file has no token list. */
        private const val DEFAULT_VOCAB: Long = 152_000L

        /** Fitted graph base, bytes: the intercept of compute buffer against tensor size. */
        const val COMPUTE_BASE_BYTES: Long = 28L * MIB

        /** Fitted slope: extra graph base per GiB of tensors. */
        const val COMPUTE_BYTES_PER_WEIGHT_GIB: Double = 22.0 * MIB

        const val COMPUTE_BASE_MIN_BYTES: Long = 25L * MIB
        const val COMPUTE_BASE_MAX_BYTES: Long = 130L * MIB
    }
}
