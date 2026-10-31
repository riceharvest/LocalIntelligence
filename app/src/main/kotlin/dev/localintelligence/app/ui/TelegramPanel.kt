package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.gateway.telegram.TelegramChat
import dev.localintelligence.android.gateway.telegram.TelegramChats
import dev.localintelligence.android.gateway.telegram.TelegramSettings
import dev.localintelligence.app.TelegramReplies
import dev.localintelligence.app.settings.SettingsStore

// ===========================================================================
// The Telegram block on the Settings screen, above the bot token row.
//
// It shows the last problem, the chat waiting for approval, and the linked
// chats, and it owns the "Link this chat" window. Every write goes through
// SettingsStore, so the poller sees an approval on its next message with no
// extra call. Technical mode only, because its rows are.
// ===========================================================================

@Composable
fun TelegramPanel(store: SettingsStore) {
    val revision by store.changes.collectAsState()
    val hasToken = remember(revision) { store.isSecretSet(TelegramSettings.TOKEN) }
    val error = remember(revision) { store.stored(TelegramSettings.LAST_ERROR) }
    val linked = remember(revision) { TelegramChats.linked(store.stored(TelegramSettings.LINKED)) }
    val pending = remember(revision) { TelegramChats.pending(store.stored(TelegramSettings.PENDING)) }
    val linkOpen = remember(revision) {
        System.currentTimeMillis() < (store.stored(TelegramSettings.LINK_OPEN_UNTIL).toLongOrNull() ?: 0L)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Telegram", style = MaterialTheme.typography.titleSmall)
        Text(
            "Create a bot with @BotFather, paste its token below, switch Telegram on, then link your chat. " +
                "Only linked chats get answers. Each message runs on this phone like a scheduled task: " +
                "anything that needs your approval is skipped, and the reply says so.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!hasToken) {
            Text("Paste the bot token below to start.", style = MaterialTheme.typography.bodySmall)
        }
        if (error.isNotBlank()) {
            Text(
                "Last problem: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text("Linked chats", style = MaterialTheme.typography.labelLarge)
        if (linked.isEmpty()) {
            Text("None yet.", style = MaterialTheme.typography.bodySmall)
        }
        linked.forEach { chat ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${chat.name} (${chat.id})",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    store.set(TelegramSettings.LINKED, TelegramChats.encodeList(linked - chat))
                }) { Text("Remove") }
            }
        }

        pending?.let { chat -> PendingChat(store, chat, linked) }

        FilledTonalButton(
            enabled = hasToken,
            onClick = {
                store.set(
                    TelegramSettings.LINK_OPEN_UNTIL,
                    (System.currentTimeMillis() + TelegramChats.LINK_WINDOW_MILLIS).toString(),
                )
            },
        ) { Text(if (linkOpen) "Waiting for /start in Telegram" else "Link this chat") }
        if (linkOpen) {
            Text(
                "Open your bot in Telegram and send /start within five minutes. The chat then appears here for approval.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** A chat that sent /start while the link window was open. Approved only by the owner's tap. */
@Composable
private fun PendingChat(store: SettingsStore, chat: TelegramChat, linked: List<TelegramChat>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${chat.name} (${chat.id}) asked to talk to this phone. Approve only if it is you.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                store.set(TelegramSettings.LINKED, TelegramChats.encodeList(linked.filterNot { it.id == chat.id } + chat))
                store.set(TelegramSettings.PENDING, "")
                store.set(TelegramSettings.LINK_OPEN_UNTIL, "0")
                TelegramReplies.send(store, chat.id, "This chat is linked to LocalIntelligence. Send a message to run it on the phone.")
            }) { Text("Approve") }
            OutlinedButton(onClick = { store.set(TelegramSettings.PENDING, "") }) { Text("Decline") }
        }
    }
}
