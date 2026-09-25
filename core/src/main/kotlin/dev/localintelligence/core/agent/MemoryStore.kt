package dev.localintelligence.core.agent

import dev.localintelligence.core.model.ChatMessage

/**
 * Durable + working memory. Pure interfaces so :core stays testable on the JVM;
 * :android supplies the Room-backed implementation.
 */
data class Memory(
    val id: Long,
    val text: String,
    val keywords: String,
    val importance: Float = 0.5f,
)

interface MemoryStore {
    suspend fun remember(text: String, importance: Float = 0.5f): Memory
    /** Lexical search. v0 uses FTS-style matching, NOT embeddings. */
    suspend fun search(query: String, limit: Int = 5): List<Memory>
    suspend fun forget(id: Long): Boolean
    suspend fun all(limit: Int = 100): List<Memory>
}

interface SessionStore {
    suspend fun createSession(): Long
    suspend fun appendMessage(sessionId: Long, message: ChatMessage)
    suspend fun messages(sessionId: Long): List<ChatMessage>
    suspend fun clear()
}

/** In-memory store. Default for tests and for the first vertical slice. */
class InMemoryMemoryStore : MemoryStore {
    private val items = mutableListOf<Memory>()
    private var nextId = 1L

    override suspend fun remember(text: String, importance: Float): Memory {
        val memory = Memory(
            id = nextId++,
            text = text,
            keywords = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.joinToString(" "),
            importance = importance,
        )
        items += memory
        return memory
    }

    override suspend fun search(query: String, limit: Int): List<Memory> {
        val terms = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
        if (terms.isEmpty()) return emptyList()
        // Token-set match, not substring: "nas" must not match "nasty".
        return items.filter { memory ->
            val memoryTerms = memory.keywords.split(' ').filter { it.isNotEmpty() }.toSet()
            terms.any { term -> term in memoryTerms }
        }
            .sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.id })
            .take(limit)
    }

    override suspend fun forget(id: Long): Boolean = items.removeIf { it.id == id }
    override suspend fun all(limit: Int): List<Memory> = items.take(limit)
}
