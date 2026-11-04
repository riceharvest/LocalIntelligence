package dev.localintelligence.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.screen.ScreenRun

// ===========================================================================
// The setup card above the screen control switch (Technical mode, full build only: the plugin
// is registered only there, so the card is shown only there).
//
// It says whether the accessibility service is on, sends the person to the two screens they need,
// and gives the Android 13+ fix for sideloaded apps: a restricted-settings lock that is lifted from
// App info, not from Accessibility itself.
// ===========================================================================

@Composable
fun ScreenControlSetupCard() {
    val context = LocalContext.current
    val on = ScreenRun.serviceConnected

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Screen control", style = MaterialTheme.typography.titleSmall)
        Text(
            if (on) {
                "On. The assistant can read and tap in an app you approve, once per request."
            } else {
                "Off. Turn on the LocalIntelligence accessibility service, then switch Screen control on below."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "If Android says this setting is restricted, open App info, tap the ⋮ menu, choose " +
                "Allow restricted settings, then come back to Accessibility. Apps installed outside " +
                "Google Play hit this on Android 13 and later.",
            style = MaterialTheme.typography.bodySmall,
        )
        FilledTonalButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Open Accessibility settings") }
        OutlinedButton(onClick = {
            val uri = Uri.fromParts("package", context.packageName, null)
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Open App info") }
    }
}
