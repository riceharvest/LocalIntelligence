package dev.localintelligence.app.ui

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
import dev.localintelligence.core.skill.SkillParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The template a new pasted skill starts from. Valid as it stands, so Save works once the user fills it in. */
private val NEW_SKILL_TEMPLATE = SkillParser.render(
    name = "my-skill",
    description = "Say when this skill applies, in a sentence.",
    body = "# My skill\n\n1. First step.\n2. Second step.",
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
    val scope = rememberCoroutineScope()
    // Switch writes bump `changes`; add and delete bump `reload`. Either one re-reads the folder.
    val changes by store.changes.collectAsState()
    var reload by remember { mutableStateOf(0) }
    val installed: List<InstalledSkill> = remember(changes, reload) { repository.list() }

    var showChoice by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var editorText by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<InstalledSkill?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Skills") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                "A skill is a SKILL.md file: a name, when to use it, and the steps. When a request " +
                    "matches a skill that is on, its steps are added for the assistant to follow.",
                style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(onClick = { showChoice = true }) { Text("Add skill") }

            if (installed.isEmpty()) {
                Text("No skills yet.", style = MaterialTheme.typography.bodyMedium)
            }
            installed.forEach { item ->
                val def = SkillSettings.enabled(item.slug)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.skill.name, style = MaterialTheme.typography.titleSmall)
                                Text(item.skill.description, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = store.stored(def), onCheckedChange = { store.set(def, it) })
                        }
                        TextButton(onClick = { deleting = item }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (showChoice) {
        AlertDialog(
            onDismissRequest = { showChoice = false },
            title = { Text("Add skill") },
            text = { Text("Paste the SKILL.md text, or give a link to a raw SKILL.md file.") },
            confirmButton = {
                TextButton(onClick = {
                    showChoice = false
                    editorText = NEW_SKILL_TEMPLATE
                }) { Text("Paste text") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showChoice = false
                    urlError = null
                    showUrl = true
                }) { Text("From a URL") }
            },
        )
    }

    if (showUrl) {
        AlertDialog(
            onDismissRequest = { if (!fetching) showUrl = false },
            title = { Text("Skill from a URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Use the raw file, for example a GitHub \"Raw\" link. Only http and https links, up to 64 KB.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                                is SkillDownloader.Result.Failed -> urlError = result.reason
                            }
                        }
                    },
                ) { Text(if (fetching) "Fetching..." else "Fetch") }
            },
            dismissButton = {
                TextButton(enabled = !fetching, onClick = { showUrl = false }) { Text("Cancel") }
            },
        )
    }

    editorText?.let { initial ->
        SkillEditorDialog(
            title = "Review skill",
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
            title = { Text("Delete ${item.skill.name}?") },
            text = { Text("The skill file is removed. Its on/off setting is cleared too.") },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(item.slug)
                    store.reset(SkillSettings.enabled(item.slug))
                    deleting = null
                    reload += 1
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
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
    var text by remember { mutableStateOf(initialText) }
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
                    "Read it before saving. Nothing is saved until you tap Save.",
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
        confirmButton = { TextButton(onClick = { error = onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
