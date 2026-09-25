package dev.localintelligence.core.model.gguf

/**
 * Sequential, bounds-checked little-endian reader over a [GgufByteSource].
 *
 * WHY every read goes through [need] first: in a GGUF file essentially every read is
 * preceded by reading a length out of the file itself. Checking "are there at least
 * [n] bytes left, and is [n] within the configured ceiling" at the single choke point
 * means the bounds rule is enforced once, for all twelve value types and both string
 * and tensor-name reads, instead of being re-derived — and eventually forgotten — at
 * each of the twenty call sites.
 *
 * All arithmetic here is explicit little-endian shifts rather than `ByteBuffer`. That
 * is a correctness choice as much as an allocation one: a `ByteBuffer.allocate` per
 * small read on a 151936-element vocabulary loop is 151936 short-lived allocations, and
 * the shift version is also immune to a platform default byte order leaking in.
 */
internal class GgufReader(
    private val source: GgufByteSource,
    private val limits: GgufLimits,
) {
    var offset: Long = 0L
        private set

    /** Total bytes the source can supply. Exposed so callers can word their own errors. */
    val sizeBytes: Long get() = source.sizeBytes

    private val scratch = ByteArray(8)

    /** Bytes still readable. Negative only if the file shrank under us, which is treated as zero. */
    fun remaining(): Long {
        val left = source.sizeBytes - offset
        return if (left < 0) 0L else left
    }

    /**
     * The single bounds check. Fails closed: an unavailable read raises rather than
     * returning short, so no caller can accidentally treat "0 bytes left" as "a zero
     * length value".
     */
    fun need(n: Long) {
        if (n < 0 || n > Int.MAX_VALUE) {
            throw GgufParseException(
                GgufParseException.Reason.IMPLAUSIBLE_COUNT,
                "declared length $n is not a usable length at offset $offset",
                offset,
            )
        }
        if (offset > limits.maxHeaderBytes || n > limits.maxHeaderBytes - offset) {
            throw GgufParseException(
                GgufParseException.Reason.LIMIT_EXCEEDED,
                "reading $n bytes at $offset would pass the ${limits.maxHeaderBytes}-byte header ceiling",
                offset,
            )
        }
        if (n > remaining()) {
            throw GgufParseException(
                GgufParseException.Reason.TRUNCATED,
                "file ends after ${source.sizeBytes} bytes but $n more were needed at $offset",
                offset,
            )
        }
    }

    fun readByte(): Int {
        need(1)
        readInto(scratch, 0, 1)
        return scratch[0].toInt() and 0xFF
    }

    /** Reads [n] bytes into a fresh array. Rejects a zero length so callers cannot spin. */
    fun readBytes(n: Int): ByteArray {
        if (n <= 0) {
            throw GgufParseException(
                GgufParseException.Reason.IMPLAUSIBLE_COUNT,
                "declared a $n-byte read at offset $offset",
                offset,
            )
        }
        val out = ByteArray(n)
        need(n.toLong())
        readInto(out, 0, n)
        return out
    }

    /** Skips [n] bytes without materialising them. Used to stride over array payloads. */
    fun skip(n: Long) {
        if (n <= 0L) return
        need(n)
        offset += n
    }

    fun readU8(): Int = readByte()

    fun readU16(): Int {
        need(2)
        readInto(scratch, 0, 2)
        return (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8)
    }

    fun readU32(): Long {
        need(4)
        readInto(scratch, 0, 4)
        return (scratch[0].toLong() and 0xFF) or
            ((scratch[1].toLong() and 0xFF) shl 8) or
            ((scratch[2].toLong() and 0xFF) shl 16) or
            ((scratch[3].toLong() and 0xFF) shl 24)
    }

    fun readU64(): Long {
        need(8)
        readInto(scratch, 0, 8)
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (scratch[i].toLong() and 0xFF)
        }
        return v
    }

    /**
     * A length-prefixed string, bounded by [maxLength].
     *
     * [maxLength] is checked against the *declared* length before a single byte is
     * read, which is the whole reason this is safe: a hostile `0xFFFFFFFFFFFFFFFF`
     * length is rejected without touching the heap.
     */
    fun readString(maxLength: Int): String {
        val declared = readU64()
        if (declared < 0 || declared > maxLength.toLong()) {
            throw GgufParseException(
                GgufParseException.Reason.LIMIT_EXCEEDED,
                "string length $declared at offset $offset exceeds the $maxLength-byte limit",
                offset,
            )
        }
        val bytes = readBytes(declared.toInt())
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * A string length-prefixed in a tensor table.
     *
     * WHY zero is refused here but not for a metadata key: a tensor name is never
     * empty, so a zero length at this position means the table ran out — the file is
     * truncated, or the declared count is a lie. Throwing [Reason.TRUNCATED] rather
     * than a framing error is what lets the caller degrade to a partial tensor table
     * instead of discarding a file whose header was perfectly readable.
     */
    fun readTensorName(maxLength: Int): String {
        val declared = readU64()
        if (declared == 0L) {
            throw GgufParseException(
                GgufParseException.Reason.TRUNCATED,
                "tensor name length is 0 at offset $offset; the tensor table ran out",
                offset,
            )
        }
        if (declared > maxLength.toLong()) {
            throw GgufParseException(
                GgufParseException.Reason.LIMIT_EXCEEDED,
                "tensor name length $declared at offset $offset exceeds the $maxLength-byte limit",
                offset,
            )
        }
        val bytes = readBytes(declared.toInt())
        return String(bytes, Charsets.UTF_8)
    }

    private fun readInto(dest: ByteArray, destOffset: Int, length: Int) {
        val read = source.readAt(offset, dest, destOffset, length)
        if (read != length) {
            throw GgufParseException(
                GgufParseException.Reason.TRUNCATED,
                "wanted $length bytes at $offset, source gave $read",
                offset,
            )
        }
        offset += length
    }
}
