package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localintelligence.core.persona.Persona
import dev.localintelligence.core.persona.PersonaLimits
import kotlinx.coroutines.launch

/**
 * Settings > Personas: the saved ways the assistant can act. Shown in both modes, because
 * a persona is something the user writes, not an advanced option.
 *
 * Nothing here touches the open chat. A persona is picked in the New chat dialog, and a
 * chat keeps the one it started with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonasScreen(
    load: suspend () -> List<Persona>,
    onSave: suspend (id: Long?, name: String, text: String) -> Unit,
    onDelete: suspend (id: Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }
    LaunchedEffect(reload) { personas = load() }

    // Saved, not remembered, so a rotation keeps the editor open with what was typed.
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = personas.firstOrNull { it.id == editingId }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Personas") },
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
                "A persona says how the assistant should act, for example short answers or a formal tone. " +
                    "Pick one when you start a new chat. The chat keeps it, and changing it needs a new chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = { creating = true }) { Text("Add persona") }

            if (personas.isEmpty()) {
                Text("No personas yet.", style = MaterialTheme.typography.bodyMedium)
            }
            personas.forEach { persona ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editingId = persona.id },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(persona.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            persona.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        PersonaEditor(
            initial = null,
            onSave = { name, text ->
                scope.launch {
                    onSave(null, name, text)
                    creating = false
                    reload++
                }
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }

    editing?.let { persona ->
        PersonaEditor(
            initial = persona,
            onSave = { name, text ->
                scope.launch {
                    onSave(persona.id, name, text)
                    editingId = null
                    reload++
                }
            },
            onDelete = { confirmingDelete = true },
            onDismiss = { editingId = null },
        )

        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Delete \"${persona.name}\"?") },
                text = { Text("Chats that used it go back to no persona. The chats themselves stay.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingDelete = false
                        scope.launch {
                            onDelete(persona.id)
                            editingId = null
                            reload++
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

/** Name and how the assistant should act, up to [PersonaLimits.MAX_TEXT_CHARS] characters. */
@Composable
private fun PersonaEditor(
    initial: Persona?,
    onSave: (name: String, text: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    val problem = PersonaLimits.problemWith(name, text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New persona" else "Edit persona") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("How the assistant should act") },
                    supportingText = { Text("${text.length} / ${PersonaLimits.MAX_TEXT_CHARS}") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                onDelete?.let { TextButton(onClick = it) { Text("Delete persona") } }
            }
        },
        confirmButton = {
            TextButton(enabled = problem == null, onClick = { onSave(name, text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
