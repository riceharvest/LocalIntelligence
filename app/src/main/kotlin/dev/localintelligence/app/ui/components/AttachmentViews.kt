package dev.localintelligence.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.media.PendingAttachment
import dev.localintelligence.app.media.ShownAttachment

/**
 * Attachments as the user sees them: a removable chip in the composer, and a
 * thumbnail row in the sent message.
 *
 * Shared by both so a picture looks the same before and after it is sent.
 */

/** A queued attachment above the text field. [onRemove] takes it back out before sending. */
@Composable
internal fun AttachmentChip(attachment: PendingAttachment, index: Int, onRemove: () -> Unit) {
    val label = attachmentLabel(attachment.shown)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AttachmentPreview(attachment.shown, Modifier.size(48.dp).padding(start = 4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove $label ${index + 1}")
            }
        }
    }
}

/** Every attachment of a sent message, in a row that scrolls rather than wraps. */
@Composable
internal fun SentAttachments(attached: List<ShownAttachment>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attached.forEach { shown ->
            when (shown) {
                is ShownAttachment.Picture -> AttachmentPreview(shown, Modifier.size(72.dp))
                is ShownAttachment.Voice -> Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Text(
                            text = attachmentLabel(shown),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreview(shown: ShownAttachment, modifier: Modifier) {
    when (shown) {
        is ShownAttachment.Picture -> {
            // Decoded once per thumbnail. The bytes are a few kilobytes, so this is cheap.
            val bitmap = remember(shown.thumbnail) {
                BitmapFactory.decodeByteArray(shown.thumbnail, 0, shown.thumbnail.size)?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Attached photo",
                    contentScale = ContentScale.Crop,
                    modifier = modifier.clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        is ShownAttachment.Voice -> Box(
            modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null)
        }
    }
}

private fun attachmentLabel(shown: ShownAttachment): String = when (shown) {
    is ShownAttachment.Picture -> "Photo"
    is ShownAttachment.Voice -> "Voice clip ${clipLength(shown.seconds)}"
}

/** A clip length as m:ss, which is how a phone shows a recording. */
internal fun clipLength(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
