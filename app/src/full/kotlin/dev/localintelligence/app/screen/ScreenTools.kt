package dev.localintelligence.app.screen

import dev.localintelligence.core.model.ToolArgs
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.policy.ScreenControl
import dev.localintelligence.core.policy.ScreenScope
import dev.localintelligence.core.settings.ScreenControlSettings
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.ToolContext
import dev.localintelligence.core.tool.ToolDefinition
import dev.localintelligence.core.tool.ToolError
import dev.localintelligence.core.tool.ToolResult
import dev.localintelligence.core.tool.catalogue.V0ToolCatalogue
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// ===========================================================================
// The screen.* tools (full build only). Every one is SCREEN_CONTROL, so each call is judged by
// RiskPolicy step 2c: a call runs only for an app the person approved in this request.
//
// The action space follows zai-org/Open-AutoGLM (Apache-2.0): Tap, Long Press, Type, Scroll, Back,
// Home and Wait. Its Take_over step, which hands a login or payment to the person, is what the
// policy's password rule and the guarded-word rule do here. The source was not copied.
//
// Definitions come from V0ToolCatalogue, so the tier and origin here are the catalogue's by construction.
// ===========================================================================

/** Every screen tool, for the composition-time catalogue check. Stateless: each call reads the service. */
internal object ScreenTools {
    val all: List<AgentTool> by lazy {
        listOf(
            ScreenReadTool(),
            ScreenTapTool(),
            ScreenLongPressTool(),
            ScreenTypeTool(),
            ScreenScrollTool(),
            ScreenBackTool(),
            ScreenHomeTool(),
            ScreenWaitTool(),
        )
    }
}

/**
 * Turns the screen plugin on for a run: Technical mode only, and only while the accessibility service is
 * connected. Off by default, so nothing reaches the model until the person has done both.
 */
class ScreenControlPlugin(private val reader: SettingsReader) : AgentPlugin {
    override val id: String = ScreenControl.PLUGIN_ID
    override val title: String = "Screen control"
    override val summary: String =
        "Reads and taps in apps you approve, through Accessibility. Technical mode only. Each app asks once per request."
    override val enabledByDefault: Boolean = false
    override val settings: List<SettingDef<*>> = listOf(ScreenControlSettings.BLOCKED_APPS)

    override fun tools(): List<AgentTool> {
        if (reader.mode != SettingsMode.TECHNICAL) return emptyList()
        if (ScreenControlService.instance == null) return emptyList()
        return ScreenTools.all
    }
}

private const val NOT_ON =
    "Screen control is not on. Turn on the LocalIntelligence accessibility service in Settings, then try again."

/** Shared shape: the catalogue definition, the service, and the failure for a missing service or item. */
private abstract class ScreenTool(name: String) : AgentTool {
    override val definition: ToolDefinition =
        checkNotNull(V0ToolCatalogue.byName(name)) { "$name missing from catalogue" }

    protected fun service(): ScreenControlService? = ScreenControlService.instance

    protected fun fail(observation: String, error: ToolError): ToolResult =
        ToolResult(success = false, observation = observation, error = error)

    protected fun ok(observation: String): ToolResult = ToolResult(success = true, observation = observation)

    /** A number argument, or null when it is missing or not a whole number. */
    protected fun intArg(args: ToolArgs, name: String): Int? =
        (args[name] as? JsonPrimitive)?.intOrNull

    /** The number an index argument names, resolved on the live screen, or null. */
    protected suspend fun resolveIndex(args: ToolArgs): ScreenTarget? {
        val index = intArg(args, "index") ?: return null
        return service()?.resolve(index)
    }

    /** The item a number names, or the failure that says to read the screen again. */
    protected fun missingItem(index: Int?): ToolResult = fail(
        "Item ${index ?: "?"} is not on the screen any more. Call screen.read again and use a number from that reading.",
        ToolError.NotFound("screen item ${index ?: "?"} did not resolve"),
    )

    /** The app in front, for the tools that act on no item. */
    override suspend fun screenScope(args: ToolArgs): ScreenScope? = service()?.foregroundScope()

    protected fun itemLabel(target: ScreenTarget): String {
        val label = target.element.text.ifBlank { target.element.description }
        return if (label.isBlank()) target.element.role else "${target.element.role} \"$label\""
    }
}

private class ScreenReadTool : ScreenTool("screen.read") {
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val svc = service() ?: return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        ScreenRun.begin()
        val snapshot = svc.read()
            ?: return fail(
                "There is no app window to read yet. Call screen.wait, then screen.read again.",
                ToolError.Unavailable("no active window"),
            )
        return ok(ScreenTree.render(snapshot))
    }
}

private class ScreenTapTool : ScreenTool("screen.tap") {
    override suspend fun screenScope(args: ToolArgs): ScreenScope? = resolveIndex(args)?.scope()

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val svc = service() ?: return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        val index = intArg(args, "index")
        val target = svc.resolve(index ?: 0) ?: return missingItem(index)
        ScreenRun.begin()
        if (!svc.tap(target)) {
            return fail("Could not tap ${itemLabel(target)}. Call screen.read again to see the screen.", ToolError.Internal("tap refused"))
        }
        return ok("Tapped ${itemLabel(target)} in ${target.appLabel}. Call screen.read to see the result.")
    }
}

private class ScreenLongPressTool : ScreenTool("screen.long_press") {
    override suspend fun screenScope(args: ToolArgs): ScreenScope? = resolveIndex(args)?.scope()

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val svc = service() ?: return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        val index = intArg(args, "index")
        val target = svc.resolve(index ?: 0) ?: return missingItem(index)
        ScreenRun.begin()
        if (!svc.longPress(target)) {
            return fail("Could not long-press ${itemLabel(target)}. Call screen.read again.", ToolError.Internal("long press refused"))
        }
        return ok("Long-pressed ${itemLabel(target)} in ${target.appLabel}.")
    }
}

private class ScreenTypeTool : ScreenTool("screen.type") {
    override suspend fun screenScope(args: ToolArgs): ScreenScope? = resolveIndex(args)?.scope()

    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val svc = service() ?: return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        val index = intArg(args, "index")
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull
            ?: return fail("screen.type needs the text to type.", ToolError.InvalidArguments("text missing"))
        val target = svc.resolve(index ?: 0) ?: return missingItem(index)
        if (target.element.password) {
            return fail(
                "That is a password field, so nothing was typed. Ask the user to type their password themselves.",
                ToolError.PermissionDenied("password field"),
            )
        }
        ScreenRun.begin()
        if (!svc.typeText(target, text)) {
            return fail("Could not type into ${itemLabel(target)}. It may not accept text.", ToolError.Internal("set text refused"))
        }
        return ok("Typed ${text.length} characters into ${itemLabel(target)} in ${target.appLabel}.")
    }
}

private class ScreenScrollTool : ScreenTool("screen.scroll") {
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val svc = service() ?: return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        val direction = (args["direction"] as? JsonPrimitive)?.contentOrNull
        // Down and right show what lies further along; up and left go back.
        val forward = when (direction) {
            "down", "right" -> true
            "up", "left" -> false
            else -> return fail("screen.scroll needs a direction: up, down, left or right.", ToolError.InvalidArguments("direction"))
        }
        ScreenRun.begin()
        if (!svc.scroll(forward)) {
            return fail("There is nothing to scroll on screen. Call screen.read to see what is there.", ToolError.NotFound("no scrollable area"))
        }
        return ok("Scrolled $direction. Call screen.read to see the new items.")
    }
}

private class ScreenBackTool : ScreenTool("screen.back") {
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val svc = service() ?: return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        ScreenRun.begin()
        if (!svc.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) {
            return fail("Back did not work here.", ToolError.Internal("global back refused"))
        }
        return ok("Pressed Back. Call screen.read to see where that led.")
    }
}

private class ScreenHomeTool : ScreenTool("screen.home") {
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val svc = service() ?: return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        ScreenRun.begin()
        if (!svc.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) {
            return fail("Home did not work here.", ToolError.Internal("global home refused"))
        }
        return ok("Pressed Home.")
    }
}

private class ScreenWaitTool : ScreenTool("screen.wait") {
    override suspend fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        if (service() == null) return fail(NOT_ON, ToolError.Unavailable("accessibility service not connected"))
        // The schema caps this at 5; the clamp keeps a malformed call from holding a run for long.
        val seconds = (intArg(args, "seconds") ?: 1).coerceIn(1, 5)
        var waited = 0
        while (waited < seconds) {
            if (context.signal.isCancelled()) return fail("Waiting was stopped.", ToolError.Cancelled("screen.wait"))
            delay(1_000L)
            waited++
        }
        return ok("Waited $seconds seconds. Call screen.read to see the screen.")
    }
}
