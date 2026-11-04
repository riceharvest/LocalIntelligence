package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
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
import androidx.compose.runtime.produceState
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
import dev.localintelligence.app.SetupState
import dev.localintelligence.app.ui.components.SearchModelCard
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The documents the assistant can answer from: add a PDF, Word, EPUB, text, Markdown or CSV file,
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
    embeddingState: SetupState = SetupState.Ready,
    onStartEmbedding: () -> Unit = {},
    onCancelEmbedding: () -> Unit = {},
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
    // The store lock is held for a whole import's embedding, so the read runs off the main thread.
    val documents: List<DocumentEntry> by produceState(initialValue = emptyList(), reload) {
        value = withContext(Dispatchers.IO) { repository.list() }
    }

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
                        context.getString(R.string.document_added_long, got.title)
                    } else {
                        context.getString(R.string.document_added, got.title)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DocumentImportException) {
                    e.message ?: context.getString(R.string.document_could_not_add)
                } catch (e: Exception) {
                    context.getString(R.string.document_could_not_read)
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
                title = { Text(stringResource(R.string.documents)) },
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
                stringResource(R.string.ask_about_a_pdf_word_epub_or_text_file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { picker.launch(DocumentImport.PICKER_TYPES) },
                enabled = !busy,
            ) { Text(if (busy) stringResource(R.string.adding) else stringResource(R.string.add_document)) }

            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            if (documents.isEmpty()) {
                Text(stringResource(R.string.no_documents_yet), style = MaterialTheme.typography.bodyMedium)
            }
            val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            documents.forEach { doc ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(doc.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.added_2, dateFormat.format(Date(doc.addedAt))) +
                                if (technical) stringResource(R.string.characters, doc.sizeChars) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            TextButton(onClick = { deleting = doc }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
            }
            // The search model card: downloads EmbeddingGemma 2 with one tap, or shows Ready when installed.
            SearchModelCard(
                state = embeddingState,
                onDownload = onStartEmbedding,
                onCancel = onCancelEmbedding,
                technical = technical,
                onOpenHub = { onOpenHub(DEFAULT_EMBEDDING_REPO, DEFAULT_EMBEDDING_FILE) },
            )
        }
    }

    deleting?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.remove_3, doc.title)) },
            text = { Text(stringResource(R.string.the_assistant_will_no_longer_answer)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        // Off the main thread for the same lock reason as the list read. Disabled while
                        // an import runs, which holds that lock for its whole embedding.
                        scope.launch {
                            withContext(Dispatchers.IO) { repository.remove(doc.id) }
                            deleting = null
                            reload++
                        }
                    },
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
