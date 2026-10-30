package dev.localintelligence.android.scripts

import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.model.UntrustedContent
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.CatalogueAgreement

/** The id a script plugin is registered under: `script.` plus its manifest id. */
fun scriptPluginId(manifestId: String): String = "${CatalogueAgreement.SCRIPT_TOOL_PREFIX}$manifestId"

/**
 * One installed script plugin. Off by default: a plugin the user just pasted in
 * does not run until they switch it on, the same rule as the remote MCP plugin.
 *
 * Its tools are `script.<id>.<tool>`. [tools] is empty on a phone without the JS
 * sandbox, so the plugin is listed with its reason and offers nothing to the model.
 */
class ScriptPlugin(
    private val installed: ScriptPluginRepository.Installed,
    private val runner: ScriptRunner,
    private val reader: SettingsReader,
) : AgentPlugin {
    override val id: String = scriptPluginId(installed.manifest.id)
    override val title: String = installed.manifest.title
    override val summary: String = installed.manifest.summary
    override val enabledByDefault: Boolean = false

    override fun tools(): List<AgentTool> {
        if (!runner.isSupported()) return emptyList()
        val budget = reader[CoreSettings.OBSERVATION_BUDGET]
        return installed.manifest.tools.map { spec ->
            ScriptTool(
                definition = ToolDefinition(
                    name = "$id.${spec.name}",
                    // The author wrote this text and it reaches the system prompt, so it
                    // gets the same neutralising as an MCP tool description and is labelled.
                    description = "(from a script plugin) " + UntrustedContent.neutralise(spec.description),
                    category = "script",
                    schema = spec.parameters,
                    risk = spec.risk,
                    // A script's output is third-party text by definition, so it is fenced as
                    // untrusted like a fetched page, whatever the script claims.
                    observationOrigin = ObservationOrigin.NETWORK,
                    tags = emptySet(),
                    requiredPermission = null,
                ),
                function = spec.name,
                mainJs = installed.mainJs,
                runner = runner,
                budget = budget,
            )
        }
    }
}

private class ScriptTool(
    override val definition: ToolDefinition,
    private val function: String,
    private val mainJs: String,
    private val runner: ScriptRunner,
    private val budget: Int,
) : AgentTool {

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (context.signal.isCancelled()) {
            return ToolResult(
                success = false,
                observation = "${definition.name} was cancelled.",
                error = ToolError.Cancelled("${definition.name} cancelled"),
            )
        }
        return when (val outcome = runner.call(mainJs, function, args.toString())) {
            is ScriptRunner.Outcome.Ok -> ToolResult(
                success = true,
                observation = ObservationTruncator.truncate(outcome.text.ifEmpty { "(the script returned no text)" }, budget),
            )
            is ScriptRunner.Outcome.Failed -> ToolResult(
                success = false,
                observation = "${definition.name} failed: ${outcome.reason}",
                error = ToolError.Internal("script ${definition.name}: ${outcome.reason}"),
            )
        }
    }
}
