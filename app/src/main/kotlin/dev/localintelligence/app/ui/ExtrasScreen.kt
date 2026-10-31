package dev.localintelligence.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.cloud.CredentialSource
import dev.localintelligence.android.cloud.ExtrasSettings
import dev.localintelligence.app.extras.ExtrasBilling
import dev.localintelligence.app.settings.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Extras screen: what the monthly subscription adds, its Play price, and how to subscribe
 * or manage it. Opened from the Extras row, which is shown only when the backend is set.
 *
 * What leaves the phone is said here in plain words, because Extras sends chat and searches to
 * outside services when it is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtrasScreen(
    store: SettingsStore,
    billing: ExtrasBilling,
    /** Starts Play's purchase sheet for the given activity. */
    onSubscribe: (Activity) -> Unit,
    /** The cloud-chat switch moved: true hands chat to Extras' model, false to the phone's own. */
    onCloudChat: (Boolean) -> Unit = {},
    onBack: () -> Unit,
) {
    val revision by store.changes.collectAsState()
    val price by billing.price.collectAsState()
    val notice by billing.notice.collectAsState()
    val context = LocalContext.current
    val scroll = rememberScrollState()

    LaunchedEffect(Unit) { billing.loadPrice() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extras") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        key(revision) {
            val active = CredentialSource.extrasActive(store)
            val until = store[ExtrasSettings.EXPIRES_AT]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Extras is one monthly subscription from Google Play. It gives this phone a smarter " +
                        "cloud chat model and better web search, with no setup. Your own keys in Technical " +
                        "mode still work the same, and the app stays free without Extras.",
                )
                Text(
                    if (active) "Extras is on until ${shortDate(until)}."
                    else "Extras is off.",
                )
                Text(
                    price?.let { "$it a month, charged by Google Play." }
                        ?: "The price is loading from Google Play.",
                )

                if (!active) {
                    Button(
                        onClick = { findActivity(context)?.let(onSubscribe) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Subscribe") }
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ExtrasBilling.manageUrl(context.packageName)))
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Manage subscription") }

                if (active) {
                    val cloudChat = store[ExtrasSettings.CLOUD_CHAT]
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ExtrasSettings.CLOUD_CHAT.title, modifier = Modifier.weight(1f))
                        Switch(
                            checked = cloudChat,
                            onCheckedChange = { on ->
                                store.set(ExtrasSettings.CLOUD_CHAT, on)
                                onCloudChat(on)
                            },
                        )
                    }
                }

                notice?.let { Text(it) }

                Text(
                    "What leaves the phone while Extras is on: chat messages go to OpenRouter, the " +
                        "model service, and web searches go to Exa. Google Play handles the payment. " +
                        "The Extras server sees only your Play purchase token and a random install id. " +
                        "It never sees your messages or searches. The keys are stored encrypted on this phone.",
                )
            }
        }
    }
}

private fun shortDate(iso: String): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.parse(iso))
}.getOrDefault("the end of this period")

private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
