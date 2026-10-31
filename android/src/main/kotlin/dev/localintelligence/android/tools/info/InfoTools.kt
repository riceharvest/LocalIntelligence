package dev.localintelligence.android.tools.info

import android.content.Context
import dev.localintelligence.android.tools.web.exchangeJson
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// ===========================================================================
// The "Weather and facts" family: weather.get, wiki.search, places.search.
//
// Three free services, none of them needs a key, so the family is on by default and
// shows in Simple mode. Each tool's reply comes from a third party, so each one is
// NETWORK origin: the loop fences its observation as untrusted text.
//
// THE SERVICES (response shapes were checked with real requests):
//   Open-Meteo forecast  https://api.open-meteo.com/v1/forecast
//   Open-Meteo geocoding https://geocoding-api.open-meteo.com/v1/search
//     https://open-meteo.com/en/docs and https://open-meteo.com/en/docs/geocoding-api
//   Wikipedia REST       https://en.wikipedia.org/w/rest.php/v1/search/page
//                        https://en.wikipedia.org/api/rest_v1/page/summary/{title}
//     https://www.mediawiki.org/wiki/API:REST_API
//   Nominatim (OSM)      https://nominatim.openstreetmap.org/search
//     https://operations.osmfoundation.org/policies/nominatim/ (one request a second,
//     no bulk, an identifying User-Agent)
// ===========================================================================

/** Identity for the free services. Nominatim's policy asks for one that names the app. */
internal const val INFO_USER_AGENT: String = "LocalIntelligence/1.0 (on-device Android agent)"

/** The tools of the Weather and facts family, in catalogue order. */
fun infoTools(context: Context, grant: PlatformGrant): List<AgentTool> = listOf(
    WeatherGetTool(context.applicationContext, grant),
    WikiSearchTool(grant),
    PlacesSearchTool(grant),
)

/** A service answered with a non-2xx status. */
internal class HttpStatusException(val status: Int) : IOException("HTTP $status")

/** A 2xx answer whose body is not the JSON shape the tool reads. */
internal class UnreadableReply : IOException("unreadable reply")

/**
 * GET [url] and parse the body as JSON. Blocking; call it on Dispatchers.IO. Throws
 * [HttpStatusException] or [UnreadableReply], which [failureFor] turns into a result.
 */
internal fun getJson(url: String, signal: CancellationSignal): JsonElement {
    val reply = exchangeJson("GET", url, emptyMap(), null, INFO_USER_AGENT, signal)
    if (!reply.ok) throw HttpStatusException(reply.status)
    return try {
        Json.parseToJsonElement(reply.body)
    } catch (e: IllegalArgumentException) {
        throw UnreadableReply()
    }
}

/** The refusal when the phone may not use the network, or null when it may. */
internal fun internetDenied(tool: String, grant: PlatformGrant): ToolResult? {
    if (grant.isGranted(ToolPermissions.INTERNET_REQUIREMENT)) return null
    return ToolResult(
        success = false,
        observation = PermissionDenial.observation(tool, ToolPermissions.INTERNET_REQUIREMENT),
        error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.INTERNET_REQUIREMENT)),
    )
}

/**
 * Every failed call, as one plain sentence that says what to do next. A phone with no
 * network gets the "could not reach" sentence and no stack trace.
 */
internal fun failureFor(service: String, e: Exception): ToolResult = when (e) {
    is HttpStatusException -> ToolResult(
        success = false,
        observation = if (e.status == 429) {
            "$service is rate-limiting this phone (HTTP 429). Do not retry right away."
        } else {
            "$service answered HTTP ${e.status}. Do not retry right away."
        },
        error = ToolError.Unavailable("HTTP ${e.status}"),
    )
    is UnreadableReply -> ToolResult(
        success = false,
        observation = "$service sent a reply this app could not read. Do not retry right away.",
        error = ToolError.Unavailable("unreadable reply"),
    )
    is UnknownHostException -> ToolResult(
        success = false,
        observation = "Could not reach $service: no such host. The connection may be down; " +
            "check it, then try once more.",
        error = ToolError.Unavailable("no such host"),
    )
    is SocketTimeoutException -> ToolResult(
        success = false,
        observation = "$service timed out. Do not retry immediately.",
        error = ToolError.Timeout("socket timeout"),
    )
    is SSLException -> ToolResult(
        success = false,
        observation = "The secure connection to $service failed. Do not retry; report that it is unavailable.",
        error = ToolError.Unavailable("TLS failed"),
    )
    is IOException -> ToolResult(
        success = false,
        observation = "Could not reach $service (${e.javaClass.simpleName}). " +
            "Check the connection, then try once more.",
        error = ToolError.Unavailable("network: ${e.javaClass.simpleName}"),
    )
    is SecurityException -> ToolResult(
        success = false,
        observation = "Android refused the network request to $service. A network policy may be blocking it.",
        error = ToolError.PermissionDenied("SecurityException"),
    )
    else -> ToolResult(
        success = false,
        observation = "$service lookup failed unexpectedly (${e.javaClass.simpleName}). " +
            "This is a tool bug; do not retry the same request.",
        error = ToolError.Internal("${e.javaClass.simpleName} in $service lookup"),
    )
}

/** A bad argument, said in one sentence the model can fix. */
internal fun invalidResult(observation: String): ToolResult = ToolResult(
    success = false,
    observation = observation,
    error = ToolError.InvalidArguments(observation.take(100)),
)

/** The result when the user's stop arrived before the call started or while it waited. */
internal fun cancelledResult(tool: String): ToolResult = ToolResult(
    success = false,
    observation = "$tool was cancelled before it finished.",
    error = ToolError.Cancelled("$tool cancelled"),
)

/** A JSON string field, or null for a missing or non-string one. */
internal fun JsonElement?.textOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Nominatim's usage policy: one request a second from one client, no bulk. Used by places.search only. */
internal object NominatimPace {
    private const val MIN_GAP_MS: Long = 1_000L
    private var lastStart: Long = 0L

    /**
     * Waits until the next request may start. Returns false if the call was cancelled
     * while it waited. Blocking; the caller is on Dispatchers.IO.
     */
    @Synchronized
    fun await(signal: CancellationSignal): Boolean {
        var left = MIN_GAP_MS - (System.currentTimeMillis() - lastStart)
        while (left > 0 && !signal.isCancelled()) {
            val step = minOf(left, 100L)
            Thread.sleep(step)
            left -= step
        }
        if (signal.isCancelled()) return false
        lastStart = System.currentTimeMillis()
        return true
    }
}
