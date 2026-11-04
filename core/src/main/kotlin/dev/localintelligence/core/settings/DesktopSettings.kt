package dev.localintelligence.core.settings

/**
 * The address of the user's own desktop bridge ("My desktop"). Set only by the My desktop screen, never by an
 * agent, and not listed in the Settings screen (the screen itself is the editor).
 *
 * WHY a row and `simple = true`: `SettingsStore.get` reads a row that is not simple as its Simple-mode default
 * whenever the mode is Simple, which is the default mode. Without `simple`, the saved address would read back
 * as empty on every reopen. The row is not in `CoreSettings.all`, so it never appears in the Settings list.
 *
 * An empty value means the screen asks for an address and makes no network call.
 */
object DesktopSettings {
    const val SERVE_URL_KEY = "desktop.serve_url"

    val SERVE_URL: SettingDef.Text = SettingDef.Text(
        SERVE_URL_KEY,
        "My desktop address",
        "The https address that tailscale serve prints for your desktop bridge. The phone talks only to this address.",
        SettingSection.PLUGINS,
        default = "",
        maxLength = 300,
        simple = true,
    )
}
