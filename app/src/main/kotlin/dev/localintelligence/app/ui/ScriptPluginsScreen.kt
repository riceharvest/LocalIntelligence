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
import dev.localintelligence.android.scripts.ScriptPackage
import dev.localintelligence.android.scripts.ScriptPackages
import dev.localintelligence.android.scripts.ScriptPlugin
import dev.localintelligence.android.scripts.ScriptPluginRepository
import dev.localintelligence.android.scripts.ScriptRunner
import dev.localintelligence.android.skills.SkillDownloader
import dev.localintelligence.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The example a new pasted plugin starts from. It is valid as it stands, so Save works. */
private val SAMPLE_PACKAGE = ScriptPackage(
    manifestText = """
        {
          "id": "word_count",
          "title": "Word count",
          "summary": "Counts the words in a text.",
          "version": "1.0.0",
          "tools": [
            {
              "name": "count_words",
              "description": "Count the words in a text.",
              "parameters": {
                "type": "object",
                "properties": {"text": {"type": "string"}},
                "required": ["text"]
              },
              "risk": "read_only"
            }
          ]
        }
    """.trimIndent(),
    mainJs = """
        function count_words(args) {
          var words = String(args.text || "").trim().split(/\s+/).filter(Boolean);
          return "Words: " + words.length;
        }
    """.trimIndent(),
)

/**
 * Installed script plugins: switch each on or off, delete, or add one.
 *
 * Adding always goes through [ScriptEditorDialog], so both files are read before
 * anything is written, whether they were pasted or came from a zip. The switch is
 * the same one the Settings screen would show, stored under the same key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptPluginsScreen(
    store: SettingsStore,
    repository: ScriptPluginRepository,
    runner: ScriptRunner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Switch writes bump `changes`; install and delete bump `reload`. Either one re-reads the folder.
    val changes by store.changes.collectAsState()
    var reload by remember { mutableStateOf(0) }
    val installed = remember(changes, reload) {
        repository.list().map { ScriptPlugin(it, runner, store) to it }
    }
    val supported = runner.isSupported()

    var showChoice by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("https://") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<ScriptPackage?>(null) }
    var deleting by remember { mutableStateOf<Pair<ScriptPlugin, ScriptPluginRepository.Installed>?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Script plugins") },
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
                "Small programs that add tools for the assistant. They run in a sandbox with no " +
                    "network and no access to your data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!supported) {
                Text(
                    "Script plugins are not supported on this phone.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = { showChoice = true }) { Text("Add plugin") }

            if (installed.isEmpty()) {
                Text("No script plugins yet.", style = MaterialTheme.typography.bodyMedium)
            }
            installed.forEach { (plugin, item) ->
                var expanded by remember { mutableStateOf(false) }
                Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    plugin.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = store.isPluginEnabled(plugin),
                                enabled = supported,
                                onCheckedChange = { store.setPluginEnabled(plugin, it) },
                            )
                        }
                        if (expanded) {
                            Text(
                                "${item.manifest.id} ${item.manifest.version}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                item.manifest.tools.joinToString("\n") { "${plugin.id}.${it.name}" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                        TextButton(onClick = { deleting = plugin to item }) { Text("Delete") }
                    }
                }
            }

            var showHowTo by remember { mutableStateOf(false) }
            TextButton(onClick = { showHowTo = !showHowTo }) { Text("How to write one") }
            if (showHowTo) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "A plugin is two files. plugin.json says what it is and which tools it offers: " +
                                "an id, a title, a summary, a version, and tools. Each tool has a name, a " +
                                "description, a parameters schema, and a risk of read_only or reversible. " +
                                "A script cannot be a riskier tier, because it cannot reach anything outside " +
                                "its sandbox.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "main.js defines one function per tool. It receives the arguments as an object " +
                                "and returns text. Use only plain JavaScript: no fetch, no files, no timers " +
                                "that run later. A call is stopped after 5 seconds.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Example, a word count tool. Its tool is named script.word_count.count_words:",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            SAMPLE_PACKAGE.mainJs,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }

    if (showChoice) {
        AlertDialog(
            onDismissRequest = { showChoice = false },
            title = { Text("Add plugin") },
            text = { Text("Write a new plugin or import one from a link.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showChoice = false
                        editor = SAMPLE_PACKAGE
                    }) { Text("Write it here") }
                    TextButton(onClick = {
                        showChoice = false
                        urlError = null
                        showUrl = true
                    }) { Text("Import from a link") }
                    TextButton(onClick = { showChoice = false }) { Text("Cancel") }
                }
            },
        )
    }

    if (showUrl) {
        AlertDialog(
            onDismissRequest = { if (!fetching) showUrl = false },
            title = { Text("Import from a link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste the link to the plugin. You can read it before it is saved.",
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
                            val unpacked = withContext(Dispatchers.IO) {
                                when (val fetched = SkillDownloader.fetch(link, ScriptPackages.MAX_PACKAGE_BYTES)) {
                                    is SkillDownloader.Fetched.Failed -> ScriptPackages.Unzip.Refused(fetched.reason)
                                    is SkillDownloader.Fetched.Ok -> ScriptPackages.unzip(fetched.bytes)
                                }
                            }
                            fetching = false
                            when (unpacked) {
                                is ScriptPackages.Unzip.Ok -> {
                                    showUrl = false
                                    editor = unpacked.pkg
                                }
                                is ScriptPackages.Unzip.Refused -> urlError = unpacked.reason
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

    editor?.let { initial ->
        ScriptEditorDialog(
            initial = initial,
            onDismiss = { editor = null },
            onConfirm = { pkg ->
                when (val saved = repository.save(pkg)) {
                    is ScriptPluginRepository.SaveResult.Saved -> {
                        editor = null
                        reload += 1
                        null
                    }
                    is ScriptPluginRepository.SaveResult.Refused -> saved.reason
                }
            },
        )
    }

    deleting?.let { (plugin, item) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${item.manifest.title}?") },
            text = { Text("The plugin files are removed. Its on/off setting is cleared too.") },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(item.manifest.id)
                    store.forgetPlugin(plugin)
                    deleting = null
                    reload += 1
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Shows both files for reading and editing before they are saved. [onConfirm]
 * returns null when accepted, which closes this dialog, or the reason it was
 * refused, shown here so the user can fix it.
 */
@Composable
fun ScriptEditorDialog(
    initial: ScriptPackage,
    onDismiss: () -> Unit,
    onConfirm: (ScriptPackage) -> String?,
) {
    var manifest by remember { mutableStateOf(initial.manifestText) }
    var mainJs by remember { mutableStateOf(initial.mainJs) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review plugin") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Read both files. Nothing is saved until you tap Save, and the plugin stays off until you switch it on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("plugin.json", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = manifest,
                    onValueChange = {
                        manifest = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Text("main.js", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = mainJs,
                    onValueChange = {
                        mainJs = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onConfirm(ScriptPackage(manifest, mainJs)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
