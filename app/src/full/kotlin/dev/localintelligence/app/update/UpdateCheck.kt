package dev.localintelligence.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.BuildConfig
import dev.localintelligence.android.update.LatestRelease
import dev.localintelligence.android.update.fetchLatestRelease
import dev.localintelligence.android.update.versionIsNewer
import dev.localintelligence.core.tool.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===========================================================================
// The full build's "Check for updates" Settings row. Only this flavor has it: the Play build's
// UpdateCheck.kt draws nothing, because Google Play updates that build.
//
// WHY IT ASKS FIRST AND NEVER REPEATS ITSELF: the check runs once per tap, after the user
// confirms. There is no background check and nothing is installed; the row only opens the
// release page in the browser.
// ===========================================================================

/** How many characters of release notes the dialog shows. The rest is on the release page. */
private const val MAX_NOTES_CHARS = 2000

/** What one check found, as the row and its dialog show it. */
private sealed interface Outcome {
    class Newer(val tag: String, val notes: String, val pageUrl: String) : Outcome
    data object Latest : Outcome
    data object NoReleases : Outcome
    data object Failed : Outcome
}

/** The Settings row. Tapping it asks; a confirmed check runs off the main thread. */
@Composable
fun UpdateCheckRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    // The last result stays under the row after its dialog is closed.
    var last by remember { mutableStateOf<Outcome?>(null) }
    var showing by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("Check for updates") },
        supportingContent = {
            Text(
                when {
                    checking -> "Checking..."
                    else -> when (last) {
                        null -> "Asks GitHub for the latest version when you tap"
                        is Outcome.Newer -> "A newer version is available"
                        Outcome.Latest -> "You have the latest version."
                        Outcome.NoReleases -> "No releases yet."
                        Outcome.Failed -> "Could not check for updates."
                    }
                },
            )
        },
        leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !checking) { asking = true },
    )

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Check for updates") },
            text = { Text("This asks GitHub for the latest version. Check now?") },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    checking = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { check() }
                        last = outcome
                        checking = false
                        showing = true
                    }
                }) { Text("Check now") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }

    val outcome = last
    if (showing && outcome != null) {
        when (outcome) {
            is Outcome.Newer -> AlertDialog(
                onDismissRequest = { showing = false },
                title = { Text("Version ${outcome.tag} is available") },
                text = {
                    Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        Text(outcome.notes.ifBlank { "This release has no notes." }.take(MAX_NOTES_CHARS))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showing = false
                        openPage(context, outcome.pageUrl)
                    }) { Text("Open release page") }
                },
                dismissButton = { TextButton(onClick = { showing = false }) { Text("Later") } },
            )
            Outcome.Latest -> AlertDialog(
                onDismissRequest = { showing = false },
                title = { Text("Check for updates") },
                text = { Text("You have the latest version.") },
                confirmButton = { TextButton(onClick = { showing = false }) { Text("OK") } },
            )
            Outcome.NoReleases -> AlertDialog(
                onDismissRequest = { showing = false },
                title = { Text("Check for updates") },
                text = { Text("No releases yet.") },
                confirmButton = { TextButton(onClick = { showing = false }) { Text("OK") } },
            )
            Outcome.Failed -> AlertDialog(
                onDismissRequest = { showing = false },
                title = { Text("Check for updates") },
                text = { Text("Could not check for updates. Try again later.") },
                confirmButton = { TextButton(onClick = { showing = false }) { Text("OK") } },
            )
        }
    }
}

/** Asks GitHub and compares the answer with the installed version. Blocking: call it on Dispatchers.IO. */
private fun check(): Outcome {
    return when (val latest = fetchLatestRelease(CancellationSignal.None)) {
        is LatestRelease.Found -> when (versionIsNewer(latest.tag, BuildConfig.VERSION_NAME)) {
            true -> Outcome.Newer(latest.tag, latest.notes, latest.pageUrl)
            false -> Outcome.Latest
            null -> Outcome.Failed
        }
        LatestRelease.NoReleases -> Outcome.NoReleases
        LatestRelease.Failed -> Outcome.Failed
    }
}

/** Opens the release page in the browser. Quietly does nothing when no app can open a web link. */
private fun openPage(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
