package dev.localintelligence.core.tool

import dev.localintelligence.core.agent.Session
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `tools.find`: the model finds and loads the tools its prompt does not list.
 *
 * On by default ("Let the model look up tools"). The prompt holds the core tools
 * and this one, and its own description carries a short LISTING of every other
 * enabled tool, so the model always knows what exists. A model that does not know
 * a tool exists answers without it or says it cannot do the thing, so the listing
 * is what makes the lookup a choice the model actually makes.
 *
 * The model loads a tool by its exact name from that listing, or by a query that
 * the lexical search answers. Either way the loaded tool's full schema comes back
 * as this call's result and the tool becomes callable for the rest of the
 * conversation ([Session.discoveredTools]).
 *
 * The listing sits in the native tool list, which is a cached prompt prefix, so
 * it must be byte-stable: it is built from the enabled tools, the core list and
 * the context length alone, sorted, and never from the conversation.
 */
class ToolFinder(
    private val registry: ToolRegistry,
    private val selector: ToolSelector,
    private val session: Session,
    /** The tools the prompt always lists. The listing leaves them out, since the model already has them. */
    private val coreNames: Collection<String> = emptyList(),
    /**
     * The loaded model's context length in tokens, which sizes the listing (see [listing]).
     * Zero or less means unknown, and the listing is sized for [FALLBACK_CONTEXT].
     */
    private val contextLength: Int = 0,
    /** One line per listing built and per tool loaded, for the device log. */
    private val log: (String) -> Unit = {},
) : AgentTool {
    /**
     * Built on first use and kept for this finder. A finder is made per run, and its
     * inputs do not change within one, so the text stays the same for the conversation.
     */
    override val definition: ToolDefinition by lazy { listing() }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val names = when (val raw = args["names"]) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(raw.contentOrNull)
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isNotEmpty()) return loadNamed(names)

        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) {
            return ToolResult(
                success = false,
                observation = "Give the exact names you need from the list, or say what the tool should do in query.",
            )
        }
        val known = session.epochTools.toSet() + session.discoveredTools + NAME
        val candidates = registry.all().filter { it.definition.name !in known }
        // The selector ranks tools but does not cut off: it returns the best three
        // even when none overlaps the query. And its words include "for", "the" and
        // the like, which most descriptions share, so a query with no real match
        // would still make three unrelated tools callable. Only a tool that shares a
        // content word with the query is offered.
        val wanted = lexicalTokens(query).filterNot { it in FUNCTION_WORDS }.toSet()
        val matching = candidates.filter { tool -> wanted.any { it in wordsOf(tool) } }
        val found = selector.select(query, emptyList(), matching, MAX_FOUND)
        if (found.isEmpty()) {
            return ToolResult(success = true, observation = "No other tool matches \"$query\". Use the tools you have, or answer.")
        }
        found.forEach { session.discoveredTools += it.definition.name }
        log("tools.find loaded: ${found.joinToString { it.definition.name }}")
        return ToolResult(
            success = true,
            observation = "These tools can be called now, like any other:\n" +
                found.joinToString("\n") { describe(it.definition) },
        )
    }

    /** Loads the tools named exactly by the model. Unknown names are reported, the rest still load. */
    private fun loadNamed(asked: List<String>): ToolResult {
        val distinct = asked.distinct()
        val capped = distinct.take(MAX_NAMED)
        val byName = registry.all().associateBy { it.definition.name }
        val known = session.epochTools.toSet() + session.discoveredTools + NAME
        val found = capped.mapNotNull { byName[it]?.definition }
        val missing = capped.filter { it !in byName }
        found.map { it.name }.filter { it !in known }.forEach { session.discoveredTools += it }
        if (found.isNotEmpty()) log("tools.find loaded: ${found.joinToString { it.name }}")
        val parts = mutableListOf<String>()
        if (found.isNotEmpty()) {
            parts += "These tools can be called now, like any other:\n" + found.joinToString("\n") { describe(it) }
        }
        if (missing.isNotEmpty()) {
            parts += "Not in the tool list: ${missing.joinToString()}. Use the names exactly as the list gives them."
        }
        if (distinct.size > MAX_NAMED) {
            parts += "Only the first $MAX_NAMED names were loaded. Ask again for the rest."
        }
        return ToolResult(success = found.isNotEmpty(), observation = parts.joinToString("\n\n"))
    }

    /**
     * The definition the model sees: [DEFINITION] with the listing as its description.
     *
     * WHY THE LISTING IS BUILT FROM THE REGISTRY, NOT A CONSTANT: the model must know every
     * tool it can load, and which ones exist depends on the switches the user has on. The
     * text is a function of the enabled tools only, so the same set always gives the same
     * bytes, which the cached prefix needs.
     *
     * Three tiers, the first that fits the budget. Budget (as Hermes sizes it): the smaller
     * of 5% of the context and 4000 tokens, at about 4 characters a token. Tier 1 is one line
     * per tool, name and a short description. Tier 2 is names only. Tier 3 is a count per
     * category, which is the floor and may still overrun a budget that tiny.
     */
    private fun listing(): ToolDefinition {
        val deferred = registry.all().map { it.definition }
            .filter { it.name != NAME && it.name != ObservationStore.READ_TOOL && it.name !in coreNames }
            .sortedWith(compareBy({ it.category }, { it.name }))
        val window = contextLength.takeIf { it > 0 } ?: FALLBACK_CONTEXT
        val budgetChars = minOf(window * LISTING_SHARE_PERCENT / 100, LISTING_MAX_TOKENS) * CHARS_PER_TOKEN
        val byCategory = deferred.groupBy { it.category }
        val tiers = listOf(
            1 to byCategory.entries.joinToString("\n") { (category, tools) ->
                "$category:\n" + tools.joinToString("\n") { "  ${it.name} - ${shortDescription(it.description)}" }
            },
            2 to byCategory.entries.joinToString("\n") { (category, tools) ->
                "$category: " + tools.joinToString(", ") { it.name }
            },
            3 to byCategory.entries.joinToString("\n") { (category, tools) ->
                "$category: ${tools.size} " + if (tools.size == 1) "tool" else "tools"
            },
        )
        val (tier, body) = tiers.firstOrNull { (_, text) -> text.length + INTRO.length + 1 <= budgetChars }
            ?: tiers.last()
        val text = if (deferred.isEmpty()) INTRO else "$INTRO\n$body"
        log("tool lookup: listing tier $tier, ${deferred.size} deferred tools, ~${text.length / CHARS_PER_TOKEN} tokens")
        return DEFINITION.copy(description = text)
    }

    companion object {
        const val NAME: String = "tools.find"

        /** Tools one query returns: enough to choose from, few enough to stay short. */
        const val MAX_FOUND: Int = 3

        /** Tools one call loads by name. */
        const val MAX_NAMED: Int = 5

        /** The listing's lead sentence. It is the first line of the description the model reads. */
        private const val INTRO: String = "Loads tools that are not in your list. The other tools are:"

        /** The listing's share of the context, and its absolute cap, in tokens (Hermes's rule). */
        private const val LISTING_SHARE_PERCENT: Int = 5
        private const val LISTING_MAX_TOKENS: Int = 4000

        /** The estimate the budget and the log use. Characters over four, as Hermes estimates. */
        private const val CHARS_PER_TOKEN: Int = 4

        /** The window a listing is sized for when the model's is unknown. */
        private const val FALLBACK_CONTEXT: Int = 4096

        /** A tool description is cut to this many characters in the listing. */
        private const val SHORT_DESCRIPTION_CHARS: Int = 60

        /**
         * Words a lookup must share with a tool to offer it. Function words only: the
         * lookup's own words ("find", "tool") are left in, since a tool description
         * rarely repeats them and the filter should not depend on that either.
         */
        private val FUNCTION_WORDS: Set<String> = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "your", "you", "can",
            "could", "please", "want", "need", "make", "help", "some", "any", "all", "get", "use",
            "not", "are", "was", "will", "would", "should", "have", "has", "how", "what", "when",
            "where", "which", "who", "just", "also", "about", "there", "here",
        )

        /** Every word a tool offers for matching: its name, description and tags. */
        private fun wordsOf(tool: AgentTool): Set<String> {
            val def = tool.definition
            return (lexicalTokens(def.name) + lexicalTokens(def.description) + def.tags.flatMap { lexicalTokens(it) }).toSet()
        }

        /**
         * The first sentence of [description], on one line, cut to [SHORT_DESCRIPTION_CHARS].
         * Internal so the listing's shape can be checked without a model.
         */
        internal fun shortDescription(description: String): String {
            val flat = description.trim().replace(Regex("\\s+"), " ")
            val sentence = flat.substringBefore(". ").trimEnd('.')
            return if (sentence.length <= SHORT_DESCRIPTION_CHARS) {
                sentence
            } else {
                sentence.take(SHORT_DESCRIPTION_CHARS - 3).trimEnd() + "..."
            }
        }

        /**
         * One tool as the model reads it when it is loaded: its name and description, then
         * every argument with its type, whether it is required, and what it means. The
         * argument lines are indented and never start with "- ", so [namedIn] reads only the
         * name lines.
         */
        internal fun describe(def: ToolDefinition): String {
            val head = "- ${def.name}: ${def.description}"
            val properties = def.schema["properties"] as? JsonObject
            if (properties.isNullOrEmpty()) return "$head\n  no arguments"
            val required = (def.schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
                .toSet()
            val lines = properties.map { (name, value) ->
                val spec = value as? JsonObject
                val type = (spec?.get("type") as? JsonPrimitive)?.contentOrNull
                val description = (spec?.get("description") as? JsonPrimitive)?.contentOrNull
                val tags = listOfNotNull(type, if (name in required) "required" else "optional").joinToString(", ")
                val label = "$name ($tags)"
                "    " + if (description.isNullOrBlank()) label else "$label: $description"
            }
            return (listOf(head, "  arguments:") + lines).joinToString("\n")
        }

        /**
         * The tool names a lookup result lists, one `- name: description` line each.
         * Read back from the window so a tool stays callable while its result is there
         * (see AgentController.compactNow). A name is split at ": ", the separator this
         * file writes; a dotted or dashed name has no space before its colon.
         */
        fun namedIn(observation: String): Set<String> =
            observation.lineSequence()
                .filter { it.startsWith("- ") }
                .map { it.removePrefix("- ").substringBefore(": ").trim() }
                .toSet()

        /**
         * The static definition. The catalogue reads its name and risk tier; the model sees
         * [listing] instead, which is this definition with the listing as its description.
         */
        val DEFINITION: ToolDefinition = ToolDefinition(
            name = NAME,
            description = INTRO,
            category = "tools",
            schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("names") {
                        put("type", "array")
                        put("maxItems", MAX_NAMED)
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Exact tool names from your list, up to $MAX_NAMED.")
                    }
                    putJsonObject("query") {
                        put("type", "string")
                        put("maxLength", 120)
                        put("description", "What the tool should do, for example \"set an alarm\". Use it when no name fits.")
                    }
                }
                put("additionalProperties", false)
            },
            risk = ToolRisk.READ_ONLY,
            observationOrigin = ObservationOrigin.LOCAL,
            tags = setOf("find a tool", "look up a tool", "other tool"),
        )
    }
}
