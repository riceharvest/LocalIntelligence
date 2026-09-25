package dev.localintelligence.core.model.gguf

/**
 * Hard ceilings applied to every length, count and offset read out of a GGUF file.
 *
 * WHY this exists as a value rather than constants scattered through the reader: this
 * parser runs against files the user picked off their own storage. A file can be
 * truncated, truncated *and* hostile, or not a GGUF file at all wearing the right four
 * bytes of magic. Every number in a GGUF header is attacker-controlled, and the two
 * ways that normally kills a parser are (a) allocating `length` bytes because the
 * header said so and (b) looping `count` times because the header said so. Both
 * defaults below are set so that a hostile header costs the same as an empty file.
 *
 * The values are generous enough for real models: a Qwen3 151k-token vocabulary is a
 * 151936-element string array and still parses comfortably inside
 * [maxHeaderBytes], which is where a ~2 MiB tokenizer vocabulary lands.
 *
 * Callers with a known-good file may relax these; callers on an untrusted path should
 * keep the defaults and treat the defaults as part of the security model.
 */
data class GgufLimits(
    /**
     * Total bytes the reader will consume, counted from the first byte of the file.
     *
     * WHY: the header of a heavily quantised model is a few hundred KiB and the worst
     * case is a 256k-entry vocabulary of long byte-fallback tokens, which is a couple
     * of MiB. 32 MiB is ~10x that worst case, and anything larger is a sign we are
     * being walked off the end of a file rather than reading metadata.
     */
    val maxHeaderBytes: Long = 32L * 1024 * 1024,

    /**
     * Cap on declared key-value pair count.
     *
     * WHY: a real GGUF carries fewer than 400 pairs, tokenizer arrays being values and
     * not pairs. A declared count in the millions is not a tokenizer, it is a loop bomb.
     */
    val maxKeyValuePairs: Long = 100_000,

    /**
     * Cap on a declared tensor count.
     *
     * WHY: no real model has more than a few thousand tensors; a declared count in the
     * hundreds of millions is a loop bomb wearing a plausible type.
     */
    val maxTensorCount: Long = 5_000_000,

    /** Cap on a metadata key's byte length. Keys are short identifiers, never payloads. */
    val maxKeyLength: Int = 1_024,

    /** Cap on a single metadata string value (a name, a chat template, a license blob). */
    val maxStringLength: Int = 4 * 1024 * 1024,

    /**
     * Cap on elements in one metadata array. Sized for real vocabularies (151936 for
     * Qwen3, 256k for Llama 3) with two orders of magnitude of headroom.
     */
    val maxArrayElements: Long = 10_000_000,

    /** Cap on a tensor name's byte length. */
    val maxTensorNameLength: Int = 1_024,

    /**
     * Cap on a tensor's declared dimensionality. ggml's own compile-time maximum is 4;
     * 8 gives slack for future layouts without letting a hostile file allocate a huge
     * dims array per tensor.
     */
    val maxTensorDimensions: Int = 8,

    /**
     * How many leading elements of a metadata array are actually decoded and retained.
     *
     * WHY nonzero: `tokenizer.ggml.tokens[0]` is how you detect a SentencePiece vs BPE
     * vocab, and the first few merge scores are occasionally useful for a UI. Why small:
     * the rest are skipped by width arithmetic, not decoded, so a 256k vocab costs a
     * 256k-iteration arithmetic loop and zero bytes of heap.
     */
    val maxArrayPreviewElements: Int = 8,

    /**
     * Ceiling applied to a context length before it is fed to the memory estimator.
     *
     * WHY: context length is multiplied by layers, heads and width to size the KV cache.
     * A declared 2^63 context would not crash an integer multiply in Kotlin — it would
     * saturate into a number so large the model reads as "fits in memory", which is
     * exactly backwards. Clamping keeps the failure visible as a warning instead.
     */
    val maxContextLength: Long = 8 * 1024 * 1024,
)
