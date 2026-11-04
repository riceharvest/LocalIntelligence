package dev.localintelligence.app.ui.components

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.SetupState
import dev.localintelligence.core.hub.formatBytes
import java.util.Locale

/**
 * An inline card for downloading the document search model with one tap.
 *
 * Shows "Better search · 0.31 GB", one download button, inline progress with Cancel,
 * and "Ready" once the model file is on disk and the embedder sees it.
 * In Technical mode, keeps a small "Choose in Hub" text link.
 * When [onDismiss] is provided (e.g. in chat), a "Dismiss" action is offered.
 */
@Composable
fun SearchModelCard(
    state: SetupState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    technical: Boolean = false,
    onOpenHub: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sizeLabel = remember(state) {
        val bytes = when (state) {
            is SetupState.Idle -> state.sizeBytes
            is SetupState.Downloading -> state.totalBytes
            else -> 0L
        }
        if (bytes > 0L) formatGgufSize(bytes) else "0.31 GB"
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state is SetupState.Ready) "Better search" else "Better search · $sizeLabel",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state is SetupState.Ready) {
                    Text(
                        text = "Ready",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "Find answers even when your words differ from the document.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                is SetupState.Idle -> {
                    val metered = remember { isOnMeteredNetwork(context) }
                    if (metered) {
                        Text(
                            text = "You're on mobile data. Wi-Fi is better for a download this size.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val label = when {
                        state.onDisk -> "Finish setup"
                        state.keptBytes > 0 -> "Continue download · ${percentOf(state.keptBytes, state.sizeBytes)}%"
                        else -> "Get search model"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = onDownload,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(label)
                        }
                        if (technical && onOpenHub != null) {
                            TextButton(
                                onClick = onOpenHub,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Choose in Hub")
                            }
                        }
                        if (onDismiss != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
                }

                is SetupState.Downloading -> {
                    val fraction = if (state.totalBytes > 0) {
                        (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val percent = (fraction * 100).toInt()
                    val milestone = percent / 10
                    val announced = remember(milestone) { "Downloading search model, ${milestone * 10} percent" }

                    Text(
                        text = "Downloading · $percent%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = announced
                        },
                    )
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${formatBytes(state.bytes)} of ${formatBytes(state.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Cancel")
                        }
                    }
                }

                SetupState.Preparing -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Almost ready...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }

                SetupState.Ready -> {
                    if (technical && onOpenHub != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = onOpenHub,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Choose in Hub")
                            }
                        }
                    }
                }

                is SetupState.Failed -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Try again")
                        }
                        if (technical && onOpenHub != null) {
                            TextButton(
                                onClick = onOpenHub,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Choose in Hub")
                            }
                        }
                        if (onDismiss != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatGgufSize(bytes: Long): String {
    val gb = bytes.toDouble() / 1_000_000_000.0
    return if (gb < 1.0) String.format(Locale.US, "%.2f GB", gb) else String.format(Locale.US, "%.1f GB", gb)
}

private fun percentOf(part: Long, whole: Long): Int =
    if (whole <= 0L) 0 else ((part.toDouble() / whole.toDouble()) * 100).toInt().coerceIn(0, 100)

private fun isOnMeteredNetwork(context: Context): Boolean = try {
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true
} catch (_: SecurityException) {
    false
}
