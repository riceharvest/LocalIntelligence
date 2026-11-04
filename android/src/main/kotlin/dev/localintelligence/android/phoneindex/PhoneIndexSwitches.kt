package dev.localintelligence.android.phoneindex

import dev.localintelligence.core.phoneindex.PhoneIndexSource
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingsReader

/**
 * The sources the person has switched on. A source is on when its switch is on; Files also needs a
 * folder chosen. Read through [SettingsReader], so the tool, the charger job and the screen agree.
 */
fun phoneIndexEnabled(settings: SettingsReader): Set<PhoneIndexSource> =
    PhoneIndexSource.entries.filterTo(mutableSetOf()) { source ->
        when (source) {
            PhoneIndexSource.NOTIFICATIONS -> settings[CoreSettings.PHONE_INDEX_NOTIFICATIONS]
            PhoneIndexSource.MESSAGES -> settings[CoreSettings.PHONE_INDEX_MESSAGES]
            PhoneIndexSource.CALENDAR -> settings[CoreSettings.PHONE_INDEX_CALENDAR]
            PhoneIndexSource.CONTACTS -> settings[CoreSettings.PHONE_INDEX_CONTACTS]
            PhoneIndexSource.FILES ->
                settings[CoreSettings.PHONE_INDEX_FILES] && settings[CoreSettings.PHONE_INDEX_FILES_FOLDER].isNotBlank()
            PhoneIndexSource.PHOTOS -> settings[CoreSettings.PHONE_INDEX_PHOTOS]
        }
    }
