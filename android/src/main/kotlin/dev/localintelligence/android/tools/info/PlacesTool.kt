package dev.localintelligence.android.tools.info

import dev.localintelligence.android.tools.web.SearchObservations
import dev.localintelligence.android.tools.web.WebArgs
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PlatformGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.util.Locale

// ===========================================================================
// places.search: find a place or address by name on OpenStreetMap (Nominatim).
//
// WHY NOMINATIM: it is the free, keyless geocoder over OpenStreetMap data. Its usage
// policy is the constraint that shapes this file: at most one request a second from
// one client, no bulk use, and an identifying User-Agent. NominatimPace enforces the
// rate, and a single search is one request, never a loop.
//
// The `near` argument is joined onto the query text. Nominatim's free-text search
// handles "Rijksmuseum, Amsterdam" well, and that keeps this to one request.
// ===========================================================================

private const val SERVICE: String = "OpenStreetMap (Nominatim)"
private const val ENDPOINT: String = "https://nominatim.openstreetmap.org/search"

/** Matches shown. Three is enough to pick from, and keeps the observation small. */
private const val LIMIT: Int = 3

private val PLACES_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("query") {
            put("type", "string")
            put("maxLength", 200)
            put("description", "The place name or address, for example Rijksmuseum.")
        }
        putJsonObject("near") {
            put("type", "string")
            put("maxLength", 120)
            put("description", "Town or area to search near, for example Amsterdam. Optional.")
        }
    }
    putJsonArray("required") { }
}

/** One match as Nominatim gives it. */
private class Place(val name: String?, val display: String, val lat: Double, val lon: Double)

class PlacesSearchTool(private val grant: PlatformGrant) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "places.search",
        description = "Finds a place or address on OpenStreetMap and returns each name, address and coordinates.",
        category = "info",
        schema = PLACES_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf("address of", "where is", "find a"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val query = WebArgs.coerceString(args["query"])
                    ?: return@withContext invalidResult(
                        "No query argument. Pass the place or address, for example query=\"Rijksmuseum\".",
                    )
                val near = WebArgs.coerceString(args["near"])
                internetDenied("places.search", grant)?.let { return@withContext it }
                val text = if (near == null) query else "$query, $near"
                if (!NominatimPace.await(context.signal)) return@withContext cancelledResult("places.search")

                val rows = getJson(
                    "$ENDPOINT?q=${URLEncoder.encode(text, "UTF-8")}&format=jsonv2&limit=$LIMIT" +
                        "&addressdetails=0&accept-language=en",
                    context.signal,
                ) as? JsonArray ?: throw UnreadableReply()
                val places = rows.mapNotNull { row ->
                    val item = row as? JsonObject ?: return@mapNotNull null
                    val display = item["display_name"].textOrNull() ?: return@mapNotNull null
                    val lat = item["lat"].textOrNull()?.toDoubleOrNull() ?: return@mapNotNull null
                    val lon = item["lon"].textOrNull()?.toDoubleOrNull() ?: return@mapNotNull null
                    Place(item["name"].textOrNull()?.takeIf { it.isNotBlank() }, display, lat, lon)
                }
                if (places.isEmpty()) {
                    return@withContext ToolResult(
                        success = false,
                        observation = "No place matches \"$text\" on OpenStreetMap. Try a fuller address or another name.",
                        error = ToolError.NotFound("no place for $text"),
                    )
                }
                val lines = places.mapIndexed { index, place ->
                    val label = place.name ?: place.display.substringBefore(',')
                    "${index + 1}. $label. ${SearchObservations.clip(place.display, ADDRESS_CHARS)} " +
                        "(${coordinate(place.lat)}, ${coordinate(place.lon)})"
                }
                ToolResult(
                    success = true,
                    observation = "Places for \"$text\" (OpenStreetMap):\n" + lines.joinToString("\n"),
                    data = buildJsonObject {
                        put("results", places.size)
                    },
                )
            } catch (e: Exception) {
                failureFor(SERVICE, e)
            }
        }

    private fun coordinate(value: Double): String = String.format(Locale.US, "%.5f", value)

    private companion object {
        const val ADDRESS_CHARS: Int = 160
    }
}
