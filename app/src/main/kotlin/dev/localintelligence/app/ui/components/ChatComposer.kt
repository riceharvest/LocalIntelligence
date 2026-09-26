package dev.localintelligence.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.ui.PermissionActivity

/**
 * The composer: what you type, and the one button that matters.
 *
 * ## THE ONE-HANDED PROBLEM THIS EXISTS TO SOLVE
 *
 * The previous layout was a single row — `OutlinedTextField(weight 1f)` followed
 * by a 48dp `IconButton` — which puts the send target hard against the right
 * edge of the screen. On a 1080px-wide phone at density 2.75 that button spans
 * x = 915..1047, centred at x = 981: **91% of the way across**, inside the outer
 * 10% of the display, which is the strip a hand covers when the phone is held in
 * one hand. The control was present and correctly sized and still effectively
 * unreachable, which is the specific failure the project treats as the worst
 * kind: a feature that exists and cannot be used.
 *
 * The fix is geometric, not cosmetic. The text field takes a full row of its
 * own and the send control takes the row below it across the full width, so:
 *
 *  - its centre moves to x = 540, i.e. **50% of the screen width** — reachable
 *    by a right thumb, and equally by a left one, which matters because the app
 *    has no handedness setting and cannot acquire one honestly;
 *  - the target grows from 48x48dp to the full composer width at 56dp tall, so
 *    the user no longer has to *aim* at anything. Landing anywhere on the strip
 *    sends.
 *
 * ## WHY THE ICON BUTTONS ARE NOT IN THE MIDDLE OF THIS
 *
 * There is no mic, no attach and no camera button here, and that is deliberate
 * rather than an omission. A control that cannot be reached one-handed is worse
 * than no control: it advertises a capability, takes a tap's worth of thumb to
 * miss, and teaches the user the app is not listening. The one secondary control
 * that does exist is the permissions entry, on the opposite edge from SEND, and
 * it is a settings destination rather than something you aim at mid-conversation.
 *
 * ## WHY THE INSETS ARE HERE AND NOT ON THE SCAFFOLD
 *
 * `Scaffold` hands its content a bottom padding equal to the measured height of
 * the bottom bar and states that it expects the bottom bar to handle its own
 * insets. Applying `imePadding` to the Scaffold instead shrinks the whole
 * scaffold, which leaves the strip between the composer and the keyboard painted
 * with the window background instead of the composer's own surface — and, on a
 * build where the window is already resized for the keyboard, risks accounting
 * for the same inset twice. Putting the inset on the composer's own content
 * means the composer's surface grows to fill the gap, the Scaffold measures that
 * grown height, and the transcript above shrinks by exactly the same amount. One
 * mechanism, one inset, no window in which the two can disagree.
 *
 * `safeDrawing` rather than `ime`, because the bottom inset has to be right in
 * three situations and they are not the same number: the keyboard up (the IME's
 * height), the keyboard down (the navigation bar, which the composer would
 * otherwise sit underneath), and a fold or a landscape gesture bar (a cutout or
 * a mandatory-system-gesture region). `safeDrawing` is a union, so it takes the
 * largest of those rather than their sum.
 */
@Composable
fun ChatComposer(
    input: String,
    busy: Boolean,
    blockedReason: String?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /**
     * Single row instead of two. Set by the caller when the window is too short
     * for a stacked composer to leave a transcript.
     *
     * WHY THE CALLER DECIDES: the decision needs the window height, and reading
     * that is the screen's job, not the composer's. Passing it in also keeps
     * this composable free of `LocalConfiguration`, so it can be dropped into
     * any host without inheriting a layout assumption.
     */
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val canSend = input.isNotBlank() && blockedReason == null && !busy

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.windowInsetsPadding(
                // The union with `ime` is belt-and-braces and costs nothing:
                // `safeDrawing` already contains the IME on API 30+, so the
                // union is the same set there, and on an older platform — where
                // Compose reports no IME inset and the window is resized by
                // `adjustResize` instead — it is the same zero. Stating it
                // means this line does not depend on the reader knowing that
                // `safeDrawing` includes the keyboard.
                WindowInsets.safeDrawing
                    .union(WindowInsets.ime)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
        ) {
            if (blockedReason != null) {
                // Announced as it appears: without this, a TalkBack user
                // encounters a disabled button with no explanation of why.
                Text(
                    text = blockedReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (compact) {
                // ONE ROW. A landscape phone held with the keyboard up leaves
                // roughly 200dp of window, and a stacked composer plus the app
                // bar is about that much on its own — the transcript would be
                // reduced to a few pixels. Un-stacking costs the centred send
                // target, which is the right thing to trade away: the screen is
                // now ~890dp wide, so the right edge is roughly twice as far from
                // a thumb as it is in portrait, and a landscape phone is held in
                // two hands anyway.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ComposerField(
                        input = input,
                        onInputChange = onInputChange,
                        canSend = canSend,
                        onSend = onSend,
                        modifier = Modifier.weight(1f),
                    )
                    SendOrStopButton(
                        busy = busy,
                        canSend = canSend,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            } else {
                ComposerField(
                    input = input,
                    onInputChange = onInputChange,
                    canSend = canSend,
                    onSend = onSend,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionEntry()

                    // One slot, two states, same geometry. The button never moves
                    // when a run starts or stops, so there is nothing to re-find
                    // with a thumb at the exact moment a run is in flight.
                    SendOrStopButton(
                        busy = busy,
                        canSend = canSend,
                        onSend = onSend,
                        onStop = onStop,
                        // Takes the rest of the row, so the target spans from
                        // just past the permissions entry to the right padding.
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The text field, shared by the stacked and the single-row composer so the two
 * layouts cannot drift apart in label, placeholder or keyboard behaviour.
 */
@Composable
private fun ComposerField(
    input: String,
    onInputChange: (String) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = input,
        onValueChange = onInputChange,
        modifier = modifier,
        // A label, not just a placeholder: a placeholder disappears the moment
        // the user types, which leaves a screen-reader user with an unlabelled
        // edit field.
        label = { Text("Message") },
        placeholder = { Text("Ask for something…") },
        // Multi-line on purpose. `maxLines > 1` makes the platform coerce the
        // IME action away from Send, so on a phone keyboard the return key
        // inserts a newline and does NOT send. That is the right trade here: a
        // local agent is routinely given a multi-line instruction, and the send
        // control is a single thumb away either way. The Send IME action is
        // still wired, which is what a hardware keyboard's return key uses.
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
    )
}

/**
 * SEND and STOP in one slot, so the control a thumb has to find is in the same
 * place whether or not a run is in flight.
 *
 * Labelled, not icon-only, for two reasons. The old control was a bare 48dp
 * icon in the corner of the screen with no label, which is the least findable
 * shape a primary action can take; and STOP replacing SEND silently would give
 * two different meanings to one target with nothing to tell them apart. The
 * label is also what lets the button be widened into a thumb-sized strip
 * without looking like a text field.
 */
@Composable
private fun SendOrStopButton(
    busy: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = if (busy) onStop else onSend,
        // A disabled send with no stated reason is a dead end, so the reason
        // travels with the state: `blockedReason` is rendered above the field.
        enabled = busy || canSend,
        modifier = modifier.heightIn(min = 56.dp),
        colors = if (busy) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Icon(
            imageVector = if (busy) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
            // The label beside it already says "Send" / "Stop"; announcing the
            // icon separately is the same word twice.
            contentDescription = null,
        )
        Text(
            text = if (busy) "Stop" else "Send",
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * The one secondary control in the composer: where a user goes to grant a
 * permission the model has just been refused.
 *
 * ## WHY IT IS HERE AT ALL
 *
 * `PermissionActivity` is declared in the manifest and its class exists, but
 * nothing in the app navigated to it: the tools return an honest
 * `PermissionDenied` naming the permission and the Settings path, and the user
 * was given no way to act on that sentence. The destination already existed and
 * was already declared, so this is a wire, not a feature.
 *
 * It sits at the start of the action row, opposite SEND, so the two large
 * targets are not adjacent — a user reaching for one cannot hit the other by
 * accident, which for a destructive control and a "did I mean to send that"
 * control is the whole point of separating them.
 */
@Composable
private fun PermissionEntry() {
    val context = LocalContext.current
    IconButton(
        onClick = { openPermissionScreen(context) },
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "Permissions",
        )
    }
}

/**
 * Opens the permission screen, falling back to this app's Settings page.
 *
 * The fallback matters for the same reason `MainActivity.openBackgroundAccess`
 * has one: a button that silently does nothing when the Activity cannot start
 * is worse than no button, because the user cannot tell "broken" from "not
 * configured". Settings is a real destination that always exists and is one tap
 * from the same grant.
 */
private fun openPermissionScreen(context: Context) {
    try {
        context.startActivity(Intent(context, PermissionActivity::class.java))
    } catch (e: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
    }
}
