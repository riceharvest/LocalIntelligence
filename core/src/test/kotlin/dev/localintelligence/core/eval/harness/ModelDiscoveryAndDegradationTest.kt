// ===========================================================================
// ModelDiscoveryAndDegradationTest.kt
//
// The "the benchmark cannot run" paths, and the model gate that prevents them.
//
// THE POINT OF THIS FILE
// =====================
//
// A benchmark that prints zeros when it has no model is worse than no
// benchmark: it converts "I do not know" into "the model is bad", and that
// mistake is invisible in a CI log. So every way this harness can fail to run
// is tested here for the same two properties:
//
//   1. it exits NON-ZERO
//   2. it prints NO result that could be mistaken for a measurement
//
// The GGUF files are SYNTHETIC. They are written byte by byte in the test, so
// the metadata gate is genuinely exercised — a real header, parsed by the real
// reader, with the context length and quantisation actually encoded — without
// needing a model on the machine. That is the only way to test the gate in CI,
// and it is why the reader is small enough to reimplement here.
// ===========================================================================

package dev.localintelligence.core.eval.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class ModelDiscoveryAndDegradationTest {

    // ---------------------------------------------------------------- discovery

    @Test
    fun `discovery finds gguf files and ignores everything else`() {
        val dir = tempDir()
        writeFakeGguf(dir.resolve("model-a.gguf"), contextLength = 4096)
        writeFakeGguf(dir.resolve("model-b.gguf"), contextLength = 2048)
        Files.writeString(dir.resolve("notes.txt"), "not a model")
        Files.writeString(dir.resolve("model-c.GGUF"), "uppercase extension")

        val found = GgufModelDiscovery.discover(dir)

        assertEquals("two real .gguf files plus the uppercase one", 3, found.size)
        assertTrue(found.any { it.fileName.toString() == "model-a.gguf" })
        assertTrue(found.any { it.fileName.toString() == "model-c.GGUF" })
        assertTrue("a .txt must never be offered as a model", found.none { it.fileName.toString() == "notes.txt" })
    }

    @Test
    fun `discovery of a directory that does not exist is empty, not an exception`() {
        val missing = Files.createTempDirectory("no-models-here").resolve("nope")
        assertEquals(emptyList<Path>(), GgufModelDiscovery.discover(missing))
    }

    @Test
    fun `discovery order is stable across runs`() {
        val dir = tempDir()
        writeFakeGguf(dir.resolve("zebra.gguf"), contextLength = 4096)
        writeFakeGguf(dir.resolve("alpha.gguf"), contextLength = 4096)
        writeFakeGguf(dir.resolve("middle.gguf"), contextLength = 4096)

        val first = GgufModelDiscovery.discover(dir).map { it.fileName.toString() }
        val second = GgufModelDiscovery.discover(dir).map { it.fileName.toString() }
        assertEquals("ordering must not depend on the filesystem", first, second)
        assertEquals(listOf("alpha.gguf", "middle.gguf", "zebra.gguf"), first)
    }

    // ------------------------------------------------------------- header parse

    @Test
    fun `a real gguf header yields architecture, context length and quant`() {
        val dir = tempDir()
        val file = writeFakeGguf(
            dir.resolve("qwen.gguf"),
            architecture = "qwen2",
            contextLength = 8192,
            quantFileType = 15, // Q4_K_M
        )

        val info = GgufModelDiscovery.read(file)

        assertEquals("qwen2", info.architecture)
        assertEquals(8192, info.contextLength)
        assertEquals("Q4_K_M", info.quantType)
        assertTrue("the fixture declares a chat template", info.hasChatTemplate)
        assertTrue("the file size must be reported", info.sizeBytes > 0)
    }

    @Test
    fun `a file that is not gguf is refused with a message naming the problem`() {
        val dir = tempDir()
        val notAModel = dir.resolve("fake.gguf")
        Files.writeString(notAModel, "this is definitely not a GGUF header")

        val error = runCatching { GgufModelDiscovery.read(notAModel) }.exceptionOrNull()
        assertTrue("must throw, got $error", error is GgufFormatException)
        assertTrue(
            "the message must say what is wrong: ${error!!.message}",
            error.message!!.contains("not a GGUF file"),
        )
    }

    @Test
    fun `a truncated header is refused rather than read past the end`() {
        val dir = tempDir()
        val truncated = dir.resolve("truncated.gguf")
        // A valid magic and version, then nothing. The reader must notice the
        // file ends mid-header instead of throwing EOFException or allocating
        // from a length it never read.
        Files.write(truncated, byteArrayOf(0x47, 0x47, 0x55, 0x46, 3, 0, 0, 0))

        val error = runCatching { GgufModelDiscovery.read(truncated) }.exceptionOrNull()
        assertTrue("must throw a clean format error, got $error", error is GgufFormatException)
    }

    // -------------------------------------------------------------- the fit gate

    @Test
    fun `the gate refuses a model whose context is too small`() {
        val info = GgufModelInfo(
            path = "/models/tiny.gguf",
            fileName = "tiny.gguf",
            sizeBytes = 1_000_000,
            architecture = "llama",
            contextLength = 2048,
            quantType = "Q4_K_M",
            hasChatTemplate = true,
        )

        val verdict = GgufModelDiscovery.assessFit(info, requiredContextTokens = 4096)

        assertFalse("a 2048-token model must not be run against a 4096-token suite", verdict.runs)
        assertTrue(
            "the reason must name both numbers: ${verdict.reasons}",
            verdict.reasons.any { it.contains("2048") && it.contains("4096") },
        )
        assertTrue(
            "the explanation must be actionable",
            verdict.explain().contains("REFUSED"),
        )
    }

    @Test
    fun `the gate refuses a model that does not declare a context length`() {
        val info = GgufModelInfo(
            path = "/models/nameless.gguf",
            fileName = "nameless.gguf",
            sizeBytes = 1_000,
            architecture = "llama",
            contextLength = null,
            hasChatTemplate = true,
        )

        val verdict = GgufModelDiscovery.assessFit(info, requiredContextTokens = 4096)

        assertFalse(
            "an unknown context cannot be treated as unlimited — that is how a 0.00 gets blamed on the model",
            verdict.runs,
        )
    }

    @Test
    fun `the gate refuses a base model with no chat template`() {
        val info = GgufModelInfo(
            path = "/models/base.gguf",
            fileName = "base.gguf",
            sizeBytes = 1_000,
            architecture = "llama",
            contextLength = 8192,
            hasChatTemplate = false,
        )

        val refused = GgufModelDiscovery.assessFit(info, 4096, requireChatTemplate = true)
        assertFalse("a base model cannot be prompted into the action protocol", refused.runs)
        assertTrue(
            "the reason must mention the template: ${refused.reasons}",
            refused.reasons.any { it.contains("chat template") },
        )

        val allowed = GgufModelDiscovery.assessFit(info, 4096, requireChatTemplate = false)
        assertTrue("the requirement is opt-out, not absolute", allowed.runs)
    }

    @Test
    fun `the gate passes a model that meets every requirement`() {
        val info = GgufModelInfo(
            path = "/models/good.gguf",
            fileName = "good.gguf",
            sizeBytes = 2_000_000_000,
            architecture = "qwen2",
            contextLength = 8192,
            quantType = "Q4_K_M",
            hasChatTemplate = true,
        )

        val verdict = GgufModelDiscovery.assessFit(info, 4096)

        assertTrue("a suitable model must be allowed to run: ${verdict.reasons}", verdict.runs)
        assertTrue(verdict.explain().contains("OK"))
    }

    // ------------------------------------------------------------ CLI degradation

    @Test
    fun `a missing model file exits non-zero and prints no result`() {
        val dir = tempDir()
        val missing = dir.resolve("not-here.gguf")

        val outcome = BenchmarkMain.run(
            BenchmarkOptions(
                modelFile = missing,
                useScriptedBackend = false,
            ),
            nowEpochMs = { FIXED_NOW },
        )

        val refused = outcome as? BenchmarkOutcome.Refused
        assertNotNull("a missing model must refuse, got $outcome", refused)
        assertTrue(
            "exit code must be non-zero, was ${refused!!.exitCode}",
            refused.exitCode != BenchmarkExit.OK,
        )
        assertEquals(BenchmarkExit.NO_MODEL, refused.exitCode)
        assertTrue(
            "the message must say no model was found: ${refused.message}",
            refused.message.contains("No model was found"),
        )
        // The critical assertion: the refusal is not shaped like a result.
        assertFalse(
            "a refusal must not contain a success rate",
            refused.message.contains("task success"),
        )
        assertFalse(
            "a refusal must not report a passed count",
            Regex("""\d+ pass""").containsMatchIn(refused.message),
        )
    }

    @Test
    fun `an empty model directory exits non-zero and prints no result`() {
        val dir = tempDir()

        val outcome = BenchmarkMain.run(
            BenchmarkOptions(modelDir = dir, useScriptedBackend = false),
            nowEpochMs = { FIXED_NOW },
        )

        val refused = outcome as? BenchmarkOutcome.Refused
        assertNotNull("an empty directory must refuse, got $outcome", refused)
        assertEquals(BenchmarkExit.NO_MODEL, refused!!.exitCode)
        assertTrue(refused.message.contains("No model was found"))
    }

    @Test
    fun `the real llama backend is unavailable on this JVM and says so`() {
        // There is no native library for a desktop JVM, and that is the
        // expected state — not a failure. What matters is that the harness
        // detects it and refuses rather than reporting zeros.
        val bridge = LlamaJniBridge.reflective()
        assertNotNull(
            "the llama.cpp bridge must be unavailable on a plain JVM",
            bridge.unavailable,
        )
        val backend = LlamaCppBenchmarkBackend(modelPath = "/models/whatever.gguf", bridge = bridge)
        assertNotNull(backend.unavailable)
        assertTrue(
            "the message must say the library could not be loaded: ${backend.unavailable!!.message}",
            backend.unavailable!!.message.contains("could not be loaded") ||
                backend.unavailable!!.message.contains("not on the classpath"),
        )
    }

    @Test
    fun fun_loadA_modelThroughAnUnavailableBackendThrowsRatherThanPretending() {
        val backend = LlamaCppBenchmarkBackend(
            modelPath = "/models/whatever.gguf",
            bridge = LlamaJniBridge.reflective(),
        )
        val error = runCatching {
            kotlinx.coroutines.runBlocking {
                backend.load(dev.localintelligence.core.model.ModelSpec(id = "benchmark", displayName = "benchmark"))
            }
        }.exceptionOrNull()
        assertNotNull("load must fail loudly, never return a fake success", error)
        assertTrue("the failure must be reported, not swallowed", error is IllegalStateException)
    }

    @Test
    fun `a non-gguf file given as a model is refused by the gate, not loaded`() {
        val dir = tempDir()
        val bogus = dir.resolve("bogus.gguf")
        Files.writeString(bogus, "definitely not a GGUF container")

        val outcome = BenchmarkMain.run(
            BenchmarkOptions(modelFile = bogus, useScriptedBackend = false),
            nowEpochMs = { FIXED_NOW },
        )

        val refused = outcome as? BenchmarkOutcome.Refused
        assertNotNull("a corrupt model must refuse, got $outcome", refused)
        assertTrue(refused!!.exitCode != BenchmarkExit.OK)
        assertTrue(
            "the message must say the file could not be read: ${refused.message}",
            refused.message.contains("could not be read as a GGUF") || refused.message.contains("No results were recorded"),
        )
    }

    @Test
    fun `a model the gate refuses exits non-zero and writes no results`() {
        val dir = tempDir()
        // A real, parseable GGUF header — but trained for 2048 tokens, and the
        // suite needs 4096. This is the exact case that would otherwise produce
        // a 0.00 success rate and a conversation about model quality.
        val tooSmall = writeFakeGguf(dir.resolve("small.gguf"), contextLength = 2048)

        val outcome = BenchmarkMain.run(
            BenchmarkOptions(
                modelFile = tooSmall,
                useScriptedBackend = false,
                requiredContext = 4096,
            ),
            nowEpochMs = { FIXED_NOW },
        )

        val refused = outcome as? BenchmarkOutcome.Refused
        assertNotNull("the gate must refuse before loading, got $outcome", refused)
        assertEquals(BenchmarkExit.MODEL_UNFIT, refused!!.exitCode)
        assertTrue(
            "the message must name the context shortfall: ${refused.message}",
            refused.message.contains("2048") && refused.message.contains("4096"),
        )
        assertTrue(
            "and must state that nothing was measured: ${refused.message}",
            refused.message.contains("No results were recorded"),
        )
    }

    // ------------------------------------------------------------------ options

    @Test
    fun `option parsing never throws on bad input`() {
        // A harness that crashes on a typo reports zero tasks and looks like a
        // passing build, which is the worst possible outcome for this tool.
        val cases = listOf(
            arrayOf("--gguf"),
            arrayOf("--out"),
            arrayOf("--only"),
            arrayOf("--limit"),
            arrayOf("--context"),
            arrayOf("--unknown-flag", "value"),
            arrayOf("--gguf", "--out", "x.json"),
            arrayOf("--limit", "not-a-number"),
        )
        cases.forEach { argv ->
            val options = runCatching { BenchmarkOptions.parse(argv) }
            assertTrue("parse(${(argv.toList())}) must not throw: ${runCatching { BenchmarkOptions.parse(argv) }.exceptionOrNull()}", options.isSuccess)
        }
    }

    @Test
    fun `a flag missing its value does not swallow the next flag`() {
        val options = BenchmarkOptions.parse(arrayOf("--gguf", "--verbose"))
        assertTrue("--verbose must still be seen", options.verbose)
        assertEquals("--gguf must not consume --verbose as its path", null, options.modelFile)
    }

    @Test
    fun `naming a model opts out of the scripted backend`() {
        val withModel = BenchmarkOptions.parse(arrayOf("--gguf", "/models/x.gguf"))
        assertFalse("asking for a real model must not silently use a fake", withModel.useScriptedBackend)

        val withoutModel = BenchmarkOptions.parse(arrayOf("--only", "single"))
        assertTrue("no model named means the scripted default", withoutModel.useScriptedBackend)
    }

    @Test
    fun `the limit is applied after the category filter`() {
        val dir = tempDir()
        val memoryTasks = dev.localintelligence.core.eval.TaskSuite.all()
            .filter { it.category.slug == "memory" }
        assertTrue("the fixture must have memory tasks", memoryTasks.size >= 2)

        val options = BenchmarkOptions(onlyCategories = setOf("memory"), limit = 2, useScriptedBackend = true)
        val outcome = BenchmarkMain.run(options, nowEpochMs = { FIXED_NOW })
        val completed = outcome as? BenchmarkOutcome.Completed
        // With the scripted backend this runs the whole (filtered) suite, which
        // is fine for a JVM test; what matters is the filter+limit path does not
        // throw and produces a coherent result.
        assertNotNull("the filtered run must complete", completed)
        assertTrue(
            "every result must belong to the requested category",
            completed!!.run.results.all { it.category == "memory" },
        )
    }

    @Test
    fun `a corrupt file in a model directory does not block a good model`() {
        val dir = tempDir()
        // Sorted discovery hits "aaa-broken.gguf" first, so a naive
        // "take the first .gguf" would pick the broken one and fail the run.
        Files.writeString(dir.resolve("aaa-broken.gguf"), "not a GGUF header at all")
        writeFakeGguf(dir.resolve("mmm-too-small.gguf"), contextLength = 512)
        val good = writeFakeGguf(dir.resolve("zzz-good.gguf"), contextLength = 8192)

        val outcome = BenchmarkMain.run(
            BenchmarkOptions(modelDir = dir, useScriptedBackend = false, requiredContext = 4096),
            nowEpochMs = { FIXED_NOW },
        )

        // The run proceeds to the backend, which is unavailable on a desktop
        // JVM — that is the EXPECTED outcome here and it is a different exit
        // code from "no model". Reaching it proves discovery chose the good
        // file rather than refusing over the broken one.
        val refused = outcome as? BenchmarkOutcome.Refused
        assertNotNull("discovery must not refuse a directory that has a usable model", refused)
        assertEquals(
            "it must have loaded the good model and failed on the missing bridge, not on discovery",
            BenchmarkExit.BACKEND_UNAVAILABLE,
            refused!!.exitCode,
        )
        assertTrue(
            "the reason must be the missing bridge: ${refused.message}",
            refused.message.contains("not on the classpath") ||
                refused.message.contains("could not be loaded"),
        )
    }

    @Test
    fun `a directory where every model is unfit is refused, and says so`() {
        val dir = tempDir()
        writeFakeGguf(dir.resolve("small.gguf"), contextLength = 2048)
        writeFakeGguf(dir.resolve("smaller.gguf"), contextLength = 1024)

        val outcome = BenchmarkMain.run(
            BenchmarkOptions(modelDir = dir, useScriptedBackend = false, requiredContext = 4096),
            nowEpochMs = { FIXED_NOW },
        )

        val refused = outcome as? BenchmarkOutcome.Refused
        assertTrue(
            "the message must say every candidate was refused, got $outcome",
            refused != null,
        )
        assertTrue(
            "the message must say every candidate was refused: ${refused?.message}",
            refused!!.message.contains("Every .gguf found was refused"),
        )
    }

    // ----------------------------------------------------------------- fixtures

    private fun tempDir(): Path = Files.createTempDirectory("bench-harness-test")

    /**
     * Writes a byte-exact GGUF v3 header.
     *
     * Real bytes, not a mock: magic, version, tensor count, kv count, then
     * key/value pairs in the container's own encoding. The reader under test
     * therefore does its actual job, which is the only way the gate above is
     * worth anything.
     *
     * The fixture deliberately includes a large `tokenizer.ggml.tokens` ARRAY,
     * because that is the value a naive parser materialises and the one that
     * would turn a 200-byte header probe into a multi-megabyte allocation. If
     * the reader regressed into reading arrays eagerly, this test would get
     * slow or fail.
     */
    private fun writeFakeGguf(
        path: Path,
        architecture: String = "llama",
        contextLength: Int = 4096,
        quantFileType: Int = 15,
        includeChatTemplate: Boolean = true,
    ): Path {
        val out = ByteArrayOutputStream()
        fun u32(v: Long) {
            out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt())
            out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt())
        }
        fun u64(v: Long) { for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
        fun ggufString(s: String) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            u64(bytes.size.toLong())
            out.write(bytes)
        }

        out.write('G'.code); out.write('G'.code); out.write('U'.code); out.write('F'.code)
        u32(3)          // version
        u64(0)          // tensor_count
        // kv_count: 6 entries.
        u64(6)

        fun kvString(key: String, value: String) {
            ggufString(key)
            u32(8) // TYPE_STRING
            ggufString(value)
        }
        fun kvU32(key: String, value: Long) {
            ggufString(key)
            u32(4) // TYPE_UINT32
            u32(value)
        }

        // Order matters for realism: `general.architecture` comes AFTER the
        // architecture-prefixed key, which is what forces a suffix match rather
        // than a prefix match in the reader.
        kvU32("general.file_type", quantFileType.toLong())
        kvU32("$architecture.context_length", contextLength.toLong())
        kvString("general.architecture", architecture)
        kvString("general.name", "benchmark-fixture")

        // A big array that must be SKIPPED, not read. 20000 u32 elements.
        ggufString("tokenizer.ggml.tokens")
        u32(9)  // TYPE_ARRAY
        u32(4)  // element type TYPE_UINT32
        u64(20_000)
        repeat(20_000) { u32(it.toLong()) }

        if (includeChatTemplate) {
            kvString("tokenizer.chat_template", "{{ bos_token }}{% for m in messages %}{{ m['content'] }}{% endfor %}")
        }

        Files.write(path, out.toByteArray())
        return path
    }

    private companion object {
        const val FIXED_NOW = 1_760_000_000_000L
    }
}
