package dev.localintelligence.android.data

import android.content.Context
import android.util.Log
import dev.localintelligence.core.tool.ObservationReader
import dev.localintelligence.core.tool.ObservationStore

/**
 * [ObservationStore] over the `observations` table.
 *
 * Blocking, as the interface is: the agent loop and `observations.read` call it
 * off the main thread (the tool from `Dispatchers.IO`). The DAO is lazy so that
 * building the tool set never opens the database; the first long result does.
 *
 * Total: a database that cannot be opened or written makes [put] return null
 * and [read] return null, and the loop carries on with a preview that has no
 * handle. Same rule as `ResilientMemoryStore`: a storage failure must not end a run.
 */
class RoomObservationStore(
    private val dao: Lazy<ObservationDao>,
    private val clock: () -> Long = System::currentTimeMillis,
) : ObservationStore {

    override fun put(toolName: String, text: String): Long? {
        if (text.isEmpty()) return null
        return try {
            val id = dao.value.insert(
                ObservationEntity(
                    toolName = toolName.take(MAX_TOOL_NAME_CHARS),
                    createdAt = clock(),
                    text = capped(text),
                    originalChars = text.length,
                ),
            )
            // Bounded on every insert, so the table never needs a cleanup job.
            dao.value.keepNewest(ObservationStore.MAX_KEPT)
            id
        } catch (e: Exception) {
            Log.w(TAG, "could not save a $toolName result (${e.javaClass.simpleName})")
            null
        }
    }

    override fun read(id: Long, query: String?, offset: Int, maxChars: Int): String? =
        try {
            dao.value.byId(id)?.let { row ->
                ObservationReader.read(
                    id = row.id,
                    toolName = row.toolName,
                    stored = row.text,
                    originalChars = row.originalChars,
                    query = query,
                    offset = offset,
                    maxChars = maxChars,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read obs#$id (${e.javaClass.simpleName})")
            null
        }

    private companion object {
        const val TAG = "ObservationStore"

        /** A tool name is a short dotted id; an MCP name is longer, but not this long. */
        const val MAX_TOOL_NAME_CHARS = 64

        /** [text] up to [ObservationStore.MAX_STORED_CHARS], not ending inside a surrogate pair. */
        fun capped(text: String): String {
            if (text.length <= ObservationStore.MAX_STORED_CHARS) return text
            var end = ObservationStore.MAX_STORED_CHARS
            if (text[end - 1].isHighSurrogate()) end--
            return text.substring(0, end)
        }
    }
}

/** The store over this database's DAO. For a caller that already holds the database. */
fun LocalIntelligenceDatabase.observationStore(): RoomObservationStore =
    RoomObservationStore(lazyOf(observationDao()))

/**
 * A store that opens the database on first use, as `historyTools` does, so a run
 * that never saves or reads a long result never opens it.
 */
fun lazyObservationStore(context: Context): RoomObservationStore {
    val app = context.applicationContext
    return RoomObservationStore(lazy { LocalIntelligenceDatabase.build(app).observationDao() })
}
