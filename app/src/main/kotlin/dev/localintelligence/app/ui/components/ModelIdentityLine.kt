package dev.localintelligence.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.ModelAvailability

/**
 * The second line of the chat's app bar: which model this conversation is
 * talking to, and how to change it.
 *
 * ## WHY THE APP BAR AND NOT UNDER THE COMPOSER
 *
 * Two reasons, one of them about the thumb. It sits at the top, so it is out of
 * the way of the one-handed reach arc the composer now occupies, and it does not
 * compete with the SEND button for the bottom of the screen. The other is that
 * it is *always on screen*: it does not scroll away with the transcript, and it
 * does not disappear when the keyboard comes up, which is exactly when a user
 * is deciding whether to trust the answer they are about to read.
 *
 * ## WHY IT IS A BUTTON
 *
 * Tapping it opens the model screen. "Which model is this" and "use a different
 * one" are the same question asked by two different people — the one who wants
 * to know and the one who wants to change it — so they are one target.
 *
 * ## WHAT IT SAYS WHEN THERE IS NO NAME
 *
 * It never renders blank and it never invents a name. With no identity wired
 * through yet, `Ready` renders as an explicit "name not reported" rather than
 * the model's absence or a guess, because a blank line would be read as "there
 * is no model" and this app's whole standard is that a state nobody can see is a
 * state nobody can trust.
 */
@Composable
fun ModelIdentityLine(
    availability: ModelAvailability,
    identity: ChatModelIdentity?,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when {
        identity != null && availability is ModelAvailability.Ready ->
            identity.quantType?.let { "${identity.displayName} · $it" } ?: identity.displayName

        identity != null -> identity.displayName

        availability is ModelAvailability.Ready ->
            "Model ready · name not reported"

        availability is ModelAvailability.Failed ->
            availability.reason

        else -> "No model imported"
    }

    val color = when (availability) {
        is ModelAvailability.Ready -> MaterialTheme.colorScheme.onSurfaceVariant
        is ModelAvailability.Failed -> MaterialTheme.colorScheme.error
        ModelAvailability.None -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "LocalIntelligence",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                // A whole-line tap target rather than an icon: the target a user
                // is aiming for here is a sentence they are reading, not a glyph.
                .fillMaxWidth()
                .clickable(onClick = onOpenModels)
                .padding(vertical = 2.dp)
                // One merged announcement. Read linearly, this is a label and a
                // link; announced as two fragments it is a label and an
                // unexplained button.
                .semantics(mergeDescendants = true) {
                    contentDescription = "Model: $text. Opens the model screen."
                    onClick(label = "Change model") { onOpenModels(); true }
                },
        )
    }
}
