package dev.localintelligence.core.tool.contracts

/**
 * What a tool actually needs from the platform, and the one sentence that tells
 * the model the truth when it does not have it.
 *
 * ## Why this exists
 *
 * Nine tool families each used to hand-write their own permission story, and
 * every one of them got it wrong in the same way. The denial sentences were
 * "No permission to read the battery. Tell the user it is unavailable in this
 * session; do not retry." That sentence is a lie twice over:
 *
 *  1. There is no "session". Every permission these tools name is a device
 *     setting the user can change in Settings at any moment, so "unavailable in
 *     this session" tells the model to give up on something the user can fix in
 *     four taps.
 *  2. "Do not retry" is advice about a retry loop, not about the user's
 *     situation. A model handed it stops asking, and the user is never told
 *     there is a switch they could flip.
 *
 * The second failure is the damaging one. A user who says "what's Dario's
 * number?" and is told "contacts are unavailable in this session" concludes the
 * app cannot do it, and never learns that one Settings toggle changes the
 * answer.
 *
 * ## The rule this file encodes
 *
 * A permission denial must name the exact permission, say in one clause that
 * nothing was read, and tell the user the specific place to change it. That is
 * a format, not a wording preference, so it lives in one place and every family
 * renders it the same way.
 *
 * ## Why it is pure Kotlin with no android.* import
 *
 * The sentence is the part a model has to reason about, and the model is the
 * part that must never be wrong. Keeping it android-free means the whole
 * rendering path is a plain function of data — and it means a family cannot
 * accidentally reach for a platform type while composing the message.
 */
data class PlatformRequirement(
    /**
     * The canonical Android permission name, or null when the capability needs
     * no permission at all.
     *
     * Canonical means the bare name — `android.permission.READ_CONTACTS` — and
     * never a sentence. A `requiredPermission` field holding
     * `"READ_EXTERNAL_STORAGE (API<=32; API 33+ needs a SAF grant)"` cannot
     * ever be matched against a set of granted permission names, so a host
     * that tried to grant it would silently fail. Where the real dependency is
     * a Settings toggle rather than a permission, this is null and [capability]
     * carries the name.
     */
    val permission: String?,
    /**
     * The human name of the capability, for the denial sentence: "Contacts",
     * "the calendar", "notification access".
     */
    val capability: String,
    /**
     * Exactly where the user changes it, in the words a person would use for
     * that screen. This is the part that turns a refusal into an action.
     */
    val grantInstructions: String,
) {
    /**
     * The one-sentence statement of what did not happen.
     *
     * "Nothing was read" is not decoration. A model that knows the read was
     * declined will say so, instead of reporting an empty result as a fact
     * about the user's phone.
     */
    val nothingHappened: String
        get() = when (permission) {
            null -> "Nothing was read or changed."
            else -> "Nothing was read or changed."
        }
}

/**
 * Whether the host can actually perform a [PlatformRequirement] right now.
 *
 * Deliberately a question about ONE requirement rather than a single global
 * boolean. The runtime's `ToolContext.permissionGranted` is global: it answers
 * "may this call proceed at all", not "can this tool see contacts". Collapsing
 * a per-capability fact into a global flag is what produced the original bug,
 * because a tool with no way to ask the specific question falls back on
 * "assume granted" and then returns empty.
 */
fun interface PlatformGrant {
    /** True when the host can perform [requirement] at this moment. */
    fun isGranted(requirement: PlatformRequirement): Boolean

    companion object {
        /** For a tool that is exercised off-device. Grants everything. */
        val GRANT_ALL: PlatformGrant = PlatformGrant { true }

        /** For a tool exercised in a denied state. Denies everything. */
        val DENY_ALL: PlatformGrant = PlatformGrant { false }
    }
}

/**
 * Renders a denial in the one shape every family uses.
 *
 * Shared so that nine tool families cannot drift into nine different excuses.
 * The order is fixed and deliberate: name the capability, say what did not
 * happen, say where to change it, and only then say what to do next. A denial
 * that leads with "do not retry" reads to a model as a dead end; a denial that
 * leads with the capability reads as a thing the user can go and switch on.
 */
object PermissionDenial {

    /**
     * @param tool the tool name, so a model that retries can tell which call
     *   was refused.
     * @param requirement what the tool needed.
     * @param retryAfterGrant true when granting the permission would make the
     *   very same call succeed. False for a capability the user must set up
     *   some other way.
     */
    fun observation(
        tool: String,
        requirement: PlatformRequirement,
        retryAfterGrant: Boolean = true,
    ): String {
        val headline = if (requirement.permission == null) {
            "${requirement.capability} is not available to this app."
        } else {
            "${requirement.capability} has not been granted to this app."
        }
        val next = if (retryAfterGrant) {
            "Tell the user this, and that granting it makes this call work. " +
                "Do not retry until they have."
        } else {
            "Tell the user this. Do not retry unchanged."
        }
        return "$headline ${requirement.nothingHappened} " +
            "To grant it: ${requirement.grantInstructions} $next"
    }

    /**
     * The short form for `ToolError.message`.
     *
     * Names the permission so a log line or a confirmation dialog says which
     * one is missing, without repeating the whole sentence.
     */
    fun summary(requirement: PlatformRequirement): String =
        requirement.permission ?: "${requirement.capability} is not enabled for this app"
}

/**
 * The requirements the shipped Android tools actually have, named once.
 *
 * A tool refers to its dependency by constant rather than by typing a string.
 * That is what stops the class of bug where a family invents its own prose
 * permission name and no host can ever match it.
 */
object ToolPermissions {
    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val READ_CALENDAR = "android.permission.READ_CALENDAR"
    const val WRITE_CALENDAR = "android.permission.WRITE_CALENDAR"
    const val VIBRATE = "android.permission.VIBRATE"
    const val SCHEDULE_EXACT_ALARM = "android.permission.SCHEDULE_EXACT_ALARM"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
    const val INTERNET = "android.permission.INTERNET"

    /** Android 13+ replaced the single storage permission with per-type media ones. */
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"

    val CONTACTS = PlatformRequirement(
        permission = READ_CONTACTS,
        capability = "Contacts access",
        grantInstructions = "Settings > Apps > LocalIntelligence > Permissions > Contacts.",
    )

    val CALENDAR_READ = PlatformRequirement(
        permission = READ_CALENDAR,
        capability = "Calendar access",
        grantInstructions = "Settings > Apps > LocalIntelligence > Permissions > Calendar.",
    )

    val CALENDAR_WRITE = PlatformRequirement(
        permission = WRITE_CALENDAR,
        capability = "Permission to change the calendar",
        grantInstructions = "Settings > Apps > LocalIntelligence > Permissions > Calendar.",
    )

    /**
     * Notification access is NOT a runtime permission and there is no
     * `requestPermissions` call that can obtain it. The only route is a human
     * flipping a switch, so [permission] is null and the instructions name the
     * screen. Naming a permission here would be a fiction the model would
     * repeat to the user.
     */
    val NOTIFICATION_LISTENER = PlatformRequirement(
        permission = null,
        capability = "Notification access",
        grantInstructions = "Android Settings > Notifications > Device & app notifications > " +
            "LocalIntelligence, then turn on notification access.",
    )

    /**
     * Reading another app's documents is a media permission from Android 13 and
     * a storage permission before it, and on Android 13+ a plain document in
     * Documents/Downloads is not reachable by any of them — only files the user
     * hands over through the system file picker are. The instructions say so,
     * because "grant the storage permission" on a modern phone is advice that
     * leads nowhere.
     */
    val DOCUMENT_READ = PlatformRequirement(
        permission = null,
        capability = "Access to your documents",
        grantInstructions = "On Android 13 and newer, open a document with the " +
            "system file picker and choose LocalIntelligence to share it with this app; " +
            "on older phones, Settings > Apps > LocalIntelligence > Permissions > " +
            "Storage.",
    )

    val DOCUMENT_WRITE = PlatformRequirement(
        permission = null,
        capability = "Permission to write documents",
        grantInstructions = "On Android 13 and newer this app can only create files in " +
            "Downloads and overwrite documents you have shared with it; on older phones, " +
            "Settings > Apps > LocalIntelligence > Permissions > Storage.",
    )

    val EXACT_ALARM = PlatformRequirement(
        permission = SCHEDULE_EXACT_ALARM,
        capability = "Permission to schedule exact alarms",
        grantInstructions = "Settings > Apps > Special app access > Alarms & reminders > " +
            "LocalIntelligence, and turn it on.",
    )

    /**
     * VIBRATE is a normal (install-time) permission: it is granted by being in
     * the manifest and cannot be revoked at runtime, so the user has no switch
     * to find. The instructions say so rather than sending them to a Settings
     * page that will not help.
     */
    val VIBRATE_REQUIREMENT = PlatformRequirement(
        permission = VIBRATE,
        capability = "Permission to vibrate the phone",
        grantInstructions = "Vibration is a setting this app cannot be denied; if this says " +
            "it is missing the app was installed without it, which is a bug rather than " +
            "something to change in Settings.",
    )

    /**
     * INTERNET is an install-time (normal) permission: it is granted by being
     * declared in the manifest and there is no runtime prompt and no Settings
     * toggle for it. The instructions say that plainly, because "you need to
     * allow internet access" is advice that sends a user hunting for a switch
     * that does not exist.
     */
    val INTERNET_REQUIREMENT = PlatformRequirement(
        permission = INTERNET,
        capability = "Network access",
        grantInstructions = "This app is either installed without network access or the " +
            "device is offline; there is no switch to flip in Settings for it.",
    )

    /**
     * The clipboard has no runtime permission to read or write. What it has is
     * a *focus* rule: from Android 10 a background app gets null back instead
     * of the clip, with no exception and no way to tell that apart from an
     * empty clipboard. Naming that is the whole honesty problem here, and it is
     * not a permission the user grants — it is a state they create by switching
     * to the app.
     */
    val CLIPBOARD_BACKGROUND_READ = PlatformRequirement(
        permission = null,
        capability = "Clipboard access while the app is in the background",
        grantInstructions = "Open LocalIntelligence and leave it on screen, then try again — " +
            "Android only lets a background app read the clipboard while it has focus.",
    )
}
