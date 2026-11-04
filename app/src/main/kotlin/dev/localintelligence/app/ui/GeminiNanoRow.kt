package dev.localintelligence.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.inference.GeminiNanoBackend
import dev.localintelligence.app.AppContainer
import dev.localintelligence.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Before the first AICore check answers. Never a status the library returns. */
private const val CHECKING = -1

/**
 * The Gemini Nano row on the Models screen. Beta, Technical mode only.
 *
 * WHY IT ASKS AICORE WHEN IT IS SHOWN: the model belongs to the system, not to a file this app
 * holds, so the only honest question is whether this phone has it now. The check runs once when
 * the row is composed, never at app start. A phone that reports UNAVAILABLE gets no row.
 *
 * The model is never loaded here on its own. Only the Use button makes it the one answering,
 * and a download starts only when the user taps Download.
 */
@Composable
fun GeminiNanoCard(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val availability by container.modelAvailability.state.collectAsState()
    val active = remember(availability) { container.geminiNanoActive }
    val context = LocalContext.current

    var status by remember { mutableIntStateOf(CHECKING) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { container.geminiNanoBackend.status() }
    }

    if (status == CHECKING || status == GeminiNanoBackend.STATUS_UNAVAILABLE) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.gemini_nano_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.gemini_nano_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                active -> Text(
                    text = stringResource(R.string.gemini_nano_in_use),
                    style = MaterialTheme.typography.bodySmall,
                )
                status == GeminiNanoBackend.STATUS_DOWNLOADING -> Text(
                    text = stringResource(R.string.gemini_nano_downloading),
                    style = MaterialTheme.typography.bodySmall,
                )
                status == GeminiNanoBackend.STATUS_DOWNLOADABLE -> Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val done = withContext(Dispatchers.IO) { container.geminiNanoBackend.download() }
                            status = withContext(Dispatchers.IO) { container.geminiNanoBackend.status() }
                            if (!done) message = context.getString(R.string.gemini_nano_download_failed)
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.gemini_nano_download)) }
                status == GeminiNanoBackend.STATUS_AVAILABLE -> Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            message = withContext(Dispatchers.IO) { container.useGeminiNano() }
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.gemini_nano_use)) }
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
