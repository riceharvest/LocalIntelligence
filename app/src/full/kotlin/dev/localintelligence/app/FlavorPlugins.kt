package dev.localintelligence.app

import android.content.Context
import dev.localintelligence.app.screen.ScreenControlPlugin
import dev.localintelligence.app.screen.ScreenTools
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.tool.AgentTool

/**
 * Flavor-only plugins for the `full` build (`docs/adr/0002-play-and-full-builds.md`).
 *
 * WHY THIS SEAM LIVES IN `:app` RATHER THAN `:android`: only `:app` has the `play`
 * and `full` product flavors; `:android` stays flavorless and cannot see
 * `app/src/full`. Flavor-only tools (such as screen control through Accessibility)
 * are therefore composed here and joined into [AppContainer]'s built-in plugin list
 * and catalogue agreement check.
 */
object FlavorPlugins {
    fun plugins(context: Context, settings: SettingsReader): List<AgentPlugin> =
        listOf(ScreenControlPlugin(settings))

    /** Every tool this flavor ships, whether or not it is offered now, for the catalogue check. */
    fun shippedTools(): List<AgentTool> = ScreenTools.all
}
