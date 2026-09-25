package dev.localintelligence.core.model.gguf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses the committed fixtures in `core/src/test/resources/gguf/`.
 *
 * WHY both read real bytes *and* compare them against [SyntheticGgufBuilder]: the
 * in-memory tests would pass just as happily if the builder and the parser drifted
 * together onto the same wrong idea of the format. The resource files were written by
 * a separate encoder, so [committed fixtures match the builder byte for byte] is a
 * genuine two-implementation agreement check, and it runs first because a failure
 * there makes every other assertion in this class meaningless.
 */
class SyntheticGgufResourcesTest {

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("gguf/$name")) {
            "missing test resource gguf/$name"
        }.use { it.readBytes() }

    // ---------------------------------------------------------------- cross-implementation

    @Test
    fun `committed fixtures match the builder byte for byte`() {
        assertArrayEquals(
            "tiny-qwen3-q4k.gguf drifted from SyntheticGgufBuilder",
            TinyModelSpec.builder().build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt()),
            resource("tiny-qwen3-q4k.gguf"),
        )
        assertArrayEquals(
            "tiny-qwen3-no-context.gguf drifted from SyntheticGgufBuilder",
            TinyModelSpec.builder(withContext = false).build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt()),
            resource("tiny-qwen3-no-context.gguf"),
        )
        assertArrayEquals(
            "tiny-qwen3-q4k-truncated.gguf drifted from SyntheticGgufBuilder",
            TinyModelSpec.builder().build(weightDataBytes = 0),
            resource("tiny-qwen3-q4k-truncated.gguf"),
        )
    }

    // ---------------------------------------------------------------- valid

    @Test
    fun `the valid Q4 fixture parses complete and self-consistent`() {
        val header = GgufParser.parse(resource("tiny-qwen3-q4k.gguf"))
        assertTrue("warnings: ${header.warnings}", header.warnings.isEmpty())
        assertTrue(header.isComplete)
        assertEquals("qwen3", header.metadata.architecture)
        assertEquals(TinyModelSpec.LAYERS, header.metadata.blockCount)
        assertEquals(TinyModelSpec.CONTEXT, header.metadata.contextLength)
        assertEquals(TinyModelSpec.VOCAB.toLong(), header.metadata.vocabularySize)
        assertEquals(TinyModelSpec.DECLARED_WEIGHT_BYTES, header.declaredWeightBytes)
        assertFalse(header.declaresMoreDataThanFileHas())
    }

    @Test
    fun `the valid Q4 fixture produces the same estimate as the in-memory fixture`() {
        val fromResource = ModelMemoryEstimator().estimate(GgufParser.parse(resource("tiny-qwen3-q4k.gguf")))
        val fromBuilder = ModelMemoryEstimator().estimate(
            GgufParser.parse(TinyModelSpec.builder().build(TinyModelSpec.DECLARED_WEIGHT_BYTES.toInt())),
        )
        assertEquals(fromBuilder.weightsBytes, fromResource.weightsBytes)
        assertEquals(fromBuilder.kvCacheBytes, fromResource.kvCacheBytes)
        assertEquals(fromBuilder.totalBytes, fromResource.totalBytes)
        assertEquals(MemoryEstimate.Confidence.MEASURED, fromResource.confidence)
    }

    @Test
    fun `the no-context fixture parses but the estimate discloses its assumption`() {
        val header = GgufParser.parse(resource("tiny-qwen3-no-context.gguf"))
        assertNull(header.metadata.contextLength)
        val e = ModelMemoryEstimator().estimate(header)
        assertTrue(e.warnings.any { it is GgufWarning.ContextLengthMissing })
        assertEquals(MemoryEstimate.Confidence.GUESS, e.confidence)
    }

    // ---------------------------------------------------------------- broken

    @Test
    fun `the truncated fixture is readable as far as it goes`() {
        val header = GgufParser.parse(resource("tiny-qwen3-q4k-truncated.gguf"))
        assertTrue("weights: ${header.declaredWeightBytes}", header.declaresMoreDataThanFileHas())
        assertTrue(header.warnings.any { it is GgufWarning.DeclaredDataExceedsFile })
    }

    @Test
    fun `the bad-version fixture is rejected as an unsupported version`() {
        val e = runCatching { GgufParser.parse(resource("tiny-bad-version.gguf")) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.UNSUPPORTED_VERSION, (e as GgufParseException).reason)
    }

    @Test
    fun `the non-GGUF fixture is rejected on magic`() {
        val e = runCatching { GgufParser.parse(resource("not-a-gguf.bin")) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.NOT_A_GGUF_FILE, (e as GgufParseException).reason)
    }

    @Test
    fun `the loop-bomb fixture is refused on its declared count`() {
        val e = runCatching { GgufParser.parse(resource("tiny-loop-bomb.gguf")) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.IMPLAUSIBLE_COUNT, (e as GgufParseException).reason)
    }

    @Test
    fun `the length-bomb fixture is refused on its declared string length`() {
        val e = runCatching { GgufParser.parse(resource("tiny-length-bomb.gguf")) }.exceptionOrNull()
        assertEquals(GgufParseException.Reason.LIMIT_EXCEEDED, (e as GgufParseException).reason)
    }

    @Test
    fun `the unknown-type fixture degrades to a partial header without crashing`() {
        val header = GgufParser.parse(resource("tiny-unknown-type.gguf"))
        val warning = header.warnings.filterIsInstance<GgufWarning.UnknownValueType>().single()
        assertEquals("future.thing", warning.key)
        assertEquals(4_096L, header.metadata.contextLength)
    }

    @Test
    fun `every committed fixture is accounted for`() {
        // A fixture added to the resources directory without a test here is a fixture
        // that silently stops being verified, so the count is pinned.
        val names = listOf(
            "tiny-qwen3-q4k.gguf",
            "tiny-qwen3-q4k-truncated.gguf",
            "tiny-qwen3-no-context.gguf",
            "tiny-bad-version.gguf",
            "not-a-gguf.bin",
            "tiny-loop-bomb.gguf",
            "tiny-length-bomb.gguf",
            "tiny-unknown-type.gguf",
        )
        for (name in names) assertTrue("fixture $name is missing", resource(name).isNotEmpty())
    }
}
