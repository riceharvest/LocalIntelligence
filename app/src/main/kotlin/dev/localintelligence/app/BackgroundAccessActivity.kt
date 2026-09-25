package dev.localintelligence.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.localintelligence.android.tools.notifications.LocalNotificationListenerService
import dev.localintelligence.app.ui.LocalIntelligenceTheme

/**
 * The screen that asks for notification access.
 *
 * ## Why this exists as its own Activity
 *
 * `LocalNotificationListenerService` can only be bound by the system, and only
 * after a human turns on a switch in Android Settings. Nothing an app ships can
 * grant it, and — the part that actually mattered — nothing in this codebase
 * *asked*. The tools all returned an honest `PermissionDenied` naming the
 * settings screen, which is the correct behaviour for a tool and useless to a
 * user, because "open Settings and find the toggle" is a thing the user has to
 * know how to do.
 *
 * So the request had to have a home, and the natural one is a screen the user can
 * be sent to. It is an Activity rather than a composable inside the chat for two
 * reasons: it is reachable from a `PendingIntent` and a notification, and it is
 * a separate task, so returning from Settings lands the user back on it.
 *
 * ## The state is re-read on every resume
 *
 * The grant happens in another process, on a screen we do not own. A value read
 * once at composition is wrong the moment the user comes back, and the screen
 * would then tell a user who just enabled it that it is still off — which is the
 * single most credibility-destroying thing a permissions screen can do.
 */
class BackgroundAccessActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalIntelligenceTheme {
                BackgroundAccessScreen()
            }
        }
    }
}

@Composable
private fun BackgroundAccessScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(LocalNotificationListenerService.isGranted(context)) }

    // Re-read on every ON_RESUME. See the class doc: the grant is decided in
    // another process and this is the only moment we can observe it.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = LocalNotificationListenerService.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Background access", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Read your notifications",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (granted) {
                        "On. The assistant can read notifications when you ask it " +
                            "to, and reply to ones that offer a reply button."
                    } else {
                        "Off. Turn this on and the assistant can read notifications " +
                            "when you ask it to. It reads nothing until you ask, and " +
                            "nothing is uploaded anywhere — the model runs on this phone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted) {
                    Button(onClick = { LocalNotificationListenerService.requestConsent(context) }) {
                        Text("Open notification access")
                    }
                }
            }
        }

        Text(
            "Android only lets you grant this from its own Settings screen, so the " +
                "button takes you there. Come back and this page will update.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
