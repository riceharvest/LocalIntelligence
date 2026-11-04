package dev.localintelligence.app.ui

import androidx.compose.ui.res.stringResource
import dev.localintelligence.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ===========================================================================
// The disclosure shown before the person is sent to Android's Accessibility settings
// (docs/adr/0003-screen-control-approval-per-app.md). It is shown for the full build's screen control only,
// from the setup card, and it is the only way to agree: nothing else writes the consent setting.
//
// It is a full-screen dialog rather than a screen, so the card keeps its place in Settings and
// "Not now" simply closes it. This file is in `:app` main because the card that opens it is in
// main; the Play build never shows the card (the plugin is not registered there).
// ===========================================================================

/**
 * The full-screen disclosure. [onAgree] is called before the Accessibility settings open; [onNotNow]
 * closes the screen and leaves screen control off.
 */
@Composable
fun ScreenControlConsentDialog(
    onAgree: () -> Unit,
    onNotNow: () -> Unit,
) {
    Dialog(
        onDismissRequest = onNotNow,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.let_the_assistant_use_other_apps), style = MaterialTheme.typography.headlineSmall)

                Text(
                    stringResource(R.string.screen_control_lets_the_assistant_read),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(stringResource(R.string.what_it_reads), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.only_the_app_in_front_during_a_request),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(stringResource(R.string.what_it_does), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.it_can_tap_type_scroll_and_press_back),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(stringResource(R.string.what_it_never_does), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.it_never_runs_unattended_or_in_the),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(stringResource(R.string.where_the_data_goes), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.nothing_leaves_the_phone_because_of),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(stringResource(R.string.how_to_turn_it_off), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.switch_off_the_localintelligence),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(stringResource(R.string.if_android_blocks_the_switch), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.some_installs_from_outside_google_play),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End),
                ) {
                    TextButton(onClick = onNotNow) { Text(stringResource(R.string.not_now)) }
                    Button(onClick = onAgree) { Text(stringResource(R.string.agree_and_open_settings)) }
                }
            }
        }
    }
}
