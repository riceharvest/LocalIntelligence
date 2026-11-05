package dev.localintelligence.app.settings

import dev.localintelligence.android.data.MemoryChangeDao
import dev.localintelligence.android.data.MemoryChangeEntity
import dev.localintelligence.core.memory.MemoryBlock
import dev.localintelligence.core.memory.MemoryBlockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The memory block over [SettingsStore], with the ledger that makes each agent write undoable.
 *
 * WHY A LEDGER: a change is undone by putting back the text it replaced, so every write keeps
 * its before and after. An undo is allowed only while the block still holds the text the change
 * wrote; if the user has edited it since, undoing would silently throw that edit away.
 *
 * WHY IT IS ALSO IN ROOM: the Undo on a "Remembered: ..." row must still work after the app
 * restarts. Each change is written to the `memory_changes` table as it is made, and [restore] reads
 * the table back at launch. The in-memory list stays the source for reads, so a screen never waits
 * on the database; the writes go to [scope] on the IO dispatcher. Without a [dao] (hosts that have
 * no database) the ledger lasts one process, as it did before.
 *
 * WHY `stored` AND NOT `get`: the block is a `simple` row, so `stored` is the text the user kept
 * in either mode, and it is never a snapshot frozen at the start of a run.
 */
class SettingsMemoryBlockStore(
    private val store: SettingsStore,
    private val dao: (() -> MemoryChangeDao)? = null,
    private val scope: CoroutineScope? = null,
) : MemoryBlockStore {

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
        persist { it.put(change.toEntity()); it.trim(KEEP_CHANGES) }
        change.id
    }

    override fun undo(changeId: Long): Boolean = synchronized(lock) {
        val change = changes.firstOrNull { it.id == changeId } ?: return false
        if (!canUndoChange(change)) return false
        store.set(MemoryBlock.TEXT, change.before)
        change.undone = true
        _revision.value += 1
        persist { it.markUndone(change.id) }
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

    /**
     * Loads the saved ledger. Called once at launch, before the chat is drawn, so the Undo on a row
     * from the last run works. A table that cannot be read leaves the ledger empty, which is the
     * old behaviour: the text is kept, the Undo is gone.
     */
    suspend fun restore() {
        val rows = try {
            dao?.invoke()?.all().orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Could not read the memory Undo ledger", e)
            return
        }
        synchronized(lock) {
            val known = changes.map { it.id }.toSet()
            for (row in rows) {
                if (row.id in known) continue
                changes += Change(row.id, row.before, row.after, row.note).also { it.undone = row.undone }
            }
            changes.sortBy { it.id }
            val newest = changes.lastOrNull()?.id ?: 0L
            if (newest >= nextId) nextId = newest + 1
            _revision.value += 1
        }
    }

    /** The ids of the changes the ledger holds, oldest first. The chat uses them to put the rows back after a restart. */
    fun changeIds(): List<Long> = synchronized(lock) { changes.map { it.id } }

    private fun Change.toEntity() =
        MemoryChangeEntity(id, before, after, note, undone, System.currentTimeMillis())

    /** Runs [block] on the database in the background. A failed write only costs the Undo after a restart. */
    private fun persist(block: suspend (MemoryChangeDao) -> Unit) {
        val database = dao ?: return
        val launcher = scope ?: return
        launcher.launch(Dispatchers.IO) {
            try {
                block(database())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Could not save the memory Undo ledger", e)
            }
        }
    }

    private companion object {
        const val TAG = "MemoryBlockStore"

        /** Enough for any chat that can still be undone; older changes are dropped. */
        const val KEEP_CHANGES = 50
    }
}
