package dev.pidroid.android.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds GGUF v3 containers byte-by-byte so the test exercises the real wire
 * format, not a reimplementation of it. Every field is written in the order and
 * width the spec requires, little-endian.
 */
class GgufTestBuilder {

    private val out = ByteArrayOutputStream()

    fun header(version: Int = 3, tensorCount: Long = 0, kvCount: Long): GgufTestBuilder {
        raw("GGUF".toByteArray(Charsets.US_ASCII))
        u32(version.toLong())
        u64(tensorCount)
        u64(kvCount)
        return this
    }

    fun string(s: String): GgufTestBuilder {
        val bytes = s.toByteArray(Charsets.UTF_8)
        u64(bytes.size.toLong())
        raw(bytes)
        return this
    }

    fun u8(v: Int): GgufTestBuilder = raw(byteArrayOf(v.toByte()))
    fun i8(v: Int): GgufTestBuilder = raw(byteArrayOf(v.toByte()))
    fun bool(v: Boolean): GgufTestBuilder = u8(if (v) 1 else 0)
    fun u16(v: Int): GgufTestBuilder = raw(le2(v))
    fun i16(v: Int): GgufTestBuilder = raw(le2(v))
    fun u32(v: Long): GgufTestBuilder = raw(le4(v))
    fun i32(v: Int): GgufTestBuilder = raw(le4(v.toLong()))
    fun u64(v: Long): GgufTestBuilder = raw(le8(v))
    fun i64(v: Long): GgufTestBuilder = raw(le8(v))
    fun f32(v: Float): GgufTestBuilder = raw(le4(java.lang.Float.floatToRawIntBits(v).toLong()))
    fun f64(v: Double): GgufTestBuilder = raw(le8(java.lang.Double.doubleToRawLongBits(v)))

    /** A KV entry: `key`, then `type`, then the value payload. */
    fun kvString(key: String, value: String) = string(key).u32(8).string(value)

    fun kvU32(key: String, value: Long) = string(key).u32(4).u32(value)

    fun kvU64(key: String, value: Long) = string(key).u32(10).u64(value)

    fun kvI32(key: String, value: Int) = string(key).u32(5).i32(value)

    fun kvF32(key: String, value: Float) = string(key).u32(6).f32(value)

    fun kvBool(key: String, value: Boolean) = string(key).u32(7).bool(value)

    fun kvU8(key: String, value: Int) = string(key).u32(0).u8(value)

    /** An array KV entry with an explicit element type and raw element payload. */
    fun kvArray(key: String, elementType: Int, count: Long, payload: ByteArray) =
        string(key).u32(9).u32(elementType.toLong()).u64(count).raw(payload)

    /** An entry whose declared type id is not one the reader knows. */
    fun kvUnknownType(key: String, typeId: Long, payload: ByteArray) =
        string(key).u32(typeId).raw(payload)

    fun tensorInfo(name: String, dims: LongArray, type: Int, offset: Long): GgufTestBuilder {
        string(name)
        u32(dims.size.toLong())
        dims.forEach { u64(it) }
        u32(type.toLong())
        u64(offset)
        return this
    }

    fun raw(bytes: ByteArray): GgufTestBuilder {
        out.write(bytes)
        return this
    }

    fun pad(n: Int): GgufTestBuilder = raw(ByteArray(n))

    fun build(): ByteArray = out.toByteArray()

    fun buildWithTrailer(n: Int): ByteArray = build() + ByteArray(n)

    private fun le2(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    private fun le4(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
    private fun le8(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}

/** A valid, llama-3-architecture Q4_K_M-shaped header. */
private fun validLlamaHeader(): ByteArray = GgufTestBuilder()
    .header(tensorCount = 4, kvCount = 10)
    .kvString("general.architecture", "llama")
    .kvString("general.name", "Llama 3.2 3B Instruct")
    .kvU32("general.alignment", 32)
    .kvU32("general.file_type", 15) // Q4_K_M
    .kvU32("general.quantization_version", 2)
    .kvU64("general.parameter_count", 3_211_758_208L)
    .kvU32("llama.context_length", 131072)
    .kvU32("llama.embedding_length", 3072)
    .kvU32("llama.block_count", 28)
    .kvU32("llama.attention.head_count", 24)
    .build()

class GgufMetadataTest {

    // ---- happy path --------------------------------------------------------------

    @Test
    fun `valid v3 header parses every field the model manager needs`() {
        val bytes = validLlamaHeader()
        val meta = GgufReader.parse(ByteArrayInputStream(bytes), bytes.size.toLong())

        assertEquals(3, meta.version)
        assertEquals(4L, meta.tensorCount)
        assertEquals("llama", meta.architecture)
        assertEquals("Llama 3.2 3B Instruct", meta.name)
        assertEquals(131072, meta.contextLength)
        assertEquals(3072, meta.embeddingLength)
        assertEquals(28, meta.blockCount)
        assertEquals(24, meta.attentionHeadCount)
        assertEquals("Q4_K_M", meta.quantType)
        assertEquals(15, meta.quantTypeId)
        assertEquals(2, meta.quantizationVersion)
        assertEquals(3_211_758_208L, meta.parameterCount)
        assertEquals(bytes.size.toLong(), meta.fileSizeBytes)
        assertFalse(meta.hasChatTemplate)
        // kvSectionBytes is where the tensor table would start.
        assertEquals(bytes.size.toLong(), meta.kvSectionBytes)
    }

    @Test
    fun `file size comes from the descriptor when the caller knows it`() {
        val bytes = validLlamaHeader()
        // Real GGUFs are gigabytes; the header is a few hundred bytes of that.
        val declared = 1_929_000_000L
        val meta = GgufReader.parse(ByteArrayInputStream(bytes), declared)
        assertEquals(declared, meta.fileSizeBytes)
    }

    @Test
    fun `file size falls back to bytes consumed when the length is unknown`() {
        val bytes = validLlamaHeader()
        val meta = GgufReader.parse(ByteArrayInputStream(bytes))
        assertEquals(bytes.size.toLong(), meta.fileSizeBytes)
    }

    @Test
    fun `all scalar value types round-trip`() {
        val c = GgufTestBuilder()
            .header(kvCount = 10)
            .kvU8("t.uint8", 200)
            .string("t.int8").u32(1).i8(-5)
            .string("t.uint16").u32(2).u16(65535)
            .string("t.int16").u32(3).i16(-32768)
            .kvU32("t.uint32", 4_000_000_000L)
            .kvI32("t.int32", -1)
            .kvF32("t.float32", 1.5f)
            .kvBool("t.bool", true)
            .kvString("t.string", "hello")
            .kvU64("t.uint64", MAX_U64)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(c), c.size.toLong(), retainAllKeys = true)
        assertEquals(GgufValue.U8(200), meta.get("t.uint8"))
        assertEquals(GgufValue.I8(-5), meta.get("t.int8"))
        assertEquals(GgufValue.U16(65535), meta.get("t.uint16"))
        assertEquals(GgufValue.I16(-32768), meta.get("t.int16"))
        assertEquals(GgufValue.U32(4_000_000_000L), meta.get("t.uint32"))
        assertEquals(GgufValue.I32(-1), meta.get("t.int32"))
        assertEquals(1.5f, (meta.get("t.float32") as GgufValue.F32).value, 0f)
        assertEquals(GgufValue.Bool(true), meta.get("t.bool"))
        assertEquals("hello", (meta.get("t.string") as GgufValue.Str).value)
        assertEquals(GgufValue.U64(MAX_U64), meta.get("t.uint64"))
    }

    @Test
    fun `float64 and signed 64-bit values decode`() {
        val b = GgufTestBuilder()
            .header(kvCount = 2)
            .string("t.float64").u32(12).f64(2.5)
            .string("t.int64").u32(11).i64(-9_000_000_000L)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), retainAllKeys = true)
        assertEquals(2.5, (meta.get("t.float64") as GgufValue.F64).value, 0.0)
        assertEquals(-9_000_000_000L, (meta.get("t.int64") as GgufValue.I64).value)
    }

    @Test
    fun `empty string and zero-length array are legal`() {
        val b = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("t.empty", "")
            .kvArray("t.arr", elementType = 4, count = 0, payload = ByteArray(0))
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), retainAllKeys = true)
        assertEquals("", (meta.get("t.empty") as GgufValue.Str).value)
        assertEquals(0, (meta.get("t.arr") as GgufValue.Arr).values.size)
    }

    @Test
    fun `arrays of each scalar type decode and nested arrays do not crash`() {
        val b = GgufTestBuilder()
            .header(kvCount = 3)
            .kvArray("t.u32s", elementType = 4, count = 3, payload = leBytes { i32(10); i32(20); i32(30) })
            .kvArray(
                "t.strs", elementType = 8, count = 1,
                payload = leBytes { u64(2); raw("hi".toByteArray()) },
            )
            .kvArray("t.nested", elementType = 9, count = 1, payload = leBytes {
                u32(4)   // element type u32
                u64(1)   // count
                i32(7)   // one element
            })
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), retainAllKeys = true)
        val u32s = meta.get("t.u32s") as GgufValue.Arr
        assertEquals(listOf(GgufValue.U32(10), GgufValue.U32(20), GgufValue.U32(30)), u32s.values)
        val strs = meta.get("t.strs") as GgufValue.Arr
        assertEquals("hi", (strs.values[0] as GgufValue.Str).value)
        val nested = meta.get("t.nested") as GgufValue.Arr
        assertEquals(listOf(GgufValue.U32(7)), (nested.values[0] as GgufValue.Arr).values)
    }

    @Test
    fun `chat template sets the tool-calling flag`() {
        val b = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "qwen2")
            .kvString("tokenizer.chat_template", "{{ bos }}<|im_start|>user\n{{ messages }}")
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong())
        assertTrue(meta.hasChatTemplate)
        assertEquals("qwen2", meta.architecture)
    }

    @Test
    fun `token ids decode`() {
        val b = GgufTestBuilder()
            .header(kvCount = 3)
            .kvU32("tokenizer.ggml.bos_token_id", 128000)
            .kvI32("tokenizer.ggml.eos_token_id", 128001)
            .kvString("general.architecture", "llama")
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong())
        assertEquals(128000, meta.bosTokenId)
        assertEquals(128001, meta.eosTokenId)
    }

    // ---- heap discipline ---------------------------------------------------------

    @Test
    fun `uninteresting keys are skipped and never retained`() {
        // A real tokenizer's token array: 4096 strings the model manager never reads.
        val tokenBytes = leBytes {
            repeat(64) { i ->
                val tok = "token$i"
                u64(tok.length.toLong())
                raw(tok.toByteArray())
            }
        }
        val b = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "llama")
            .kvArray("tokenizer.ggml.tokens", elementType = 8, count = 64, payload = tokenBytes)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong())
        assertEquals("llama", meta.architecture)
        assertNull("the 64-entry token array must not be retained", meta.get("tokenizer.ggml.tokens"))
        assertTrue(meta.retainedKeys().all { it != "tokenizer.ggml.tokens" })
    }

    @Test
    fun `retainAllKeys keeps what the default drops`() {
        val b = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "llama")
            .kvString("custom.thing", "kept")
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), retainAllKeys = true)
        assertEquals("kept", (meta.get("custom.thing") as GgufValue.Str).value)
    }

    // ---- error paths -------------------------------------------------------------

    @Test
    fun `bad magic yields a clean error naming the offset`() {
        val b = GgufTestBuilder().raw("XXXX".toByteArray()).pad(32).build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("message should mention the magic, was: ${e.message}", e.message!!.contains("magic"))
        assertTrue(e.message!!.contains("GGUF"))
    }

    @Test
    fun `truncated header fails cleanly rather than returning defaults`() {
        val full = validLlamaHeader()
        // Cut inside the KV section: the declared kv_count is still 10.
        val truncated = full.copyOf(full.size / 2)
        val e = expectFormatError {
            GgufReader.parse(ByteArrayInputStream(truncated), truncated.size.toLong())
        }
        assertTrue("was: ${e.message}", e.message!!.contains("truncated"))
    }

    @Test
    fun `a file shorter than the fixed header fails cleanly`() {
        val b = "GG".toByteArray(Charsets.US_ASCII)
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("was: ${e.message}", e.message!!.contains("truncated"))
    }

    @Test
    fun `empty input fails cleanly`() {
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(ByteArray(0)), 0) }
        assertTrue("was: ${e.message}", e.message!!.contains("truncated"))
    }

    @Test
    fun `a kv count that overruns the file yields a clean error`() {
        // 10^9 KV entries in a 40-byte file.
        val b = GgufTestBuilder()
            .header(kvCount = 1_000_000_000L)
            .kvString("general.architecture", "llama")
            .build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("was: ${e.message}", e.message!!.contains("kv_count"))
    }

    @Test
    fun `an absurd kv count is rejected before any allocation`() {
        val b = GgufTestBuilder().header(kvCount = Long.MAX_VALUE).build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("was: ${e.message}", e.message!!.contains("kv_count"))
    }

    @Test
    fun `an unknown value type is reported, not crashed on`() {
        // Type id 999 with 4 bytes of payload: we cannot know the width, so we
        // must refuse rather than desynchronise.
        val b = GgufTestBuilder()
            .header(kvCount = 1)
            .kvUnknownType("weird.key", typeId = 999, payload = leBytes { i32(1) })
            .build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("was: ${e.message}", e.message!!.contains("unknown GGUF value type"))
    }

    @Test
    fun `an unknown value type in a skipped key still fails cleanly`() {
        val b = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "llama")
            .kvUnknownType("not.interesting", typeId = 4242, payload = leBytes { i32(1) })
            .build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("was: ${e.message}", e.message!!.contains("unknown GGUF value type"))
    }

    @Test
    fun `an oversized string length is rejected without allocating`() {
        val b = GgufTestBuilder()
            .header(kvCount = 1)
            .string("general.name")
            .u32(8)       // string type
            .u64(1 shl 40) // 1 TiB of "name"
            .build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("was: ${e.message}", e.message!!.contains("bytes at offset"))
    }

    @Test
    fun `an array count longer than the file is rejected`() {
        val b = GgufTestBuilder()
            .header(kvCount = 1)
            .string("t.arr")
            .u32(9)        // array
            .u32(4)        // element u32
            .u64(1 shl 30) // a billion elements
            .build()
        val e = expectFormatError {
            GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), retainAllKeys = true)
        }
        assertTrue("was: ${e.message}", e.message!!.contains("elements"))
    }

    @Test
    fun `a future version is refused instead of misparsed`() {
        val b = GgufTestBuilder().header(version = 99, kvCount = 0).build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), b.size.toLong()) }
        assertTrue("was: ${e.message}", e.message!!.contains("version 99"))
    }

    @Test
    fun `a bool other than 0 or 1 is a clean error`() {
        val b = GgufTestBuilder()
            .header(kvCount = 1)
            .string("t.bool").u32(7).u8(7)
            .build()
        val e = expectFormatError {
            GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), retainAllKeys = true)
        }
        assertTrue("was: ${e.message}", e.message!!.contains("bool"))
    }

    @Test
    fun `a stream that lies about its length does not read out of bounds`() {
        // A well-formed fixed header (magic + version + counts) followed by
        // nothing, but the caller claims 64 bytes. The reader must trust the
        // stream, not the claim, and stop at the truncation.
        val b = GgufTestBuilder()
            .header(kvCount = 4) // says 4 KVs, supplies none
            .build()
        val e = expectFormatError { GgufReader.parse(ByteArrayInputStream(b), 64L) }
        assertTrue("was: ${e.message}", e.message!!.contains("truncated"))
    }

    @Test
    fun `a stream that ends early without a declared length reports the truncation`() {
        val full = validLlamaHeader()
        val lying = object : InputStream() {
            private val delegate = ByteArrayInputStream(full.copyOf(full.size - 3))
            private var served = 0
            override fun read(): Int {
                if (served++ > 40) return -1 // lie: pretend the file is longer
                return delegate.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val r = delegate.read(b, off, len)
                served += if (r > 0) r else 0
                return r
            }
        }
        try {
            GgufReader.parse(lying, -1L)
            fail("expected an error from a stream that ends early")
        } catch (e: EOFException) {
            assertTrue(true)
        } catch (e: GgufFormatException) {
            assertTrue(true)
        }
    }

    // ---- tensor table ------------------------------------------------------------

    @Test
    fun `tensor table decodes when requested`() {
        val b = GgufTestBuilder()
            .header(tensorCount = 2, kvCount = 1)
            .kvString("general.architecture", "llama")
            .tensorInfo("token_embd.weight", longArrayOf(3072, 128256), 12, 0)
            .tensorInfo("blk.0.attn_q.weight", longArrayOf(3072, 3072), 6, 1_572_864)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), readTensors = true)
        assertEquals(2, meta.tensors.size)
        assertEquals("token_embd.weight", meta.tensors[0].name)
        assertEquals(listOf(3072L, 128256L), meta.tensors[0].dimensions)
        assertEquals(12, meta.tensors[0].type)
        assertEquals(1_572_864L, meta.tensors[1].offset)
    }

    @Test
    fun `tensor table is not read by default`() {
        // tensor_count says 5 but no tensor data follows: the default parse must
        // succeed, because the model manager only needs the KV section.
        val b = GgufTestBuilder()
            .header(tensorCount = 5, kvCount = 1)
            .kvString("general.architecture", "llama")
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong())
        assertEquals(5L, meta.tensorCount)
        assertTrue(meta.tensors.isEmpty())
    }

    @Test
    fun `mixed quant is detected from the tensor table`() {
        val b = GgufTestBuilder()
            .header(tensorCount = 2, kvCount = 1)
            .kvString("general.architecture", "llama")
            .tensorInfo("blk.0.ffn_down.weight", longArrayOf(3072, 8192), 14 /* Q6_K */, 0)
            .tensorInfo("blk.0.attn_q.weight", longArrayOf(3072, 3072), 12 /* Q4_K */, 1)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong(), readTensors = true)
        assertTrue(meta.isMixedQuant)
    }

    @Test
    fun `a non-llama architecture resolves its own hyper-parameters`() {
        val b = GgufTestBuilder()
            .header(kvCount = 5)
            .kvString("general.architecture", "qwen2")
            .kvString("general.name", "Qwen2.5 3B")
            .kvU32("qwen2.context_length", 32768)
            .kvU32("qwen2.embedding_length", 2048)
            .kvU32("qwen2.block_count", 36)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong())
        assertEquals("qwen2", meta.architecture)
        assertEquals(32768, meta.contextLength)
        assertEquals(2048, meta.embeddingLength)
        assertEquals(36, meta.blockCount)
        assertEquals("Qwen2.5 3B", meta.name)
    }

    @Test
    fun `unknown file type is labelled rather than guessed`() {
        val b = GgufTestBuilder()
            .header(kvCount = 2)
            .kvString("general.architecture", "llama")
            .kvU32("general.file_type", 99)
            .build()
        val meta = GgufReader.parse(ByteArrayInputStream(b), b.size.toLong())
        assertEquals("UNKNOWN_99", meta.quantType)
        assertEquals(99, meta.quantTypeId)
    }

    // ---- helpers -----------------------------------------------------------------

    private fun expectFormatError(block: () -> Unit): GgufFormatException {
        try {
            block()
        } catch (e: GgufFormatException) {
            return e
        } catch (e: IndexOutOfBoundsException) {
            fail("parser leaked an IndexOutOfBoundsException: ${e.message}")
        } catch (e: OutOfMemoryError) {
            fail("parser tried to allocate on an untrusted length: ${e.message}")
        }
        fail("expected a GgufFormatException")
        error("unreachable")
    }
}

/** Little-endian payload builder, independent of [GgufTestBuilder]'s instance state. */
private fun leBytes(block: GgufTestBuilder.() -> Unit): ByteArray = GgufTestBuilder().apply(block).build()

/**
 * Long.MAX_VALUE written as an expression: Kotlin rejects 18_446_744_073_709_551_615L
 * as a literal because it does not fit the type checker before the L suffix applies.
 */
private val MAX_U64: Long = -1L ushr 1 or Long.MIN_VALUE
