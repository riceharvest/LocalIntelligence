package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
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

/**
 * Writes the first empty server address and its token header name ("" for Bearer), so a
 * header left over in that row cannot change how the new server is called. Returns false
 * when all five rows are used.
 */
private fun fillNextEmptyRow(store: SettingsStore, address: String, tokenHeader: String = ""): Boolean {
    val slot = McpSettings.urls.indexOfFirst { store.stored(it).isBlank() }
    if (slot < 0) return false
    store.set(McpSettings.urls[slot], address)
    store.set(McpSettings.headers[slot], tokenHeader)
    return true
}

/** The preset buttons and their one-line hints. Technical mode only, as its rows are. */
@Composable
fun McpAddServerRow(store: SettingsStore) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.add_a_server), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.a_button_fills_the_next_empty_server),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { fillNextEmptyRow(store, ZAPIER_ADDRESS) }) { Text(stringResource(R.string.zapier)) }
            FilledTonalButton(onClick = { fillNextEmptyRow(store, COMPOSIO_ADDRESS, COMPOSIO_TOKEN_HEADER) }) {
                Text(stringResource(R.string.composio))
            }
            // Custom writes nothing: the user types the address into an empty row.
            FilledTonalButton(onClick = { }) { Text(stringResource(R.string.custom)) }
        }
        Text(
            stringResource(R.string.zapier_get_the_token_at_mcp_zapier_com),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.composio_paste_your_composio_api_key),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.custom_type_any_other_server_s_https),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Zapier's documented connection endpoint. The token goes in the Bearer row, not in the address. */
private const val ZAPIER_ADDRESS = "https://mcp.zapier.com/api/v1/connect"

/**
 * Composio's consumer connection endpoint, from its MCP docs. Its API key goes in the
 * x-api-key header, not as a Bearer token, so the preset also sets the header row.
 */
private const val COMPOSIO_ADDRESS = "https://connect.composio.dev/mcp"
private const val COMPOSIO_TOKEN_HEADER = "x-api-key"
