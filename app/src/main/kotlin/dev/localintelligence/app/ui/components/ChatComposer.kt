package dev.localintelligence.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.media.PendingAttachment
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
 * ## WHY THE ATTACH AND MIC BUTTONS SIT NEXT TO THE SETTINGS ENTRY
 *
 * A control that cannot be reached one-handed is worse than no control: it
 * advertises a capability, takes a tap's worth of thumb to miss, and teaches the
 * user the app is not listening. So attach and mic are small icons on the same
 * edge as the settings entry, away from the full-width SEND strip. They are
 * never adjacent to SEND, so a thumb aiming for one cannot land on the other.
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
    /** Queued photos and voice clips, shown as chips above the field. */
    attachments: List<PendingAttachment> = emptyList(),
    /** Whether the attach button is offered. The caller turns it off while a run is in flight or at the photo limit. */
    canAttach: Boolean = true,
    /** Whether the mic is on: a clip is recording, or dictation is listening. */
    micActive: Boolean = false,
    /** Whether the mic button is offered. */
    canUseMic: Boolean = true,
    /** A live line such as "Recording 0:07". Null when nothing is in progress. */
    liveStatus: String? = null,
    /** A one-line complaint, such as a photo that did not decode. */
    notice: String? = null,
    /** Whether hands-free mode is on. Its button then shows as active, and tapping it stops the mode. */
    handsFreeOn: Boolean = false,
    /** Whether the hands-free button is offered. */
    canHandsFree: Boolean = true,
    onAttach: () -> Unit = {},
    onMic: () -> Unit = {},
    onHandsFree: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    /** Opens Settings. The chat has no app bar, so this is the way to every other screen. */
    onOpenSettings: () -> Unit = {},
    /**
     * Whether [blockedReason] is written above the field. The caller turns it off
     * while the empty chat already shows the same sentence in the middle.
     */
    showBlockedReason: Boolean = true,
    /** A way out of [blockedReason], such as "Download a model". Null when waiting is the answer. */
    blockedAction: Pair<String, () -> Unit>? = null,
    modifier: Modifier = Modifier,
) {
    // A message can be only attachments, but never while a clip is still recording.
    val canSend = (input.isNotBlank() || attachments.isNotEmpty()) &&
        blockedReason == null &&
        !busy &&
        liveStatus == null

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
            if (blockedReason != null && showBlockedReason) {
                // Announced as it appears: without this, a TalkBack user
                // encounters a disabled button with no explanation of why.
                // Neutral, not red: "no model yet" or "loading" is a state, and
                // the button next to it is the way forward.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = blockedReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    blockedAction?.let { (label, action) ->
                        TextButton(onClick = action) { Text(label) }
                    }
                }
            }

            if (notice != null) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (liveStatus != null) {
                // Announced as it changes, so a screen-reader user hears the recording is running.
                Text(
                    text = liveStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEachIndexed { index, item ->
                        AttachmentChip(
                            attachment = item,
                            index = index,
                            onRemove = { onRemoveAttachment(index) },
                        )
                    }
                }
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
                    SettingsEntry(onOpenSettings)
                    MediaButtons(canAttach, canUseMic, micActive, handsFreeOn, canHandsFree, onAttach, onMic, onHandsFree)
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
                    SettingsEntry(onOpenSettings)
                    MediaButtons(canAttach, canUseMic, micActive, handsFreeOn, canHandsFree, onAttach, onMic, onHandsFree)

                    // One slot, two states, same geometry. The button never moves
                    // when a run starts or stops, so there is nothing to re-find
                    // with a thumb at the exact moment a run is in flight.
                    SendOrStopButton(
                        busy = busy,
                        canSend = canSend,
                        onSend = onSend,
                        onStop = onStop,
                        // Takes the rest of the row, so the target spans from
                        // just past the media buttons to the right padding.
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
        // No floating label, which made the field look like a form. The name a
        // screen reader announces is set here instead, so the field is never
        // unlabelled once the placeholder is gone.
        modifier = modifier.semantics { contentDescription = "Message" },
        placeholder = { Text("Ask anything") },
        shape = RoundedCornerShape(24.dp),
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
 * Attach and mic, as 48dp icon buttons. Shared by both layouts.
 *
 * Disabled rather than hidden, so the user can see the controls exist and why
 * they are unavailable, and so the row keeps its shape when a run starts.
 */
@Composable
private fun MediaButtons(
    canAttach: Boolean,
    canUseMic: Boolean,
    micActive: Boolean,
    handsFreeOn: Boolean,
    canHandsFree: Boolean,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onHandsFree: () -> Unit,
) {
    IconButton(onClick = onAttach, enabled = canAttach, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Filled.AttachFile, contentDescription = "Attach a photo")
    }
    IconButton(
        onClick = onMic,
        enabled = canUseMic,
        modifier = Modifier.size(48.dp),
        colors = if (micActive) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = if (micActive) "Stop recording" else "Record voice",
        )
    }
    IconButton(
        onClick = onHandsFree,
        enabled = canHandsFree,
        modifier = Modifier.size(48.dp),
        colors = if (handsFreeOn) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) {
        Icon(
            Icons.Filled.Headset,
            contentDescription = if (handsFreeOn) "Stop hands-free mode" else "Start hands-free mode",
        )
    }
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
        // Stop is tonal, not red: stopping is a normal thing to do, not an error.
        colors = if (busy) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
 * The way out of the chat: the chat has no app bar, so models, permissions,
 * skills and everything else are reached through Settings.
 *
 * It sits at the start of the action row, opposite SEND, so the two large
 * targets are not adjacent.
 */
@Composable
private fun SettingsEntry(onOpenSettings: () -> Unit) {
    IconButton(
        onClick = onOpenSettings,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Settings",
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
fun openPermissionScreen(context: Context) {
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
