package dev.localintelligence.core.memory

import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection

/**
 * The memory block: one short note the assistant keeps and sees on every step.
 *
 * ## WHY THE AGENT MAY WRITE IT NOW
 *
 * `docs/architecture.md` section 14 used to say memory is written only by a deterministic
 * filter, and that the model never writes memory. The owner changed that on 2026-11-04:
 * the agent may edit this one block through `memory.update_block`, on the condition that
 * every change is visible in the chat and can be undone. Extraction by a separate model
 * pass stays banned. The block is never filled from a model's summary of a conversation.
 *
 * ## WHY IT IS A SETTING ROW
 *
 * It is one text the user can read and edit in Settings, and [SettingDef.Text] already
 * gives both, with the cap enforced on read too. Storing it anywhere else would be a
 * preferences file of its own, which the brief forbids. It is `simple = true` so Simple
 * mode reads the stored value rather than the default, which would hide the block.
 */
object MemoryBlock {
    /** The cap. Long enough for a few facts, short enough to stay cheap in every prompt. */
    const val MAX_CHARS: Int = 1_000

    val TEXT = SettingDef.Text(
        key = "memory.block.text",
        title = "Memory block",
        help = "Short notes the assistant keeps and sees on every step. It changes when you ask it " +
            "to remember something, or when you edit it here. Leave empty to keep nothing.",
        section = SettingSection.GENERAL,
        default = "",
        maxLength = MAX_CHARS,
        simple = true,
    )
}

/**
 * Read and write access to the block, as the tool and the settings see it. A store is the one
 * place the block is written; the tool never reaches the settings directly, because `:android`
 * cannot see `:app`'s SettingsStore.
 */
interface MemoryBlockStore {
    fun read(): String

    /** Writes [text] and returns the id of this change. [note] is what the user asked to keep, for the chat row. */
    fun write(text: String, note: String): Long

    /** Restores the text from before change [changeId]. False when that change cannot be undone any more. */
    fun undo(changeId: Long): Boolean

    /** Whether [changeId] can still be undone: it was not undone and the block still holds its text. */
    fun canUndo(changeId: Long): Boolean

    /** The note change [changeId] kept, for the chat row, or null for an unknown change. */
    fun noteOf(changeId: Long): String?
}

/**
 * The one rule for changing the block, as pure Kotlin so the JVM can check it.
 *
 * Operations:
 *  - `add`: appends one line. Refused when it would go over the cap, with the room left, so
 *    a small model can see that it must replace or clear instead of retrying the same text.
 *  - `replace`: sets the whole block. Prefer `add`: a small model that rewrites the whole
 *    block tends to drop facts it did not restate.
 *  - `clear`: empties the block.
 */
sealed interface MemoryBlockEdit {
    data class Changed(val text: String) : MemoryBlockEdit
    data class Refused(val reason: String) : MemoryBlockEdit

    companion object {
        /** Whitespace is collapsed, so one note is always one line of the block. */
        fun apply(current: String, action: String, note: String): MemoryBlockEdit {
            val clean = note.replace(Regex("\\s+"), " ").trim()
            return when (action) {
                "add" -> {
                    if (clean.isEmpty()) return Refused("Nothing to add: the note is empty.")
                    val joined = if (current.isBlank()) clean else current.trimEnd() + "\n" + clean
                    fits(current, joined, "That note")
                }
                "replace" -> {
                    if (clean.isEmpty()) return Refused("Nothing to write: use clear to empty the block.")
                    fits("", clean, "The new text")
                }
                "clear" -> Changed("")
                else -> Refused("Unknown action \"$action\". Use add, replace or clear.")
            }
        }

        private fun fits(current: String, next: String, what: String): MemoryBlockEdit {
            if (next.length <= MemoryBlock.MAX_CHARS) return Changed(next)
            val room = (MemoryBlock.MAX_CHARS - current.length).coerceAtLeast(0)
            return Refused(
                "$what is ${next.length} characters, but the memory block holds ${MemoryBlock.MAX_CHARS} " +
                    "and has room for $room more. Use replace with a shorter text, or clear first.",
            )
        }
    }
}
