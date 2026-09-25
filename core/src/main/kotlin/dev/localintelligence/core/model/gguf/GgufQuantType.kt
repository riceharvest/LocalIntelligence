package dev.localintelligence.core.model.gguf

/**
 * The ggml tensor element type of a weight block, with the geometry needed to size it.
 *
 * WHY block geometry rather than "bits per weight": k-quants are block-quantised, so
 * `elements * bits / 8` is wrong at the edges — a Q4_K tensor of 100 elements is
 * ceil(100/256) blocks of 144 bytes, not 56 bytes. The estimator therefore asks this
 * type for [bytesFor] and gets the exact number llama.cpp will actually read, while
 * [averageBitsPerWeight] exists only so a UI can print a friendly "4.5 bit" label.
 *
 * [blockElements] and [blockBytes] are the real ggml `block_q*_K` sizes. They are
 * integers, so [bytesFor] cannot overflow per tensor as long as the element count is
 * sane — and the element count is itself computed with saturating arithmetic in
 * [GgufTensorInfo.elementCount].
 */
enum class GgufQuantType(
    val id: Int,
    val label: String,
    val blockElements: Int,
    val blockBytes: Int,
) {
    F32(0, "F32", 1, 4),
    F16(1, "F16", 1, 2),
    Q4_0(2, "Q4_0", 32, 18),
    Q4_1(3, "Q4_1", 32, 20),
    // Q4_2 and Q4_3 were removed from llama.cpp years ago. They are listed because a file
    // converted back then can still declare them, and a reader that answers "unknown"
    // for a real format is less useful than one that names it. Not annotated
    // @Deprecated: these entries document the format, they are not deprecated API.
    Q4_2(4, "Q4_2", 32, 22),
    Q4_3(5, "Q4_3", 32, 24),
    Q5_0(6, "Q5_0", 32, 22),
    Q5_1(7, "Q5_1", 32, 24),
    Q8_0(8, "Q8_0", 32, 34),
    // Likewise unused by any current llama.cpp writer, but still a valid type id.
    Q8_1(9, "Q8_1", 32, 36),
    Q2_K(10, "Q2_K", 256, 84),
    Q3_K(11, "Q3_K", 256, 110),
    Q4_K(12, "Q4_K", 256, 144),
    Q5_K(13, "Q5_K", 256, 176),
    Q6_K(14, "Q6_K", 256, 210),
    Q8_K(15, "Q8_K", 256, 292),
    IQ2_XXS(16, "IQ2_XXS", 256, 66),
    IQ2_XS(17, "IQ2_XS", 256, 74),
    IQ3_XXS(18, "IQ3_XXS", 256, 98),
    IQ1_S(19, "IQ1_S", 256, 50),
    IQ4_NL(20, "IQ4_NL", 32, 18),
    IQ3_S(21, "IQ3_S", 256, 110),
    IQ2_S(22, "IQ2_S", 256, 82),
    IQ4_XS(23, "IQ4_XS", 256, 136),
    I8(24, "I8", 1, 1),
    I16(25, "I16", 1, 2),
    I32(26, "I32", 1, 4),
    I64(27, "I64", 1, 8),
    F64(28, "F64", 1, 8),
    IQ1_M(29, "IQ1_M", 256, 56),
    BF16(30, "BF16", 1, 2),
    Q4_0_4_4(31, "Q4_0_4_4", 32, 18),
    Q4_0_4_8(32, "Q4_0_4_8", 32, 20),
    Q4_0_8_8(33, "Q4_0_8_8", 32, 24),
    TQ1_0(34, "TQ1_0", 256, 54),
    TQ2_0(35, "TQ2_0", 256, 66),
    MXFP4(36, "MXFP4", 32, 17),
    ;

    /**
     * Block-averaged cost of one weight, in bits.
     *
     * A display and cross-check value. It is *not* what the estimator uses, because
     * rounding a partial block to a fractional bit is how a memory estimate ends up
     * 200 bytes short and mysteriously un-loadable.
     */
    val averageBitsPerWeight: Double
        get() = blockBytes * 8.0 / blockElements

    /** Bytes a whole number of these blocks occupies, saturating instead of overflowing. */
    fun bytesForBlocks(blocks: Long): Long {
        if (blocks <= 0L) return 0L
        if (blocks > Long.MAX_VALUE / blockBytes) return Long.MAX_VALUE
        return blocks * blockBytes
    }

    /**
     * Exact on-disk bytes for [elements] weights of this type.
     *
     * Saturates at [Long.MAX_VALUE] rather than wrapping negative. A hostile tensor
     * declaring 2^62 elements must produce "unbelievably large", never "small and
     * loadable". The ceiling is computed as a division plus a remainder rather than the
     * usual `n + k - 1` trick, because that addition is itself the overflow for a
     * [elements] value anywhere near [Long.MAX_VALUE].
     */
    fun bytesFor(elements: Long): Long {
        if (elements <= 0L) return 0L
        val blocks = elements / blockElements + if (elements % blockElements == 0L) 0L else 1L
        return bytesForBlocks(blocks)
    }

    /** True for types that store float weights directly, so a "quant" label would be a lie. */
    val isFloat: Boolean
        get() = this == F32 || this == F16 || this == BF16 || this == F64

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Null for a type this build does not know; callers keep going rather than aborting a whole file. */
        fun fromTensorType(id: Long): GgufQuantType? = byId[id.toInt()]?.takeIf { it.id.toLong() == id }

        /**
         * The type that dominates a tensor table, i.e. the one covering the most elements.
         *
         * WHY dominant and not first-seen: every real model mixes types — a Q4_K_M file
         * stores `token_embd.weight` in Q6_K and the rest in Q4_K, and it may keep
         * `output_norm.weight` in F32. The mode is what a user means by "this is a Q4
         * model", and it is the type the "will it fit" warning should be keyed on.
         */
        fun dominant(tensors: List<GgufTensorInfo>): GgufQuantType? {
            val byWeight = HashMap<GgufQuantType, Long>(8)
            for (t in tensors) {
                val qt = t.quantType ?: continue
                byWeight[qt] = saturatingAdd(byWeight[qt] ?: 0L, t.elementCount)
            }
            return byWeight.entries.maxByOrNull { it.value }?.key
        }
    }
}

/** Adds without wrapping. Used wherever a hostile number is accumulated into a total. */
internal fun saturatingAdd(a: Long, b: Long): Long =
    if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

/** Multiplies without wrapping. A negative product from a hostile input is never meaningful. */
internal fun saturatingMul(a: Long, b: Long): Long = when {
    a <= 0L || b <= 0L -> 0L
    a > Long.MAX_VALUE / b -> Long.MAX_VALUE
    else -> a * b
}
