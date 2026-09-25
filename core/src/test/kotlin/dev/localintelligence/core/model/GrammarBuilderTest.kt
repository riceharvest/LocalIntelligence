package dev.localintelligence.core.model

import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarBuilderTest {

    // -------------------------------------------------------------- shape

    @Test
    fun grammarForOneToolIsNonEmptyAndSyntacticallyPlausible() {
        val grammar = GrammarBuilder.forActions(listOf(calendarSearch()))
        assertTrue(grammar.isNotBlank())
        assertStructure(grammar)
        assertTrue(grammar.startsWith("root ::= "))
    }

    @Test
    fun everyRuleIsAWellFormedLineWithBalancedDelimiters() {
        val grammar = GrammarBuilder.forActions(manyTools())
        val ruleNames = assertStructure(grammar)

        // Every referenced rule must be defined, or llama.cpp rejects the grammar.
        val undefined = MiniGbnf.compile(grammar).referencedRules() - ruleNames
        assertTrue("grammar references undefined rules: $undefined", undefined.isEmpty())
    }

    @Test
    fun zeroToolsYieldsARespondOnlyGrammar() {
        val grammar = GrammarBuilder.forActions(emptyList())
        assertStructure(grammar)
        assertTrue(grammar.contains("action ::= resp"))
        assertFalse(grammar.contains("toolcall"))
        assertTrue(grammar.contains("""resp ::= "<respond>" """.trimEnd()))
    }

    @Test
    fun toolNamesAreLiteralAlternativesOnTheToolcallRule() {
        val names = listOf("calendar.search", "contacts.search", "battery.read")
        val grammar = GrammarBuilder.forActions(names.map { tool(it) })
        val toolcall = grammar.lines().first { it.startsWith("toolcall ::= ") }

        val alternatives = toolcall.removePrefix("toolcall ::= ").split(" | ")
        assertEquals(3, alternatives.size)
        for ((index, name) in names.withIndex()) {
            assertEquals(
                gbnfLiteral("<tool name=\"$name\">") + " args${index + 1} " + gbnfLiteral("</tool>"),
                alternatives[index],
            )
        }
    }

    /** An independent re-implementation of GBNF literal quoting, so the assertions are not tautological. */
    private fun gbnfLiteral(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun containsEveryToolNameThatWasPassed() {
        val names = listOf("calendar.search", "contacts.search", "battery.read")
        val grammar = GrammarBuilder.forActions(names.map { tool(it) })
        for (name in names) {
            assertTrue("missing literal for $name", grammar.contains(""""<tool name=\"$name\">""""))
        }
    }

    @Test
    fun doesNotContainAToolNameThatWasNotPassed() {
        val grammar = GrammarBuilder.forActions(listOf(calendarSearch(), contactsSearch()))
        assertFalse(grammar.contains("battery.read"))
        assertFalse(grammar.contains("sms.send"))
        assertFalse(grammar.contains("calendar.create"))
    }

    @Test
    fun duplicateToolNamesAreDeduplicatedInsteadOfCrashing() {
        val grammar = GrammarBuilder.forActions(
            listOf(calendarSearch(), calendarSearch(), contactsSearch(), calendarSearch()),
        )
        val toolcall = grammar.lines().first { it.startsWith("toolcall ::= ") }
        assertEquals(2, toolcall.removePrefix("toolcall ::= ").split(" | ").size)
        assertStructure(grammar)
    }

    @Test
    fun blankToolNamesAreDroppedInsteadOfProducingAnUnusableAlternative() {
        val grammar = GrammarBuilder.forActions(listOf(tool("   "), calendarSearch()))
        assertFalse(grammar.contains("""name=\"   \""""))
        assertStructure(grammar)
    }

    @Test
    fun schemaShapesAreTranslatedIntoArgumentRules() {
        val grammar = GrammarBuilder.forActions(
            listOf(
                tool(
                    "demo.tool",
                    properties = mapOf("q" to "string", "n" to "integer", "f" to "number", "b" to "boolean"),
                    required = listOf("q"),
                ),
            ),
        )
        val rule = grammar.lines().first { it.startsWith("args1 ::= ") }
        assertTrue(rule, rule.contains(gbnfLiteral("\"q\"") + " ws " + gbnfLiteral(":") + " ws string"))
        assertTrue(rule, rule.contains(gbnfLiteral("\"n\"") + " ws " + gbnfLiteral(":") + " ws integer"))
        assertTrue(rule, rule.contains(gbnfLiteral("\"f\"") + " ws " + gbnfLiteral(":") + " ws number"))
        assertTrue(rule, rule.contains(gbnfLiteral("\"b\"") + " ws " + gbnfLiteral(":") + " ws boolean"))
        // Required key first, optional keys after, each at most once.
        assertTrue(
            "required must come first",
            rule.indexOf(gbnfLiteral("\"q\"")) < rule.indexOf(gbnfLiteral("\"n\"")),
        )
    }

    @Test
    fun enumsAreConstrainedToTheirLiteralValues() {
        val grammar = GrammarBuilder.forActions(
            listOf(
                tool(
                    "demo.tool",
                    properties = mapOf("unit" to "string"),
                    enums = mapOf("unit" to listOf("celsius", "fahrenheit")),
                ),
            ),
        )
        val rule = grammar.lines().first { it.startsWith("args1 ::= ") }
        assertTrue(
            rule,
            rule.contains(
                gbnfLiteral("\"unit\"") + " ws " + gbnfLiteral(":") + " ws (" +
                    gbnfLiteral("\"celsius\"") + " | " + gbnfLiteral("\"fahrenheit\"") + ")",
            ),
        )
    }

    @Test
    fun aToolWithNoPropertiesOnlyAllowsAnEmptyObject() {
        val grammar = GrammarBuilder.forActions(listOf(tool("no.args", properties = emptyMap())))
        val rule = grammar.lines().first { it.startsWith("args1 ::= ") }
        assertEquals("args1 ::= ws " + gbnfLiteral("{") + " ws " + gbnfLiteral("}"), rule)
    }

    @Test
    fun nestedObjectsAndArraysFallBackToTheGenericJsonRules() {
        val grammar = GrammarBuilder.forActions(
            listOf(tool("nested.tool", properties = mapOf("filter" to "object", "tags" to "array"))),
        )
        val rule = grammar.lines().first { it.startsWith("args1 ::= ") }
        assertTrue(rule, rule.contains(gbnfLiteral("\"filter\"") + " ws " + gbnfLiteral(":") + " ws object"))
        assertTrue(rule, rule.contains(gbnfLiteral("\"tags\"") + " ws " + gbnfLiteral(":") + " ws array"))
    }

    @Test
    fun isDeterministicAcrossCalls() {
        val tools = manyTools()
        val first = GrammarBuilder.forActions(tools)
        repeat(50) { assertEquals(first, GrammarBuilder.forActions(tools)) }

        val firstHint = GrammarBuilder.hint(tools)
        repeat(50) { assertEquals(firstHint, GrammarBuilder.hint(tools)) }
    }

    @Test
    fun toolOrderIsHonoured() {
        val a = GrammarBuilder.forActions(listOf(calendarSearch(), contactsSearch()))
        val b = GrammarBuilder.forActions(listOf(contactsSearch(), calendarSearch()))
        assertTrue(a != b) // deterministic, not order-insensitive: the first alternative is the first tool
    }

    // -------------------------------------------------------------- acceptance

    @Test
    fun generatedGrammarAcceptsAWellFormedRespond() {
        val grammar = GrammarBuilder.forActions(manyTools())
        val matcher = MiniGbnf.compile(grammar)
        assertTrue(matcher.accepts("<respond>You have 2 events tomorrow.</respond>"))
        assertTrue(matcher.accepts("<respond>héllo 🎉</respond>"))
    }

    @Test
    fun generatedGrammarAcceptsAWellFormedToolCall() {
        val matcher = MiniGbnf.compile(GrammarBuilder.forActions(manyTools()))
        assertTrue("calendar.search with a string arg", matcher.accepts("""<tool name="calendar.search">{"query":"alice"}</tool>"""))
        assertTrue("battery.read with a boolean arg", matcher.accepts("""<tool name="battery.read">{"percent":true}</tool>"""))
    }

    @Test
    fun generatedGrammarRejectsAToolNameOutsideTheSet() {
        val matcher = MiniGbnf.compile(GrammarBuilder.forActions(manyTools()))
        assertFalse(
            "a hallucinated tool name must be unspeakable",
            matcher.accepts("""<tool name="battery.write">{"percent":true}</tool>"""),
        )
        assertFalse(matcher.accepts("""<tool name="calendar.searchX">{}</tool>"""))
    }

    @Test
    fun generatedGrammarRejectsAMissingRequiredArgument() {
        val matcher = MiniGbnf.compile(GrammarBuilder.forActions(manyTools()))
        assertFalse(
            "query is required, so {} must not be speakable",
            matcher.accepts("""<tool name="calendar.search">{}</tool>"""),
        )
    }

    @Test
    fun generatedGrammarAllowsOptionalArguments() {
        val matcher = MiniGbnf.compile(GrammarBuilder.forActions(manyTools()))
        assertTrue("optional arg present", matcher.accepts("""<tool name="calendar.search">{"query":"a","days":2}</tool>"""))
        assertTrue("spaces around the colon", matcher.accepts("""<tool name="calendar.search">{ "query" : "a" }</tool>"""))
    }

    @Test
    fun generatedGrammarRejectsProseAndExtraActions() {
        val matcher = MiniGbnf.compile(GrammarBuilder.forActions(manyTools()))
        assertFalse(matcher.accepts("Sure, let me check your calendar."))
        assertFalse(matcher.accepts("<respond>hi</respond><tool name=\"battery.read\">{}</tool>"))
        assertFalse(matcher.accepts("<respond></respond>"))
        assertFalse(matcher.accepts(""))
    }

    @Test
    fun respondOnlyGrammarRejectsEveryToolCall() {
        val matcher = MiniGbnf.compile(GrammarBuilder.forActions(emptyList()))
        assertTrue(matcher.accepts("<respond>hi</respond>"))
        assertFalse(matcher.accepts("""<tool name="calendar.search">{}</tool>"""))
    }

    // -------------------------------------------------------------- hint

    @Test
    fun hintNamesTheLegalShapesAndTools() {
        val hint = GrammarBuilder.hint(manyTools())
        assertTrue(hint.contains("<respond>"))
        assertTrue(hint.contains("<tool"))
        assertTrue(hint.contains("calendar.search"))
        assertTrue(hint.contains("battery.read"))
        assertFalse(hint.contains("\n"))
    }

    @Test
    fun hintWithNoToolsSaysRespondOnly() {
        val hint = GrammarBuilder.hint(emptyList())
        assertTrue(hint.contains("<respond>"))
        assertFalse(hint.contains("<tool"))
    }

    @Test
    fun hintCapsALongNameList() {
        val tools = (1..20).map { tool("tool.number$it") }
        val hint = GrammarBuilder.hint(tools)
        assertTrue(hint.contains("tool.number8"))
        assertFalse(hint.contains("tool.number9"))
        assertTrue(hint.contains("+12 more"))
    }

    // -------------------------------------------------------------- helpers

    private fun manyTools(): List<ToolDefinition> = listOf(
        calendarSearch(),
        contactsSearch(),
        tool(
            "battery.read",
            properties = mapOf("percent" to "boolean"),
            required = listOf("percent"),
        ),
    )

    private fun calendarSearch() = tool(
        "calendar.search",
        properties = linkedMapOf("query" to "string", "days" to "integer"),
        required = listOf("query"),
    )

    private fun contactsSearch() = tool("contacts.search", properties = mapOf("query" to "string"))

    private fun tool(
        name: String,
        properties: Map<String, String> = emptyMap(),
        required: List<String> = emptyList(),
        enums: Map<String, List<String>> = emptyMap(),
    ): ToolDefinition = ToolDefinition(
        name = name,
        description = "Test tool $name.",
        category = "test",
        schema = schema(properties, required, enums),
        risk = ToolRisk.READ_ONLY,
    )

    private fun schema(
        properties: Map<String, String>,
        required: List<String>,
        enums: Map<String, List<String>>,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            properties.forEach { (key, type) ->
                if (enums.containsKey(key)) {
                    put(key, buildJsonObject {
                        put(
                            "enum",
                            buildJsonArray { enums.getValue(key).forEach { add(it) } },
                        )
                    })
                } else {
                    put(key, buildJsonObject { put("type", type) })
                }
            }
        })
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach { add(it) } })
        }
    }

    /** Returns the set of defined rule names after checking every line parses. */
    private fun assertStructure(grammar: String): Set<String> {
        val names = LinkedHashSet<String>()
        grammar.lines().filter { it.isNotBlank() }.forEach { line ->
            val sep = line.indexOf("::=")
            assertTrue("rule has no '::=' -> $line", sep > 0)
            val name = line.substring(0, sep).trim()
            assertTrue("bad rule name '$name'", Regex("""^[a-z][a-z0-9]*$""").matches(name))
            val body = line.substring(sep + 3).trim()
            assertTrue("empty rule body -> $line", body.isNotEmpty())
            assertBalanced(body, line)
            names += name
        }
        assertTrue("grammar has no rules", names.isNotEmpty())
        assertTrue("grammar must start at root", "root" in names)
        assertEquals("root must be the first rule", "root", names.first())
        return names
    }

    private fun assertBalanced(text: String, line: String) {
        var inString = false
        var inClass = false
        var parens = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> i++ // escape inside a literal or class
                inString -> if (c == '"') inString = false
                inClass -> if (c == ']') inClass = false
                c == '"' -> inString = true
                c == '[' -> inClass = true
                c == '(' -> parens++
                c == ')' -> { parens--; assertTrue("unbalanced parens -> $line", parens >= 0) }
            }
            i++
        }
        assertTrue("unclosed string -> $line", !inString)
        assertTrue("unclosed char class -> $line", !inClass)
        assertEquals("unbalanced parens -> $line", 0, parens)
    }
}

/**
 * A deliberately small GBNF matcher: rule references, string literals, character
 * classes, groups, alternation, and `?`/`*`/`+`. It is not llama.cpp — but it is
 * enough to make "the generated grammar accepts a well-formed action and rejects a
 * hallucinated tool name" a verified claim rather than a hopeful one.
 */
private class MiniGbnf private constructor(private val rules: Map<String, Node>) {

    private sealed interface Node
    private data class Lit(val text: String) : Node
    private data class Cls(val negated: Boolean, val ranges: List<IntRange>) : Node
    private data class Seq(val items: List<Node>) : Node
    private data class Alt(val options: List<Node>) : Node
    private data class Rep(val item: Node, val min: Int, val max: Int) : Node
    private data class Ref(val name: String) : Node

    fun accepts(input: String): Boolean {
        val root = rules["root"] ?: return false
        return match(root, input, 0, 0) { it == input.length }
    }

    /** Every rule name the grammar refers to, including the root. */
    fun referencedRules(): Set<String> {
        val out = LinkedHashSet<String>()
        for (node in rules.values) collectRefs(node, out)
        return out
    }

    private fun collectRefs(node: Node, out: MutableSet<String>) {
        when (node) {
            is Lit, is Cls -> Unit
            is Ref -> out += node.name
            is Seq -> node.items.forEach { collectRefs(it, out) }
            is Alt -> node.options.forEach { collectRefs(it, out) }
            is Rep -> collectRefs(node.item, out)
        }
    }

    private fun match(node: Node, s: String, pos: Int, depth: Int, k: (Int) -> Boolean): Boolean {
        if (depth > MAX_DEPTH) return false
        return when (node) {
            is Lit -> s.startsWith(node.text, pos) && k(pos + node.text.length)
            is Cls -> pos < s.length && classHit(node, s[pos]) && k(pos + 1)
            is Ref -> rules[node.name]?.let { match(it, s, pos, depth + 1, k) } ?: false
            is Seq -> sequence(node.items, 0, s, pos, depth, k)
            is Alt -> node.options.any { match(it, s, pos, depth + 1, k) }
            is Rep -> repeat(node, 0, s, pos, depth, k)
        }
    }

    private fun sequence(
        items: List<Node>,
        index: Int,
        s: String,
        pos: Int,
        depth: Int,
        k: (Int) -> Boolean,
    ): Boolean = if (index == items.size) {
        k(pos)
    } else {
        match(items[index], s, pos, depth + 1) { p -> sequence(items, index + 1, s, p, depth, k) }
    }

    private fun repeat(
        node: Rep,
        count: Int,
        s: String,
        pos: Int,
        depth: Int,
        k: (Int) -> Boolean,
    ): Boolean {
        if (count < node.min) {
            return match(node.item, s, pos, depth + 1) { p -> repeat(node, count + 1, s, p, depth, k) }
        }
        if (node.max < 0 || count < node.max) {
            // The progress guard keeps a zero-width body from looping forever.
            if (match(node.item, s, pos, depth + 1) { p ->
                    if (p > pos) repeat(node, count + 1, s, p, depth, k) else false
                }
            ) {
                return true
            }
        }
        return k(pos)
    }

    private fun classHit(node: Cls, c: Char): Boolean {
        val hit = node.ranges.any { c.code in it }
        return if (node.negated) !hit else hit
    }

    companion object {
        const val MAX_DEPTH = 5_000

        fun compile(gbnf: String): MiniGbnf = MiniGbnf(parseRules(gbnf))

        private fun parseRules(gbnf: String): Map<String, Node> {
            val out = LinkedHashMap<String, Node>()
            for (rawLine in gbnf.split('\n')) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val sep = line.indexOf("::=")
                check(sep > 0) { "not a rule: $line" }
                val name = line.substring(0, sep).trim()
                val body = line.substring(sep + 3)
                val parser = ExprParser(body, line)
                val node = parser.alternation()
                parser.skipWhitespace()
                check(parser.atEnd()) { "trailing junk in: $line" }
                out[name] = node
            }
            return out
        }

        /** Decodes the escape llgrammar implements, and nothing else. */
        fun escape(s: String, i: Int): Pair<Int, Int> = when (val c = s[i + 1]) {
            'n' -> 10 to i + 2
            'r' -> 13 to i + 2
            't' -> 9 to i + 2
            'b' -> 8 to i + 2
            'f' -> 12 to i + 2
            'x' -> s.substring(i + 2, i + 4).toInt(16) to i + 4
            'u' -> s.substring(i + 2, i + 6).toInt(16) to i + 6
            'U' -> s.substring(i + 2, i + 10).toInt(16) to i + 10
            else -> c.code to i + 2
        }

        fun classAt(s: String, start: Int): Triple<Boolean, List<IntRange>, Int> {
            var i = start + 1
            var negated = false
            if (i < s.length && s[i] == '^') {
                negated = true
                i++
            }
            val ranges = mutableListOf<IntRange>()
            while (i < s.length && s[i] != ']') {
                val lo: Int
                val afterLo: Int
                if (s[i] == '\\') {
                    val (code, next) = escape(s, i)
                    lo = code; afterLo = next
                } else {
                    lo = s[i].code; afterLo = i + 1
                }
                if (afterLo < s.length && s[afterLo] == '-' && afterLo + 1 < s.length && s[afterLo + 1] != ']') {
                    val hi: Int
                    val afterHi: Int
                    if (s[afterLo + 1] == '\\') {
                        val (code, next) = escape(s, afterLo + 1)
                        hi = code; afterHi = next
                    } else {
                        hi = s[afterLo + 1].code; afterHi = afterLo + 2
                    }
                    ranges += lo..hi
                    i = afterHi
                } else {
                    ranges += lo..lo
                    i = afterLo
                }
            }
            return Triple(negated, ranges, i + 1)
        }
    }

    private class ExprParser(private val s: String, private val line: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun alternation(): Node {
            val options = mutableListOf(sequence())
            while (true) {
                skipWhitespace()
                if (pos < s.length && s[pos] == '|') {
                    pos++
                    options += sequence()
                } else {
                    break
                }
            }
            return if (options.size == 1) options[0] else Alt(options)
        }

        private fun sequence(): Node {
            val items = mutableListOf<Node>()
            while (true) {
                skipWhitespace()
                if (pos >= s.length) break
                val c = s[pos]
                if (c == '|' || c == ')') break
                var item = atom()
                skipWhitespace()
                while (pos < s.length && (s[pos] == '*' || s[pos] == '+' || s[pos] == '?')) {
                    val quantifier = s[pos]
                    pos++
                    item = when (quantifier) {
                        '*' -> Rep(item, 0, -1)
                        '+' -> Rep(item, 1, -1)
                        else -> Rep(item, 0, 1)
                    }
                    skipWhitespace()
                }
                items += item
            }
            return if (items.size == 1) items[0] else Seq(items)
        }

        private fun atom(): Node {
            check(pos < s.length) { "unexpected end of rule: $line" }
            return when (val c = s[pos]) {
                '"' -> {
                    val out = StringBuilder()
                    var i = pos + 1
                    var closed = false
                    while (i < s.length) {
                        val ch = s[i]
                        if (ch == '\\') {
                            val (code, next) = escape(s, i)
                            out.append(code.toChar())
                            i = next
                        } else if (ch == '"') {
                            i++
                            closed = true
                            break
                        } else {
                            out.append(ch)
                            i++
                        }
                    }
                    check(closed) { "unterminated literal in: $line" }
                    pos = i
                    Lit(out.toString())
                }
                '[' -> {
                    val (negated, ranges, next) = classAt(s, pos)
                    pos = next
                    Cls(negated, ranges)
                }
                '(' -> {
                    pos++
                    val inner = alternation()
                    skipWhitespace()
                    check(pos < s.length && s[pos] == ')') { "unclosed group in: $line" }
                    pos++
                    inner
                }
                else -> {
                    if (c.isLetter()) {
                        val start = pos
                        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] == '_')) pos++
                        Ref(s.substring(start, pos))
                    } else {
                        // llgrammar also accepts a bare character as a terminal, but
                        // never one of its structural characters.
                        check(c !in "|*+?:,={}<>") { "unexpected character '$c' in: $line" }
                        pos++
                        Lit(c.toString())
                    }
                }
            }
        }
    }
}
