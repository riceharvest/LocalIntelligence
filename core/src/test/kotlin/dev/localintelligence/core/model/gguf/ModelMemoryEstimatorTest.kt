package dev.localintelligence.core.model.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory estimate, asserted against numbers worked out by hand.
 *
 * Every expected value in this file is a literal, not a re-derivation through the
 * production formula. A test that recomputes the thing it is testing with the same
 * helper the code uses passes when the code is consistently wrong, which for a memory
 * model means confidently telling a user the wrong thing about their phone.
 */
class ModelMemoryEstimatorTest {

    private val estimator = ModelMemoryEstimator()

    private fun tinyHeader(
        quant: GgufQuantType = GgufQuantType.Q4_K,
        fileType: Long? = TinyModelSpec.Q4_K_FILE_TYPE,
        withContext: Boolean = true,
    ): GgufHeader = GgufParser.parse(
        TinyModelSpec.builder(quant = quant, fileType = fileType, withContext = withContext)
            .build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt()),
    )

    // ---------------------------------------------------------------- the formula

    @Test
    fun `weights come from the tensor table and are exact`() {
        val e = estimator.estimate(tinyHeader())
        assertEquals(TinyModelSpec.DECLARED_WEIGHT_BYTES, e.weightsBytes)
        assertEquals(EstimateBasis.TENSOR_TABLE, e.basis)
        assertEquals(TinyModelSpec.PARAMETER_COUNT, e.parameterCount)
    }

    @Test
    fun `kv cache is two tensors times layers times kv heads times head dim times context times bytes`() {
        val e = estimator.estimate(tinyHeader())
        // 2 (K,V) * 4 layers * 2 kv heads * 32 head dim * 512 ctx * 2.0 bytes
        assertEquals(TinyModelSpec.KV_BYTES_AT_512_F16, e.kvCacheBytes)
        assertEquals(2.0, e.bytesPerKvElement, 1e-9)
        assertEquals(4L, e.layers)
        assertEquals(2L, e.kvHeads)
        assertEquals(32L, e.headDimension)
        assertTrue("head dim came from a division here", e.headDimensionDerived)
    }

    @Test
    fun `overhead is the larger of the flat floor and the proportional allowance`() {
        val e = estimator.estimate(tinyHeader())
        // 39432 * 0.02 = 788 bytes, far below the 64 MiB floor.
        assertEquals(64L * 1024 * 1024, e.overheadBytes)
    }

    @Test
    fun `total is the sum of the three parts`() {
        val e = estimator.estimate(tinyHeader())
        assertEquals(e.weightsBytes + e.kvCacheBytes + e.overheadBytes, e.totalBytes)
    }

    @Test
    fun `the estimate is always labelled as an estimate`() {
        assertTrue(estimator.estimate(tinyHeader()).isEstimate)
    }

    // ---------------------------------------------------------------- inputs the UI shows

    @Test
    fun `every input carries the key it came from so the UI can show why`() {
        val e = estimator.estimate(tinyHeader())
        val byName = e.inputs.associate { it.name to it.source }
        assertEquals("<arch>.context_length", byName["Context length"])
        assertEquals("<arch>.block_count", byName["Layers"])
        assertEquals("<arch>.attention.head_count_kv", byName["KV heads"])
        assertEquals(
            "<arch>.embedding_length / <arch>.attention.head_count",
            byName["Head dimension"],
        )
        assertTrue(byName.containsKey("Bits per weight"))
        assertTrue(byName.containsKey("Weights"))
    }

    @Test
    fun `a derived KV head count is labelled MHA rather than presented as declared`() {
        // No head_count_kv key at all: this is multi-head attention, and the fallback
        // must say so rather than report a KV head count nobody declared.
        val file = SyntheticGgufBuilder().apply {
            string("general.architecture", "qwen3")
            u32("qwen3.context_length", 2048)
            u32("qwen3.block_count", 4)
            u32("qwen3.embedding_length", 128)
            u32("qwen3.attention.head_count", 4)
        }.build()
        val e = estimator.estimate(GgufParser.parse(file))
        assertEquals(4L, e.kvHeads)
        assertEquals("<arch>.attention.head_count (MHA)", e.inputs.first { it.name == "KV heads" }.source)
    }

    // ---------------------------------------------------------------- confidence

    @Test
    fun `a complete self-consistent header grades as measured`() {
        val e = estimator.estimate(tinyHeader())
        assertEquals(MemoryEstimate.Confidence.MEASURED, e.confidence)
    }

    @Test
    fun `an assumed context length downgrades confidence to guess`() {
        val e = estimator.estimate(tinyHeader(withContext = false))
        assertEquals(MemoryEstimate.Confidence.GUESS, e.confidence)
        assertEquals(2_048L, e.contextLength)
        val warning = e.warnings.filterIsInstance<GgufWarning.ContextLengthMissing>().single()
        assertEquals(2_048L, warning.assumed)
    }

    @Test
    fun `a context length past the ceiling is clamped and disclosed`() {
        val hostile = SyntheticGgufBuilder().apply {
            string("general.architecture", "qwen3")
            u64("qwen3.context_length", 4_000_000_000L)
            u32("qwen3.block_count", 4)
            u32("qwen3.embedding_length", 128)
            u32("qwen3.attention.head_count", 4)
        }.build()
        val e = estimator.estimate(GgufParser.parse(hostile))
        assertEquals(8L * 1024 * 1024, e.contextLength)
        assertTrue(e.warnings.any { it is GgufWarning.ContextLengthClamped })
        assertEquals(MemoryEstimate.Confidence.GUESS, e.confidence)
    }

    @Test
    fun `a header with no geometry cannot size a KV cache and says so`() {
        val bare = SyntheticGgufBuilder().apply {
            string("general.architecture", "qwen3")
            u32("qwen3.context_length", 2048)
        }.build()
        val e = estimator.estimate(GgufParser.parse(bare))
        assertEquals(0L, e.kvCacheBytes)
        assertTrue(
            "missing geometry must be visible, not folded into a zero: ${e.inputs}",
            e.inputs.any { it.name == "KV cache" && it.value == "not computable" },
        )
        assertEquals(MemoryEstimate.Confidence.GUESS, e.confidence)
    }

    @Test
    fun `a header that declares tensors but exposes none falls back to the file size`() {
        val pad = 64 * 1024
        val bytes = SyntheticGgufBuilder().apply {
            string("general.architecture", "qwen3")
            declaredTensorCountOverride = 291L
        }.build(pad)
        val e = estimator.estimate(GgufParser.parse(bytes))
        // The tensor table is unusable and nothing else declares a size, so the file on
        // disk is the only remaining evidence -- and it is a floor, never an over-estimate.
        // It is the whole file, header included, which is why it is a little over the pad.
        assertEquals(EstimateBasis.FILE_SIZE, e.basis)
        assertEquals(bytes.size.toLong(), e.weightsBytes)
        assertTrue(e.weightsBytes > pad)
        assertTrue(e.fileSizeIsTheOnlyEvidence())
    }

    @Test
    fun `a genuinely empty model declares no weights rather than guessing some`() {
        val empty = GgufParser.parse(SyntheticGgufBuilder().build())
        assertEquals(0L, empty.declaredWeightBytes)
        assertEquals(0L, empty.tensorTableParameterCount)
        assertEquals(EstimateBasis.TENSOR_TABLE, estimator.estimate(empty).basis)
    }

    // ---------------------------------------------------------------- Q4 vs Q8

    @Test
    fun `re-pricing the same model at Q4_K is exactly parameters times 4_5 over 8`() {
        val e = estimator.estimate(tinyHeader(), quantTypeOverride = GgufQuantType.Q4_K)
        assertEquals(EstimateBasis.PARAMETER_COUNT, e.basis)
        // 58368 * 4.5 / 8 = 32832 exactly.
        assertEquals(32_832L, e.weightsBytes)
    }

    @Test
    fun `Q8 costs almost exactly twice Q4 on the same shapes`() {
        val header = tinyHeader()
        val q4 = estimator.estimate(header, quantTypeOverride = GgufQuantType.Q4_K)
        val q8 = estimator.estimate(header, quantTypeOverride = GgufQuantType.Q8_0)
        // 58368 * 8.5 / 8 = 62016.
        assertEquals(62_016L, q8.weightsBytes)
        assertTrue(q8.weightsBytes > q4.weightsBytes)
    }

    @Test
    fun `alternatives come back ordered by cost so the UI can list them`() {
        val all = estimator.estimateAlternatives(tinyHeader())
        val weights = all.map { it.weightsBytes }
        assertEquals(weights.sorted(), weights)
        assertEquals(
            listOf(GgufQuantType.Q4_K, GgufQuantType.Q6_K, GgufQuantType.Q8_0),
            all.map { it.quantType },
        )
    }

    @Test
    fun `an override at the file's own type still costs less than the measured tensor sum`() {
        // The tensor table is ground truth, so the override path is strictly a
        // modelling exercise and must be presented as a different basis.
        val e = estimator.estimate(tinyHeader(), quantTypeOverride = GgufQuantType.Q4_K)
        assertEquals(EstimateBasis.PARAMETER_COUNT, e.basis)
        assertEquals(32_832L, e.weightsBytes)
        assertTrue(e.weightsBytes < TinyModelSpec.DECLARED_WEIGHT_BYTES)
    }

    // ---------------------------------------------------------------- KV cache types

    @Test
    fun `q8 KV cache is a little over half of f16 and q4 is a little over a quarter`() {
        val header = tinyHeader()
        val f16 = estimator.estimate(header, kvCacheType = KvCacheType.F16)
        val q8 = estimator.estimate(header, kvCacheType = KvCacheType.Q8_0)
        val q4 = estimator.estimate(header, kvCacheType = KvCacheType.Q4_0)

        // 524288 * (34/32) / 2 = 278528
        assertEquals(278_528L, q8.kvCacheBytes)
        // 524288 * (18/32) / 2 = 147456
        assertEquals(147_456L, q4.kvCacheBytes)
        assertTrue(q4.kvCacheBytes < q8.kvCacheBytes)
        assertTrue(q8.kvCacheBytes < f16.kvCacheBytes)
    }

    @Test
    fun `a longer context scales the KV cache exactly linearly`() {
        val header = tinyHeader()
        val at512 = estimator.estimate(header).kvCacheBytes
        val at1024 = estimator.estimate(header, contextLengthOverride = 1_024).kvCacheBytes
        assertEquals(at512 * 2, at1024)
    }

    @Test
    fun `a caller override of context length is labelled as such`() {
        val e = estimator.estimate(tinyHeader(), contextLengthOverride = 8_192)
        assertEquals(8_192L, e.contextLength)
        assertEquals("caller override", e.inputs.first { it.name == "Context length" }.source)
    }

    // ---------------------------------------------------------------- fit checks

    @Test
    fun `fitsWithin compares the total against a budget`() {
        val e = estimator.estimate(tinyHeader())
        assertTrue(e.fitsWithin(128L * 1024 * 1024))
        assertFalse(e.fitsWithin(1024L))
    }

    @Test
    fun `a hostile geometry cannot make a model look like it fits`() {
        val hostile = SyntheticGgufBuilder().apply {
            string("general.architecture", "qwen3")
            u64("qwen3.context_length", 4_000_000_000L)
            u32("qwen3.block_count", 1_000_000)
            u32("qwen3.embedding_length", 1_000_000)
            u32("qwen3.attention.head_count", 1)
        }.build()
        val e = estimator.estimate(GgufParser.parse(hostile))
        // Saturating, not wrapping, and therefore not "small enough to load".
        assertTrue("hostile kv cache was ${e.kvCacheBytes}", e.kvCacheBytes > Int.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, e.kvCacheBytes)
        assertFalse(e.fitsWithin(8L * 1024 * 1024 * 1024))
    }

    // ---------------------------------------------------------------- formatting

    @Test
    fun `byte formatting is binary and readable`() {
        assertEquals("512 B", MemoryEstimate.formatBytes(512))
        assertEquals("1.00 KiB", MemoryEstimate.formatBytes(1024))
        assertEquals("1.00 MiB", MemoryEstimate.formatBytes(1024L * 1024))
        assertEquals("1.00 GiB", MemoryEstimate.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `describe states the components and the confidence`() {
        val text = estimator.estimate(tinyHeader()).describe()
        assertTrue(text, text.contains("estimated"))
        assertTrue(text, text.contains("measured"))
        assertTrue(text, text.contains("Q4_K"))
        assertTrue(text, text.contains("KV"))
    }

    @Test
    fun `null bits per weight is possible and does not crash formatting`() {
        val bare = SyntheticGgufBuilder().build()
        val e = estimator.estimate(GgufParser.parse(bare))
        assertNull("no quant type means no bits per weight", e.bitsPerWeight)
        assertTrue(e.describe().contains("unquantized"))
    }
}

/** True when the weight figure came from the file size because nothing better existed. */
private fun MemoryEstimate.fileSizeIsTheOnlyEvidence(): Boolean =
    inputs.any { it.name == "Weights" && it.source.startsWith("file size") }
