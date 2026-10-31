package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.mcp.McpSettings
import dev.localintelligence.app.settings.SettingsStore

// ===========================================================================
// "Add a server" on the Settings screen, above the first MCP server row.
//
// A preset fills the next empty server address with the service's documented
// endpoint. It never fills a token: the user pastes their own token into that
// server's token row. Nothing is remembered here: a write recomposes the whole
// settings block, so every hint is static text, shown at all times.
// ===========================================================================

/** Writes the first empty server address. Returns false when all five rows are used. */
private fun fillNextEmptyRow(store: SettingsStore, address: String): Boolean {
    val slot = McpSettings.urls.indexOfFirst { store.stored(it).isBlank() }
    if (slot < 0) return false
    store.set(McpSettings.urls[slot], address)
    return true
}

/** The preset buttons and their one-line hints. Technical mode only, as its rows are. */
@Composable
fun McpAddServerRow(store: SettingsStore) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add a server", style = MaterialTheme.typography.titleSmall)
        Text(
            "A button fills the next empty server address. You still paste your own token.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { fillNextEmptyRow(store, ZAPIER_ADDRESS) }) { Text("Zapier") }
            // Custom writes nothing: the user types the address into an empty row.
            FilledTonalButton(onClick = { }) { Text("Custom") }
        }
        Text(
            "Zapier: get the token at mcp.zapier.com (Add MCP server, Other, Settings, Generate token).",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Composio: not supported yet. It needs an x-api-key header, which this app does not send.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Custom: type any other server's https:// address into an empty row.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Zapier's documented connection endpoint. The token goes in the Bearer row, not in the address. */
private const val ZAPIER_ADDRESS = "https://mcp.zapier.com/api/v1/connect"
