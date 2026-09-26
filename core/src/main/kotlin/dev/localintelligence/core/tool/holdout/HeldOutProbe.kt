package dev.localintelligence.core.tool.holdout

import dev.localintelligence.core.tool.AgentTool
import dev.localintelligence.core.tool.LexicalToolSelector

/**
 * HELD-OUT generalisation probe. Not part of the repository and not a test.
 *
 * ## Why this exists
 *
 * After the tag-list fix the 176-case harness reports 176/176 at k=10. That
 * number is no longer informative on its own: the tags were written while
 * reading those 176 utterances, so "every case now passes" is exactly what an
 * overfit looks like. A selector tuned on its own eval set is a selector that
 * has memorised the eval set.
 *
 * So this is a SECOND, INDEPENDENT set, written after the tags were frozen,
 * deliberately avoiding the vocabulary the fix introduced:
 *
 *  - No tag string added by this change-set is reused as a whole phrase.
 *  - The style is different: most of these are terse imperatives or Dutch,
 *    where the dataset is English conversational.
 *  - Several target the SAME tools by a route the fix did not touch, which is
 *    the only way to tell a real vocabulary improvement from 13 patches that
 *    happen to fit.
 *
 * It is deliberately NOT a pass/fail gate and it is not wired into CI. Its
 * only job is to be a number that the tag fix did not see coming.
 */
private data class Case(val utterance: String, val expected: Set<String>)

private val HELD_OUT = listOf(
    // --- device.battery, reached by routes the new tags do not spell out ---
    Case("battery percentage", setOf("device.battery")),
    Case("is my phone about to flat", setOf("device.battery")),
    Case("how much charge is left", setOf("device.battery")),
    Case("batterij", setOf("device.battery")),
    // --- calendar ---
    Case("do i have anything scheduled", setOf("calendar.search")),
    Case("what is on my agenda", setOf("calendar.search")),
    Case("put a dentist appointment in for friday", setOf("calendar.create")),
    Case("reserve two hours tomorrow afternoon", setOf("calendar.create")),
    // --- files ---
    Case("locate the tax document", setOf("files.search")),
    Case("which file did I download last week", setOf("files.search")),
    Case("get rid of the old spreadsheet", setOf("files.delete")),
    Case("verwijder dat bestand", setOf("files.delete")),
    // --- contacts ---
    Case("what is jans phone number", setOf("contacts.search")),
    Case("find sanders in my address book", setOf("contacts.search")),
    // --- notifications ---
    Case("any messages waiting", setOf("notifications.list")),
    Case("what notifications do i have", setOf("notifications.list")),
    Case("answer the message from my brother", setOf("notifications.reply")),
    // --- apps ---
    Case("which apps are on this handset", setOf("apps.list")),
    Case("start the maps application", setOf("apps.open")),
    // --- web ---
    Case("pull up that article online", setOf("web.fetch")),
    Case("what does the news site say", setOf("web.fetch")),
    // --- alarms, reached without "up at" / "need to be up" ---
    Case("ring me at half seven", setOf("alarm.create")),
    Case("wake me tomorrow morning", setOf("alarm.create")),
    // --- clipboard, device.info: NOT touched by the tag fix at all ---
    // Included precisely so the probe contains cases where the fix can only
    // ever be neutral. If these move, the measurement is not measuring what
    // it claims to.
    Case("what did I copy", setOf("clipboard.read")),
    Case("how much ram does this have", setOf("device.info")),
)

fun main() {
    val tools: List<AgentTool> = dev.localintelligence.core.tool.eval.AndroidToolSnapshot.tools
    val selector = LexicalToolSelector()

    var hit = 0
    val misses = ArrayList<String>()
    for (case in HELD_OUT) {
        val selected = selector.select(
            task = case.utterance,
            sessionKeywords = emptyList(),
            available = tools,
            maxTools = 10,
        ).map { it.definition.name }
        if (selected.any { it in case.expected }) {
            hit++
        } else {
            misses += "    \"${case.utterance}\" -> wanted ${case.expected.joinToString()}, got ${selected.joinToString()}"
        }
    }

    println("=".repeat(78))
    println("HELD-OUT GENERALISATION PROBE (independent of the 176-case dataset)")
    println("=".repeat(78))
    println("cases: ${HELD_OUT.size}   k=10   tools: ${tools.size}")
    println("recalled: $hit/${HELD_OUT.size}")
    println()
    if (misses.isEmpty()) {
        println("no misses")
    } else {
        println("MISSES (${misses.size})")
        misses.forEach { println(it) }
    }
}
