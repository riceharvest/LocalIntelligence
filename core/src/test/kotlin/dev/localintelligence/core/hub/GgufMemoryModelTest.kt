package dev.localintelligence.core.hub

import dev.localintelligence.core.model.gguf.EstimateBasis
import dev.localintelligence.core.model.gguf.GgufHeader
import dev.localintelligence.core.model.gguf.GgufParser
import dev.localintelligence.core.model.gguf.GgufQuantType
import dev.localintelligence.core.model.gguf.KvCacheType
import dev.localintelligence.core.model.gguf.MemoryEstimate
import dev.localintelligence.core.model.gguf.TinyModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam into `core.model.gguf`, proved to compile and to agree.
 *
 * These tests exist because a seam nobody has ever called is a guess. The
 * adapter in [GgufMemoryModel] is driven against a real [GgufHeader] — built
 * with the gguf workstream's own `TinyModelSpec` fixture rather than a
 * hand-rolled one — so the moment the estimator's signature changes this fails
 * to compile instead of failing silently at runtime on a user's phone.
 *
 * The tiny fixture is 39 KB, not 1.9 GB. That is fine and is the point: the
 * adapter is being tested for *wiring*, not for arithmetic, and the arithmetic
 * is [dev.localintelligence.core.model.gguf.ModelMemoryEstimatorTest]'s job
 * against hand-worked literals.
 */
class GgufMemoryModelTest {

    private val budget = FakeBudget(availableRam = 8_000_000_000L, freeDisk = 64L * 1024 * 1024 * 1024)

    private fun tinyHeader(): GgufHeader = GgufParser.parse(
        TinyModelSpec.builder(quant = GgufQuantType.Q4_K, fileType = TinyModelSpec.Q4_K_FILE_TYPE)
            .build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt()),
    )

    @Test
    fun `the adapter produces a MemoryModel the hub can call`() {
        val model = GgufMemoryModel.from(tinyHeader())
        val bytes = model.estimate(
            fileBytes = TinyModelSpec.DECLARED_WEIGHT_BYTES.toLong(),
            quant = GgufQuant.Q4_K_M,
            contextLength = 512,
            parameterCount = TinyModelSpec.PARAMETER_COUNT,
        )
        assertTrue("a real estimate must be positive, got $bytes", bytes > 0)
    }

    @Test
    fun `the adapter routes the context length through to the real estimator`() {
        val model = GgufMemoryModel.from(tinyHeader())
        val file = TinyModelSpec.DECLARED_WEIGHT_BYTES.toLong()
        val short = model.estimate(file, GgufQuant.Q4_K_M, 512, TinyModelSpec.PARAMETER_COUNT)
        val long = model.estimate(file, GgufQuant.Q4_K_M, 8_192, TinyModelSpec.PARAMETER_COUNT)
        // KV is strictly linear in context. On this 39 KB fixture the 64 MiB
        // runtime floor dominates the TOTAL, so the meaningful assertion is the
        // DELTA, not the ratio: 16x the context is 15x more KV, exactly
        // 15 * TinyModelSpec.KV_BYTES_AT_512_F16.
        val kvDelta = TinyModelSpec.KV_BYTES_AT_512_F16 * 15
        assertEquals(
            "the context length must reach the real estimator's KV term",
            kvDelta,
            long - short,
        )
    }

    @Test
    fun `the conservative and exact models are the same order of magnitude`() {
        // WHY this matters: if the two memory models disagree by an order of
        // magnitude, a user is either refused a model that works or sold one
        // that does not. This is the check that keeps them honest with each
        // other, and it is the reason both err toward the conservative side.
        val file = TinyModelSpec.DECLARED_WEIGHT_BYTES.toLong()
        val exact = GgufMemoryModel.from(tinyHeader())
            .estimate(file, GgufQuant.Q4_K_M, 512, TinyModelSpec.PARAMETER_COUNT)
        val conservative = PreDownloadMemoryModel.estimate(
            file, GgufQuant.Q4_K_M, 512, TinyModelSpec.PARAMETER_COUNT,
        )
        // The exact estimate carries a 64 MiB runtime floor, which dwarfs this
        // fixture's 39 KB of weights; the conservative one does too. So the
        // comparison is meaningful only in ratio, and the ratio is asserted
        // loosely on purpose: it is a "these two are talking about the same
        // thing" check, not a numeric one.
        val ratio = maxOf(exact, conservative).toDouble() / minOf(exact, conservative)
        assertTrue("exact=$exact conservative=$conservative ratio=$ratio", ratio in 0.1..10.0)
    }

    @Test
    fun `refine applies the same decision factor as the pre-flight gate`() {
        // A REAL estimate, not a hand-built one: `MemoryEstimate` carries its
        // own inputs and warnings, and hand-building one here would test my
        // constructor call rather than the verdict.
        val estimate = dev.localintelligence.core.model.gguf.ModelMemoryEstimator()
            .estimate(tinyHeader(), contextLengthOverride = 4_096)
        val refined = GgufMemoryModel.refine(estimate, budget)
        assertEquals(
            "the refined verdict must apply the same 1.15 factor as the pre-flight",
            (estimate.totalBytes * FitGate.DECISION_FACTOR).toLong(),
            refined.totalBytes,
        )
    }

    @Test
    fun `refine refuses a model the exact estimate says will not fit`() {
        val estimate = dev.localintelligence.core.model.gguf.ModelMemoryEstimator()
            .estimate(tinyHeader(), contextLengthOverride = 4_096)
        // A budget below even the 64 MiB runtime floor, so the refusal is
        // unambiguous rather than a close call.
        val tiny = FakeBudget(availableRam = 1_000L, freeDisk = 64L * 1024 * 1024 * 1024)
        val refined = GgufMemoryModel.refine(estimate, tiny)
        assertFalse(refined.fits)
        assertTrue("the message must name a lever the user has", refined.explanation.contains("quantization"))
        assertTrue("and the other one", refined.explanation.contains("context"))
    }
}
