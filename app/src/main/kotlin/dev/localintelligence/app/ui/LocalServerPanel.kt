package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.gateway.localserver.LocalServerReveal
import dev.localintelligence.android.gateway.localserver.LocalServerSettings
import dev.localintelligence.app.LocalServerService
import dev.localintelligence.app.settings.SettingsStore

// ===========================================================================
// The local server block on the Settings screen, above its token row.
//
// It shows the address other apps call, the last problem, and the token. The token is shown once,
// from memory, with a Copy button: the stored value is never read back into the UI, so after this
// screen closes the only way to get a token is Regenerate. Technical mode only, because its rows are.
// ===========================================================================

@Composable
fun LocalServerPanel(store: SettingsStore) {
    val revision by store.changes.collectAsState()
    val error = remember(revision) { store.stored(LocalServerSettings.LAST_ERROR) }
    val token = remember(revision) { LocalServerReveal.token }
    val hasToken = remember(revision) { store.isSecretSet(LocalServerSettings.TOKEN) }
    val clipboard = LocalClipboardManager.current

    // The revealed token leaves memory when this screen closes, so it is shown only in this visit.
    DisposableEffect(Unit) {
        onDispose { LocalServerReveal.token = null }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local server", style = MaterialTheme.typography.titleSmall)
        Text(
            "Other apps on this phone can use the loaded model at " +
                "http://${LocalServerSettings.HOST}:${LocalServerSettings.PORT}/v1 " +
                "with the access token as a Bearer header. They get the model only: no tools, memory or chats. " +
                "A notification shows while it runs.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (error.isNotBlank()) {
            Text(
                "Last problem: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (token != null) {
            Text(
                "Copy this token now. It is not shown again.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    token,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text("Copy") }
            }
        } else if (hasToken) {
            Text(
                "The access token is set. Regenerate makes a new one and cuts off the old one at once.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                "No token yet. Switch the local server on to make one.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        OutlinedButton(onClick = { LocalServerService.regenerateToken(store) }) { Text("Regenerate token") }
    }
}
