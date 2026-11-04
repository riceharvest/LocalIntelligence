package dev.localintelligence.core.settings

/**
 * The one user-editable screen control option: apps the assistant must never act in.
 *
 * WHY a row and not a constant: a user may have a banking or password-manager app they
 * never want touched, and the approval model does not block those automatically
 * (docs/threat-model.md T10). The list is comma separated package names, read through the
 * frozen settings snapshot for each run, so a change applies at the next message.
 */
object ScreenControlSettings {
    const val BLOCKED_APPS_KEY = "screen.control.blocked"

    val BLOCKED_APPS: SettingDef.Text = SettingDef.Text(
        BLOCKED_APPS_KEY,
        "Apps screen control never uses",
        "Package names, separated by commas, such as com.example.bank. The assistant refuses to read " +
            "or act in these apps, even when you approve them. Its own screens, Settings and the " +
            "permission dialogs are always refused.",
        SettingSection.PLUGINS,
        default = "",
        maxLength = 500,
    )

    /**
     * The person's answer to the disclosure screen (docs/adr/0003). Not listed in the plugin's
     * settings: the only way to say yes is the disclosure itself, so a toggle here would let
     * someone skip what it says. Written by the setup card; read by the plugin before it offers
     * any screen tool.
     */
    const val CONSENT_KEY = "screen.control.consent"

    val CONSENT: SettingDef.Toggle = SettingDef.Toggle(
        CONSENT_KEY,
        "Screen control consent",
        "Whether the person has read the screen control disclosure and agreed to open Accessibility settings.",
        SettingSection.PLUGINS,
        default = false,
        // WHY simple: Simple mode reads any other row as its default, so a "yes" would never stick there.
        simple = true,
    )

    /** The user's list, parsed: trimmed, lowercased, blanks dropped. */
    fun parse(raw: String): Set<String> =
        raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
