package dev.localintelligence.core.agent

import dev.localintelligence.core.model.ToolArgs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ActionParserImplTest {

    private val parser: ActionParser = ActionParserImpl
    private val tools = setOf("calendar.search", "contacts.search")

    // -------------------------------------------------------------- respond

    @Test
    fun parsesRespond() {
        val result = parser.parse("<respond>You have 2 events tomorrow.</respond>", tools)
        assertEquals(
            ActionParseResult.Parsed(AgentAction.Respond("You have 2 events tomorrow.")),
            result,
        )
    }

    @Test
    fun respondIsTrimmedAndSurroundingWhitespaceIgnored() {
        val result = parser.parse("\n  <respond>  hi  </respond>  \n", tools)
        assertEquals(ActionParseResult.Parsed(AgentAction.Respond("hi")), result)
    }

    @Test
    fun unicodeAndEmojiSurviveUnchanged() {
        val text = "héllo 🎉 世界 — ½"
        val result = parser.parse("<respond>$text</respond>", tools)
        assertEquals(ActionParseResult.Parsed(AgentAction.Respond(text)), result)
    }

    @Test
    fun respondContainingTheLiteralToolTagIsNotReparsed() {
        val inner = """Use <tool name="calendar.search">{"query":"alice"}</tool> to call a tool."""
        val result = parser.parse("<respond>$inner</respond>", tools)
        assertEquals(ActionParseResult.Parsed(AgentAction.Respond(inner)), result)
    }

    @Test
    fun respondMissingItsClosingTagIsTolerated() {
        val result = parser.parse("<respond>partial answer", tools)
        assertEquals(ActionParseResult.Parsed(AgentAction.Respond("partial answer")), result)
    }

    @Test
    fun emptyRespondIsMalformedRatherThanABlankAnswer() {
        val result = parser.parse("<respond>   </respond>", tools)
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
    }

    @Test
    fun firstActionWinsWhenTheModelEmitsTwo() {
        val raw = "<respond>hi</respond>\n<tool name=\"calendar.search\">{}</tool>"
        assertEquals(ActionParseResult.Parsed(AgentAction.Respond("hi")), parser.parse(raw, tools))
    }

    // -------------------------------------------------------------- tool calls

    @Test
    fun parsesToolCall() {
        val raw = """<tool name="calendar.search">{"query":"alice"}</tool>"""
        val result = parser.parse(raw, tools)
        val parsed = result as ActionParseResult.Parsed
        val call = parsed.action as AgentAction.CallTool
        assertEquals("calendar.search", call.name)
        assertEquals("alice", (call.arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun parsesToolCallWithMultipleArgumentTypes() {
        val raw = """<tool name="contacts.search">{"query":"a","limit":5,"deep":true}</tool>"""
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals(3, call.arguments.size)
        assertEquals("5", (call.arguments["limit"] as JsonPrimitive).content)
    }

    @Test
    fun emptyToolBodyIsAnEmptyObjectAndInventsNothing() {
        val raw = """<tool name="calendar.search"></tool>"""
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals(ToolArgs(emptyMap()), call.arguments)
    }

    @Test
    fun toolNameAttributeWithoutQuotesIsAccepted() {
        val raw = "<tool name=calendar.search>{\"query\":\"a\"}</tool>"
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("calendar.search", call.name)
    }

    @Test
    fun tagNamesAreMatchedCaseInsensitively() {
        val raw = "<TOOL NAME=\"calendar.search\">{\"query\":\"a\"}</TOOL>"
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("calendar.search", call.name)
    }

    @Test
    fun unknownToolIsMalformedAndNamesTheLegalOptions() {
        val raw = """<tool name="sms.send">{"to":"+31600000000"}</tool>"""
        val result = parser.parse(raw, tools)
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
        val reason = (result as ActionParseResult.Malformed).reason
        assertTrue("reason should name the bad tool: $reason", reason.contains("sms.send"))
        assertTrue("reason should list the legal tools: $reason", reason.contains("calendar.search"))
        assertTrue("reason should list the legal tools: $reason", reason.contains("contacts.search"))
    }

    @Test
    fun toolCallWhenNoToolsAreOfferedIsMalformed() {
        val raw = """<tool name="calendar.search">{}</tool>"""
        val result = parser.parse(raw, emptySet())
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
        val reason = (result as ActionParseResult.Malformed).reason
        assertTrue("reason should say no tools exist: $reason", reason.contains("No tools are available"))
    }

    @Test
    fun toolTagWithoutANameIsMalformed() {
        val raw = "<tool>{\"query\":\"a\"}</tool>"
        val result = parser.parse(raw, tools)
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
    }

    // -------------------------------------------------------------- JSON repair

    @Test
    fun repairsFencedJsonInsideTheToolTag() {
        val raw = "<tool name=\"calendar.search\">\n```json\n{\"query\":\"alice\"}\n```\n</tool>"
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("alice", (call.arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun repairsAFencedWholeOutput() {
        val raw = "```\n<tool name=\"calendar.search\">{\"query\":\"alice\"}</tool>\n```"
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("calendar.search", call.name)
    }

    @Test
    fun repairsTrailingComma() {
        val raw = """<tool name="calendar.search">{"query":"alice",}</tool>"""
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals(1, call.arguments.size)
    }

    @Test
    fun repairsSingleQuotedStrings() {
        val raw = "<tool name=\"calendar.search\">{'query': 'alice'}</tool>"
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("alice", (call.arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun repairsUnquotedKeys() {
        val raw = "<tool name=\"calendar.search\">{query: \"alice\"}</tool>"
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("alice", (call.arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun repairDoesNotCorruptDoubleQuotedStrings() {
        val raw = """<tool name="calendar.search">{"note":"it's fine, really"}</tool>"""
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("it's fine, really", (call.arguments["note"] as JsonPrimitive).content)
    }

    @Test
    fun liftsAJsonObjectOutOfSurroundingProse() {
        val raw = "<tool name=\"calendar.search\">Here you go: {\"query\":\"alice\"} -- done</tool>"
        val call = (parser.parse(raw, tools) as ActionParseResult.Parsed).action as AgentAction.CallTool
        assertEquals("alice", (call.arguments["query"] as JsonPrimitive).content)
    }

    @Test
    fun neverInventsAMissingArgumentValue() {
        // A key with no value, and a value with no key, are both unspeakable JSON.
        for (raw in listOf(
            """<tool name="calendar.search">{"query": }</tool>""",
            """<tool name="calendar.search">{: "alice"}</tool>""",
            """<tool name="calendar.search">alice</tool>""",
        )) {
            val result = parser.parse(raw, tools)
            assertTrue("expected Malformed for $raw, got $result", result is ActionParseResult.Malformed)
        }
    }

    @Test
    fun malformedJsonIsMalformed() {
        val raw = """<tool name="calendar.search">{"query": "alice</tool>"""
        val result = parser.parse(raw, tools)
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
        assertEquals(raw, (result as ActionParseResult.Malformed).raw)
    }

    @Test
    fun nonObjectArgumentsAreMalformed() {
        for (raw in listOf(
            """<tool name="calendar.search">[1,2,3]</tool>""",
            """<tool name="calendar.search">"alice"</tool>""",
        )) {
            val result = parser.parse(raw, tools)
            assertTrue("expected Malformed for $raw, got $result", result is ActionParseResult.Malformed)
        }
    }

    @Test
    fun absurdlyLongArgumentsAreRejectedWithoutParsing() {
        val raw = """<tool name="calendar.search">{"q":"""" + "x".repeat(20_000) + "\"}</tool>"
        val result = parser.parse(raw, tools)
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
        assertTrue((result as ActionParseResult.Malformed).reason.contains("too long"))
    }

    // -------------------------------------------------------------- prose and emptiness

    @Test
    fun proseInsteadOfACallIsMalformed() {
        val raw = "Sure! Let me check your calendar for tomorrow morning."
        val result = parser.parse(raw, tools)
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
        val reason = (result as ActionParseResult.Malformed).reason
        assertTrue("reason should teach the format: $reason", reason.contains("<respond>"))
    }

    @Test
    fun emptyStringIsMalformed() {
        val result = parser.parse("", tools)
        assertTrue("expected Malformed, got $result", result is ActionParseResult.Malformed)
        assertEquals("", (result as ActionParseResult.Malformed).raw)
    }

    @Test
    fun whitespaceOnlyStringIsMalformed() {
        assertTrue(parser.parse("   \n\t  ", tools) is ActionParseResult.Malformed)
    }

    @Test
    fun malformedAlwaysCarriesTheOriginalRawOutput() {
        val raw = "total nonsense 🎉"
        val result = parser.parse(raw, tools) as ActionParseResult.Malformed
        assertEquals(raw, result.raw)
        assertTrue(result.reason.isNotBlank())
    }

    // -------------------------------------------------------------- property tests

    @Test
    fun everyWellFormedActionParses() {
        val names = listOf("calendar.search", "contacts.search")
        val random = Random(20260925L)
        repeat(400) {
            val name = names[random.nextInt(names.size)]
            val args = buildJsonObject {
                put("query", listOf("alice", "", "a b c", "ünï🎉", "{braces}")[random.nextInt(5)])
                if (random.nextBoolean()) put("limit", random.nextInt(0, 100))
            }
            val raw = """<tool name="$name">$args</tool>"""
            val call = (parser.parse(raw, tools.toSet()) as ActionParseResult.Parsed)
                .action as AgentAction.CallTool
            assertEquals(name, call.name)
            assertTrue(call.arguments.containsKey("query"))
        }
    }

    @Test
    fun fuzzNeverThrows() {
        val random = Random(1337L)
        val pool = listOf(
            "<", ">", "/", "\"", "'", "=", "{", "}", "[", "]", ":", ",", "\\", "`",
            "tool", "respond", "name", "json", "```", "\n", "\r", "\t", " ", "a", "Z",
            "0", "-", "é", "🎉", "", "\u0000", "<tool>", "</tool>", "<respond>",
            "</respond>", "calendar.search", "contacts.search",
        )
        val seeds = listOf(
            """<tool name="calendar.search">{"query":"alice"}</tool>""",
            """<respond>hello</respond>""",
            """<tool name=""></tool>""",
        )

        val corpus = ArrayList<String>(6000)
        repeat(3000) {
            val sb = StringBuilder()
            repeat(random.nextInt(0, 24)) { sb.append(pool[random.nextInt(pool.size)]) }
            corpus += sb.toString()
        }
        repeat(1500) {
            val seed = seeds[random.nextInt(seeds.size)]
            corpus += seed.substring(0, random.nextInt(0, seed.length + 1))
        }
        repeat(500) {
            val seed = seeds[random.nextInt(seeds.size)]
            corpus += seed + pool[random.nextInt(pool.size)] + seed
        }
        // Pathological nesting: a recursive JSON parser would blow the stack here.
        repeat(200) { corpus += "[".repeat(random.nextInt(1, 4000)) }
        repeat(200) { corpus += "\uD83C" } // lone surrogate
        repeat(200) { corpus += "￿" } // lone BMP-1 noncharacter

        val allowed = setOf("calendar.search", "contacts.search", "")
        for (input in corpus) {
            val result = try {
                parser.parse(input, allowed)
            } catch (t: Throwable) {
                throw AssertionError("parser threw on ${input.take(80)}: $t", t)
            }
            when (result) {
                is ActionParseResult.Malformed -> assertEquals(input, result.raw)
                is ActionParseResult.Parsed -> when (val action = result.action) {
                    // Whatever comes out, it must be self-consistent: no blank
                    // answer, and never a call to a tool that was not on offer.
                    is AgentAction.Respond -> assertTrue(action.text.isNotBlank())
                    is AgentAction.CallTool -> assertTrue(
                        "call to ${action.name} from a fuzz input",
                        action.name in allowed,
                    )
                }
            }
        }
    }
}
