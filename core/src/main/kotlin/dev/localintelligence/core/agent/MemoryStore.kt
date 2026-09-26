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

/**
 * The WRITE half of memory, as the agent loop sees it.
 *
 * ## WHY THIS IS A SEAM AND NOT A METHOD ON [MemoryStore]
 *
 * The loop needs to offer a finished turn to something that decides whether it
 * is worth keeping, and that decision already exists:
 * `dev.localintelligence.core.memory.LexicalMemoryStore.recordTurn` applies
 * `MemoryWritePolicy` and, if the turn qualifies, writes one row. That class
 * cannot simply have `recordTurn` added to [MemoryStore] here, and the reason
 * is ownership rather than taste: `MemoryStore` is implemented by
 * `InMemoryMemoryStore` (in this file), by `ResilientMemoryStore` and
 * `RoomMemoryStore` in `:android`, and by `LexicalMemoryStore` in
 * `core/memory`. Widening the interface would make every one of them carry a
 * method whose answer is "not me", and would put a call to it in every
 * construction site of every store. A narrow interface the loop asks for is one
 * line at the composition root and zero lines everywhere else.
 *
 * ## WHAT THE ARGUMENT IS, AND WHY IT IS NOT THE MODEL'S ANSWER
 *
 * [recordTurn] takes the USER's text. The loop calls it on the branch where a
 * run produces an answer, and it deliberately does NOT pass that answer: a
 * model told "what is my wifi password" replies "your wifi password is
 * hunter2", and storing that would promote a hallucination into a durable fact
 * that every later query re-asserts. A memory has to be something the user
 * said.
 *
 * ## IT IS OPTIONAL, AND THAT IS NOT THE SAME AS THE RISK POLICY
 *
 * [dev.localintelligence.core.agent.AgentController] defaults its risk policy
 * to a real instance precisely so that no controller can be built ungated by
 * omission. This one is nullable, and the asymmetry is deliberate: not
 * recording a turn is the behaviour this codebase has always had, so a null
 * recorder is a missing feature rather than a missing safety gate. The cost is
 * that the app has to pass it - see `AppContainer.newController` - and that the
 * app passing it is stated there in those words.
 */
fun interface TurnRecorder {
    /**
     * Offers one finished user turn for storage.
     *
     * @return the stored [Memory], or null when the turn did not qualify. The
     *   loop ignores the return: a memory that was not written is not a fact
     *   about the run, and inventing a trace entry for it would be a claim
     *   about storage this module does not own.
     */
    suspend fun recordTurn(userText: String): Memory?
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
