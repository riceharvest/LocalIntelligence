package dev.localintelligence.app.ui

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import dev.localintelligence.android.data.DesktopDao
import dev.localintelligence.app.R
import dev.localintelligence.app.settings.SettingsStore
import dev.localintelligence.core.desktop.DesktopAgent
import dev.localintelligence.core.desktop.DesktopKeys
import dev.localintelligence.core.desktop.DesktopRules

/**
 * My desktop: the coding agents on the user's own computer, read and answered over Tailscale.
 * Full build only (the route and the Settings row are gated in MainActivity and SettingsScreen).
 *
 * WHY no approval buttons on cached text: a button press answers the question on the desktop now, and the
 * saved text may be old. The screen shows the saved text with the buttons off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(
    settings: SettingsStore,
    dao: DesktopDao,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val vm = remember(settings, dao) { DesktopViewModel(settings, dao, scope) }
    val state by vm.state.collectAsState()

    LaunchedEffect(vm) { vm.start() }

    // Sends waiting messages when the phone gets a network back. The callback is removed when the screen closes.
    val context = LocalContext.current
    DisposableEffect(vm) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                vm.onNetworkAvailable()
            }
        }
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { manager?.unregisterNetworkCallback(callback) } }
    }

    val selected = state.selected
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: stringResource(R.string.desktop_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (selected != null) vm.close() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        if (selected == null) {
            AgentList(state, padding, vm, onOpenSettings)
        } else {
            AgentView(state, selected, padding, vm)
        }
    }
}

@Composable
private fun AgentList(
    state: DesktopUiState,
    padding: androidx.compose.foundation.layout.PaddingValues,
    vm: DesktopViewModel,
    onOpenSettings: () -> Unit,
) {
    var address by remember(state.serveUrl) { mutableStateOf(state.serveUrl) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.desktop_intro), style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.desktop_address_label)) },
            supportingText = { Text(stringResource(R.string.desktop_address_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.saveAddress(address) }) { Text(stringResource(R.string.desktop_save)) }
            TextButton(onClick = { vm.refresh() }) { Text(stringResource(R.string.desktop_refresh)) }
        }

        state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        if (state.queued > 0) {
            Text(stringResource(R.string.desktop_queued_count, state.queued), style = MaterialTheme.typography.bodySmall)
        }
        state.notice?.let { Text(noticeText(it), style = MaterialTheme.typography.bodySmall) }

        Text(stringResource(R.string.desktop_agents_title), style = MaterialTheme.typography.titleMedium)
        val agents = state.agents
        if (agents != null && agents.isEmpty()) {
            Text(stringResource(R.string.desktop_no_agents))
        }
        agents.orEmpty().forEach { agent ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.open(agent) }
                    .padding(vertical = 2.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(agent.name, style = MaterialTheme.typography.titleSmall)
                    Text(statusText(agent.status), style = MaterialTheme.typography.bodySmall)
                    if (agent.cwd.isNotBlank()) {
                        Text(agent.cwd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.desktop_ask_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.desktop_ask_help), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.desktop_open_settings)) }
            }
        }
    }
}

@Composable
private fun AgentView(
    state: DesktopUiState,
    agent: DesktopAgent,
    padding: androidx.compose.foundation.layout.PaddingValues,
    vm: DesktopViewModel,
) {
    val live = state.liveText != null
    val shown = state.liveText ?: state.cachedText.orEmpty()
    val keysOn = DesktopRules.keysAllowed(state.online, live)
    var message by remember(agent.target) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (live) stringResource(R.string.desktop_live) else stringResource(R.string.desktop_cached),
            style = MaterialTheme.typography.labelMedium,
        )
        state.error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(noticeText(it), style = MaterialTheme.typography.bodySmall) }
        if (DesktopRules.isBlocked(agent.status)) {
            Text(stringResource(R.string.desktop_status_blocked), style = MaterialTheme.typography.bodySmall)
        }

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
                Text(shown, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(stringResource(R.string.desktop_answer_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.desktop_answer_help), style = MaterialTheme.typography.bodySmall)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (DesktopKeys.APPROVALS + DesktopKeys.NAVIGATION).forEach { key ->
                FilledTonalButton(onClick = { vm.key(key) }, enabled = keysOn) { Text(keyLabel(key)) }
            }
        }

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text(stringResource(R.string.desktop_message_hint)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6,
        )
        if (state.queuedForSelected.isNotEmpty()) {
            Text(stringResource(R.string.desktop_queued_title), style = MaterialTheme.typography.titleSmall)
            state.queuedForSelected.forEach { queued ->
                Text(queued, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            FilledTonalButton(
                onClick = {
                    vm.send(message)
                    message = ""
                },
                enabled = message.isNotBlank(),
            ) { Text(stringResource(R.string.desktop_send)) }
        }
    }
}

@Composable
private fun statusText(status: String): String = stringResource(
    when (status) {
        "idle" -> R.string.desktop_status_idle
        "working" -> R.string.desktop_status_working
        "blocked" -> R.string.desktop_status_blocked
        "done" -> R.string.desktop_status_done
        else -> R.string.desktop_status_unknown
    },
)

@Composable
private fun keyLabel(key: String): String = when (key) {
    "enter" -> stringResource(R.string.desktop_key_enter)
    "esc" -> stringResource(R.string.desktop_key_esc)
    "up" -> stringResource(R.string.desktop_key_up)
    "down" -> stringResource(R.string.desktop_key_down)
    else -> key
}

@Composable
private fun errorText(error: DesktopError): String = stringResource(
    when (error) {
        DesktopError.NOT_SET -> R.string.desktop_not_set
        DesktopError.INVALID_ADDRESS -> R.string.desktop_invalid_address
        DesktopError.UNREACHABLE -> R.string.desktop_unreachable
        DesktopError.REFUSED -> R.string.desktop_refused
        DesktopError.BLOCKED -> R.string.desktop_blocked
        DesktopError.STALE -> R.string.desktop_stale
        DesktopError.NOT_FOUND -> R.string.desktop_not_found
        DesktopError.MESSAGE_INVALID -> R.string.desktop_message_invalid
        DesktopError.QUEUE_FULL -> R.string.desktop_queue_full
    },
)

@Composable
private fun noticeText(notice: DesktopNotice): String = stringResource(
    when (notice) {
        DesktopNotice.QUEUED -> R.string.desktop_queued
        DesktopNotice.QUEUE_SENT -> R.string.desktop_queue_sent
    },
)
