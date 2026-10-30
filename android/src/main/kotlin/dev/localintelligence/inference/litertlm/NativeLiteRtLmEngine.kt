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
import dev.localintelligence.core.model.AcceleratorKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    companion object {
        private const val TAG = "LiteRtLmEngine"

        /**
         * How long [NativeLiteRtLmConversation.send] waits without any runtime event
         * before it treats the runtime as wedged.
         *
         * This is a silence limit, not a limit on the whole reply. A long answer on a
         * slow phone streams for many minutes and is correct; a runtime that has said
         * nothing for five minutes (prefill included) is not making progress. Every
         * callback resets the clock. A cap on total time would discard a reply that
         * was still arriving and on track to finish. Five minutes, not less: the
         * runtime reports nothing during prefill, and a 4K-token prompt at 15 tok/s
         * on a slow phone's CPU takes about 4.5 minutes before the first event.
         */
        const val SILENCE_TIMEOUT_MINUTES = 5L

        /**
         * How often [NativeLiteRtLmConversation.send] re-checks the latch.
         *
         * Short enough that a cancel is felt as a stop rather than a lag, long
         * enough that a generation is not spinning. 200 ms costs 5 wakeups a
         * second on a thread that is otherwise blocked in native decode.
         *
         * `internal` rather than `private`: [NativeLiteRtLmConversation] is a
         * separate top-level class, not a nested one, so a private companion
         * member is not in its scope.
         */
        internal const val POLL_INTERVAL_MS = 200L

        /**
         * How long to wait for the runtime to acknowledge a cancel before
         * returning the partial answer anyway.
         *
         * A cancel that the runtime never confirms is not a reason to hold the
         * calling thread: the text produced so far is already in the tracker's
         * hands and the backend reports it with `StopReason.CANCELLED`. Two
         * seconds is long enough for a decode to unwind cleanly and short enough
         * that a wedged runtime does not read as a hung app.
         *
         * `internal` for the same reason as [POLL_INTERVAL_MS].
         */
        internal const val CANCEL_GRACE_MS = 2_000L

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
                backend = config.accelerator.toNativeBackend(config.numThreads, config.nativeLibraryDir),
                maxNumTokens = config.contextLength,
                cacheDir = config.cacheDir,
            )
            val created = try {
                Engine(engineConfig)
            } catch (e: Throwable) {
                throw LiteRtLmEngineException(
                    "could not construct a LiteRT-LM engine for ${config.modelPath} " +
                        "on ${config.accelerator.name}: " +
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
                    "LiteRT-LM could not load ${config.modelPath} on " +
                        "${config.accelerator.name}: " +
                        (e.message ?: e::class.java.simpleName),
                    e,
                )
            }
            return NativeLiteRtLmEngine(created)
        }

        /**
         * Maps a resolved [AcceleratorKind] onto LiteRT-LM's `Backend` sealed class.
         *
         * ## Why this mapping is a `when` and not a lookup
         *
         * The three cases are not symmetric and pretending they are would hide the
         * one that matters:
         *
         * - [AcceleratorKind.CPU] carries [LiteRtLmEngineConfig.numThreads] and
         *   nothing else.
         * - [AcceleratorKind.GPU] takes **no** parameters at all. Verified by
         *   `javap` on the shipped 0.13.1 AAR: `Backend$GPU` has a single no-arg
         *   constructor and no properties. There is no thread count, no
         *   "prefer low power" flag, nothing to get wrong — and equally, no way to
         *   ask the GPU backend for anything the class does not offer.
         * - [AcceleratorKind.NPU] takes only [LiteRtLmEngineConfig.nativeLibraryDir].
         *   Passing null would construct `Backend.NPU("")` (its default) and the
         *   runtime would log `Dispatch library directory is not set` and fall back
         *   internally, silently. Passing the real dir is the only difference
         *   between a working NPU and a slow one, so it is threaded through rather
         *   than defaulted away.
         *
         * LiteRT-LM 0.13.1 also has a `Backend.GOOGLE_TENSOR` in its sources, but
         * it is **not** in the published 0.13.1 artifact — `javap` lists exactly
         * `Backend$CPU`, `Backend$GPU` and `Backend$NPU`. It is therefore not
         * selectable here, and pretending otherwise would mean a build that only
         * fails on a Pixel.
         */
        private fun AcceleratorKind.toNativeBackend(
            numThreads: Int,
            nativeLibraryDir: String?,
        ): Backend = when (this) {
            AcceleratorKind.CPU -> Backend.CPU(numOfThreads = numThreads)
            AcceleratorKind.GPU -> Backend.GPU()
            AcceleratorKind.NPU -> Backend.NPU(nativeLibraryDir.orEmpty())
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

/**
 * A native LiteRT-LM conversation, possibly kept across requests.
 *
 * Each `sendMessageAsync` appends one message to the runtime's history and
 * prefills only that message on top of the KV cache the conversation already
 * holds; the reply is appended to the history when it completes. That is what
 * makes keeping one conversation cheaper than rebuilding it per request.
 * Checked against the 0.17.1 Kotlin API and `runtime/conversation/conversation.cc`
 * at tag v0.17.1.
 */
internal class NativeLiteRtLmConversation(
    private val conversation: Conversation,
) : LiteRtLmConversation {

    /** Sticky by design: see [LiteRtLmConversation.cancel]. */
    private val cancelled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * True from the start of a send until the runtime's own terminal event. It can be
     * true after [send] has returned: the runtime may still be decoding after a
     * cancel grace or the silence cap. While it is, [close] is deferred.
     */
    private val sending = AtomicBoolean(false)
    private val closeRequested = AtomicBoolean(false)

    /** Nanotime of the last runtime event, or of the send's start. The silence cap reads it. */
    private val lastActivityNanos = AtomicLong(0L)

    override val isAlive: Boolean
        get() = try {
            conversation.isAlive
        } catch (_: Throwable) {
            false
        }

    override val tokenCount: Int?
        get() = try {
            conversation.getTokenCount()
        } catch (_: Throwable) {
            // A closed conversation or a runtime that cannot count: unknown,
            // not zero, so the caller does not read it as an empty context.
            null
        }

    override fun send(turn: LiteRtLmTurn, listener: LiteRtLmListener) {
        val done = CountDownLatch(1)
        val gate = SendGate(listener)

        val callback = object : com.google.ai.edge.litertlm.MessageCallback {
            override fun onMessage(message: Message) {
                lastActivityNanos.set(System.nanoTime())
                // LiteRT-LM's callback carries the message as it stands. The
                // tracker in the backend decides whether that is a delta or a
                // cumulative snapshot, so the whole text is forwarded here rather
                // than guessing at this layer.
                val text = message.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }
                if (text.isNotEmpty()) gate.delta(text)
            }

            override fun onDone() {
                gate.done()
                terminalReached()
                done.countDown()
            }

            override fun onError(throwable: Throwable) {
                gate.error(throwable)
                terminalReached()
                done.countDown()
            }
        }

        lastActivityNanos.set(System.nanoTime())
        sending.set(true)
        try {
            conversation.sendMessageAsync(turn.toNativeMessage(), callback)
        } catch (e: Throwable) {
            // Thrown synchronously for a closed conversation or empty content. No
            // runtime event follows, so there is nothing left to wait for.
            gate.error(e)
            terminalReached()
            done.countDown()
        }

        // Poll rather than take the latch in one blocking `await`. A single
        // await of the full timeout makes a cancelled generation indistinguishable
        // from a stuck one: `cancelProcess()` is a request, and if the runtime
        // does not answer it with a terminal event the caller blocks for the whole
        // window. On a phone that is a stop button that does nothing for ten
        // minutes, which is the freeze this polling exists to prevent.
        //
        // The constants are on NativeLiteRtLmEngine's companion, qualified
        // because this class is a separate top-level declaration.
        val silenceNanos = NativeLiteRtLmEngine.SILENCE_TIMEOUT_MINUTES * 60L * 1_000_000_000L
        var cancelObservedAt = 0L
        while (true) {
            if (done.await(
                    NativeLiteRtLmEngine.POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS,
                )
            ) {
                break
            }

            if (System.nanoTime() - lastActivityNanos.get() >= silenceNanos) {
                // The runtime has gone silent. Cancel so the native decode stops,
                // and report the failure rather than returning whatever partial
                // text happened to arrive as a completed answer.
                cancel()
                gate.close()
                listener.onError(
                    LiteRtLmEngineException(
                        "the LiteRT-LM runtime sent nothing for " +
                            "${NativeLiteRtLmEngine.SILENCE_TIMEOUT_MINUTES} minutes",
                    ),
                )
                break
            }

            // Cancel was requested but the runtime has not acknowledged it. Give
            // it a short grace period to unwind its own decode, then stop
            // waiting: the user asked to stop, and continuing to block the calling
            // thread to preserve a decode they just cancelled is the wrong trade.
            if (cancelled.get()) {
                if (cancelObservedAt == 0L) {
                    cancelObservedAt = System.nanoTime()
                    cancel()
                } else if (
                    System.nanoTime() - cancelObservedAt >
                    NativeLiteRtLmEngine.CANCEL_GRACE_MS * 1_000_000L
                ) {
                    gate.close()
                    listener.onError(
                        LiteRtLmEngineException(
                            "cancelled, but the LiteRT-LM runtime did not acknowledge " +
                                "the cancel within ${NativeLiteRtLmEngine.CANCEL_GRACE_MS} ms; " +
                                "returning the partial answer",
                        ),
                    )
                    break
                }
            }
        }
        // Nothing reaches the caller after this point. A late runtime event is
        // dropped by the gate, and the conversation stays open until the runtime's
        // terminal event (see [close]).
        gate.close()
        if (cancelled.get()) cancel()
    }

    /** The runtime's terminal event has arrived: a deferred [close] can now run. */
    private fun terminalReached() {
        sending.set(false)
        if (closeRequested.get()) closeNow()
    }

    override fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        try {
            conversation.cancelProcess()
        } catch (e: Throwable) {
            Log.w(TAG, "LiteRT-LM cancel failed", e)
        }
    }

    /**
     * Closes the native conversation. While the runtime may still be decoding on it
     * (a send that returned after a cancel grace or the silence cap, with no
     * terminal event yet), the close is deferred to that event: closing under a live
     * decode is a use-after-free in the runtime.
     */
    override fun close() {
        closeRequested.set(true)
        if (sending.get()) return
        closeNow()
    }

    private fun closeNow() {
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

/**
 * Forwards one send's events to its listener until the send returns.
 *
 * The runtime calls back on its own thread, and can do so after [send] has already
 * reported its result. [close] takes the same lock as every forward, so once it
 * returns no event can reach the listener, and the caller can read what it
 * collected without a race.
 */
private class SendGate(private val listener: LiteRtLmListener) {
    private var open = true

    @Synchronized
    fun delta(text: String) {
        if (open) listener.onDelta(text)
    }

    @Synchronized
    fun done() {
        if (open) listener.onDone()
    }

    @Synchronized
    fun error(throwable: Throwable) {
        if (open) listener.onError(throwable)
    }

    @Synchronized
    fun close() {
        open = false
    }
}

/** The default [LiteRtLmEngineFactory]: real LiteRT-LM, on a device. */
internal val LiteRtLmEngineFactoryProvider: LiteRtLmEngineFactory =
    LiteRtLmEngineFactory { config -> NativeLiteRtLmEngine.build(config) }

internal fun LiteRtLmTurn.toNativeMessage(): Message = when (role) {
    LiteRtLmRole.SYSTEM -> Message.system(Contents.of(text))
    LiteRtLmRole.USER -> Message.user(Contents.of(text))
    LiteRtLmRole.MODEL -> Message.model(Contents.of(text))
    // LiteRT-LM models a tool result as a named response. Passing the name
    // through matters: without it the model cannot tell which tool answered,
    // and a two-tool conversation becomes guesswork.
    LiteRtLmRole.TOOL -> Message.tool(Contents.of(Content.ToolResponse(toolName.orEmpty(), text)))
}
