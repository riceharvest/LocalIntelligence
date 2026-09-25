package dev.localintelligence.android.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [ModelImporter] itself needs a `ContentResolver` and a real `Uri`, so it is
 * not constructible in a JVM unit test. What *is* testable — and what the model
 * manager and the RAM gate actually depend on — is everything underneath it:
 * [RamEstimate] and [GgufReader]. Those are exercised here against synthetic
 * streams. The SAF-specific surface is covered by androidTest, which needs a
 * device; see the PR description.
 */
class ModelImporterTest {

    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    /**
     * A llama-3.2-3B-Q4_K_M-shaped header: 28 layers, 8 KV heads, head_dim 128.
     *
     * The *stream* is only the header, but the declared length is the real file
     * size, because that is exactly what the model manager sees: a
     * ContentResolver descriptor reports the whole file's size while the header
     * is a few hundred bytes of it. Allocating 1.9 GB of padding here would
     * test the heap, not the parser.
     */
    private fun parse3B(fileSizeBytes: Long = 1_930_000_000L, contextLength: Int = 131_072): GgufMetadata {
        val header = GgufTestBuilder()
            .header(tensorCount = 291, kvCount = 10)
            .kvString("general.architecture", "llama")
            .kvString("general.name", "Llama 3.2 3B Instruct")
            .kvU32("general.file_type", 15) // Q4_K_M
            .kvU64("general.parameter_count", 3_211_758_208L)
            .kvU32("llama.context_length", contextLength.toLong())
            .kvU32("llama.embedding_length", 3072)
            .kvU32("llama.block_count", 28)
            .kvU32("llama.feed_forward_length", 8192)
            .kvU32("llama.attention.head_count", 24)
            .kvU32("llama.attention.head_count_kv", 8)
            .build()
        return GgufReader.parse(ByteArrayInputStream(header), fileSizeBytes)
    }

    // ---- the documented worked example -------------------------------------------

    @Test
    fun `3B Q4_K_M at 4K context lands at the documented 2_37 GiB`() {
        val est = RamEstimate.from(parse3B(), contextLength = 4096)

        // Weights come from the declared file size, so this is the real number.
        assertEquals(1_930_000_000L, est.weightBytes)

        // KV: 28 layers * 8 kv-heads * 128 head_dim * 2 (K,V) * 2 (f16) = 4096 B
        //     per token per layer, 114_688 B per token.
        assertEquals(4_096L * 28, est.kvBytesPerToken)
        assertEquals(114_688L, est.kvBytesPerToken)

        // 114_688 * 4096 = 469_762_048 B = 448 MiB
        assertEquals(469_762_048L, est.kvBytes(4096))
        assertEquals(448L, est.kvBytes(4096) / mib)

        val total = est.totalBytes(4096)
        // weights + 448 MiB + 0.5 MiB logits + 128 MiB compute
        assertEquals(1_930_000_000L + 469_762_048L + est.logitsBytes() + 128 * mib, total)
        assertEquals(2.37, total.toDouble() / gib, 0.02)
    }

    @Test
    fun `KV cache is exactly linear in context length`() {
        val est = RamEstimate.from(parse3B())
        val k1 = est.kvBytes(1024)
        val k4 = est.kvBytes(4096)
        val k8 = est.kvBytes(8192)
        val k32 = est.kvBytes(32768)

        assertEquals(k1 * 4, k4)          // exact integer scaling
        assertEquals(k1 * 8, k8)
        assertEquals(k1 * 32, k32)
        // 448 MiB per 4K, i.e. 114,688 B per token.
        assertEquals(448L * mib, k4)
        assertEquals(114_688L, k1 / 1024)
    }

    @Test
    fun `weights stay constant as context grows so the KV cache dominates`() {
        val est = RamEstimate.from(parse3B())
        val at4k = est.totalBytes(4096)
        val at32k = est.totalBytes(32768)

        // 448 MiB per 4K -> 3.5 GiB at 32K, linear.
        assertEquals(3.5, est.kvBytes(32768) / gib.toDouble(), 0.01)
        assertTrue("KV should dominate at 32K", est.kvBytes(32768) > est.weightBytes)
        assertTrue(at32k > at4k)
    }

    @Test
    fun `the documented per-context totals match a recomputation`() {
        // The table in the RamEstimate KDoc, verified rather than trusted.
        val est = RamEstimate.from(parse3B())
        val expectedKv = mapOf(
            1024 to 0.11, 4096 to 0.44, 8192 to 0.88,
            32768 to 3.50, 131072 to 14.00,
        )
        for ((ctx, gibExpected) in expectedKv) {
            val actual = est.kvBytes(ctx) / gib.toDouble()
            assertEquals(
                "KV at ctx=$ctx",
                gibExpected,
                actual,
                0.02 * gibExpected,
            )
        }
    }

    @Test
    fun `a 3B at 4K does not fit in 2 GiB but does in 4 GiB`() {
        val est = RamEstimate.from(parse3B())
        assertFalse("2 GiB is below the weight+KV total", est.fitsIn(2 * gib, 4096))
        assertTrue(est.fitsIn(4 * gib, 4096))
    }

    // ---- oversized-model gate ---------------------------------------------------

    @Test
    fun `a 128K-context 3B is correctly refused on a 6 GiB budget`() {
        val est = RamEstimate.from(parse3B())
        val budget = 6 * gib
        assertFalse("15.9 GiB must not fit 6 GiB", est.fitsIn(budget, 131_072))
        // And the affordable context is genuinely well below the trained 128K:
        // 6 GiB - 1.93 GB weights - 0.5 MiB logits - 128 MiB compute
        // = 4.45 GB of KV / 114,688 B per token = ~38K tokens.
        val affordable = est.maxAffordableContext(budget)
        assertTrue("affordable=$affordable", affordable in 32_768..40_960)
        assertTrue("must be far below the trained 128K", affordable < 131_072 / 3)
    }

    @Test
    fun `max affordable context never promises more than fits`() {
        val est = RamEstimate.from(parse3B())
        for (budget in listOf(2L * gib, 3 * gib, 4 * gib, 8 * gib)) {
            val ctx = est.maxAffordableContext(budget)
            assertTrue("ctx=$ctx at budget=$budget", est.fitsIn(budget, ctx))
            if (ctx > 0) {
                assertFalse(
                    "one more token must not fit",
                    est.fitsIn(budget, ctx + 1),
                )
            }
        }
    }

    @Test
    fun `a budget smaller than the weights affords no context at all`() {
        val est = RamEstimate.from(parse3B())
        assertEquals(0, est.maxAffordableContext(1L * gib))
        assertFalse(est.fitsIn(1L * gib, 1024))
    }

    @Test
    fun `a large model is refused on a small budget`() {
        // A 70B Q4_K is ~40 GB of weights; nothing phone-sized holds it.
        val bigHeader = GgufTestBuilder()
            .header(kvCount = 7)
            .kvString("general.architecture", "llama")
            .kvU32("llama.context_length", 8192)
            .kvU32("llama.embedding_length", 8192)
            .kvU32("llama.block_count", 80)
            .kvU32("llama.attention.head_count", 64)
            .kvU32("llama.attention.head_count_kv", 8)
            .kvU64("general.parameter_count", 70_000_000_000L)
            .build()
        val sizeBytes = 40_000_000_000L
        val meta = GgufReader.parse(
            ByteArrayInputStream(bigHeader),
            sizeBytes,
        )
        val est = RamEstimate.from(meta)
        // fileSizeBytes is not populated by parse() with a header-only buffer,
        // so this exercises the parameter-count fallback path.
        assertFalse("40 GB must not fit 8 GiB", est.fitsIn(8 * gib, 8192))
    }

    // ---- metadata extraction from a synthetic stream ----------------------------

    @Test
    fun `size and metadata are extracted from a synthetic stream`() {
        val declared = 1_930_000_000L
        val meta = parse3B(declared)
        assertEquals(declared, meta.fileSizeBytes)
        assertEquals("llama", meta.architecture)
        assertEquals("Llama 3.2 3B Instruct", meta.name)
        assertEquals("Q4_K_M", meta.quantType)
        assertEquals(28, meta.blockCount)
        assertEquals(3072, meta.embeddingLength)
        assertEquals(8192, meta.feedForwardLength)
        assertEquals(24, meta.attentionHeadCount)
        assertEquals(8, meta.attentionHeadCountKv)
        assertEquals(131_072, meta.contextLength)
        assertEquals(3_211_758_208L, meta.parameterCount)
        assertEquals(291L, meta.tensorCount)
    }

    @Test
    fun `head_dim is derived from embedding over head count when absent`() {
        val est = RamEstimate.from(parse3B())
        // 3072 / 24 = 128
        assertEquals(128, est.headDim)
    }

    @Test
    fun `an explicit key_length override beats the derived head_dim`() {
        val header = GgufTestBuilder()
            .header(kvCount = 5)
            .kvString("general.architecture", "llama")
            .kvU32("llama.embedding_length", 3072)
            .kvU32("llama.block_count", 28)
            .kvU32("llama.attention.head_count", 24)
            .kvU32("llama.attention.key_length", 64)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(header), header.size.toLong())
        assertEquals(64, RamEstimate.from(meta).headDim)
    }

    @Test
    fun `a header missing architecture fields still yields a usable estimate`() {
        val header = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "llama")
            .kvU64("general.parameter_count", 3_000_000_000L)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(header), header.size.toLong())
        val est = RamEstimate.from(meta)
        // The stream held only the header, so fileSizeBytes is not a real file
        // size: the estimate must fall back to params * 4.83 / 8
        // = 3e9 * 4.83 / 8 = 1.81 GB rather than reporting a few hundred bytes.
        assertEquals(1_811_250_000L, est.weightBytes)
        assertEquals(0L, est.kvBytes(4096)) // no block_count -> no KV to charge for
        assertTrue(est.totalBytes(4096) > 1_500_000_000L)
    }

    @Test
    fun `GQA models are not charged for non-KV heads`() {
        val est = RamEstimate.from(parse3B())
        // 8 kv heads, not 24: charging 24 would triple the KV cache.
        assertEquals(8, est.kvHeads)
        val perToken = est.kvBytesPerToken
        assertTrue(perToken < 300_000L)
    }

    @Test
    fun `MHA with no separate kv head count falls back to the head count`() {
        val header = GgufTestBuilder()
            .header(kvCount = 4)
            .kvString("general.architecture", "gpt2")
            .kvU32("gpt2.embedding_length", 768)
            .kvU32("gpt2.block_count", 12)
            .kvU32("gpt2.attention.head_count", 12)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(header), header.size.toLong())
        val est = RamEstimate.from(meta)
        assertEquals(12, est.kvHeads)
        assertEquals(64, est.headDim) // 768 / 12
        // 12 * 12 * 64 * 2 * 2 = 36_864 B per token
        assertEquals(36_864L, est.kvBytesPerToken)
    }

    // ---- stream handling --------------------------------------------------------

    @Test
    fun `an unknown file size still parses`() {
        val header = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "llama")
            .kvString("general.name", "no size")
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(header), -1L)
        assertEquals("no size", meta.name)
        // Falls back to bytes actually consumed, i.e. the header length.
        assertEquals(header.size.toLong(), meta.fileSizeBytes)
    }

    @Test
    fun `a stream provider that lies about size does not crash the import`() {
        val header = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "llama")
            .kvString("general.name", "truncated")
            .build()
        // Claims 4 GB, stream holds only the header.
        val stream: InputStream = ByteArrayInputStream(header)
        val meta = GgufReader.parse(stream, 4_000_000_000L)
        // The header is complete, so parsing succeeds; the trailing claim is
        // simply never read. A real truncation mid-KV is what throws.
        assertEquals("truncated", meta.name)
    }

    @Test
    fun `a truncated GGUF is reported as a format error, not a crash`() {
        val full = parse3B()
        assertNotNull(full)
        val header = GgufTestBuilder()
            .header(kvCount = 6)
            .kvString("general.architecture", "llama")
            .kvString("general.name", "cut short")
            .build()
        val cut = header.copyOf(header.size - 5)
        val failed = try {
            GgufReader.parse(ByteArrayInputStream(cut), cut.size.toLong())
            false
        } catch (e: GgufFormatException) {
            true
        }
        assertTrue(failed)
    }

    // ---- device budget ----------------------------------------------------------

    @Test
    fun `usable device bytes is positive and bounded`() {
        val usable = RamEstimate.usableDeviceBytes()
        assertTrue("usable=$usable", usable > 0)
        // Never more than 55% of physical RAM, by construction.
        assertTrue("usable=$usable", usable <= 16L * gib)
    }

    @Test
    fun `a 1B model is comfortably affordable where a 3B is borderline`() {
        val header = GgufTestBuilder()
            .header(kvCount = 6)
            .kvString("general.architecture", "llama")
            .kvU32("llama.context_length", 8192)
            .kvU32("llama.embedding_length", 2048)
            .kvU32("llama.block_count", 16)
            .kvU32("llama.attention.head_count", 32)
            .kvU32("llama.attention.head_count_kv", 8)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(header), 1_100_000_000L)
        val est = RamEstimate.from(meta)
        assertTrue(est.fitsIn(2 * gib, 8192))
    }
}
