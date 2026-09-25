package dev.pidroid.android.data

import dev.pidroid.core.agent.InMemoryMemoryStore
import dev.pidroid.core.agent.Memory
import dev.pidroid.core.model.ChatMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM-only tests. There is no device or emulator in this environment and Robolectric is
 * not available, so `android.database.sqlite` cannot execute here. Everything that can
 * be decided without a database is therefore decided in `MemoryQueries` /
 * `MessageMapping` and tested here; the DAO round-trip is verified by KSP + compilation
 * (see the PR description) and needs instrumentation to truly execute.
 */
class RoomStoreTest {

    // ---------------------------------------------------------------- escaping --

    @Test
    fun `classic injection payload cannot break out of the MATCH expression`() {
        val payload = "'; DROP TABLE memories; --"

        val match = MemoryQueries.buildMatchExpression(payload)

        // The payload tokenises into harmless words. No quote, semicolon or comment
        // marker survives: the tokenizer keeps only [a-z0-9] runs longer than 2 chars.
        assertEquals("\"drop\" OR \"table\" OR \"memories\"", match)
        assertTrue("no raw quote may survive", !match!!.contains("'"))
        assertTrue("no semicolon may survive", !match.contains(";"))
        assertTrue("no comment marker may survive", !match.contains("--"))
    }

    @Test
    fun `double quote in a query cannot terminate the FTS5 literal`() {
        // A bare `"` is a separator for the tokenizer, so it is dropped rather than
        // escaped. escapeFtsLiteral is still proven independently, because it is the
        // function that would be load-bearing if the tokenizer were ever loosened.
        assertEquals("\"user\"", MemoryQueries.buildMatchExpression("\"user\""))
        assertEquals("user", MemoryQueries.escapeFtsLiteral("user"))
        assertEquals("a\"\"b", MemoryQueries.escapeFtsLiteral("a\"b"))
    }

    @Test
    fun `union select and comment tricks are neutralised`() {
        for (payload in listOf(
            "\" OR 1=1 --",
            "x' UNION SELECT * FROM memories --",
            "foo\"; ATTACH DATABASE '/etc/passwd' AS p; --",
            "*",
            "()",
            "NEAR(a b)",
        )) {
            val match = MemoryQueries.buildMatchExpression(payload)
            if (match == null) continue
            assertTrue("unsafe chars leaked from: $payload", SAFE_EXPRESSION.matches(match))
        }
    }

    @Test
    fun `fts5 operator words are quoted so they stay literals`() {
        // AND/OR/NOT/NEAR are real FTS5 operators. Left unquoted, a search for a memory
        // about boolean logic would parse as query syntax and corrupt or hijack the
        // query. "or" is 2 chars so the tokenizer drops it before the parser ever sees
        // it; "and"/"not"/"near" survive the >2 filter and must therefore be quoted.
        assertNull(MemoryQueries.buildMatchExpression("or"))
        assertEquals("\"and\"", MemoryQueries.buildMatchExpression("and"))
        assertEquals("\"not\"", MemoryQueries.buildMatchExpression("not"))
        assertEquals("\"near\"", MemoryQueries.buildMatchExpression("near"))
        // Proven to execute without error against real SQLite+FTS5 in the FtsVerify
        // harness; here we prove the quoting that makes it safe.
        assertTrue(SAFE_EXPRESSION.matches(MemoryQueries.buildMatchExpression("and not near")!!))
    }

    @Test
    fun `search statement binds both the match expression and the limit`() {
        val query = MemoryQueries.buildSearchQuery("hello world", 3)!!

        // Two placeholders: the MATCH expression and the LIMIT. Both are bound.
        assertEquals(2, query.sql.count { it == '?' })
        assertEquals(2, query.argCount)
        // The user's text must never appear in the SQL text itself, only as a bind arg.
        assertTrue(!query.sql.contains("hello"))
        assertTrue(!query.sql.contains("world"))
    }

    // --------------------------------------------------------- empty vs all --

    @Test
    fun `a query with no usable terms yields no query at all, never everything`() {
        // The load-bearing property: a null query short-circuits to emptyList() in
        // RoomMemoryStore. If this ever returned a match-all statement, a search for
        // "!!" would dump the user's entire memory store into the model's context.
        for (junk in listOf("", "   ", "!!", "a", "ab", "  ...  ", "'", "\"", "--", "()")) {
            assertNull("expected null query for '$junk'", MemoryQueries.buildSearchQuery(junk, 5))
        }
    }

    @Test
    fun `numeric tokens are real terms, matching InMemoryMemoryStore`() {
        // "123" is three characters and `[a-z0-9]` includes digits, so it survives the
        // >2 filter and is a legitimate search term. Pinned here because a future
        // "tighten the tokenizer" change must not silently diverge from :core, where a
        // PIN stored as "the pin is 1234" is findable by searching "1234".
        // "the" and "pin" survive too (4 and 3 chars); only "is" is dropped at 2.
        assertEquals(listOf("the", "pin", "1234"), MemoryQueries.tokenize("the pin is 1234"))
        assertEquals("\"1234\"", MemoryQueries.buildMatchExpression("1234"))
        // A partial numeric query is a prefix, not a match: FTS5 is token-based, so
        // "123" is a different term from "1234" and will not retrieve it.
        assertEquals("\"123\"", MemoryQueries.buildMatchExpression("123"))
    }

    @Test
    fun `terms shorter than three characters are dropped as noise`() {
        assertEquals(listOf("alpha", "beta"), MemoryQueries.tokenize("alpha of beta"))
        assertEquals(listOf("xyz"), MemoryQueries.tokenize("xyz"))
    }

    // ---------------------------------------------------------------- ordering --

    @Test
    fun `importance outranks recency, recency breaks the tie`() {
        val rows = listOf(
            row(id = 1, importance = 0.9f, createdAt = 100),
            row(id = 2, importance = 0.1f, createdAt = 300),
            row(id = 3, importance = 0.5f, createdAt = 200),
            row(id = 4, importance = 0.5f, createdAt = 250),
        )

        val ordered = MemoryQueries.orderByImportanceThenRecency(rows)

        assertEquals(listOf(1L, 4L, 3L, 2L), ordered.map { it.id })
    }

    @Test
    fun `id is the final deterministic tiebreak for otherwise identical rows`() {
        val rows = listOf(
            row(id = 7, importance = 0.5f, createdAt = 100),
            row(id = 8, importance = 0.5f, createdAt = 100),
            row(id = 9, importance = 0.5f, createdAt = 100),
        )

        assertEquals(listOf(9L, 8L, 7L), MemoryQueries.orderByImportanceThenRecency(rows).map { it.id })
    }

    @Test
    fun `ordering is total so repeated searches agree`() {
        val rows = (1..20).map { row(id = it.toLong(), importance = 0.5f, createdAt = 500) }
        val a = MemoryQueries.orderByImportanceThenRecency(rows).map { it.id }
        val b = MemoryQueries.orderByImportanceThenRecency(rows.reversed()).map { it.id }
        assertEquals(a, b)
    }

    // --------------------------------------------------------------------- cap --

    @Test
    fun `search limit is hard-capped at five regardless of what the caller asks`() {
        assertEquals(5, MemoryQueries.coerceSearchLimit(5))
        assertEquals(5, MemoryQueries.coerceSearchLimit(50))
        assertEquals(5, MemoryQueries.coerceSearchLimit(Int.MAX_VALUE))
        assertEquals(0, MemoryQueries.coerceSearchLimit(0))
        assertEquals(0, MemoryQueries.coerceSearchLimit(-3))
    }

    @Test
    fun `a large limit is clamped in the SQL argument, not just in Kotlin`() {
        // Clamping only in Kotlin would still ship `LIMIT 1000000` to SQLite. The cap
        // has to be the thing that is actually bound.
        val args = MemoryQueries.searchArgs("needle", 9999)!!
        assertEquals(5, args[1])
        // And the wrapper actually binds exactly those two args to the statement.
        val query = MemoryQueries.buildSearchQuery("needle", 9999)!!
        assertEquals(args.size, query.argCount)
        assertEquals(2, query.argCount)
    }

    @Test
    fun `all limit is bounded too`() {
        assertEquals(500, MemoryQueries.coerceAllLimit(10_000))
        assertEquals(0, MemoryQueries.coerceAllLimit(-1))
        assertEquals(100, MemoryQueries.coerceAllLimit(100))
    }

    // ------------------------------------------------------- keyword parity --

    @Test
    fun `keyword derivation matches InMemoryMemoryStore exactly`() = runTest {
        val corpus = listOf(
            "The user prefers DARK ROAST coffee in the morning",
            "Wifi password is hunter2 (network: Home-5G)",
            "MiXeD case, punctuation, and  spaced   out   words!!",
            "a bb ccc",
            "unicode ünïcödé and emoji 🚀 mixed in",
        )

        val inMemory = InMemoryMemoryStore()
        for (text in corpus) inMemory.remember(text)

        val room = inMemory.all(limit = corpus.size)
        val derived = corpus.map(MemoryQueries::keywords)

        assertEquals(
            room.map(Memory::keywords),
            derived,
        )
    }

    @Test
    fun `tokenisation and keyword derivation agree with each other`() {
        val text = "The user prefers DARK ROAST coffee"
        assertEquals(MemoryQueries.tokenize(text).joinToString(" "), MemoryQueries.keywords(text))
    }

    // --------------------------------------------------------- message codec --

    @Test
    fun `text variants round-trip exactly`() {
        val messages = listOf(
            ChatMessage.System("You are PiDroid. Be terse."),
            ChatMessage.User("what is on my calendar?"),
            ChatMessage.Assistant("You have one meeting at 14:00."),
        )

        for (message in messages) {
            val entity = MessageMapping.toColumns(message, sessionId = 7, createdAt = 1_000)
            assertEquals(message, MessageMapping.fromColumns(entity))
        }
    }

    @Test
    fun `tool observation round-trips including success false`() {
        val failed = ChatMessage.ToolObservation(
            toolName = "calendar.search",
            observation = "Error: no calendar permission (code 403)",
            success = false,
        )

        val entity = MessageMapping.toColumns(failed, sessionId = 1, createdAt = 5)

        assertEquals(MessageKind.TOOL_OBSERVATION.wire, entity.kind)
        // text must stay NULL: a tool result is not a text message.
        assertNull(entity.text)
        assertEquals(failed, MessageMapping.fromColumns(entity))
    }

    @Test
    fun `tool observation survives quotes newlines and unicode`() {
        // The hostile payload: a SQL metacharacter, a newline, a quote, emoji, RTL text
        // and a NUL-adjacent control char all in one string. A lossy codec here silently
        // corrupts the context window and is the single most damaging bug in this module.
        val observation = buildString {
            append("{\"error\":\"it's broken\"}\n")
            append("'; DROP TABLE memories; --\n")
            append("line\r\nwith\ttabs")
            append(" 🚀 ünïcödé مرحبا ")
            append("\"quoted\"")
        }
        val original = ChatMessage.ToolObservation(
            toolName = "files.read(\"it's\")",
            observation = observation,
            success = false,
        )

        val restored = MessageMapping.fromColumns(
            MessageMapping.toColumns(original, sessionId = 3, createdAt = 42),
        )

        assertEquals(original, restored)
        assertEquals(observation, (restored as ChatMessage.ToolObservation).observation)
        assertTrue(restored.observation.contains("'; DROP TABLE memories; --"))
    }

    @Test
    fun `a tool observation and a user message with identical text stay distinguishable`() {
        // Discriminator collision would be invisible without this: both would otherwise
        // round-trip to the same object and the model would think a tool spoke.
        val asText = MessageMapping.toColumns(
            ChatMessage.User("same text"),
            sessionId = 1,
            createdAt = 1,
        )
        val asTool = MessageMapping.toColumns(
            ChatMessage.ToolObservation("tool", "same text", success = true),
            sessionId = 1,
            createdAt = 1,
        )

        assertNotEquals(asText.kind, asTool.kind)
        assertTrue(MessageMapping.fromColumns(asText) is ChatMessage.User)
        assertTrue(MessageMapping.fromColumns(asTool) is ChatMessage.ToolObservation)
    }

    @Test
    fun `unknown discriminator fails loudly instead of silently degrading context`() {
        val corrupt = MessageEntity(
            id = 1, sessionId = 1, kind = "mystery", text = "hi", createdAt = 0,
        )
        try {
            MessageMapping.fromColumns(corrupt)
            fail("expected IllegalArgumentException for unknown kind")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("mystery"))
        }
    }

    // ------------------------------------------------------------- insertion --

    @Test
    fun `message ordering is insertion order which is what compaction needs`() {
        val transcript = listOf(
            ChatMessage.System("system"),
            ChatMessage.User("one"),
            ChatMessage.Assistant("two"),
            ChatMessage.ToolObservation("t", "three", success = true),
            ChatMessage.User("four"),
        )

        // Simulate the DAO: ids are assigned by insertion order.
        val stored = transcript.mapIndexed { index, message ->
            MessageMapping.toColumns(message, sessionId = 1, createdAt = index.toLong(), id = index + 1L)
        }.sortedBy { it.id } // == ORDER BY id ASC in MessageDao.forSession

        assertEquals(transcript, stored.map(MessageMapping::fromColumns))
    }

    // ---------------------------------------------------------------- instant --

    @Test
    fun `instant survives the epoch-millis round trip`() {
        val converters = PiDroidTypeConverters()
        val instant = java.time.Instant.ofEpochMilli(1_700_000_000_123)

        val millis = converters.instantToEpochMillis(instant)

        assertEquals(1_700_000_000_123L, millis)
        assertEquals(instant, converters.epochMillisToInstant(millis))
        // Nullable, because Room columns are nullable and a null must not become 0.
        assertNull(converters.instantToEpochMillis(null))
        assertNull(converters.epochMillisToInstant(null))
    }

    private fun row(id: Long, importance: Float, createdAt: Long) = MemoryEntity(
        id = id,
        text = "text $id",
        keywords = "text",
        importance = importance,
        createdAt = createdAt,
    )

    private companion object {
        /**
         * A MATCH expression is only ever a sequence of double-quoted words joined by
         * OR. Anything else escaping these boundaries is a bug by definition.
         */
        val SAFE_EXPRESSION = Regex("""^(" ?[a-z0-9]+" ?)( OR (" ?[a-z0-9]+" ?))*$""")
    }
}
