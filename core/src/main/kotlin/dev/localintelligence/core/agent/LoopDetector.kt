package dev.localintelligence.core.agent

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.ToolError

/**
 * Deterministic loop protection. No LLM involved — this is pure Kotlin
 * bookkeeping so a small model cannot burn battery re-issuing the same call.
 *
 * Rules (see docs/architecture.md):
 *  - same normalized call twice  -> warn the model
 *  - same normalized call 3x      -> block
 *  - same error twice             -> refuse that tool for the rest of the run, with
 *                                    an observation the model can act on
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

    /**
     * BLOCK ends the run: the same call with identical arguments a third time.
     * REFUSE does not: the tool failed twice, so this call is not run, and the
     * model is told so and can try something else or answer.
     */
    enum class Verdict { ALLOW, WARN, BLOCK, REFUSE }

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
        val key = Key(toolName, normalize(args))
        val count = (seen[key] ?: 0) + 1
        seen[key] = count
        // WHY a failed tool is refused but the run goes on: two identical failures
        // say the tool is not working right now (no network, a missing permission),
        // not that the task is hopeless. The call is not run, and the model is told
        // so; it can answer or use another tool. Only the same call with the same
        // arguments a third time ends the run.
        return when {
            count >= blockAfter -> Verdict.BLOCK
            toolName in unavailable -> Verdict.REFUSE
            count >= warnAfter -> Verdict.WARN
            else -> Verdict.ALLOW
        }
    }

    /** Record a tool outcome so repeat-failure detection works. */
    fun recordResult(toolName: String, observation: String, success: Boolean, error: ToolError? = null) {
        if (observation == lastObservation) noProgressStreak++ else noProgressStreak = 0
        lastObservation = observation

        if (success) {
            errorsByTool.remove(toolName)
        } else if (error !is ToolError.InvalidArguments) {
            // Bad arguments are the model's own fixable input, not a broken tool.
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
