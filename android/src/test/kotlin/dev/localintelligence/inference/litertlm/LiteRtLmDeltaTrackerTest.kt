package dev.localintelligence.inference.litertlm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LiteRtLmDeltaTracker] exists because LiteRT-LM's streaming contract is
 * ambiguous: `MessageCallback.onMessage` is specified as carrying the whole
 * reply so far, while a streaming API that a caller joins is expected to carry
 * deltas. Guessing wrong is not cosmetic — a cumulative runtime appended naively
 * re-renders the entire answer once per token.
 *
 * So both shapes are pinned here, and the cross case (a runtime that restarts
 * its text) is pinned too, because that is the one that would silently drop
 * output.
 */
class LiteRtLmDeltaTrackerTest {

    @Test
    fun `delta chunks pass through unchanged`() {
        val tracker = LiteRtLmDeltaTracker()
        val out = mutableListOf<String>()

        out += tracker.accept("Hello")
        out += tracker.accept(", ")
        out += tracker.accept("world")

        assertEquals(listOf("Hello", ", ", "world"), out)
        assertEquals("Hello, world", tracker.text())
    }

    @Test
    fun `cumulative chunks emit only the new tail`() {
        val tracker = LiteRtLmDeltaTracker()
        val out = mutableListOf<String>()

        // Exactly what LiteRT-LM's callback is specified to send.
        out += tracker.accept("Hello")
        out += tracker.accept("Hello, ")
        out += tracker.accept("Hello, world")

        assertEquals(
            "a cumulative runtime must not re-render what it already sent",
            listOf("Hello", ", ", "world"),
            out,
        )
        assertEquals("Hello, world", tracker.text())
    }

    @Test
    fun `a cumulative stream reconciles to the same text as a delta one`() {
        val cumulative = LiteRtLmDeltaTracker()
        val delta = LiteRtLmDeltaTracker()
        val tokens = listOf("Hello", ", ", "world", "!")

        // The same reply, delivered the two different ways LiteRT-LM's contract
        // allows. Both must reconstruct the identical answer -- this is the
        // property that makes the backend safe to point at either runtime.
        var running = ""
        tokens.forEach { running += it; cumulative.accept(running) }
        tokens.forEach { delta.accept(it) }

        assertEquals("Hello, world!", cumulative.text())
        assertEquals(cumulative.text(), delta.text())
    }

    @Test
    fun `an empty chunk is a no-op`() {
        val tracker = LiteRtLmDeltaTracker()
        assertEquals("", tracker.accept(""))
        tracker.accept("abc")
        assertEquals("", tracker.accept(""))
        assertEquals("abc", tracker.text())
    }

    @Test
    fun `a runtime that restarts its text is treated as a new delta`() {
        val tracker = LiteRtLmDeltaTracker()
        tracker.accept("First answer")
        // A tool-call round trip: LiteRT-LM re-enters generation and the text
        // restarts rather than extending. Treating this as cumulative would drop
        // every character of the second answer.
        tracker.accept("Second answer")

        assertEquals("First answerSecond answer", tracker.text())
    }

    @Test
    fun `a repeated chunk in a delta stream is not swallowed`() {
        val tracker = LiteRtLmDeltaTracker()
        val out = mutableListOf<String>()

        // A real shape: a model repeating a token. A tracker that re-decided the
        // mode per chunk would see "word " extend "word ", wrongly conclude
        // cumulative, and drop every chunk after the first -- which silently
        // truncates the answer to a single token.
        repeat(5) { out += tracker.accept("word ") }

        assertEquals(List(5) { "word " }, out)
        assertEquals("word word word word word ", tracker.text())
    }

    @Test
    fun `a re-send is swallowed once cumulative mode is proven`() {
        val tracker = LiteRtLmDeltaTracker()
        // Strict growth proves cumulative mode...
        tracker.accept("Hello")
        tracker.accept("Hello, ")
        tracker.accept("Hello, world")
        // ...and now the runtime re-sends the completed answer, which is a real
        // flush pattern. It adds nothing and must not be emitted.
        assertEquals("", tracker.accept("Hello, world"))
        assertEquals("", tracker.accept("Hello, world"))
        assertEquals("Hello, world", tracker.text())
    }

    @Test
    fun `a cumulative stream that ends with a one-word chunk is not dropped`() {
        val tracker = LiteRtLmDeltaTracker()
        val out = mutableListOf<String>()

        // Strict growth, so cumulative mode. The last chunk extends the previous
        // text and must contribute its tail.
        out += tracker.accept("The answer")
        out += tracker.accept("The answer is")
        out += tracker.accept("The answer is 4")

        assertEquals(listOf("The answer", " is", " 4"), out)
        assertEquals("The answer is 4", tracker.text())
    }

    @Test
    fun `unicode is not split by code unit`() {
        val tracker = LiteRtLmDeltaTracker()
        val out = mutableListOf<String>()

        // A surrogate pair split across two callbacks is the classic way a
        // substring-based delta implementation corrupts output.
        val text = "héllo wörld 😀"
        text.forEach { ch -> out += tracker.accept(ch.toString()) }

        assertEquals(text, out.joinToString(""))
        assertEquals(text, tracker.text())
    }

    @Test
    fun `length tracks the emitted text`() {
        val tracker = LiteRtLmDeltaTracker()
        assertEquals(0, tracker.length())
        tracker.accept("Hello")
        tracker.accept(", ")
        assertEquals(7, tracker.length())
    }
}