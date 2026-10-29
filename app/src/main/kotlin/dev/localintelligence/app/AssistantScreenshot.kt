package dev.localintelligence.app

import android.graphics.Bitmap

/**
 * The most recent screen capture the assistant was given, held in memory only.
 *
 * WHY IN MEMORY AND NOT A FILE: a screenshot shows whatever the user had open,
 * which is often private. Nothing writes it to disk or sends it anywhere. It is
 * dropped when a new capture arrives, when the session is destroyed, or when the
 * process dies. Vision support will read it through [latest]; until then nothing
 * reads it.
 */
object AssistantScreenshot {

    @Volatile
    private var current: Bitmap? = null

    /** The latest capture, or null if none has arrived since the last [clear]. */
    fun latest(): Bitmap? = current

    internal fun put(bitmap: Bitmap?) {
        current = bitmap
    }

    /** Forgets the held capture. The bitmap is not recycled: a reader may still hold it. */
    fun clear() {
        current = null
    }
}
