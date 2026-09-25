package dev.localintelligence.inference.litertlm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one file in this package that touches Google's LiteRT-LM runtime.
 *
 * ## Why the indirection is not over-engineering
 *
 * `litertlm-android:0.13.1` is Java 21 bytecode. This module compiles on
 * `jvmToolchain(17)` and unit-tests on a JDK 17 JVM, which cannot *load* a class
 * file above version 61. Every LiteRT-LM reference in the package is therefore
 * confined to this adapter, and the test suite substitutes
 * [FakeLiteRtLmEngine]. See [LiteRtLmEngine] for the full rationale.
 *
 * The other reason is the same one that put [dev.localintelligence.android.inference.LlamaBridge]
 * in its own file: this is the entire native surface. When LiteRT-LM changes its
 * API, this is the only file that has to change, and everything above it is
 * tested.
 *
 * ## Threads
 *
 * LiteRT-LM's `sendMessageAsync` is fire-and-forget — it hands the message to
 * native code and returns immediately, with results arriving on a
 * runtime-owned thread. `send` below therefore blocks on a latch, because the
 * frozen `ModelBackend.generate` is suspending and the caller expects a result
 * when it returns. The latch is what turns a callback API into a suspending one,
 * and it is bounded so a runtime that never fires a terminal event cannot hang a
 * phone thread forever.
 */
internal class NativeLiteRtLmEngine(
    private val engine: Engine,
) : LiteRtLmEngine {

    override val isAlive: Boolean
        get() = try {
            engine.isInitialized()
        } catch (_: Throwable) {
            // A native handle that has been torn down under us is not alive, and
            // asking must not be the thing that throws.
            false
        }

    override fun openConversation(request: LiteRtLmConversationRequest): LiteRtLmConversation {
        val config = ConversationConfig(
            systemInstruction = request.systemInstruction?.let { Contents.of(it) },
            initialMessages = request.history.map { it.toNativeMessage() },
            samplerConfig = SamplerConfig(
                topK = request.sampler.topK,
                topP = request.sampler.topP,
                temperature = request.sampler.temperature,
                seed = request.sampler.seed,
            ),
            // Tool calling is left off. The frozen GenerationRequest carries tool
            // NAMES, not schemas, so there is nothing valid to hand the runtime's
            // ToolManager, and letting it auto-execute would bypass
            // AgentController's own tool loop entirely.
            tools = emptyList(),
            automaticToolCalling = false,
        )
        val conversation = engine.createConversation(config)
        return NativeLiteRtLmConversation(conversation)
    }

    override fun cancel() {
        // The engine holds no cancel of its own -- LiteRT-LM cancels per
        // conversation, and the backend holds the reference to the active one. So
        // this is deliberately a no-op rather than a cached no-op that would go
        // stale: the backend is the one that knows which conversation is live.
    }

    override fun close() {
        try {
            if (engine.isInitialized()) engine.close()
        } catch (e: Throwable) {
            // Closing a native engine twice throws from the runtime. Teardown is
            // best-effort and must not propagate into the caller's result.
            Log.w(TAG, "LiteRT-LM engine close failed", e)
        }
    }

    private fun LiteRtLmTurn.toNativeMessage(): Message = when (role) {
        LiteRtLmRole.SYSTEM -> Message.system(Contents.of(text))
        LiteRtLmRole.USER -> Message.user(Contents.of(text))
        LiteRtLmRole.MODEL -> Message.model(Contents.of(text))
        // LiteRT-LM models a tool result as a named response. Passing the name
        // through matters: without it the model cannot tell which tool answered,
        // and a two-tool conversation becomes guesswork.
        LiteRtLmRole.TOOL -> Message.tool(Contents.of(Content.ToolResponse(toolName.orEmpty(), text)))
    }

    companion object {
        private const val TAG = "LiteRtLmEngine"

        /**
         * How long [NativeLiteRtLmConversation.send] waits for a terminal event.
         *
         * Bounded because a phone thread that never returns is worse than a wrong
         * result: it is an ANR. Ten minutes is far beyond any legitimate
         * generation on a 1-4B — a 4096-token answer at 10 tok/s is under seven
         * minutes — so tripping this means the runtime is wedged, and reporting
         * ERROR is the correct answer.
         */
        const val TERMINAL_TIMEOUT_MINUTES = 10L

        /**
         * Builds a real engine for [config].
         *
         * `enableBenchmark` is set here, before any engine is constructed, because
         * LiteRT-LM reads it exactly once at construction. Without it the runtime
         * reports no prefill or decode timings and the whole point of adding a
         * second backend — comparing tok/s — is lost.
         *
         * @throws LiteRtLmEngineException if the model cannot be loaded. Never
         *     throws the raw `LiteRtLmJniException`, so a caller does not need to
         *     know that class exists to handle a load failure.
         */
        fun build(config: LiteRtLmEngineConfig): LiteRtLmEngine {
            applyExperimentalFlags()
            val engineConfig = EngineConfig(
                modelPath = config.modelPath,
                backend = Backend.CPU(numOfThreads = config.numThreads),
                maxNumTokens = config.contextLength,
                cacheDir = config.cacheDir,
            )
            val created = try {
                Engine(engineConfig)
            } catch (e: Throwable) {
                throw LiteRtLmEngineException(
                    "could not construct a LiteRT-LM engine for ${config.modelPath}: " +
                        (e.message ?: e::class.java.simpleName),
                    e,
                )
            }
            try {
                // Documented as taking up to ten seconds; must never be on main.
                created.initialize()
            } catch (e: Throwable) {
                closeQuietly(created)
                throw LiteRtLmEngineException(
                    "LiteRT-LM could not load ${config.modelPath}: " +
                        (e.message ?: e::class.java.simpleName),
                    e,
                )
            }
            return NativeLiteRtLmEngine(created)
        }

        /**
         * Builds a factory, so a caller can hold one without depending on the
         * default binding.
         */
        fun defaultFactory(): LiteRtLmEngineFactory = LiteRtLmEngineFactory { build(it) }

        @OptIn(ExperimentalApi::class)
        private fun applyExperimentalFlags() {
            // Benchmark first and unconditionally: the reason this backend exists
            // is to produce comparable numbers against llama.cpp.
            ExperimentalFlags.enableBenchmark = true
            // Constrained decoding is a process-global switch read when a
            // conversation is created. It is left at its default because it forces
            // tool-call JSON, and this backend reports supportsGrammar = false --
            // claiming constrained output while not honouring a GBNF would be the
            // exact lie the frozen interface warns against.
        }

        private fun closeQuietly(closeable: AutoCloseable) {
            try {
                closeable.close()
            } catch (_: Throwable) {
                // Already reported, or nothing left to report.
            }
        }
    }
}

/** One in-flight generation over a native LiteRT-LM conversation. */
internal class NativeLiteRtLmConversation(
    private val conversation: Conversation,
) : LiteRtLmConversation {

    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override val isAlive: Boolean
        get() = try {
            conversation.isAlive
        } catch (_: Throwable) {
            false
        }

    override fun send(turn: LiteRtLmTurn, listener: LiteRtLmListener) {
        val done = CountDownLatch(1)
        val failed = AtomicReference<Throwable?>(null)

        val callback = object : com.google.ai.edge.litertlm.MessageCallback {
            override fun onMessage(message: Message) {
                // LiteRT-LM's callback carries the message as it stands. The
                // tracker in the backend decides whether that is a delta or a
                // cumulative snapshot, so the whole text is forwarded here rather
                // than guessing at this layer.
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) listener.onDelta(text)
            }

            override fun onDone() {
                listener.onDone()
                done.countDown()
            }

            override fun onError(throwable: Throwable) {
                failed.set(throwable)
                listener.onError(throwable)
                done.countDown()
            }
        }

        try {
            conversation.sendMessageAsync(Contents.of(turn.text), callback)
        } catch (e: Throwable) {
            // Thrown synchronously for a closed conversation or empty content.
            failed.set(e)
            listener.onError(e)
            done.countDown()
        }

        val finished = done.await(
            NativeLiteRtLmEngine.TERMINAL_TIMEOUT_MINUTES,
            TimeUnit.MINUTES,
        )
        if (!finished) {
            // The runtime never reported a terminal event. Cancel so the native
            // decode stops, and report the failure rather than returning whatever
            // partial text happened to arrive as a completed answer.
            cancel()
            listener.onError(
                LiteRtLmEngineException(
                    "the LiteRT-LM runtime produced no terminal event within " +
                        "${NativeLiteRtLmEngine.TERMINAL_TIMEOUT_MINUTES} minutes",
                ),
            )
        }
        if (cancelled.get()) cancel()
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        try {
            conversation.cancelProcess()
        } catch (e: Throwable) {
            Log.w(TAG, "LiteRT-LM cancel failed", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            conversation.close()
        } catch (e: Throwable) {
            Log.w(TAG, "LiteRT-LM conversation close failed", e)
        }
    }

    private companion object {
        const val TAG = "LiteRtLmConv"
    }
}

/** The default [LiteRtLmEngineFactory]: real LiteRT-LM, on a device. */
internal val LiteRtLmEngineFactoryProvider: LiteRtLmEngineFactory =
    LiteRtLmEngineFactory { config -> NativeLiteRtLmEngine.build(config) }
