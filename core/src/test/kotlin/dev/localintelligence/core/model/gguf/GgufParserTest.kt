package dev.localintelligence.core.model.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing of well-formed and mildly-broken headers.
 *
 * Grouped by what the file is rather than by which method is called, because the test
 * matrix is defined in terms of files: valid Q4, valid Q8, header-only truncation,
 * garbage magic, wrong endianness, and a file whose declared size exceeds its actual
 * size all appear here.
 */
class GgufParserTest {

    // ---------------------------------------------------------------- valid files

    @Test
    fun `valid Q4 file yields every metadata field the app needs`() {
        val header = GgufParser.parse(TinyModelSpec.validFile(quant = GgufQuantType.Q4_K))
        val m = header.metadata

        assertEquals(3, header.version)
        assertTrue("valid file must parse complete", header.isComplete)
        assertTrue("valid file must raise no warnings: ${header.warnings}", header.warnings.isEmpty())

        assertEquals("qwen3", m.architecture)
        assertEquals(TinyModelSpec.NAME, m.name)
        assertEquals(TinyModelSpec.LAYERS, m.blockCount)
        assertEquals(TinyModelSpec.CONTEXT, m.contextLength)
        assertEquals(TinyModelSpec.EMBEDDING, m.embeddingLength)
        assertEquals(TinyModelSpec.FEED_FORWARD, m.feedForwardLength)
        assertEquals(TinyModelSpec.HEAD_COUNT, m.headCount)
        assertEquals(TinyModelSpec.HEAD_COUNT_KV, m.headCountKv)
        assertEquals(TinyModelSpec.VOCAB.toLong(), m.vocabularySize)
        assertEquals(GgufFileType.MOSTLY_Q4_K_M, m.fileType)
    }

    @Test
    fun `valid Q8 file parses and reports Q8 rather than Q4`() {
        val header = GgufParser.parse(
            TinyModelSpec.validFile(quant = GgufQuantType.Q8_0, fileType = TinyModelSpec.Q8_0_FILE_TYPE),
        )
        assertEquals(GgufFileType.MOSTLY_Q8_0, header.metadata.fileType)
        assertEquals(GgufQuantType.Q8_0, header.tensors.first().quantType)
        // Q8_0 is 8.5 bits per weight against Q4_K's 4.5, so the same shapes weigh ~1.9x.
        assertTrue(header.declaredWeightBytes!! > TinyModelSpec.DECLARED_WEIGHT_BYTES)
    }

    @Test
    fun `tensor table gives the exact weight byte count rather than a bits-per-weight guess`() {
        val header = GgufParser.parse(TinyModelSpec.validFile())
        // tok_embd: 32768 weights at Q4_K = 128 blocks of 144. output: 25600 at Q6_K =
        // 100 blocks of 210. The sum is the file's weight section, not an estimate.
        assertEquals(TinyModelSpec.DECLARED_WEIGHT_BYTES, header.declaredWeightBytes)
        assertEquals(TinyModelSpec.PARAMETER_COUNT, header.tensorTableParameterCount)
    }

    @Test
    fun `head dimension falls back to embedding length over head count and is marked derived`() {
        val header = GgufParser.parse(TinyModelSpec.validFile())
        val resolved = header.metadata.resolveHeadDimension()
        assertNotNull(resolved)
        assertEquals(TinyModelSpec.HEAD_DIM, resolved!!.value)
        assertEquals(
            GgufMetadata.HeadDimensionSource.EMBEDDING_LENGTH_OVER_HEAD_COUNT,
            resolved.source,
        )
    }

    @Test
    fun `a declared key_length outranks rope and embedding when resolving head dimension`() {
        val file = TinyModelSpec.builder().apply {
            u32("qwen3.attention.key_length", 96)
            u32("qwen3.rope.dimension_count", 64)
        }.build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt())
        val resolved = GgufParser.parse(file).metadata.resolveHeadDimension()!!
        assertEquals(96L, resolved.value)
        assertEquals(GgufMetadata.HeadDimensionSource.KEY_LENGTH, resolved.source)
    }

    @Test
    fun `rope dimension count outranks the embedding division but loses to key_length`() {
        val file = TinyModelSpec.builder().apply { u32("qwen3.rope.dimension_count", 64) }
            .build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt())
        val resolved = GgufParser.parse(file).metadata.resolveHeadDimension()!!
        assertEquals(64L, resolved.value)
        assertEquals(GgufMetadata.HeadDimensionSource.ROPE_DIMENSION_COUNT, resolved.source)
    }

    @Test
    fun `MoE expert metadata is extracted`() {
        val m = GgufParser.parse(TinyModelSpec.moeFile()).metadata
        assertEquals(8L, m.expertCount)
        assertEquals(2L, m.expertUsedCount)
        assertEquals(64L, m.expertFeedForwardLength)
        assertTrue("expert keys must stay reachable", m.expertKeys.containsKey("qwen3.expert_count"))
    }

    @Test
    fun `a 256k-element vocabulary is summarised not materialised`() {
        val file = TinyModelSpec.builder(vocab = 256_000)
            .build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt())
        val tokens = GgufParser.parse(file).metadata.fields["tokenizer.ggml.tokens"] as GgufValue.Array
        assertEquals(256_000L, tokens.count)
        assertEquals(GgufValueType.STRING, tokens.elementType)
        // The whole point of summarising: 256k declared elements, a handful retained.
        assertTrue("preview must be capped, was ${tokens.preview.size}", tokens.preview.size <= 8)
        assertEquals("tok0", tokens.preview.first().asString())
    }

    @Test
    fun `every scalar value type round-trips`() {
        val file = SyntheticGgufBuilder().apply {
            u8("a.u8", 200)
            u16("a.u16", 60_000)
            u32("a.u32", 4_000_000_000L)
            u64("a.u64", 9_000_000_000L)
            i32("a.i32", -42L)
            f32("a.f32", 1.5f)
            f64("a.f64", -2.25)
            bool("a.bool", true)
            string("a.string", "hello")
            u32Array("a.array", listOf(1, 2, 3))
        }.build()
        val f = GgufParser.parse(file).fields
        assertEquals(200L, f["a.u8"]?.asLong())
        assertEquals(60_000L, f["a.u16"]?.asLong())
        assertEquals(4_000_000_000L, f["a.u32"]?.asLong())
        assertEquals(9_000_000_000L, f["a.u64"]?.asLong())
        assertEquals(-42L, (f["a.i32"] as GgufValue.Signed).bits)
        assertEquals(1.5f, (f["a.f32"] as GgufValue.Float32).value, 0f)
        assertEquals(-2.25, (f["a.f64"] as GgufValue.Float64).value, 0.0)
        assertEquals(true, (f["a.bool"] as GgufValue.Bool).value)
        assertEquals("hello", f["a.string"]?.asString())
        assertEquals(3L, (f["a.array"] as GgufValue.Array).count)
    }

    // ---------------------------------------------------------------- quant detection

    @Test
    fun `F32 Q4 and Q6_K tensors are all distinguished by their declared type`() {
        for ((quant, expectedBytesForTokEmb) in listOf(
            GgufQuantType.F32 to 32_768L * 4L,
            GgufQuantType.F16 to 32_768L * 2L,
            GgufQuantType.Q4_K to 18_432L,
            GgufQuantType.Q6_K to 26_880L,
            GgufQuantType.Q8_0 to 32_768L + 32_768L / 32L * 2L,
        )) {
            val header = GgufParser.parse(TinyModelSpec.validFile(quant = quant))
            assertEquals(quant.label, quant, header.tensors.first().quantType)
            assertEquals(
                "$quant tok_embd size",
                expectedBytesForTokEmb,
                header.tensors.first().bytes,
            )
        }
    }

    @Test
    fun `the dominant quant type is the one covering the most weights not the first one`() {
        val header = GgufParser.parse(TinyModelSpec.validFile(quant = GgufQuantType.Q4_K))
        // output.weight is Q6_K but covers fewer weights, so Q4_K is the honest label.
        assertEquals(GgufQuantType.Q4_K, header.dominantQuantType)
    }

    @Test
    fun `a file_type that contradicts the tensor table is reported rather than believed`() {
        val file = TinyModelSpec.builder(fileType = TinyModelSpec.F32_FILE_TYPE)
            .build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt())
        val header = GgufParser.parse(file)
        assertTrue(
            "expected a conflict warning, got ${header.warnings}",
            header.warnings.any { it is GgufWarning.FileTypeDisagreesWithTensors },
        )
        // The table still wins for sizing.
        assertEquals(GgufQuantType.Q4_K, header.dominantQuantType)
    }

    @Test
    fun `a Q4_K_M style mix is not reported as a conflict`() {
        // Q4_K and Q6_K differ by more than a bit, which is exactly why the tolerance
        // exists; but a file declaring Q4_K while being mostly Q4_K must stay quiet.
        val header = GgufParser.parse(TinyModelSpec.validFile(fileType = TinyModelSpec.Q4_K_FILE_TYPE))
        assertTrue(
            "Q4_K/Q6_K mix must not warn: ${header.warnings}",
            header.warnings.none { it is GgufWarning.FileTypeDisagreesWithTensors },
        )
    }

    // ---------------------------------------------------------------- truncated and partial

    @Test
    fun `a header-only truncated file still yields metadata and is marked incomplete`() {
        val full = TinyModelSpec.validFile()
        val header = GgufParser.parse(full.copyOf(200))

        assertFalse("a truncated header must not claim to be complete", header.isComplete)
        assertTrue(
            "expected a truncation warning, got ${header.warnings}",
            header.warnings.any { it is GgufWarning.KeyValueListTruncated },
        )
        // Everything decoded before the cut is still trustworthy and still useful.
        assertEquals("qwen3", header.metadata.architecture)
    }

    @Test
    fun `strict mode refuses a truncated header that partial mode accepts`() {
        val truncated = TinyModelSpec.validFile().copyOf(200)
        val relaxed = GgufParser.parse(truncated, truncation = GgufParser.TruncationPolicy.PARTIAL)
        assertFalse(relaxed.isComplete)

        val strict = runCatching {
            GgufParser.parse(truncated, truncation = GgufParser.TruncationPolicy.STRICT)
        }
        assertTrue("strict mode must reject a truncated header", strict.isFailure)
        assertEquals(
            GgufParseException.Reason.TRUNCATED,
            (strict.exceptionOrNull() as GgufParseException).reason,
        )
    }

    @Test
    fun `a file whose declared weights exceed its actual size is flagged`() {
        // Same header, zero weight payload: exactly a 12% downloaded 4 GiB model.
        val header = GgufParser.parse(TinyModelSpec.builder().build(weightDataBytes = 0))

        assertTrue(header.declaresMoreDataThanFileHas())
        val warning = header.warnings.filterIsInstance<GgufWarning.DeclaredDataExceedsFile>().single()
        assertTrue(
            "declared end ${warning.declaredEndBytes} should exceed file ${warning.fileBytes}",
            warning.declaredEndBytes > warning.fileBytes,
        )
        // The weight figure is still correct — it is the file that is short.
        assertEquals(TinyModelSpec.DECLARED_WEIGHT_BYTES, header.declaredWeightBytes)
    }

    @Test
    fun `a self-consistent file does not raise a size warning`() {
        val header = GgufParser.parse(TinyModelSpec.validFile())
        assertFalse(header.declaresMoreDataThanFileHas())
        assertTrue(
            "unexpected: ${header.warnings}",
            header.warnings.none { it is GgufWarning.DeclaredDataExceedsFile },
        )
    }

    // ---------------------------------------------------------------- not a GGUF file

    @Test
    fun `an empty file is rejected as not-a-GGUF rather than as truncated`() {
        val e = runCatching { GgufParser.parse(ByteArray(0)) }.exceptionOrNull()
        assertTrue(e is GgufParseException)
        assertEquals(GgufParseException.Reason.NOT_A_GGUF_FILE, (e as GgufParseException).reason)
    }

    @Test
    fun `a file shorter than the magic is rejected`() {
        val e = runCatching { GgufParser.parse(byteArrayOf(0x47, 0x47, 0x55)) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.NOT_A_GGUF_FILE, (e as GgufParseException).reason)
    }

    @Test
    fun `garbage with the right length is rejected on magic, not parsed`() {
        val garbage = ByteArray(4096) { (it * 31 % 251).toByte() }
        val e = runCatching { GgufParser.parse(garbage) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.NOT_A_GGUF_FILE, (e as GgufParseException).reason)
        assertEquals("Not a GGUF model file", e.displayText)
    }

    @Test
    fun `a byte-swapped magic is rejected`() {
        val file = TinyModelSpec.validFile().copyOf()
        // "FUGG" instead of "GGUF" — what a big-endian writer's magic reversal looks like.
        file[0] = 'F'.code.toByte()
        val e = runCatching { GgufParser.parse(file) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.NOT_A_GGUF_FILE, (e as GgufParseException).reason)
    }

    @Test
    fun `a big-endian file is rejected as an unsupported version, not as garbage`() {
        // The magic is still ASCII, so the only evidence of byte order is the version
        // field. Reporting "unsupported version" is the honest and actionable answer.
        val file = SyntheticGgufBuilder(bigEndian = true).apply {
            string("general.architecture", "qwen3")
        }.build()
        val e = runCatching { GgufParser.parse(file) }.exceptionOrNull() as GgufParseException
        assertEquals(GgufParseException.Reason.UNSUPPORTED_VERSION, e.reason)
        assertTrue("version was ${e.message}", e.message!!.contains("50331648"))
    }

    @Test
    fun `an unknown version number is rejected`() {
        val file = TinyModelSpec.validFile()
        file[4] = 99 // version low byte
        val e = runCatching { GgufParser.parse(file) }.exceptionOrNull() as GgufParseException
        assertEquals(GgufParseException.Reason.UNSUPPORTED_VERSION, e.reason)
    }

    // ---------------------------------------------------------------- degraded metadata

    @Test
    fun `architecture is inferred from key prefixes when the general key is absent`() {
        val file = SyntheticGgufBuilder().apply {
            u32("qwen3.context_length", 2048)
            u32("qwen3.block_count", 28)
            u32("qwen3.embedding_length", 1024)
        }.build()
        val header = GgufParser.parse(file)
        assertEquals("qwen3", header.metadata.architecture)
        assertTrue(header.metadata.architectureInferred)
        assertEquals(28L, header.metadata.blockCount)
        assertTrue(
            "inference must be disclosed: ${header.warnings}",
            header.warnings.any { it is GgufWarning.ArchitectureInferred },
        )
    }

    @Test
    fun `a missing context length is absent, not defaulted to zero`() {
        val file = TinyModelSpec.builder(withContext = false)
            .build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt())
        val header = GgufParser.parse(file)
        assertNull("absence must stay distinguishable from a zero", header.metadata.contextLength)
        // Parsing is not affected — only the estimate has to cope.
        assertTrue(header.isComplete)
    }

    @Test
    fun `a duplicate key takes the last value and is disclosed`() {
        val file = SyntheticGgufBuilder().apply {
            u32("qwen3.block_count", 28)
            u32("qwen3.block_count", 36)
        }.build()
        val header = GgufParser.parse(file)
        assertEquals(36L, header.metadata.blockCount)
        assertTrue(header.warnings.any { it is GgufWarning.DuplicateKey })
    }

    @Test
    fun `a sharded model is flagged as unloadable on its own`() {
        val file = TinyModelSpec.builder().apply {
            u32("general.split.count", 2)
            u32("general.split.no", 0)
        }.build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt())
        val warning = GgufParser.parse(file).warnings.filterIsInstance<GgufWarning.ShardedModel>().single()
        assertEquals(2L, warning.shardCount)
    }

    @Test
    fun `nested arrays are rejected rather than guessed at`() {
        val file = SyntheticGgufBuilder().apply { nestedArray("weird.nested") }.build()
        val e = runCatching { GgufParser.parse(file) }.exceptionOrNull() as GgufParseException
        assertEquals(GgufParseException.Reason.UNSUPPORTED_STRUCTURE, e.reason)
    }

    @Test
    fun `file-backed parsing agrees with in-memory parsing`() {
        val bytes = TinyModelSpec.validFile()
        val file = java.io.File.createTempFile("gguf-parity", ".gguf")
        try {
            file.writeBytes(bytes)
            val fromFile = GgufParser.parse(file)
            val fromBytes = GgufParser.parse(bytes)
            assertEquals(fromBytes.metadata.fields.keys, fromFile.metadata.fields.keys)
            assertEquals(fromBytes.declaredWeightBytes, fromFile.declaredWeightBytes)
            assertEquals(bytes.size.toLong(), fromFile.fileBytes)
        } finally {
            file.delete()
        }
    }
}
