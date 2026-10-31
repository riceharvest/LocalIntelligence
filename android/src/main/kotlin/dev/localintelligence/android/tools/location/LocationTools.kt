package dev.localintelligence.android.tools.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.localintelligence.core.model.ObservationOrigin
import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.ToolRisk
import dev.localintelligence.core.tool.contracts.PermissionDenial
import dev.localintelligence.core.tool.contracts.PlatformGrant
import dev.localintelligence.core.tool.contracts.ToolPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// =====================================================================================
// LocationTools: location.get.
//
// WHY COARSE, NOT FINE: the only permission this tool asks for is
// ACCESS_COARSE_LOCATION. With coarse alone Android refuses the GPS provider
// outright (SecurityException), so the fresh-fix path uses the network/fused
// provider and the last-known path skips any provider that throws. The answer is
// approximate by design, and the observation says how approximate.
//
// WHY A TIMEOUT: getCurrentLocation has no timeout of its own, and a model waiting on
// a cold GPS for a minute is worse than a slightly older fix. The fresh request gets
// FRESH_FIX_TIMEOUT_MS and then the last known fix across providers answers instead.
// =====================================================================================

private const val FRESH_FIX_TIMEOUT_MS = 6_000L

/** Providers usable with a coarse grant, in the order a fresh fix should try them. */
private val FRESH_PROVIDERS = listOf(LocationManager.NETWORK_PROVIDER, "fused")

private val LOCATION_SCHEMA = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { }
    putJsonArray("required") { }
}

class LocationGetTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "location.get",
        description = "Get the phone's current approximate location as coordinates and accuracy. " +
            "Use when the user asks where they are or for their location.",
        category = "location",
        schema = LOCATION_SCHEMA,
        risk = ToolRisk.READ_ONLY,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf(
            "where am i", "location", "gps", "my location", "where are we",
            "coordinates", "current location", "nearby",
            // Dutch: the scorer reads only the tool's English text, so a Dutch request needs these to count as asked.
            "waar ben ik", "locatie", "hier",
        ),
        requiredPermission = ToolPermissions.ACCESS_COARSE_LOCATION,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult =
        withContext(Dispatchers.IO) {
            // Asked of the platform, not of ToolContext.permissionGranted, for the same
            // reason as contacts.search: that flag is never populated.
            if (!grant.isGranted(ToolPermissions.LOCATION)) {
                return@withContext ToolResult(
                    success = false,
                    observation = PermissionDenial.observation("location.get", ToolPermissions.LOCATION),
                    error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.LOCATION)),
                )
            }
            if (context.signal.isCancelled()) {
                return@withContext ToolResult(
                    success = false,
                    observation = "Cancelled before the location was read.",
                    error = ToolError.Cancelled("cancelled before location.get"),
                )
            }

            try {
                val lm = appContext.getSystemService(LocationManager::class.java)
                    ?: return@withContext ToolResult(
                        success = false,
                        observation = "This phone has no location service, so the location cannot be read.",
                        error = ToolError.Unavailable("no LocationManager"),
                    )
                val fix = currentFix(appContext, lm) ?: return@withContext ToolResult(
                    success = false,
                    observation = "No location is available: Location is turned off or no fix could be " +
                        "found. Tell the user to turn on Location in quick settings, then try again.",
                    error = ToolError.Unavailable("no location fix"),
                )
                ToolResult(
                    success = true,
                    observation = describe(fix, System.currentTimeMillis()),
                    data = buildJsonObject {
                        put("lat", fix.latitude)
                        put("lon", fix.longitude)
                        if (fix.hasAccuracy()) put("accuracy_m", fix.accuracy.toDouble())
                        put("time_millis", fix.time)
                    },
                )
            } catch (e: SecurityException) {
                // Revoked between the grant check and the call.
                ToolResult(
                    success = false,
                    observation = PermissionDenial.observation("location.get", ToolPermissions.LOCATION),
                    error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.LOCATION)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never a stack trace in the observation; a short reference is enough.
                ToolResult(
                    success = false,
                    observation = "Internal error in location.get (${e.javaClass.simpleName}). " +
                        "This is a bug in the app, not something retrying will fix.",
                    error = ToolError.Internal("location.get ${e.javaClass.simpleName}"),
                )
            }
        }

    private fun describe(fix: Location, nowMillis: Long): String {
        val coords = String.format(Locale.US, "%.5f, %.5f", fix.latitude, fix.longitude)
        val accuracy = if (fix.hasAccuracy()) "±${fix.accuracy.roundToInt()} m" else "accuracy unknown"
        val ageMinutes = (nowMillis - fix.time).coerceAtLeast(0L) / 60_000L
        return "$coords ($accuracy, $ageMinutes min old)"
    }
}

/** The tool set for the location family. */
fun locationTools(context: Context, grant: PlatformGrant): List<AgentTool> =
    listOf(LocationGetTool(context.applicationContext, grant))

/**
 * The phone's best current fix, or null when there is none. The caller checks the
 * permission first. weather.get uses this too, so both tools read location the same way.
 */
internal suspend fun phoneFix(appContext: Context): Location? {
    val lm = appContext.getSystemService(LocationManager::class.java) ?: return null
    return currentFix(appContext, lm)
}

/** A fresh fix when the platform can give one in time, else the best last known fix. */
@SuppressLint("MissingPermission")
private suspend fun currentFix(appContext: Context, lm: LocationManager): Location? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val provider = FRESH_PROVIDERS.firstOrNull { lm.isProviderEnabled(it) }
        if (provider != null) {
            val fresh = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { requestFresh(appContext, lm, provider) }
            if (fresh != null) return fresh
        }
    }
    return lastKnown(lm)
}

/** API 30+ only; the caller checks the SDK level. Cancelling the coroutine cancels the request. */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.R)
private suspend fun requestFresh(appContext: Context, lm: LocationManager, provider: String): Location? =
    suspendCancellableCoroutine<Location?> { cont ->
        val cancel = android.os.CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        try {
            lm.getCurrentLocation(
                provider,
                cancel,
                ContextCompat.getMainExecutor(appContext),
            ) { location -> if (cont.isActive) cont.resume(location) }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }

/** The newest last-known fix across every enabled provider that will answer. */
@SuppressLint("MissingPermission")
private fun lastKnown(lm: LocationManager): Location? {
    var best: Location? = null
    for (provider in lm.getProviders(true)) {
        val loc = try {
            lm.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            null
        } ?: continue
        if (best == null || loc.time > best.time) best = loc
    }
    return best
}
