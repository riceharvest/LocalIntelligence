package dev.pidroid.core.agent

import dev.pidroid.core.context.ContextBuilder
import dev.pidroid.core.context.SystemPrompts
import dev.pidroid.core.model.ChatMessage
import dev.pidroid.core.model.GenerationRequest
import dev.pidroid.core.model.GenerationResult
import dev.pidroid.core.model.ModelBackend
import dev.pidroid.core.model.ModelCapabilities
import dev.pidroid.core.model.ModelSpec
import dev.pidroid.core.model.StopReason
import dev.pidroid.core.model.ToolArgs
import dev.pidroid.core.tool.AgentTool
import dev.pidroid.core.tool.LexicalToolSelector
import dev.pidroid.core.tool.SimpleToolRegistry
import dev.pidroid.core.tool.ToolContext
import dev.pidroid.core.tool.ToolDefinition
import dev.pidroid.core.tool.ToolResult
import dev.pidroid.core.tool.ToolRisk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loop is tested only through its observable surface: [AgentResult] and
 * [StepTrace] (`docs/wave1-contract.md`).
 *
 * Real collaborators: `SimpleToolRegistry`, `LexicalToolSelector`, `LoopDetector`,
 * `InMemoryMemoryStore`, and the frozen `ToolCallValidator` behind
 * [ToolCallValidatorGate.forRegistry]. Only the model, the parser and the tools
 * are doubles, so a regression in the shipped registry/selector/detector fails
 * these tests too.
 *
 * Doubles are private to this file on purpose: the eval harness owns the
 * `eval` test package and its fakes, and this wave must not collide with it.
 */
class AgentControllerTest {

    // ------------------------------------------------------------- happy paths

    @Test
    fun singleToolCallFollowedByRespondReturnsSuccess() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.battery"), ok("RESPOND:42%")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("how much battery do I have?")

        assertTrue(result is AgentResult.Success)
        result as AgentResult.Success
        assertEquals("42%", result.text)
        assertEquals(1, tool.executionCount)
        // The tool result is what the model saw, not a JSON blob.
        val observed = harness.backend.seenMessages.last().filterIsInstance<ChatMessage.ToolObservation>()
        assertEquals(1, observed.size)
        assertEquals("42%", observed.single().observation)
        assertEquals("device.battery", observed.single().toolName)
    }

    @Test
    fun respondEndsTheTaskImmediately() = runTest {
        val harness = Harness(responses = listOf(ok("RESPOND:hello there")))

        val result = harness.controller.run("say hi")

        assertEquals(AgentResult.Success("hello there", result.traceOrEmpty()), result)
        assertEquals(1, harness.backend.generateCalls)
        assertEquals(1, result.traceOrEmpty().size)
    }

    // ---------------------------------------------------------------- validation

    @Test
    fun unknownToolNameIsRejectedAndNeverExecuted() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.nonexistent"), ok("RESPOND:sorry")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("check the battery") as AgentResult.Success

        assertEquals(0, tool.executionCount)
        val rejected = result.trace.filter { it.kind == StepTrace.Kind.TOOL_CALL }
        assertEquals(1, rejected.size)
        assertFalse(rejected.single().success)
        assertTrue(rejected.single().detail.contains("device.nonexistent"))
        // The parser was only offered the visible tool names, so the model was
        // never even shown the tool it hallucinated.
        assertEquals(setOf("device.battery"), harness.parser.allowedToolSets.first())
    }

    /**
     * Validation runs before the loop detector, so the same bad call twice is two
     * argument errors, not a loop. If the order were reversed this would stop
     * with "loop detected" and the tool would still not have run.
     */
    @Test
    fun argumentErrorIsReportedAsAnArgumentErrorNotALoop() = runTest {
        val tool = FakeTool(toolDef("file.delete", ToolRisk.DESTRUCTIVE)) { _, _ ->
            ToolResult(success = true, observation = "deleted")
        }
        val badCall = ok("""CALL:file.delete|{"path":"/tmp/x"}""")
        val harness = Harness(
            responses = listOf(badCall, badCall, ok("RESPOND:I could not delete it.")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("delete the file")

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        result as AgentResult.Success
        assertEquals(0, tool.executionCount)
        val rejections = result.trace.filter { it.kind == StepTrace.Kind.TOOL_CALL }
        assertEquals(2, rejections.size)
        assertTrue(rejections.all { !it.success && it.detail.contains("unexpected argument") })
        // The model was told what was actually wrong.
        val corrections = harness.backend.seenMessages.last().filterIsInstance<ChatMessage.Assistant>()
        assertTrue(corrections.any { it.text.contains("unexpected argument") })
    }

    // ------------------------------------------------------------ loop detector

    @Test
    fun repeatedIdenticalCallWarnsOnTheSecondAndBlocksOnTheThird() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        // One scripted response, repeated: the model never changes its mind.
        val harness = Harness(responses = listOf(ok("CALL:device.battery")), toolList = listOf(tool))

        val result = harness.controller.run("how much battery?")

        assertTrue("expected Stop, got $result", result is AgentResult.Stop)
        result as AgentResult.Stop
        assertTrue(result.reason.contains("device.battery"))
        // 1st executed, 2nd warned (not executed), 3rd blocked.
        assertEquals(1, tool.executionCount)
        assertEquals(1, result.trace.count { it.kind == StepTrace.Kind.OBSERVATION })
        assertEquals(3, harness.backend.generateCalls)
        val warning = harness.backend.seenMessages.last().filterIsInstance<ChatMessage.Assistant>()
        assertTrue(warning.any { it.text.contains("already ran device.battery") })
    }

    @Test
    fun aStalledRunStopsWithNoProgress() = runTest {
        // Four different calls, one identical observation each: not a repeat (so
        // the loop detector is quiet) but no state change either. The first
        // observation only establishes the baseline, so the shipped rule trips on
        // the fourth.
        val tools = listOf("device.a", "device.b", "device.c", "device.d").map { name ->
            FakeTool(toolDef(name, ToolRisk.READ_ONLY)) { _, _ ->
                ToolResult(success = true, observation = "no data")
            }
        }
        val harness = Harness(
            responses = tools.map { ok("CALL:${it.definition.name}") } + ok("RESPOND:never reached"),
            toolList = tools,
        )

        val result = harness.controller.run("poll the sensors")

        assertTrue("expected Stop, got $result", result is AgentResult.Stop)
        assertTrue((result as AgentResult.Stop).reason.contains("no progress"))
        assertTrue(tools.all { it.executionCount == 1 })
    }

    // ------------------------------------------------------------ risk policy

    @Test
    fun destructiveToolReturnsAwaitingConfirmationAndIsNotExecuted() = runTest {
        val tool = FakeTool(toolDef("file.delete", ToolRisk.DESTRUCTIVE)) { _, _ ->
            ToolResult(success = true, observation = "deleted 1 file")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:file.delete"), ok("RESPOND:deleted")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("delete the file")

        assertTrue("expected AwaitingConfirmation, got $result", result is AgentResult.AwaitingConfirmation)
        val awaiting = result as AgentResult.AwaitingConfirmation
        assertEquals("file.delete", awaiting.toolName)
        assertEquals(0, tool.executionCount)
        assertEquals(1, harness.backend.generateCalls)
    }

    @Test
    fun approvedDestructiveToolThenExecutes() = runTest {
        val tool = FakeTool(toolDef("file.delete", ToolRisk.DESTRUCTIVE)) { _, _ ->
            ToolResult(success = true, observation = "deleted 1 file")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:file.delete"), ok("RESPOND:deleted it")),
            toolList = listOf(tool),
        )

        val first = harness.controller.run("delete the file")
        assertTrue(first is AgentResult.AwaitingConfirmation)
        val second = harness.controller.confirmAndResume(approved = true)

        assertTrue("expected Success, got $second", second is AgentResult.Success)
        assertEquals(1, tool.executionCount)
        // The tool knows a human said yes.
        assertTrue(tool.contexts.single().userConfirmed)
        assertEquals(2, harness.backend.generateCalls)
    }

    @Test
    fun declinedDestructiveToolIsNeverExecuted() = runTest {
        val tool = FakeTool(toolDef("file.delete", ToolRisk.DESTRUCTIVE)) { _, _ ->
            ToolResult(success = true, observation = "deleted 1 file")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:file.delete"), ok("RESPOND:I left it alone")),
            toolList = listOf(tool),
        )

        harness.controller.run("delete the file")
        val result = harness.controller.confirmAndResume(approved = false)

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        assertEquals(0, tool.executionCount)
        // The model is told it was declined, so it can route around it.
        val told = harness.backend.seenMessages.last().filterIsInstance<ChatMessage.Assistant>()
        assertTrue(told.any { it.text.contains("declined file.delete") })
    }

    @Test
    fun externalCommunicationAlsoRequiresConfirmation() = runTest {
        val tool = FakeTool(toolDef("sms.send", ToolRisk.EXTERNAL_COMMUNICATION)) { _, _ ->
            ToolResult(success = true, observation = "sent")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:sms.send"), ok("RESPOND:sent")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("text Dario")

        assertTrue(result is AgentResult.AwaitingConfirmation)
        assertEquals(0, tool.executionCount)
    }

    @Test
    fun readOnlyToolExecutesWithoutConfirmation() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val reversible = FakeTool(toolDef("clipboard.write", ToolRisk.REVERSIBLE)) { _, _ ->
            ToolResult(success = true, observation = "copied")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.battery"), ok("RESPOND:42%")),
            toolList = listOf(tool, reversible),
        )

        val result = harness.controller.run("how much battery?")

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        assertEquals(1, tool.executionCount)
        assertFalse(tool.contexts.single().userConfirmed)
    }

    @Test
    fun privilegedToolIsRefusedAndNeverExecuted() = runTest {
        val tool = FakeTool(toolDef("device.root", ToolRisk.PRIVILEGED)) { _, _ ->
            ToolResult(success = true, observation = "rooted")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.root"), ok("RESPOND:I cannot do that")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("root the device")

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        assertEquals(0, tool.executionCount)
        val refusals = (result as AgentResult.Success).trace.filter { it.kind == StepTrace.Kind.TOOL_CALL }
        assertEquals(1, refusals.size)
        assertFalse(refusals.single().success)
        val told = harness.backend.seenMessages.last().filterIsInstance<ChatMessage.Assistant>()
        assertTrue(told.any { it.text.contains("disabled") })
    }

    // ------------------------------------------------------------- tool failures

    @Test
    fun aThrowingToolBecomesAFailedObservationAndTheLoopContinues() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            throw IllegalStateException("sensor bus on fire")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.battery"), ok("RESPOND:I could not read it.")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("how much battery?")

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        result as AgentResult.Success
        val call = result.trace.single { it.kind == StepTrace.Kind.TOOL_CALL }
        val observation = result.trace.single { it.kind == StepTrace.Kind.OBSERVATION }
        assertFalse(call.success)
        assertFalse(observation.success)
        // Named, short, and no stack trace reaches the model.
        assertTrue(observation.detail.contains("IllegalStateException"))
        assertTrue(observation.detail.contains("sensor bus on fire"))
        assertFalse(observation.detail.contains("dev.pidroid.core"))
        // The model read the failure and was allowed to answer.
        val told = harness.backend.seenMessages.last().filterIsInstance<ChatMessage.ToolObservation>()
        assertEquals(1, told.size)
        assertFalse(told.single().success)
    }

    // --------------------------------------------------------------- the budget

    @Test
    fun oversizedObservationIsTruncatedBeforeTheModelSeesIt() = runTest {
        val huge = "x".repeat(9_000)
        val tool = FakeTool(toolDef("files.read", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = huge)
        }
        val harness = Harness(
            responses = listOf(ok("CALL:files.read"), ok("RESPOND:read it")),
            toolList = listOf(tool),
            config = AgentConfig(observationBudgetChars = 512),
        )

        val result = harness.controller.run("read the file") as AgentResult.Success

        val observation = result.trace.single { it.kind == StepTrace.Kind.OBSERVATION }
        assertTrue(observation.detail.length <= 512)
        assertTrue(observation.detail.contains("truncated"))
        // The model was handed exactly the truncated text, never the full blob.
        val told = harness.backend.seenMessages.last()
            .filterIsInstance<ChatMessage.ToolObservation>().single()
        assertEquals(observation.detail, told.observation)
        assertTrue(told.observation.length <= 512)
    }

    @Test
    fun anAbsurdlySmallObservationBudgetDoesNotCrashTheLoop() = runTest {
        val tool = FakeTool(toolDef("files.read", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "y".repeat(5_000))
        }
        val harness = Harness(
            responses = listOf(ok("CALL:files.read"), ok("RESPOND:read it")),
            toolList = listOf(tool),
            config = AgentConfig(observationBudgetChars = 10),
        )

        val result = harness.controller.run("read the file")

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        val observation = (result as AgentResult.Success).trace
            .single { it.kind == StepTrace.Kind.OBSERVATION }
        assertEquals(10, observation.detail.length)
    }

    // ------------------------------------------------------------- step budgets

    @Test
    fun maxStepsIsRespectedAndYieldsStepLimitReached() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.battery")),
            toolList = listOf(tool),
            config = AgentConfig(maxSteps = 2),
        )

        val result = harness.controller.run("how much battery?")

        assertEquals(AgentResult.StepLimitReached, result)
        assertEquals(2, harness.backend.generateCalls)
    }

    @Test
    fun repeatedMalformedOutputStopsInsteadOfLoopingForever() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(ok("I think the answer is probably about forty percent.")),
            toolList = listOf(tool),
            config = AgentConfig(maxSteps = 50, maxMalformedRetries = 3),
        )

        val result = harness.controller.run("how much battery?")

        assertTrue("expected Stop, got $result", result is AgentResult.Stop)
        result as AgentResult.Stop
        assertTrue(result.reason.contains("valid action"))
        // Stopped by the malformed budget, not by maxSteps: 3 attempts, no infinite loop.
        assertEquals(3, harness.backend.generateCalls)
        // The third failure is traced before the loop gives up, so all 3 are recorded.
        assertEquals(3, result.trace.count { it.kind == StepTrace.Kind.MALFORMED })
        // Prose never reached a tool.
        assertEquals(0, tool.executionCount)
        // The model was told the shape it should have produced, by tool name.
        val correction = harness.backend.seenMessages[1].filterIsInstance<ChatMessage.Assistant>()
        assertTrue(correction.any { it.text.contains("CallTool") && it.text.contains("device.battery") })
    }

    @Test
    fun aSuccessfulParseResetsTheMalformedStreak() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(
                ok("I am not sure about that."),
                ok("CALL:device.battery"),
                ok("hmm, still not sure"),
                ok("RESPOND:42%"),
            ),
            toolList = listOf(tool),
            config = AgentConfig(maxMalformedRetries = 2),
        )

        val result = harness.controller.run("how much battery?")

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        assertEquals(1, tool.executionCount)
        assertEquals(4, harness.backend.generateCalls)
    }

    // ------------------------------------------------------------ cancellation

    @Test
    fun cancelBeforeTheRunMeansTheModelIsNeverCalled() = runTest {
        val harness = Harness(responses = listOf(ok("RESPOND:hi")))

        harness.controller.cancel()
        val result = harness.controller.run("say hi")

        assertEquals(AgentResult.Cancelled, result)
        assertEquals(0, harness.backend.generateCalls)
        // ModelBackend.cancel() is actually wired, not just our own flag.
        assertEquals(1, harness.backend.cancelCalls)
    }

    @Test
    fun cancelMidRunReturnsCancelledAndTheToolObservesTheSignal() = runTest {
        lateinit var controller: AgentController
        var signalInsideTool: Boolean? = null
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, context ->
            controller.cancel()
            signalInsideTool = context.signal.isCancelled()
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.battery"), ok("RESPOND:42%")),
            toolList = listOf(tool),
        )
        controller = harness.controller

        val result = controller.run("how much battery?")

        assertEquals(AgentResult.Cancelled, result)
        assertEquals(1, tool.executionCount)
        // Cancellation reached the tool through its ToolContext.
        assertEquals(true, signalInsideTool)
        assertEquals(1, harness.backend.cancelCalls)
        // And it did not carry on to another generation.
        assertEquals(1, harness.backend.generateCalls)
    }

    @Test
    fun aCancelledStopReasonFromTheModelReturnsCancelled() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.battery")),
            toolList = listOf(tool),
            transform = { it.copy(stopReason = StopReason.CANCELLED) },
        )

        val result = harness.controller.run("how much battery?")

        assertEquals(AgentResult.Cancelled, result)
        assertEquals(0, tool.executionCount)
    }

    // ------------------------------------------------------------- the contract

    @Test
    fun aThrowingModelBackendIsContainedAndReportedAsStop() = runTest {
        val harness = Harness(responses = listOf(ok("RESPOND:hi")), throwOnGenerate = true)

        val result = harness.controller.run("say hi")

        assertTrue("expected Stop, got $result", result is AgentResult.Stop)
        val stop = result as AgentResult.Stop
        assertTrue(stop.reason.contains("internal error"))
        assertNotNull(stop.trace)
        assertEquals(1, harness.backend.generateCalls)
    }

    @Test
    fun traceCarriesTheExpectedKindsInOrder() = runTest {
        val tool = FakeTool(toolDef("device.battery", ToolRisk.READ_ONLY)) { _, _ ->
            ToolResult(success = true, observation = "42%")
        }
        val harness = Harness(
            responses = listOf(ok("CALL:device.battery"), ok("RESPOND:42%")),
            toolList = listOf(tool),
        )

        val result = harness.controller.run("how much battery?") as AgentResult.Success

        assertEquals(
            listOf(
                StepTrace.Kind.GENERATION,
                StepTrace.Kind.TOOL_CALL,
                StepTrace.Kind.OBSERVATION,
                StepTrace.Kind.GENERATION,
            ),
            result.trace.map { it.kind },
        )
        assertEquals(listOf(1, 1, 1, 2), result.trace.map { it.step })
        assertTrue(result.trace.all { it.success })
        assertEquals("42%", result.trace[2].detail)
        assertEquals("device.battery{}", result.trace[1].detail)
    }

    @Test
    fun compactionFoldsOldTurnsButKeepsTheTask() = runTest {
        val tools = (0..4).map { i ->
            FakeTool(toolDef("device.sensor$i", ToolRisk.READ_ONLY)) { _, _ ->
                ToolResult(success = true, observation = "reading $i")
            }
        }
        val harness = Harness(
            responses = tools.map { ok("CALL:${it.definition.name}") } + ok("RESPOND:all five read"),
            toolList = tools,
            config = AgentConfig(workingTokenLimit = 1),
        )

        val result = harness.controller.run("read all five sensors")

        assertTrue("expected Success, got $result", result is AgentResult.Success)
        result as AgentResult.Success
        assertEquals(5, tools.sumOf { it.executionCount })
        assertEquals(1, result.trace.count { it.kind == StepTrace.Kind.COMPACTION })

        val finalPrompt = harness.backend.seenMessages.last()
        // The tail survives verbatim; the rest was folded into the summary.
        assertEquals(4, finalPrompt.filterIsInstance<ChatMessage.ToolObservation>().size)
        assertNotNull(harness.session.workingSummary)
        assertTrue(finalPrompt.any { it is ChatMessage.User && it.text.contains("read all five") })
        assertTrue(
            finalPrompt.any {
                it is ChatMessage.Assistant && it.text.contains("Actions already taken")
            },
        )
    }
}

// --------------------------------------------------------------------- doubles

private fun ok(text: String) = GenerationResult(text = text)

private fun toolDef(name: String, risk: ToolRisk) = ToolDefinition(
    name = name,
    description = "Returns the current value of $name.",
    category = name.substringBefore('.'),
    schema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
        putJsonArray("required") { }
    },
    risk = risk,
    tags = setOf(name.substringAfter('.')),
)

/** A tool whose behaviour is a lambda, recording every call it receives. */
private class FakeTool(
    override val definition: ToolDefinition,
    private val behavior: suspend (ToolArgs, ToolContext) -> ToolResult,
) : AgentTool {
    val calls = mutableListOf<ToolArgs>()
    val contexts = mutableListOf<ToolContext>()

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        calls += args
        contexts += context
        return behavior(args, context)
    }

    val executionCount: Int get() = calls.size
}

/**
 * Scripted backend. Once the script runs out the last response repeats, which is
 * how a confused model is simulated.
 */
private class FakeBackend(
    private val responses: List<GenerationResult>,
    private val transform: (GenerationResult) -> GenerationResult = { it },
    private val throwOnGenerate: Boolean = false,
) : ModelBackend {
    override val id: String = "fake"
    override val capabilities: ModelCapabilities = ModelCapabilities(
        contextLength = 8192,
        supportsToolCalling = true,
        supportsGrammar = true,
        supportsVision = false,
        supportsKvCache = false,
    )

    val seenMessages = mutableListOf<List<ChatMessage>>()
    var generateCalls = 0
        private set
    var cancelCalls = 0
        private set

    override suspend fun load(model: ModelSpec) = Unit

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        seenMessages += request.messages
        generateCalls += 1
        if (throwOnGenerate) throw IllegalStateException("inference backend died")
        return transform(responses.getOrElse(generateCalls - 1) { responses.last() })
    }

    override suspend fun unload() = Unit
    override fun countTokens(text: String): Int = text.length / 4
    override fun cancel() {
        cancelCalls += 1
    }
}

/**
 * `RESPOND:…` / `CALL:name|{json}`. Deliberately dumb: it does not enforce
 * [ActionParser]'s `allowedTools`, so the validator — not the parser — is what
 * rejects a hallucinated tool name.
 */
private class TextParser : ActionParser {
    val raws = mutableListOf<String>()
    val allowedToolSets = mutableListOf<Set<String>>()

    override fun parse(raw: String, allowedTools: Set<String>): ActionParseResult {
        raws += raw
        allowedToolSets += allowedTools
        val text = raw.trim()
        return when {
            text.startsWith(RESPOND) ->
                ActionParseResult.Parsed(AgentAction.Respond(text.removePrefix(RESPOND)))

            text.startsWith(CALL) -> {
                val rest = text.removePrefix(CALL)
                val args = rest.substringAfter('|', "")
                try {
                    val parsed = Json.parseToJsonElement(args.ifEmpty { "{}" }).jsonObject
                    ActionParseResult.Parsed(AgentAction.CallTool(rest.substringBefore('|'), parsed))
                } catch (t: Throwable) {
                    ActionParseResult.Malformed("unparseable arguments", raw)
                }
            }

            else -> ActionParseResult.Malformed("not a legal action", raw)
        }
    }

    private companion object {
        const val RESPOND = "RESPOND:"
        const val CALL = "CALL:"
    }
}

/** Test double for the context workstream: system prompt, task, then history. */
private object FlatContextBuilder : ContextBuilder {
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

/** Wires the real registry, selector, detector, memory store and validator. */
private class Harness(
    responses: List<GenerationResult>,
    toolList: List<AgentTool> = emptyList(),
    config: AgentConfig = AgentConfig(),
    transform: (GenerationResult) -> GenerationResult = { it },
    throwOnGenerate: Boolean = false,
    val session: Session = Session(),
) {
    val backend = FakeBackend(responses, transform, throwOnGenerate)
    val parser = TextParser()
    private val registry = SimpleToolRegistry(toolList)

    val controller = AgentController(
        model = backend,
        parser = parser,
        tools = registry,
        toolSelector = LexicalToolSelector(),
        validator = ToolCallValidatorGate.forRegistry(registry),
        loopDetector = LoopDetector(),
        contextBuilder = FlatContextBuilder,
        memory = InMemoryMemoryStore(),
        sessions = session,
        config = config,
    )
}

/** `StepLimitReached` and `Cancelled` carry no trace; this keeps assertions readable. */
private fun AgentResult.traceOrEmpty(): List<StepTrace> = when (this) {
    is AgentResult.Success -> trace
    is AgentResult.AwaitingConfirmation -> trace
    is AgentResult.Stop -> trace
    AgentResult.StepLimitReached -> emptyList()
    AgentResult.Cancelled -> emptyList()
}
