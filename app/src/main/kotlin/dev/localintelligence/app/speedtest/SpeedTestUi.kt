package dev.localintelligence.app.speedtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The button, its progress while it runs, and the reason when it did not start.
 *
 * Shared by the Models screen and the Settings screen: both read the same
 * [SpeedTestRunner], so a run started in one is visible in the other.
 */
@Composable
fun SpeedTestButton(runner: SpeedTestRunner, modifier: Modifier = Modifier) {
    val status by runner.status.collectAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val running = status as? SpeedTestStatus.Running
        if (running != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Speed test, pass ${running.pass} of ${running.passes}…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = runner::cancel) { Text("Cancel") }
            }
        } else {
            FilledTonalButton(onClick = runner::start) { Text("Speed test") }
        }
        when (val s = status) {
            is SpeedTestStatus.Refused -> Text(
                text = s.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is SpeedTestStatus.Failed -> Text(
                text = "Speed test failed: ${s.reason}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
    }
}

/**
 * A finished result: the summary line, and the date it was measured on this phone.
 *
 * The method is printed because a number without it cannot be compared with another:
 * two passes of a 256-token prompt, 64 generated, and the median (the mean, for two).
 */
@Composable
fun SpeedTestResultBlock(result: SpeedTestResult, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = result.summary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = buildString {
                append("${result.measuredOn()}. Median of 2 runs: 256-token prompt, 64 tokens generated.")
                if (!result.countsExact) append(" Token counts are estimated by this backend.")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
