package dev.localintelligence.android.tools.home

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

// ===========================================================================
// The three Home Assistant tools. Their definitions come from V0ToolCatalogue,
// so the risk tier here is the catalogue's by construction.
//
// The tier is split across two tools because RiskPolicy reads one tier per tool
// and never per argument. home.control is REVERSIBLE and runs without asking;
// home.secure (locks and covers) is DESTRUCTIVE and asks every time. Each tool
// refuses the other's domains, so a lock cannot reach the REVERSIBLE path by
// being named through the wrong tool.
//
// Device text comes from the user's own server, which is outside the phone. The
// tool definitions are NETWORK origin, so every observation is fenced as untrusted.
// ===========================================================================

private const val CONTROL = "home.control"
private const val SECURE = "home.secure"

/** Domains that take a reversible action. */
private val REVERSIBLE_DOMAINS = setOf("light", "switch", "fan", "media_player", "climate")

/** Domains that need home.secure, and its confirmation. */
private val SECURE_DOMAINS = setOf("lock", "cover")

/** Never driven from here, through either tool: arming or disarming an alarm is not a device action. */
private const val ALARM_DOMAIN = "alarm_control_panel"

/** The actions each domain takes, by the names the model sees. */
private val ALLOWED_ACTIONS: Map<String, Set<String>> = mapOf(
    "light" to setOf("turn_on", "turn_off", "toggle"),
    "switch" to setOf("turn_on", "turn_off", "toggle"),
    "fan" to setOf("turn_on", "turn_off", "toggle"),
    "media_player" to setOf("turn_on", "turn_off", "toggle", "play", "pause"),
    "climate" to setOf("turn_on", "turn_off", "set_temperature"),
    "lock" to setOf("lock", "unlock"),
    "cover" to setOf("open", "close"),
)

private val DOMAIN_WORD = Regex("[a-z_]{1,40}")
private val ENTITY_ID = Regex("[a-z0-9_]{1,80}\\.[a-z0-9_]{1,80}")

private const val CONNECT_MS = 8_000
private const val READ_MS = 15_000
private const val MAX_REPLY_BYTES = 4 * 1024 * 1024
private const val MAX_OBSERVATION_CHARS = 1_800
private const val MAX_DEVICE_ROWS = 20

/** Tools for the given server. [server] is read at each call, so a settings change applies at once. */
internal fun homeTools(server: () -> HomeServer?): List<AgentTool> = listOf(
    HomeDevicesTool(server),
    HomeControlTool(CONTROL, server),
    HomeControlTool(SECURE, server),
)

// ---------------------------------------------------------------------------
// home.devices
// ---------------------------------------------------------------------------

/** `GET /api/states`, filtered by words and domain, at most [MAX_DEVICE_ROWS] rows. */
private class HomeDevicesTool(private val server: () -> HomeServer?) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName("home.devices")) { "home.devices missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val tool = definition.name
            try {
                val query = textArg(args, "query")?.take(80)
                val domain = textArg(args, "domain")?.lowercase()
                if (domain != null && !DOMAIN_WORD.matches(domain)) {
                    return@withContext invalid(tool, "needs domain as one word, such as light or climate", "domain")
                }
                val cfg = server() ?: return@withContext notSetUp(tool)
                if (context.signal.isCancelled()) return@withContext cancelled(tool)

                val reply = exchange(cfg, "GET", "/api/states", null)
                if (!reply.ok) return@withContext httpFailure(tool, reply, cfg.token)
                val states = parseArray(reply.body)
                    ?: return@withContext unexpectedReply(tool)

                val words = query?.lowercase()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
                val matches = states.mapNotNull { it as? JsonObject }.filter { entity ->
                    val id = textOf(entity["entity_id"]) ?: return@filter false
                    val wordsMatch = words.all { word -> word in haystack(entity) }
                    (domain == null || id.startsWith("$domain.")) && wordsMatch
                }
                // With neither argument this lists the first devices, so a model can see what exists.
                val wanted = listOfNotNull(query?.let { "'$it'" }, domain?.let { "type $it" })
                    .joinToString(" ")
                    .ifEmpty { "any device" }
                if (matches.isEmpty()) {
                    return@withContext ToolResult(
                        success = true,
                        observation = "No device matches $wanted. Try fewer words, or a domain such as " +
                            "light, switch or climate.",
                        data = buildJsonObject { put("count", 0) },
                    )
                }

                val total = matches.size
                val observation = StringBuilder("$total device${if (total == 1) "" else "s"} match $wanted:")
                var shown = 0
                for (entity in matches.take(MAX_DEVICE_ROWS)) {
                    val row = "\n" + describe(entity)
                    if (observation.length + row.length > MAX_OBSERVATION_CHARS) break
                    observation.append(row)
                    shown++
                }
                if (shown < total) {
                    observation.append("\n${total - shown} more match. Narrow the query or add a domain.")
                }
                ToolResult(
                    success = true,
                    observation = observation.toString(),
                    data = buildJsonObject { put("count", total) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                unreachable(tool, e)
            } catch (e: Exception) {
                internal(tool, e)
            }
        }
}

/** One device as a line: id, friendly name, state, and the attributes a person checks. */
private fun describe(entity: JsonObject): String {
    val id = textOf(entity["entity_id"]).orEmpty()
    val attrs = entity["attributes"] as? JsonObject
    val name = textOf(attrs?.get("friendly_name"))?.take(60)?.takeIf { it.isNotBlank() }
    val state = textOf(entity["state"]).orEmpty().take(40)
    val unit = textOf(attrs?.get("unit_of_measurement"))?.take(8)
    val head = if (name != null) "$id ($name)" else id
    val value = if (unit != null) "$state $unit" else state
    val details = details(id.substringBefore('.'), attrs)
    return "- $head: $value" + if (details.isEmpty()) "" else ", $details"
}

private fun details(domain: String, attrs: JsonObject?): String {
    if (attrs == null) return ""
    fun number(key: String): Int? =
        (attrs[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }?.roundToInt()
    return when (domain) {
        "light" -> number("brightness")?.let { "brightness ${(it * 100.0 / 255).roundToInt()}%" }.orEmpty()
        "climate" -> listOfNotNull(
            number("temperature")?.let { "target $it" },
            number("current_temperature")?.let { "now $it" },
        ).joinToString(", ")
        "cover" -> number("current_position")?.let { "position $it%" }.orEmpty()
        else -> ""
    }
}

/** Searchable text for one device: its friendly name and entity id, with separators as spaces. */
private fun haystack(entity: JsonObject): String {
    val id = textOf(entity["entity_id"]).orEmpty()
    val name = textOf((entity["attributes"] as? JsonObject)?.get("friendly_name")).orEmpty()
    return "$name $id".lowercase().replace('_', ' ').replace('.', ' ')
}

// ---------------------------------------------------------------------------
// home.control and home.secure
// ---------------------------------------------------------------------------

/**
 * `POST /api/services/<domain>/<service>` for one entity. [name] picks the tier:
 * [CONTROL] for reversible domains, [SECURE] for locks and covers. The other tool
 * refuses the domain, so the tier is fixed by the domain, not by what the model asks.
 */
private class HomeControlTool(
    name: String,
    private val server: () -> HomeServer?,
) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName(name)) { "$name missing from catalogue" }

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            val tool = definition.name
            try {
                val entity = textArg(args, "entity_id")?.lowercase()?.takeIf { ENTITY_ID.matches(it) }
                    ?: return@withContext invalid(
                        tool,
                        "needs entity_id, such as light.kitchen_ceiling. Find it with home.devices",
                        "entity_id",
                    )
                val action = textArg(args, "action")?.lowercase()
                    ?: return@withContext invalid(tool, "needs action", "action")
                val domain = entity.substringBefore('.')

                routing(tool, domain)?.let { return@withContext it }

                val rawValue = args["value"]?.takeUnless { it is JsonNull }
                val value: Double? = if (rawValue == null) {
                    null
                } else {
                    (rawValue as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        ?: return@withContext invalid(tool, "value must be a number", "value")
                }

                val plan = when (val planned = planFor(domain, action, value)) {
                    is Planned.Refused -> return@withContext invalid(tool, planned.reason, "action")
                    is Planned.Ok -> planned.plan
                }

                val cfg = server() ?: return@withContext notSetUp(tool)
                if (context.signal.isCancelled()) return@withContext cancelled(tool)

                val body = buildJsonObject {
                    put("entity_id", entity)
                    plan.data.forEach { (key, element) -> put(key, element) }
                }.toString()
                val reply = exchange(cfg, "POST", "/api/services/$domain/${plan.service}", body)
                if (!reply.ok) return@withContext httpFailure(tool, reply, cfg.token)

                // The reply lists the states the call changed. Quote the new state when it is there.
                val changed = parseArray(reply.body)
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { textOf(it["entity_id"]) == entity }
                val now = textOf(changed?.get("state"))
                val observation = if (now != null) {
                    "$entity is now ${now.take(40)}."
                } else {
                    "Sent $action to $entity. Home Assistant accepted it; check it with home.devices."
                }
                ToolResult(
                    success = true,
                    observation = observation,
                    data = buildJsonObject {
                        put("entity_id", entity)
                        put("action", action)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                unreachable(tool, e)
            } catch (e: Exception) {
                internal(tool, e)
            }
        }

    /** A refusal when this tool does not own [domain]; null when it does. */
    private fun routing(tool: String, domain: String): ToolResult? {
        if (domain == ALARM_DOMAIN) {
            return ToolResult(
                success = false,
                observation = "Alarm panels are not controlled by this app. Use the alarm system's own app.",
                error = ToolError.PermissionDenied("alarm panels are not offered"),
            )
        }
        val owner = when (domain) {
            in SECURE_DOMAINS -> SECURE
            in REVERSIBLE_DOMAINS -> CONTROL
            else -> return ToolResult(
                success = false,
                observation = "$tool does not handle $domain devices. It handles " +
                    "${REVERSIBLE_DOMAINS.sorted().joinToString()} (home.control) and " +
                    "${SECURE_DOMAINS.sorted().joinToString()} (home.secure).",
                error = ToolError.InvalidArguments("domain"),
            )
        }
        if (owner == tool) return null
        val why = if (owner == SECURE) "which asks the user to confirm first" else "which runs without asking"
        return ToolResult(
            success = false,
            observation = "$domain devices go through $owner, $why. Call $owner instead.",
            error = ToolError.InvalidArguments("domain"),
        )
    }
}

/** The service to call for one domain and action, or the reason it is refused. */
private sealed interface Planned {
    class Ok(val plan: Plan) : Planned
    class Refused(val reason: String) : Planned
}

private class Plan(val service: String, val data: Map<String, JsonElement>)

private fun planFor(domain: String, action: String, value: Double?): Planned {
    val allowed = ALLOWED_ACTIONS[domain].orEmpty()
    if (action !in allowed) {
        return Planned.Refused("$domain devices take ${allowed.sorted().joinToString()}, not \"$action\".")
    }
    val service = when (action) {
        "play" -> "media_play"
        "pause" -> "media_pause"
        "open" -> "open_cover"
        "close" -> "close_cover"
        else -> action
    }
    val data = mutableMapOf<String, JsonElement>()
    when {
        domain == "light" && action == "turn_on" && value != null ->
            data["brightness_pct"] = JsonPrimitive(value.coerceIn(0.0, 100.0).roundToInt())
        domain == "climate" && action == "set_temperature" -> {
            if (value == null) return Planned.Refused("set_temperature needs value, the temperature in degrees.")
            if (value !in 5.0..35.0) return Planned.Refused("the temperature must be between 5 and 35 degrees.")
            data["temperature"] = JsonPrimitive(value)
        }
        value != null -> return Planned.Refused(
            "$action does not take a value. Only light turn_on (brightness) and set_temperature (degrees) do.",
        )
    }
    return Planned.Ok(Plan(service, data))
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

private class HomeReply(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
}

/**
 * One HTTP exchange with the user's server. Redirects are not followed: a 3xx is a
 * failure, because following it would hand the token to whatever host it names.
 */
private fun exchange(server: HomeServer, method: String, path: String, json: String?): HomeReply {
    val connection = URL(server.base + path).openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_MS
        connection.readTimeout = READ_MS
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer ${server.token}")
        connection.setRequestProperty("Accept", "application/json")
        if (json != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use(::readCapped) ?: ByteArray(0)
        return HomeReply(status, String(bytes, Charsets.UTF_8))
    } finally {
        connection.disconnect()
    }
}

private fun readCapped(stream: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val n = stream.read(buffer)
        if (n < 0) break
        if (out.size() + n > MAX_REPLY_BYTES) throw IOException("reply larger than the limit")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

private fun parseArray(body: String): JsonArray? =
    runCatching { Json.parseToJsonElement(body) as? JsonArray }.getOrNull()

private fun textOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

private fun textArg(args: ToolArgs, key: String): String? =
    (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun invalid(tool: String, what: String, field: String) = ToolResult(
    success = false,
    observation = "$tool ${what.trimEnd('.')}.",
    error = ToolError.InvalidArguments(field),
)

private fun notSetUp(tool: String) = ToolResult(
    success = false,
    observation = "$tool: Home Assistant is not set up. Add its address and access token in Settings, under Plugins.",
    error = ToolError.PermissionDenied("home assistant not set up"),
)

private fun cancelled(tool: String) = ToolResult(
    success = false,
    observation = "$tool was cancelled.",
    error = ToolError.Cancelled("$tool cancelled"),
)

private fun unexpectedReply(tool: String) = ToolResult(
    success = false,
    observation = "$tool: Home Assistant sent a reply this app does not understand. Check the address in Settings.",
    error = ToolError.Unavailable("unexpected reply"),
)

private fun unreachable(tool: String, e: IOException) = ToolResult(
    success = false,
    observation = "$tool could not reach Home Assistant (${e.javaClass.simpleName}). " +
        "Check the connection and the address in Settings.",
    error = ToolError.Unavailable("network: ${e.javaClass.simpleName}"),
)

private fun internal(tool: String, e: Exception) = ToolResult(
    success = false,
    observation = "$tool could not run (${e.javaClass.simpleName}). Do not retry the same call.",
    error = ToolError.Internal("${e.javaClass.simpleName} in $tool"),
)

/** A failed reply as one short line. The token is removed from the detail first, in case a reply echoes it. */
private fun httpFailure(tool: String, reply: HomeReply, token: String): ToolResult {
    val detail = reply.body.replace(token, "[token]").replace(Regex("\\s+"), " ").take(160)
    val said = if (detail.isBlank()) "" else " Home Assistant said: $detail"
    return when (reply.status) {
        401, 403 -> ToolResult(
            success = false,
            observation = "$tool: Home Assistant refused the access token. Create a new one and paste it in Settings.",
            error = ToolError.PermissionDenied("token refused"),
        )
        404 -> ToolResult(
            success = false,
            observation = "$tool: Home Assistant has no such device or action.$said",
            error = ToolError.NotFound("HTTP 404"),
        )
        400 -> ToolResult(
            success = false,
            observation = "$tool: Home Assistant rejected the call (HTTP 400).$said",
            error = ToolError.InvalidArguments("Home Assistant rejected the call"),
        )
        else -> ToolResult(
            success = false,
            observation = "$tool failed with HTTP ${reply.status}. Try again later.$said",
            error = ToolError.Unavailable("HTTP ${reply.status}"),
        )
    }
}
