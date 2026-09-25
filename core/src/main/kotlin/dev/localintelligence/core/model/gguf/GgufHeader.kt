package dev.localintelligence.core.model.gguf

/**
 * Everything a GGUF header states about a model, and nothing about the weights.
 *
 * WHY this type carries [fileBytes] and [isComplete]: the two facts that decide whether
 * a user can run a model are not in the metadata at all. They are (a) how big the file
 * actually is on disk and (b) whether the file was whole when we read it. Without
 * both, a truncated download of a 4 GiB model parses into a perfectly healthy-looking
 * header and the UI cheerfully reports a loadable model. [isComplete] and the
 * `DeclaredDataExceedsFile` warning are how that lie gets caught.
 *
 * [warnings] is part of the contract, not diagnostics. A caller showing a memory
 * estimate must be able to see that the estimate was built from a partial header.
 */
data class GgufHeader(
    /** GGUF format version read from the file. v3 is current; v1/v2 are accepted. */
    val version: Int,
    /** Size of the file the header was read from, in bytes. */
    val fileBytes: Long,
    /** Number of tensors the header *declared*, which may exceed [tensors].size. */
    val declaredTensorCount: Long,
    /** Tensors actually read from the tensor table. */
    val tensors: List<GgufTensorInfo>,
    /** Resolved metadata view. */
    val metadata: GgufMetadata,
    /** Bytes consumed by the header and tensor table. The weights start after this, plus alignment. */
    val headerBytes: Long,
    /**
     * False when any part of the declared header could not be read.
     *
     * This is the single flag a UI needs: false means "this file is damaged or
     * unfamiliar, treat every number below as a lower bound".
     */
    val isComplete: Boolean,
    val warnings: List<GgufWarning>,
) {
    /** Every metadata field, in file order, including keys this build does not interpret. */
    val fields: Map<String, GgufValue> get() = metadata.fields

    /**
     * Bytes the weights occupy, summed from the tensor table.
     *
     * `null` when the table was not fully readable — either because the file has tensors
     * declared but none parsed, or because a tensor's type is unknown to this build.
     * Returning a partial sum would understate a model, which is the one direction an
     * error that bad cannot take. A tensor declaring an impossible shape saturates
     * rather than wraps.
     */
    val declaredWeightBytes: Long?
        get() {
            if (tensors.isEmpty()) return if (declaredTensorCount == 0L) 0L else null
            var total = 0L
            for (t in tensors) {
                val b = t.bytes ?: return null
                total = saturatingAdd(total, b)
            }
            return total
        }

    /**
     * Total parameter count summed from the tensor table shapes.
     *
     * `null` when the table is missing or incomplete, so the caller can fall back to a
     * declared count instead of believing a partial sum. Note this counts everything,
     * output heads and embeddings included, which is what `general.parameter_count`
     * usually means too, so the two are comparable rather than systematically different.
     */
    val tensorTableParameterCount: Long?
        get() {
            if (tensors.isEmpty()) return if (declaredTensorCount == 0L) 0L else null
            var total = 0L
            for (t in tensors) total = saturatingAdd(total, t.elementCount)
            return total
        }

    /**
     * The quantisation that best describes this file.
     *
     * The tensor-table mode wins over `general.file_type` because the table is
     * ground truth and `file_type` is a label the converter wrote once and may have
     * stopped maintaining. Ties go to the declared file type, because a converter that
     * knew it was producing a Q4_K_M has context the table does not.
     */
    val dominantQuantType: GgufQuantType?
        get() {
            val fromTensors = GgufQuantType.dominant(tensors)
            val declared = metadata.fileType?.representativeQuant
            return fromTensors ?: declared
        }

    /** True when the weights alone exceed the bytes actually present in the file. */
    fun declaresMoreDataThanFileHas(): Boolean {
        val declared = declaredWeightBytes ?: return false
        return saturatingAdd(headerBytes, declared) > fileBytes
    }
}
