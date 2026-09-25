package dev.localintelligence.core.model.gguf

/**
 * `general.file_type` — llama.cpp's label for how the weights as a whole were
 * quantised, as opposed to [GgufQuantType] which is what one tensor's bytes are.
 *
 * WHY both are exposed: `file_type` is a single convenience number written by the
 * converter and is frequently *wrong or absent* for a hand-merged or re-quantised
 * file, while the tensor table is ground truth. So the parser records the declared
 * file type as a claim, the estimator prefers the tensor-table mode, and a
 * disagreement between the two becomes a warning the UI can show rather than a silent
 * wrong answer.
 */
enum class GgufFileType(val id: Int, val label: String, val representativeQuant: GgufQuantType?) {
    ALL_F32(0, "F32", GgufQuantType.F32),
    MOSTLY_F16(1, "F16", GgufQuantType.F16),
    MOSTLY_Q4_0(2, "Q4_0", GgufQuantType.Q4_0),
    MOSTLY_Q4_1(3, "Q4_1", GgufQuantType.Q4_1),
    MOSTLY_Q4_1_SOME_F16(4, "Q4_1_SOME_F16", GgufQuantType.Q4_1),
    MOSTLY_Q4_2(5, "Q4_2", GgufQuantType.Q4_2),
    MOSTLY_Q4_3(6, "Q4_3", GgufQuantType.Q4_3),
    MOSTLY_Q8_0(7, "Q8_0", GgufQuantType.Q8_0),
    MOSTLY_Q5_0(8, "Q5_0", GgufQuantType.Q5_0),
    MOSTLY_Q5_1(9, "Q5_1", GgufQuantType.Q5_1),
    MOSTLY_Q2_K(10, "Q2_K", GgufQuantType.Q2_K),
    MOSTLY_Q3_K_S(11, "Q3_K_S", GgufQuantType.Q3_K),
    MOSTLY_Q3_K_M(12, "Q3_K_M", GgufQuantType.Q3_K),
    MOSTLY_Q3_K_L(13, "Q3_K_L", GgufQuantType.Q3_K),
    MOSTLY_Q4_K_S(14, "Q4_K_S", GgufQuantType.Q4_K),
    MOSTLY_Q4_K_M(15, "Q4_K_M", GgufQuantType.Q4_K),
    MOSTLY_Q5_K_S(16, "Q5_K_S", GgufQuantType.Q5_K),
    MOSTLY_Q5_K_M(17, "Q5_K_M", GgufQuantType.Q5_K),
    MOSTLY_Q6_K(18, "Q6_K", GgufQuantType.Q6_K),
    MOSTLY_IQ2_XXS(19, "IQ2_XXS", GgufQuantType.IQ2_XXS),
    MOSTLY_IQ2_XS(20, "IQ2_XS", GgufQuantType.IQ2_XS),
    MOSTLY_Q2_K_S(21, "Q2_K_S", GgufQuantType.Q2_K),
    // "IQ3_XS" is a legacy file_type label with no matching ggml tensor type, so it maps
    // to nothing. Null keeps the claim out of the estimate instead of inventing a bits
    // per weight for a format this build cannot size.
    MOSTLY_IQ3_XS(22, "IQ3_XS", null),
    MOSTLY_IQ3_XXS(23, "IQ3_XXS", GgufQuantType.IQ3_XXS),
    MOSTLY_IQ1_S(24, "IQ1_S", GgufQuantType.IQ1_S),
    MOSTLY_IQ4_NL(25, "IQ4_NL", GgufQuantType.IQ4_NL),
    MOSTLY_IQ3_S(26, "IQ3_S", GgufQuantType.IQ3_S),
    MOSTLY_IQ3_M(27, "IQ3_M", GgufQuantType.IQ3_S),
    MOSTLY_IQ2_S(28, "IQ2_S", GgufQuantType.IQ2_S),
    MOSTLY_IQ2_M(29, "IQ2_M", GgufQuantType.IQ2_S),
    MOSTLY_IQ4_XS(30, "IQ4_XS", GgufQuantType.IQ4_XS),
    MOSTLY_IQ1_M(31, "IQ1_M", GgufQuantType.IQ1_M),
    MOSTLY_BF16(32, "BF16", GgufQuantType.BF16),
    MOSTLY_Q4_0_4_4(33, "Q4_0_4_4", GgufQuantType.Q4_0_4_4),
    MOSTLY_Q4_0_4_8(34, "Q4_0_4_8", GgufQuantType.Q4_0_4_8),
    MOSTLY_Q4_0_8_8(35, "Q4_0_8_8", GgufQuantType.Q4_0_8_8),
    MOSTLY_TQ1_0(36, "TQ1_0", GgufQuantType.TQ1_0),
    MOSTLY_TQ2_0(37, "TQ2_0", GgufQuantType.TQ2_0),
    ;

    /** A sensible "bits per weight" for a file declaring this type, for display only. */
    val averageBitsPerWeight: Double?
        get() = representativeQuant?.averageBitsPerWeight

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Null when the declared id is not one this build knows; the claim is dropped, the file is not rejected. */
        fun fromId(id: Long): GgufFileType? = byId[id.toInt()]?.takeIf { it.id.toLong() == id }

        /**
         * Tolerance in bits when comparing a declared file type against the tensor
         * table's dominant type.
         *
         * WHY one bit: Q4_K_M is legitimately a mix of Q4_K (4.50) and Q6_K (6.56) and
         * any converter naming it by the dominant part is right. Reporting that as a
         * conflict would make the warning meaningless, so only a disagreement wider than
         * a format's own internal spread counts.
         */
        const val TOLERANCE_BITS: Double = 1.0

        /** True when a declared type and the tensor table describe the same quantisation. */
        fun agreesWith(declared: GgufFileType, dominant: GgufQuantType): Boolean {
            val declaredBits = declared.averageBitsPerWeight ?: return true
            return kotlin.math.abs(declaredBits - dominant.averageBitsPerWeight) <= TOLERANCE_BITS
        }
    }
}
