package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.mcp.McpSettings
import dev.localintelligence.app.settings.SettingsStore

// ===========================================================================
// "Add a server" on the Settings screen, above the first MCP server row.
//
// A preset fills the next empty server address. It never fills a token: the user
// pastes their own token, from the service's own dashboard, into that server's
// token row. The address is the service's documented endpoint, not a per-user
// URL, so nothing secret is written by this helper.
// ===========================================================================

private class McpPreset(val label: String, val address: String?, val hint: String)

private val MCP_PRESETS = listOf(
    McpPreset(
        label = "Zapier",
        address = "https://mcp.zapier.com/api/v1/connect",
        hint = "Zapier: at mcp.zapier.com, add a server (choose Other), then Settings, Generate token. " +
            "Paste that token into this server's token row. It is sent as a Bearer token.",
    ),
    McpPreset(
        label = "Composio",
        address = "https://connect.composio.dev/mcp",
        hint = "Composio asks for your key in an x-api-key header. This app sends only a Bearer token, " +
            "so Composio will not connect yet.",
    ),
    McpPreset(
        label = "Custom",
        address = null,
        hint = "Any other MCP server: type its https:// address into an empty server row, and its " +
            "bearer token into that row's token field, if it has one.",
    ),
)

/** The preset buttons, with the hint of the one last picked. Technical mode only, as its rows are. */
@Composable
fun McpAddServerRow(store: SettingsStore) {
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add a server", style = MaterialTheme.typography.titleSmall)
        Text(
            "Pick a service to fill the next empty server address. You still paste your own token.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MCP_PRESETS.forEachIndexed { index, preset ->
                FilledTonalButton(onClick = {
                    picked = index
                    message = addPreset(store, preset)
                }) { Text(preset.label) }
            }
        }
        if (picked >= 0) {
            Text(MCP_PRESETS[picked].hint, style = MaterialTheme.typography.bodySmall)
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Fills the first empty server address with the preset. Returns what to tell the user. */
private fun addPreset(store: SettingsStore, preset: McpPreset): String {
    val address = preset.address ?: return "Type the address into an empty server row below."
    val slot = McpSettings.urls.indexOfFirst { store.stored(it).isBlank() }
    if (slot < 0) return "All five server rows are in use. Clear one first."
    store.set(McpSettings.urls[slot], address)
    return "Filled server ${slot + 1}. Paste its token into the token row, then send a message."
}
