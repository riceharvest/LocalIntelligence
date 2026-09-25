package dev.localintelligence.core.model.gguf

/**
 * A decoded GGUF metadata value.
 *
 * WHY this is a closed sealed hierarchy instead of `Any`: the estimator reads numbers
 * out of a hostile file, and the whole safety argument rests on "every value is either
 * a number we can sanity-check, a bounded string, or a summarised array". An `Any`
 * would leak Kotlin boxed types into the estimator and quietly let a `List<Any>` of
 * 256k decoded tokens through.
 *
 * Integers arrive as a single [Signed] variant. Unsigned 64-bit values above
 * [Long.MAX_VALUE] are represented by their two's-complement bits, which is negative;
 * [asLong] returns `null` for those rather than handing back a negative length that
 * some later arithmetic might treat as "small". Callers that need the true magnitude
 * use [asLong] and get a "too large to represent" answer, which is the correct answer
 * for a length field.
 */
/** A true GGUF metadata value. */
sealed interface GgufValue {

    /** An integer, kept in the 64-bit two's-complement form it was read as. */
    data class Signed(val bits: Long) : GgufValue

    data class Float32(val value: Float) : GgufValue

    data class Float64(val value: Double) : GgufValue

    data class Bool(val value: Boolean) : GgufValue

    /** A bounded UTF-8 string. Never longer than `limits.maxStringLength`. */
    data class Text(val value: String) : GgufValue

    /**
     * An array, summarised rather than retained.
     *
     * WHY summarised: a Qwen3 vocabulary is 151936 strings. Materialising it to read a
     * count would make a "header parse" cost tens of megabytes of heap on a phone, so
     * the reader skips elements by width arithmetic and keeps [count] plus a short
     * [preview]. [count] is the load-bearing part: the vocabulary size that the memory
     * model needs *is* this number, and it is available without decoding anything.
     */
    data class Array(
        val elementType: GgufValueType,
        val count: Long,
        val preview: List<GgufValue>,
    ) : GgufValue

    /**
     * This value as a `Long`, or `null` if it is not an integer or does not fit.
     *
     * Returns `null` rather than throwing because the caller is almost always walking a
     * list of candidate keys where a type mismatch is expected and uninteresting, and a
     * `null` keeps that walk branch-free.
     */
    fun asLong(): Long? = when (this) {
        is Signed -> bits.takeIf { it >= 0 }
        is Bool -> if (value) 1L else 0L
        else -> null
    }

    /** This value as a `Double`, for the float types the spec uses for scales and temperatures. */
    fun asDouble(): Double? = when (this) {
        is Float32 -> value.toDouble()
        is Float64 -> value
        is Signed -> bits.toDouble()
        is Bool -> if (value) 1.0 else 0.0
        else -> null
    }

    fun asString(): String? = (this as? Text)?.value

    /** Element count of an array, `1` for a scalar, `0` for a value that has no size. */
    val elementCount: Long
        get() = when (this) {
            is Array -> count
            is Text -> 1L
            else -> 0L
        }
}

/**
 * Largest metadata number that is treated as a real measurement rather than a defect.
 *
 * WHY one shared constant: a declared context length of 2^62 and a missing context
 * length both have to end up as "no usable value", and they must get there by the
 * *same* rule. If the parser's disclosure list and the resolved view disagree about
 * what is plausible, a corrupt file gets described to the user as merely sparse — and
 * the estimate built from it is wrong in the direction that says "it will fit".
 *
 * Sized far above anything real: a 100M-token context and a 10-trillion-element tensor
 * do not exist, and both are one byte away from a plausible value.
 */
internal const val MAX_PLAUSIBLE_METADATA_VALUE = 1e12
