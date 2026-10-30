package dev.localintelligence.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.media.PendingAttachment
import dev.localintelligence.app.media.encodeBitmapPicture
import dev.localintelligence.app.ui.LocalIntelligenceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The compact overlay the assistant shows over the current app.
 *
 * WHY IT FORWARDS INSTEAD OF RUNNING THE CHAT HERE: the chat view model is built
 * inside [MainActivity] around a gateway to the agent service. Building a second
 * one here would duplicate that wiring, so the text goes to MainActivity through
 * the same ACTION_SEND path a share uses, exactly as [ProcessTextActivity] does.
 * The answer therefore appears in the main chat, not in this overlay.
 *
 * Reached from the session (see [AssistantSession]) and from the ASSIST intent.
 * Exported because the system starts it.
 */
class AssistActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        // Read once, when the overlay opens. The capture is what the user saw
        // when they invoked the assistant, and it may be replaced while they type.
        val screenshot = AssistantScreenshot.latest()
        setContent {
            LocalIntelligenceTheme {
                AssistOverlay(
                    screenshot = screenshot,
                    onSend = { text, screen ->
                        // The capture goes into the same holder the chat run takes from,
                        // before the text reaches the chat, so the run sees it with the message.
                        (application as LocalIntelligenceApp).container.pendingAttachments.add(
                            listOfNotNull(screen),
                        )
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, text)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            },
                        )
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@Composable
private fun AssistOverlay(
    screenshot: Bitmap?,
    onSend: (text: String, screen: PendingAttachment?) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Off by default: the screen is private, and sending it is a choice made per request.
    var includeScreen by remember { mutableStateOf(false) }
    val ready = text.isNotBlank()
    val scope = rememberCoroutineScope()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Ask LocalIntelligence", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ask anything") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            if (screenshot != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = includeScreen,
                            role = Role.Checkbox,
                            onValueChange = { includeScreen = it },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeScreen, onCheckedChange = null)
                    Text("Include screen", Modifier.padding(start = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(
                    onClick = {
                        val send = text.trim()
                        // Encoding a full screen is slow enough to stall the overlay, so it runs off the main thread.
                        scope.launch {
                            val screen = if (includeScreen && screenshot != null) {
                                withContext(Dispatchers.Default) { encodeBitmapPicture(screenshot) }
                            } else {
                                null
                            }
                            onSend(send, screen)
                        }
                    },
                    enabled = ready,
                ) { Text("Send") }
            }
        }
    }
}
