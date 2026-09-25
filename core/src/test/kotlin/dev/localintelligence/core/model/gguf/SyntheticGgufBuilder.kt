package dev.localintelligence.core.model.gguf

/**
 * Writes synthetic GGUF v3 headers for tests.
 *
 * WHY build bytes in a test rather than ship a real model: the smallest real quantised
 * model is hundreds of megabytes, and the test matrix needs a *truncated* file, a
 * byte-swapped one, a file with an unknown value tag, and a file whose tensor table
 * claims four gigabytes of weights. None of those exist on Hugging Face, and a repo
 * that carries a 4 GiB fixture is a repo nobody clones.
 *
 * The encoder is written from the spec independently of [GgufParser]'s reader, so a
 * test passing means two implementations agree rather than one implementation agreeing
 * with itself. [SyntheticGgufResourcesTest] pins the committed resource files to this
 * builder byte for byte, which keeps the "synthetic files in test resources" promise
 * honest instead of decorative.
 */
class SyntheticGgufBuilder(
    private val version: Long = 3,
    /** Writes every integer big-endian. The magic bytes stay ASCII, which is the point. */
    private val bigEndian: Boolean = false,
) {
    private val kvs = ArrayList<RawKv>()
    private val tensors = ArrayList<RawTensor>()

    private class RawKv(val key: String, val typeId: Long, val payload: ByteArray)

    private class RawTensor(
        val name: String,
        val dims: List<Long>,
        val typeId: Long,
        val offset: Long,
    )

    // ---------------------------------------------------------------- metadata writers

    /**
     * A string value. The payload is length-prefixed, exactly as a key is, because the
     * reader has no other way to know where the value ends.
     */
    fun string(key: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutput(bigEndian)
        body.u64(bytes.size.toLong())
        body.raw(bytes)
        put(key, GgufValueType.STRING.id.toLong(), body.toByteArray())
    }

    fun u8(key: String, value: Long) = put(key, GgufValueType.UINT8.id.toLong(), fixed(value, 1))
    fun u16(key: String, value: Long) = put(key, GgufValueType.UINT16.id.toLong(), fixed(value, 2))
    fun u32(key: String, value: Long) = put(key, GgufValueType.UINT32.id.toLong(), fixed(value, 4))
    fun u64(key: String, value: Long) = put(key, GgufValueType.UINT64.id.toLong(), fixed(value, 8))
    fun i32(key: String, value: Long) = put(key, GgufValueType.INT32.id.toLong(), fixed(value, 4))

    fun bool(key: String, value: Boolean) =
        put(key, GgufValueType.BOOL.id.toLong(), byteArrayOf(if (value) 1 else 0))

    fun f32(key: String, value: Float) =
        put(key, GgufValueType.FLOAT32.id.toLong(), fixed(java.lang.Float.floatToRawIntBits(value).toLong(), 4))

    fun f64(key: String, value: Double) =
        put(key, GgufValueType.FLOAT64.id.toLong(), fixed(java.lang.Double.doubleToLongBits(value), 8))

    /** An array of strings, the shape `tokenizer.ggml.tokens` has in a real file. */
    fun stringArray(key: String, values: List<String>) {
        val body = ByteArrayOutput(bigEndian)
        body.u32(GgufValueType.STRING.id.toLong())
        body.u64(values.size.toLong())
        for (v in values) {
            body.u64(v.toByteArray(Charsets.UTF_8).size.toLong())
            body.raw(v.toByteArray(Charsets.UTF_8))
        }
        put(key, GgufValueType.ARRAY.id.toLong(), body.toByteArray())
    }

    /** An array of uint32, the shape `rope.dimension_count` style lists have. */
    fun u32Array(key: String, values: List<Long>) {
        val body = ByteArrayOutput(bigEndian)
        body.u32(GgufValueType.UINT32.id.toLong())
        body.u64(values.size.toLong())
        for (v in values) body.u32(v)
        put(key, GgufValueType.ARRAY.id.toLong(), body.toByteArray())
    }

    /** An array of arrays. Not legal GGUF; the parser must reject it rather than guess. */
    fun nestedArray(key: String) {
        val body = ByteArrayOutput(bigEndian)
        body.u32(GgufValueType.ARRAY.id.toLong())
        body.u64(2)
        body.u32(GgufValueType.UINT32.id.toLong())
        body.u64(1)
        body.u32(7)
        put(key, GgufValueType.ARRAY.id.toLong(), body.toByteArray())
    }

    /** An arbitrary value with an arbitrary tag. The only way to build an unknown-tag file. */
    fun rawValue(key: String, typeId: Long, payload: ByteArray) = put(key, typeId, payload)

    /** A key whose declared string length is hostile. Builds a length-prefix bomb. */
    fun stringWithDeclaredLength(key: String, declaredLength: Long) {
        val body = ByteArrayOutput(bigEndian)
        body.u64(declaredLength)
        body.raw(key.toByteArray(Charsets.UTF_8))
        put(key, GgufValueType.STRING.id.toLong(), body.toByteArray())
    }

    private fun put(key: String, typeId: Long, payload: ByteArray) {
        kvs += RawKv(key, typeId, payload)
    }

    // ---------------------------------------------------------------- tensor writers

    fun tensor(name: String, dims: List<Long>, typeId: Long, offset: Long) {
        tensors += RawTensor(name, dims, typeId, offset)
    }

    fun tensor(name: String, dims: List<Long>, type: GgufQuantType) {
        tensors += RawTensor(name, dims, type.id.toLong(), runningOffset(dims, type))
    }

    private var nextOffset = 0L

    private fun runningOffset(dims: List<Long>, type: GgufQuantType): Long {
        val at = nextOffset
        nextOffset += type.bytesFor(dims.fold(1L) { a, b -> a * b })
        return at
    }

    // ---------------------------------------------------------------- assembly

    /** The declared counts, which a test can lie about independently of the body. */
    var declaredTensorCountOverride: Long? = null
    var declaredKeyValueCountOverride: Long? = null

    /** Magic bytes to emit. Lets a test build a file that is not GGUF at all. */
    var magicOverride: ByteArray? = null

    /**
     * Assembles the file.
     *
     * [weightDataBytes] of padding is appended after the tensor table so a
     * self-consistent file can declare weights that actually exist. Leaving it at 0
     * produces exactly the half-downloaded file the size check is meant to catch.
     */
    fun build(weightDataBytes: Int = 0): ByteArray {
        val out = ByteArrayOutput(bigEndian)
        out.raw(magicOverride ?: byteArrayOf(0x47, 0x47, 0x55, 0x46))
        out.u32(version)
        out.u64(declaredTensorCountOverride ?: tensors.size.toLong())
        out.u64(declaredKeyValueCountOverride ?: kvs.size.toLong())
        for (kv in kvs) {
            val keyBytes = kv.key.toByteArray(Charsets.UTF_8)
            out.u64(keyBytes.size.toLong())
            out.raw(keyBytes)
            out.u32(kv.typeId)
            out.raw(kv.payload)
        }
        for (t in tensors) {
            val nameBytes = t.name.toByteArray(Charsets.UTF_8)
            out.u64(nameBytes.size.toLong())
            out.raw(nameBytes)
            out.u32(t.dims.size.toLong())
            for (d in t.dims) out.u64(d)
            out.u32(t.typeId)
            out.u64(t.offset)
        }
        out.raw(ByteArray(weightDataBytes))
        return out.toByteArray()
    }

    /** The first [keepBytes] of the real file, i.e. a download cut off mid-header. */
    fun buildTruncated(keepBytes: Int): ByteArray {
        val full = build()
        return full.copyOf(keepBytes.coerceIn(0, full.size))
    }

    /** Header size with no weight payload — the offset weights would start at. */
    fun headerByteCount(): Int = build().size

    // ---------------------------------------------------------------- primitives

    private fun fixed(value: Long, width: Int): ByteArray {
        val out = ByteArray(width)
        for (i in 0 until width) {
            val shift = if (bigEndian) (width - 1 - i) * 8 else i * 8
            out[i] = ((value ushr shift) and 0xFF).toByte()
        }
        return out
    }

    private class ByteArrayOutput(private val bigEndian: Boolean) {
        private var buffer = ByteArray(256)
        private var size = 0

        fun raw(bytes: ByteArray) {
            ensure(size + bytes.size)
            System.arraycopy(bytes, 0, buffer, size, bytes.size)
            size += bytes.size
        }

        fun u32(v: Long) = raw(fixedWidth(v, 4))

        fun u64(v: Long) = raw(fixedWidth(v, 8))

        private fun fixedWidth(v: Long, width: Int): ByteArray = ByteArray(width) { i ->
            val shift = if (bigEndian) (width - 1 - i) * 8 else i * 8
            ((v ushr shift) and 0xFF).toByte()
        }

        private fun ensure(capacity: Int) {
            if (capacity <= buffer.size) return
            var next = buffer.size
            while (next < capacity) next *= 2
            buffer = buffer.copyOf(next)
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
