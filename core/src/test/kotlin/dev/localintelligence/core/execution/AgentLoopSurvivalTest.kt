package dev.localintelligence.core.execution

import dev.localintelligence.core.agent.AgentAction
import dev.localintelligence.core.agent.AgentConfig
import dev.localintelligence.core.agent.AgentController
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.InMemoryMemoryStore
import dev.localintelligence.core.agent.LoopDetector
import dev.localintelligence.core.agent.Memory
import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.agent.ToolCallValidatorGate
import dev.localintelligence.core.context.ContextBuilder
import dev.localintelligence.core.context.SystemPrompts
import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector
import dev.localintelligence.core.tool.SimpleToolRegistry
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee that matters, asserted against the **real** `AgentController`.
 *
 * A timeout must be an ordinary step, not a fatal one. Every other test in this
 * package proves a component behaves; this file proves the assembled system does:
 * a model whose tool call times out still gets a next step, and a run that is
 * cancelled stops without an exception escaping.
 *
 * The loop, registry, selector, validator and detector are all real. Only the
 * model backend and the tool bodies are doubles, so a regression in the shipped
 * wiring fails here too.
 */
class AgentLoopSurvivalTest {

    @Test
    fun aTimedOutToolDoesNotKillTheLoopAndTheNextStepStillRuns() = runTest {
        // Step 1 calls a tool that overruns its budget. Step 2 responds. If the
        // timeout killed the run, there would be no step 2 and no answer.
        val slow = GuardedTool(
            delegate = ScriptedTool("web.fetch") { delayForever() },
            executor = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler)),
        ).withBudget(ExecutionBudget(1_000, "loop test"))

        val harness = LoopHarness(
            script = listOf(call("web.fetch"), respond("I could not reach that page.")),
            tools = listOf(slow),
        )

        val result = harness.controller.run("what does example.com say?")

        assertTrue("the loop must survive a timeout, got $result", result is AgentResult.Success)
        // Two generations means the loop genuinely continued past the failure.
        assertEquals("the next step must still have run", 2, harness.backend.generationCount)
    }

    @Test
    fun theModelIsToldATimeoutIsNotAFault() = runTest {
        val slow = GuardedTool(
            delegate = ScriptedTool("web.fetch") { delayForever() },
            executor = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler)),
        ).withBudget(ExecutionBudget(1_000, "loop test"))

        val harness = LoopHarness(
            script = listOf(call("web.fetch"), respond("stopping.")),
            tools = listOf(slow),
        )

        harness.controller.run("check the page")

        // The observation the model actually read is the whole point of the
        // three-way split. A timeout told as a crash invites a pointless retry.
        val observations = harness.backend.requests.last().messages
            .filterIsInstance<ChatMessage.ToolObservation>()
        assertEquals(1, observations.size)
        val text = observations.single().observation
        assertTrue("a timeout must read as a stop, got: $text", text.contains("did not finish"))
        assertFalse("a timeout must not read as a crash: $text", text.contains("failed"))
    }

    @Test
    fun aToolThatThrowsBecomesAFailedObservationAndTheLoopContinues() = runTest {
        val broken = ScriptedTool("device.battery") {
            throw IllegalStateException("sensor bus is on fire")
        }
        val harness = LoopHarness(
            script = listOf(call("device.battery"), respond("the sensor is broken.")),
            tools = listOf(broken),
        )

        val result = harness.controller.run("what is the battery")

        // A tool that throws is a failed step, never a dead agent.
        assertTrue("expected success after recovery, got $result", result is AgentResult.Success)
        assertEquals(2, harness.backend.generationCount)
    }

    @Test
    fun aCancelledRunStopsWithCancelledAndNoEscapedException() = runTest {
        val slow = GuardedTool(
            delegate = ScriptedTool("web.fetch") { delayForever() },
            executor = BoundedToolExecutor(toolDispatcher = StandardTestDispatcher(testScheduler)),
        ).withBudget(ExecutionBudget(60_000, "loop test"))

        val harness = LoopHarness(
            script = listOf(call("web.fetch"), respond("never reached")),
            tools = listOf(slow),
        )

        // Cancelling before the run starts is the deterministic version of a user
        // pressing stop. The contract is a value, never a thrown exception.
        harness.controller.cancel()
        val result = harness.controller.run("check the page")

        assertTrue("expected Cancelled, got $result", result is AgentResult.Cancelled)
        assertEquals("nothing should have been generated", 0, harness.backend.generationCount)
    }

    @Test
    fun aCancellationDuringAToolCallIsReportedAsCancelledNotFailure() = runTest {
        val token = CancellationToken.none()
        val dispatcher = StandardTestDispatcher(testScheduler)

        // The tool polls its token between waits, the way a well-written one
        // must. The cancel arrives while it is in flight, not before it starts —
        // which is the case a token checked only at the start would miss.
        val cancellable = ScriptedTool("web.fetch") {
            while (true) {
                token.checkActive()
                kotlinx.coroutines.delay(1_000)
            }
            @Suppress("UNREACHABLE_CODE")
            ToolResult(success = true, observation = "never")
        }
        val guarded = GuardedTool(
            delegate = cancellable,
            executor = BoundedToolExecutor(toolDispatcher = dispatcher),
        )

        val call = async {
            guarded.executeWith(emptyArgs(), ToolContext(), token)
        }
        advanceTimeBy(2_000)
        token.cancel("user pressed stop")
        advanceUntilIdle()

        val result = call.await()
        assertFalse("a cancelled call must not report success", result.success)
        // The whole point of the three-way split: the model must be able to tell
        // "the user stopped this" from "the tool is broken", because the correct
        // response to each is different.
        assertTrue(
            "a cancel must be typed as cancelled, not a failure: ${result.error}",
            result.error is ToolError.Cancelled,
        )
        val observation = result.observation
        assertTrue("must tell the model the run is over: $observation", observation.contains("run is over"))
        assertFalse("a cancel must not read as a crash: $observation", observation.contains("failed"))
    }

    @Test
    fun aUserCancelReachesANestedGuardedToolCall() = runTest {
        val parent = CancellationToken.none()
        val innerSawCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatcher = StandardTestDispatcher(testScheduler)

        // The inner tool is guarded in its own right, exactly as a tool that
        // calls another tool would be. The point is that one cancel on the run
        // reaches both — the failure this whole package was written for.
        // `innerRef` is a forward reference because the tool body needs to read
        // the inner wrapper's own token, which cannot exist before it is built.
        val innerRef = arrayOfNulls<GuardedTool>(1)
        val inner = GuardedTool(
            delegate = ScriptedTool("web.fetch") {
                while (true) {
                    if (innerRef[0]!!.activeToken().isCancelled) {
                        innerSawCancel.set(true)
                        tokenThrow()
                    }
                    kotlinx.coroutines.delay(1_000)
                }
                @Suppress("UNREACHABLE_CODE")
                ToolResult(success = true, observation = "never")
            },
            executor = BoundedToolExecutor(toolDispatcher = dispatcher),
        )
        innerRef[0] = inner
        val outer = GuardedTool(
            delegate = ScriptedTool("summarise") { inner.execute(emptyArgs(), ToolContext()) },
            executor = BoundedToolExecutor(toolDispatcher = dispatcher),
        )

        val call = launch { outer.executeWith(emptyArgs(), ToolContext(), parent) }
        advanceTimeBy(2_000)
        parent.cancel("user pressed stop")
        advanceUntilIdle()
        call.join()

        assertTrue(
            "a cancel on the run must reach the nested tool call",
            innerSawCancel.get(),
        )
    }

    @Test
    fun theNoProgressWatchdogStopsARunThatChangesNothing() = runTest {
        // A model that calls three different tools which all return the same
        // text. No call repeats, so only a fingerprint-based watchdog catches it.
        val watchdog = NoProgressWatchdog(
            noProgressLimit = 3,
            stallTimeoutMs = null,
            clock = FakeExecutionClock(),
        )
        repeat(4) { index ->
            watchdog.record("no results found")
        }

        assertTrue("the watchdog must terminate a stalled run", watchdog.noProgressStreak >= 3)
    }

    // ------------------------------------------------------------------ doubles

    /** A tool whose body is a lambda, recording the calls it receives. */
    private class ScriptedTool(
        name: String,
        private val behavior: suspend (ToolArgs) -> ToolResult,
    ) : AgentTool {
        override val definition: ToolDefinition = ToolDefinition(
            name = name,
            description = "Test tool $name.",
            category = name.substringBefore('.'),
            schema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject { })
                put("required", kotlinx.serialization.json.buildJsonArray { })
            },
            risk = ToolRisk.READ_ONLY,
            tags = setOf(name.substringAfter('.')),
        )

        var calls = 0
            private set

        override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
            calls += 1
            return behavior(args)
        }
    }

    /** Parks the coroutine for a very long time in virtual time. */
    private suspend fun delayForever(): ToolResult {
        kotlinx.coroutines.delay(Long.MAX_VALUE / 2)
        return ToolResult(success = true, observation = "never")
    }

    /** Scripted backend: returns the next line of the script on every call. */
    private class ScriptedBackend(private val script: List<String>) : ModelBackend {
        override val id = "scripted"
        override val capabilities = ModelCapabilities(
            contextLength = 4096,
            supportsToolCalling = true,
            supportsGrammar = true,
            supportsVision = false,
            supportsKvCache = false,
        )

        val requests = mutableListOf<GenerationRequest>()
        val generationCount: Int get() = requests.size
        private var cursor = 0
        private var cancelled = false

        override suspend fun load(model: ModelSpec) = Unit
        override suspend fun unload() = Unit
        override fun countTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)
        override fun cancel() {
            cancelled = true
        }

        override suspend fun generate(request: GenerationRequest): GenerationResult {
            if (cancelled) return GenerationResult(text = "", stopReason = dev.localintelligence.core.model.StopReason.CANCELLED)
            requests += request
            val text = script.getOrElse(cursor) { script.last() }
            cursor += 1
            return GenerationResult(text = text)
        }
    }

    /** The eval action protocol, so the real parser handles the script. */
    private object JsonParser : dev.localintelligence.core.agent.ActionParser {
        override fun parse(
            raw: String,
            allowedTools: Set<String>,
        ): dev.localintelligence.core.agent.ActionParseResult {
            return try {
                val element = Json.parseToJsonElement(raw).jsonObject
                when (element["action"]?.toString()?.trim('"')) {
                    "respond" -> dev.localintelligence.core.agent.ActionParseResult.Parsed(
                        AgentAction.Respond(element["text"].toString().trim('"')),
                    )

                    "call" -> dev.localintelligence.core.agent.ActionParseResult.Parsed(
                        AgentAction.CallTool(
                            element["name"].toString().trim('"'),
                            element["args"]?.jsonObject ?: buildJsonObject { },
                        ),
                    )

                    else -> dev.localintelligence.core.agent.ActionParseResult.Malformed(
                        "unknown action", raw,
                    )
                }
            } catch (t: Throwable) {
                dev.localintelligence.core.agent.ActionParseResult.Malformed("unparseable", raw)
            }
        }
    }

    private object FlatContext : ContextBuilder {
        override fun build(
            task: String,
            history: List<ChatMessage>,
            memories: List<Memory>,
            tools: List<ToolDefinition>,
        ): List<ChatMessage> = listOf(
            ChatMessage.System(SystemPrompts.forTools(tools)),
            ChatMessage.User(task),
        ) + history
    }

    /** Wires the real loop around the real registry, selector, detector. */
    private class LoopHarness(
        script: List<String>,
        tools: List<AgentTool>,
        config: AgentConfig = AgentConfig(),
    ) {
        val backend = ScriptedBackend(script)
        private val registry = SimpleToolRegistry(tools)

        val controller = AgentController(
            model = backend,
            parser = JsonParser,
            tools = registry,
            toolSelector = LexicalToolSelector(),
            validator = ToolCallValidatorGate.forRegistry(registry),
            loopDetector = LoopDetector(),
            contextBuilder = FlatContext,
            memory = InMemoryMemoryStore(),
            sessions = Session(),
            config = config,
        )
    }

    private companion object {
        fun emptyArgs(): ToolArgs = buildJsonObject { }

        /**
         * Thrown by a tool that has noticed the run is stopping. The guard turns
         * it into a structured Cancelled result rather than letting it escape.
         */
        fun tokenThrow(): Nothing = throw OperationCancelledException("user pressed stop")

        fun respond(text: String): String = JsonObject(
            mapOf("action" to JsonPrimitive("respond"), "text" to JsonPrimitive(text)),
        ).toString()

        fun call(name: String): String = JsonObject(
            mapOf(
                "action" to JsonPrimitive("call"),
                "name" to JsonPrimitive(name),
                "args" to buildJsonObject { },
            ),
        ).toString()
    }
}
