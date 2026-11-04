package dev.localintelligence.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The checklist for a multi-step task, owned by the runtime rather than by the
 * transcript.
 *
 * WHY IT LIVES ON [Session] AND NOT IN THE MESSAGES: the window is folded and
 * trimmed mid-run (see `AgentController.foldWindow`), and a checklist written as a
 * message would be folded away with the step that wrote it. Held here, the list
 * survives every compaction and is put back into each prompt by
 * `AgentController.buildPrompt`, from the one copy that exists.
 *
 * WHY IT IS ABSENT UNTIL USED: [render] returns null for an empty list, so a model
 * that never calls `todo.update` gets no extra text in its prompt at all.
 *
 * WHY THE WHOLE LIST IS SENT EACH TIME: a small model writes a complete list far
 * more reliably than it writes a patch to one item of it. Replace is the only write.
 *
 * Bounded on purpose, because the list is re-sent every step on a phone: at most
 * [MAX_ITEMS] items, each at most [MAX_TEXT_CHARS] characters.
 */
class TodoList {

    /** One checklist row. [text] is what the model wrote, cut to [MAX_TEXT_CHARS]. */
    data class Item(val text: String, val status: Status)

    /**
     * The four states an item can be in. [wire] is the word the model writes and the
     * word the prompt shows. `completed` and `canceled` are accepted as aliases,
     * because models write them.
     */
    enum class Status(val wire: String) {
        PENDING("pending"),
        IN_PROGRESS("in_progress"),
        DONE("done"),
        CANCELLED("cancelled"),
    }

    /** Result of reading a model's list: the items, or the one sentence the model should act on. */
    sealed interface Parsed {
        data class Ok(val items: List<Item>) : Parsed
        data class Invalid(val message: String) : Parsed
    }

    private var items: List<Item> = emptyList()

    /** The current items, in order. A copy, so a caller cannot change the list under the runtime. */
    fun snapshot(): List<Item> = items

    /** True while any item is still to do. An unfinished list survives into the next message. */
    val hasOpen: Boolean
        get() = items.any { it.status == Status.PENDING || it.status == Status.IN_PROGRESS }

    /** True when there is at least one item, which is when the prompt shows the list. */
    val hasItems: Boolean
        get() = items.isNotEmpty()

    /** Replaces the whole list. Called by the tool after [parse] accepted the input. */
    fun replace(next: List<Item>) {
        items = next
    }

    /**
     * The list as the JSON array [parse] reads, so a restart can put it back. Stored with the
     * session row (see `SessionStore.setTodos`); null when there is no list.
     */
    fun encode(): String? {
        if (items.isEmpty()) return null
        return JsonArray(
            items.map { item ->
                JsonObject(
                    mapOf(
                        "text" to JsonPrimitive(item.text),
                        "status" to JsonPrimitive(item.status.wire),
                    ),
                )
            },
        ).toString()
    }

    /**
     * Puts back a list [encode] wrote. A value that no longer parses leaves the list empty:
     * a missing checklist is better than a half-read one.
     */
    fun restore(json: String?) {
        if (json.isNullOrBlank()) return
        val raw = runCatching { Json.parseToJsonElement(json) as? JsonArray }.getOrNull() ?: return
        val parsed = parse(raw) as? Parsed.Ok ?: return
        items = parsed.items
    }

    /** Forgets the list: a finished task, a new conversation or a restored one. */
    fun clear() {
        items = emptyList()
    }

    /**
     * The block the runtime puts at the end of each prompt while there is a list, or
     * null. Numbered and in order, with the status word, so the model can see what is
     * left without the transcript that wrote it.
     */
    fun render(): String? {
        if (items.isEmpty()) return null
        val lines = items.mapIndexed { i, item -> "${i + 1}. [${item.status.wire}] ${item.text}" }
        return HEADER + "\n" + lines.joinToString("\n")
    }

    companion object {
        /** The tool name, shared by the catalogue, the tool and the policy exemption. */
        const val TOOL_NAME = "todo.update"

        /** Most items one list may hold. A longer plan is a plan the model should split. */
        const val MAX_ITEMS = 12

        /** Most characters one item's text may hold. */
        const val MAX_TEXT_CHARS = 120

        /** The first line of the rendered block. The model reads it as the runtime's note, not the user's. */
        const val HEADER = "Task list (kept by the runtime; send the whole list with todo.update when it changes):"

        /**
         * Reads the `items` argument. Every item needs non-blank `text` and a known
         * `status`; anything else is refused whole, so a half-read list never replaces
         * the real one.
         */
        fun parse(raw: JsonArray): Parsed {
            if (raw.size > MAX_ITEMS) {
                return Parsed.Invalid("items has ${raw.size} entries, and a task list holds at most $MAX_ITEMS. Merge some steps and send again.")
            }
            val out = ArrayList<Item>(raw.size)
            for ((i, element) in raw.withIndex()) {
                val obj = element as? JsonObject
                    ?: return Parsed.Invalid("item ${i + 1} must be an object with \"text\" and \"status\".")
                val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return Parsed.Invalid("item ${i + 1} has no \"text\". Give each item a short description.")
                val status = statusOf((obj["status"] as? JsonPrimitive)?.contentOrNull)
                    ?: return Parsed.Invalid(
                        "item ${i + 1} has an unknown status. Use pending, in_progress, done or cancelled.",
                    )
                out += Item(text.take(MAX_TEXT_CHARS), status)
            }
            return Parsed.Ok(out)
        }

        /** The status a model meant by [word], or null when it means none. */
        private fun statusOf(word: String?): Status? =
            when (word?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
                "pending" -> Status.PENDING
                "in_progress" -> Status.IN_PROGRESS
                "done", "completed" -> Status.DONE
                "cancelled", "canceled" -> Status.CANCELLED
                else -> null
            }
    }
}
