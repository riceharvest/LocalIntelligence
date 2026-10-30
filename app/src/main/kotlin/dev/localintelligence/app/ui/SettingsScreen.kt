package dev.localintelligence.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.scripts.ScriptPlugin
import dev.localintelligence.app.export.ChatExporter
import dev.localintelligence.app.openAssistantSettings
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.app.settings.SuggestedModel
import dev.localintelligence.app.speedtest.SpeedTestButton
import dev.localintelligence.app.speedtest.SpeedTestResultBlock
import dev.localintelligence.app.speedtest.SpeedTestRunner
import dev.localintelligence.app.ui.components.openPermissionScreen
import dev.localintelligence.core.plugin.AgentPlugin
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingApplies
import dev.localintelligence.core.settings.SettingSection
import dev.localintelligence.core.hub.formatBytes
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import java.util.Locale

/**
 * Every setting, generated from the declarations in [CoreSettings] and the
 * plugins', and the way to every other screen: the chat has no app bar, so
 * models, skills, permissions and the rest are opened from here.
 *
 * Simple mode shows only the General rows (name, colour, appearance, about you);
 * the phone decides everything else. Technical shows every row, the plugin
 * switches and the diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SettingsStore,
    plugins: List<AgentPlugin>,
    suggestion: SuggestedModel,
    /** True when the suggested file is already in the model list; the card is then hidden. */
    suggestionInstalled: Boolean = false,
    onOpenHub: (repo: String, file: String) -> Unit,
    /** Opens the skills screen. Defaulted so a caller that predates skills still compiles. */
    onOpenSkills: () -> Unit = {},
    /** The speed test, shown as a row in the Technical Model section. Null hides it. */
    speedTest: SpeedTestRunner? = null,
    /** Opens the documents screen. Defaulted, like [onOpenSkills]. */
    onOpenDocuments: () -> Unit = {},
    /** Opens the script plugins screen. Defaulted, like [onOpenSkills]. */
    onOpenScripts: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    onOpenTrace: () -> Unit = {},
    onOpenRedaction: () -> Unit = {},
    /** Clears the chat and starts a new conversation. Null hides the row (empty chat, or a run going). */
    onNewChat: (() -> Unit)? = null,
    /** Exports the current chat in a format. Null hides the row: an empty chat has nothing to export. */
    onExportChat: ((ChatExporter.Format) -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Any write bumps this, which recomposes the screen with fresh values.
    val revision by store.changes.collectAsState()
    // Outside key(revision), so a write does not scroll the page back to the top.
    val scroll = rememberScrollState()

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
            // Read here, not above the Scaffold: only this block recomposes on a write,
            // so a value read outside it would keep the old mode.
            val technical = store.mode == SettingsMode.TECHNICAL
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ModeSwitch(store)
                if (!technical && !suggestionInstalled) SuggestionCard(suggestion) { onOpenHub(suggestion.repo, suggestion.file) }

                val context = LocalContext.current
                Card(modifier = Modifier.fillMaxWidth()) {
                    onNewChat?.let { NewChatRow(it) }
                    NavRow(Icons.Filled.Memory, "Models", onOpenModels)
                    NavRow(Icons.Filled.AutoAwesome, "Skills", onOpenSkills)
                    NavRow(Icons.Filled.Description, "Documents", onOpenDocuments)
                    NavRow(Icons.Filled.Schedule, "Scheduled tasks", onOpenSchedule)
                    NavRow(Icons.Filled.Lock, "Permissions") { openPermissionScreen(context) }
                    NavRow(Icons.Filled.Assistant, "Set as phone assistant") { openAssistantSettings(context) }
                    if (technical) NavRow(Icons.Filled.Extension, "Script plugins", onOpenScripts)
                    onExportChat?.let { ExportRow(it) }
                }

                val rows = (CoreSettings.all + plugins.flatMap { it.settings })
                    .filter { it !== CoreSettings.MODE && (technical || (it.simple && it.section == SettingSection.GENERAL)) }
                for (section in SettingSection.entries) {
                    val inSection = rows.filter { it.section == section }
                    // The speed test sits under the Model section's rows (Compute, GPU
                    // layers), Technical mode only.
                    val speedTestHere = technical && speedTest != null && section == SettingSection.MODEL
                    if (inSection.isEmpty() && !speedTestHere) continue
                    SectionHeader(section.title)
                    inSection.forEach { SettingRow(store, it, technical) }
                    if (speedTestHere) speedTest?.let { SpeedTestRow(it) }
                }

                if (technical) {
                    SectionHeader("Plugins")
                    // Script plugins have their own screen, with the same switch, so they are not repeated here.
                    plugins.filterNot { it is ScriptPlugin }.forEach { plugin ->
                        SwitchRow(
                            title = plugin.title,
                            help = plugin.summary,
                            checked = store.isPluginEnabled(plugin),
                            onChange = { store.setPluginEnabled(plugin, it) },
                        )
                    }

                    SectionHeader("Diagnostics")
                    Card(modifier = Modifier.fillMaxWidth()) {
                        NavRow(Icons.Filled.Timeline, "Trace of the last run", onOpenTrace)
                        NavRow(Icons.Filled.Shield, "What is filtered before it leaves the phone", onOpenRedaction)
                    }
                }
            }
        }
    }
}

/** One destination: icon, name, chevron. The whole row is the target. */
@Composable
private fun NavRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** New chat, after a confirmation: the cleared conversation cannot be reopened here. */
@Composable
private fun NewChatRow(onNewChat: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    NavRow(Icons.Filled.AddComment, "New chat") { asking = true }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Start a new chat?") },
            text = { Text("This conversation will be cleared. Past chats can still find what was said.") },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    onNewChat()
                }) { Text("New chat") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/** Export, with the formats in a menu under the row. */
@Composable
private fun ExportRow(onExport: (ChatExporter.Format) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NavRow(Icons.Filled.IosShare, "Export this chat") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ChatExporter.Format.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.label) },
                    onClick = {
                        open = false
                        onExport(format)
                    },
                )
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
            Text("Recommended for this phone", style = MaterialTheme.typography.labelLarge)
            Text(suggestion.name, style = MaterialTheme.typography.titleMedium)
            Text(suggestion.reason, style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = onOpenHub) { Text("Download · ${formatBytes(suggestion.sizeBytes)}") }
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

/**
 * The speed test as a button row. Not a [SettingDef]: it changes nothing, it measures.
 * The help text says what it does to the conversation, because that is the question
 * a user has about a button that runs the model.
 */
@Composable
private fun SpeedTestRow(runner: SpeedTestRunner) {
    val last by runner.last.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowTitle(
            title = "Speed test",
            help = "Runs the loaded model twice: a prompt of about 160 tokens, then 64 tokens " +
                "generated. Measures prefill and decode speed on this phone. With Compute on Auto " +
                "it also tries each GPU and keeps the fastest. Refused while an agent run is " +
                "active. The conversation is not changed.",
        )
        SpeedTestButton(runner)
        last?.let { SpeedTestResultBlock(it) }
    }
}

@Composable
private fun SettingRow(store: SettingsStore, def: SettingDef<*>, technical: Boolean) {
    val help = def.help
    // "Applies now" is the common case and says nothing; only a delay is worth a line.
    val note = if (technical) when (def.applies) {
        SettingApplies.NEXT_RUN -> "Applies from the next message"
        SettingApplies.NEXT_MODEL_LOAD -> "Applies from the next model load"
        else -> null
    } else null
    when (def) {
        is SettingDef.Toggle -> SwitchRow(def.title, help, store.stored(def), note = note) { store.set(def, it) }
        is SettingDef.Choice -> if (def === CoreSettings.THEME_COLOR) {
            ColorRow(store, def, help, note)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RowTitle(def.title, help, note = note)
                if (def.options.size <= 3) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        def.options.forEachIndexed { index, option ->
                            SegmentedButton(
                                selected = store.stored(def) == option.id,
                                onClick = { store.set(def, option.id) },
                                shape = SegmentedButtonDefaults.itemShape(index, def.options.size),
                            ) { Text(option.label) }
                        }
                    }
                } else {
                    // Four or more labels do not fit one segmented row on a phone; chips wrap.
                    ChipChoices(store, def)
                }
            }
        }
        is SettingDef.FloatValue -> {
            val value = store.stored(def)
            Column {
                RowTitle(def.title, help, valueText = String.format(Locale.ROOT, "%.2f", value), note = note) {
                    store.reset(def)
                }
                Slider(
                    value = value,
                    onValueChange = { store.set(def, snap(it, def.min, def.step)) },
                    valueRange = def.min..def.max,
                )
            }
        }
        is SettingDef.Text -> TextSettingRow(store, def, help, note)
        is SettingDef.Secret -> SecretRow(store, def, help, note)
        is SettingDef.IntValue -> {
            val value = store.stored(def)
            val span = def.max.toLong() - def.min.toLong()
            Column {
                RowTitle(def.title, help, valueText = value.toString(), note = note) { store.reset(def) }
                if (span in 1..500) {
                    Slider(
                        value = value.toFloat(),
                        onValueChange = {
                            store.set(def, (Math.round(it / def.step) * def.step).coerceIn(def.min, def.max))
                        },
                        valueRange = def.min.toFloat()..def.max.toFloat(),
                    )
                } else {
                    NumberField(value, def.min, def.max) { store.set(def, it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipChoices(store: SettingsStore, def: SettingDef.Choice) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        def.options.forEach { option ->
            FilterChip(
                selected = store.stored(def) == option.id,
                onClick = { store.set(def, option.id) },
                label = { Text(option.label) },
            )
        }
    }
}

/** Theme colour as swatches: a colour is chosen by looking at it, not by its name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorRow(store: SettingsStore, def: SettingDef.Choice, help: String, note: String?) {
    val context = LocalContext.current
    val chosen = store.stored(def)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowTitle(def.title, help, note = note)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Wallpaper colours exist from Android 12; older phones are not offered them.
            def.options.filter { it.id != CoreSettings.THEME_WALLPAPER || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
                .forEach { option ->
                    val selected = chosen == option.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.selectable(
                            selected = selected,
                            onClick = { store.set(def, option.id) },
                            role = Role.RadioButton,
                        ),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(themeSwatch(option.id, context))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                        ) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                        }
                        Text(option.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
        }
    }
}

/** Up to this many characters, a text setting is one line (a name), not a note. */
private const val SHORT_TEXT = 80

/**
 * A multi-line text setting with an explicit Save.
 *
 * WHY SAVE AND NOT WRITE-ON-EVERY-KEY: a store write bumps [SettingsStore.changes], and
 * the whole screen is keyed on that revision, so each keystroke would rebuild the field
 * and drop the cursor. The draft lives here until Save, which is the one write.
 */
@Composable
private fun TextSettingRow(store: SettingsStore, def: SettingDef.Text, help: String, note: String?) {
    val stored = store.stored(def)
    var draft by remember(stored) { mutableStateOf(stored) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowTitle(def.title, help, note = note)
        // A name-sized value gets one line; a note gets room to write.
        val short = def.maxLength <= SHORT_TEXT
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(def.maxLength) },
            singleLine = short,
            minLines = if (short) 1 else 3,
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

/**
 * A credential. The screen says only "set" or "not set"; the field is for typing
 * a new value, never for reading the old one. Clearing removes it.
 */
@Composable
private fun SecretRow(store: SettingsStore, def: SettingDef.Secret, help: String, note: String?) {
    val isSet = store.isSecretSet(def)
    var draft by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RowTitle(def.title, help, valueText = if (isSet) "Saved" else "Not set", note = note)
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                refused = false
            },
            label = { Text(if (isSet) "Replace" else "Paste the key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    if (store.setSecret(def, draft)) draft = "" else refused = true
                },
                enabled = draft.isNotBlank(),
            ) { Text("Save") }
            if (isSet) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Remove the saved key?") },
                confirmButton = {
                    TextButton(onClick = {
                        store.clearSecret(def)
                        draft = ""
                        confirmClear = false
                    }) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            )
        }
        if (refused) {
            Text(
                "The phone's keystore refused to store it. Nothing was saved.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RowTitle(
    title: String,
    help: String,
    valueText: String? = null,
    note: String? = null,
    onReset: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            HelpText(help, note)
        }
        if (valueText != null) {
            Spacer(Modifier.width(8.dp))
            Text(valueText, style = MaterialTheme.typography.labelLarge)
        }
        if (onReset != null) TextButton(onClick = onReset) { Text("Reset") }
    }
}

/** Help in the quiet colour so the title stands out, with the optional timing line under it. */
@Composable
private fun HelpText(help: String, note: String?) {
    Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            HelpText(help, note)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * A number typed by hand. The draft stays here while typing and is committed on Done or
 * when focus leaves: committing per keystroke made the first digit of a value below the
 * minimum snap the field to the minimum before the rest could be typed.
 */
@Composable
private fun NumberField(value: Int, min: Int, max: Int, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var hadFocus by remember { mutableStateOf(false) }
    fun commit() {
        val parsed = text.toIntOrNull()?.coerceIn(min, max)
        if (parsed == null) {
            text = value.toString()
        } else {
            text = parsed.toString()
            if (parsed != value) onCommit(parsed)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(10) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (hadFocus && !it.isFocused) commit()
                hadFocus = it.isFocused
            },
    )
}

private fun snap(value: Float, min: Float, step: Float): Float =
    if (step <= 0f) value else min + Math.round((value - min) / step) * step
