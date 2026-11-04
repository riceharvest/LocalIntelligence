package dev.localintelligence.core.settings

/**
 * The address of the user's own desktop bridge ("My desktop"). Set in the app, never by an agent.
 *
 * WHY a row and not a constant: each user runs the bridge on their own machine, under their own tailnet
 * name. The value is only read by the My desktop screen (see `DesktopRules.normalizeServeUrl`), so an
 * empty value means the screen asks for an address and makes no network call.
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
    )
}
