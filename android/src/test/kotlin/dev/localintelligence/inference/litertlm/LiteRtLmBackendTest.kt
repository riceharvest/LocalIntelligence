package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.SamplingParams
import dev.localintelligence.core.model.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The behaviour contract of [LiteRtLmBackend], exercised against
 * [FakeLiteRtLmEngine] rather than a real model.
 *
 * The engine is fake because `litertlm-android:0.13.1` is Java 21 bytecode and
 * this JVM is JDK 17 — see [LiteRtLmEngine]. But a fake is also the only way to
 * reach the cases that matter most: a runtime that streams cumulatively, a
 * generation cancelled mid-stream, a runtime that reports an error, a runtime
 * that never reports a terminal event at all, a load that fails while a previous
 * model is still resident. None of those can be provoked on demand from a real
 * 1-3B model, and all of them are cases the backend claims to handle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiteRtLmBackendTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var modelRoot: File
    private lateinit var modelFile: File
    private lateinit var factory: RecordingLiteRtLmEngineFactory
    private lateinit var backend: LiteRtLmBackend

    @Before
    fun setUp() {
        modelRoot = temp.newFolder("models")
        modelFile = File(modelRoot, "gemma-3n-e2b.litertlm")
        // Contents are irrelevant: resolution is the model's job, and the fake
        // engine never opens the file.
        modelFile.writeText("not a real model, and does not need to be")

        factory = RecordingLiteRtLmEngineFactory()
        backend = newBackend(factory)
    }

    /**
     * `Dispatchers.Unconfined` rather than a test dispatcher: [LiteRtLmBackend]
     * blocks on a runtime callback, and a queued test dispatcher would deadlock
     * waiting for a task that only runs when the test yields. The real dispatcher
     * is `Dispatchers.IO` in production.
     */
    private fun newBackend(
        factory: LiteRtLmEngineFactory,
        contextLength: Int = LiteRtLmBackend.DEFAULT_CONTEXT_LENGTH,
        supportsToolCalling: Boolean = false,
    ) = LiteRtLmBackend(
        modelSource = LiteRtLmModelSource(modelRoot),
        engineFactory = factory,
        ioDispatcher = Dispatchers.Unconfined,
        contextLength = contextLength,
        supportsToolCalling = supportsToolCalling,
    )

    private fun spec(
        path: File = modelFile,
        supportedBackends: Set<String> = emptySet(),
    ) = ModelSpec(
        id = path.absolutePath,
        displayName = "Gemma 3n E2B",
        sizeBytes = 1_500_000_000L,
        supportedBackends = supportedBackends,
    )

    private fun request(
        text: String = "what is 2 + 2?",
        params: SamplingParams = SamplingParams(),
        messages: List<ChatMessage> = listOf(ChatMessage.User(text)),
        grammar: String? = null,
    ) = GenerationRequest(messages = messages, params = params, grammar = grammar)

    /** The conversations the backend opened on the engine it was given. */
    private fun RecordingLiteRtLmEngineFactory.conversationsOf(index: Int = 0): List<FakeLiteRtLmConversation> =
        built.getOrNull(index)?.conversations.orEmpty()

    // ==================================================================== load

    @Test
    fun `load brings up an engine and reports its capabilities`() = runTest {
        backend.load(spec())

        assertEquals(1, factory.configs.size)
        assertEquals(modelFile.absolutePath, factory.configs[0].modelPath)
        assertEquals(LiteRtLmBackend.DEFAULT_CONTEXT_LENGTH, factory.configs[0].contextLength)
        // Benchmarking is the entire reason a second backend exists.
        assertTrue("benchmark must be on", factory.configs[0].enableBenchmark)

        val caps = backend.capabilities
        assertEquals(LiteRtLmBackend.DEFAULT_CONTEXT_LENGTH, caps.contextLength)
        assertTrue("a prefill-decode engine has a real KV cache", caps.supportsKvCache)
    }

    @Test
    fun `an unknown model path is refused by load, not swallowed`() = runTest {
        val missing = File(modelRoot, "does-not-exist.litertlm")

        val thrown = caughtSuspending { backend.load(spec(path = missing)) }

        assertNotNull("load must throw so the caller knows no model arrived", thrown)
        assertTrue(
            "the message must name the path: ${thrown?.message}",
            thrown!!.message!!.contains("does-not-exist"),
        )
        assertEquals("no engine may be built for a bad path", 0, factory.configs.size)
    }

    @Test
    fun `a model file that is missing throws rather than half-loading`() = runTest {
        val gone = File(modelRoot, "ghost.litertlm")
        assertFalse(gone.exists())

        val thrown = caughtSuspending { backend.load(spec(path = gone)) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        assertEquals(0, factory.built.size)
    }

    @Test
    fun `a corrupt model -- a directory holding only a GGUF -- is refused`() = runTest {
        val wrong = temp.newFolder("not-a-model")
        File(wrong, "model.gguf").writeText("GGUF...")

        val thrown = caughtSuspending { backend.load(spec(path = wrong)) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        // The message has to distinguish this from "no such file", because the fix
        // is a different model, not a different path.
        assertTrue(
            "message should mention the bundle suffix: ${thrown?.message}",
            thrown!!.message!!.contains(".litertlm"),
        )
    }

    @Test
    fun `a failed load leaves the previously loaded model working`() = runTest {
        backend.load(spec())
        val good = factory.built[0]

        caughtSuspending { backend.load(spec(path = File(modelRoot, "nope.litertlm"))) }

        assertTrue("the good engine must still be alive", good.alive)
        assertEquals("no second engine was built", 1, factory.built.size)
        // The real point: a bad path must not cost the user their working model.
        assertEquals(StopReason.COMPLETED, backend.generate(request()).stopReason)
    }

    @Test
    fun `a load that produces a dead engine is rejected and cleaned up`() = runTest {
        val deadFactory = RecordingLiteRtLmEngineFactory { FakeLiteRtLmEngine(alive = false) }
        val b = newBackend(deadFactory)

        val thrown = caughtSuspending { b.load(spec()) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmEngineException)
        assertEquals("the dead engine must be closed, not leaked", 1, deadFactory.built[0].closeCount)
    }

    @Test
    fun `a factory that throws surfaces as a load failure`() = runTest {
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            throw LiteRtLmEngineException("native library missing")
        })

        val thrown = caughtSuspending { b.load(spec()) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmEngineException)
    }

    // ================================================================= generate

    @Test
    fun `a happy path load and generate returns the text and reports COMPLETED`() = runTest {
        backend.load(spec())

        val result = backend.generate(request("hello"))

        assertEquals("Hello, world", result.text)
        assertEquals(StopReason.COMPLETED, result.stopReason)
        assertTrue("completionTokens must be counted", result.completionTokens > 0)
        assertTrue("promptTokens must be counted", result.promptTokens > 0)
    }

    @Test
    fun `generate before load is an ERROR, not a crash`() = runTest {
        val result = backend.generate(request())

        assertEquals("", result.text)
        assertEquals(StopReason.ERROR, result.stopReason)
    }

    @Test
    fun `generate after unload is an ERROR`() = runTest {
        backend.load(spec())
        backend.unload()

        assertEquals(StopReason.ERROR, backend.generate(request()).stopReason)
    }

    @Test
    fun `a runtime failure is reported as ERROR`() = runTest {
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            FakeLiteRtLmEngine { it.failWith = IllegalStateException("out of memory") }
        })
        b.load(spec())

        val result = b.generate(request())

        assertEquals(StopReason.ERROR, result.stopReason)
    }

    @Test
    fun `a runtime that throws synchronously is reported as ERROR`() = runTest {
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            FakeLiteRtLmEngine { it.throwOnSend = IllegalStateException("conversation is closed") }
        })
        b.load(spec())

        assertEquals(StopReason.ERROR, b.generate(request()).stopReason)
    }

    @Test
    fun `a runtime that never reports a terminal event is an ERROR, not an empty success`() = runTest {
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            FakeLiteRtLmEngine { it.silent = true }
        })
        b.load(spec())

        val result = b.generate(request())

        // Reporting COMPLETED here would let a wedged runtime look like a model
        // that had nothing to say -- a silent failure the agent loop cannot see.
        assertEquals(StopReason.ERROR, result.stopReason)
    }

    @Test
    fun `an empty request is refused before any runtime work`() = runTest {
        backend.load(spec())

        val result = backend.generate(request(messages = emptyList()))

        assertEquals(StopReason.ERROR, result.stopReason)
        assertEquals("no conversation may be opened", 0, factory.conversationsOf().size)
    }

    @Test
    fun `a transcript ending on a non-user turn is refused`() = runTest {
        backend.load(spec())

        val result = backend.generate(
            request(
                messages = listOf(
                    ChatMessage.User("hi"),
                    ChatMessage.Assistant("hello"),
                ),
            ),
        )

        assertEquals(StopReason.ERROR, result.stopReason)
        assertEquals(0, factory.conversationsOf().size)
    }

    @Test
    fun `a grammar request is refused because this backend cannot enforce one`() = runTest {
        backend.load(spec())

        val result = backend.generate(request(grammar = "root ::= \"<respond>\""))

        assertEquals(StopReason.ERROR, result.stopReason)
        // And the reason is declared up front, so the runtime should never have
        // sent one in the first place.
        assertFalse(backend.capabilities.supportsGrammar)
    }

    @Test
    fun `a full agent transcript maps onto the runtime conversation`() = runTest {
        backend.load(spec())

        backend.generate(
            request(
                messages = listOf(
                    ChatMessage.System("You are terse."),
                    ChatMessage.User("find alice"),
                    ChatMessage.Assistant("<tool>done</tool>"),
                    ChatMessage.ToolObservation("contacts.search", "alice: a@example.com", true),
                    ChatMessage.User("thanks"),
                ),
            ),
        )

        val conversation = factory.conversationsOf().single()
        val config = conversation.request
        assertEquals("You are terse.", config.systemInstruction)
        // The system prompt is not replayed as a history turn, or the model would
        // see it twice.
        assertEquals(
            listOf(
                LiteRtLmRole.USER,
                LiteRtLmRole.MODEL,
                LiteRtLmRole.TOOL,
            ),
            config.history.map { it.role },
        )
        // The observation keeps the tool name: LiteRT-LM models a tool result as
        // a named response, and a bare string loses which tool answered.
        val tool = config.history.last()
        assertEquals("contacts.search", tool.toolName)
        assertEquals("alice: a@example.com", tool.text)
        // The final turn is the one that gets sent, not replayed as history.
        assertEquals(LiteRtLmRole.USER, conversation.sentTurn?.role)
        assertEquals("thanks", conversation.sentTurn?.text)
    }

    @Test
    fun `sampling parameters are forwarded and the unsupported ones are dropped`() = runTest {
        backend.load(spec())

        backend.generate(
            request(
                params = SamplingParams(
                    temperature = 0.3f,
                    topP = 0.8f,
                    minP = 0.01f,
                    repeatPenalty = 1.3f,
                    seed = 1234,
                    maxOutputTokens = 256,
                ),
            ),
        )

        val sampler = factory.conversationsOf().single().request.sampler
        assertEquals(0.3, sampler.temperature, 1e-6)
        assertEquals(0.8, sampler.topP, 1e-6)
        assertEquals(1234, sampler.seed)
        // topK is not in SamplingParams, so it comes from the backend's own knob.
        assertEquals(LiteRtLmBackend.DEFAULT_TOP_K, sampler.topK)
    }

    // ================================================================= streaming

    @Test
    fun `a stream emits one callback per delta and then a terminal result`() = runTest {
        backend.load(spec())
        val chunks = mutableListOf<String>()

        val result = backend.generateStreaming(request()) { chunks += it }

        assertEquals(listOf("Hello", ", ", "world"), chunks)
        assertEquals("Hello, world", result.text)
        assertEquals(StopReason.COMPLETED, result.stopReason)
    }

    @Test
    fun `a cumulative runtime does not duplicate its own text`() = runTest {
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            FakeLiteRtLmEngine { it.cumulative = true }
        })
        b.load(spec())
        val chunks = mutableListOf<String>()

        val result = b.generateStreaming(request()) { chunks += it }

        // LiteRT-LM's MessageCallback is specified as carrying the whole reply so
        // far. Appending naively would render "Hello, world" three times over.
        // This assertion is why LiteRtLmDeltaTracker exists.
        assertEquals("Hello, world", result.text)
        assertEquals(listOf("Hello", ", ", "world"), chunks)
    }

    @Test
    fun `every callback arrives before generateStreaming returns`() = runTest {
        backend.load(spec())
        val seen = mutableListOf<String>()

        backend.generateStreaming(request()) { seen += it }

        assertEquals(3, seen.size)
    }

    // ============================================================== cancellation

    @Test
    fun `cancelling mid-stream returns CANCELLED with the partial text intact`() = runTest {
        val started = CountDownLatch(1)
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            FakeLiteRtLmEngine { conv ->
                conv.blockUntilCancelled = true
                conv.onChunkEmitted = { started.countDown() }
            }
        })
        b.load(spec())

        val collected = mutableListOf<String>()
        val worker = Thread {
            runBlocking { b.generateStreaming(request()) { collected += it } }
        }
        worker.start()

        assertTrue("the fake never started streaming", started.await(5, TimeUnit.SECONDS))
        b.cancel() // from "the UI thread", while the generation runs
        worker.join(5_000)

        assertEquals(listOf("Hello"), collected)
        // "cancel sets a flag" and "cancel reaches the runtime" are different
        // claims, and only the second one stops a phone burning battery.
        assertTrue("cancel must reach the runtime", b.isLastGenerationCancelled())
    }

    @Test
    fun `a cancelled generation reports CANCELLED, not COMPLETED`() = runTest {
        val started = CountDownLatch(1)
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            FakeLiteRtLmEngine { conv ->
                conv.blockUntilCancelled = true
                conv.onChunkEmitted = { started.countDown() }
            }
        })
        b.load(spec())

        val result = arrayOfNulls<dev.localintelligence.core.model.GenerationResult>(1)
        val worker = Thread {
            runBlocking { result[0] = b.generate(request()) }
        }
        worker.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        b.cancel()
        worker.join(5_000)

        // A generation that was stopped did not complete, whatever the runtime
        // said on its way out.
        assertEquals(StopReason.CANCELLED, result[0]!!.stopReason)
    }

    @Test
    fun `cancel is safe to call when idle`() = runTest {
        backend.load(spec())

        backend.cancel()
        backend.cancel()

        // Idempotent, and it must not leave a stale flag that stops the next turn.
        assertEquals(StopReason.COMPLETED, backend.generate(request()).stopReason)
    }

    @Test
    fun `cancel before any load is safe`() {
        backend.cancel()
        backend.cancel()
    }

    // =================================================================== unload

    @Test
    fun `unload closes the engine and resets capabilities`() = runTest {
        backend.load(spec())
        val engine = factory.built[0]

        backend.unload()

        assertEquals("unload must close exactly once", 1, engine.closeCount)
        assertEquals(0, backend.capabilities.contextLength)
    }

    @Test
    fun `a backend is re-loadable after unload`() = runTest {
        backend.load(spec())
        backend.unload()

        backend.load(spec())

        assertEquals("a second engine was built", 2, factory.configs.size)
        assertEquals(StopReason.COMPLETED, backend.generate(request()).stopReason)
    }

    @Test
    fun `unloading twice is safe`() = runTest {
        backend.load(spec())
        val engine = factory.built[0]

        backend.unload()
        backend.unload()

        assertEquals("a double unload must not double-close", 1, engine.closeCount)
    }

    @Test
    fun `loading a second model closes the first engine`() = runTest {
        backend.load(spec())
        val first = factory.built[0]

        val second = File(modelRoot, "gemma-3-1b.litertlm").apply { writeText("x") }
        backend.load(spec(path = second))

        assertEquals("the retired engine must be closed", 1, first.closeCount)
        assertEquals(2, factory.configs.size)
    }

    @Test
    fun `every conversation the backend opened is closed`() = runTest {
        backend.load(spec())
        backend.generate(request("one"))
        backend.generate(request("two"))

        val conversations = factory.conversationsOf()
        assertEquals(2, conversations.size)
        for (c in conversations) {
            assertTrue("a conversation leaked", c.isClosed)
        }
    }

    // ============================================================== concurrency

    @Test
    fun `a second load while one is in flight is rejected, not serialized`() = runTest {
        val slow = RecordingLiteRtLmEngineFactory().apply { block = true }
        val b = newBackend(slow)

        val first = Thread { runBlocking { caughtSuspending { b.load(spec()) } } }
        first.start()
        assertTrue("the first load never reached the factory", slow.awaitCall(1))

        val second = caughtSuspending { b.load(spec()) }

        // Rejected, not queued. Swapping the engine under an in-flight
        // initialization would leave the first load writing into a closed handle.
        assertNotNull("the second load must be refused", second)
        assertTrue(
            "message should explain: ${second?.message}",
            second!!.message!!.contains("already in progress"),
        )
        slow.release()
        first.join(5_000)
    }

    @Test
    fun `a load that finished does not block the next one`() = runTest {
        val slow = RecordingLiteRtLmEngineFactory().apply { block = true }
        val b = newBackend(slow)

        val first = Thread { runBlocking { b.load(spec()) } }
        first.start()
        assertTrue("the load never reached the factory", slow.awaitCall(1))
        slow.release()
        first.join(5_000)

        // The in-progress flag has to be cleared in a finally, or one failure
        // would leave the backend permanently unable to load again.
        val second = File(modelRoot, "second.litertlm").apply { writeText("x") }
        b.load(spec(path = second))
        assertEquals(2, slow.configs.size)
    }

    @Test
    fun `generate during a load is refused rather than run against a closing engine`() = runTest {
        val slow = RecordingLiteRtLmEngineFactory()
        val b = newBackend(slow)
        b.load(spec()) // first load, unblocked

        slow.block = true
        val reload = Thread { runBlocking { b.load(spec()) } }
        reload.start()
        // This is the SECOND create call; awaiting the first would race.
        assertTrue("the reload never reached the factory", slow.awaitCall(2))

        // The engine is being replaced. Generating now would decode against a
        // handle that is about to be closed.
        assertEquals(StopReason.ERROR, b.generate(request()).stopReason)

        slow.release()
        reload.join(5_000)
    }

    // ============================================================= capabilities

    @Test
    fun `capabilities are UNKNOWN before a load`() {
        assertEquals(0, backend.capabilities.contextLength)
        assertFalse(backend.capabilities.supportsToolCalling)
    }

    @Test
    fun `tool calling is reported only when the caller declares it`() = runTest {
        val declared = newBackend(factory, supportsToolCalling = true)
        declared.load(spec())
        assertTrue(declared.capabilities.supportsToolCalling)

        val undeclared = newBackend(RecordingLiteRtLmEngineFactory(), supportsToolCalling = false)
        undeclared.load(spec())
        assertFalse(undeclared.capabilities.supportsToolCalling)
    }

    @Test
    fun `vision is never claimed because it is not wired`() = runTest {
        backend.load(spec())
        assertFalse(backend.capabilities.supportsVision)
    }

    @Test
    fun `the backend id is stable and distinct from llamacpp`() {
        assertEquals("litertlm", backend.id)
        assertFalse(backend.id == "llamacpp")
    }

    @Test
    fun `an out-of-range context length is clamped rather than passed through`() = runTest {
        val absurd = newBackend(factory, contextLength = 10_000_000)
        absurd.load(spec())
        // Clamping is not cosmetic: an unclamped maxNumTokens asks the runtime for
        // a KV allocation that never returns and takes the process down with it.
        assertTrue(
            "context=${absurd.capabilities.contextLength}",
            absurd.capabilities.contextLength <= 131_072,
        )

        val tiny = newBackend(RecordingLiteRtLmEngineFactory(), contextLength = 1)
        tiny.load(spec())
        assertTrue(
            "context=${tiny.capabilities.contextLength}",
            tiny.capabilities.contextLength >= 128,
        )
    }

    // ============================================================= token budget

    @Test
    fun `an output budget is enforced even though LiteRT-LM has no max-tokens field`() = runTest {
        val b = newBackend(RecordingLiteRtLmEngineFactory {
            // A runtime that would happily stream forever.
            FakeLiteRtLmEngine { it.deltas = List(500) { "word " } }
        })
        b.load(spec())

        val result = b.generate(
            GenerationRequest(
                messages = listOf(ChatMessage.User("go")),
                params = SamplingParams(maxOutputTokens = 16),
            ),
        )

        assertEquals(StopReason.MAX_TOKENS, result.stopReason)
        assertTrue(
            "output ran past the budget: ${result.text.length} chars",
            result.text.length < 500 * 5,
        )
    }

    @Test
    fun `a generation inside its budget is not mislabelled as MAX_TOKENS`() = runTest {
        backend.load(spec())

        val result = backend.generate(request(params = SamplingParams(maxOutputTokens = 512)))

        assertEquals(StopReason.COMPLETED, result.stopReason)
    }

    // =========================================================== token counting

    @Test
    fun `countTokens works with no model loaded and is biased high`() {
        assertEquals(0, backend.countTokens(""))
        val text = "the quick brown fox jumps over the lazy dog"
        // 43 chars / 4 = 11. Under-counting overflows the context; over-counting
        // only compacts a little early.
        assertTrue("count=${backend.countTokens(text)}", backend.countTokens(text) >= 11)
    }

    @Test
    fun `countTokens charges more for structured output than for prose`() {
        val prose = "a".repeat(100)
        val code = "a\n".repeat(50)
        assertTrue(backend.countTokens(code) > backend.countTokens(prose))
    }
}