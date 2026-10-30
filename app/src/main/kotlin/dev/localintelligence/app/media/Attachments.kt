package dev.localintelligence.app.media

import dev.localintelligence.core.model.MediaInput

/**
 * What a transcript row needs to draw one attachment after its message is sent.
 *
 * WHY IT HOLDS NO MODEL BYTES: the transcript lives as long as the process, and
 * a picture the model reads is a few hundred kilobytes. The row keeps a small
 * preview; the run gets the full bytes through [PendingAttachment].
 */
sealed interface ShownAttachment {
    /** A small JPEG, at most 256 px on its longer side. */
    class Picture(val thumbnail: ByteArray) : ShownAttachment

    /** A voice clip, drawn as its length. */
    data class Voice(val seconds: Int) : ShownAttachment
}

/** One attachment waiting to travel with the next message: what the model reads, and what to draw. */
class PendingAttachment(val media: MediaInput, val shown: ShownAttachment)
