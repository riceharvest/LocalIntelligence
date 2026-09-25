package dev.pidroid.core.context

import dev.pidroid.core.agent.Memory
import dev.pidroid.core.model.ChatMessage
import dev.pidroid.core.tool.ToolDefinition
import dev.pidroid.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context assembly. The budget is the product; these tests exist to prove the
 * prompt stays small, ordered, and whole.
 */
class DefaultContextBuilderTest {

    // ---------------------------------------------------------------- helpers

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = "Does $name on the device.",
        category = "test",
        schema = JsonObject(emptyMap()),
        risk = ToolRisk.READ_ONLY,
    )

    /** A 20-tool registry, the shape a real device would carry. */
    private fun twentyTools() = (1..20).map { tool("tool.$it") }

    private fun memory(id: Long, text: String) =
        Memory(id = id, text = text, keywords = "")

    private fun turns(count: Int, chars: Int = 200): List<ChatMessage> =
        (1..count).map { i ->
            val body = "turn $i " + "x".repeat(chars)
            if (i % 2 == 1) ChatMessage.Assistant(body) else ChatMessage.User(body)
        }

    private fun totalTokens(messages: List<ChatMessage>) = TokenEstimate.tokens(messages)

    /** The first User message's text, asserted to exist. */
    private fun firstUserText(out: List<ChatMessage>): String {
        val message = out.firstOrNull { it is ChatMessage.User } as? ChatMessage.User
        assertNotNull("no User message in ${out.size} messages", message)
        return message!!.text
    }

    private fun rendered(messages: List<ChatMessage>): String = buildString {
        messages.forEach { message ->
            when (message) {
                is ChatMessage.System -> appendLine("[system] ${message.text}")
                is ChatMessage.User -> appendLine("[user] ${message.text}")
                is ChatMessage.Assistant -> appendLine("[assistant] ${message.text}")
                is ChatMessage.ToolObservation ->
                    appendLine("[tool:${message.toolName} ok=${message.success}] ${message.observation}")
            }
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `tool definitions are present when tools are given`() {
        val tools = listOf(tool("calendar.search"), tool("contacts.search"))
        val prompt = rendered(DefaultContextBuilder().build("task", emptyList(), emptyList(), tools))

        assertTrue(prompt.contains("calendar.search"))
        assertTrue(prompt.contains("contacts.search"))
        assertTrue(prompt.contains("Available tools:"))
    }

    /**
     * The single biggest context saving in the system (architecture section 11).
     * 20 tools exist; only the 3 selected may reach the model.
     */
    @Test
    fun `only the passed tools appear - the other 17 are absent`() {
        val selected = listOf(tool("tool.1"), tool("tool.2"), tool("tool.3"))
        val prompt = rendered(DefaultContextBuilder().build("task", emptyList(), emptyList(), selected))

        (1..20).forEach { i ->
            val name = "tool.$i"
            if (i <= 3) {
                assertTrue("selected tool $name must be present", prompt.contains(name))
            } else {
                assertFalse("unselected tool $name must be absent", prompt.contains(name))
            }
        }
    }

    @Test
    fun `system message is first`() {
        val tools = listOf(tool("calendar.search"))
        val out = DefaultContextBuilder().build("find my dentist", emptyList(), emptyList(), tools)

        assertTrue(out.isNotEmpty())
        val first = out.first()
        assertTrue("first message must be System, was ${first::class.simpleName}", first is ChatMessage.System)
        assertEquals(SystemPrompts.forTools(tools), (first as ChatMessage.System).text)
    }

    @Test
    fun `task appears as the current user message`() {
        val out = DefaultContextBuilder().build("set an alarm for 8 30", emptyList(), emptyList(), emptyList())

        assertTrue(rendered(out).contains("set an alarm for 8 30"))
        assertTrue(out.any { it is ChatMessage.User && it.text == "set an alarm for 8 30" })
    }

    @Test
    fun `order is system, summary, memories, task, turns`() {
        val compactor = ContextCompactor()
        val summary = compactor.summaryMessage(
            CompactedState(
                task = "set an alarm for 8 30",
                progress = listOf("checked clock"),
                failures = listOf("alarm.set FAILED: no permission"),
            )
        )
        val out = DefaultContextBuilder().build(
            task = "set an alarm for 8 30",
            history = listOf(summary) + turns(4),
            memories = listOf(memory(1, "user wakes at 7")),
            tools = listOf(tool("alarm.set")),
        )

        val systemAt = out.indexOfFirst { it is ChatMessage.System && it.text == SystemPrompts.forTools(listOf(tool("alarm.set"))) }
        val summaryAt = out.indexOfFirst { it is ChatMessage.System && it.text.startsWith(SUMMARY_PREFIX) }
        val memoryAt = out.indexOfFirst { it is ChatMessage.User && it.text.startsWith(MEMORY_HEADER) }
        val taskAt = out.indexOfFirst { it is ChatMessage.User && it.text == "set an alarm for 8 30" }

        assertEquals(0, systemAt)
        assertEquals(1, summaryAt)
        assertEquals(2, memoryAt)
        assertEquals(3, taskAt)
        // 4 fixed slots + 4 turns, all of which fit inside a 6000-token budget.
        assertEquals(8, out.size)
    }

    @Test
    fun `memories are capped at 5`() {
        val memories = (1..12).map { memory(it.toLong(), "fact number $it") }
        val out = DefaultContextBuilder().build("task", emptyList(), memories, emptyList())

        val block = firstUserText(out)
        val lines = block.lines().filter { it.startsWith("- ") }
        assertEquals(5, lines.size)
        assertTrue(block.contains("fact number 1"))
        assertFalse("memory 6 is past the cap", block.contains("fact number 6"))
    }

    @Test
    fun `each memory is truncated and stays on one line`() {
        val long = "z".repeat(5_000)
        val out = DefaultContextBuilder().build("task", emptyList(), listOf(memory(1, long)), emptyList())

        val block = firstUserText(out)
        val line = block.lines().first { it.startsWith("- ") }
        assertTrue("memory line must be capped, was ${line.length}", line.length <= ContextLimits.MEMORY_LINE_CHARS + 2)
        assertTrue("truncation must be visible", line.endsWith("…"))
    }

    @Test
    fun `blank memories are dropped rather than rendered`() {
        val out = DefaultContextBuilder()
            .build("task", emptyList(), listOf(memory(1, "   "), memory(2, "real fact")), emptyList())

        val block = firstUserText(out)
        assertEquals(1, block.lines().count { it.startsWith("- ") })
        assertFalse(block.contains("- \n"))
    }

    @Test
    fun `huge history is trimmed but system and task survive`() {
        val tools = listOf(tool("alarm.set"))
        val out = DefaultContextBuilder(workingLimit = 6_000)
            .build("set an alarm for 8 30", turns(400), emptyList(), tools)

        assertTrue(totalTokens(out) <= 6000)
        // The system message is rebuilt per build() call by design, so compare
        // content, not identity. What matters is that it is present and first.
        assertEquals(SystemPrompts.forTools(tools), (out.first() as ChatMessage.System).text)
        assertTrue(rendered(out).contains("set an alarm for 8 30"))
        assertTrue("turns should survive, not everything be dropped", out.size > 4)
    }

    @Test
    fun `history that cannot fit still returns a valid prompt`() {
        // Working limit so small that only the system message can fit.
        val tools = listOf(tool("alarm.set"))
        val out = DefaultContextBuilder(workingLimit = 1)
            .build("set an alarm", turns(50), listOf(memory(1, "fact")), tools)

        assertTrue("must not throw and must return something", out.isNotEmpty())
        assertTrue(out.first() is ChatMessage.System)
        assertTrue("tool definitions must survive", rendered(out).contains("alarm.set"))
        assertTrue("the task must survive", rendered(out).contains("set an alarm"))
    }

    @Test
    fun `an oversized task alone is returned rather than thrown`() {
        val out = DefaultContextBuilder(workingLimit = 10)
            .build("q".repeat(50_000), emptyList(), emptyList(), emptyList())

        assertEquals(2, out.size)
        assertTrue(out[0] is ChatMessage.System)
        assertEquals(50_000, (out[1] as ChatMessage.User).text.length)
    }

    @Test
    fun `total size respects workingLimit`() {
        listOf(200, 600, 1_500, 3_000, 6_000).forEach { limit ->
            val out = DefaultContextBuilder(workingLimit = limit)
                .build("find the pdf i downloaded yesterday", turns(120), (1..9).map { memory(it.toLong(), "fact $it") }, twentyTools().take(6))

            // The two protected slots (system + task) are allowed to exceed an
            // impossible limit; anything above that would be a budgeting bug.
            val protected = TokenEstimate.tokens(listOf(out.first(), out.last()))
            if (protected <= limit) {
                assertTrue(
                    "limit=$limit produced ${totalTokens(out)} tokens",
                    totalTokens(out) <= limit,
                )
            }
        }
    }

    @Test
    fun `trimming drops the oldest turns first and keeps the newest`() {
        val history = turns(100)
        val out = DefaultContextBuilder(workingLimit = 1_200)
            .build("task", history, emptyList(), emptyList())

        val kept = out.filter { it is ChatMessage.Assistant || it is ChatMessage.User }
            .filter { message -> history.any { it === message } }
        assertTrue("some turns must survive", kept.isNotEmpty())

        // Oldest-first trimming means the survivors are a SUFFIX of the input.
        val lastKeptIndex = history.indexOf(kept.last())
        kept.forEach { message ->
            assertTrue(
                "kept set must be contiguous from the end",
                history.indexOf(message) >= lastKeptIndex - kept.size + 1,
            )
        }
        assertTrue(rendered(out).contains("turn 100 "))
        assertFalse("oldest turn should be gone", rendered(out).contains("turn 1 "))
    }

    /**
     * A half-truncated JSON observation teaches the model that JSON may be
     * malformed. Every surviving turn must be the ORIGINAL object.
     */
    @Test
    fun `no message is ever sliced mid-string`() {
        val json = """{"events":[{"title":"Standup","start":"09:00"},{"title":"1:1","start":"11:00"}]}"""
        val history = (1..40).map { i ->
            if (i % 3 == 0) {
                ChatMessage.ToolObservation("calendar.search", "$json #$i", success = i % 6 != 0)
            } else {
                ChatMessage.Assistant("assistant turn $i " + "y".repeat(300))
            }
        }

        val out = DefaultContextBuilder(workingLimit = 900).build("task", history, emptyList(), emptyList())

        val originals = history.toSet()
        out.forEach { message ->
            if (message is ChatMessage.System) return@forEach
            if (message is ChatMessage.User) return@forEach
            assertTrue(
                "message was rebuilt rather than reused: ${message::class.simpleName}",
                originals.contains(message),
            )
        }

        // And nothing ends mid-token: a reused observation is byte-complete.
        out.filterIsInstance<ChatMessage.ToolObservation>().forEach { observation ->
            assertTrue(
                "observation was sliced: ${observation.observation}",
                history.any { it === observation } && json in observation.observation,
            )
        }
    }

    @Test
    fun `the task is not duplicated when history opens with the same turn`() {
        val task = "share the pdf i downloaded yesterday"
        val history = listOf(
            ChatMessage.User(task),
            ChatMessage.Assistant("I'll look in the Downloads folder."),
        )
        val out = DefaultContextBuilder().build(task, history, emptyList(), emptyList())

        val occurrences = out.count { it is ChatMessage.User && it.text == task }
        assertEquals("the task must appear exactly once", 1, occurrences)
    }

    @Test
    fun `a merely similar opening turn is kept`() {
        val history = listOf(
            ChatMessage.User("share the pdf i downloaded yesterday please"),
            ChatMessage.Assistant("Looking."),
        )
        val out = DefaultContextBuilder()
            .build("share the pdf i downloaded yesterday", history, emptyList(), emptyList())

        assertTrue("only an EXACT duplicate may be dropped", rendered(out).contains("please"))
    }

    @Test
    fun `empty history and empty memories produce the minimal prompt`() {
        val out = DefaultContextBuilder().build("task", emptyList(), emptyList(), emptyList())

        assertEquals(2, out.size)
        assertTrue(out[0] is ChatMessage.System)
        assertEquals("task", (out[1] as ChatMessage.User).text)
    }

    @Test
    fun `a blank task is not rendered as an empty user turn`() {
        val out = DefaultContextBuilder().build("   ", emptyList(), emptyList(), emptyList())

        assertEquals(1, out.size)
        assertTrue(out[0] is ChatMessage.System)
    }

    @Test
    fun `a leading system message that is not a summary stays as a normal turn`() {
        val stray = ChatMessage.System("You are a helpful assistant with a long preamble.")
        val out = DefaultContextBuilder().build("task", listOf(stray), emptyList(), emptyList())

        // Not recognised as a summary, so it is just another turn in the window.
        assertSame(stray, out.last())
        assertTrue(out.size >= 2)
    }

    @Test
    fun `summary is lifted out of history rather than duplicated as a turn`() {
        val compactor = ContextCompactor()
        val summary = compactor.summaryMessage(CompactedState(task = "t", failures = listOf("x FAILED: y")))
        val out = DefaultContextBuilder().build("t", listOf(summary) + turns(3), emptyList(), emptyList())

        val summaryCount = out.count { it is ChatMessage.System && it.text.startsWith(SUMMARY_PREFIX) }
        assertEquals(1, summaryCount)
        assertSame(summary, out[1])
    }

    @Test
    fun `window is bounded so a huge session cannot blow the budget`() {
        val out = DefaultContextBuilder(workingLimit = 6_000)
            .build("task", turns(10_000), emptyList(), emptyList())

        // 4 fixed slots at most + the 64-message scan window.
        assertTrue("output too large: ${out.size}", out.size <= 4 + ContextLimits.MAX_HISTORY_SCAN)
        assertTrue(totalTokens(out) <= 6_000)
    }

    @Test
    fun `the built prompt is under the architecture target for a routine task`() {
        val compactor = ContextCompactor()
        val summary = compactor.summaryMessage(CompactedState(task = "find the pdf i downloaded yesterday"))
        val out = DefaultContextBuilder().build(
            task = "find the pdf i downloaded yesterday and share it",
            history = listOf(summary) + turns(6),
            memories = (1..2).map { memory(it.toLong(), "downloads land in /storage/emulated/0/Download") },
            tools = listOf(tool("storage.search"), tool("storage.share"), tool("contacts.search")),
        )

        assertNotNull(out.first())
        // 3-6K is the section 9 working target. A routine task must sit well
        // under the 20K prefill the architecture calls a hard failure.
        assertTrue("routine prompt was ${totalTokens(out)} tokens", totalTokens(out) < 2_000)
    }
}
