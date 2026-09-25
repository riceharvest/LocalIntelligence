package dev.localintelligence.core.model.gguf

/**
 * One shared, fully-specified synthetic model, so parser tests and estimator tests
 * assert against the same numbers instead of each inventing their own.
 *
 * The geometry is deliberately small enough that every figure in the tests can be
 * checked by hand, and small enough that the built file stays under 100 KiB. It is
 * shaped like a real 4-layer GQA transformer: `head_count` 4 with `head_count_kv` 2,
 * and no `key_length`, so the head dimension has to be derived from
 * `embedding_length / head_count` — which is the path most likely to be wrong and so
 * the one most worth pinning.
 *
 * Expected values, all derived by hand and asserted in the tests:
 * ```
 * head dim        = 128 / 4            = 32
 * tok_embd        = 256 x 128 = 32768 weights, Q4_K = 128 blocks x 144 = 18432 bytes
 * output          = 200 x 128 = 25600 weights, Q6_K = 100 blocks x 210 = 21000 bytes
 * declared weights                        = 39432 bytes
 * parameters (tensor table)               = 58368
 * kv cache @ 512 ctx, f16 = 2*4*2*32*512*2 = 524288 bytes
 * ```
 */
object TinyModelSpec {
    const val ARCHITECTURE = "qwen3"
    const val NAME = "Qwen3 0.6B Instruct (synthetic test fixture)"
    const val LAYERS = 4L
    const val CONTEXT = 512L
    const val EMBEDDING = 128L
    const val FEED_FORWARD = 256L
    const val HEAD_COUNT = 4L
    const val HEAD_COUNT_KV = 2L
    const val VOCAB = 64

    const val TOK_EMB_ELEMENTS = 32_768L
    const val OUTPUT_ELEMENTS = 25_600L
    const val TOK_EMB_BYTES = 18_432L
    const val OUTPUT_BYTES = 21_000L
    const val DECLARED_WEIGHT_BYTES = TOK_EMB_BYTES + OUTPUT_BYTES
    const val PARAMETER_COUNT = TOK_EMB_ELEMENTS + OUTPUT_ELEMENTS
    const val HEAD_DIM = 32L

    /** `2 (K,V) * layers * kvHeads * headDim * ctx * 2 bytes`. */
    const val KV_BYTES_AT_512_F16 = 524_288L

    const val Q4_K_FILE_TYPE = 15L
    const val Q8_0_FILE_TYPE = 7L
    const val F32_FILE_TYPE = 0L
    const val Q6_K_FILE_TYPE = 18L

    /**
     * A complete, self-consistent file: every declared weight byte is actually present.
     *
     * [weightDataBytes] defaults to exactly the declared weight size, which is the
     * boundary at which [GgufHeader.declaresMoreDataThanFileHas] flips to false.
     */
    fun builder(
        quant: GgufQuantType = GgufQuantType.Q4_K,
        fileType: Long? = Q4_K_FILE_TYPE,
        withContext: Boolean = true,
        vocab: Int = VOCAB,
    ): SyntheticGgufBuilder {
        val b = SyntheticGgufBuilder()
        b.string("general.architecture", ARCHITECTURE)
        b.string("general.name", NAME)
        b.string("general.basename", "tiny-qwen3-test")
        fileType?.let { b.u32("general.file_type", it) }
        b.u64("general.parameter_count", PARAMETER_COUNT)
        if (withContext) b.u32("$ARCHITECTURE.context_length", CONTEXT)
        b.u32("$ARCHITECTURE.block_count", LAYERS)
        b.u32("$ARCHITECTURE.embedding_length", EMBEDDING)
        b.u32("$ARCHITECTURE.feed_forward_length", FEED_FORWARD)
        b.u32("$ARCHITECTURE.attention.head_count", HEAD_COUNT)
        b.u32("$ARCHITECTURE.attention.head_count_kv", HEAD_COUNT_KV)
        b.stringArray("tokenizer.ggml.tokens", (0 until vocab).map { "tok$it" })
        b.tensor("token_embd.weight", listOf(256L, EMBEDDING), quant)
        b.tensor("output.weight", listOf(200L, EMBEDDING), GgufQuantType.Q6_K)
        return b
    }

    /**
     * A complete file with exactly as much weight padding as the tensor table declares,
     * so the size check has something real to agree with.
     *
     * The padding is passed to [SyntheticGgufBuilder.build], not baked in here: a builder
     * that recorded a pad it did not apply would produce a "valid" fixture that is in
     * fact a truncated download, and the first test to notice would be a confusing one.
     */
    fun validFile(
        quant: GgufQuantType = GgufQuantType.Q4_K,
        fileType: Long? = Q4_K_FILE_TYPE,
    ): ByteArray = builder(quant = quant, fileType = fileType).build(DECLARED_WEIGHT_BYTES.toInt())

    /** A MoE variant, for the `*.expert_count` extraction path. */
    fun moeFile(): ByteArray = SyntheticGgufBuilder().apply {
        string("general.architecture", ARCHITECTURE)
        string("general.name", "TinyMoE (synthetic test fixture)")
        u32("general.file_type", Q4_K_FILE_TYPE)
        u32("$ARCHITECTURE.context_length", CONTEXT)
        u32("$ARCHITECTURE.block_count", LAYERS)
        u32("$ARCHITECTURE.embedding_length", EMBEDDING)
        u32("$ARCHITECTURE.attention.head_count", HEAD_COUNT)
        u32("$ARCHITECTURE.attention.head_count_kv", HEAD_COUNT_KV)
        u32("$ARCHITECTURE.expert_count", 8)
        u32("$ARCHITECTURE.expert_used_count", 2)
        u32("$ARCHITECTURE.expert_feed_forward_length", 64)
        u32("$ARCHITECTURE.rope.dimension_count", HEAD_DIM)
        tensor("token_embd.weight", listOf(256L, EMBEDDING), GgufQuantType.Q4_K)
    }.build(DECLARED_WEIGHT_BYTES.toInt())
}
