package dev.localintelligence.app.media

/**
 * The attachments waiting for the next run. Process memory only, like
 * [dev.localintelligence.app.AssistantScreenshot].
 *
 * WHY NOT THE INTENT: [dev.localintelligence.app.ExecutionService] carries the
 * task as an Intent extra. A photo's bytes do not belong in a Bundle: the system
 * copies it across the binder, and a payload that size fails the transaction. The
 * service runs in this process, so the holder is shared directly.
 *
 * Who writes: the chat, right before it starts a message. Who reads: the
 * AgentViewModel of a chat run, which takes everything and clears it. A scheduled
 * run never takes from here.
 */
class PendingAttachments {
    private val lock = Any()
    private var items: List<PendingAttachment> = emptyList()

    fun add(more: List<PendingAttachment>) = synchronized(lock) { items = items + more }

    /** What would be sent now. Does not clear: the transcript row is drawn from it. */
    fun snapshot(): List<PendingAttachment> = synchronized(lock) { items }

    /** Hands the waiting attachments to a run and forgets them, so no run takes them twice. */
    fun take(): List<PendingAttachment> = synchronized(lock) { items.also { items = emptyList() } }

    /** Drops them when the run they were meant for never started. */
    fun clear() = synchronized(lock) { items = emptyList() }
}
