package dev.localintelligence.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.R

/**
 * The slim row above the transcript in Simple mode: the way to Settings, at the end of the row.
 *
 * WHY IT IS HERE AND NOT IN THE COMPOSER: a gear beside Send reads as a control for the
 * message, and a Simple user who has never heard of a model does not know what it configures.
 * At the top it is a separate place to go, which is what it is. It has no background of its
 * own, so it sits on the window colour and the transcript scrolls beneath it.
 *
 * The [actions] slot is for more entries at the same end of the row, placed before Settings.
 * Nothing else lives here: the new-chat control belongs to the empty chat.
 */
@Composable
fun SimpleChatTopRow(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        actions()
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.settings),
            )
        }
    }
}
