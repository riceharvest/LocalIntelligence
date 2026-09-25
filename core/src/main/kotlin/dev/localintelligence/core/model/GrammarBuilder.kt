package dev.localintelligence.core.model

import dev.localintelligence.core.tool.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * GBNF for the action protocol. See `ActionParserImpl` in :core/agent for the
 * format itself — the two must change together.
 *
 * The point of the grammar is the `toolcall` rule: the tool name is a literal
 * alternation over the *currently visible* set, so a hallucinated tool name is not
 * "rejected downstream", it is unspeakable. Argument shape comes from each tool's
 * JSON Schema.
 *
 * Two deliberate limits, both in service of a 1B model:
 *  - only top-level argument properties are constrained. Nested objects and arrays
 *    fall back to the generic `object`/`array`/`value` JSON rules, because a model
 *    that cannot hit the outer shape will not hit a deep one either.
 *  - `required` keys come first, then optional keys, each at most once, in schema
 *    order. Key order is therefore fixed, which measurably helps small models and
 *    costs us nothing.
 */
object GrammarBuilder {

    /** GBNF literal for one double-quote character. */
    private const val DQ = "\"\\\"\""

    /** GBNF literal for one backslash character. */
    private const val BS = "\"\\\\\""

    /** The same two characters, unquoted, for use *inside* a character class. */
    private const val DQ_RAW = "\""
    private const val BS_RAW = "\\\\"

    /**
     * The JSON string rule, assembled from parts rather than written as one raw
     * Kotlin string. A raw string that ends in a quoted `"\""` is a quoting
     * foot-gun — Kotlin's `"""` terminator swallows the last quote — and a grammar
     * with a truncated literal is a grammar llama.cpp rejects outright.
     */
    private val STRING_RULE =
        "string ::= $DQ ( [^$DQ_RAW$BS_RAW] | $BS " +
            "( [$DQ_RAW$BS_RAW/bfnrt] | " + literal("\"u\"") +
            " [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* $DQ"

    /** Shared JSON value rules. Identical to the shape llama.cpp's json.gbnf uses. */
    private val SHARED_RULES = listOf(
        "ws" to """ws ::= [ \t\n]*""",
        "value" to """value ::= object | array | string | number | boolean | "null"""",
        "object" to """object ::= "{" ws ( string ws ":" ws value (ws "," ws string ws ":" ws value)* )? ws "}"""",
        "array" to """array ::= "[" ws ( value (ws "," ws value)* )? ws "]"""",
        "string" to STRING_RULE,
        "number" to """number ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?""",
        "integer" to """integer ::= "-"? ("0" | [1-9] [0-9]*)""",
        "boolean" to """boolean ::= "true" | "false"""",
    )

    /**
     * Respond text. Single-line, at least one character, and free of `<`/`>` so a
     * respond can never contain what looks like another tag. (The parser is more
     * lenient than the grammar — a backend without grammar support can still emit
     * anything, and the parser tolerates it. A permissive parser over a tight
     * grammar is the safe direction.)
     */
    private const val TEXT_RULE = """text ::= [^\x00\x0A\x0D<>]+"""

    /**
     * GBNF constraining output to Respond(text) or CallTool(name in [tools], args).
     *
     * Blank and duplicate tool names are dropped rather than rejected: this is
     * called on whatever the selector produced, and a bad entry must not be able to
     * take the turn down.
     */
    fun forActions(tools: List<ToolDefinition>): String {
        val defs = sanitize(tools)
        val lines = ArrayList<String>(SHARED_RULES.size + defs.size + 8)

        lines += "root ::= action"
        lines += "action ::= " + if (defs.isEmpty()) "resp" else "resp | toolcall"
        lines += """resp ::= "<respond>" text "</respond>""""
        lines += TEXT_RULE

        if (defs.isNotEmpty()) {
            val toolcall = StringBuilder("toolcall ::= ")
            defs.forEachIndexed { index, def ->
                if (index > 0) toolcall.append(" | ")
                toolcall.append(literal(openTagFor(def.name)))
                    .append(" args").append(index + 1)
                    .append(" \"</tool>\"")
            }
            lines += toolcall.toString()

            defs.forEachIndexed { index, def ->
                lines += "args${index + 1} ::= ${argsRule(def)}"
            }
        }

        SHARED_RULES.forEach { (_, rule) -> lines += rule }
        return lines.joinToString("\n", postfix = "\n")
    }

    /**
     * A one-line human/model-readable hint naming the legal action shapes.
     *
     * This goes into the malformed-retry observation, so it is kept short. The name
     * list is capped at 8: past that a model is not going to pick better, and the
     * retry budget is more valuable than the extra names.
     */
    fun hint(tools: List<ToolDefinition>): String {
        val names = sanitize(tools).map { it.name }
        if (names.isEmpty()) {
            return "Reply with <respond>your answer</respond>. No tools are available."
        }
        val shown = names.take(MAX_HINT_NAMES)
        val more = if (names.size > shown.size) " (+${names.size - shown.size} more)" else ""
        return "Reply with <respond>your answer</respond>, or " +
            """<tool name="NAME">{"arg":value}</tool> where NAME is one of: """ +
            shown.joinToString(", ") + more
    }

    // ---------------------------------------------------------------- internals

    private const val MAX_HINT_NAMES = 8

    private fun sanitize(tools: List<ToolDefinition>): List<ToolDefinition> =
        tools.filter { it.name.isNotBlank() }.distinctBy { it.name }

    private fun openTagFor(name: String): String = "<tool name=\"$name\">"

    private fun argsRule(tool: ToolDefinition): String {
        val properties = tool.schema["properties"] as? JsonObject
        val keys = properties?.keys?.toList().orEmpty()
        val required = (tool.schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()

        // Required first, then optional, each group in schema order.
        val ordered = keys.filter { it in required } + keys.filter { it !in required }
        if (ordered.isEmpty()) return """ws "{" ws "}""""

        val sb = StringBuilder("""ws "{" ws""")
        ordered.forEachIndexed { index, key ->
            val property = properties?.get(key) as? JsonObject
            if (index > 0) sb.append(""" (ws "," ws""")
            sb.append(' ').append(pair(key, valueRule(property)))
            if (index > 0) sb.append(")?")
        }
        return sb.append(""" ws "}"""").toString()
    }

    /**
     * One `"key" : value` pair.
     *
     * The key literal must carry the JSON quote characters *as matched text*: the
     * GBNF quotes are grammar syntax, so `"\"query\""` is what forces the model to
     * emit `"query"` rather than `query`. `ws` on both sides of the colon keeps
     * `{ "query" : "a" }` legal, since a small model will absolutely write it.
     */
    private fun pair(key: String, value: String): String =
        literal("\"$key\"") + " ws " + literal(":") + " ws " + value

    private fun valueRule(property: JsonObject?): String {
        if (property == null) return "value"

        val enumValues = (property["enum"] as? JsonArray)?.map { it } ?: return when (typeOf(property)) {
            "string" -> "string"
            "number" -> "number"
            "integer" -> "integer"
            "boolean" -> "boolean"
            "array" -> "array"
            "object" -> "object"
            else -> "value"
        }
        if (enumValues.isEmpty()) return "value"

        val alternatives = enumValues.map { element ->
            when (element) {
                is JsonNull -> literal("null")
                is JsonPrimitive -> {
                    val text = element.content
                    if (element.isString) literal("\"$text\"") else literal(text)
                }
                else -> "value"
            }
        }
        return alternatives.joinToString(" | ", "(", ")")
    }

    private fun typeOf(property: JsonObject): String? = when (val type = property["type"]) {
        is JsonPrimitive -> type.content?.lowercase()
        is JsonArray -> type
            .filterIsInstance<JsonPrimitive>()
            .firstNotNullOfOrNull { it.content?.lowercase()?.takeIf { t -> t != "null" } }
        else -> null
    }

    /**
     * Quotes a GBNF literal. Only the escapes llgrammar actually implements are
     * emitted — `\" \\ \n \r \t \xNN` — because an unknown escape such as `\b`
     * makes the whole grammar fail to parse.
     */
    private fun literal(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 || c.code == 0x7F ->
                    out.append("\\x").append(c.code.toString(16).padStart(2, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
