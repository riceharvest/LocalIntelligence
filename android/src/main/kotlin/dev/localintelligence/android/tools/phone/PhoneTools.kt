package dev.localintelligence.android.tools.phone

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import dev.localintelligence.android.tools.device.ArgCoerce
import dev.localintelligence.android.tools.device.ArgResult
import dev.localintelligence.android.tools.device.guarded
import dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =====================================================================================
// Phone controls: flashlight, volume, media keys, dialer, maps and the one place the
// model may name a phone number out loud. Everything here is a user-visible action the
// phone already has a button for, which is why all of it is REVERSIBLE or READ_ONLY and
// none of it needs a permission beyond what the platform grants by itself.
//
// Nothing here calls a number. ACTION_DIAL only fills the dialer, and the user presses
// call. Nothing here saves a contact either; contacts.add in ContactsTools.kt opens the
// contacts app with the fields filled in.
// =====================================================================================

/** Pure helpers for the phone family. No android.* types, so the rules are plain functions. */
internal object PhoneNumber {

    /** Longest dialable string we will hand to the dialer: an international number plus codes. */
    const val MAX_CHARS = 30

    /** Digits, spaces and the usual separators, with an optional leading +. Anything else is refused. */
    private val SHAPE = Regex("^\\+?[0-9 ()./*#-]+$")

    /**
     * Returns the number in dialer form (`+31612345678`, `0612345678`), or null when the
     * text is not a phone number. Letters are refused rather than dropped: "06 12 call
     * mom" with the letters silently removed would dial a number nobody read.
     */
    fun clean(raw: String): String? {
        val trimmed = raw.trim()
        if (!SHAPE.matches(trimmed)) return null
        val digits = trimmed.filter { it.isDigit() || it == '*' || it == '#' }
        if (digits.none { it.isDigit() } || digits.length > MAX_CHARS) return null
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }
}

/** Volume percentages mapped onto a stream's own range, and back again. */
internal object VolumeMath {

    /** The raw index for [percent] (0..100) in a stream whose range is [min]..[max]. */
    fun rawFor(percent: Int, min: Int, max: Int): Int {
        if (max <= min) return min
        return min + ((max - min) * percent + 50) / 100
    }

    /** The percentage a raw index sits at. Reading back the real value keeps the observation honest. */
    fun percentOf(raw: Int, min: Int, max: Int): Int {
        if (max <= min) return 0
        return ((raw - min) * 100 + (max - min) / 2) / (max - min)
    }
}

/** The streams a user actually thinks of as "volume". */
internal enum class VolumeStream(val arg: String, val platform: Int, val label: String) {
    MEDIA("media", AudioManager.STREAM_MUSIC, "Media"),
    RING("ring", AudioManager.STREAM_RING, "Ring"),
    ALARM("alarm", AudioManager.STREAM_ALARM, "Alarm"),
    NOTIFICATION("notification", AudioManager.STREAM_NOTIFICATION, "Notification");

    companion object {
        val ARG_NAMES: List<String> = values().map { it.arg }

        fun byArg(raw: String): VolumeStream? = values().firstOrNull { it.arg == raw.trim().lowercase() }
    }
}

/** The three ringer modes. Silent is the one that needs Do Not Disturb access. */
internal enum class RingerMode(val arg: String, val platform: Int) {
    NORMAL("normal", AudioManager.RINGER_MODE_NORMAL),
    VIBRATE("vibrate", AudioManager.RINGER_MODE_VIBRATE),
    SILENT("silent", AudioManager.RINGER_MODE_SILENT);

    companion object {
        val ARG_NAMES: List<String> = values().map { it.arg }

        fun byArg(raw: String): RingerMode? = values().firstOrNull { it.arg == raw.trim().lowercase() }
    }
}

/** Transport controls the media key row already has. */
internal enum class MediaAction(val arg: String) {
    PLAY("play"),
    PAUSE("pause"),
    NEXT("next"),
    PREVIOUS("previous");

    companion object {
        val ARG_NAMES: List<String> = values().map { it.arg }

        fun byArg(raw: String): MediaAction? = values().firstOrNull { it.arg == raw.trim().lowercase() }
    }
}

/**
 * Travel modes. [letter] is the value Google Maps reads from `google.navigation:mode=`.
 * `transit` is mapped to `r` as the brief specifies; see the report on the open question.
 */
internal enum class NavMode(val arg: String, val letter: String) {
    DRIVE("drive", "d"),
    WALK("walk", "w"),
    BIKE("bike", "b"),
    TRANSIT("transit", "r");

    companion object {
        val ARG_NAMES: List<String> = values().map { it.arg }

        fun byArg(raw: String): NavMode? = values().firstOrNull { it.arg == raw.trim().lowercase() }
    }
}

/** Package of the Google Maps app, which understands `google.navigation:` turn-by-turn links. */
internal const val GOOGLE_MAPS_PACKAGE: String = "com.google.android.apps.maps"

/**
 * Reads a short text argument. An empty string is refused for a required argument and
 * treated as absent for an optional one, because models send "" for fields they skip.
 */
internal fun textArg(args: ToolArgs, key: String, max: Int, optional: Boolean = false): ArgResult<String> =
    when (val r = ArgCoerce.string(args, key)) {
        is ArgResult.Present -> {
            val text = r.value.trim()
            when {
                text.isEmpty() -> if (optional) ArgResult.Missing(key) else ArgResult.Unusable(key, "an empty string")
                text.length > max -> ArgResult.Unusable(key, "a ${text.length}-character string")
                else -> ArgResult.Present(text)
            }
        }
        is ArgResult.Missing -> r
        is ArgResult.Unusable -> r
    }

/** A refusal of the arguments, with nothing attempted. */
internal fun invalidArgument(message: String): ToolResult = ToolResult(
    success = false,
    observation = message,
    error = ToolError.InvalidArguments(message.take(120)),
)

/** The model's cancel signal arrived before the action began. */
internal fun cancelledBefore(tool: String): ToolResult = ToolResult(
    success = false,
    observation = "Cancelled before $tool did anything.",
    error = ToolError.Cancelled("cancelled before $tool"),
)

/**
 * Starts [intent] as a new task. False when no app took it or the platform refused.
 *
 * Both exceptions are the answer "nothing on this phone can do that", not a fault, so
 * they are returned as false rather than thrown into [guarded]'s permission message.
 */
internal fun startIntent(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/**
 * True when some installed app declares it can take [intent]. Needs the matching
 * `<queries>` entry in the manifest on API 30+, or this answers no for an app that is there.
 */
internal fun canHandle(context: Context, intent: Intent): Boolean =
    intent.resolveActivity(context.packageManager) != null

// =====================================================================================
// Schemas
// =====================================================================================

private val FLASHLIGHT_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("on") {
            put("type", "boolean")
            put("description", "true to turn the flashlight on, false to turn it off.")
        }
    }
    putJsonArray("required") { add("on") }
}

private val VOLUME_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("stream") {
            put("type", "string")
            putJsonArray("enum") { VolumeStream.ARG_NAMES.forEach { add(it) } }
            put("description", "Which volume to change. Default media.")
        }
        putJsonObject("level") {
            put("type", "integer")
            put("minimum", 0)
            put("maximum", 100)
            put("description", "Volume as a percentage, 0 to 100.")
        }
        putJsonObject("mode") {
            put("type", "string")
            putJsonArray("enum") { RingerMode.ARG_NAMES.forEach { add(it) } }
            put("description", "Ringer mode: normal, vibrate or silent. Only for the ring stream. Silent needs Do Not Disturb access.")
        }
    }
    putJsonArray("required") { }
}

private val MEDIA_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            putJsonArray("enum") { MediaAction.ARG_NAMES.forEach { add(it) } }
            put("description", "play, pause, next or previous.")
        }
    }
    putJsonArray("required") { add("action") }
}

private val DIAL_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("number") {
            put("type", "string")
            put("maxLength", PhoneNumber.MAX_CHARS)
            put("description", "Phone number to dial, e.g. +31612345678. Look a name up with contacts.search first.")
        }
    }
    putJsonArray("required") { add("number") }
}

private val NAVIGATE_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("destination") {
            put("type", "string")
            put("maxLength", 200)
            put("description", "Where to go: an address, a place name or a city.")
        }
        putJsonObject("mode") {
            put("type", "string")
            putJsonArray("enum") { NavMode.ARG_NAMES.forEach { add(it) } }
            put("description", "How to travel. Default drive.")
        }
    }
    putJsonArray("required") { add("destination") }
}

private val SHOW_SCHEMA: ToolArgs = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("query") {
            put("type", "string")
            put("maxLength", 200)
            put("description", "Place, address or search to show on the map.")
        }
    }
    putJsonArray("required") { add("query") }
}

// =====================================================================================
// Tools
// =====================================================================================

/** REVERSIBLE. Turns the torch on the first camera that has one. Needs no camera permission. */
class PhoneFlashlightTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "phone.flashlight",
        description = "Turn the phone's flashlight on or off and return whether it changed.",
        category = "phone",
        schema = FLASHLIGHT_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        // WHY: the observation is the app's own wording, not page text; fencing it made the model repeat the action.
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("flashlight", "torch", "light", "zaklamp"),
        // Torch mode is open to any app on API 23+; the CAMERA permission is not involved.
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val on = ArgCoerce.booleanOrNull(args["on"])
            ?: return invalidArgument("on is required: true to turn the flashlight on, false to turn it off.")
        if (context.signal.isCancelled()) return cancelledBefore("phone.flashlight")
        return guarded("phone.flashlight") { setTorch(on) }
    }

    private fun setTorch(on: Boolean): ToolResult {
        val manager = appContext.getSystemService(CameraManager::class.java)
            ?: return ToolResult(
                success = false,
                observation = "This phone's camera service is not available, so the flashlight did not change.",
                error = ToolError.Unavailable("no camera service"),
            )
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: CameraAccessException) {
            return ToolResult(
                success = false,
                observation = "The camera service did not answer, so the flashlight did not change. Try again shortly.",
                error = ToolError.Unavailable("camera service did not answer"),
            )
        }
        if (cameraId == null) {
            return ToolResult(
                success = false,
                observation = "This phone has no flashlight.",
                error = ToolError.Unavailable("no camera with a flash"),
            )
        }
        try {
            manager.setTorchMode(cameraId, on)
        } catch (e: CameraAccessException) {
            return ToolResult(
                success = false,
                observation = "The flashlight did not change. Another app may be using the camera.",
                error = ToolError.Unavailable("torch refused: ${e.reason}"),
            )
        }
        return ToolResult(
            success = true,
            observation = if (on) "Flashlight on." else "Flashlight off.",
            data = buildJsonObject { put("on", on) },
        )
    }
}

/**
 * REVERSIBLE. Sets one stream's volume as a percentage and, for the ring stream, the
 * ringer mode. The percentage is mapped onto the stream's own range, so 50 means half of
 * whatever the phone has, not half of 0..100 raw steps.
 */
class PhoneVolumeTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "phone.volume",
        description = "Set the volume of one sound stream or the ringer mode, and return the new level.",
        category = "phone",
        schema = VOLUME_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf(
            "volume", "louder", "quieter", "mute", "silent", "ringer", "vibrate mode", "harder", "zachter",
        ),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val explicitStream = when (val r = ArgCoerce.string(args, "stream")) {
            is ArgResult.Present -> VolumeStream.byArg(r.value)
                ?: return invalidArgument(
                    "stream must be one of ${VolumeStream.ARG_NAMES.joinToString(", ")}, " +
                        "but was \"${ArgCoerce.ellipsize(r.value, 24)}\".",
                )
            is ArgResult.Missing -> null
            is ArgResult.Unusable -> return invalidArgument(
                "stream must be one word: ${VolumeStream.ARG_NAMES.joinToString(", ")}.",
            )
        }
        val mode = when (val r = ArgCoerce.string(args, "mode")) {
            is ArgResult.Present -> RingerMode.byArg(r.value)
                ?: return invalidArgument(
                    "mode must be one of ${RingerMode.ARG_NAMES.joinToString(", ")}, " +
                        "but was \"${ArgCoerce.ellipsize(r.value, 24)}\".",
                )
            is ArgResult.Missing -> null
            is ArgResult.Unusable -> return invalidArgument(
                "mode must be one word: ${RingerMode.ARG_NAMES.joinToString(", ")}.",
            )
        }
        // The ringer mode only means something for the ring stream, so a mode on its
        // own means the ring stream rather than an error.
        val stream = explicitStream ?: if (mode != null) VolumeStream.RING else VolumeStream.MEDIA
        if (mode != null && stream != VolumeStream.RING) {
            return invalidArgument("mode only applies to the ring stream. Send stream \"ring\" with it, or leave stream out.")
        }
        val level = when (val r = ArgCoerce.intIn(args, "level", 0, 100)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> null
            is ArgResult.Unusable -> return invalidArgument(
                "level must be a number from 0 to 100, but was ${r.got}.",
            )
        }
        if (context.signal.isCancelled()) return cancelledBefore("phone.volume")
        return guarded("phone.volume") { applyVolume(stream, level, mode) }
    }

    private fun applyVolume(stream: VolumeStream, level: Int?, mode: RingerMode?): ToolResult {
        val audio = appContext.getSystemService(AudioManager::class.java)
            ?: return ToolResult(
                success = false,
                observation = "This phone's audio service is not available, so nothing changed.",
                error = ToolError.Unavailable("no audio service"),
            )

        // The ringer mode goes first. If the platform refuses it, the volume has not
        // been touched yet, so "nothing changed" is true.
        if (mode != null) {
            try {
                audio.ringerMode = mode.platform
            } catch (e: SecurityException) {
                if (mode != RingerMode.SILENT) throw e
                return ToolResult(
                    success = false,
                    observation = "Silent needs Do Not Disturb access; set to vibrate instead?",
                    error = ToolError.PermissionDenied("Do Not Disturb access needed for silent ringer"),
                )
            }
        }

        val min = minVolume(audio, stream.platform)
        val max = audio.getStreamMaxVolume(stream.platform)
        if (level != null) {
            audio.setStreamVolume(stream.platform, VolumeMath.rawFor(level, min, max), 0)
        }
        val percent = VolumeMath.percentOf(audio.getStreamVolume(stream.platform), min, max)

        val volumeSentence = when {
            level != null -> "${stream.label} volume is now $percent%."
            mode == null -> "${stream.label} volume is at $percent%."
            else -> null
        }
        val ringerSentence = mode?.let { "Ringer set to ${it.arg}." }
        return ToolResult(
            success = true,
            observation = listOfNotNull(volumeSentence, ringerSentence).joinToString(" "),
            data = buildJsonObject {
                put("stream", stream.arg)
                put("percent", percent)
                if (mode != null) put("ringer", mode.arg)
            },
        )
    }

    /** Android 9 added a real minimum; below that every stream starts at zero. */
    private fun minVolume(audio: AudioManager, stream: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(stream) else 0
}

/**
 * REVERSIBLE. Sends one transport control to the first active media session. Reading
 * the sessions needs the notification-listener grant, which is the only route the
 * platform offers, so this is refused exactly the way notifications.list is refused.
 */
class PhoneMediaTool(
    private val appContext: Context,
    private val grant: PlatformGrant,
) : AgentTool {

    override val definition = ToolDefinition(
        name = "phone.media",
        description = "Play, pause, skip or go back in the media app that is playing, and return what was sent.",
        category = "phone",
        schema = MEDIA_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("music", "pause", "play", "next song", "skip", "previous song", "podcast"),
        // Not a permission a host can grant, so it is named by its capability; see PermissionDenial.
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val action = when (val r = ArgCoerce.string(args, "action")) {
            is ArgResult.Present -> MediaAction.byArg(r.value)
                ?: return invalidArgument("action must be one of ${MediaAction.ARG_NAMES.joinToString(", ")}.")
            is ArgResult.Missing -> return invalidArgument(
                "action is required: one of ${MediaAction.ARG_NAMES.joinToString(", ")}.",
            )
            is ArgResult.Unusable -> return invalidArgument(
                "action must be one word: ${MediaAction.ARG_NAMES.joinToString(", ")}.",
            )
        }
        if (!grant.isGranted(ToolPermissions.NOTIFICATION_LISTENER)) return notificationAccessDenied()
        if (context.signal.isCancelled()) return cancelledBefore("phone.media")
        return guarded("phone.media") { sendControl(action) }
    }

    private fun sendControl(action: MediaAction): ToolResult {
        val sessions = try {
            val listener = ComponentName(appContext, LocalNotificationListenerService::class.java)
            appContext.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(listener)
                ?: emptyList()
        } catch (e: SecurityException) {
            // The platform checks the listener grant itself and throws when it is missing.
            return notificationAccessDenied()
        }
        val controller = sessions.firstOrNull()
            ?: return ToolResult(
                success = false,
                observation = "No media app has an active session on this phone, so nothing was sent. " +
                    "Open the music or podcast app and start something first.",
                error = ToolError.NotFound("no active media session"),
            )
        val controls = controller.transportControls
        when (action) {
            MediaAction.PLAY -> controls.play()
            MediaAction.PAUSE -> controls.pause()
            MediaAction.NEXT -> controls.skipToNext()
            MediaAction.PREVIOUS -> controls.skipToPrevious()
        }
        val pkg = controller.packageName ?: "the media app"
        return ToolResult(
            success = true,
            observation = "Sent ${action.arg} to $pkg.",
            data = buildJsonObject {
                put("action", action.arg)
                put("package", pkg)
            },
        )
    }

    private fun notificationAccessDenied(): ToolResult = ToolResult(
        success = false,
        observation = PermissionDenial.observation("phone.media", ToolPermissions.NOTIFICATION_LISTENER),
        error = ToolError.PermissionDenied(PermissionDenial.summary(ToolPermissions.NOTIFICATION_LISTENER)),
    )
}

/**
 * REVERSIBLE. Fills the dialer and stops there. ACTION_DIAL never places a call, so the
 * user always presses call, and a wrong number costs them a tap rather than a call.
 */
class PhoneDialTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "phone.dial",
        description = "Open the phone dialer with a number filled in. The user presses call; nothing is dialled for them.",
        category = "phone",
        schema = DIAL_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("call", "dial", "phone number", "ring", "bel"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val raw = when (val r = textArg(args, "number", PhoneNumber.MAX_CHARS * 2)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> return invalidArgument("number is required, e.g. +31612345678.")
            is ArgResult.Unusable -> return invalidArgument("number must be one piece of text, but was ${r.got}.")
        }
        val number = PhoneNumber.clean(raw)
            ?: return invalidArgument(
                "\"${ArgCoerce.ellipsize(raw, 24)}\" is not a phone number. Send digits with an " +
                    "optional leading +, e.g. +31612345678. Look up a name with contacts.search first.",
            )
        if (context.signal.isCancelled()) return cancelledBefore("phone.dial")
        return guarded("phone.dial") { openDialer(number) }
    }

    private fun openDialer(number: String): ToolResult {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
        if (!canHandle(appContext, intent)) {
            return ToolResult(
                success = false,
                observation = "No phone app on this phone can open the dialer, so nothing was opened.",
                error = ToolError.Unavailable("no dialer app"),
            )
        }
        if (!startIntent(appContext, intent)) {
            return ToolResult(
                success = false,
                observation = "Android refused to open the dialer, so nothing was opened.",
                error = ToolError.Unavailable("startActivity refused for phone.dial"),
            )
        }
        return ToolResult(
            success = true,
            observation = "Opened the dialer with $number. The call starts when you press call.",
        )
    }
}

/**
 * REVERSIBLE. Opens turn-by-turn directions in Google Maps when it is installed, and the
 * phone's own map app when it is not. Google's `google.navigation:` link is the only
 * one that starts directions; `geo:` only shows a place.
 */
class MapsNavigateTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "maps.navigate",
        description = "Start directions to a place in a maps app, by car, on foot, by bike or by transit.",
        category = "maps",
        schema = NAVIGATE_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("navigate", "directions", "route", "take me to", "how do i get to"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val destination = when (val r = textArg(args, "destination", MAX_PLACE_CHARS)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> return invalidArgument("destination is required: an address or a place name.")
            is ArgResult.Unusable -> return invalidArgument(
                "destination must be one piece of text under $MAX_PLACE_CHARS characters, but was ${r.got}.",
            )
        }
        val mode = when (val r = ArgCoerce.string(args, "mode")) {
            is ArgResult.Present -> NavMode.byArg(r.value)
                ?: return invalidArgument("mode must be one of ${NavMode.ARG_NAMES.joinToString(", ")}.")
            is ArgResult.Missing -> NavMode.DRIVE
            is ArgResult.Unusable -> return invalidArgument(
                "mode must be one word: ${NavMode.ARG_NAMES.joinToString(", ")}.",
            )
        }
        if (context.signal.isCancelled()) return cancelledBefore("maps.navigate")
        return guarded("maps.navigate") { navigate(destination, mode) }
    }

    private fun navigate(destination: String, mode: NavMode): ToolResult {
        val encoded = Uri.encode(destination)
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded&mode=${mode.letter}"))
            .setPackage(GOOGLE_MAPS_PACKAGE)
        if (canHandle(appContext, google) && startIntent(appContext, google)) {
            return ToolResult(success = true, observation = "Opened ${mode.arg} directions to $destination in Google Maps.")
        }

        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
        if (!canHandle(appContext, geo) || !startIntent(appContext, geo)) {
            return ToolResult(
                success = false,
                observation = "No maps app on this phone can do that. Nothing was opened.",
                error = ToolError.Unavailable("no maps app for directions"),
            )
        }
        return ToolResult(
            success = true,
            observation = "Google Maps is not installed, so I opened $destination in the phone's map app. " +
                "The route may need to be started there.",
        )
    }
}

/** REVERSIBLE. Shows a place or search on the map. Nothing is routed. */
class MapsShowTool(private val appContext: Context) : AgentTool {

    override val definition = ToolDefinition(
        name = "maps.show",
        description = "Show a place, address or search on the map without starting directions.",
        category = "maps",
        schema = SHOW_SCHEMA,
        risk = ToolRisk.REVERSIBLE,
        observationOrigin = ObservationOrigin.LOCAL,
        tags = setOf("map", "show on map", "where is", "nearby", "in de buurt"),
        requiredPermission = null,
    )

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val query = when (val r = textArg(args, "query", MAX_PLACE_CHARS)) {
            is ArgResult.Present -> r.value
            is ArgResult.Missing -> return invalidArgument("query is required: a place, address or search.")
            is ArgResult.Unusable -> return invalidArgument(
                "query must be one piece of text under $MAX_PLACE_CHARS characters, but was ${r.got}.",
            )
        }
        if (context.signal.isCancelled()) return cancelledBefore("maps.show")
        return guarded("maps.show") {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
            when {
                !canHandle(appContext, intent) -> ToolResult(
                    success = false,
                    observation = "No maps app on this phone can do that. Nothing was opened.",
                    error = ToolError.Unavailable("no maps app"),
                )
                !startIntent(appContext, intent) -> ToolResult(
                    success = false,
                    observation = "Android refused to open the map, so nothing was opened.",
                    error = ToolError.Unavailable("startActivity refused for maps.show"),
                )
                else -> ToolResult(success = true, observation = "Showing $query on the map.")
            }
        }
    }
}

private const val MAX_PLACE_CHARS: Int = 200

/** All six phone-family tools, wired to a real [Context]. */
fun phoneTools(context: Context, grant: PlatformGrant): List<AgentTool> {
    val app = context.applicationContext
    return listOf(
        PhoneFlashlightTool(app),
        PhoneVolumeTool(app),
        PhoneMediaTool(app, grant),
        PhoneDialTool(app),
        MapsNavigateTool(app),
        MapsShowTool(app),
    )
}
