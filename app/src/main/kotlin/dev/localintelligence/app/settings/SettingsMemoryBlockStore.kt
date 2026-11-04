package dev.localintelligence.app.settings

import dev.localintelligence.core.memory.MemoryBlock
import dev.localintelligence.core.memory.MemoryBlockStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The memory block over [SettingsStore], with the ledger that makes each agent write undoable.
 *
 * WHY A LEDGER: a change is undone by putting back the text it replaced, so every write keeps
 * its before and after. An undo is allowed only while the block still holds the text the change
 * wrote; if the user has edited it since, undoing would silently throw that edit away.
 *
 * WHY IT IS IN MEMORY: the ledger lives for the process. After a restart the Undo on an old row
 * is gone, and the row says nothing. The text itself is kept, and that is the part that matters.
 *
 * WHY `stored` AND NOT `get`: the block is a `simple` row, so `stored` is the text the user kept
 * in either mode, and it is never a snapshot frozen at the start of a run.
 */
class SettingsMemoryBlockStore(private val store: SettingsStore) : MemoryBlockStore {

    private class Change(val id: Long, val before: String, val after: String, val note: String) {
        var undone: Boolean = false
    }

    private val lock = Any()
    private val changes = mutableListOf<Change>()
    /**
     * Seeded from the clock, not 1: the chat rows keep their change ids across a restart, and a counter
     * from 1 would give a new change an id an old row already holds, so that old row would show and
     * undo the new change, with its note.
     */
    private var nextId = System.currentTimeMillis()

    private val _revision = MutableStateFlow(0)

    /** Bumps on every write and undo, so chat rows redraw their Undo state. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    override fun read(): String = store.stored(MemoryBlock.TEXT)

    override fun write(text: String, note: String): Long = synchronized(lock) {
        val before = read()
        store.set(MemoryBlock.TEXT, text)
        val change = Change(nextId++, before = before, after = text, note = note)
        changes += change
        _revision.value += 1
        change.id
    }

    override fun undo(changeId: Long): Boolean = synchronized(lock) {
        val change = changes.firstOrNull { it.id == changeId } ?: return false
        if (!canUndoChange(change)) return false
        store.set(MemoryBlock.TEXT, change.before)
        change.undone = true
        _revision.value += 1
        true
    }

    override fun canUndo(changeId: Long): Boolean = synchronized(lock) {
        val change = changes.firstOrNull { it.id == changeId } ?: return false
        canUndoChange(change)
    }

    override fun noteOf(changeId: Long): String? = synchronized(lock) {
        changes.firstOrNull { it.id == changeId }?.note
    }

    /** Not undone, and the block still holds what the change wrote. */
    private fun canUndoChange(change: Change): Boolean = !change.undone && read() == change.after
}
