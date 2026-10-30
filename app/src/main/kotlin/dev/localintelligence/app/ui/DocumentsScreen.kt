package dev.localintelligence.app.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.documents.DEFAULT_EMBEDDING_FILE
import dev.localintelligence.android.documents.DEFAULT_EMBEDDING_REPO
import dev.localintelligence.android.documents.DocumentEntry
import dev.localintelligence.android.documents.DocumentImport
import dev.localintelligence.android.documents.DocumentImportException
import dev.localintelligence.android.documents.DocumentStore
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The documents the assistant can answer from: add a PDF, text, Markdown or CSV file,
 * see what is there, remove one.
 *
 * Adding goes through the system file picker, which grants read access to the one
 * file chosen. The text is copied into app storage at once, so the file can be deleted
 * afterwards without losing the document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    repository: DocumentStore,
    embeddingModelReady: Boolean,
    onOpenHub: (repo: String, file: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settingsVersion by (context.applicationContext as LocalIntelligenceApp).container.settings.changes.collectAsStateWithLifecycle()
    val technical = remember(settingsVersion) {
        (context.applicationContext as LocalIntelligenceApp).container.settings.mode == SettingsMode.TECHNICAL
    }
    val scope = rememberCoroutineScope()
    // Adding or removing bumps this, which re-reads the list.
    var reload by remember { mutableIntStateOf(0) }
    val documents: List<DocumentEntry> = remember(reload) { repository.list() }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DocumentEntry?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    val got = DocumentImport.extract(context, uri)
                    repository.add(got.title, got.text)
                    if (got.truncated) {
                        "Added ${got.title}. It is long, so only the first part can be searched."
                    } else {
                        "Added ${got.title}."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DocumentImportException) {
                    e.message ?: "Could not add that file."
                } catch (e: Exception) {
                    "Could not read that file. Try a different copy."
                }
            }
            busy = false
            reload++
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
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
                "Ask about a PDF or text file. It stays on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { picker.launch(DocumentImport.PICKER_TYPES) },
                enabled = !busy,
            ) { Text(if (busy) "Adding..." else "Add document") }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (documents.isEmpty()) {
                Text("No documents yet.", style = MaterialTheme.typography.bodyMedium)
            }
            val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            documents.forEach { doc ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(doc.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Added ${dateFormat.format(Date(doc.addedAt))}" +
                                if (technical) ", ${doc.sizeChars} characters" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = { deleting = doc }) { Text("Remove") }
                        }
                    }
                }
            }
            // Without the model, search matches words only. The hub download is the one place the
            // model arrives, and it is kept for search rather than loaded as the chat model.
            if (!embeddingModelReady) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Find answers even when your words differ from the document.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FilledTonalButton(onClick = { onOpenHub(DEFAULT_EMBEDDING_REPO, DEFAULT_EMBEDDING_FILE) }) {
                            Text("Get the search model")
                        }
                    }
                }
            }
        }
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove ${doc.title}?") },
            text = { Text("The assistant will no longer answer from this document.") },
            confirmButton = {
                TextButton(onClick = {
                    repository.remove(doc.id)
                    deleting = null
                    reload++
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}
