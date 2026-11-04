package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
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
        Text(stringResource(R.string.telegram), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.create_a_bot_with_botfather_paste_its),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!hasToken) {
            Text(stringResource(R.string.paste_the_bot_token_below_to_start), style = MaterialTheme.typography.bodySmall)
        }
        if (error.isNotBlank()) {
            Text(
                stringResource(R.string.last_problem, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(stringResource(R.string.linked_chats), style = MaterialTheme.typography.labelLarge)
        if (linked.isEmpty()) {
            Text(stringResource(R.string.none_yet), style = MaterialTheme.typography.bodySmall)
        }
        linked.forEach { chat ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.telegram_chat_label, chat.name, chat.id),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    store.set(TelegramSettings.LINKED, TelegramChats.encodeList(linked - chat))
                }) { Text(stringResource(R.string.remove)) }
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
        ) { Text(if (linkOpen) stringResource(R.string.waiting_for_start_in_telegram) else stringResource(R.string.link_this_chat)) }
        if (linkOpen) {
            Text(
                stringResource(R.string.open_your_bot_in_telegram_and_send),
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
            stringResource(R.string.asked_to_talk_to_this_phone_approve, chat.name, chat.id),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                store.set(TelegramSettings.LINKED, TelegramChats.encodeList(linked.filterNot { it.id == chat.id } + chat))
                store.set(TelegramSettings.PENDING, "")
                store.set(TelegramSettings.LINK_OPEN_UNTIL, "0")
                TelegramReplies.send(store, chat.id, "This chat is linked to LocalIntelligence. Send a message to run it on the phone.")
            }) { Text(stringResource(R.string.approve)) }
            OutlinedButton(onClick = { store.set(TelegramSettings.PENDING, "") }) { Text(stringResource(R.string.decline)) }
        }
    }
}
