package dev.localintelligence.inference.litertlm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [LiteRtLmEngine] that never touches native code.
 *
 * ## Why a fake rather than the real engine
 *
 * `litertlm-android:0.13.1` is Java 21 bytecode and the unit-test JVM is JDK 17,
 * so a test that constructed a real `Engine` would die with
 * `UnsupportedClassVersionError` before its first assertion. See
 * [LiteRtLmEngine]'s KDoc for the full reason.
 *
 * But that is only the mechanical reason. The fake earns its place because it
 * makes the *awkward* cases reachable at all: a runtime that streams
 * cumulatively, a generation cancelled mid-stream, a runtime that reports a
 * terminal event with no text, one that never reports a terminal event, one that
 * would stream forever. None of those can be provoked on demand from a real 1-3B
 * model, and every one of them is a case the backend claims to handle.
 */
class FakeLiteRtLmEngine(
    /** When false, [openConversation] throws, as a real engine would on a bad model. */
    var alive: Boolean = true,
    /**
     * Applied to every conversation this engine opens, before it is handed back.
     * This is the hook every test uses: the plain fake is well-behaved, and a
     * test that needs a pathological runtime configures it here.
     */
    var configure: (FakeLiteRtLmConversation) -> Unit = {},
) : LiteRtLmEngine {

    /** Every conversation opened, in order. */
    val conversations = mutableListOf<FakeLiteRtLmConversation>()

    /** Incremented on every [close]. The "unload released resources" assertion. */
    var closeCount: Int = 0
        private set

    var cancelCount: Int = 0
        private set

    override val isAlive: Boolean get() = alive

    override fun openConversation(
        request: LiteRtLmConversationRequest,
    ): LiteRtLmConversation {
        if (!alive) {
            throw LiteRtLmEngineException("the fake engine was configured dead")
        }
        val conversation = FakeLiteRtLmConversation(request)
        // Read through the property, not the constructor parameter: a subclass or
        // a later assignment to `configure` must still take effect.
        configure(conversation)
        conversations += conversation
        return conversation
    }

    override fun cancel() {
        cancelCount++
    }

    override fun close() {
        closeCount++
        alive = false
    }
}

/**
 * One fake conversation, scripted by the test that owns it.
 *
 * The default — emit [deltas], then report done — is the happy path, so a test
 * that only cares about the happy path never has to know the knobs exist.
 */
class FakeLiteRtLmConversation(
    val request: LiteRtLmConversationRequest,
) : LiteRtLmConversation {

    /** The turn the backend sent. */
    var sentTurn: LiteRtLmTurn? = null
        private set

    var cancelCount: Int = 0
        private set

    var closeCount: Int = 0
        private set

    private val alive = AtomicBoolean(true)
    private val cancelSeen = AtomicBoolean(false)

    /**
     * Chunks to emit, in order. Whether each is a delta or a cumulative snapshot
     * is [cumulative] — both are exercised, because the backend has to handle
     * whichever the real runtime does.
     */
    var deltas: List<String> = listOf("Hello", ", ", "world")

    /**
     * Emit each chunk as the whole answer-so-far, the way LiteRT-LM's
     * `MessageCallback` is specified: every callback carries the full reply.
     *
     * This is the case that makes `LiteRtLmDeltaTracker` necessary. A backend
     * that appended every callback would render "Hello, world" three times over.
     */
    var cumulative: Boolean = false

    /** Report this instead of completing. */
    var failWith: Throwable? = null

    /** Return from [send] without ever reporting a terminal event. */
    var silent: Boolean = false

    /** Throw from [send] synchronously, as a closed conversation would. */
    var throwOnSend: Throwable? = null

    /** Called after each chunk, on the sending thread. */
    var onChunkEmitted: ((Int) -> Unit)? = null

    /**
     * Hold inside [send] until cancelled, so "cancel arrives mid-stream" is a
     * deterministic test rather than a hopeful comment. A real runtime blocks
     * here in native decode.
     */
    var blockUntilCancelled: Boolean = false

    private val released = CountDownLatch(1)

    override val isAlive: Boolean get() = alive.get()

    override fun send(turn: LiteRtLmTurn, listener: LiteRtLmListener) {
        sentTurn = turn
        throwOnSend?.let {
            listener.onError(it)
            return
        }
        if (silent) return

        val accumulated = StringBuilder()
        deltas.forEachIndexed { index, chunk ->
            if (cancelSeen.get()) return@forEachIndexed
            if (cumulative) {
                accumulated.append(chunk)
                listener.onDelta(accumulated.toString())
            } else {
                listener.onDelta(chunk)
            }
            onChunkEmitted?.invoke(index)
            if (blockUntilCancelled) released.await(2, TimeUnit.SECONDS)
        }

        // A cancelled conversation still reports a terminal event, because a real
        // one does, and the backend's CANCELLED path has to cope with it.
        if (failWith != null && !cancelSeen.get()) {
            listener.onError(failWith!!)
            return
        }
        listener.onDone()
    }

    override fun cancel() {
        cancelCount++
        cancelSeen.set(true)
        released.countDown()
    }

    override fun close() {
        closeCount++
        alive.set(false)
    }

    /** True once the backend closed this conversation. */
    val isClosed: Boolean get() = closeCount > 0
}

/**
 * A factory that records every [LiteRtLmEngineConfig] it was asked to build.
 *
 * This is how a test asserts what the backend actually told the runtime — the
 * context it allocated, the path it resolved, whether it asked for benchmarking —
 * which is the only way to test the mapping without a model on disk.
 */
class RecordingLiteRtLmEngineFactory(
    /** Return an engine, or throw to simulate a load failure. */
    private val produce: (LiteRtLmEngineConfig) -> LiteRtLmEngine = {
        FakeLiteRtLmEngine()
    },
) : LiteRtLmEngineFactory {

    val configs = mutableListOf<LiteRtLmEngineConfig>()

    /** Engines handed out, so a test can assert the retired one was closed. */
    val built = mutableListOf<FakeLiteRtLmEngine>()

    /** When set, [create] blocks at the gate until [release]. */
    var block: Boolean = false
    private val gate = CountDownLatch(1)

    /**
     * How many times [create] has been entered.
     *
     * A plain `CountDownLatch` would be wrong here: it is already counted down by
     * the first load, so a second `awaitEntered()` would return immediately and
     * the test would race the thread it is trying to observe. Tests that need to
     * watch the Nth call await [awaitCall].
     */
    private val calls = AtomicInteger(0)

    override fun create(config: LiteRtLmEngineConfig): LiteRtLmEngine {
        configs += config
        calls.incrementAndGet()
        if (block) gate.await(5, TimeUnit.SECONDS)
        val engine = produce(config)
        if (engine is FakeLiteRtLmEngine) built += engine
        return engine
    }

    /**
     * Blocks until [create] has been entered at least [n] times (1-based), so a
     * test can wait for a specific call rather than the first one.
     */
    fun awaitCall(n: Int = 1): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (calls.get() < n) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(5)
        }
        return true
    }

    fun release() = gate.countDown()
}

/** Runs [block], returning the throwable it raised, or null if it raised nothing. */
internal fun caught(block: () -> Unit): Throwable? = try {
    block()
    null
} catch (t: Throwable) {
    t
}

/**
 * The suspending form of [caught].
 *
 * `load` is suspend, so the throwing assertions cannot use the blocking helper.
 * [CancellationException] is deliberately re-thrown: a cancelled load was not a
 * failure, and swallowing it here would mask real structured-concurrency bugs.
 */
internal suspend fun caughtSuspending(block: suspend () -> Unit): Throwable? = try {
    block()
    null
} catch (c: kotlinx.coroutines.CancellationException) {
    throw c
} catch (t: Throwable) {
    t
}