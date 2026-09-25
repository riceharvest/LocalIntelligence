package dev.localintelligence.core.model.gguf

/**
 * The thirteen value types a GGUF metadata key can hold.
 *
 * WHY this is a closed enum and not an `Int`: the tag is read straight out of an
 * untrusted file. Modelling it as a closed set lets the parser reject a tag it does
 * not know *before* it reads any payload, which is the only safe way to handle a
 * stream whose framing you cannot compute. A raw `Int` here would tempt every call
 * site into `when (tag) { 0 -> ...; else -> readBytes(4) }`, and the `else` branch is
 * where a hostile file gets to choose its own width.
 *
 * [fixedByteWidth] is the on-disk width of a value of this type, or `null` when the
 * width is variable (string, array) and the reader must parse further. A non-null
 * width is what lets the array skipper jump over an element without materialising it —
 * that is the difference between a bounded header parse and an OOM.
 *
 * Ids are fixed by the GGUF spec and must never be renumbered; the ordering below is
 * the spec's own, not a preference.
 */
enum class GgufValueType(val id: Int, val fixedByteWidth: Int?) {
    UINT8(0, 1),
    INT8(1, 1),
    UINT16(2, 2),
    INT16(3, 2),
    UINT32(4, 4),
    INT32(5, 4),
    FLOAT32(6, 4),
    BOOL(7, 1),
    STRING(8, null),
    ARRAY(9, null),
    UINT64(10, 8),
    INT64(11, 8),
    FLOAT64(12, 8),
    ;

    /**
     * True for the unsigned integer types, which are the ones most likely to carry a
     * hostile length or a count. Callers bounds-checking attacker-controlled numbers
     * should look here rather than re-deriving the set.
     */
    val isUnsigned: Boolean
        get() = this == UINT8 || this == UINT16 || this == UINT32 || this == UINT64

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Null for a tag this build does not know, so the caller can degrade instead of guessing. */
        fun fromId(id: Long): GgufValueType? = byId[id.toInt()]?.takeIf { it.id.toLong() == id }
    }
}
