package dev.localintelligence.core.tool

import dev.localintelligence.core.documents.DocumentChunker
import dev.localintelligence.core.documents.DocumentIndex
import dev.localintelligence.core.documents.IndexedDocument

/**
 * What one `observations.read` call shows the model: a part of a stored tool
 * result, the place it came from, and how to read on. Pure and total, so every
 * [ObservationStore] renders reads the same way.
 *
 * ## TWO WAYS TO READ
 *
 *  - **By query.** The text is split and scored exactly as `documents.search`
 *    does it ([DocumentChunker], [DocumentIndex], BM25), and the best passages are
 *    shown with their character range in the stored text. The model can then read
 *    on from the end of a passage by offset. A query that matches nothing falls
 *    back to reading in order, so the call still returns text instead of costing a
 *    step to say "no match".
 *  - **By offset.** The characters from the offset, with the range shown and the
 *    offset of the next part named. Ranges are 0-based and end-exclusive, so the
 *    next offset is the end of the range shown.
 *
 * Everything returned, including the lines around the text, fits in the
 * requested size, which is between [MIN_READ_CHARS] and
 * [ObservationStore.MAX_READ_CHARS]: below the loop's observation budget, so the
 * "read on" line is never cut off.
 */
object ObservationReader {

    /**
     * The smallest read. The lines around the text take up to about 290 characters
     * (a no-match note, a long tool name, six-digit offsets), so this leaves room
     * for text even then. A smaller request is raised to this.
     */
    const val MIN_READ_CHARS: Int = 400

    /** Passages scored per query read. Each is up to 800 characters, so more would not fit. */
    private const val MAX_PASSAGES = 4

    /** Characters of a passage used to find it again in the stored text. */
    private const val PROBE_CHARS = 48

    /**
     * @param stored the stored text, at most [ObservationStore.MAX_STORED_CHARS].
     * @param originalChars how long the tool's output was before it was capped.
     */
    fun read(
        id: Long,
        toolName: String,
        stored: String,
        originalChars: Int,
        query: String?,
        offset: Int,
        maxChars: Int,
    ): String {
        val budget = maxChars.coerceIn(MIN_READ_CHARS, ObservationStore.MAX_READ_CHARS)
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        if (q != null) {
            byQuery(id, toolName, stored, q, budget)?.let { return it }
            val note = "Nothing in obs#$id matches \"${q.take(60)}\", so here it is in order."
            return byOffset(id, toolName, stored, originalChars, offset, budget, note)
        }
        return byOffset(id, toolName, stored, originalChars, offset, budget, note = null)
    }

    private fun byOffset(
        id: Long,
        toolName: String,
        stored: String,
        originalChars: Int,
        offset: Int,
        budget: Int,
        note: String?,
    ): String {
        val total = stored.length
        if (total == 0) return "obs#$id from $toolName is empty."
        var start = offset.coerceAtLeast(0)
        if (start >= total) {
            return "obs#$id from $toolName has ${groupedCount(total)} chars, so offset $offset is past the end. " +
                "Use an offset below $total."
        }
        if (stored[start].isLowSurrogate()) start++

        val lost = (originalChars - total).coerceAtLeast(0)
        val notKept = if (lost > 0) " (the last ${groupedCount(lost)} chars were not kept)" else ""
        fun header(end: Int) = (if (note != null) "$note\n" else "") +
            "obs#$id from $toolName, chars ${groupedCount(start)}–${groupedCount(end)} of ${groupedCount(total)}$notKept:"
        fun footer(end: Int) =
            if (end < total) "Next part: ${ObservationStore.READ_TOOL} with id $id and offset $end."
            else "End of obs#$id."

        // Sized for the longest frame, so the frame drawn for the real end always fits.
        val widest = (start + budget).coerceAtMost(total)
        val frame = header(widest).length + maxOf(footer(widest).length, footer(total).length) + 2
        var end = (start + (budget - frame).coerceAtLeast(1)).coerceAtMost(total)
        if (end < total && stored[end - 1].isHighSurrogate()) end--
        if (end <= start) end = (start + 1).coerceAtMost(total)
        return header(end) + "\n" + stored.substring(start, end) + "\n" + footer(end)
    }

    /** The best passages for [query], or null when no passage matches. */
    private fun byQuery(id: Long, toolName: String, stored: String, query: String, budget: Int): String? {
        val found = DocumentIndex.build(listOf(IndexedDocument("obs$id", "obs#$id", stored)))
            .search(query, MAX_PASSAGES)
        if (found.isEmpty()) return null
        val ranges = locate(stored, DocumentChunker.chunk(stored))

        val header = "obs#$id from $toolName, ${groupedCount(stored.length)} chars. " +
            "Best matches for \"${query.take(60)}\":"
        val bestEnd = ranges.getOrNull(found.first().number - 1)?.last?.plus(1)
        val footer = if (bestEnd != null && bestEnd < stored.length) {
            "Read on: ${ObservationStore.READ_TOOL} with id $id and offset $bestEnd."
        } else {
            "Read in order: ${ObservationStore.READ_TOOL} with id $id and an offset."
        }

        var room = budget - header.length - footer.length - 2
        val lines = ArrayList<String>()
        for (p in found) {
            val range = ranges.getOrNull(p.number - 1)
            val label = if (range != null) {
                "[chars ${groupedCount(range.first)}–${groupedCount(range.last + 1)}] "
            } else {
                "[passage ${p.number}] "
            }
            val line = label + p.text
            val cost = line.length + 1
            if (cost <= room) {
                lines += line
                room -= cost
            } else if (lines.isEmpty() && room > label.length + 1) {
                lines += cutAt(line, room - 2) + "…"
                break
            } else {
                break
            }
        }
        return header + "\n" + lines.joinToString("\n") + "\n" + footer
    }

    /**
     * Where each passage of [chunks] sits in [text], as a 0-based character range, or
     * null where it cannot be found.
     *
     * [DocumentChunker] collapses whitespace, so a passage is not a substring of the
     * stored text. It is a substring of the stored text with whitespace collapsed the
     * same way, which is built here with a map back to the original positions.
     * Passages are found whole and in order, each after the start of the one before,
     * so repeated text maps to the right place. The one case that is approximate is
     * a passage holding a single word longer than a passage, which the chunker cuts
     * with a space: it is then found by its first [PROBE_CHARS] characters and its
     * end estimated from its length.
     */
    private fun locate(text: String, chunks: List<String>): List<IntRange?> {
        val flat = StringBuilder(text.length)
        val origin = IntArray(text.length)
        var pendingSpace = -1
        for (i in text.indices) {
            val c = text[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r') {
                if (flat.isNotEmpty() && pendingSpace < 0) pendingSpace = i
                continue
            }
            if (pendingSpace >= 0) {
                origin[flat.length] = pendingSpace
                flat.append(' ')
                pendingSpace = -1
            }
            origin[flat.length] = i
            flat.append(c)
        }
        val collapsed = flat.toString()

        var from = 0
        return chunks.map { chunk ->
            if (chunk.isEmpty()) return@map null
            // The whole passage first: a short probe is ambiguous in repetitive text.
            val exact = collapsed.indexOf(chunk, from)
            val start = if (exact >= 0) exact else collapsed.indexOf(chunk.take(PROBE_CHARS), from)
            if (start < 0) return@map null
            from = start + 1
            val end = (start + chunk.length).coerceAtMost(collapsed.length)
            origin[start]..origin[end - 1]
        }
    }

    /** The first [n] characters of [s], not ending inside a surrogate pair. */
    private fun cutAt(s: String, n: Int): String {
        var end = n.coerceIn(0, s.length)
        if (end > 0 && s[end - 1].isHighSurrogate()) end--
        return s.substring(0, end)
    }
}
