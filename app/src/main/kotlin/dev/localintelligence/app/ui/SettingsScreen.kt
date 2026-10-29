package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.openAssistantSettings
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.app.settings.SuggestedModel
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import java.util.Locale

/**
 * Every setting, generated from the declarations in [CoreSettings] and the
 * plugins'. Simple mode shows the rows marked simple plus the plugin switches;
 * Technical shows everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SettingsStore,
    plugins: List<AgentPlugin>,
    suggestion: SuggestedModel,
    onOpenHub: (repo: String, file: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Any write bumps this, which recomposes the screen with fresh values.
    val revision by store.changes.collectAsState()
    val technical = store.mode == SettingsMode.TECHNICAL

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        key(revision) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeSwitch(store)
                if (!technical) SuggestionCard(suggestion) { onOpenHub(suggestion.repo, suggestion.file) }
                AssistantCard()

                val rows = (CoreSettings.all + plugins.flatMap { it.settings })
                    .filter { it !== CoreSettings.MODE && (technical || it.simple) }
                for (section in SettingSection.entries) {
                    val inSection = rows.filter { it.section == section }
                    if (inSection.isEmpty()) continue
                    SectionHeader(section.title)
                    inSection.forEach { SettingRow(store, it, technical) }
                }

                SectionHeader(if (technical) "Plugins" else "What the assistant can use")
                plugins.forEach { plugin ->
                    SwitchRow(
                        title = plugin.title,
                        help = plugin.summary,
                        checked = store.isPluginEnabled(plugin),
                        onChange = { store.setPluginEnabled(plugin, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeSwitch(store: SettingsStore) {
    val def = CoreSettings.MODE
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            def.options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = store.stored(def) == option.id,
                    onClick = { store.set(def, option.id) },
                    shape = SegmentedButtonDefaults.itemShape(index, def.options.size),
                ) { Text(option.label) }
            }
        }
        Text(def.help, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SuggestionCard(suggestion: SuggestedModel, onOpenHub: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Suggested for this phone", style = MaterialTheme.typography.labelLarge)
            Text(suggestion.file, style = MaterialTheme.typography.titleMedium)
            Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = onOpenHub) { Text("Get it from ${suggestion.repo}") }
        }
    }
}

@Composable
private fun AssistantCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Phone assistant", style = MaterialTheme.typography.labelLarge)
            Text(
                "Opens LocalIntelligence over any app from the long-press Home or power button. " +
                    "You choose it in system settings.",
                style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(onClick = { openAssistantSettings(context) }) { Text("Set as phone assistant") }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SettingRow(store: SettingsStore, def: SettingDef<*>, technical: Boolean) {
    val help = if (technical) "${def.help} Applies ${def.applies.label}." else def.help
    when (def) {
        is SettingDef.Toggle -> SwitchRow(def.title, help, store.stored(def)) { store.set(def, it) }
        is SettingDef.Choice -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RowTitle(def.title, help)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                def.options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = store.stored(def) == option.id,
                        onClick = { store.set(def, option.id) },
                        shape = SegmentedButtonDefaults.itemShape(index, def.options.size),
                    ) { Text(option.label) }
                }
            }
        }
        is SettingDef.FloatValue -> {
            val value = store.stored(def)
            Column {
                RowTitle(def.title, help, valueText = String.format(Locale.ROOT, "%.2f", value)) {
                    store.reset(def)
                }
                Slider(
                    value = value,
                    onValueChange = { store.set(def, snap(it, def.min, def.step)) },
                    valueRange = def.min..def.max,
                )
            }
        }
        is SettingDef.Text -> TextSettingRow(store, def, help)
        is SettingDef.IntValue -> {
            val value = store.stored(def)
            val span = def.max.toLong() - def.min.toLong()
            Column {
                RowTitle(def.title, help, valueText = value.toString()) { store.reset(def) }
                if (span in 1..500) {
                    Slider(
                        value = value.toFloat(),
                        onValueChange = {
                            store.set(def, (Math.round(it / def.step) * def.step).coerceIn(def.min, def.max))
                        },
                        valueRange = def.min.toFloat()..def.max.toFloat(),
                    )
                } else {
                    NumberField(value) { store.set(def, it) }
                }
            }
        }
    }
}

/**
 * A multi-line text setting with an explicit Save.
 *
 * WHY SAVE AND NOT WRITE-ON-EVERY-KEY: a store write bumps [SettingsStore.changes], and
 * the whole screen is keyed on that revision, so each keystroke would rebuild the field
 * and drop the cursor. The draft lives here until Save, which is the one write.
 */
@Composable
private fun TextSettingRow(store: SettingsStore, def: SettingDef.Text, help: String) {
    val stored = store.stored(def)
    var draft by remember(stored) { mutableStateOf(stored) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowTitle(def.title, help)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(def.maxLength) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("${draft.length} of ${def.maxLength} characters") },
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (stored.isNotEmpty()) {
                TextButton(onClick = { store.set(def, "") }) { Text("Clear") }
            }
            FilledTonalButton(
                onClick = { store.set(def, draft) },
                enabled = draft != stored,
            ) { Text("Save") }
        }
    }
}

@Composable
private fun RowTitle(title: String, help: String, valueText: String? = null, onReset: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        if (valueText != null) {
            Spacer(Modifier.width(8.dp))
            Text(valueText, style = MaterialTheme.typography.labelLarge)
        }
        if (onReset != null) TextButton(onClick = onReset) { Text("Reset") }
    }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(value: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let(onCommit)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun snap(value: Float, min: Float, step: Float): Float =
    if (step <= 0f) value else min + Math.round((value - min) / step) * step
