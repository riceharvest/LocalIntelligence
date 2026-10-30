package dev.localintelligence.core.plugin

import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool

/**
 * A unit of capability that can be switched on and off.
 *
 * Modelled on DeepSeek Harness's "everything is a plugin", scaled down to a phone:
 * a plugin contributes tools, settings rows, and a few hooks into the loop, and
 * switching it off removes all three at the next run. The built-in tool families
 * are plugins too, which is what makes "toggle anything" a plugin switch rather
 * than a flag in every feature.
 *
 * Nothing here loads code at runtime. A plugin is a Kotlin object compiled into
 * the app; external plugins (skills, sandboxed scripts) adapt to this interface.
 */
interface AgentPlugin {
    /** Stable id, also its settings key prefix: `tools.calendar`. */
    val id: String
    val title: String

    /** One line for the plugin list. */
    val summary: String

    val enabledByDefault: Boolean get() = true

    /** Rows this plugin adds to the settings screen, listed under it. */
    val settings: List<SettingDef<*>> get() = emptyList()

    /** Tools offered to the model while the plugin is on. */
    fun tools(): List<AgentTool> = emptyList()

    /** Hooks into the loop for one run, or null for none. */
    fun hooks(settings: SettingsReader): PluginHooks? = null
}

/**
 * Where a plugin can act on a run, in loop order.
 *
 * Every method has a do-nothing default, and the loop calls them through
 * [PluginSet], which isolates failures: a hook that throws is skipped for that
 * call, never allowed to end the run.
 */
interface PluginHooks {
    /**
     * Lines for the system prompt that do not depend on the task (the user's
     * profile). They stay put from one message to the next, so the prompt
     * before them can be reused.
     */
    fun contextForSession(): List<String> = emptyList()

    /**
     * Lines for this turn only, chosen from the task (matched skills). They go
     * into the user turn, not the system prompt, because they change per task.
     */
    fun contextForStep(task: String): List<String> = emptyList()

    /** Before a validated tool call is judged by the risk policy. First [ToolGate.Deny] wins. */
    fun beforeTool(name: String, args: ToolArgs): ToolGate = ToolGate.Proceed

    /** Rewrites a tool's output before the model sees it. Runs before truncation. */
    fun afterTool(name: String, args: ToolArgs, observation: String, success: Boolean): String = observation

    /** Once the run has a result. */
    fun onRunEnd(result: AgentResult) {}

    companion object {
        val NONE: PluginHooks = object : PluginHooks {}
    }
}

sealed interface ToolGate {
    data object Proceed : ToolGate

    /** The call does not run; [reason] is what the model is told. */
    data class Deny(val reason: String) : ToolGate
}

/**
 * The plugins switched on for one run, and their hooks chained in order.
 *
 * Built per run from the current settings, so a toggle takes effect at the next
 * message without rebuilding anything long-lived.
 */
class PluginSet(
    plugins: List<AgentPlugin>,
    settings: SettingsReader,
    isEnabled: (AgentPlugin) -> Boolean,
) {
    val enabled: List<AgentPlugin> = plugins.filter { runCatching { isEnabled(it) }.getOrDefault(false) }

    fun tools(): List<AgentTool> = enabled.flatMap { runCatching { it.tools() }.getOrDefault(emptyList()) }

    val hooks: PluginHooks = chain(enabled.mapNotNull { runCatching { it.hooks(settings) }.getOrNull() })

    private fun chain(all: List<PluginHooks>): PluginHooks {
        if (all.isEmpty()) return PluginHooks.NONE
        return object : PluginHooks {
            override fun contextForSession(): List<String> =
                all.flatMap { h -> runCatching { h.contextForSession() }.getOrDefault(emptyList()) }

            override fun contextForStep(task: String): List<String> =
                all.flatMap { h -> runCatching { h.contextForStep(task) }.getOrDefault(emptyList()) }

            override fun beforeTool(name: String, args: ToolArgs): ToolGate {
                for (h in all) {
                    val gate = runCatching { h.beforeTool(name, args) }.getOrDefault(ToolGate.Proceed)
                    if (gate is ToolGate.Deny) return gate
                }
                return ToolGate.Proceed
            }

            override fun afterTool(name: String, args: ToolArgs, observation: String, success: Boolean): String =
                all.fold(observation) { acc, h ->
                    runCatching { h.afterTool(name, args, acc, success) }.getOrDefault(acc)
                }

            override fun onRunEnd(result: AgentResult) {
                all.forEach { h -> runCatching { h.onRunEnd(result) } }
            }
        }
    }
}
