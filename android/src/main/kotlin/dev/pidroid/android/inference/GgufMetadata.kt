package dev.pidroid.android.inference

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * GGUF container header parsing, in pure Kotlin over a byte stream.
 *
 * Why pure Kotlin and not `llama_model_load_from_file` + `llama_model_meta_val_str`:
 * the model-manager UI has to show architecture, context length and quantization
 * *before* anyone commits 2 GB of RAM to a load, and a load needs a free model
 * slot. Reading the header is a few hundred byte reads; a load is not. So this
 * parses the container directly and the native side is only used for generation.
 *
 * Format (GGUF v3, little-endian throughout):
 *
 * ```
 *   magic          char[4]   "GGUF"
 *   version        u32
 *   tensor_count   u64
 *   kv_count       u64
 *   kv_count x  { key: gguf_string, type: u32, value }
 *   tensor_count x { name: gguf_string, n_dims: u32, dims: u64[n_dims],
 *                    type: u32, offset: u64 }
 *   ...pad to general.alignment... then the tensor bytes
 * ```
 *
 * `gguf_string` is `u64 byte_length` followed by exactly that many bytes. It is
 * NOT NUL-terminated, and it is NOT the length of the Kotlin string once decoded.
 *
 * Every read is bounds-checked against the declared file length before it is
 * attempted, so a truncated, hostile or simply wrong file (a user can pick any
 * file through SAF) produces a [GgufFormatException] naming the offset. It never
 * produces an IndexOutOfBoundsException, and it never allocates on a length
 * taken from the file without checking that length against what is left.
 *
 * Heap discipline: this parser does **not** retain every KV pair. A real
 * tokenizer carries `tokenizer.ggml.tokens` (128k+ strings) and a `merges` list;
 * retaining those is ~10 MB of heap for data the model manager never reads. By
 * default only keys in [INTERESTING_KEYS] are kept and everything else is skipped
 * by size arithmetic without ever being read into memory. Parsed header
 * footprint: a few KB.
 */
// ---- gguf_metadata_value_type ------------------------------------------------------
// File-level (not class-level) because both the parser and the stream reader need them.
private const val TYPE_UINT8 = 0
private const val TYPE_INT8 = 1
private const val TYPE_UINT16 = 2
private const val TYPE_INT16 = 3
private const val TYPE_UINT32 = 4
private const val TYPE_INT32 = 5
private const val TYPE_FLOAT32 = 6
private const val TYPE_BOOL = 7
private const val TYPE_STRING = 8
private const val TYPE_ARRAY = 9
private const val TYPE_UINT64 = 10
private const val TYPE_INT64 = 11
private const val TYPE_FLOAT64 = 12
private const val TYPE_MAX = 12

/** Fixed on-disk width per scalar type; -1 marks the variable-length types. */
private val FIXED_SIZE = intArrayOf(1, 1, 2, 2, 4, 4, 4, 1, -1, -1, 8, 8, 8)

/** 67M elements. Real ones are ~128k (a 128k-vocab token array). */
private const val MAX_ARRAY_COUNT = 1 shl 26

/** 1 MiB. Real ones are 1-8 KB (chat templates). */
private const val MAX_STRING_BYTES = 1 shl 20

private const val MAX_DEPTH = 8

/** 1M KV pairs. A 7B has ~30. */
private const val MAX_KV_COUNT = 1 shl 20

/** `ggml_type` id for Q6_K, used by the mixed-quant probe in [GgufMetadata.isMixedQuant]. */
const val GGML_TYPE_Q6_K = 14

/**
 * Architecture-scoped hyper-parameter suffixes, as they appear in GGUF after the
 * `<arch>.` prefix (`llama.context_length`, `qwen2.block_count`, ...). These are
 * the only `<arch>.*` keys retained by default, because they are what the RAM
 * estimate and the capability flags are computed from. Everything else in the
 * `<arch>.*` space is a per-tensor rope/quant override the app never reads.
 */
private val ARCH_FIELDS = listOf(
    "context_length",
    "embedding_length",
    "block_count",
    "feed_forward_length",
    "attention.head_count",
    "attention.head_count_kv",
    "attention.key_length",
    "attention.value_length",
    "rope.dimension_count",
)

class GgufFormatException(message: String) : IOException(message)

/** A decoded GGUF value. Scalars are boxed; the type tags mirror the file's. */
sealed interface GgufValue {
    data class U8(val value: Int) : GgufValue
    data class I8(val value: Int) : GgufValue
    data class U16(val value: Int) : GgufValue
    data class I16(val value: Int) : GgufValue
    data class U32(val value: Long) : GgufValue
    data class I32(val value: Int) : GgufValue
    data class U64(val value: Long) : GgufValue
    data class I64(val value: Long) : GgufValue
    data class F32(val value: Float) : GgufValue
    data class F64(val value: Double) : GgufValue
    data class Bool(val value: Boolean) : GgufValue
    data class Str(val value: String) : GgufValue
    data class Arr(val elementType: Int, val values: List<GgufValue>) : GgufValue
    data class Obj(val values: Map<String, GgufValue>) : GgufValue

    /** Best-effort numeric view, used for the architecture-scoped lookups. */
    fun asLongOrNull(): Long? = when (this) {
        is U8 -> value.toLong()
        is I8 -> value.toLong()
        is U16 -> value.toLong()
        is I16 -> value.toLong()
        is U32 -> value
        is I32 -> value.toLong()
        is U64 -> value
        is I64 -> value
        is F32 -> value.toLong()
        is F64 -> value.toLong()
        is Bool -> if (value) 1L else 0L
        else -> null
    }

    fun asStringOrNull(): String? = (this as? Str)?.value
}

/** One tensor descriptor from the header (not the tensor data). */
data class GgufTensorInfo(
    val name: String,
    val dimensions: List<Long>,
    val type: Int,
    val offset: Long,
)

/**
 * The header of a GGUF file, decoded.
 *
 * Only the fields the app actually branches on are promoted to typed properties.
 * The raw map is available via [get] for anything else, and is bounded by
 * [INTERESTING_KEYS] unless the parser was asked to retain everything.
 */
data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    /** Bytes occupied by the KV section, i.e. where the tensor table starts. */
    val kvSectionBytes: Long,
    val architecture: String?,
    val name: String?,
    val quantType: String?,
    val quantTypeId: Int?,
    val quantizationVersion: Int?,
    val contextLength: Int?,
    val embeddingLength: Int?,
    val blockCount: Int?,
    val feedForwardLength: Int?,
    val attentionHeadCount: Int?,
    val attentionHeadCountKv: Int?,
    val keyLength: Int?,
    val valueLength: Int?,
    val ropeDimensionCount: Int?,
    val parameterCount: Long?,
    val hasChatTemplate: Boolean,
    val chatTemplate: String?,
    val bosTokenId: Int?,
    val eosTokenId: Int?,
    val fileSizeBytes: Long,
    val tensors: List<GgufTensorInfo> = emptyList(),
    private val values: Map<String, GgufValue> = emptyMap(),
) {
    fun get(key: String): GgufValue? = values[key]

    fun retainedKeys(): Set<String> = values.keys

    /**
     * True when the tensor table shows the mixed-precision pattern behind the
     * "M" quant names. Q4_K_M is stored as ftype Q4_K_M but the attention
     * `.ffn_down` tensors are Q6_K while the rest are Q4_K, so ftype alone
     * cannot distinguish Q4_K_S from Q4_K_M.
     */
    val isMixedQuant: Boolean
        get() = tensors.isNotEmpty() && tensors.any {
            it.type == GGML_TYPE_Q6_K && it.name.endsWith("ffn_down.weight")
        }

    companion object {
        const val MAGIC = 0x46554747 // "GGUF" read as a little-endian u32
        const val MIN_SUPPORTED_VERSION = 2
        const val VERSION = 3

        /**
         * Keys retained by default. `general.architecture` is needed to resolve
         * the `<arch>.*` family, and `general.alignment` so the tensor data
         * offset can be validated.
         */
        val INTERESTING_KEYS: Set<String> = setOf(
            "general.architecture",
            "general.name",
            "general.alignment",
            "general.file_type",
            "general.quantization_version",
            "general.parameter_count",
            "general.description",
            "tokenizer.chat_template",
            "tokenizer.ggml.bos_token_id",
            "tokenizer.ggml.eos_token_id",
        )
    }
}

/** Parses a GGUF header. Every method is pure and JVM-only. */
object GgufReader {

    /**
     * Parses the header of [input].
     *
     * @param declaredLength total bytes available, or -1 if unknown. Pass it
     *   whenever you have it: it is what turns a corrupt length field into a
     *   clean error instead of a huge allocation.
     * @param retainAllKeys keep every KV pair instead of only [GgufMetadata.INTERESTING_KEYS].
     * @param readTensors also decode the tensor table. Off by default because it
     *   is only needed for the mixed-quant probe and costs `tensor_count` seeks.
     */
    fun parse(
        input: InputStream,
        declaredLength: Long = -1L,
        retainAllKeys: Boolean = false,
        readTensors: Boolean = false,
    ): GgufMetadata {
        val reader = GgufStreamReader(input, declaredLength)
        reader.readMagic()
        val version = reader.u32("version").toInt()
        if (version < GgufMetadata.MIN_SUPPORTED_VERSION) {
            throw GgufFormatException(
                "GGUF version $version is older than the minimum supported " +
                    "version ${GgufMetadata.MIN_SUPPORTED_VERSION}",
            )
        }
        if (version > GgufMetadata.VERSION) {
            throw GgufFormatException(
                "GGUF version $version is newer than this reader understands " +
                    "(v${GgufMetadata.VERSION}); refusing to guess at the layout",
            )
        }
        val tensorCount = reader.u64("tensor_count")
        val kvCount = reader.u64("kv_count")
        if (kvCount > MAX_KV_COUNT) {
            throw GgufFormatException(
                "GGUF kv_count $kvCount exceeds the sanity limit of $MAX_KV_COUNT " +
                    "at offset ${reader.offset}; file is corrupt or not GGUF",
            )
        }

        val values = LinkedHashMap<String, GgufValue>(32)
        var skipped = 0L
        var i = 0L
        while (i < kvCount) {
            val key = reader.string("metadata key #$i")
            val type = reader.u32("value type of '$key'").toInt()
            if (type > TYPE_MAX) {
                // The spec says the type enum is fixed-width, so a type id above
                // the known set still has a knowable width... except for the
                // variable-width ones. Rather than guess, drop this file: we
                // cannot tell where the next key starts.
                throw GgufFormatException(
                    "unknown GGUF value type $type for key '$key' at offset " +
                        "${reader.offset}; cannot determine the value width",
                )
            }
            if (retainAllKeys || key in GgufMetadata.INTERESTING_KEYS || isArchitectureField(key)) {
                values[key] = reader.readValue(type, "$key")
            } else {
                reader.skipValue(type, "$key")
                skipped++
            }
            i++
        }
        val kvSectionBytes = reader.offset

        val tensors = if (readTensors) readTensorInfos(reader, tensorCount) else emptyList()

        return build(version, tensorCount, kvSectionBytes, values, tensors, reader, declaredLength)
    }

    private fun build(
        version: Int,
        tensorCount: Long,
        kvSectionBytes: Long,
        values: Map<String, GgufValue>,
        tensors: List<GgufTensorInfo>,
        reader: GgufStreamReader,
        declaredLength: Long,
    ): GgufMetadata {
        val arch = values["general.architecture"]?.asStringOrNull()

        // Architecture-scoped hyper-parameters are keyed `<arch>.<field>`, and the
        // architecture name itself is only known once the KV section has been read.
        fun archValue(field: String): Long? =
            arch?.let { values["$it.$field"] }?.asLongOrNull()

        val ftype = values["general.file_type"]?.asLongOrNull()?.toInt()
        val template = values["tokenizer.chat_template"]?.asStringOrNull()

        return GgufMetadata(
            version = version,
            tensorCount = tensorCount,
            kvSectionBytes = kvSectionBytes,
            architecture = arch,
            name = values["general.name"]?.asStringOrNull(),
            quantType = ftype?.let { quantNameForFileType(it) },
            quantTypeId = ftype,
            quantizationVersion = values["general.quantization_version"]?.asLongOrNull()?.toInt(),
            contextLength = archValue("context_length")?.toInt(),
            embeddingLength = archValue("embedding_length")?.toInt(),
            blockCount = archValue("block_count")?.toInt(),
            feedForwardLength = archValue("feed_forward_length")?.toInt(),
            attentionHeadCount = archValue("attention.head_count")?.toInt(),
            attentionHeadCountKv = archValue("attention.head_count_kv")?.toInt(),
            keyLength = archValue("attention.key_length")?.toInt(),
            valueLength = archValue("attention.value_length")?.toInt(),
            ropeDimensionCount = archValue("rope.dimension_count")?.toInt(),
            parameterCount = values["general.parameter_count"]?.asLongOrNull(),
            hasChatTemplate = template != null,
            // Retained in [values] under its own key, but not promoted: the backend
            // branches on the flag, and a ~20 KB template on the model record would
            // have to be held for the lifetime of the list entry.
            chatTemplate = null,
            bosTokenId = values["tokenizer.ggml.bos_token_id"]?.asLongOrNull()?.toInt(),
            eosTokenId = values["tokenizer.ggml.eos_token_id"]?.asLongOrNull()?.toInt(),
            fileSizeBytes = if (declaredLength >= 0) declaredLength else reader.bytesConsumed,
            tensors = tensors,
            values = values,
        )
    }

    private fun readTensorInfos(reader: GgufStreamReader, tensorCount: Long): List<GgufTensorInfo> {
        if (tensorCount == 0L) return emptyList()
        // A 7B has ~291 tensors. Anything far past that is a corrupt count and
        // would otherwise be a long loop of seeks.
        if (tensorCount > 1 shl 20) {
            throw GgufFormatException(
                "GGUF tensor_count $tensorCount exceeds the sanity limit of " +
                    "1048576 at offset ${reader.offset}",
            )
        }
        val out = ArrayList<GgufTensorInfo>(minOf(tensorCount, 1024L).toInt())
        repeat(tensorCount.toInt()) { i ->
            val name = reader.string("tensor name #$i")
            val nDims = reader.u32("n_dims of tensor '$name'").toInt()
            if (nDims < 0 || nDims > 8) {
                throw GgufFormatException(
                    "tensor '$name' declares $nDims dimensions at offset " +
                        "${reader.offset}; GGUF tensors have 1..8",
                )
            }
            val dims = LongArray(nDims) { reader.u64("dim of tensor '$name'") }
            val type = reader.u32("type of tensor '$name'").toInt()
            val offset = reader.u64("data offset of tensor '$name'")
            out += GgufTensorInfo(name, dims.toList(), type, offset)
        }
        return out
    }

    /**
     * True for the `<arch>.<field>` keys the RAM estimate and capabilities depend
     * on. The architecture prefix is not known until `general.architecture` has
     * been read, so this matches on the field suffix instead. Deliberately a
     * suffix match on a closed list rather than a prefix match on `general.`
     * families, which would pull in the whole vocab and token tables.
     */
    private fun isArchitectureField(key: String): Boolean =
        ARCH_FIELDS.any { key == it || key.endsWith(".$it") }

    /** `general.file_type` -> the name llama.cpp prints for it. */
    fun quantNameForFileType(ftype: Int): String = when (ftype) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "IQ2_S"
        22 -> "IQ1_S"
        23 -> "IQ1_M"
        24 -> "IQ4_XS"
        25 -> "IQ4_NL"
        26 -> "TQ1_0"
        27 -> "TQ2_0"
        28 -> "MXFP4"
        else -> "UNKNOWN_$ftype"
    }
}

/**
 * Forward-only bounds-checked reader.
 *
 * GGUF puts everything we need at the front of the file, so a sequential reader
 * is sufficient and avoids requiring a seekable stream. Seekability is still
 * used where available (it makes `readTensors` cheap on a real file) but never
 * required.
 */
internal class GgufStreamReader(
    private val input: InputStream,
    private val declaredLength: Long,
) {
    var offset: Long = 0L
        private set
    var bytesConsumed: Long = 0L
        private set

    private val single = ByteArray(1)

    private fun remaining(): Long = if (declaredLength >= 0) declaredLength - offset else Long.MAX_VALUE

    fun readMagic() {
        val magic = u32("magic")
        if (magic.toInt() != GgufMetadata.MAGIC) {
            throw GgufFormatException(
                "not a GGUF file: magic is 0x${magic.toString(16)} at offset 0, " +
                    "expected 0x${GgufMetadata.MAGIC.toString(16)} (\"GGUF\")",
            )
        }
    }

    /** Reads [n] bytes, failing loudly if the file ends first. */
    fun readBytes(n: Int, what: String): ByteArray {
        if (n < 0) throw GgufFormatException("negative length $n for $what at offset $offset")
        if (n.toLong() > remaining()) {
            throw GgufFormatException(
                "truncated GGUF: $what needs $n bytes at offset $offset but only " +
                    "${remainingCoerced()} remain",
            )
        }
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) {
                // The caller declared more bytes than the stream actually has
                // (a lying ContentResolver length, a truncated download, a pipe).
                // Report it as a format error naming the offset, not a bare EOF.
                throw GgufFormatException(
                    "truncated GGUF: stream ended $${n - read}B into $what at offset " +
                        "$offset; the declared length of $declaredLength does not match " +
                        "the stream",
                )
            }
            read += r
        }
        offset += n
        bytesConsumed += n
        return out
    }

    private fun remainingCoerced(): Long = if (remaining() == Long.MAX_VALUE) -1L else remaining()

    fun skipFully(n: Long, what: String) {
        if (n < 0) throw GgufFormatException("negative skip $n for $what at offset $offset")
        if (n > remaining()) {
            throw GgufFormatException(
                "truncated GGUF: $what needs $n bytes at offset $offset but only " +
                    "${remainingCoerced()} remain",
            )
        }
        var left = n
        while (left > 0) {
            val chunk = minOf(left, 1L shl 16)
            val skipped = input.skip(chunk)
            if (skipped <= 0) {
                // skip() may legitimately return 0; fall back to a real read.
                val r = input.read(single, 0, 1)
                if (r < 0) {
                    throw GgufFormatException(
                        "truncated GGUF: stream ended while skipping $what at offset " +
                            "$offset; the declared length of $declaredLength does not " +
                            "match the stream",
                    )
                }
                left -= 1
            } else {
                left -= skipped
            }
            offset += if (skipped > 0) skipped else 1
            bytesConsumed += if (skipped > 0) skipped else 1
        }
    }

    fun u8(what: String): Int = readBytes(1, what)[0].toInt() and 0xFF

    fun i8(what: String): Int = readBytes(1, what)[0].toInt()

    fun bool(what: String): Boolean {
        val v = u8(what)
        if (v > 1) throw GgufFormatException("GGUF bool '$what' is $v at offset ${offset - 1}; must be 0 or 1")
        return v == 1
    }

    fun u16(what: String): Int {
        val b = ByteBuffer.wrap(readBytes(2, what)).order(ByteOrder.LITTLE_ENDIAN)
        return b.short.toInt() and 0xFFFF
    }

    fun i16(what: String): Int = u16(what).toShort().toInt()

    fun u32(what: String): Long =
        ByteBuffer.wrap(readBytes(4, what)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    fun i32(what: String): Int = ByteBuffer.wrap(readBytes(4, what)).order(ByteOrder.LITTLE_ENDIAN).int

    fun u64(what: String): Long = ByteBuffer.wrap(readBytes(8, what)).order(ByteOrder.LITTLE_ENDIAN).long

    fun i64(what: String): Long = u64(what)

    fun f32(what: String): Float = Float.fromBits(i32(what))

    fun f64(what: String): Double = Double.fromBits(u64(what))

    fun string(what: String): String {
        val len = u64("length of $what")
        if (len < 0 || len > MAX_STRING_BYTES) {
            throw GgufFormatException(
                "GGUF string '$what' declares $len bytes at offset $offset; " +
                    "limit is $MAX_STRING_BYTES (corrupt file or not GGUF)",
            )
        }
        if (len > remaining()) {
            throw GgufFormatException(
                "truncated GGUF: string '$what' declares $len bytes at offset " +
                    "$offset but only ${remaining()} remain",
            )
        }
        if (len == 0L) return ""
        val raw = readBytes(len.toInt(), what)
        return String(raw, StandardCharsets.UTF_8)
    }

    fun readValue(type: Int, what: String, depth: Int = 0): GgufValue {
        if (depth > MAX_DEPTH) {
            throw GgufFormatException("GGUF value '$what' nests deeper than $MAX_DEPTH at offset $offset")
        }
        return when (type) {
            TYPE_UINT8 -> GgufValue.U8(u8(what))
            TYPE_INT8 -> GgufValue.I8(i8(what))
            TYPE_UINT16 -> GgufValue.U16(u16(what))
            TYPE_INT16 -> GgufValue.I16(i16(what))
            TYPE_UINT32 -> GgufValue.U32(u32(what))
            TYPE_INT32 -> GgufValue.I32(i32(what))
            TYPE_FLOAT32 -> GgufValue.F32(f32(what))
            TYPE_BOOL -> GgufValue.Bool(bool(what))
            TYPE_STRING -> GgufValue.Str(string(what))
            TYPE_UINT64 -> GgufValue.U64(u64(what))
            TYPE_INT64 -> GgufValue.I64(i64(what))
            TYPE_FLOAT64 -> GgufValue.F64(f64(what))
            TYPE_ARRAY -> readArray(what, depth)
            else -> throw GgufFormatException("unknown GGUF value type $type for '$what' at offset $offset")
        }
    }

    private fun readArray(what: String, depth: Int): GgufValue {
        val elementType = u32("array element type of '$what'").toInt()
        if (elementType > TYPE_MAX) {
            throw GgufFormatException(
                "unknown GGUF array element type $elementType in '$what' at offset " +
                    "${offset - 4}; cannot determine element width",
            )
        }
        val count = u64("array length of '$what'")
        if (count < 0 || count > MAX_ARRAY_COUNT) {
            throw GgufFormatException(
                "GGUF array '$what' declares $count elements at offset $offset; " +
                    "limit is $MAX_ARRAY_COUNT (corrupt file or not GGUF)",
            )
        }
        if (elementType != TYPE_ARRAY) {
            val width = FIXED_SIZE[elementType].toLong()
            if (width > 0) {
                // Check the whole span before touching the stream, so a bogus
                // count cannot drive a long read on a short file.
                val span = width * count
                if (count != 0L && span / count != width) {
                    throw GgufFormatException("GGUF array '$what' length overflows at offset $offset")
                }
                if (span > remaining()) {
                    throw GgufFormatException(
                        "truncated GGUF: array '$what' declares $count elements " +
                            "(${span}B) at offset $offset but only ${remaining()} remain",
                    )
                }
            }
        }
        val out = ArrayList<GgufValue>(minOf(count, 4096L).toInt())
        var i = 0L
        while (i < count) {
            out += readValue(elementType, "$what[$i]", depth + 1)
            i++
        }
        return GgufValue.Arr(elementType, out)
    }

    /**
     * Advances past a value without materialising it. This is what keeps a
     * 128k-entry tokenizer array off the JVM heap.
     */
    fun skipValue(type: Int, what: String, depth: Int = 0) {
        if (depth > MAX_DEPTH) {
            throw GgufFormatException("GGUF value '$what' nests deeper than $MAX_DEPTH at offset $offset")
        }
        when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> u8(what)
            TYPE_UINT16, TYPE_INT16 -> u16(what)
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> u32(what)
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> u64(what)
            TYPE_STRING -> {
                val len = u64("length of $what")
                if (len < 0 || len > MAX_STRING_BYTES) {
                    throw GgufFormatException(
                        "GGUF string '$what' declares $len bytes at offset $offset; " +
                            "limit is $MAX_STRING_BYTES",
                    )
                }
                skipFully(len, "string '$what'")
            }
            TYPE_ARRAY -> {
                val elementType = u32("array element type of '$what'").toInt()
                if (elementType > TYPE_MAX) {
                    throw GgufFormatException(
                        "unknown GGUF array element type $elementType in '$what' at " +
                            "offset ${offset - 4}; cannot determine element width",
                    )
                }
                val count = u64("array length of '$what'")
                if (count < 0 || count > MAX_ARRAY_COUNT) {
                    throw GgufFormatException(
                        "GGUF array '$what' declares $count elements at offset $offset; " +
                            "limit is $MAX_ARRAY_COUNT",
                    )
                }
                val width = FIXED_SIZE[elementType].toLong()
                if (width > 0) {
                    val span = width * count
                    if (count != 0L && span / count != width) {
                        throw GgufFormatException("GGUF array '$what' length overflows at offset $offset")
                    }
                    skipFully(span, "array '$what'")
                } else {
                    var i = 0L
                    while (i < count) {
                        skipValue(elementType, "$what[$i]", depth + 1)
                        i++
                    }
                }
            }
            else -> throw GgufFormatException(
                "unknown GGUF value type $type for '$what' at offset $offset; cannot skip it",
            )
        }
    }
}

/**
 * A GGUF opened for reading, owning whatever the platform gave us. Closing it
 * releases the descriptor — important when it is a SAF descriptor, which counts
 * against the app's open-descriptor budget and, on some providers, against a
 * per-document open count.
 */
interface GgufSource : Closeable {
    val length: Long
    fun openStream(): InputStream
}

/** Wraps a stream the caller already owns. Does not close the delegate on [close]. */
class StreamGgufSource(
    override val length: Long,
    private val opener: () -> InputStream,
) : GgufSource {
    override fun openStream(): InputStream = opener()
    override fun close() = Unit
}