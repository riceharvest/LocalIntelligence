package dev.pidroid.core.agent

import dev.pidroid.core.model.ToolArgs

/**
 * Deterministic loop protection. No LLM involved — this is pure Kotlin
 * bookkeeping so a small model cannot burn battery re-issuing the same call.
 *
 * Rules (see docs/architecture.md):
 *  - same normalized call twice  -> warn the model
 *  - same normalized call 3x      -> block
 *  - same error twice             -> mark unavailable, stop re-prompting
 *  - N consecutive calls that changed nothing -> terminate the step
 */
class LoopDetector(
    private val blockAfter: Int = 3,
    private val warnAfter: Int = 2,
    private val noProgressLimit: Int = 3,
) {
    private data class Key(val tool: String, val args: String)

    private val seen = LinkedHashMap<Key, Int>()
    private val errorsByTool = LinkedHashMap<String, Int>()
    private val unavailable = LinkedHashSet<String>()

    /** Consecutive tool calls that produced an observation identical to the last one. */
    private var noProgressStreak = 0
    private var lastObservation: String? = null

    enum class Verdict { ALLOW, WARN, BLOCK }

    /**
     * Canonical form used for identity: tool name + normalized arguments.
     * Key order must not matter, so we sort entries recursively.
     */
    fun normalize(args: ToolArgs): String = canonical(args)

    private fun canonical(element: kotlinx.serialization.json.JsonElement): String =
        when (element) {
            is kotlinx.serialization.json.JsonObject ->
                element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) ->
                    "\"$k\":${canonical(v)}"
                }
            is kotlinx.serialization.json.JsonArray ->
                element.joinToString(",", "[", "]") { canonical(it) }
            else -> element.toString()
        }

    fun check(toolName: String, args: ToolArgs): Verdict {
        if (toolName in unavailable) return Verdict.BLOCK
        val key = Key(toolName, normalize(args))
        val count = (seen[key] ?: 0) + 1
        seen[key] = count
        return when {
            count >= blockAfter -> Verdict.BLOCK
            count >= warnAfter -> Verdict.WARN
            else -> Verdict.ALLOW
        }
    }

    /** Record a tool outcome so repeat-failure detection works. */
    fun recordResult(toolName: String, observation: String, success: Boolean) {
        if (observation == lastObservation) noProgressStreak++ else noProgressStreak = 0
        lastObservation = observation

        if (success) {
            errorsByTool.remove(toolName)
        } else {
            val n = (errorsByTool[toolName] ?: 0) + 1
            errorsByTool[toolName] = n
            if (n >= 2) unavailable += toolName
        }
    }

    /** True when the agent has stalled and the loop should stop. */
    fun hasStalled(): Boolean = noProgressStreak >= noProgressLimit

    fun isUnavailable(toolName: String): Boolean = toolName in unavailable

    fun reset() {
        seen.clear()
        errorsByTool.clear()
        unavailable.clear()
        noProgressStreak = 0
        lastObservation = null
    }
}
