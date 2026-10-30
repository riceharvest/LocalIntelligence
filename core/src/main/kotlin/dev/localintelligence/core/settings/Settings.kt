package dev.localintelligence.core.settings

/**
 * One user-facing setting: its key, type, default and where it shows.
 *
 * Every setting in the app is declared as one of these, and the settings
 * screen is generated from the declarations. That is what makes "Simple" and
 * "Technical" a filter over one list rather than two screens, and what lets a
 * feature add a toggle without touching the screen: it declares a row.
 *
 * [simple] rows are shown in Simple mode. Every other row is hidden there and
 * reads as its Simple value (see [SettingsReader]), so a value someone set in
 * Technical mode cannot leak into Simple mode.
 */
sealed class SettingDef<T : Any>(
    val key: String,
    val title: String,
    val help: String,
    val section: SettingSection,
    val default: T,
    val simple: Boolean,
    val applies: SettingApplies,
) {
    /** The stored value if it is valid for this setting, otherwise [default]. */
    abstract fun coerce(raw: Any?): T

    class Toggle(
        key: String, title: String, help: String, section: SettingSection,
        default: Boolean, simple: Boolean = false, applies: SettingApplies = SettingApplies.NEXT_RUN,
    ) : SettingDef<Boolean>(key, title, help, section, default, simple, applies) {
        override fun coerce(raw: Any?): Boolean = raw as? Boolean ?: default
    }

    class IntValue(
        key: String, title: String, help: String, section: SettingSection,
        default: Int, val min: Int, val max: Int, val step: Int = 1,
        simple: Boolean = false, applies: SettingApplies = SettingApplies.NEXT_RUN,
    ) : SettingDef<Int>(key, title, help, section, default, simple, applies) {
        override fun coerce(raw: Any?): Int =
            (raw as? Number)?.toInt()?.takeIf { it in min..max } ?: default
    }

    class FloatValue(
        key: String, title: String, help: String, section: SettingSection,
        default: Float, val min: Float, val max: Float, val step: Float,
        simple: Boolean = false, applies: SettingApplies = SettingApplies.NEXT_RUN,
    ) : SettingDef<Float>(key, title, help, section, default, simple, applies) {
        override fun coerce(raw: Any?): Float =
            (raw as? Number)?.toFloat()?.takeIf { it.isFinite() && it in min..max } ?: default
    }

    class Choice(
        key: String, title: String, help: String, section: SettingSection,
        default: String, val options: List<Option>,
        simple: Boolean = false, applies: SettingApplies = SettingApplies.NEXT_RUN,
    ) : SettingDef<String>(key, title, help, section, default, simple, applies) {
        data class Option(val id: String, val label: String)

        override fun coerce(raw: Any?): String =
            (raw as? String)?.takeIf { id -> options.any { it.id == id } } ?: default
    }

    /**
     * Free text, hard-capped at [maxLength] characters. The cap is enforced on read
     * too, so no path can hand the prompt more than the setting promises.
     * [blankMeansDefault]: an emptied field reads as [default] (a model id), rather
     * than as an empty value (a profile the user cleared).
     */
    class Text(
        key: String, title: String, help: String, section: SettingSection,
        default: String, val maxLength: Int = 200, val blankMeansDefault: Boolean = false,
        simple: Boolean = false, applies: SettingApplies = SettingApplies.NEXT_RUN,
    ) : SettingDef<String>(key, title, help, section, default, simple, applies) {
        override fun coerce(raw: Any?): String {
            val value = (raw as? String)?.take(maxLength) ?: return default
            return if (blankMeansDefault && value.isBlank()) default else value
        }
    }

    /**
     * A credential, such as an API key. It is stored encrypted and never shown
     * back: the settings screen reports only whether one is set, and offers a
     * field to replace or clear it. The stored form is opaque to [coerce]; the
     * store decrypts before coercing, so readers always get the plain text.
     *
     * The default is the empty string, meaning "not set".
     */
    class Secret(
        key: String, title: String, help: String, section: SettingSection,
        simple: Boolean = false, applies: SettingApplies = SettingApplies.NEXT_RUN,
    ) : SettingDef<String>(key, title, help, section, "", simple, applies) {
        override fun coerce(raw: Any?): String = (raw as? String)?.takeIf { it.isNotBlank() } ?: default
    }
}

/** Where a setting is listed. Order is display order. */
enum class SettingSection(val title: String) {
    GENERAL("General"),
    APPROVALS("Approvals"),
    INTERFACE("Interface"),
    VOICE("Voice"),
    MODEL("Model"),
    SAMPLING("Sampling"),
    AGENT("Agent loop"),
    PLUGINS("Plugins"),
}

/** When a change reaches the app, shown next to the control. */
enum class SettingApplies(val label: String) {
    NOW("now"),
    NEXT_RUN("from the next message"),
    NEXT_MODEL_LOAD("from the next model load"),
}

/** Read access to effective setting values. */
interface SettingsReader {
    operator fun <T : Any> get(def: SettingDef<T>): T

    companion object {
        /** Every setting at its default. For JVM callers and previews. */
        val DEFAULTS: SettingsReader = object : SettingsReader {
            override fun <T : Any> get(def: SettingDef<T>): T = def.default
        }
    }
}

enum class SettingsMode(val id: String) {
    SIMPLE("simple"),
    TECHNICAL("technical"),
}

val SettingsReader.mode: SettingsMode
    get() = if (this[CoreSettings.MODE] == SettingsMode.TECHNICAL.id) SettingsMode.TECHNICAL else SettingsMode.SIMPLE
