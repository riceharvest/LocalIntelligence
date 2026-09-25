package dev.localintelligence.core.model.gguf

/**
 * One entry in the GGUF tensor table: name, shape, element type, and where its bytes
 * start.
 *
 * This is the single most valuable thing in the header, and the reason a header parse
 * beats a file-size heuristic: the tensor table states the *exact* number of bytes the
 * weights occupy, per tensor, with no arithmetic guesswork. 291 tensors at 4.5 bits
 * per weight is not an estimate, it is the file.
 *
 * [dims] and [declaredTypeId] are exactly what the file said, including when they are
 * nonsense. Nothing here interprets them; the safe interpretation lives in
 * [elementCount], [bytes] and [quantType], which saturate instead of overflowing.
 */
data class GgufTensorInfo(
    val name: String,
    val dims: List<Long>,
    /** The raw type id from the file, kept even when it is not a type this build knows. */
    val declaredTypeId: Long,
    /** Offset of this tensor's data from the start of the tensor-data section, as declared. */
    val declaredOffset: Long,
) {
    /** The known element type, or null if the file declared a type this build does not know. */
    val quantType: GgufQuantType?
        get() = GgufQuantType.fromTensorType(declaredTypeId)

    /**
     * Number of weights in this tensor, saturating at [Long.MAX_VALUE].
     *
     * Saturating rather than wrapping is the whole point: a hostile header declaring
     * dims `[2^40, 2^40]` must produce "impossibly large", not a negative count that
     * later multiplication would turn into a small, plausible, wrong size.
     */
    val elementCount: Long
        get() {
            var total = 1L
            for (d in dims) {
                if (d < 0) return 0L
                total = saturatingMul(total, d)
            }
            return total
        }

    /** Exact byte size of this tensor, or `null` when its type is unknown to this build. */
    val bytes: Long?
        get() = quantType?.bytesFor(elementCount)
}
