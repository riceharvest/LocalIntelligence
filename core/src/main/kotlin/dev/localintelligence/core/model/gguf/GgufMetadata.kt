package dev.localintelligence.core.model.gguf

/**
 * The GGUF metadata keys this app actually cares about, resolved from the raw field
 * map.
 *
 * WHY a resolved view instead of making every caller know the key strings: the
 * architecture prefix is a per-model wildcard (`llama.context_length`,
 * `qwen3.context_length`, `gemma3.attention.head_count`), and the fallbacks for
 * head dimension are three deep. Scattering that knowledge across a UI and a memory
 * estimator guarantees the two drift apart. The wildcard resolution happens once, here,
 * and the result is a flat class of `Long?` fields where "absent" is unambiguous.
 *
 * Every field is nullable and absence is meaningful: a missing `headCountKv` is the
 * signal to fall back to MHA, and a missing `contextLength` is the signal that the
 * memory estimate is guessing. Collapsing those into a default of 0 at parse time
 * would hide exactly the information the UI needs to be honest.
 *
 * Out-of-range values are dropped to `null` and reported as [implausibleKeys] rather
 * than clamped, so a file claiming 2^62 context does not become a 2^62 KV cache.
 */
class GgufMetadata internal constructor(
    /** Every decoded key, in file order. Unrecognised keys stay reachable. */
    val fields: Map<String, GgufValue>,
    /** Architecture prefix used for wildcard lookups, e.g. `llama` in `llama.block_count`. */
    val architecture: String?,
    /** True when [architecture] was inferred from key prefixes because the key was missing. */
    val architectureInferred: Boolean,
    /** Keys whose value could not be used as a number, with the raw value, for display. */
    val implausibleKeys: List<Pair<String, String>>,
) {

    /**
     * Reads `<architecture>.<suffix>`, the form nearly every architecture key takes.
     *
     * WHY a member rather than a literal lookup at each call site: the prefix is
     * per-model, so `block_count` and `attention.head_count` are not fixed key names.
     * Routing all of them through here means an inferred architecture is honoured
     * everywhere at once, and there is exactly one place where a future "this
     * architecture puts it at the top level" special case would have to go.
     */
    private fun archLong(suffix: String): Long? {
        val prefix = architecture ?: return null
        return fields.long("$prefix.$suffix")
    }

    /** `general.architecture`, or the inferred prefix. `llama`, `qwen3`, `gemma3`, ... */
    val architectureName: String? get() = architecture

    /** `general.name` — the converter's own label, e.g. "Qwen3 4B Instruct". */
    val name: String? get() = fields.string("general.name")

    /** `general.basename` — the filename stem the converter recorded. */
    val basename: String? get() = fields.string("general.basename")

    /** `general.file_type` as llama.cpp labels it. A claim, not ground truth. */
    val fileType: GgufFileType?
        get() = fields.long("general.file_type")?.let(GgufFileType::fromId)

    /** `general.parameter_count`, when a converter bothered to write one. */
    val declaredParameterCount: Long? get() = fields.long("general.parameter_count")

    /** `<arch>.context_length` — the trained window. 0 or absent means "unknown". */
    val contextLength: Long? get() = archLong("context_length")

    /** `<arch>.train_context_length` — sometimes the real trained length when the key above is a cap. */
    val trainContextLength: Long? get() = archLong("train_context_length")

    /** `<arch>.block_count` — transformer layer count. The single most important number here. */
    val blockCount: Long? get() = archLong("block_count")

    /** `<arch>.embedding_length` — model width, used for head dimension when nothing better exists. */
    val embeddingLength: Long? get() = archLong("embedding_length")

    /** `<arch>.feed_forward_length` — the MLP inner width. */
    val feedForwardLength: Long? get() = archLong("feed_forward_length")

    /** `<arch>.attention.head_count` — query heads. */
    val headCount: Long? get() = archLong("attention.head_count")

    /**
     * `<arch>.attention.head_count_kv` — key/value heads.
     *
     * Absent means multi-head attention with one KV head per query head, so callers
     * should fall back to [headCount] rather than to 0. Getting this backwards (using
     * 0, or using headCount when the file says otherwise) is the classic 8x KV-cache
     * overestimation, so the fallback lives in one documented place: [kvHeadCount].
     */
    val headCountKv: Long? get() = archLong("attention.head_count_kv")

    /** `<arch>.attention.key_length` — per-head key projection width. */
    val keyLength: Long? get() = archLong("attention.key_length")

    /** `<arch>.attention.value_length` — per-head value projection width. */
    val valueLength: Long? get() = archLong("attention.value_length")

    /** `<arch>.rope.dimension_count` — RoPE width, usually the head dimension. */
    val ropeDimensionCount: Long? get() = archLong("rope.dimension_count")

    /** `<arch>.expert_count` — total experts, for MoE models. */
    val expertCount: Long? get() = archLong("expert_count")

    /** `<arch>.expert_used_count` — experts activated per token, for MoE models. */
    val expertUsedCount: Long? get() = archLong("expert_used_count")

    /** `<arch>.expert_feed_forward_length` — per-expert MLP width, for MoE models. */
    val expertFeedForwardLength: Long? get() = archLong("expert_feed_forward_length")

    /** `tokenizer.ggml.tokens` element count, the honest vocabulary size. */
    val vocabularySize: Long?
        get() = (fields["tokenizer.ggml.tokens"] as? GgufValue.Array)?.count

    /** `general.split.count` — shard count; > 1 means this file is not a whole model. */
    val splitCount: Long? get() = fields.long("general.split.count")

    /** `general.split.no` — this file's zero-based shard index. */
    val splitNumber: Long? get() = fields.long("general.split.no")

    /** Every `*.expert_*` key present, for models whose MoE layout does not match this parser. */
    val expertKeys: Map<String, Long>
        get() = fields.entries.mapNotNull { (k, v) ->
            if (k.contains(".expert")) v.asLong()?.let { k to it } else null
        }.toMap()

    /**
     * KV heads to use, with the MHA fallback applied.
     *
     * Returns `null` only when the file has neither a KV head count nor a query head
     * count, which means the KV cache cannot be sized at all — a different failure from
     * "size it as MHA", and the estimator treats them differently.
     */
    val kvHeadCount: Long? get() = headCountKv ?: headCount

    /**
     * The head dimension to use, and where it came from.
     *
     * WHY the order: `attention.key_length` is what attention actually computes with.
     * `rope.dimension_count` is the next most trustworthy because a mismatch between it
     * and key length means partial RoPE. `embedding_length / head_count` is a division
     * that is only valid when the model has no per-head width at all.
     *
     * The returned source matters for display: an estimate built on a derived head
     * dimension is weaker evidence than one built on a declared `key_length`, and the
     * UI should be able to say which it got.
     */
    fun resolveHeadDimension(): HeadDimension? {
        keyLength?.takeIf { it > 0 }?.let { return HeadDimension(it, HeadDimensionSource.KEY_LENGTH) }
        ropeDimensionCount?.takeIf { it > 0 }
            ?.let { return HeadDimension(it, HeadDimensionSource.ROPE_DIMENSION_COUNT) }
        val width = embeddingLength?.takeIf { it > 0 } ?: return null
        val heads = headCount?.takeIf { it > 0 } ?: return null
        if (width % heads != 0L) return null
        return HeadDimension(width / heads, HeadDimensionSource.EMBEDDING_LENGTH_OVER_HEAD_COUNT)
    }

    /** A resolved head dimension paired with the evidence used, so estimates can be graded. */
    data class HeadDimension(val value: Long, val source: HeadDimensionSource)

    enum class HeadDimensionSource {
        /** `<arch>.attention.key_length` — declared directly by attention. */
        KEY_LENGTH,

        /** `<arch>.rope.dimension_count` — declared, but for RoPE rather than attention. */
        ROPE_DIMENSION_COUNT,

        /** Derived by dividing embedding length by head count. Weakest evidence. */
        EMBEDDING_LENGTH_OVER_HEAD_COUNT,
    }
}

/** Reads a string field, or null. */
private fun Map<String, GgufValue>.string(key: String): String? = this[key]?.asString()

/**
 * Reads an integer field, or null.
 *
 * Returns null for negatives, for values above [Long.MAX_VALUE], and for anything past
 * [MAX_PLAUSIBLE_METADATA_VALUE] — all of which are "this file is lying" rather than
 * "this file has no value", and the difference is visible in [implausibleKeys]. Applying
 * the ceiling here rather than only in the warning list is deliberate: this is the view
 * the memory estimator reads, so a 2^62 layer count that reached it would produce a KV
 * cache sized for a model that does not exist.
 */
private fun Map<String, GgufValue>.long(key: String): Long? =
    this[key]?.asLong()?.takeIf { it >= 0 && it <= MAX_PLAUSIBLE_METADATA_VALUE }
