package dev.localintelligence.android.mcp

import dev.localintelligence.android.tools.web.UrlVerdict
import dev.localintelligence.android.tools.web.WebUrls
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationTruncator
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.catalogue.CatalogueAgreement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException

// ===========================================================================
// Tools from one remote MCP server. Off by default.
//
// Off, or no address set: [McpPlugin.tools] returns nothing and makes no
// network call. On, with an address: the run's tool list is built from the
// server's own tools/list, once per run.
//
// Every remote tool is NETWORK_EGRESS, so each call confirms unless the user
// chose "Open websites without asking", and its output is NETWORK-origin, so it
// is fenced as untrusted text like a fetched page. The server's tool
// descriptions are also model-visible, and they are not fenced; see
// docs/threat-model.md.
// ===========================================================================

/** The settings rows this plugin adds. Keys are prefixed with the plugin id. */
object McpSettings {
    const val PLUGIN_ID = "mcp.remote"

    /** Simple, so Simple mode can reach it: with no address nothing is sent. */
    val URL = SettingDef.Text(
        "mcp.remote.url", "Server address",
        "One MCP server, as an https:// address. Empty means no server: nothing is sent and no " +
            "tools are offered. Its tools appear as mcp.<name>.",
        SettingSection.PLUGINS, default = "", maxLength = 300, simple = true,
    )
    val TOKEN = SettingDef.Secret(
        "mcp.remote.token", "Bearer token",
        "Optional. Sent to the server as Authorization: Bearer. Stored encrypted and never shown back.",
        SettingSection.PLUGINS,
    )

    val all: List<SettingDef<*>> = listOf(URL, TOKEN)
}

/**
 * Off by default. Offers one remote server's tools while it is switched on and
 * an address is set.
 *
 * [reader] is the live settings store. The address and token are read when a
 * run builds its tool list, so a change takes effect at the next message.
 */
class McpPlugin(
    private val reader: SettingsReader,
) : AgentPlugin {
    override val id: String = McpSettings.PLUGIN_ID
    override val title: String = "Remote tools (MCP)"
    override val summary: String =
        "Tools from one MCP server you name. Each call sends its arguments to that server."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = McpSettings.all

    /**
     * Called once per run, from the thread that builds the run's controller: the
     * service's background scope, never the main thread (see ExecutionService.startRun,
     * which launches prepareThenStart on serviceScope). The listing blocks that
     * thread for up to the request timeout, and it is the only network call here.
     *
     * An unreachable or misbehaving server yields no tools and no failure: the run
     * goes ahead without them.
     */
    override fun tools(): List<AgentTool> {
        val address = reader[McpSettings.URL].trim()
        if (address.isEmpty()) return emptyList()
        // The same address check web.fetch uses: no private, loopback or link-local
        // host, no file or script scheme. Then https only, since the token rides in
        // the Authorization header and http would send it in clear text.
        val accepted = WebUrls.validate(address) as? UrlVerdict.Accepted ?: return emptyList()
        if (!accepted.url.startsWith("https://")) return emptyList()
        val token = reader[McpSettings.TOKEN].takeIf { it.isNotBlank() }
        val budget = reader[CoreSettings.OBSERVATION_BUDGET]
        return try {
            val session = McpSession.open(accepted.url, token)
            remoteTools(session, session.listTools(), budget)
        } catch (e: IOException) {
            emptyList()
        } catch (e: RuntimeException) {
            // A malformed reply from the server must not end the run either.
            emptyList()
        }
    }
}

/**
 * Turns the server's tool descriptions into [AgentTool]s. A tool whose name is
 * empty after sanitising is skipped, and so is a second tool that sanitises to a
 * name already taken: the registry refuses duplicate names, and a run must not
 * fail over a server's naming.
 */
internal fun remoteTools(session: McpSession, listed: List<JsonObject>, budget: Int): List<AgentTool> {
    val taken = HashSet<String>()
    return listed.mapNotNull { tool ->
        val remoteName = (tool["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val name = toolName(remoteName) ?: return@mapNotNull null
        if (!taken.add(name)) return@mapNotNull null
        McpRemoteTool(session, remoteName, definition(name, remoteName, tool), budget)
    }
}

/**
 * `mcp.` plus the remote name, lowercased to [a-z0-9_] and capped, so a name a
 * model has to type stays short. Null when nothing is left after sanitising.
 */
internal fun toolName(remoteName: String): String? {
    val slug = remoteName.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(MAX_NAME_CHARS)
        .trimEnd('_')
    return if (slug.isEmpty()) null else "${CatalogueAgreement.DYNAMIC_TOOL_PREFIX}$slug"
}

private const val MAX_NAME_CHARS = 48
private const val MAX_DESCRIPTION_CHARS = 300

private fun definition(name: String, remoteName: String, tool: JsonObject): ToolDefinition {
    val described = (tool["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    return ToolDefinition(
        name = name,
        description = described.ifEmpty { "Tool $remoteName on the remote MCP server." }
            .take(MAX_DESCRIPTION_CHARS),
        category = "mcp",
        schema = (tool["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") },
        // Every call sends the model's arguments to the server. A tool that
        // reads or writes nothing on the phone still leaves it.
        risk = ToolRisk.NETWORK_EGRESS,
        observationOrigin = ObservationOrigin.NETWORK,
        // No tags. Ranking reads the name and the server's description, which
        // is what the selector scores for a tool with no catalogue entry.
        tags = emptySet(),
        requiredPermission = null,
    )
}

/** One remote tool. Calls go through the run's [McpSession]. */
private class McpRemoteTool(
    private val session: McpSession,
    private val remoteName: String,
    override val definition: ToolDefinition,
    private val budget: Int,
) : AgentTool {

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            if (context.signal.isCancelled()) {
                return@withContext ToolResult(
                    success = false,
                    observation = "${definition.name} was cancelled.",
                    error = ToolError.Cancelled("${definition.name} cancelled"),
                )
            }
            try {
                val result = session.callTool(remoteName, args)
                val text = contentText(result)
                val observation = ObservationTruncator.truncate(
                    text.ifEmpty { "(the server returned no text)" },
                    budget,
                )
                if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
                    ToolResult(
                        success = false,
                        observation = observation,
                        error = ToolError.Unavailable("the server reported an error"),
                    )
                } else {
                    ToolResult(success = true, observation = observation)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ToolResult(
                    success = false,
                    observation = "${definition.name} could not reach the MCP server " +
                        "(${e.javaClass.simpleName}). Check the connection before retrying.",
                    error = ToolError.Unavailable("network: ${e.javaClass.simpleName}"),
                )
            } catch (e: RuntimeException) {
                ToolResult(
                    success = false,
                    observation = "${definition.name} could not run (${e.javaClass.simpleName}). " +
                        "Do not retry the same call.",
                    error = ToolError.Internal("${e.javaClass.simpleName} in ${definition.name}"),
                )
            }
        }

    /** The text parts of a tools/call result, joined. Image and resource parts are skipped. */
    private fun contentText(result: JsonObject): String {
        val parts = result["content"] as? JsonArray ?: return ""
        return parts.mapNotNull { part ->
            val obj = part as? JsonObject ?: return@mapNotNull null
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") return@mapNotNull null
            (obj["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("\n")
    }
}
