package dev.localintelligence.android.di

import android.content.Context
import dev.localintelligence.android.tools.alarm.AndroidAlarmPlatform
import dev.localintelligence.android.tools.alarm.AlarmCancelTool
import dev.localintelligence.android.tools.alarm.AlarmCreateTool
import dev.localintelligence.android.tools.alarm.AlarmListTool
import dev.localintelligence.android.tools.apps.AppsListTool
import dev.localintelligence.android.tools.apps.AppsOpenTool
import dev.localintelligence.android.tools.apps.AppsShareTool
import dev.localintelligence.android.tools.clipboard.AndroidClipboardPlatform
import dev.localintelligence.android.tools.clipboard.ClipboardReadTool
import dev.localintelligence.android.tools.clipboard.ClipboardWriteTool
import dev.localintelligence.android.tools.device.AndroidDevicePlatform
import dev.localintelligence.android.tools.device.DeviceBatteryTool
import dev.localintelligence.android.tools.device.DeviceInfoTool
import dev.localintelligence.android.tools.device.DeviceOpenSettingsTool
import dev.localintelligence.android.tools.device.DeviceVibrateTool
import dev.localintelligence.android.tools.files.FilesDeleteTool
import dev.localintelligence.android.tools.files.FilesListTool
import dev.localintelligence.android.tools.files.FilesReadTextTool
import dev.localintelligence.android.tools.files.FilesSearchTool
import dev.localintelligence.android.tools.files.FilesWriteTextTool
import dev.localintelligence.android.tools.notifications.NotificationDismissTool
import dev.localintelligence.android.tools.notifications.NotificationListTool
import dev.localintelligence.android.tools.notifications.NotificationReplyTool
import dev.localintelligence.android.tools.web.WebFetchTool
import dev.localintelligence.android.tools.alarm.AlarmPlatform
import dev.localintelligence.android.tools.clipboard.ClipboardPlatform
import dev.localintelligence.android.tools.device.DevicePlatform
import dev.localintelligence.core.tool.AgentTool

/**
 * The Android services the tool families need, resolved once per graph.
 *
 * WHY this is an interface rather than just a `Context`: it is what makes
 * [ANDROID_TOOL_REGISTRATIONS] a plain `val` that can be read on a JVM. The
 * registration list is the file most likely to develop a duplicate — a tool
 * family lands, someone adds their line twice — and a check that needs a device
 * to run is a check that does not run.
 */
interface ToolPlatforms {
    val device: DevicePlatform
    val clipboard: ClipboardPlatform
    val alarm: AlarmPlatform
    fun context(): Context
}

/** The real thing. One platform object per family, shared by that family's tools. */
internal class AndroidPlatforms(private val appContext: Context) : ToolPlatforms {
    override val device: DevicePlatform by lazy { AndroidDevicePlatform(appContext) }
    override val clipboard: ClipboardPlatform by lazy { AndroidClipboardPlatform(appContext) }
    override val alarm: AlarmPlatform by lazy { AndroidAlarmPlatform(appContext) }
    override fun context(): Context = appContext
}

/**
 * One registration: the tool name this app promises the model, and how to build
 * it.
 *
 * The name is declared rather than read off the built tool because the tools
 * that matter cannot be constructed on a JVM. Declaring it beside the factory
 * moves duplicate detection to the JVM, where it runs on every build.
 *
 * The duplication is caught, not merely tolerated: [AndroidToolSource] verifies
 * each built tool against its declared name on device, so a wave-2 rename that
 * does not reach this list fails loudly rather than registering a tool the
 * model can never name.
 */
data class ToolRegistration(
    val name: String,
    val build: (ToolPlatforms) -> AgentTool,
)

/**
 * Every tool this app exposes, named explicitly.
 *
 * ## Why an explicit list and not the `*Tools(context)` factory functions
 *
 * Every wave-2 file ships a `fooTools(context): List<AgentTool>` convenience
 * factory, and this list deliberately does *not* call them:
 *
 *  1. **Greppability.** "Which tools does this app actually expose?" must be
 *     answerable with one `grep` and a readable diff. `deviceTools(ctx)` hides
 *     four tool classes behind a call whose body has to be opened to count, and
 *     a tool added inside a factory body would reach the app with no change here
 *     at all — a diff nobody reviews.
 *  2. **One platform per family.** The factories construct a *new*
 *     `AndroidDevicePlatform(context)` per tool, so `deviceTools` builds three
 *     platform objects for four tools. [ToolPlatforms] builds one each.
 *
 * No reflection, no classpath scanning. The registration surface is exactly this
 * list, and adding a tool is a deliberate edit to it.
 *
 * Grouped by family, alphabetical within the group. Order is not behaviour — the
 * loop calls `ToolSelector.select` every step, so what the model sees is decided
 * by relevance scoring, not list position — but a stable order makes a duplicate
 * report legible.
 */
internal val ANDROID_TOOL_REGISTRATIONS: List<ToolRegistration> = listOf(
    // alarm
    reg("alarm.create") { AlarmCreateTool(it.alarm) },
    reg("alarm.list") { AlarmListTool(it.alarm) },
    reg("alarm.cancel") { AlarmCancelTool(it.alarm) },
    // apps
    reg("apps.list") { AppsListTool(it.context()) },
    reg("apps.open") { AppsOpenTool(it.context()) },
    reg("apps.share") { AppsShareTool(it.context()) },
    // clipboard
    reg("clipboard.read") { ClipboardReadTool(it.clipboard) },
    reg("clipboard.write") { ClipboardWriteTool(it.clipboard) },
    // device
    reg("device.battery") { DeviceBatteryTool(it.device) },
    reg("device.info") { DeviceInfoTool(it.device) },
    reg("device.vibrate") { DeviceVibrateTool(it.device) },
    reg("device.open_settings") { DeviceOpenSettingsTool(it.device) },
    // files
    reg("files.list") { FilesListTool(it.context()) },
    reg("files.search") { FilesSearchTool(it.context()) },
    reg("files.read_text") { FilesReadTextTool(it.context()) },
    reg("files.write_text") { FilesWriteTextTool(it.context()) },
    reg("files.delete") { FilesDeleteTool(it.context()) },
    // notifications
    reg("notifications.list") { NotificationListTool() },
    reg("notifications.reply") { NotificationReplyTool() },
    reg("notifications.dismiss") { NotificationDismissTool() },
    // web
    reg("web.fetch") { WebFetchTool() },
)

private fun reg(name: String, build: (ToolPlatforms) -> AgentTool): ToolRegistration =
    ToolRegistration(name, build)

/**
 * Builds the real tools from the real Android platforms.
 *
 * Constructed per graph and cheap: [AndroidPlatforms] defers each platform
 * object, and the lambdas capture nothing but the receiver.
 */
class AndroidToolSource(
    private val platforms: ToolPlatforms,
) : ToolSource {

    constructor(context: Context) : this(AndroidPlatforms(context.applicationContext))

    override fun tools(): List<AgentTool> = ANDROID_TOOL_REGISTRATIONS.map { registration ->
        val tool = registration.build(platforms)
        // The declared name is the contract the model is prompted with, so a
        // mismatch is a silent dead tool, not a cosmetic drift. Caught here
        // because this is the first point where both names are in hand.
        check(tool.definition.name == registration.name) {
            "registered as '${registration.name}' but built " +
                "'${tool.definition.name}'; update ANDROID_TOOL_REGISTRATIONS"
        }
        tool
    }
}
