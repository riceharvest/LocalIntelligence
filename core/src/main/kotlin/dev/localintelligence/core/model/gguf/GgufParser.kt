package dev.localintelligence.core.model.gguf

import java.io.File

/**
 * Reads a GGUF header — magic, version, the key-value metadata block and the tensor
 * table — and stops before the weights.
 *
 * WHY stop before the weights: everything the app needs to answer "which models do I
 * have, and which of them fit" lives in the first few hundred KiB. A header parse of a
 * 4 GiB Q4 model touches well under 1% of the file and allocates kilobytes, so the
 * model list stays responsive as the library grows.
 *
 * WHY a static parser and not a `GGUF` class with a mutable cursor: the parse is a
 * single atomic read of an untrusted resource. There is no legitimate second call, and
 * a stateful cursor is exactly the shape that turns "re-read after a truncated file"
 * into "silently continue from a half-consumed position".
 *
 * Every entry point either returns a [GgufHeader] whose [GgufHeader.isComplete] and
 * [GgufHeader.warnings] describe exactly what was missing, or throws
 * [GgufParseException] because there was no usable header at all.
 */
object GgufParser {

    /** The four magic bytes, `G G U F`, in file order. */
    val MAGIC: ByteArray = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    /** Versions this build speaks. v3 is current; v1/v2 share the same framing. */
    val SUPPORTED_VERSIONS: Set<Int> = setOf(1, 2, 3)

    /**
     * How to treat a file that ends before its header does.
     *
     * WHY this is a choice rather than one behaviour: a half-copied model is a real
     * thing a user hits, and "reject it" loses the context length and vocabulary size
     * that would let the UI say "incomplete download, 12% done". [PARTIAL] returns what
     * was readable and marks the header incomplete; [STRICT] is for tooling that must
     * not act on partial data.
     */
    enum class TruncationPolicy {
        /** Return what was read, with warnings and `isComplete = false`. */
        PARTIAL,

        /** Throw [GgufParseException] the moment the file ends inside a declared structure. */
        STRICT,
    }

    /** Parses a header out of an in-memory buffer — the path for tests and small assets. */
    fun parse(
        bytes: ByteArray,
        limits: GgufLimits = GgufLimits(),
        truncation: TruncationPolicy = TruncationPolicy.PARTIAL,
    ): GgufHeader = parse(GgufByteSource.of(bytes), limits, truncation)

    /** Parses a header from a file the user selected, without mapping the weights. */
    fun parse(
        file: File,
        limits: GgufLimits = GgufLimits(),
        truncation: TruncationPolicy = TruncationPolicy.PARTIAL,
    ): GgufHeader = parse(GgufByteSource.of(file), limits, truncation)

    /** Parses a header from an arbitrary bounded byte source. */
    fun parse(
        source: GgufByteSource,
        limits: GgufLimits = GgufLimits(),
        truncation: TruncationPolicy = TruncationPolicy.PARTIAL,
    ): GgufHeader {
        val reader = GgufReader(source, limits)
        val warnings = ArrayList<GgufWarning>(4)

        readMagic(reader)
        val version = readVersion(reader)
        val declaredTensors = readCount(reader, "tensor count", limits.maxTensorCount)
        val declaredPairs = readCount(reader, "key-value count", limits.maxKeyValuePairs)

        val fields = readKeyValues(reader, declaredPairs, limits, truncation, warnings)
        val tensors = readTensorTable(reader, declaredTensors, limits, truncation, warnings)
        val metadata = GgufMetadata(
            fields = fields,
            architecture = resolveArchitecture(fields, warnings),
            architectureInferred = fields.containsKey("general.architecture").not(),
            implausibleKeys = findImplausibleKeys(fields),
        )

        val header = GgufHeader(
            version = version,
            fileBytes = source.sizeBytes,
            declaredTensorCount = declaredTensors,
            tensors = tensors,
            metadata = metadata,
            headerBytes = reader.offset,
            isComplete = warnings.none { it is GgufWarning.KeyValueListTruncated || it is GgufWarning.TensorTableTruncated },
            warnings = warnings,
        )

        addConsistencyWarnings(header, warnings)
        return header.copy(warnings = warnings.toList())
    }

    // ------------------------------------------------------------------ fixed header

    private fun readMagic(reader: GgufReader) {
        val read = try {
            reader.readBytes(MAGIC.size)
        } catch (e: GgufParseException) {
            // A file too short to even hold four magic bytes is not a GGUF file; saying
            // "truncated" here would be a guess about a file we never identified.
            throw GgufParseException(
                GgufParseException.Reason.NOT_A_GGUF_FILE,
                "file is only ${reader.sizeBytes} bytes; too short for a GGUF header",
                0L,
            ).also { it.initCause(e) }
        }
        if (!read.contentEquals(MAGIC)) {
            throw GgufParseException(
                GgufParseException.Reason.NOT_A_GGUF_FILE,
                "expected GGUF magic, found ${read.toHex()}",
                0L,
            )
        }
    }

    /**
     * Reads the version and rejects anything this build does not speak.
     *
     * WHY a byte-swapped file lands here rather than in the magic check: a
     * big-endian-written GGUF still begins with the four ASCII bytes `GGUF`, so the
     * magic matches and the very next field is `0x03000000` instead of `3`. Reporting
     * that as an unsupported version is both true and actionable; reporting it as "not
     * a GGUF file" would send the user looking for a download problem.
     */
    private fun readVersion(reader: GgufReader): Int {
        val raw = reader.readU32()
        if (raw < 0 || raw > Int.MAX_VALUE || raw.toInt() !in SUPPORTED_VERSIONS) {
            throw GgufParseException(
                GgufParseException.Reason.UNSUPPORTED_VERSION,
                "GGUF version field is $raw; this build speaks ${SUPPORTED_VERSIONS.sorted()}",
                4L,
            )
        }
        return raw.toInt()
    }

    /**
     * Reads a declared count and refuses implausible ones before they can drive a loop.
     *
     * This is the anti-DoS choke point for the whole file: a 4-byte edit turns
     * `kv_count` into 2^63, and without this check the parse loop would spin for
     * centuries. The ceiling is orders of magnitude above any real model, so a
     * legitimate file never reaches it.
     */
    private fun readCount(reader: GgufReader, what: String, ceiling: Long): Long {
        val raw = reader.readU64()
        if (raw < 0 || raw > ceiling) {
            throw GgufParseException(
                GgufParseException.Reason.IMPLAUSIBLE_COUNT,
                "$what is $raw, above the $ceiling ceiling",
                reader.offset,
            )
        }
        return raw
    }

    // ------------------------------------------------------------------ key-value block

    private fun readKeyValues(
        reader: GgufReader,
        declared: Long,
        limits: GgufLimits,
        truncation: TruncationPolicy,
        warnings: MutableList<GgufWarning>,
    ): Map<String, GgufValue> {
        val fields = LinkedHashMap<String, GgufValue>(64)
        var index = 0L
        parse@ while (index < declared) {
            val key: String
            val value: GgufValue
            try {
                key = reader.readString(limits.maxKeyLength)
                val typeId = reader.readU32()
                val type = GgufValueType.fromId(typeId)
                if (type == null) {
                    // No width is knowable, so the next key cannot be located. Keep
                    // everything decoded so far and stop; see GgufWarning.UnknownValueType.
                    warnings += GgufWarning.UnknownValueType(key, typeId)
                    if (truncation == TruncationPolicy.STRICT) {
                        throw GgufParseException(
                            GgufParseException.Reason.UNSUPPORTED_STRUCTURE,
                            "key '$key' has unknown value type $typeId",
                            reader.offset,
                        )
                    }
                    break@parse
                }
                value = reader.readValue(type, limits, truncation, warnings)
            } catch (e: GgufParseException) {
                if (truncation == TruncationPolicy.STRICT || !isRecoverable(e)) throw e
                warnings += GgufWarning.KeyValueListTruncated(declared, index)
                break@parse
            }
            if (fields.put(key, value) != null) warnings += GgufWarning.DuplicateKey(key)
            index++
        }
        if (index < declared && warnings.none { it is GgufWarning.KeyValueListTruncated }) {
            warnings += GgufWarning.KeyValueListTruncated(declared, index)
        }
        return fields
    }

    /**
     * Decodes one value, dispatching on its tag.
     *
     * A recoverable failure is one caused by the file ending: a short read can be
     * reported with what we have, because we know exactly where we stopped. A framing
     * or count violation is not recoverable — continuing would mean trusting a length
     * that we have already established is a lie — so it always propagates.
     */
    private fun GgufReader.readValue(
        type: GgufValueType,
        limits: GgufLimits,
        truncation: TruncationPolicy,
        warnings: MutableList<GgufWarning>,
    ): GgufValue = when (type) {
        GgufValueType.UINT8,
        GgufValueType.UINT16,
        GgufValueType.UINT32,
        GgufValueType.UINT64,
        -> GgufValue.Signed(readU64OfWidth(type))
        GgufValueType.INT8 -> GgufValue.Signed(readU8().toByte().toLong())
        GgufValueType.INT16 -> GgufValue.Signed(readU16().toShort().toLong())
        GgufValueType.INT32 -> GgufValue.Signed(readU32().toInt().toLong())
        GgufValueType.INT64 -> GgufValue.Signed(readU64())
        GgufValueType.FLOAT32 -> GgufValue.Float32(Float.fromBits(readU32().toInt()))
        GgufValueType.FLOAT64 -> GgufValue.Float64(Double.fromBits(readU64()))
        GgufValueType.BOOL -> GgufValue.Bool(readU8() != 0)
        GgufValueType.STRING -> GgufValue.Text(readString(limits.maxStringLength))
        GgufValueType.ARRAY -> readArray(limits, truncation, warnings)
    }

    private fun GgufReader.readU64OfWidth(type: GgufValueType): Long = when (type) {
        GgufValueType.UINT8 -> readU8().toLong()
        GgufValueType.UINT16 -> readU16().toLong()
        GgufValueType.UINT32 -> readU32()
        else -> readU64()
    }

    /**
     * Decodes an array as a summary, never as a list of 256k values.
     *
     * WHY only the first [GgufLimits.maxArrayPreviewElements] are decoded: the only
     * array contents anything in this app reads are the first token (to tell a
     * SentencePiece vocab from a BPE one) and the count. Everything after the preview
     * is skipped with width arithmetic, so a 256k vocabulary costs 256k additions and
     * zero allocations instead of 256k strings.
     *
     * A scalar element array can be skipped without even walking it, but a string
     * array must be walked because each element carries its own length. Both paths
     * go through the same bounds-checked [skipValue] so neither can be the one place
     * that forgets to check.
     */
    private fun GgufReader.readArray(
        limits: GgufLimits,
        truncation: TruncationPolicy,
        warnings: MutableList<GgufWarning>,
    ): GgufValue {
        val elementTypeId = readU32()
        val elementType = GgufValueType.fromId(elementTypeId)
            ?: throw GgufParseException(
                GgufParseException.Reason.UNSUPPORTED_STRUCTURE,
                "array element type $elementTypeId is not known to this build",
                offset,
            )
        if (elementType == GgufValueType.ARRAY) {
            // The spec has no nested arrays. Rather than guess at a frame, say so.
            throw GgufParseException(
                GgufParseException.Reason.UNSUPPORTED_STRUCTURE,
                "array-of-array is not part of the GGUF spec",
                offset,
            )
        }
        val count = readU64()
        if (count < 0 || count > limits.maxArrayElements) {
            throw GgufParseException(
                GgufParseException.Reason.IMPLAUSIBLE_COUNT,
                "array of $count elements exceeds the ${limits.maxArrayElements} limit",
                offset,
            )
        }

        val preview = ArrayList<GgufValue>(minOf(count, limits.maxArrayPreviewElements.toLong()).toInt())
        var i = 0L
        while (i < count) {
            if (i < limits.maxArrayPreviewElements) {
                preview += readValue(elementType, limits, truncation, warnings)
            } else {
                skipValue(elementType, limits)
            }
            i++
        }
        return GgufValue.Array(elementType, count, preview)
    }

    /** Advances past one value of known type without materialising it. */
    private fun GgufReader.skipValue(type: GgufValueType, limits: GgufLimits) {
        val width = type.fixedByteWidth
        if (width != null) {
            skip(width.toLong())
            return
        }
        when (type) {
            GgufValueType.STRING -> skip(readBoundedStringLength(limits.maxStringLength).toLong())
            GgufValueType.ARRAY ->
                throw GgufParseException(
                    GgufParseException.Reason.UNSUPPORTED_STRUCTURE,
                    "cannot skip a nested array",
                    offset,
                )
            else -> throw GgufParseException(
                GgufParseException.Reason.UNSUPPORTED_STRUCTURE,
                "type $type has no width",
                offset,
            )
        }
    }

    /** Reads a string length and bounds-checks it, without decoding the bytes. */
    private fun GgufReader.readBoundedStringLength(maxLength: Int): Int {
        val declared = readU64()
        if (declared < 0 || declared > maxLength.toLong()) {
            throw GgufParseException(
                GgufParseException.Reason.LIMIT_EXCEEDED,
                "string length $declared at offset $offset exceeds the $maxLength-byte limit",
                offset,
            )
        }
        return declared.toInt()
    }

    // ------------------------------------------------------------------ tensor table

    /**
     * Reads the tensor table, which is the exact weight inventory.
     *
     * WHY read the table at all: summing `ceil(elements/block) * blockBytes` over the
     * table is not an estimate, it is the size of the file's weight section. That is
     * what lets the memory model report weights as *measured* rather than inferred from
     * a parameter count and a bits-per-weight guess, and it is the only way to catch a
     * file whose declared data runs past its own end.
     */
    private fun readTensorTable(
        reader: GgufReader,
        declared: Long,
        limits: GgufLimits,
        truncation: TruncationPolicy,
        warnings: MutableList<GgufWarning>,
    ): List<GgufTensorInfo> {
        val tensors = ArrayList<GgufTensorInfo>(minOf(declared, 4096L).toInt())
        var i = 0L
        parse@ while (i < declared) {
            val name: String
            val dims: List<Long>
            val typeId: Long
            val dataOffset: Long
            try {
                name = reader.readTensorName(limits.maxTensorNameLength)
                val nDims = reader.readU32()
                if (nDims < 0 || nDims > limits.maxTensorDimensions) {
                    throw GgufParseException(
                        GgufParseException.Reason.IMPLAUSIBLE_COUNT,
                        "tensor '$name' declares $nDims dimensions",
                        reader.offset,
                    )
                }
                dims = ArrayList<Long>(nDims.toInt())
                repeat(nDims.toInt()) { dims += reader.readU64() }
                typeId = reader.readU32()
                dataOffset = reader.readU64()
            } catch (e: GgufParseException) {
                if (truncation == TruncationPolicy.STRICT || !isRecoverable(e)) throw e
                warnings += GgufWarning.TensorTableTruncated(declared, i)
                break@parse
            }
            tensors += GgufTensorInfo(name, dims, typeId, dataOffset)
            i++
        }
        if (i < declared && warnings.none { it is GgufWarning.TensorTableTruncated }) {
            warnings += GgufWarning.TensorTableTruncated(declared, i)
        }
        return tensors
    }

    // ------------------------------------------------------------------ metadata resolution

    /**
     * Resolves the architecture prefix used for `<arch>.*` wildcard lookups.
     *
     * WHY infer when the key is missing: `general.architecture` is written by every
     * current converter, but a hand-assembled or very old file may omit it while still
     * carrying perfectly good `qwen3.block_count` keys. Falling back to the most
     * frequent key prefix recovers those, and [GgufMetadata.architectureInferred] keeps
     * the fact that it was a guess from being invisible.
     */
    private fun resolveArchitecture(
        fields: Map<String, GgufValue>,
        warnings: MutableList<GgufWarning>,
    ): String? {
        fields.stringOrNull("general.architecture")?.let { return it }
        val counts = HashMap<String, Int>()
        for (key in fields.keys) {
            val dot = key.indexOf('.')
            if (dot <= 0) continue
            if (key.startsWith("tokenizer.") || key.startsWith("general.")) continue
            counts.merge(key.substring(0, dot), 1, Int::plus)
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return null
        warnings += GgufWarning.ArchitectureInferred(best.key)
        return best.key
    }

    /**
     * Flags values that are structurally unusable, so "missing" and "lying" stay
     * distinguishable in the UI.
     *
     * A declared context length of 2^62 and a missing context length both end up as
     * `null` in the resolved view. Reporting them identically would let a corrupt file
     * be described as merely sparse.
     */
    private fun findImplausibleKeys(fields: Map<String, GgufValue>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((key, value) in fields) {
            // asDouble covers integers and both float types in one step. A uint64 above
            // Long.MAX_VALUE arrives as a negative, which the negative test below catches.
            val number = value.asDouble() ?: continue
            if (number < 0 || number.isNaN() || number > MAX_PLAUSIBLE_METADATA_VALUE) {
                out += key to number.toString()
            }
        }
        return out
    }

    private fun addConsistencyWarnings(header: GgufHeader, warnings: MutableList<GgufWarning>) {
        val declared = header.declaredWeightBytes
        if (declared != null) {
            val end = saturatingAdd(header.headerBytes, declared)
            if (end > header.fileBytes) {
                warnings += GgufWarning.DeclaredDataExceedsFile(end, header.fileBytes)
            }
        }
        val splitCount = header.metadata.splitCount
        if (splitCount != null && splitCount > 1) {
            warnings += GgufWarning.ShardedModel(splitCount, header.metadata.splitNumber ?: 0L)
        }
        val fileType = header.metadata.fileType
        val dominant = GgufQuantType.dominant(header.tensors)
        if (fileType != null && dominant != null && !GgufFileType.agreesWith(fileType, dominant)) {
            warnings += GgufWarning.FileTypeDisagreesWithTensors(fileType, dominant)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun Map<String, GgufValue>.stringOrNull(key: String): String? =
        (this[key] as? GgufValue.Text)?.value?.takeIf { it.isNotBlank() }

    /**
     * Whether a parse failure leaves the rest of the file interpretable.
     *
     * Only a short read qualifies. A count or limit violation means the file has
     * already told us something impossible, and continuing to interpret bytes after
     * that is how a parser ends up allocating on a lie.
     */
    private fun isRecoverable(e: GgufParseException): Boolean = e.reason == GgufParseException.Reason.TRUNCATED

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
