package dev.localintelligence.core.model.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hostile and degenerate input.
 *
 * Every test here corresponds to a field a user — or a file that arrived over a
 * hostile channel — can control. The two properties under test are that the parser
 * never *allocates* on a declared length and never *loops* on a declared count, and
 * that when it cannot continue it says why rather than throwing something opaque or
 * returning a number that reads as plausible.
 */
class GgufHostileInputTest {

    // ---------------------------------------------------------------- loop bombs

    @Test
    fun `a declared key-value count of 2^63 is refused before it can drive a loop`() {
        val builder = TinyModelSpec.builder().apply { declaredKeyValueCountOverride = Long.MAX_VALUE }
        val started = System.nanoTime()
        val e = runCatching { GgufParser.parse(builder.build(1024)) }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
        // A loop that actually ran would not come back in 5 seconds.
        assertTrue("count bomb took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `a declared tensor count of 2^63 is refused`() {
        val builder = TinyModelSpec.builder().apply { declaredTensorCountOverride = Long.MAX_VALUE }
        val e = runCatching { GgufParser.parse(builder.build(1024)) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
    }

    @Test
    fun `a count beyond the configured ceiling is refused even when it is not absurd`() {
        // A billion is not 2^63, but it is still not a header this parser should follow.
        val builder = TinyModelSpec.builder().apply { declaredKeyValueCountOverride = 1_000_000_000L }
        val e = runCatching { GgufParser.parse(builder.build(1024)) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
    }

    @Test
    fun `a custom limit is honoured`() {
        val builder = TinyModelSpec.builder().apply { declaredTensorCountOverride = 100L }
        val e = runCatching {
            GgufParser.parse(
                builder.build(1024),
                GgufLimits(maxTensorCount = 10L),
            )
        }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
    }

    // ---------------------------------------------------------------- allocation bombs

    @Test
    fun `a string declaring a 2^63 length is refused without allocating`() {
        val builder = TinyModelSpec.builder().apply {
            stringWithDeclaredLength("general.name", Long.MAX_VALUE)
        }
        val started = System.nanoTime()
        val e = runCatching { GgufParser.parse(builder.build(1024)) }.exceptionOrNull()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(GgufParseException.Reason.LIMIT_EXCEEDED, (e as GgufParseException).reason)
        assertTrue("string bomb took ${elapsedMs}ms", elapsedMs < 5_000)
    }

    @Test
    fun `a string longer than the key limit is refused`() {
        val builder = TinyModelSpec.builder().apply {
            stringWithDeclaredLength("general.name", 10_000)
        }
        val e = runCatching {
            GgufParser.parse(builder.build(1024), GgufLimits(maxStringLength = 1_024))
        }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.LIMIT_EXCEEDED, (e as GgufParseException).reason)
    }

    @Test
    fun `an array declaring 2^63 elements is refused`() {
        // The payload contains zero elements, so honouring the count would mean
        // "allocating" nothing but "looping" forever. Both are refused.
        val builder = SyntheticGgufBuilder().apply {
            val body = SyntheticArrayBody()
            body.elementType(GgufValueType.UINT32)
            body.count(Long.MAX_VALUE)
            rawValue("huge.array", GgufValueType.ARRAY.id.toLong(), body.bytes())
        }
        val e = runCatching { GgufParser.parse(builder.build()) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
    }

    @Test
    fun `a huge array count is refused when it would also exceed the header ceiling`() {
        val builder = SyntheticGgufBuilder().apply {
            val body = SyntheticArrayBody()
            body.elementType(GgufValueType.STRING)
            body.count(5_000_000L)
            rawValue("vocab", GgufValueType.ARRAY.id.toLong(), body.bytes())
        }
        val e = runCatching {
            GgufParser.parse(builder.build(), GgufLimits(maxArrayElements = 1_000L))
        }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
    }

    @Test
    fun `reading past the end of the file is a truncation, not a silent zero`() {
        val truncated = TinyModelSpec.builder().buildTruncated(64)
        val header = GgufParser.parse(truncated)
        assertTrue(!header.isComplete)
        assertTrue(
            "expected truncation, got ${header.warnings}",
            header.warnings.any { it is GgufWarning.KeyValueListTruncated },
        )
    }

    @Test
    fun `the header byte ceiling stops a file that would be read end to end`() {
        // A 4 MiB file of valid-looking pairs under a 64 KiB ceiling.
        val builder = SyntheticGgufBuilder()
        repeat(4_000) { builder.string("padding.key.$it", "x".repeat(512)) }
        val e = runCatching {
            GgufParser.parse(builder.build(), GgufLimits(maxHeaderBytes = 64 * 1024))
        }.exceptionOrNull()
        assertNotNull("a header over the ceiling must be refused, not silently truncated", e)
    }

    // ---------------------------------------------------------------- absurd values, gracefully

    @Test
    fun `absurd declared shapes saturate instead of wrapping negative`() {
        val builder = TinyModelSpec.builder().apply {
            tensor("impossible.weight", listOf(Long.MAX_VALUE, Long.MAX_VALUE), GgufQuantType.Q4_K)
        }
        val tensor = GgufParser.parse(builder.build(1024)).tensors.first { it.name == "impossible.weight" }
        // 2^63 * 2^63 must not wrap into a small positive count.
        assertEquals(Long.MAX_VALUE, tensor.elementCount)
        // ...and the byte count must stay positive. It is not Long.MAX_VALUE because
        // Long.MAX_VALUE Q4_K weights is 3.6e16 blocks of 144 bytes, which is ~5.2e18 --
        // an enormous number that legitimately fits. The property under test is
        // "does not wrap", not "always saturates".
        assertTrue("wrapped to ${tensor.bytes}", tensor.bytes!! > 0)
    }

    @Test
    fun `a tensor with more dimensions than ggml allows is refused`() {
        val builder = TinyModelSpec.builder().apply {
            tensor("wide.weight", List(9) { 2L }, GgufQuantType.Q4_K)
        }
        val e = runCatching { GgufParser.parse(builder.build(1024)) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
    }

    @Test
    fun `a negative context length is treated as absent rather than as a size`() {
        val builder = TinyModelSpec.builder(withContext = false).apply {
            i32("qwen3.context_length", -1L)
        }
        val header = GgufParser.parse(builder.build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt()))
        assertNull(header.metadata.contextLength)
        assertTrue(
            "a negative size must be disclosed, not silently dropped: ${header.warnings}",
            header.metadata.implausibleKeys.any { it.first == "qwen3.context_length" },
        )
    }

    @Test
    fun `a huge but non-negative declared value survives parsing and is flagged as implausible`() {
        val builder = TinyModelSpec.builder(withContext = false).apply {
            u64("qwen3.block_count", 9_000_000_000_000_000_000L)
        }
        val header = GgufParser.parse(builder.build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt()))
        // Parsed (the value is a legal uint64) but rejected as a size, and disclosed.
        assertTrue(
            "expected an implausible-value disclosure, got ${header.metadata.implausibleKeys}",
            header.metadata.implausibleKeys.any { it.first == "qwen3.block_count" },
        )
        assertNull(header.metadata.blockCount)
    }

    @Test
    fun `an unknown value type keeps every key decoded before it and does not crash`() {
        val builder = SyntheticGgufBuilder().apply {
            string("general.architecture", "qwen3")
            u32("qwen3.context_length", 4096)
            // Tag 4242 with a four-byte payload: a type a future GGUF revision might add.
            rawValue("future.thing", 4_242L, byteArrayOf(1, 2, 3, 4))
            u32("qwen3.block_count", 28)
        }
        val header = GgufParser.parse(builder.build())

        val warning = header.warnings.filterIsInstance<GgufWarning.UnknownValueType>().single()
        assertEquals("future.thing", warning.key)
        assertEquals(4_242L, warning.typeId)
        // Everything before the unknown tag is intact...
        assertEquals("qwen3", header.metadata.architecture)
        assertEquals(4_096L, header.metadata.contextLength)
        // ...and the key after it is honestly absent rather than decoded from a guess.
        assertNull(header.metadata.blockCount)
    }

    @Test
    fun `strict mode refuses an unknown value type instead of returning a partial header`() {
        val builder = SyntheticGgufBuilder().apply {
            u32("qwen3.context_length", 4096)
            rawValue("future.thing", 4_242L, byteArrayOf(1, 2, 3, 4))
        }
        val e = runCatching {
            GgufParser.parse(builder.build(), truncation = GgufParser.TruncationPolicy.STRICT)
        }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.UNSUPPORTED_STRUCTURE, (e as GgufParseException).reason)
    }

    @Test
    fun `an unknown tensor type is kept verbatim and the weight sum refuses to guess`() {
        val builder = TinyModelSpec.builder().apply {
            tensor("mystery.weight", listOf(256L, 128L), typeId = 9_999L, offset = 0L)
        }
        val header = GgufParser.parse(builder.build(1024))
        val mystery = header.tensors.first { it.name == "mystery.weight" }
        assertNull("an unknown type has no known size", mystery.quantType)
        assertNull(mystery.bytes)
        // A partial sum would understate the model, which is the dangerous direction.
        assertNull(header.declaredWeightBytes)
    }

    // ---------------------------------------------------------------- block arithmetic

    @Test
    fun `block arithmetic rounds up and never overflows`() {
        assertEquals(0L, GgufQuantType.Q4_K.bytesFor(0))
        assertEquals(144L, GgufQuantType.Q4_K.bytesFor(1))
        assertEquals(144L, GgufQuantType.Q4_K.bytesFor(256))
        assertEquals(288L, GgufQuantType.Q4_K.bytesFor(257))
        // Q4_K at Long.MAX_VALUE weights is ~5.2e18 bytes: huge, but a real number, so it
        // must not saturate. F32 at the same element count is 3.7e19 and does saturate.
        assertTrue(GgufQuantType.Q4_K.bytesFor(Long.MAX_VALUE) > 0)
        assertEquals(Long.MAX_VALUE, GgufQuantType.F32.bytesFor(Long.MAX_VALUE))
        assertEquals(0L, GgufQuantType.Q4_K.bytesFor(-5))
    }

    @Test
    fun `average bits per weight match the known values for the common quant types`() {
        assertEquals(4.5, GgufQuantType.Q4_K.averageBitsPerWeight, 1e-9)
        assertEquals(8.5, GgufQuantType.Q8_0.averageBitsPerWeight, 1e-9)
        assertEquals(6.5625, GgufQuantType.Q6_K.averageBitsPerWeight, 1e-9)
        assertEquals(32.0, GgufQuantType.F32.averageBitsPerWeight, 1e-9)
        assertEquals(16.0, GgufQuantType.F16.averageBitsPerWeight, 1e-9)
    }

    @Test
    fun `value type lookup rejects an out of range tag without wrapping`() {
        assertNull(GgufValueType.fromId(4_294_967_296L))
        assertNull(GgufValueType.fromId(99))
        assertNotNull(GgufValueType.fromId(10))
        assertNull(GgufQuantType.fromTensorType(9_999L))
        assertNull(GgufFileType.fromId(Long.MAX_VALUE))
    }

    /** Minimal hand-rolled array payload, so the hostile counts need no string bodies. */
    private class SyntheticArrayBody {
        private val out = java.io.ByteArrayOutputStream()
        private fun u32(v: Long) {
            for (i in 0 until 4) out.write(((v ushr (i * 8)) and 0xFF).toInt())
        }

        private fun u64(v: Long) {
            for (i in 0 until 8) out.write(((v ushr (i * 8)) and 0xFF).toInt())
        }

        fun elementType(t: GgufValueType) = u32(t.id.toLong())
        fun count(n: Long) = u64(n)
        fun bytes(): ByteArray = out.toByteArray()
    }
}
