package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import dev.localintelligence.app.photos.PHOTO_RUN_LIMIT
import dev.localintelligence.app.photos.PhotoRunEnd
import dev.localintelligence.app.photos.PhotoRunReport
import dev.localintelligence.app.photos.PhotoSearchIndex
import kotlinx.coroutines.launch

/**
 * Photo search: index the photo library by caption, then delete that index. Full build only. The
 * row that opens this screen is shown only when the photo plugin is present (see MainActivity).
 *
 * WHY INDEXING IS A BUTTON, NOT A BACKGROUND JOB: it uses the loaded model for every photo, which
 * is seconds of compute each. The person starts it, sees progress, and can stop it. Leaving this
 * screen stops it too, and the rows already written are kept, so the next run resumes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSearchScreen(
    index: PhotoSearchIndex,
    visionReady: Boolean,
    chatBusy: () -> Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The photo permission a run needs: the media permission from Android 13, the storage one before.
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var reload by remember { mutableIntStateOf(0) }
    val indexed by produceState(initialValue = -1, reload) { value = index.count() }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // A run belongs to this screen: leaving it stops the run, and what was written stays.
    DisposableEffect(Unit) {
        onDispose { index.stop() }
    }

    fun startRun() {
        running = true
        message = null
        scope.launch {
            val report: PhotoRunReport = index.run(
                chatBusy = chatBusy,
                onProgress = { done, total -> progress = done to total },
            )
            running = false
            progress = null
            message = report.describe()
            reload++
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startRun()
        } else {
            message = "Photo search needs access to your photos. Allow it in the Android settings for this app, then try again."
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.photo_search)) },
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
                stringResource(R.string.finds_photos_by_what_they_show_the_2, PHOTO_RUN_LIMIT),
            )

            Text(
                when {
                    indexed < 0 -> stringResource(R.string.checking_the_index)
                    indexed == 0 -> stringResource(R.string.nothing_indexed_yet)
                    else -> stringResource(R.string.photos_indexed, indexed)
                },
            )

            if (!visionReady) {
                Text(
                    stringResource(R.string.indexing_needs_a_model_that_can_read),
                )
            }

            progress?.let { (done, total) ->
                Text(stringResource(R.string.captioning_photo_of, (done + 1).coerceAtMost(total), total))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            message?.let { Text(it) }

            if (running) {
                OutlinedButton(onClick = { index.stop() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stop))
                }
            } else {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                            startRun()
                        } else {
                            permissionLauncher.launch(permission)
                        }
                    },
                    enabled = visionReady && !chatBusy(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.index_my_photos))
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = indexed > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.delete_index))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_the_photo_index)) },
            text = { Text(stringResource(R.string.the_captions_are_removed_your_photos)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        index.clear()
                        message = "The photo index is deleted."
                        reload++
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** The one line the screen shows when a run ends. */
private fun PhotoRunReport.describe(): String = when (end) {
    PhotoRunEnd.FINISHED -> when {
        captioned == 0 && failed == 0 -> "No new photos to index."
        failed == 0 -> "Indexed $captioned photos."
        else -> "Indexed $captioned photos. $failed could not be read."
    }
    PhotoRunEnd.STOPPED -> "Stopped after $captioned photos. The next run continues from here."
    PhotoRunEnd.CHAT_BUSY -> "A chat is running, so indexing paused. Run it again when the chat is done."
    PhotoRunEnd.NO_VISION_MODEL -> "No model that can read pictures is loaded. Load one on the Models screen."
    PhotoRunEnd.NO_ACCESS -> "Photo access was not granted, so no photos were read."
}
