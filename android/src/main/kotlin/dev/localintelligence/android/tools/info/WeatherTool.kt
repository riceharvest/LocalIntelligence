package dev.localintelligence.android.tools.info

import android.content.Context
import dev.localintelligence.android.tools.location.phoneFix
import dev.localintelligence.android.tools.web.WebArgs
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.CancellationSignal
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// ===========================================================================
// weather.get: current weather and a short forecast, metric units.
//
// WHY OPEN-METEO: it is free, needs no key, and answers with WMO weather codes and
// plain numbers, so the reply is small and the text the model sees is written here,
// not copied from the service.
//
// WHERE THE PLACE COMES FROM: a `place` argument is geocoded by Open-Meteo. With no
// place, the phone's location is read through the same code path as location.get,
// including its permission check. The coordinates leave the phone only in the
// forecast request, rounded to four decimals.
// ===========================================================================

private const val SERVICE: String = "Open-Meteo"
private const val FORECAST_ENDPOINT: String = "https://api.open-meteo.com/v1/forecast"
private const val GEOCODING_ENDPOINT: String = "https://geocoding-api.open-meteo.com/v1/search"

private val WEATHER_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("place") {
            put("type", "string")
            put("maxLength", 120)
            put("description", "Town or place name, for example Haarlem. Leave out for the phone's location.")
        }
        putJsonObject("days") {
            put("type", "integer")
            put("minimum", 1)
            put("maximum", 7)
            put("description", "Days to forecast, 1 to 7. Default 1, today only.")
        }
    }
    putJsonArray("required") { }
}

class WeatherGetTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "weather.get",
        description = "Gets the current weather, today's high, low and rain chance, and the days after, " +
            "for a place or the phone's location.",
        category = "info",
        schema = WEATHER_SCHEMA,
        // NETWORK_EGRESS like web.search: the query leaves the phone, so the user confirms (or
        // auto-accepts network tools) the same way.
        risk = ToolRisk.NETWORK_EGRESS,
        fixedHost = "api.open-meteo.com",
        observationOrigin = ObservationOrigin.NETWORK,
        tags = setOf("weather", "rain", "temperature", "forecast", "weer", "regen"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                internetDenied("weather.get", grant)?.let { return@withContext it }
                val place = WebArgs.coerceString(args["place"])
                val days = WebArgs.coerceInt(args["days"], fallback = 1, min = 1, max = 7)
                if (context.signal.isCancelled()) return@withContext cancelledResult("weather.get")

                val spot = if (place != null) {
                    geocode(place, context.signal) ?: return@withContext ToolResult(
                        success = false,
                        observation = "No place called \"$place\" was found. Try a nearby town or a fuller name.",
                        error = ToolError.NotFound("no place named $place"),
                    )
                } else {
                    if (!grant.isGranted(ToolPermissions.LOCATION)) return@withContext ToolResult(
                        success = false,
                        observation = PermissionDenial.observation("weather.get", ToolPermissions.LOCATION),
                        error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.LOCATION)),
                    )
                    val fix = phoneFix(appContext) ?: return@withContext ToolResult(
                        success = false,
                        observation = "No location is available, so the weather here cannot be read. " +
                            "Tell the user to turn on Location, or ask again with a place name.",
                        error = ToolError.Unavailable("no location fix"),
                    )
                    Spot("your location", fix.latitude, fix.longitude)
                }
                if (context.signal.isCancelled()) return@withContext cancelledResult("weather.get")

                val root = getJson(forecastUrl(spot, days), context.signal) as? JsonObject
                    ?: throw UnreadableReply()
                describe(spot.name, root)
            } catch (e: Exception) {
                failureFor(SERVICE, e)
            }
        }

    /** The first geocoding match, or null when Open-Meteo knows no place by that name. */
    private fun geocode(place: String, signal: CancellationSignal): Spot? {
        val url = "$GEOCODING_ENDPOINT?name=${URLEncoder.encode(place, "UTF-8")}&count=1&language=en&format=json"
        val root = getJson(url, signal) as? JsonObject ?: throw UnreadableReply()
        val first = (root["results"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val lat = first["latitude"].doubleValue() ?: throw UnreadableReply()
        val lon = first["longitude"].doubleValue() ?: throw UnreadableReply()
        val label = listOfNotNull(first["name"].textOrNull(), first["country"].textOrNull())
            .joinToString(", ")
        return Spot(label.ifEmpty { place }, lat, lon)
    }

    /** The reply as the observation: now, today, then one line for each later day. */
    private fun describe(name: String, root: JsonObject): ToolResult {
        val current = root["current"] as? JsonObject
        val daily = root["daily"] as? JsonObject ?: throw UnreadableReply()
        val dates = daily.column("time")
        if (dates.isEmpty()) throw UnreadableReply()
        val codes = daily.column("weather_code")
        val highs = daily.column("temperature_2m_max")
        val lows = daily.column("temperature_2m_min")
        val rains = daily.column("precipitation_probability_max")
        val forecast = dates.indices.map { i ->
            Day(
                date = dates[i].textOrNull(),
                code = codes.getOrNull(i).intValue(),
                high = highs.getOrNull(i).doubleValue(),
                low = lows.getOrNull(i).doubleValue(),
                rain = rains.getOrNull(i).doubleValue(),
            )
        }

        val now = current?.get("temperature_2m").doubleValue()
        val nowCode = current?.get("weather_code").intValue()
        val today = forecast.first()
        val lines = mutableListOf<String>()
        lines += "Weather for $name: now ${now?.let { "${it.roundToInt()} °C" } ?: "unknown"}, " +
            "${condition(nowCode)}. Today: high ${today.high.celsius()}, low ${today.low.celsius()}, " +
            "rain chance ${today.rain.percent()}, ${condition(today.code)}."
        for (day in forecast.drop(1)) {
            lines += "${weekday(day.date)}: high ${day.high.celsius()}, low ${day.low.celsius()}, " +
                "rain chance ${day.rain.percent()}, ${condition(day.code)}."
        }
        return ToolResult(
            success = true,
            observation = lines.joinToString("\n"),
            data = buildJsonObject {
                put("days", forecast.size)
                put("place", name)
            },
        )
    }

    private fun forecastUrl(spot: Spot, days: Int): String =
        "$FORECAST_ENDPOINT?latitude=${coordinate(spot.lat)}&longitude=${coordinate(spot.lon)}" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=$days"

    private fun coordinate(value: Double): String = String.format(Locale.US, "%.4f", value)

    private class Spot(val name: String, val lat: Double, val lon: Double)

    private class Day(
        val date: String?,
        val code: Int?,
        val high: Double?,
        val low: Double?,
        val rain: Double?,
    )
}

/** One daily column from the reply, or an empty list when the service left it out. */
private fun JsonObject.column(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

private fun JsonElement?.doubleValue(): Double? = (this as? JsonPrimitive)?.doubleOrNull

private fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun Double?.celsius(): String = this?.let { "${it.roundToInt()} °C" } ?: "unknown"

private fun Double?.percent(): String = this?.let { "${it.roundToInt()}%" } ?: "unknown"

/** "Sun 1 Nov" from an ISO date, or the date as sent when it does not parse. */
private fun weekday(date: String?): String {
    if (date == null) return "later"
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return parsed.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
}

/** WMO weather interpretation codes, as Open-Meteo documents them. Unknown codes read as unknown. */
private fun condition(code: Int?): String = when (code) {
    0 -> "clear sky"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61, 63, 65 -> "rain"
    66, 67 -> "freezing rain"
    71, 73, 75 -> "snow"
    77 -> "snow grains"
    80, 81, 82 -> "rain showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunderstorm with hail"
    else -> "conditions unknown"
}
