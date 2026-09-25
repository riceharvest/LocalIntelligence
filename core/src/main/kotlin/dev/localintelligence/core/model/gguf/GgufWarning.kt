package dev.localintelligence.core.model.gguf

/**
 * A problem found while reading a header that did not stop the parse.
 *
 * WHY a warning list instead of failing the parse: a model the user has half
 * downloaded, or one written by a newer converter that added a key type, still
 * carries the metadata the UI needs to answer "can I run this?". Throwing away a
 * perfectly good context length because one unknown key appeared would be trading a
 * correct answer for a pedantic one. The warnings exist so the *incompleteness* is
 * still visible — a partial estimate is shown as partial, not dressed up as certain.
 */
sealed interface GgufWarning {

    /** A human-readable one-liner. Enough for a list row; not a full report. */
    val message: String

    /**
     * The header ran out of metadata before the declared number of pairs. Every pair
     * read so far is valid; the rest are simply absent, so any "missing" field is
     * missing because the file is short, not because the model lacks it.
     */
    data class KeyValueListTruncated(val declared: Long, val parsed: Long) : GgufWarning {
        override val message: String
            get() = "metadata truncated: declared $declared key-value pairs, read $parsed"
    }

    /** The tensor table ran out before the declared tensor count, so weight sizing is partial. */
    data class TensorTableTruncated(val declared: Long, val parsed: Long) : GgufWarning {
        override val message: String
            get() = "tensor table truncated: declared $declared tensors, read $parsed"
    }

    /**
     * A key carried a value type this build does not know.
     *
     * WHY the parse stops there instead of skipping the value: a GGUF key-value
     * stream is self-delimiting only because the reader knows the width of every
     * value. Given an unknown tag there is no width, and therefore no way to find the
     * next key — the file has no resync point. Reading on would mean interpreting
     * attacker-chosen bytes as a length. So the reader keeps every pair decoded before
     * this one, records the tag, and returns. That is the only honest graceful
     * degradation available, and it is why this is a warning and not an exception.
     */
    data class UnknownValueType(val key: String, val typeId: Long) : GgufWarning {
        override val message: String
            get() = "unsupported value type $typeId for key '$key'; remaining metadata skipped"
    }

    /** The same key appeared twice. Last write wins, exactly as llama.cpp would do. */
    data class DuplicateKey(val key: String) : GgufWarning {
        override val message: String get() = "duplicate metadata key '$key'"
    }

    /**
     * The tensor table's own arithmetic says the weight data would end past the end of
     * the file. This is the honest signal for "this download is incomplete", and it is
     * the check that a 512-byte stub of a 4 GiB model cannot pass.
     */
    data class DeclaredDataExceedsFile(val declaredEndBytes: Long, val fileBytes: Long) : GgufWarning {
        override val message: String
            get() = "declared tensor data ends at $declaredEndBytes but the file is only $fileBytes bytes"
    }

    /** `split.count` > 1: this is one shard of a model and cannot be loaded alone. */
    data class ShardedModel(val shardCount: Long, val shardNumber: Long) : GgufWarning {
        override val message: String
            get() = "shard $shardNumber of $shardCount; this file alone is not a loadable model"
    }

    /** `general.file_type` disagrees with the dominant tensor type. Both are reported. */
    data class FileTypeDisagreesWithTensors(
        val declaredFileType: GgufFileType,
        val dominantTensorType: GgufQuantType,
    ) : GgufWarning {
        override val message: String
            get() = "file_type says ${declaredFileType.label} but most weights are ${dominantTensorType.label}"
    }

    /** `general.architecture` absent, so the architecture was inferred from key prefixes. */
    data class ArchitectureInferred(val inferred: String) : GgufWarning {
        override val message: String get() = "general.architecture missing; inferred '$inferred' from key prefixes"
    }

    /** A dimension count beyond ggml's own maximum; treated as a corrupt file. */
    data class ImplausibleDimensions(val tensor: String, val dimensions: Long) : GgufWarning {
        override val message: String
            get() = "tensor '$tensor' declares $dimensions dimensions"
    }

    /** No context length anywhere in the metadata, so the estimator assumed one and said so. */
    data class ContextLengthMissing(val assumed: Long) : GgufWarning {
        override val message: String
            get() = "no context_length in metadata; memory estimate assumes $assumed tokens"
    }

    /** A declared context length above the configured ceiling, clamped before use. */
    data class ContextLengthClamped(val declared: Long, val clampedTo: Long) : GgufWarning {
        override val message: String
            get() = "declared context length $declared clamped to $clampedTo"
    }

    /** Metadata carried a non-finite or negative number where a size was expected. */
    data class ImplausibleValue(val key: String, val raw: String) : GgufWarning {
        override val message: String get() = "key '$key' holds an implausible value ($raw)"
    }
}
