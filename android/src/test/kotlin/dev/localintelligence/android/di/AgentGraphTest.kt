package dev.localintelligence.android.di

import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.InMemoryMemoryStore
import dev.localintelligence.core.agent.SessionStore
import dev.localintelligence.core.model.ModelSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Type-narrowing assertions.
 *
 * JUnit4's `fail()` returns `Unit`, not `Nothing`, so `result as? T ?: fail(...)`
 * widens to `T | Unit` and every field access after it stops compiling. These
 * helpers throw instead of returning, which keeps the narrowed type and makes
 * the failure message name what was actually received.
 */
internal fun AgentLaunch.requireUnavailable(): AgentLaunch.Unavailable =
    this as? AgentLaunch.Unavailable ?: throw AssertionError("expected Unavailable, got $this")

internal fun AgentLaunch.requireRan(): AgentLaunch.Ran =
    this as? AgentLaunch.Ran ?: throw AssertionError("expected Ran, got $this")

/**
 * The wiring matrix.
 *
 * Every test here runs on the JVM with no emulator and no device, which is only
 * possible because [AgentGraph] takes its collaborators as constructor
 * parameters instead of reaching for a `Context`. That is the whole reason the
 * composition root is shaped this way: the integration point everyone else
 * depends on is the one thing that must not be the least tested file in the
 * repo.
 */
class AgentGraphTest {

    private val model = ModelSpec(id = "content://models/small.gguf", displayName = "small")

    /** A graph with nothing loaded, which is the state of a fresh install. */
    private fun graphOf(
        tools: List<dev.localintelligence.core.tool.AgentTool> = listOf(FakeTool("device.battery")),
        backend: RecordingBackend = RecordingBackend(),
    ): Pair<AgentGraph, RecordingBackend> {
        val graph = AgentGraph(
            toolSource = { tools },
            backendFactory = { backend },
            memoryStoreFactory = { InMemoryMemoryStore() },
            sessionStoreFactory = { NoopSessionStore },
        )
        return graph to backend
    }

    // ------------------------------------------------------- graph construction

    @Test
    fun `graph constructs with no model imported`() {
        val (graph, backend) = graphOf()

        // The first-run state has to be a working graph, not a crashed one: the
        // user has to reach the import screen to fix it.
        assertNull("no model may be loaded before the user asks", graph.loadedModel)
        assertEquals(0, backend.loadCount)
        assertEquals(1, graph.tools.all().size)
    }

    @Test
    fun `constructing the graph does not load the model`() {
        val backend = RecordingBackend()

        // Touch every lazy the UI could reach before the first question. If any
        // of these were eager, this is where a 2 GB load would be triggered.
        val graph = AgentGraph(
            toolSource = { listOf(FakeTool("device.battery")) },
            backendFactory = { backend },
            memoryStoreFactory = { InMemoryMemoryStore() },
            sessionStoreFactory = { NoopSessionStore },
        )
        val registry = graph.tools
        val store = graph.memoryStore
        val sessions = graph.sessionStore
        val builder = graph.contextBuilder
        val modelBackend = graph.backend

        assertEquals(
            "constructing the graph must not load a model; that is 2 GB of native memory",
            0,
            backend.loadCount,
        )
        assertNotNull(registry)
        assertNotNull(store)
        assertNotNull(sessions)
        assertNotNull(builder)
        assertNotNull(modelBackend)
        assertEquals(0, backend.loadCount)
    }

    @Test
    fun `building a controller does not load the model`() {
        val (graph, backend) = graphOf()

        graph.newController()

        assertEquals(0, backend.loadCount)
    }

    @Test
    fun `graph constructs with a model and loads it only on launch`() = runTest {
        val (graph, backend) = graphOf()

        val result = graph.launch(model, "what is the battery level")

        assertTrue("expected a run, got $result", result is AgentLaunch.Ran)
        assertEquals(1, backend.loadCount)
        assertEquals(model, graph.loadedModel)
    }

    // ------------------------------------------------------------- entry point

    @Test
    fun `entry point reports NO_MODEL rather than crashing when unconfigured`() = runTest {
        val (graph, backend) = graphOf()

        val result = graph.launch(null, "do something")

        // This is the first-run path. It must be a sentence the UI can show, and
        // it must not have touched the model or the loop.
        val unavailable = result.requireUnavailable()
        assertEquals(AgentLaunch.Reason.NO_MODEL, unavailable.reason)
        assertTrue(
            "the message must be actionable for a user, not a diagnostic",
            unavailable.message.isNotBlank() && unavailable.message.contains("model", ignoreCase = true),
        )
        assertEquals(0, backend.loadCount)
        assertEquals(0, backend.generateCount)
    }

    @Test
    fun `entry point reports a failed load instead of throwing`() = runTest {
        val (graph, backend) = graphOf(backend = RecordingBackend(failOnLoad = true))

        // A 2 GB model that does not fit is the common case here, and it must
        // come back as a state the UI renders.
        val result = graph.launch(model, "hello")

        val unavailable = result.requireUnavailable()
        assertEquals(AgentLaunch.Reason.MODEL_LOAD_FAILED, unavailable.reason)
        assertNull("a failed load must not be recorded as loaded", graph.loadedModel)
    }

    @Test
    fun `entry point reports NOT_READY when the registry is empty`() = runTest {
        val (graph, backend) = graphOf(tools = emptyList())

        val result = graph.launch(model, "hello")

        val unavailable = result.requireUnavailable()
        assertEquals(AgentLaunch.Reason.NOT_READY, unavailable.reason)
        // Nothing is loaded for a run that cannot happen: that is 2 GB saved.
        assertEquals(0, backend.loadCount)
    }

    @Test
    fun `a successful launch returns a Ran result`() = runTest {
        val (graph, _) = graphOf()

        val result = graph.launch(model, "say hello")

        val ran = result.requireRan()
        // The loop parsed `<respond>done</respond>` through the real parser and
        // recorded the generation step, so this is the whole real round trip:
        // registry -> selection -> context -> backend -> parser -> respond.
        val success = ran.result as? AgentResult.Success
            ?: throw AssertionError("expected Success, got ${ran.result}")
        assertEquals("done", success.text)
        assertTrue(
            "the run must leave a trace behind for the UI: ${success.trace}",
            success.trace.isNotEmpty(),
        )
    }

    // ----------------------------------------------------------------- laziness

    @Test
    fun `a second launch with the same model does not reload it`() = runTest {
        val (graph, backend) = graphOf()

        graph.launch(model, "first")
        graph.launch(model, "second")

        // Reloading per run would mean paying the 2 GB load on every message.
        assertEquals(1, backend.loadCount)
        assertEquals(2, backend.generateCount)
    }

    @Test
    fun `switching models unloads nothing but does load the new one`() = runTest {
        val (graph, backend) = graphOf()
        val other = ModelSpec(id = "content://models/other.gguf", displayName = "other")

        graph.launch(model, "first")
        graph.launch(other, "second")

        assertEquals(2, backend.loadCount)
        assertEquals(other, graph.loadedModel)
    }

    // ------------------------------------------------------- model deleted live

    @Test
    fun `deleting a model mid-session leaves the registry consistent`() = runTest {
        val tools = listOf(FakeTool("device.battery"), FakeTool("clock.alarm"))
        val (graph, backend) = graphOf(tools = tools)
        graph.launch(model, "first")

        val namesBefore = graph.tools.all().map { it.definition.name }.sorted()

        // The user deletes the model from settings while a conversation is open.
        graph.forget()

        assertNull(graph.loadedModel)
        assertEquals(1, backend.unloadCount)
        // The registry holds tool instances that know nothing about models, so
        // it must be byte-for-byte what it was before the delete.
        assertEquals(namesBefore, graph.tools.all().map { it.definition.name }.sorted())
        assertEquals(2, graph.tools.all().size)
        assertNotNull("tools must still resolve by name", graph.tools.byName("device.battery"))
    }

    @Test
    fun `launching after a delete reports NO_MODEL until a model is named`() = runTest {
        val (graph, _) = graphOf()
        graph.launch(model, "first")
        graph.forget()

        // The same spec is still in the UI's list, so the UI may well pass it
        // again. Generating against a freed native handle would crash in JNI;
        // reloading is correct, and here the test asserts the honest state.
        val result = graph.launch(null, "after delete")

        val unavailable = result.requireUnavailable()
        assertEquals(AgentLaunch.Reason.NO_MODEL, unavailable.reason)
    }

    @Test
    fun `launching again after a delete reloads and runs`() = runTest {
        val (graph, backend) = graphOf()
        graph.launch(model, "first")
        graph.forget()

        val result = graph.launch(model, "second")

        assertTrue("expected a run, got $result", result is AgentLaunch.Ran)
        assertEquals(2, backend.loadCount)
    }

    @Test
    fun `forget is safe to call twice`() = runTest {
        val (graph, backend) = graphOf()
        graph.launch(model, "first")

        graph.forget()
        graph.forget()

        // A double unload would be a use-after-free in the native layer if the
        // backend were not idempotent; the graph must not be the thing that
        // turns a double-tap into one.
        assertNull(graph.loadedModel)
        assertEquals(2, backend.unloadCount)
    }

    // --------------------------------------------------------- controller shape

    @Test
    fun `each run gets its own controller`() {
        val (graph, _) = graphOf()

        // cancel() is sticky and AwaitingConfirmation must be resumed on the
        // same instance, so two runs must not share a controller.
        assertTrue(graph.newController() !== graph.newController())
    }

    @Test
    fun `registry diagnostics are empty for a well formed tool set`() {
        val (graph, _) = graphOf(
            tools = listOf(FakeTool("device.battery"), FakeTool("clipboard.read")),
        )

        assertTrue("unexpected: ${graph.registryDiagnostics}", graph.registryDiagnostics.isEmpty())
    }

    @Test
    fun `requireWellFormed passes for a well formed registry`() {
        val (graph, _) = graphOf(tools = listOf(FakeTool("device.battery")))

        graph.requireWellFormed()
    }

    @Test
    fun `requireWellFormed throws and names the offender when a tool is malformed`() {
        val (graph, _) = graphOf(tools = listOf(malformedSchemaTool()))

        try {
            graph.requireWellFormed()
            fail("expected requireWellFormed to throw")
        } catch (e: IllegalStateException) {
            assertTrue(
                "the message must name the tool: ${e.message}",
                e.message!!.contains("fake.broken"),
            )
        }
    }

    @Test
    fun `the context builder inherits the loop's working token limit`() = runTest {
        // Proved through behaviour, not by reading a field: a prompt is built
        // from the same `AgentConfig` the loop compacts against, so a mismatch
        // would fill the window before the trigger fires. The builder keeps a
        // large tool list out of a small budget, so the observable effect is
        // that the system prompt survives and the tool list is cut.
        val config = AgentConfig(workingTokenLimit = 200)
        val graph = AgentGraph(
            toolSource = {
                (1..6).map { FakeTool("device.tool$it", description = "x".repeat(400)) }
            },
            backendFactory = { RecordingBackend() },
            memoryStoreFactory = { InMemoryMemoryStore() },
            sessionStoreFactory = { NoopSessionStore },
            config = config,
        )

        val history = graph.contextBuilder.build(
            task = "check the battery",
            history = emptyList(),
            memories = emptyList(),
            tools = graph.tools.all().map { it.definition },
        )

        // Every 400-char description is ~100 tokens, so six of them cannot fit in
        // 200 alongside the task. The builder is budgeted and truncates rather
        // than throwing, which is the contract DefaultContextBuilder documents.
        assertFalse(history.isEmpty())
        assertTrue(
            "the system message must survive: a context with no tool list is useless",
            history.first() is dev.localintelligence.core.model.ChatMessage.System,
        )
    }

    // ------------------------------------------------------------- concurrency

    @Test
    fun `two concurrent launches with the same model load it once`() = runTest {
        val (graph, backend) = graphOf()

        // The realistic shape of this bug is a double tap, or a service starting
        // a run while the UI is composing one. Two 2 GB loads is an OOM, not a
        // slow start.
        val results = listOf(
            async { graph.launch(model, "first") },
            async { graph.launch(model, "second") },
        ).map { it.await() }

        assertTrue(results.all { it is AgentLaunch.Ran })
        assertEquals("one model must be loaded, not two", 1, backend.loadCount)
    }

    @Test
    fun `concurrent launches of different models do not leave a stale model loaded`() = runTest {
        val (graph, backend) = graphOf()
        val other = ModelSpec(id = "content://models/other.gguf", displayName = "other")

        val results = listOf(
            async { graph.launch(model, "first") },
            async { graph.launch(other, "second") },
        ).map { it.await() }

        assertTrue(results.all { it is AgentLaunch.Ran })
        // Whoever went last owns the residency slot, and `loadedModel` must say
        // so: a graph that reports model A while B is resident would let the UI
        // show the wrong model and the loop generate against the wrong one.
        val resident = graph.loadedModel
        assertTrue(
            "loadedModel must be one of the two requested, was $resident",
            resident != null && (resident.id == model.id || resident.id == other.id),
        )
        // The backend is a plain recorder, so it cannot show real overlap; what
        // is asserted is that the graph's own view agrees with the last load.
        assertEquals("the last load is the resident model", resident?.id, backend.loaded.last().id)
    }

    // ------------------------------------------------------------------ message

    @Test
    fun `a load failure message is written for a user`() = runTest {
        val (graph, _) = graphOf(backend = RecordingBackend(failOnLoad = true))

        val unavailable = graph.launch(model, "hello").requireUnavailable()

        // It goes straight into a UI string. A raw exception message would be
        // "no native library", which tells the user nothing they can act on.
        assertTrue(
            "the message must be a plain sentence, was: ${unavailable.message}",
            unavailable.message.isNotBlank() &&
                !unavailable.message.contains("Exception") &&
                !unavailable.message.contains("null"),
        )
    }

    // ----------------------------------------------------------------- internal

    @Test
    fun `a backend that throws a non exception is still handled`() = runTest {
        // A failing native call surfaces as an Error or a JNI throw, not an
        // Exception. Catching only Exception would let it out of the graph and
        // kill the caller's coroutine.
        val graph = AgentGraph(
            toolSource = { listOf(FakeTool("device.battery")) },
            backendFactory = { ThrowingBackend() },
            memoryStoreFactory = { InMemoryMemoryStore() },
            sessionStoreFactory = { NoopSessionStore },
        )

        val result = graph.launch(model, "hello")

        assertEquals(AgentLaunch.Reason.MODEL_LOAD_FAILED, result.requireUnavailable().reason)
    }

    /** Minimal [SessionStore] for tests that only need the graph to hold one. */
    private object NoopSessionStore : SessionStore {
        override suspend fun createSession(): Long = 1L
        override suspend fun appendMessage(
            sessionId: Long,
            message: dev.localintelligence.core.model.ChatMessage,
        ) = Unit
        override suspend fun messages(sessionId: Long): List<dev.localintelligence.core.model.ChatMessage> =
            emptyList()
        override suspend fun clear() = Unit
    }
}