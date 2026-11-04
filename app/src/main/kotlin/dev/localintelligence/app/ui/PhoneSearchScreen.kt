package dev.localintelligence.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.phoneindex.PhoneIndexStore
import dev.localintelligence.android.phoneindex.phoneIndexEnabled
import dev.localintelligence.app.R
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.phoneindex.PhoneIndexSource
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingDef
import kotlinx.coroutines.launch

/**
 * Search my phone: choose what on this phone is indexed, see how much is stored, delete any source, and
 * refresh now. Shown in both Simple and Technical modes.
 *
 * WHY EVERY SOURCE IS A SWITCH, OFF BY DEFAULT: the index holds private things (texts, names, notification
 * text). Nothing is read until the person turns a source on, and turning one off stops reading it; the
 * stored items stay until Delete, so the person decides when they go.
 *
 * WHY THE PHOTO ROW IS FULL BUILD ONLY: the captions are written by the vision model in Photo search,
 * which the Play build does not ship.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSearchScreen(
    settings: SettingsStore,
    index: PhoneIndexStore,
    fullBuild: Boolean,
    /** Simple mode reads plainer sentences here. Technical keeps the wording it always had. */
    simple: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bumped after each change, so the switches and counts are read again from settings and the index.
    var version by remember { mutableIntStateOf(0) }
    var counts by remember { mutableStateOf<Map<PhoneIndexSource, Int>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Keyed on the version, so the counts are read again after each change.
    LaunchedEffect(version) { counts = index.counts() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // The grant is kept across restarts so the charger refresh can read the folder later.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            settings.set(CoreSettings.PHONE_INDEX_FILES_FOLDER, uri.toString())
            version++
        }
    }

    val folder = settings.stored(CoreSettings.PHONE_INDEX_FILES_FOLDER)
    val sources = PhoneIndexSource.entries.filter { fullBuild || it != PhoneIndexSource.PHOTOS }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.phone_search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Simple mode reads plainer sentences; Technical keeps the wording it always had.
            Text(stringResource(if (simple) R.string.plain_search_intro else R.string.phone_search_intro))
            Text(stringResource(if (simple) R.string.plain_search_privacy else R.string.phone_search_privacy))
            Text(stringResource(if (simple) R.string.plain_search_charging else R.string.phone_search_charging_note))

            for (source in sources) {
                val def = toggleFor(source)
                val on = settings.stored(def)
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(titleOf(source))
                        Text(helpOf(source))
                        Text(stringResource(R.string.phone_search_stored, counts?.get(source) ?: 0))
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { checked ->
                            settings.set(def, checked)
                            version++
                        },
                    )
                }
                if ((counts?.get(source) ?: 0) > 0) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                index.delete(source)
                                version++
                            }
                        },
                    ) { Text(stringResource(R.string.phone_search_delete)) }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.phone_search_files_help))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (folder.isBlank()) stringResource(R.string.phone_search_no_folder)
                        else stringResource(R.string.phone_search_folder_chosen),
                    )
                }
                OutlinedButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.phone_search_choose_folder))
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val folderUri = settings.stored(CoreSettings.PHONE_INDEX_FILES_FOLDER)
                                .takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                            val ok = runCatching {
                                index.refresh(phoneIndexEnabled(settings), folderUri, messagesAllowed = true)
                            }.isSuccess
                            busy = false
                            message = context.getString(
                                if (ok) R.string.phone_search_index_done else R.string.phone_search_index_failed,
                            )
                            version++
                        }
                    },
                ) { Text(stringResource(R.string.phone_search_index_now)) }
            }
            message?.let { Text(it) }
        }
    }
}

/** The switch that turns [source] on or off. */
private fun toggleFor(source: PhoneIndexSource): SettingDef.Toggle = when (source) {
    PhoneIndexSource.NOTIFICATIONS -> CoreSettings.PHONE_INDEX_NOTIFICATIONS
    PhoneIndexSource.MESSAGES -> CoreSettings.PHONE_INDEX_MESSAGES
    PhoneIndexSource.CALENDAR -> CoreSettings.PHONE_INDEX_CALENDAR
    PhoneIndexSource.CONTACTS -> CoreSettings.PHONE_INDEX_CONTACTS
    PhoneIndexSource.FILES -> CoreSettings.PHONE_INDEX_FILES
    PhoneIndexSource.PHOTOS -> CoreSettings.PHONE_INDEX_PHOTOS
}

@Composable
private fun titleOf(source: PhoneIndexSource): String = stringResource(
    when (source) {
        PhoneIndexSource.NOTIFICATIONS -> R.string.phone_search_notifications
        PhoneIndexSource.MESSAGES -> R.string.phone_search_messages
        PhoneIndexSource.CALENDAR -> R.string.phone_search_calendar
        PhoneIndexSource.CONTACTS -> R.string.phone_search_contacts
        PhoneIndexSource.FILES -> R.string.phone_search_files
        PhoneIndexSource.PHOTOS -> R.string.phone_search_photos
    },
)

@Composable
private fun helpOf(source: PhoneIndexSource): String = stringResource(
    when (source) {
        PhoneIndexSource.NOTIFICATIONS -> R.string.phone_search_notifications_help
        PhoneIndexSource.MESSAGES -> R.string.phone_search_messages_help
        PhoneIndexSource.CALENDAR -> R.string.phone_search_calendar_help
        PhoneIndexSource.CONTACTS -> R.string.phone_search_contacts_help
        PhoneIndexSource.FILES -> R.string.phone_search_files_help
        PhoneIndexSource.PHOTOS -> R.string.phone_search_photos_help
    },
)
