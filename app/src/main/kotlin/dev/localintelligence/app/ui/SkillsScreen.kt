package dev.localintelligence.app.ui

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.skills.InstalledSkill
import dev.localintelligence.android.skills.SkillDownloader
import dev.localintelligence.android.skills.SkillRepository
import dev.localintelligence.android.skills.SkillSettings
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import dev.localintelligence.core.skill.SkillParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The template a new pasted skill starts from. Valid as it stands, so Save works once the user fills it in.
 * A function, not a val, because its text now comes from strings.xml and needs a Context.
 */
private fun newSkillTemplate(context: Context): String = SkillParser.render(
    name = "my-skill",
    description = context.getString(R.string.skill_template_description),
    body = context.getString(R.string.skill_template_body),
)

/**
 * Installed skills: switch each on or off, delete, or add one.
 *
 * Adding always goes through [SkillEditorDialog], so the user reads the text
 * before anything is written, whether it was pasted or downloaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    store: SettingsStore,
    repository: SkillRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Switch writes bump `changes`; add and delete bump `reload`. Either one re-reads the folder.
    val changes by store.changes.collectAsState()
    var reload by remember { mutableStateOf(0) }
    val installed: List<InstalledSkill> = remember(changes, reload) { repository.list() }

    // Saved, not remembered, so a rotation keeps the dialogs open and the typed text.
    // [fetching] stays remembered: a recreation cancels the fetch.
    var showChoice by rememberSaveable { mutableStateOf(false) }
    var showUrl by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("https://") }
    var urlError by rememberSaveable { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var editorText by rememberSaveable { mutableStateOf<String?>(null) }
    var showForm by rememberSaveable { mutableStateOf(false) }
    val technical = store.mode == SettingsMode.TECHNICAL
    var deleting by remember { mutableStateOf<InstalledSkill?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.skills)) },
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
            Text(
                stringResource(R.string.skills_teach_the_assistant_a_routine),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { showChoice = true }) { Text(stringResource(R.string.add_skill)) }

            if (installed.isEmpty()) {
                Text(stringResource(R.string.no_skills_yet), style = MaterialTheme.typography.bodyMedium)
            }
            installed.forEach { item ->
                val def = SkillSettings.enabled(item.slug)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.skill.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    item.skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = store.stored(def), onCheckedChange = { store.set(def, it) })
                        }
                        TextButton(onClick = { deleting = item }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }

    if (showChoice) {
        AlertDialog(
            onDismissRequest = { showChoice = false },
            title = { Text(stringResource(R.string.add_skill)) },
            text = { Text(stringResource(R.string.write_a_new_skill_or_import_one_from_a)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showChoice = false
                        if (technical) editorText = newSkillTemplate(context) else showForm = true
                    }) { Text(stringResource(R.string.write_it_here)) }
                    TextButton(onClick = {
                        showChoice = false
                        urlError = null
                        showUrl = true
                    }) { Text(stringResource(R.string.import_from_a_link)) }
                    TextButton(onClick = { showChoice = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }

    if (showUrl) {
        AlertDialog(
            onDismissRequest = { if (!fetching) showUrl = false },
            title = { Text(stringResource(R.string.import_from_a_link)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.paste_the_link_to_the_skill), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    urlError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !fetching,
                    onClick = {
                        fetching = true
                        urlError = null
                        val link = url.trim()
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { SkillDownloader.download(link) }
                            fetching = false
                            when (result) {
                                is SkillDownloader.Result.Ok -> {
                                    showUrl = false
                                    editorText = result.text
                                }
                                is SkillDownloader.Result.Failed ->
                                    urlError = context.getString(R.string.skill_url_failed, result.reason)
                            }
                        }
                    },
                ) { Text(if (fetching) stringResource(R.string.fetching) else stringResource(R.string.fetch)) }
            },
            dismissButton = {
                TextButton(enabled = !fetching, onClick = { showUrl = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showForm) {
        SkillFormDialog(
            onDismiss = { showForm = false },
            onConfirm = { text ->
                when (val saved = repository.save(text)) {
                    is SkillRepository.SaveResult.Saved -> {
                        showForm = false
                        reload += 1
                        null
                    }
                    is SkillRepository.SaveResult.Refused -> saved.reason
                }
            },
        )
    }

    editorText?.let { initial ->
        SkillEditorDialog(
            title = stringResource(R.string.review_skill),
            initialText = initial,
            onDismiss = { editorText = null },
            onConfirm = { text ->
                when (val saved = repository.save(text)) {
                    is SkillRepository.SaveResult.Saved -> {
                        editorText = null
                        reload += 1
                        null
                    }
                    is SkillRepository.SaveResult.Refused -> saved.reason
                }
            },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_2, item.skill.name)) },
            text = { Text(stringResource(R.string.the_skill_file_is_removed_its_on_off)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(item.slug)
                    store.reset(SkillSettings.enabled(item.slug))
                    deleting = null
                    reload += 1
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Simple-mode skill writing: three plain fields, turned into the skill text with
 * [SkillParser.render] so the saved file is the same one the raw editor would give.
 * [onConfirm] has the same contract as in [SkillEditorDialog].
 */
@Composable
private fun SkillFormDialog(onDismiss: () -> Unit, onConfirm: (String) -> String?) {
    var name by rememberSaveable { mutableStateOf("") }
    var whenToUse by rememberSaveable { mutableStateOf("") }
    var steps by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_skill)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = whenToUse,
                    onValueChange = { whenToUse = it; error = null },
                    label = { Text(stringResource(R.string.when_to_use_it)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = steps,
                    onValueChange = { steps = it; error = null },
                    label = { Text(stringResource(R.string.steps)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    name.isBlank() -> context.getString(R.string.skill_give_a_name)
                    whenToUse.isBlank() -> context.getString(R.string.skill_say_when_to_use)
                    steps.isBlank() -> context.getString(R.string.skill_write_the_steps)
                    else -> onConfirm(SkillParser.render(name.trim(), whenToUse.trim(), steps.trim()))
                }
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Shows a SKILL.md for reading and editing before it is saved. [onConfirm]
 * returns null when the text was accepted, which is the caller's cue to close
 * this dialog, or the reason it was refused, shown here so the user can fix it.
 *
 * Shared by the skills screen (paste, URL) and "Save as skill" in the chat, so
 * every route into the store passes the same review step.
 */
@Composable
fun SkillEditorDialog(
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> String?,
) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.read_it_before_saving_nothing_is_saved),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 10,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onConfirm(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
