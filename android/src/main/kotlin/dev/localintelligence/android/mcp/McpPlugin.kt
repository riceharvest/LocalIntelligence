package dev.localintelligence.android.mcp

import dev.localintelligence.core.model.UntrustedContent
import dev.localintelligence.android.tools.web.UrlVerdict
import dev.localintelligence.android.tools.web.WebUrls
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ObservationStore
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
// Tools from up to five remote MCP servers. Off by default.
//
// Off, or no address set: [McpPlugin.tools] returns nothing and makes no
// network call. On, with addresses: the run's tool list is built from each
// server's own tools/list, once per run. A server that cannot be reached is
// skipped; the others still answer.
//
// Every remote tool is NETWORK_EGRESS, so each call confirms unless the user
// chose "Open websites without asking", and its output is NETWORK-origin, so it
// is fenced as untrusted text like a fetched page. The server's tool
// descriptions are also model-visible, and they are not fenced; see
// docs/threat-model.md.
// ===========================================================================

/**
 * The settings rows this plugin adds. Keys are prefixed with the plugin id.
 *
 * Up to [MAX_SERVERS] servers, each an address row and a token row. Server 1 keeps
 * the keys this plugin shipped with, so an address and token already set carry
 * over unchanged. Servers 2 to 5 are Technical-mode rows; an empty address means
 * that slot is not used. Every slot is listed rather than shown on demand, because
 * the settings screen is generated from a flat list of rows.
 */
object McpSettings {
    const val PLUGIN_ID = "mcp.remote"

    /** How many servers can be configured at once. */
    const val MAX_SERVERS = 5

    private fun urlKey(slot: Int): String = if (slot == 1) "mcp.remote.url" else "mcp.remote.url.$slot"
    private fun tokenKey(slot: Int): String = if (slot == 1) "mcp.remote.token" else "mcp.remote.token.$slot"

    /** Simple for slot 1, so Simple mode can reach it: with no address nothing is sent. */
    private fun urlRow(slot: Int) = SettingDef.Text(
        urlKey(slot), if (slot == 1) "Server address" else "Server $slot address",
        "One MCP server, as an https:// address. Empty means this slot is not used: nothing is " +
            "sent. With one server its tools appear as mcp.<name>; with more, as mcp.$slot.<name>.",
        SettingSection.PLUGINS, default = "", maxLength = 300, simple = slot == 1,
    )

    private fun tokenRow(slot: Int) = SettingDef.Secret(
        tokenKey(slot), if (slot == 1) "Bearer token" else "Server $slot bearer token",
        "Optional. Sent to the server as Authorization: Bearer. Stored encrypted and never shown back.",
        SettingSection.PLUGINS,
    )

    /** Address rows, index 0 is server 1. */
    val urls: List<SettingDef.Text> = (1..MAX_SERVERS).map(::urlRow)

    /** Token rows, index 0 is server 1. */
    val tokens: List<SettingDef.Secret> = (1..MAX_SERVERS).map(::tokenRow)

    val all: List<SettingDef<*>> = (0 until MAX_SERVERS).flatMap { listOf(urls[it], tokens[it]) }
}

/**
 * Off by default. Offers the tools of up to five remote servers while it is
 * switched on and at least one address is set.
 *
 * [reader] is the live settings store. The addresses and tokens are read when a
 * run builds its tool list, so a change takes effect at the next message.
 */
class McpPlugin(
    private val reader: SettingsReader,
) : AgentPlugin {
    override val id: String = McpSettings.PLUGIN_ID
    override val title: String = "Remote tools (MCP)"
    override val summary: String =
        "Tools from up to five MCP servers you name. Each call sends its arguments to that server."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = McpSettings.all

    /**
     * Called once per run, from the thread that builds the run's controller: the
     * service's background scope, never the main thread (see ExecutionService.startRun,
     * which launches prepareThenStart on serviceScope). Each listing blocks that
     * thread for up to the request timeout, and it is the only network call here.
     *
     * An unreachable or misbehaving server yields no tools and no failure: the run
     * goes ahead without it, and without its tools only.
     *
     * Only addresses that pass the checks count towards the naming. So one server
     * that is set but unusable does not turn the other's `mcp.<name>` into
     * `mcp.<slot>.<name>`.
     */
    override fun tools(): List<AgentTool> {
        val servers = McpSettings.urls.indices.mapNotNull { index ->
            val address = reader[McpSettings.urls[index]].trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val url = acceptedAddress(address) ?: return@mapNotNull null
            Server(
                slot = index + 1,
                url = url,
                token = reader[McpSettings.tokens[index]].takeIf { it.isNotBlank() },
            )
        }
        // One server keeps the plain mcp.<name>. More than one need the slot number so
        // their names cannot collide, and the slot keeps a name stable when another
        // server is changed or fails.
        val numbered = servers.size > 1
        return servers.flatMap { server -> toolsFrom(server, numbered) }
    }

    /** One configured server: its slot number (1 to 5), checked address and optional token. */
    private class Server(val slot: Int, val url: String, val token: String?)

    private fun toolsFrom(server: Server, numbered: Boolean): List<AgentTool> {
        val prefix = if (numbered) "${CatalogueAgreement.DYNAMIC_TOOL_PREFIX}${server.slot}."
        else CatalogueAgreement.DYNAMIC_TOOL_PREFIX
        return try {
            val session = McpSession.open(server.url, server.token)
            remoteTools(session, session.listTools(), prefix)
        } catch (e: IOException) {
            emptyList()
        } catch (e: RuntimeException) {
            // A malformed reply from the server must not end the run either.
            emptyList()
        }
    }

    /**
     * The same address check web.fetch uses: no private, loopback or link-local
     * host, no file or script scheme. Then https only, since the token rides in
     * the Authorization header and http would send it in clear text. Null when the
     * address fails either check.
     */
    private fun acceptedAddress(address: String): String? {
        val accepted = WebUrls.validate(address) as? UrlVerdict.Accepted ?: return null
        return accepted.url.takeIf { it.startsWith("https://") }
    }
}

/**
 * Turns the server's tool descriptions into [AgentTool]s. A tool whose name is
 * empty after sanitising is skipped, and so is a second tool that sanitises to a
 * name already taken: the registry refuses duplicate names, and a run must not
 * fail over a server's naming.
 */
internal fun remoteTools(
    session: McpSession,
    listed: List<JsonObject>,
    prefix: String = CatalogueAgreement.DYNAMIC_TOOL_PREFIX,
): List<AgentTool> {
    val taken = HashSet<String>()
    return listed.mapNotNull { tool ->
        val remoteName = (tool["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val name = toolName(remoteName, prefix) ?: return@mapNotNull null
        if (!taken.add(name)) return@mapNotNull null
        McpRemoteTool(session, remoteName, definition(name, remoteName, tool))
    }
}

/**
 * [prefix] (`mcp.`, or `mcp.<slot>.` with several servers) plus the remote name,
 * lowercased to [a-z0-9_] and capped, so a name a model has to type stays short.
 * Null when nothing is left after sanitising.
 */
internal fun toolName(remoteName: String, prefix: String = CatalogueAgreement.DYNAMIC_TOOL_PREFIX): String? {
    val slug = remoteName.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(MAX_NAME_CHARS)
        .trimEnd('_')
    return if (slug.isEmpty()) null else "$prefix$slug"
}

private const val MAX_NAME_CHARS = 48
private const val MAX_DESCRIPTION_CHARS = 300

private fun definition(name: String, remoteName: String, tool: JsonObject): ToolDefinition {
    val described = (tool["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    return ToolDefinition(
        name = name,
        // The server writes this text and it reaches the system prompt, so it gets
        // the same neutralisation as an observation body (no role markers, no
        // template control tokens) and is labelled as the server's own words.
        description = described.ifEmpty { "Tool $remoteName on the remote MCP server." }
            .let { "(from the MCP server) " + UntrustedContent.neutralise(it) }
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

/**
 * One remote tool. Calls go through the run's [McpSession].
 *
 * The full text goes out, up to the store's limit, and the loop decides what the
 * model sees: a long result is saved and previewed with a handle (see
 * [ObservationStore]). Cutting it here would leave the store nothing to keep.
 */
private class McpRemoteTool(
    private val session: McpSession,
    private val remoteName: String,
    override val definition: ToolDefinition,
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
                val observation = text.ifEmpty { "(the server returned no text)" }
                    .take(ObservationStore.MAX_STORED_CHARS)
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
