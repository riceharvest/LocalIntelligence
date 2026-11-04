package dev.localintelligence.app.ui

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

    // Saved, not remembered: a rotation recreates the activity and must not close the
    // dialogs or drop the text typed into them. [fetching] stays remembered because a
    // recreation cancels the fetch, so restoring "true" would leave the spinner stuck.
    var showChoice by rememberSaveable { mutableStateOf(false) }
    var showUrl by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("https://") }
    var urlError by rememberSaveable { mutableStateOf<String?>(null) }
    var fetching by remember { mutableStateOf(false) }
    var editor by rememberSaveable(stateSaver = ScriptPackageSaver) { mutableStateOf<ScriptPackage?>(null) }
    var deleting by remember { mutableStateOf<Pair<ScriptPlugin, ScriptPluginRepository.Installed>?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.script_plugins)) },
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
                stringResource(R.string.small_programs_that_add_tools_for_the),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!supported) {
                Text(
                    stringResource(R.string.script_plugins_are_not_supported_on),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FilledTonalButton(onClick = { showChoice = true }) { Text(stringResource(R.string.add_plugin)) }

            if (installed.isEmpty()) {
                Text(stringResource(R.string.no_script_plugins_yet), style = MaterialTheme.typography.bodyMedium)
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
                        TextButton(onClick = { deleting = plugin to item }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }

            var showHowTo by remember { mutableStateOf(false) }
            TextButton(onClick = { showHowTo = !showHowTo }) { Text(stringResource(R.string.how_to_write_one)) }
            if (showHowTo) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.a_plugin_is_two_files_plugin_json_says),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.main_js_defines_one_function_per_tool),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.example_a_word_count_tool_its_tool_is),
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
            title = { Text(stringResource(R.string.add_plugin)) },
            text = { Text(stringResource(R.string.write_a_new_plugin_or_import_one_from_a)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showChoice = false
                        editor = SAMPLE_PACKAGE
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
                    Text(
                        stringResource(R.string.paste_the_link_to_the_plugin_you_can),
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
                ) { Text(if (fetching) stringResource(R.string.fetching) else stringResource(R.string.fetch)) }
            },
            dismissButton = {
                TextButton(enabled = !fetching, onClick = { showUrl = false }) { Text(stringResource(R.string.cancel)) }
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
            title = { Text(stringResource(R.string.delete_2, item.manifest.title)) },
            text = { Text(stringResource(R.string.the_plugin_files_are_removed_its_on_off)) },
            confirmButton = {
                TextButton(onClick = {
                    repository.delete(item.manifest.id)
                    store.forgetPlugin(plugin)
                    deleting = null
                    reload += 1
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Saves the review dialog's package across recreation. The two texts are stored as a
 * plain list because that is what a saved-state bundle can hold. Null means "closed".
 */
private val ScriptPackageSaver = Saver<ScriptPackage?, ArrayList<String>>(
    save = { pkg -> pkg?.let { arrayListOf(it.manifestText, it.mainJs) } },
    restore = { saved -> ScriptPackage(saved[0], saved[1]) },
)

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
    var manifest by rememberSaveable { mutableStateOf(initial.manifestText) }
    var mainJs by rememberSaveable { mutableStateOf(initial.mainJs) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_plugin)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.read_both_files_nothing_is_saved_until),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.plugin_json), style = MaterialTheme.typography.labelMedium)
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
                Text(stringResource(R.string.main_js), style = MaterialTheme.typography.labelMedium)
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
            TextButton(onClick = { error = onConfirm(ScriptPackage(manifest, mainJs)) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
