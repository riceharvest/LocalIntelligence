package dev.localintelligence.core.model.gguf

/**
 * Turns a parsed [GgufHeader] into a memory estimate a UI can show *and* justify.
 *
 * ## The formula
 *
 * ```
 * weights  = sum over tensors:  ceil(elements / blockElements) * blockBytes
 *          (fallback: parameterCount * bitsPerWeight / 8, then: fileBytes)
 * kv cache = 2 * layers * kvHeads * headDim * contextLength * bytesPerElement
 * overhead = max(RUNTIME_BUFFER_FLOOR, RUNTIME_BUFFER_RATIO * weights)
 * total    = weights + kvCache + overhead
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
 * ## Why the KV cache formula is the conservative one
 *
 * `2 * layers * kvHeads * headDim * ctx * bytesPerElement` is a *full* cache. llama.cpp
 * with a non-zero `--ubatch` and a `llama_context` sized to `ctx` does allocate roughly
 * that, and batched prefill is the moment peak usage occurs, so a fit-check that ignored
 * the cache would tell a user with 2.1 GiB of headroom that a 2.2 GiB model is fine.
 * Over-estimating the cache is the safe direction for a warning; the [KvCacheType]
 * argument is how a user who disagrees opts into the cheaper cache and re-checks.
 *
 * ## The overhead constant, argued
 *
 * [RUNTIME_BUFFER_FLOOR] is 64 MiB. A llama.cpp decode step needs the compute graph,
 * the RoPE tables, the logits buffer (`vocab` floats — 151936 x 4 bytes = 0.6 MiB for
 * Qwen3), and the token/score arrays the tokenizer materialises at load. For a small
 * model on a phone the logits buffer and tokenizer dominate, and both are roughly
 * constant rather than proportional to the model, which is exactly the shape a flat
 * floor models. 64 MiB is a round number above the small-model floor and well below the
 * point where it would mask a real "does not fit".
 *
 * [RUNTIME_BUFFER_RATIO] is 2% of the weights. This covers the parts that genuinely do
 * scale: page-in slack, the mmap working set, and backend graph allocations that track
 * hidden size. 2% is deliberately modest. The failure mode of an over-generous ratio is
 * a model that would have loaded being reported as not fitting, and a user who is told
 * "no" for a model that works will not trust the next "yes" either.
 *
 * Both constants are `const` so a reader can diff the assumptions against a future
 * measurement instead of reverse-engineering a magic number.
 */
class ModelMemoryEstimator(
    private val limits: GgufLimits = GgufLimits(),
) {

    /**
     * Context length assumed when the file declares none AND the caller states
     * none.
     *
     * NOT the app's context, and deliberately not
     * [dev.localintelligence.core.model.token.ContextCeiling.ALLOCATED_CONTEXT_TOKENS]:
     * this is the floor for a memory *estimate*, and the app allocates
     * [dev.localintelligence.core.model.token.ContextCeiling.ALLOCATED_CONTEXT_TOKENS].
     * It is recorded here so the two can never be confused for one number.
     *
     * WHY 2048: it is the smallest window that can hold a system prompt, a tool result
     * and a reply, so it is the smallest assumption that would not obviously understate
     * a real session. Choosing something larger would inflate every estimate for files
     * that simply omit the key, and choosing something smaller would hide the problem.
     * The assumption is always reported as [GgufWarning.ContextLengthMissing].
     *
     * UNREACHABLE IN THE SHIPPING PATH, and audited as part of the 6000-vs-4096
     * ceiling work: every production caller of [estimate] passes
     * `contextLengthOverride` (the importer, the downloader, the self-check and
     * `Diagnostics` all do), so this only fires for a direct library caller. It
     * was left at 2048 rather than "fixed" to the allocation because changing it
     * would move RAM-fit verdicts — a different subsystem, on a path nothing
     * currently reaches. Worth revisiting if that ever changes.
     */
    val assumedContextLength: Long = 2_048

    /** Flat runtime-buffer allowance. See the class KDoc for the argument. */
    val runtimeBufferFloorBytes: Long = RUNTIME_BUFFER_FLOOR

    /** Runtime buffers as a fraction of weight bytes. See the class KDoc for the argument. */
    val runtimeBufferRatio: Double = RUNTIME_BUFFER_RATIO

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
        val kvHeads = m.kvHeadCount
        val headDim = m.resolveHeadDimension()
        val headDimValue = headDim?.value
        layers?.let { inputs += EstimateInput("Layers", it.toString(), "<arch>.block_count") }
        kvHeads?.let {
            inputs += EstimateInput(
                "KV heads",
                it.toString(),
                if (m.headCountKv != null) "<arch>.attention.head_count_kv" else "<arch>.attention.head_count (MHA)",
            )
        }
        if (headDim != null) {
            inputs += EstimateInput("Head dimension", headDim.value.toString(), headDimSourceKey(headDim.source))
        }
        m.expertCount?.let { inputs += EstimateInput("Experts", it.toString(), "<arch>.expert_count") }

        // ---- kv cache ----------------------------------------------------------
        val bytesPerElement = kvCacheType.bytesPerElement
        val kvCacheBytes = if (layers == null || kvHeads == null || headDimValue == null) {
            inputs += EstimateInput("KV cache", "not computable", "missing layer/head metadata")
            0L
        } else {
            // 2 = K and V.
            //
            // WHY this is the one place the estimate multiplies in floating point:
            // bytesPerElement is fractional for the quantised cache types (q8_0 is
            // 34/32 = 1.0625), and truncating it to a Long turns every quantised cache
            // into an f16-sized one — the exact opposite of what the user asked for. All
            // other factors are integers and 1.0625 is exactly representable, so the
            // product is exact up to 2^53, roughly 9000x larger than any real model's
            // cache. Beyond that the product is not a number a phone has, and it
            // saturates rather than wrapping.
            val exact = 2.0 * layers * kvHeads * headDimValue * contextLength * bytesPerElement
            val cache = if (!exact.isFinite() || exact >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else exact.toLong()
            inputs += EstimateInput("Bytes per KV element", "%.3f".format(bytesPerElement), kvCacheType.label)
            cache
        }

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

        // ---- overhead ----------------------------------------------------------
        val ratioPart = (weights.toDouble() * runtimeBufferRatio).toLong()
        val overhead = maxOf(runtimeBufferFloorBytes, ratioPart)
        inputs += EstimateInput(
            "Runtime buffers",
            MemoryEstimate.formatBytes(overhead),
            "max(${MemoryEstimate.formatBytes(runtimeBufferFloorBytes)}, ${(runtimeBufferRatio * 100).toInt()}% of weights)",
        )

        val total = saturatingAdd(saturatingAdd(weights, kvCacheBytes), overhead)

        return MemoryEstimate(
            basis = basis,
            weightsBytes = weights,
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
        /** 64 MiB. Flat allowance for the compute graph, RoPE tables, logits and tokenizer arrays. */
        const val RUNTIME_BUFFER_FLOOR: Long = 64L * 1024 * 1024

        /** 2% of weight bytes for page-in slack, mmap working set and graph allocations. */
        const val RUNTIME_BUFFER_RATIO: Double = 0.02
    }
}
