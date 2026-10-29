package dev.localintelligence.app.settings

import android.content.Context
import android.content.SharedPreferences
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.SettingsReader
import dev.localintelligence.core.settings.mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place settings are stored. Every [SettingDef] in the app reads and
 * writes through here, so there is one file, one change signal and one rule for
 * Simple mode.
 *
 * Simple mode: a row that is not [SettingDef.simple] reads as the value
 * [simpleValues] picks for this phone, or its default. The stored Technical
 * value is kept, not erased, and comes back when the user switches back.
 */
class SettingsStore(
    context: Context,
    private val simpleValues: () -> Map<String, Any> = { emptyMap() },
) : SettingsReader {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0)

    /** Bumps on every write. Screens collect it to redraw. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    override fun <T : Any> get(def: SettingDef<T>): T {
        if (def !== CoreSettings.MODE && !def.simple && mode == SettingsMode.SIMPLE) {
            return def.coerce(simpleValues()[def.key])
        }
        return stored(def)
    }

    /** The value saved for [def], ignoring Simple mode. What the Technical screen edits. */
    fun <T : Any> stored(def: SettingDef<T>): T = def.coerce(prefs.all[def.key])

    fun <T : Any> set(def: SettingDef<T>, value: T) {
        val coerced = def.coerce(value)
        prefs.edit().apply {
            when (coerced) {
                is Boolean -> putBoolean(def.key, coerced)
                is Int -> putInt(def.key, coerced)
                is Float -> putFloat(def.key, coerced)
                is String -> putString(def.key, coerced)
                else -> return
            }
        }.apply()
        _changes.value += 1
    }

    fun reset(def: SettingDef<*>) {
        prefs.edit().remove(def.key).apply()
        _changes.value += 1
    }

    fun isPluginEnabled(plugin: AgentPlugin): Boolean =
        prefs.getBoolean(pluginKey(plugin), plugin.enabledByDefault)

    fun setPluginEnabled(plugin: AgentPlugin, enabled: Boolean) {
        prefs.edit().putBoolean(pluginKey(plugin), enabled).apply()
        _changes.value += 1
    }

    /**
     * A copy for one run: each value is read once, on first use, and then held,
     * so a toggle while a run is going cannot change it halfway.
     */
    fun snapshot(): SettingsReader {
        val values = HashMap<String, Any>()
        return object : SettingsReader {
            override fun <T : Any> get(def: SettingDef<T>): T {
                @Suppress("UNCHECKED_CAST")
                return values.getOrPut(def.key) { this@SettingsStore[def] } as T
            }
        }
    }

    private fun pluginKey(plugin: AgentPlugin) = "plugin.${plugin.id}.enabled"

    private companion object {
        const val FILE = "settings"
    }
}
