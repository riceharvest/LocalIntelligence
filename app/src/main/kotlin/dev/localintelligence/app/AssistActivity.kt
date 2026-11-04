package dev.localintelligence.app

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.localintelligence.app.media.LiveDictation
import dev.localintelligence.app.media.PendingAttachment
import dev.localintelligence.app.media.encodeBitmapPicture
import dev.localintelligence.app.ui.AppLockScreen
import dev.localintelligence.app.ui.LocalIntelligenceTheme
import dev.localintelligence.core.settings.CoreSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The compact overlay the assistant shows over the current app.
 *
 * WHY IT FORWARDS INSTEAD OF RUNNING THE CHAT HERE: the chat view model is built
 * inside [MainActivity] around a gateway to the agent service. Building a second
 * one here would duplicate that wiring, so the text goes to MainActivity through
 * the same ACTION_SEND path a share uses, or into hands-free mode when opened
 * from a wake-word detection.
 *
 * Reached from the session (see [AssistantSession]) and from the ASSIST intent.
 * Exported because the system starts it.
 */
class AssistActivity : FragmentActivity() {

    private var dictation: LiveDictation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        WakeWordService.onAssistOverlayActive(true)
        val container = (application as LocalIntelligenceApp).container
        val handsFree = intent?.getBooleanExtra(EXTRA_HANDS_FREE, false) == true
        val silenceMillis = (container.settings[CoreSettings.HANDS_FREE_SILENCE] * 1000).toLong()
        val liveDictation = if (handsFree) LiveDictation(this).also { dictation = it } else null

        // Read once, when the overlay opens. The capture is what the user saw
        // when they invoked the assistant, and it may be replaced while they type.
        val screenshot = AssistantScreenshot.latest()
        val assistantName = container
            .settings[CoreSettings.ASSISTANT_NAME].trim().ifBlank { "LocalIntelligence" }
        setContent {
            LocalIntelligenceTheme {
                // The PIN comes before the overlay, so the hands-free listening and the screen capture
                // in the overlay do not start until the app is unlocked.
                val locked by container.appLock.locked.collectAsStateWithLifecycle()
                val settingsVersion by container.settings.changes.collectAsStateWithLifecycle()
                LaunchedEffect(settingsVersion) { container.appLock.protectRecents(this@AssistActivity) }
                if (locked) {
                    AppLockScreen(appLock = container.appLock)
                    return@LocalIntelligenceTheme
                }
                AssistOverlay(
                    assistantName = assistantName,
                    screenshot = screenshot,
                    handsFree = handsFree,
                    silenceMillis = silenceMillis,
                    dictation = liveDictation,
                    onSend = { text, screen ->
                        // The capture goes into the same holder the chat run takes from,
                        // before the text reaches the chat, so the run sees it with the message.
                        container.pendingAttachments.add(listOfNotNull(screen))
                        if (handsFree) {
                            WakeWordService.onHandsFreeActive(true, this)
                            startActivity(
                                Intent(this, MainActivity::class.java).apply {
                                    putExtra(MainActivity.EXTRA_START_HANDS_FREE, true)
                                    if (text.isNotBlank()) {
                                        putExtra(MainActivity.EXTRA_HANDS_FREE_PROMPT, text)
                                    }
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                },
                            )
                        } else {
                            startActivity(
                                Intent(this, MainActivity::class.java).apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, text)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                },
                            )
                        }
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        dictation?.release()
        dictation = null
        WakeWordService.onAssistOverlayActive(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_HANDS_FREE = "dev.localintelligence.app.extra.ASSIST_HANDS_FREE"
    }
}

@Composable
private fun AssistOverlay(
    assistantName: String,
    screenshot: Bitmap?,
    handsFree: Boolean,
    silenceMillis: Long,
    dictation: LiveDictation?,
    onSend: (text: String, screen: PendingAttachment?) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Off by default: the screen is private, and sending it is a choice made per request.
    var includeScreen by remember { mutableStateOf(false) }
    val ready = text.isNotBlank()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listening = dictation?.listening?.collectAsStateWithLifecycle()?.value ?: false

    val submit: (String) -> Unit = { rawText ->
        val send = rawText.trim()
        // Encoding a full screen is slow enough to stall the overlay, so it runs off the main thread.
        scope.launch {
            val screen = if (includeScreen && screenshot != null) {
                withContext(Dispatchers.Default) { encodeBitmapPicture(screenshot) }
            } else {
                null
            }
            onSend(send, screen)
        }
    }

    LaunchedEffect(handsFree) {
        if (handsFree && dictation != null) {
            val started = dictation.startHandsFree(
                current = "",
                silenceMillis = silenceMillis,
                onText = { text = it },
                onEnd = { spoken, _ ->
                    val candidate = (spoken ?: text).trim()
                    if (candidate.isNotEmpty()) {
                        submit(candidate)
                    }
                },
            )
            if (!started) {
                // Device has no SpeechRecognizer for the overlay; hand off directly to
                // MainActivity's hands-free mode (which also supports audio-clip models).
                submit("")
            }
        } else {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (listening) stringResource(R.string.listening) else stringResource(R.string.ask, assistantName),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (listening) stringResource(R.string.speak_now) else stringResource(R.string.ask_anything)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                minLines = 2,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (ready) submit(text) }),
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
                    Text(stringResource(R.string.include_screen), Modifier.padding(start = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { submit(text) }, enabled = ready) { Text(stringResource(R.string.ask_2)) }
            }
            Text(
                stringResource(R.string.the_answer_opens_in_localintelligence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
