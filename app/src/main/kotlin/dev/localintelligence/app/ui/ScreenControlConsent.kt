package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ===========================================================================
// The disclosure shown before the person is sent to Android's Accessibility settings
// (docs/adr/0003-screen-control-approval-per-app.md). It is shown for the full build's screen control only,
// from the setup card, and it is the only way to agree: nothing else writes the consent setting.
//
// It is a full-screen dialog rather than a screen, so the card keeps its place in Settings and
// "Not now" simply closes it. This file is in `:app` main because the card that opens it is in
// main; the Play build never shows the card (the plugin is not registered there).
// ===========================================================================

/**
 * The full-screen disclosure. [onAgree] is called before the Accessibility settings open; [onNotNow]
 * closes the screen and leaves screen control off.
 */
@Composable
fun ScreenControlConsentDialog(
    onAgree: () -> Unit,
    onNotNow: () -> Unit,
) {
    Dialog(
        onDismissRequest = onNotNow,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Let the assistant use other apps?", style = MaterialTheme.typography.headlineSmall)

                Text(
                    "Screen control lets the assistant read and act in an app on your screen. " +
                        "Read this before you turn it on.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("What it reads", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Only the app in front, during a request that uses it: the text and buttons on that " +
                        "screen. It does not read your other apps in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("What it does", style = MaterialTheme.typography.titleSmall)
                Text(
                    "It can tap, type, scroll and press Back or Home, but only in an app you approve for " +
                        "that request. It asks you again before any tap on a button such as send, pay, buy, " +
                        "delete, install or confirm.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("What it never does", style = MaterialTheme.typography.titleSmall)
                Text(
                    "It never runs unattended or in the background. It does not act in its own screens, " +
                        "Settings, or permission dialogs.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("Where the data goes", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Nothing leaves the phone because of screen control, unless you have set up a cloud " +
                        "model to answer the request. If one does, the text on that screen goes to that " +
                        "model's provider.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("How to turn it off", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Switch off the LocalIntelligence accessibility service in Android's Accessibility " +
                        "settings. You can also switch Screen control off in this Settings screen.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text("If Android blocks the switch", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Some installs from outside Google Play are blocked from turning on accessibility " +
                        "services until you allow restricted settings. If that happens:\n" +
                        "1. Open App info for LocalIntelligence.\n" +
                        "2. Tap the ⋮ menu at the top right.\n" +
                        "3. Tap Allow restricted settings.\n" +
                        "4. Come back to Accessibility and turn on the service.\n" +
                        "Not every install shows this step.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
                ) {
                    TextButton(onClick = onNotNow) { Text("Not now") }
                    Button(onClick = onAgree) { Text("Agree and open settings") }
                }
            }
        }
    }
}
