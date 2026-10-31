package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.cloud.ChatGptError
import dev.localintelligence.android.cloud.ChatGptModel
import dev.localintelligence.android.cloud.ChatGptSettings
import dev.localintelligence.android.cloud.ChatGptSignInOutcome
import dev.localintelligence.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===========================================================================
// The ChatGPT entries: the Settings row in Simple mode and the Models card. Both show the same
// state (signed out, signed in and not in use, in use) and call the same actions, so the two
// places cannot disagree. The sign-in wording follows the ChatGPT UI guidelines: "Continue with
// ChatGPT" starts the sign-in, and no logo is drawn.
// ===========================================================================

/** The Simple-mode Settings row for ChatGPT. */
@Composable
fun ChatGptSettingsCard(container: AppContainer) {
    ChatGptCard(container = container, detailed = false)
}

/** The Models screen entry for ChatGPT, with the model picker. */
@Composable
fun ChatGptModelCard(container: AppContainer) {
    ChatGptCard(container = container, detailed = true)
}

@Composable
private fun ChatGptCard(container: AppContainer, detailed: Boolean) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val settingsVersion by container.settings.changes.collectAsState()
    val availability by container.modelAvailability.state.collectAsState()
    val account = remember(settingsVersion) { container.chatGptPlugin.account() }
    val active = remember(availability) { container.chatGptActive }
    val chosen = remember(settingsVersion) { container.chatGptPlugin.chosenModel() }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var showNotice by remember { mutableStateOf(false) }

    fun busyAction(block: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    fun signIn() = busyAction {
        when (val outcome = container.chatGptSignIn.signIn()) {
            is ChatGptSignInOutcome.SignedIn -> {
                container.useChatGpt()
                showNotice = !container.chatGptPlugin.welcomeSeen()
            }
            is ChatGptSignInOutcome.Failed -> message = outcome.message
        }
    }

    fun signOut() = busyAction {
        if (container.chatGptActive) container.useLocalModel()
        val revoked = container.chatGptSignIn.signOut()
        if (!revoked) {
            message = "Signed out here. OpenAI did not confirm the sign-out. You can also disconnect " +
                "the app in ChatGPT settings."
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "ChatGPT",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (account == null) {
                Text(
                    text = "Use your ChatGPT plan for chat. Eligible messages go to OpenAI while it is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(enabled = !busy, onClick = { signIn() }) { Text("Continue with ChatGPT") }
            } else if (active) {
                Text(
                    text = "Using ChatGPT plan. Messages go to OpenAI while ChatGPT is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (detailed) {
                    Text(
                        text = "In use. Use a local model below to stop sending messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    TextButton(onClick = { uriHandler.openUri(ChatGptSettings.USAGE_URL) }) { Text("Manage usage") }
                    TextButton(enabled = !busy, onClick = { busyAction { container.useLocalModel() } }) {
                        Text("Use the phone's model")
                    }
                }
            } else {
                Text(
                    text = "Signed in with ChatGPT. Not in use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    Button(enabled = !busy, onClick = { busyAction { container.useChatGpt() } }) { Text("Use ChatGPT") }
                }
            }
            if (detailed && account != null) {
                Text(
                    text = "Model: " + chosen.ifBlank { "the first model your account lists" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(enabled = !busy, onClick = { picking = true }) { Text("Choose model") }
                }
            }
            if (account != null) {
                Row {
                    TextButton(enabled = !busy, onClick = { signOut() }) { Text("Sign out") }
                }
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (picking) {
        ChatGptModelPicker(container = container, onDone = { picking = false })
    }
    if (showNotice) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("You're using your ChatGPT plan") },
            text = {
                Text(
                    "Eligible AI requests in this app use your ChatGPT plan. " +
                        "You can manage usage in ChatGPT settings.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    container.chatGptPlugin.markWelcomeSeen()
                    showNotice = false
                }) { Text("Got it") }
            },
        )
    }
}

/** Lists the account's models and saves the one picked. Blank means the first listed model. */
@Composable
private fun ChatGptModelPicker(container: AppContainer, onDone: () -> Unit) {
    var models by remember { mutableStateOf<List<ChatGptModel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            models = withContext(Dispatchers.IO) { container.chatGptBackend.listModels() }
        } catch (e: ChatGptError) {
            error = e.userMessage
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Choose a model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val list = models
                when {
                    error != null -> Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    list == null -> Text("Loading models…", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        TextButton(onClick = {
                            container.chatGptPlugin.setChatModel("")
                            onDone()
                        }) { Text("First model the account lists") }
                        list.forEach { model ->
                            TextButton(onClick = {
                                container.chatGptPlugin.setChatModel(model.slug)
                                onDone()
                            }) { Text(model.displayName) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
