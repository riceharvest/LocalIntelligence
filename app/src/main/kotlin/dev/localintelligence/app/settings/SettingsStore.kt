package dev.localintelligence.app.settings

import android.content.Context
import android.content.SharedPreferences
import dev.localintelligence.android.hub.KeystoreCipher
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
 *
 * [SettingDef.Secret] values are kept encrypted under the same Keystore approach
 * as the HF token ([KeystoreCipher]), in this same file as `iv:ciphertext`. A
 * read decrypts; the settings screen never asks for the plain value.
 */
class SettingsStore(
    context: Context,
    private val simpleValues: () -> Map<String, Any> = { emptyMap() },
) : SettingsReader {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val cipher = KeystoreCipher(SECRET_KEY_ALIAS)

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
    fun <T : Any> stored(def: SettingDef<T>): T {
        if (def is SettingDef.Secret) {
            @Suppress("UNCHECKED_CAST")
            return def.coerce(readSecret(def)) as T
        }
        return def.coerce(prefs.all[def.key])
    }

    /**
     * True when a [SettingDef.Secret] holds a value that can still be decrypted. A
     * value the Keystore can no longer open (after a lock-screen change) reads as
     * not set, so the screen does not claim a key that the tools would never use.
     */
    fun isSecretSet(def: SettingDef.Secret): Boolean = readSecret(def)?.isNotBlank() == true

    /**
     * Encrypts and saves a secret. Blank clears it. Returns false, and stores
     * nothing, when the Keystore refuses: a secret is never written in plain text.
     */
    fun setSecret(def: SettingDef.Secret, value: String): Boolean {
        if (value.isBlank()) return clearSecret(def)
        val (ciphertext, iv) = cipher.encryptParts(value.trim()) ?: return false
        prefs.edit().putString(def.key, iv + SEPARATOR + ciphertext).apply()
        _changes.value += 1
        return true
    }

    fun clearSecret(def: SettingDef.Secret): Boolean {
        prefs.edit().remove(def.key).apply()
        _changes.value += 1
        return true
    }

    private fun readSecret(def: SettingDef.Secret): String? {
        val sealed = prefs.getString(def.key, null) ?: return null
        val parts = sealed.split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null
        return cipher.decryptParts(ciphertext = parts[1], iv = parts[0])
    }

    fun <T : Any> set(def: SettingDef<T>, value: T) {
        if (def is SettingDef.Secret) {
            // Callers must use setSecret; a plain write here would store the key unencrypted.
            return
        }
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

    /** Clears a plugin's on/off value, so a plugin installed later under the same id starts from its default. */
    fun forgetPlugin(plugin: AgentPlugin) {
        prefs.edit().remove(pluginKey(plugin)).apply()
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
        const val SECRET_KEY_ALIAS = "localintelligence_settings_secrets"

        /** Base64 has no colon, so it separates the IV from the ciphertext safely. */
        const val SEPARATOR = ":"
    }
}
