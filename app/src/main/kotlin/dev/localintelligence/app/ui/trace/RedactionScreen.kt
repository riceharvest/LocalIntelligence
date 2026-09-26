package dev.localintelligence.app.ui.trace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.security.RedactionPolicy
import dev.localintelligence.core.tool.redaction.SecretCategory

/**
 * What the secret filter covers, in the user's terms.
 *
 * ## WHY THIS SCREEN EXISTS AT ALL
 *
 * A redaction filter nobody can see is indistinguishable from no filter, and
 * worse — it is a claim made in a changelog rather than in the product. If the
 * user cannot find out what is being filtered, they cannot decide whether to
 * paste a seed phrase into the chat, and the correct decision depends entirely
 * on knowing the answer.
 *
 * ## WHY IT IS NOT A SETTINGS TOGGLE
 *
 * A switch labelled "protect my secrets" that can be turned off is a promise
 * the app cannot keep when it is on and actively breaks when it is off: with
 * the filter off, a pasted recovery phrase goes straight into the model
 * context with nothing on screen saying so. The honest control is not a
 * control. So there is no toggle, and the screen says the filter is always on
 * rather than implying a choice that does not exist.
 *
 * ## THE HONESTY RULE FOR THIS FILE
 *
 * Every string on this screen is a statement about what the filter does, and
 * none of them is a statement about whether the user is safe. The last section
 * is the one that matters and it is deliberately the longest: the list of
 * things this does NOT cover is the actual content of the screen, because a
 * user who knows the limits can use the feature correctly and a user who does
 * not will paste something irreversible on the strength of a green tick.
 *
 * Nothing here claims a detection rate, a false-positive rate, or a security
 * guarantee. There is no measurement of any of those in this repository, and a
 * number invented here would be the exact kind of claim this app should not
 * make.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("What is filtered") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AlwaysOnBanner()

            Section(title = "What it does") {
                Text(RedactionPolicy.SUMMARY, style = MaterialTheme.typography.bodyMedium)
            }

            Section(title = "What it looks for") {
                Text(
                    "Each of these is matched on a shape, not guessed at. " +
                        "The label is the whole of the match — no part of the " +
                        "original value is kept anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Read from the redactor rather than listed here, so a pattern
                // added to :core appears on this screen without a second edit —
                // and, more importantly, so this screen cannot advertise a
                // category that is not actually implemented.
                for (category in RedactionPolicy.categories) {
                    CategoryCard(category)
                }
            }

            Section(title = "What it does not do") {
                Text(
                    HONEST_LIMITS,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * The filter is not optional, and this says so without offering a switch.
 *
 * The icon and the colour are the affordance: this is a statement about the
 * app's behaviour, not a control the user is being asked to operate.
 */
@Composable
private fun AlwaysOnBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Shield, contentDescription = null)
            Column {
                Text("Always on", style = MaterialTheme.typography.titleSmall)
                Text(
                    "There is no setting to turn this off, because a filter " +
                        "this good is worth more than the choice.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** One detected category, with the shape and the confidence, in that order. */
@Composable
private fun CategoryCard(category: SecretCategory) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(category.title, style = MaterialTheme.typography.titleSmall)
            Text(
                category.explanation,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                category.shape,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The one line that tells the user how much to trust the row above
            // it. A structural match is matched on evidence; a contextual one is
            // a judgement call that can miss.
            Text(
                category.confidence,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/**
 * The section the screen exists for.
 *
 * Written to be read by someone who is about to paste something they cannot
 * get back. Every clause names a case that gets through, because the failure
 * this guards against is a user who believes the filter is complete.
 */
private val HONEST_LIMITS = """
This is a best-effort filter, not a guarantee.

It does not cover:

• Text you type or paste into the chat box. If you paste a password into a
  message, the model sees it and this filter does not run on it. This is
  deliberate — it is your own text, and you can see and delete it — but it is
  the most likely way a secret gets into a conversation.

• A secret with no recognisable shape. A password written out as ordinary
  words, a photo of a password, a secret in a script this filter does not
  recognise. Anything that does not match one of the shapes above passes
  through untouched.

• A one-time code on its own. Codes are only caught next to a word like
  "code" or "OTP", because a bare six-digit number is also a price, a
  timestamp and a line of a diff. Copy a code to the clipboard by itself and
  ask the assistant to read it, and it will not be filtered.

• What the model writes back. If the model repeats a secret in its answer,
  that answer is shown as the model produced it.

• What the model works out on its own. Removing strings from what it reads
  does not remove meaning from what it reads. Given a table of partly masked
  account numbers, it can still reason about the rest.

• Anything already saved. Conversations and memories recorded by an earlier
  version of the app are not rewritten. There is no cleanup pass over your
  history.

This filter runs when a tool reads something — the clipboard, a file you hand
it, a page it fetches. It cannot protect a secret you never handed to the app
in the first place.
""".trimIndent()
