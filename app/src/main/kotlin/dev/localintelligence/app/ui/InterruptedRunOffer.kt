package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.R

/**
 * The one line offered at launch after the process died during a foreground run. Continue
 * resumes that run from the stored conversation; Dismiss withdraws the offer and its mark.
 *
 * WHY A ROW ABOVE THE COMPOSER: it is the next thing the user can act on, and it is gone the
 * moment they send a message or choose either button.
 */
@Composable
fun InterruptedRunOffer(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = stringResource(R.string.interrupted_run_offer),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.interrupted_run_dismiss)) }
            TextButton(onClick = onContinue) { Text(stringResource(R.string.interrupted_run_continue)) }
        }
    }
}
