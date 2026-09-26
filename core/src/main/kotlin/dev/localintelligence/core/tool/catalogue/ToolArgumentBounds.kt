package dev.localintelligence.core.tool.catalogue

/**
 * The numeric bounds and defaults that appear in more than one place: once in
 * the tool's JSON Schema, where the model reads them, and once in the tool's
 * `execute()`, where they are enforced.
 *
 * ## Why these are here and not in the tools
 *
 * A bound that is written twice is a bound that will be wrong in one of the two
 * places. Before this file existed, `device.vibrate` published `minimum: 50` in
 * the catalogue while the tool clamped to 10, and `files.list` published
 * `maximum: 200` / "Default 25" while the tool's real limit was 100 with a
 * default of 20. Neither side noticed, because the model is only ever shown the
 * `:android` schema, so the wrong number was invisible by construction.
 *
 * Each constant here is the value the SHIPPED tool used before this change.
 * `:android` keeps its own constants as aliases of these (so `execute()` reads
 * the same number the schema advertises), which means a bound can no longer be
 * tightened in the code and left stale in the schema.
 *
 * ## These are claims, not enforcement
 *
 * Nothing here validates anything. `ToolCallValidator` checks that a call's
 * argument NAMES are legal, and each tool's `execute()` still has to clamp and
 * reject on its own — a schema is a description to the model, and a model will
 * misread one. The value of putting the number in one place is that the
 * description and the enforcement stop being able to disagree.
 */
object ToolArgumentBounds {

    // -------------------------------------------------------------- alarm.create
    const val ALARM_MIN_HOUR: Int = 0
    const val ALARM_MAX_HOUR: Int = 23
    const val ALARM_MIN_MINUTE: Int = 0
    const val ALARM_MAX_MINUTE: Int = 59

    // -------------------------------------------------------------- device.vibrate
    const val VIBRATE_MIN_DURATION_MS: Int = 10
    const val VIBRATE_MAX_DURATION_MS: Int = 5_000
    const val VIBRATE_DEFAULT_DURATION_MS: Int = 300

    // -------------------------------------------------------------- clipboard.write
    const val CLIPBOARD_MAX_WRITE_CHARS: Int = 100_000

    // ------------------------------------------------------------------- files.*
    const val FILES_DEFAULT_LIMIT: Int = 20
    const val FILES_MAX_LIMIT: Int = 100
    const val FILES_MAX_QUERY_CHARS: Int = 200
    const val FILES_MAX_MIME_CHARS: Int = 100
    const val FILES_MAX_WRITE_NAME_CHARS: Int = 120
    const val FILES_MAX_WRITE_CHARS: Int = 8192

    // -------------------------------------------------------------------- apps.*
    const val APPS_DEFAULT_LIMIT: Int = 30
    const val APPS_MAX_LIMIT: Int = 100
    const val APPS_MAX_QUERY_CHARS: Int = 120
    const val APPS_MAX_TEXT_CHARS: Int = 2000

    // ---------------------------------------------------------------- calendar.*
    const val CALENDAR_DEFAULT_LIMIT: Int = 20
    const val CALENDAR_MAX_LIMIT: Int = 50
    const val CALENDAR_MIN_DURATION_MINUTES: Int = 1
    const val CALENDAR_MAX_DURATION_MINUTES: Int = 24 * 60

    // ----------------------------------------------------------------- contacts.*
    const val CONTACTS_DEFAULT_LIMIT: Int = 10
    const val CONTACTS_MAX_LIMIT: Int = 25

    // ----------------------------------------------------------- notifications.*
    const val NOTIFICATIONS_DEFAULT_LIST_LIMIT: Int = 10

    // ------------------------------------------------------------------ web.fetch
    const val WEB_MIN_MAX_CHARS: Int = 100
    const val WEB_MAX_MAX_CHARS: Int = 8000
    const val WEB_DEFAULT_MAX_CHARS: Int = 2000
}

/**
 * The settings sub-screens the agent may open, as the ARGUMENT NAMES the model
 * sends.
 *
 * ## Why this moved to `:core`
 *
 * It was a pure Kotlin enum in `:android` with no platform dependency, and it
 * was the only thing the `device.open_settings` schema referenced that `:core`
 * could not see. `:android` could not import it in the other direction, so the
 * schema and the enum could not both be right while living on opposite sides of
 * the module boundary. The enum is the authority — `SettingsScreenResolver`
 * resolves model input against exactly these five names — so the enum moved to
 * the module that owns the schema, and `:android` imports it from here.
 *
 * The Intent action is deliberately NOT stored here: it is supplied by the
 * Android layer from the real `Settings.ACTION_*` constant, so this enum stays
 * pure and the one-per-screen mapping lives next to the platform call that uses
 * it.
 *
 * Note this is a FIVE-entry list where `V0ToolCatalogue` used to publish
 * SIXTEEN. The catalogue's `SETTINGS_SCREENS` was a third declaration of the
 * same idea and it was wrong: eleven of its sixteen names resolve to nothing,
 * so a model reading the catalogue would have been told it could open
 * `accessibility` or `date_time`, and the tool would have refused.
 */
enum class SettingsScreen(val argName: String, val displayName: String) {
    WIFI("wifi", "Wi-Fi"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    DISPLAY("display", "Display"),
    SOUND("sound", "Sound & vibration"),
    BATTERY_SAVER("battery_saver", "Battery saver"),
    ;

    companion object {
        val ARG_NAMES: List<String> get() = entries.map { it.argName }
    }
}
