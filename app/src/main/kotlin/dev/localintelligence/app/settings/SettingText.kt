package dev.localintelligence.app.settings

import android.content.Context
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection

/**
 * The text the settings screen shows, in the phone's language.
 *
 * WHY here and not in :core: :core and :android have no resources, so the
 * declared English on each SettingDef stays the source of truth and this lookup
 * only swaps it for a translation when one exists. A setting or option without
 * a string falls back to that declared English, so a new row never shows blank.
 */
object SettingText {
    /** The label of [def], translated when a string exists for its key. */
    fun title(context: Context, def: SettingDef<*>): String =
        SettingTextMap.titles[def.key]?.let { context.getString(it) } ?: def.title

    /** The help text of [def], translated when a string exists for its key. */
    fun help(context: Context, def: SettingDef<*>): String =
        SettingTextMap.helps[def.key]?.let { context.getString(it) } ?: def.help

    /** The label of a choice option of [def]. */
    fun option(context: Context, def: SettingDef.Choice, option: SettingDef.Choice.Option): String =
        SettingTextMap.options["${def.key}/${option.id}"]?.let { context.getString(it) } ?: option.label

    /** The section heading, translated when a string exists for it. */
    fun section(context: Context, section: SettingSection): String =
        SettingTextMap.sections[section.name]?.let { context.getString(it) } ?: section.title
}
