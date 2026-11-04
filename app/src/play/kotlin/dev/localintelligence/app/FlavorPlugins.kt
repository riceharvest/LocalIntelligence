package dev.localintelligence.app

import android.content.Context
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingsReader

/**
 * Flavor-only plugins for the `play` build (`docs/adr/0002-play-and-full-builds.md`).
 *
 * WHY THIS SEAM LIVES IN `:app` RATHER THAN `:android`: only `:app` has the `play`
 * and `full` product flavors; `:android` stays flavorless and cannot see
 * `app/src/full`. Flavor-only tools are therefore composed here and joined into
 * [AppContainer]'s built-in plugin list and catalogue agreement check.
 */
object FlavorPlugins {
    fun plugins(context: Context, settings: SettingsReader): List<AgentPlugin> = emptyList()
}
