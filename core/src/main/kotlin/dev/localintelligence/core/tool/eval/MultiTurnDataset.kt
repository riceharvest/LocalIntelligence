package dev.localintelligence.core.tool.eval

import dev.localintelligence.core.tool.eval.TurnIntent.ELAPSED_REFERENCE
import dev.localintelligence.core.tool.eval.TurnIntent.OBSERVATION_DEPENDENT
import dev.localintelligence.core.tool.eval.TurnIntent.PRONOUN_REFERENCE
import dev.localintelligence.core.tool.eval.TurnIntent.SELF_CONTAINED

/**
 * Multi-turn conversations, in the shape the product is actually used in.
 *
 * ## Provenance, stated the same way [SelectorDataset] states its own
 *
 * Hand-written plausible English. NOT transcribed from a conversation log —
 * this repository has no telemetry, so any "real user phrasing" claim would be
 * a fiction. What these are is a set of conversations written to be hard in the
 * specific way multi-turn requests are hard: the subject is established once
 * and referred to later, and a follow-up depends on a result the tool returned
 * rather than on anything the user said twice.
 *
 * ## THE CONTROL GROUP IS HALF THE CORPUS, DELIBERATELY
 *
 * `MultiTurnDataset.controls` is not filler. A corpus made only of
 * referential turns would report a catastrophic failure rate that says nothing
 * about the selector, because every single case would be unanswerable from its
 * own words by construction. The controls are multi-turn conversations whose
 * turns are each independently answerable — a real user does both in one
 * session — and they are scored in the same run, at the same k, by the same
 * code. The gap between the two groups is the measurement. A corpus with no
 * control group can only produce a number, and a number without a control is
 * the exact defect that got the last fake E2E suite deleted.
 *
 * ## Why the tool set is not uniform here either
 *
 * Real sessions are dominated by a few tools, so the distribution is not
 * uniform and per-tool counts are printed by the harness. But the
 * [MultiTurnDataset.UNCOVERED_BY_DESIGN] set is named explicitly: those tools
 * have no natural second turn, and pretending otherwise would mean inventing
 * a conversation nobody has. The harness prints them as a KNOWN GAP rather than
 * letting a zero read as coverage.
 */
object MultiTurnDataset {

    /**
     * Tools with no multi-turn case, and why.
     *
     * Stated in the source rather than discovered at print time so that adding
     * a tool to the corpus is a deliberate act with a reason attached, instead
     * of a gap that shows up as a zero in a table nobody interrogates.
     *
     * Read this before concluding a tool is untestable in multi-turn. Three of
     * these DO have cases below; this list is only ever printed for tools with
     * a zero, and it is a prompt to write one, not a licence to skip one.
     */
    val UNCOVERED_BY_DESIGN: Map<String, String> = mapOf(
        "device.vibrate" to "ring/buzz has no natural follow-up that needs another tool",
        "apps.list" to "listing installed apps is a terminal request in a phone session",
        "clipboard.read" to "a clipboard read-back is almost always its own turn",
    )

    /** Conversations whose subject carries across turns. */
    val referential: List<MultiTurnScenario> = listOf(

        // The canonical failure. "that" is the battery reading from two turns
        // back. The word "battery" appears NOWHERE in the final utterance, so
        // device.battery scores 0 on the turn that needs it — while the model,
        // which was shown the earlier turn, knows exactly what "that" is.
        MultiTurnScenario(
            id = "battery-then-remind",
            title = "Check battery, then be reminded to check it again",
            turns = listOf(
                ScenarioTurn(
                    utterance = "how much battery is left",
                    expected = setOf("device.battery"),
                    intent = SELF_CONTAINED,
                    calls = "device.battery",
                    observation = "Battery 34%, about 2h 10m remaining. Not charging.",
                    assistant = "You're on 34%, roughly two hours left.",
                ),
                ScenarioTurn(
                    utterance = "that's lower than I expected",
                    expected = setOf("device.battery"),
                    intent = PRONOUN_REFERENCE,
                    refersTo = 1,
                ),
                ScenarioTurn(
                    // The product question in one line: the referent is 2 turns
                    // back, the reminder is the task, and battery is the tool
                    // the user will expect to be re-checked.
                    utterance = "remind me to check that again at 7",
                    expected = setOf("alarm.create", "device.battery"),
                    intent = ELAPSED_REFERENCE,
                    refersTo = 2,
                    calls = "alarm.create",
                    observation = "Alarm set for 19:00, label \"check battery\".",
                    assistant = "Done — I'll remind you at 7pm to check it again.",
                ),
            ),
        ),

        // A contact found in turn 1 becomes the target of a message in turn 3.
        // notifications.reply is the tool; nothing in "text him" says "contact"
        // or "notification", and the contact id exists only inside an
        // observation the selector is structurally denied.
        MultiTurnScenario(
            id = "contact-then-message",
            title = "Look up a person, then message them, then message the other one",
            turns = listOf(
                ScenarioTurn(
                    utterance = "find Ben's number",
                    expected = setOf("contacts.search"),
                    intent = SELF_CONTAINED,
                    calls = "contacts.search",
                    observation = "Ben Okafor, +44 7700 900412, ben@example.com",
                    assistant = "Ben Okafor — 07700 900412.",
                ),
                ScenarioTurn(
                    utterance = "what was his email again",
                    expected = setOf("contacts.get", "contacts.search"),
                    intent = PRONOUN_REFERENCE,
                    refersTo = 1,
                ),
                ScenarioTurn(
                    utterance = "text him I'm running about ten minutes late",
                    expected = setOf("notifications.reply", "apps.share", "contacts.search"),
                    intent = ELAPSED_REFERENCE,
                    refersTo = 1,
                ),
            ),
        ),

        // The observation-dependent case the selector cannot win by design.
        // The file name exists ONLY inside a web.fetch observation. Feeding
        // observations to the selector is exactly the exfiltration path
        // SessionKeywordTrustTest closes, so this turn is a real tension
        // between "correct" and "safe" and the harness reports what happens
        // rather than asserting either answer.
        MultiTurnScenario(
            id = "web-then-save",
            title = "Fetch a page, then save part of it to a file",
            turns = listOf(
                ScenarioTurn(
                    utterance = "what does the release notes page say",
                    expected = setOf("web.fetch"),
                    intent = SELF_CONTAINED,
                    calls = "web.fetch",
                    observation = "v0.4.0 — added contacts.get, fixed alarm cancel, 3 security fixes.",
                    assistant = "v0.4.0: added contacts.get, fixed alarm cancel, three security fixes.",
                ),
                ScenarioTurn(
                    utterance = "save that to a file called release-notes.txt",
                    expected = setOf("files.write_text", "files.read_text"),
                    intent = OBSERVATION_DEPENDENT,
                    refersTo = 1,
                ),
                ScenarioTurn(
                    utterance = "and what did it say about the security fixes",
                    expected = setOf("files.read_text", "web.fetch"),
                    intent = ELAPSED_REFERENCE,
                    refersTo = 2,
                ),
            ),
        ),

        // A file located by search, then read, then deleted. Three turns, one
        // subject, and the subject is a string that only ever existed in an
        // observation.
        MultiTurnScenario(
            id = "file-then-read-then-delete",
            title = "Find a file, read it, then get rid of it",
            turns = listOf(
                ScenarioTurn(
                    utterance = "is there a PDF about the mortgage anywhere",
                    expected = setOf("files.search"),
                    intent = SELF_CONTAINED,
                    calls = "files.search",
                    observation = "Documents/mortgage-2026.pdf (1.2 MB)",
                    assistant = "Yes — Documents/mortgage-2026.pdf.",
                ),
                ScenarioTurn(
                    utterance = "what does it say",
                    expected = setOf("files.read_text"),
                    intent = OBSERVATION_DEPENDENT,
                    refersTo = 1,
                ),
                ScenarioTurn(
                    utterance = "delete it, I've got the details",
                    expected = setOf("files.delete"),
                    intent = PRONOUN_REFERENCE,
                    refersTo = 1,
                ),
            ),
        ),

        // Calendar: check the day, then add to it, then check the new entry
        // back. The middle turn is self-contained; the last is not, and the
        // difference between them is the whole measurement.
        MultiTurnScenario(
            id = "calendar-add-then-verify",
            title = "Look at the day, add a meeting, confirm it landed",
            turns = listOf(
                ScenarioTurn(
                    utterance = "what's on my calendar today",
                    expected = setOf("calendar.search"),
                    intent = SELF_CONTAINED,
                    calls = "calendar.search",
                    observation = "09:00 standup; 14:00 dentist; 17:30 gym.",
                    assistant = "Standup at 9, dentist at 2, gym at 5:30.",
                ),
                ScenarioTurn(
                    utterance = "add a coffee with Priya at 4 this afternoon",
                    expected = setOf("calendar.create"),
                    intent = SELF_CONTAINED,
                    calls = "calendar.create",
                    observation = "Created: Coffee with Priya, today 16:00-16:45.",
                    assistant = "Added coffee with Priya at 4pm.",
                ),
                ScenarioTurn(
                    utterance = "is that definitely in there",
                    expected = setOf("calendar.search"),
                    intent = OBSERVATION_DEPENDENT,
                    refersTo = 1,
                ),
            ),
        ),

        // Alarms, where create and cancel sit next to each other and share
        // almost every tag. "turn it off" is lexically closer to the wrong
        // tool than to the right one, which is the near-miss risk a shared
        // session makes worse rather than better.
        MultiTurnScenario(
            id = "alarm-create-then-adjust",
            title = "Set an alarm, then move it, then check what's set",
            turns = listOf(
                ScenarioTurn(
                    utterance = "set an alarm for 7am",
                    expected = setOf("alarm.create"),
                    intent = SELF_CONTAINED,
                    calls = "alarm.create",
                    observation = "Alarm set 07:00 daily, label \"wake up\".",
                    assistant = "Alarm set for 7am.",
                ),
                ScenarioTurn(
                    utterance = "actually make it half six",
                    expected = setOf("alarm.create", "alarm.cancel"),
                    intent = OBSERVATION_DEPENDENT,
                    refersTo = 1,
                ),
                ScenarioTurn(
                    utterance = "what have I got set for tomorrow morning",
                    expected = setOf("alarm.list"),
                    intent = PRONOUN_REFERENCE,
                    refersTo = 1,
                ),
            ),
        ),

        // Notification chain: list, act on one, then report. The reply tool and
        // the dismiss tool and the list tool all share the tag "notification",
        // so the selector is choosing between near-identical descriptions using
        // a pronoun.
        MultiTurnScenario(
            id = "notifications-triage",
            title = "Check notifications, dismiss one, reply to another",
            turns = listOf(
                ScenarioTurn(
                    utterance = "what notifications do I have",
                    expected = setOf("notifications.list"),
                    intent = SELF_CONTAINED,
                    calls = "notifications.list",
                    observation = "2 notifications: Spotify (promo), WhatsApp — Priya (8m ago).",
                    assistant = "A Spotify promo, and a WhatsApp message from Priya.",
                ),
                ScenarioTurn(
                    utterance = "get rid of the Spotify one",
                    expected = setOf("notifications.dismiss"),
                    intent = PRONOUN_REFERENCE,
                    refersTo = 1,
                ),
                ScenarioTurn(
                    utterance = "reply to Priya with 'on my way'",
                    expected = setOf("notifications.reply"),
                    intent = ELAPSED_REFERENCE,
                    refersTo = 1,
                ),
            ),
        ),

        // The long one. Five turns, and the final turn needs a tool whose only
        // mention was four turns back. This is the shape a real session takes
        // and the shape a two-turn harness can never produce.
        MultiTurnScenario(
            id = "long-session-tail-reference",
            title = "A five-turn session whose last turn reaches back to the first",
            turns = listOf(
                ScenarioTurn(
                    utterance = "how much storage do I have left",
                    expected = setOf("device.info"),
                    intent = SELF_CONTAINED,
                    calls = "device.info",
                    observation = "Storage: 12.4 GB free of 128 GB (9.7%).",
                    assistant = "12.4 GB free of 128.",
                ),
                ScenarioTurn(
                    utterance = "set an alarm for 6:30",
                    expected = setOf("alarm.create"),
                    intent = SELF_CONTAINED,
                    calls = "alarm.create",
                    observation = "Alarm set 06:30.",
                    assistant = "Alarm set for 6:30.",
                ),
                ScenarioTurn(
                    utterance = "what's my battery at",
                    expected = setOf("device.battery"),
                    intent = SELF_CONTAINED,
                    calls = "device.battery",
                    observation = "Battery 71%.",
                    assistant = "71%.",
                ),
                ScenarioTurn(
                    utterance = "vibrate the phone so I can find it",
                    expected = setOf("device.vibrate"),
                    intent = SELF_CONTAINED,
                    calls = "device.vibrate",
                    observation = "Vibrated for 5 seconds.",
                    assistant = "Buzzed.",
                ),
                ScenarioTurn(
                    // Four turns back. The user is back on the storage question
                    // and the only word that ties this turn to it is "that".
                    utterance = "can you check that again, it's probably the downloads",
                    expected = setOf("device.info", "files.list", "files.delete"),
                    intent = ELAPSED_REFERENCE,
                    refersTo = 4,
                ),
            ),
        ),

        // A settings detour: the user goes to settings, gets an answer, and
        // then asks for a different settings page. device.open_settings and
        // device.battery overlap on "battery saver", so a shared session is
        // where that ambiguity gets resolved or does not.
        MultiTurnScenario(
            id = "settings-detour",
            title = "Open settings, check something, go somewhere else in settings",
            turns = listOf(
                ScenarioTurn(
                    utterance = "open the wifi settings",
                    expected = setOf("device.open_settings"),
                    intent = SELF_CONTAINED,
                    calls = "device.open_settings",
                    observation = "Opened Wi-Fi settings.",
                    assistant = "Wi-Fi settings are open.",
                ),
                ScenarioTurn(
                    utterance = "is battery saver on",
                    expected = setOf("device.battery", "device.open_settings"),
                    intent = PRONOUN_REFERENCE,
                    refersTo = 1,
                ),
                ScenarioTurn(
                    utterance = "take me back to the sound settings",
                    expected = setOf("device.open_settings"),
                    intent = PRONOUN_REFERENCE,
                    refersTo = 1,
                ),
            ),
        ),
    )

    /**
     * The control group: multi-turn conversations where every turn stands alone.
     *
     * These are real conversations, not a degenerate case — a user checking
     * three unrelated things in one sitting is completely ordinary, and it is
     * the shape most likely to be scored well. They exist so that the
     * referential failure rate is measured against a baseline from the same
     * code, the same k and the same tool set, rather than against a number
     * somebody asserted was fine.
     */
    val controls: List<MultiTurnScenario> = listOf(
        MultiTurnScenario(
            id = "control-three-asks",
            title = "Three unrelated one-shot requests in a row",
            turns = listOf(
                ScenarioTurn(
                    utterance = "how much battery is left",
                    expected = setOf("device.battery"),
                    intent = SELF_CONTAINED,
                    calls = "device.battery",
                    observation = "Battery 34%, about 2h 10m remaining.",
                    assistant = "34%, about two hours.",
                ),
                ScenarioTurn(
                    utterance = "set an alarm for 6:30",
                    expected = setOf("alarm.create"),
                    intent = SELF_CONTAINED,
                    calls = "alarm.create",
                    observation = "Alarm set 06:30.",
                    assistant = "Set for 6:30.",
                ),
                ScenarioTurn(
                    utterance = "list my alarms",
                    expected = setOf("alarm.list"),
                    intent = SELF_CONTAINED,
                    calls = "alarm.list",
                    observation = "1 alarm: 06:30 daily.",
                    assistant = "One alarm, 6:30 daily.",
                ),
            ),
        ),
        MultiTurnScenario(
            id = "control-device-sweep",
            title = "A device-info sweep, each turn independently answerable",
            turns = listOf(
                ScenarioTurn(
                    utterance = "what phone is this",
                    expected = setOf("device.info"),
                    intent = SELF_CONTAINED,
                    calls = "device.info",
                    observation = "Pixel 8 Pro, Android 15.",
                    assistant = "Pixel 8 Pro on Android 15.",
                ),
                ScenarioTurn(
                    utterance = "how much storage is left",
                    expected = setOf("device.info"),
                    intent = SELF_CONTAINED,
                    calls = "device.info",
                    observation = "12.4 GB free of 128 GB.",
                    assistant = "12.4 GB free.",
                ),
                ScenarioTurn(
                    utterance = "buzz the phone",
                    expected = setOf("device.vibrate"),
                    intent = SELF_CONTAINED,
                    calls = "device.vibrate",
                    observation = "Vibrated for 5 seconds.",
                    assistant = "Buzzed.",
                ),
                ScenarioTurn(
                    utterance = "open the settings",
                    expected = setOf("device.open_settings"),
                    intent = SELF_CONTAINED,
                    calls = "device.open_settings",
                    observation = "Opened Settings.",
                    assistant = "Settings are open.",
                ),
            ),
        ),
        MultiTurnScenario(
            id = "control-apps-and-web",
            title = "App and web requests in one session",
            turns = listOf(
                ScenarioTurn(
                    utterance = "open Spotify",
                    expected = setOf("apps.open"),
                    intent = SELF_CONTAINED,
                    calls = "apps.open",
                    observation = "Launched Spotify.",
                    assistant = "Spotify's open.",
                ),
                ScenarioTurn(
                    utterance = "what apps do I have installed",
                    expected = setOf("apps.list"),
                    intent = SELF_CONTAINED,
                    calls = "apps.list",
                    observation = "142 apps installed.",
                    assistant = "142 installed.",
                ),
                ScenarioTurn(
                    utterance = "fetch the news page for me",
                    expected = setOf("web.fetch"),
                    intent = SELF_CONTAINED,
                    calls = "web.fetch",
                    observation = "Top stories: ...",
                    assistant = "Here's the news.",
                ),
            ),
        ),
        MultiTurnScenario(
            id = "control-clipboard-and-files",
            title = "Clipboard and file turns that each name their own subject",
            turns = listOf(
                ScenarioTurn(
                    utterance = "copy the meeting location to the clipboard",
                    expected = setOf("clipboard.write"),
                    intent = SELF_CONTAINED,
                    calls = "clipboard.write",
                    observation = "Copied 28 characters.",
                    assistant = "Copied.",
                ),
                ScenarioTurn(
                    utterance = "what's on my clipboard",
                    expected = setOf("clipboard.read"),
                    intent = SELF_CONTAINED,
                    calls = "clipboard.read",
                    observation = "Room 4B, Riverside Building.",
                    assistant = "Room 4B, Riverside Building.",
                ),
                ScenarioTurn(
                    utterance = "what files are on my phone",
                    expected = setOf("files.list"),
                    intent = SELF_CONTAINED,
                    calls = "files.list",
                    observation = "Documents (14), Downloads (38), DCIM (212).",
                    assistant = "14 documents, 38 downloads, 212 photos.",
                ),
                ScenarioTurn(
                    utterance = "save this to a file called notes.txt",
                    expected = setOf("files.write_text"),
                    intent = SELF_CONTAINED,
                    calls = "files.write_text",
                    observation = "Wrote Downloads/notes.txt.",
                    assistant = "Saved.",
                ),
            ),
        ),
    )

    /** Every scenario, referential first so a reader meets the hard cases first. */
    val all: List<MultiTurnScenario> = referential + controls

    /** Every scored turn across every scenario, flattened. */
    val turns: List<ScenarioTurn> = all.flatMap { it.turns }

    /** How many turns expect each tool, for the coverage table. */
    fun coverageByTool(): Map<String, Int> =
        turns.flatMap { it.expected }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

    init {
        val dupes = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "duplicate scenario ids: $dupes" }

        // A corpus with a tool nobody reaches is a corpus with a hole in it,
        // and a hole is exactly what a headline number hides. Enforced at
        // construction so the harness cannot start with a known gap.
        val uncovered = EvalCase.ALL_TOOL_NAMES - coverageByTool().keys
        require(uncovered.isEmpty()) {
            "these shipped tools have no multi-turn turn: $uncovered. Either write " +
                "the conversation or record the reason in UNCOVERED_BY_DESIGN — a " +
                "zero in the coverage table is only acceptable when it is explained."
        }
    }
}
