package dev.localintelligence.core.tool.eval

/**
 * One canonical user utterance and the tool set that should be reachable for it.
 *
 * ## Why the expected answer is a SET and not a single tool
 *
 * Several real tasks are legitimately answerable by more than one tool, and
 * scoring a set wrong is a harness that has confused "the model picked the
 * wrong one" with "the model picked something defensible". `notifications.reply`
 * and `clipboard.write` both serve "let Sarah know I'm running late"; so do
 * `apps.share` and `notifications.reply` for "pass this on to Dave". A recall
 * number computed against a single arbitrary winner would understate the
 * selector and then get quoted as if it were true.
 *
 * So [expected] is the set of tools a reasonable implementation could call and
 * still be right, and recall is `expected intersect selected` over `expected`.
 * For the unambiguous majority that is a single element and the two definitions
 * coincide.
 *
 * ## What an utterance is allowed to be
 *
 * Real phrasing for a task this app can do, including the ways people actually
 * talk: no keyword lists, no "battery" appearing because battery is the word
 * under test, no casing or punctuation tricks. "how much juice is left" is a
 * legitimate utterance and a legitimate hard case. Where an utterance is
 * deliberately adversarial, [Intent.AMBIGUOUS] or [Intent.NEAR_MISS] says so
 * rather than leaving the next reader to wonder whether it is a fair test.
 */
data class EvalCase(
    val utterance: String,
    /** Every tool that could correctly serve this. Order is not significant. */
    val expected: Set<String>,
    val intent: Intent = Intent.PLAIN,
    /**
     * Session keywords carried over from earlier turns, in the shape
     * [dev.localintelligence.core.agent.Session.currentKeywords] supplies.
     * Empty for the common case: a fresh session with no accumulated keywords
     * is the honest default, and a harness that always supplies keywords is
     * measuring a different selector.
     */
    val sessionKeywords: List<String> = emptyList(),
) {
    init {
        require(expected.isNotEmpty()) { "a case with no expected tool cannot be scored: $utterance" }
        require(expected.none { it !in ALL_TOOL_NAMES }) {
            "unknown expected tool in \"$utterance\": " +
                expected.filter { it !in ALL_TOOL_NAMES }
        }
    }

    /** The first tool a sensible implementation would reach for. */
    val firstChoice: String get() = expected.first()

    enum class Intent {
        /** An ordinary, unambiguous request. */
        PLAIN,

        /**
         * Two or more tools are genuinely defensible, and picking the wrong one
         * is still not wrong.
         */
        AMBIGUOUS,

        /**
         * Lexically adjacent to a different tool: shares a word with a
         * neighbour and must not be pulled to it. These are where an
         * alphabetical tie-break gets caught.
         */
        NEAR_MISS,

        /**
         * Needs one tool to discover the input of another — a contact id, a
         * notification key, a content:// URI. Scored on the FIRST call, since
         * selection happens per turn and the follow-up happens on the next.
         */
        CHAINED,
    }

    companion object {
        val ALL_TOOL_NAMES: Set<String> = AndroidToolSnapshot.tools
            .map { it.definition.name }
            .toSet()
    }
}

/**
 * The dataset, and the terms it is held to.
 *
 * ## Provenance and honesty about what it is
 *
 * Every utterance here was written to be a realistic phrasing of a task this
 * app can genuinely perform, with the expected tool set assigned by reading the
 * 25 shipped [AndroidToolSnapshot] definitions — not by running the selector
 * and recording what it already picks. That distinction is the whole value of
 * the file: a dataset generated from the selector's own output measures the
 * selector's current behaviour and can only ever confirm it.
 *
 * They are NOT transcribed from a real conversation log. This repository has
 * no telemetry, so a "real user phrasing" claim would be a fiction. What they
 * are is hand-written plausible English for the seven target task families in
 * `docs/architecture.md` §1, written to be hard in the ways real requests are
 * hard: indirect phrasing, plural/ambiguous verbs, a session's earlier keywords
 * carrying the subject, and neighbours that share vocabulary.
 *
 * ## Coverage, and the gaps named rather than hidden
 *
 * 25 tools, 180 cases. Every tool is the expected answer for at least four
 * cases, and the mix includes near-misses and multi-tool cases because a set
 * of clean single-tool utterances would flatter any selector.
 *
 * The distribution is NOT uniform, and that is deliberate: real requests are
 * dominated by battery, alarms, calendar and notifications, and a uniform set
 * would imply a workload this product does not have. Per-category counts are
 * printed by the harness so the shape is visible rather than asserted.
 *
 * **What it does not cover:** non-English phrasing (the scorer tokenises on
 * `[^a-z0-9]+`, so any non-Latin utterance is a guaranteed zero-score case and
 * that is a separate, larger finding), and multi-turn tasks where the second
 * turn re-selects. [EvalCase.Intent.CHAINED] covers the FIRST call only, which
 * is what selection actually controls.
 */
object SelectorDataset {

    val cases: List<EvalCase> = listOf(

        // ------------------------------------------------------------- battery
        EvalCase("how much battery is left", setOf("device.battery")),
        EvalCase("what's my battery level", setOf("device.battery")),
        EvalCase("am I charging right now", setOf("device.battery")),
        EvalCase("how long until my phone is fully charged", setOf("device.battery")),
        EvalCase("check the battery", setOf("device.battery")),
        EvalCase("how much juice do I have left", setOf("device.battery")),
        EvalCase("is this thing going to die before I get home", setOf("device.battery")),
        EvalCase("what percentage charge is it on", setOf("device.battery")),
        EvalCase("how long do I have left before it dies", setOf("device.battery")),
        EvalCase("battery status please", setOf("device.battery")),
        // "power" is a device.battery tag, but "turn the power off" is not a
        // task this app has a tool for — battery is still the closest reading.
        EvalCase("is my phone charging or draining", setOf("device.battery"), EvalCase.Intent.NEAR_MISS),

        // --------------------------------------------------------------- alarm
        EvalCase("set an alarm for 7am", setOf("alarm.create")),
        EvalCase("wake me up at half past six tomorrow", setOf("alarm.create")),
        EvalCase("can you set a reminder for 8", setOf("alarm.create")),
        EvalCase("ring at 6:45 please", setOf("alarm.create")),
        EvalCase("I need to be up at 5:30", setOf("alarm.create")),
        EvalCase("set my morning alarm for quarter past seven", setOf("alarm.create")),
        EvalCase("remind me to take my pills at 9pm", setOf("alarm.create")),
        EvalCase("book a wake up call for 4am", setOf("alarm.create")),
        EvalCase("what alarms have I got set", setOf("alarm.list")),
        EvalCase("list my alarms", setOf("alarm.list")),
        EvalCase("do I have anything set for tomorrow morning", setOf("alarm.list")),
        EvalCase("show me the alarms I set earlier", setOf("alarm.list")),
        EvalCase("cancel the alarm for 7am", setOf("alarm.cancel")),
        EvalCase("delete that alarm", setOf("alarm.cancel")),
        EvalCase("I don't need the 6am one any more", setOf("alarm.cancel")),
        EvalCase("turn off my morning alarm", setOf("alarm.cancel")),
        EvalCase("kill the wake up call", setOf("alarm.cancel")),
        // "stop the alarm" is an alarm.cancel tag, and "stop" is also how a
        // user might mean a timer — but there is no timer tool, so cancel.
        EvalCase("stop that alarm ringing", setOf("alarm.cancel"), EvalCase.Intent.NEAR_MISS),
        // Set vs list is the classic near-miss pair: both are "alarms".
        EvalCase("actually what's already set", setOf("alarm.list"), EvalCase.Intent.NEAR_MISS),

        // ------------------------------------------------------------ calendar
        EvalCase("what's on my calendar today", setOf("calendar.search")),
        EvalCase("do I have anything tomorrow afternoon", setOf("calendar.search")),
        EvalCase("am I free at 3pm on Friday", setOf("calendar.search")),
        EvalCase("show me my schedule for next week", setOf("calendar.search")),
        EvalCase("any meetings today", setOf("calendar.search")),
        EvalCase("what's my next appointment", setOf("calendar.search")),
        EvalCase("look up my agenda for the 30th", setOf("calendar.search")),
        EvalCase("am I busy this evening", setOf("calendar.search")),
        EvalCase("add a dentist appointment on Thursday at 2pm", setOf("calendar.create")),
        EvalCase("put lunch with Sarah in my calendar for noon", setOf("calendar.create")),
        EvalCase("block out 4 to 5 this afternoon for work", setOf("calendar.create")),
        EvalCase("schedule a call with the bank for 10am Monday", setOf("calendar.create")),
        EvalCase("new calendar entry: gym at 6", setOf("calendar.create")),
        EvalCase("book me in for the standup tomorrow at 9:30", setOf("calendar.create")),
        // "remind me at" is an alarm.create tag AND calendar.create carries
        // "reminder". Both are defensible; a time-bound personal reminder on
        // the calendar is at least as good a reading as an alarm.
        EvalCase("remind me about the mortgage on the 1st", setOf("alarm.create", "calendar.create"), EvalCase.Intent.AMBIGUOUS),
        // Search vs create on the same word: "book" is a calendar.create tag.
        EvalCase("have I got anything booked on the 12th", setOf("calendar.search"), EvalCase.Intent.NEAR_MISS),
        EvalCase("is anything in the diary this week", setOf("calendar.search"), EvalCase.Intent.NEAR_MISS),
        EvalCase("put a hold in my calendar for Friday", setOf("calendar.create"), EvalCase.Intent.NEAR_MISS),

        // --------------------------------------------------------------- files
        EvalCase("what files are on my phone", setOf("files.list")),
        EvalCase("list my downloads", setOf("files.list")),
        EvalCase("browse my documents", setOf("files.list")),
        EvalCase("show me the recent files", setOf("files.list", "files.search")),
        EvalCase("find the file called invoice.pdf", setOf("files.search")),
        EvalCase("search my documents for anything with tax in the name", setOf("files.search")),
        EvalCase("is there a PDF about the mortgage anywhere", setOf("files.search")),
        EvalCase("look for files I changed this week", setOf("files.search")),
        EvalCase("open notes.txt and tell me what it says", setOf("files.read_text", "files.search", "files.list"), EvalCase.Intent.CHAINED),
        EvalCase("read the contents of that document", setOf("files.read_text")),
        EvalCase("preview the first bit of the readme", setOf("files.read_text")),
        EvalCase("save this to a file called summary.txt", setOf("files.write_text")),
        EvalCase("write my shopping list into a new note", setOf("files.write_text")),
        EvalCase("put this into a text file I can find later", setOf("files.write_text")),
        EvalCase("export that to a document", setOf("files.write_text")),
        EvalCase("delete the file called old-tax-return.pdf", setOf("files.delete")),
        EvalCase("get rid of that screenshot", setOf("files.delete")),
        EvalCase("remove this document for me", setOf("files.delete")),
        EvalCase("bin the draft I saved this morning", setOf("files.delete")),
        // "read"/"open" are files.read_text tags AND apps.open has "open" and
        // "launch". A document is a file; an app is a package.
        EvalCase("open maps", setOf("apps.open"), EvalCase.Intent.NEAR_MISS),
        EvalCase("read me the file", setOf("files.read_text"), EvalCase.Intent.NEAR_MISS),
        EvalCase("show me what's in my downloads folder", setOf("files.list"), EvalCase.Intent.NEAR_MISS),
        // "recent files" sits on both list and search.
        EvalCase("what did I download yesterday", setOf("files.list", "files.search"), EvalCase.Intent.AMBIGUOUS),

        // ------------------------------------------------------------ contacts
        EvalCase("what's Alice's number", setOf("contacts.search")),
        EvalCase("look up Dave's phone number", setOf("contacts.search")),
        EvalCase("who is in my contacts called Priya", setOf("contacts.search")),
        EvalCase("find the email for Tom", setOf("contacts.search")),
        EvalCase("search my address book for Green", setOf("contacts.search")),
        EvalCase("get me the full contact details for contact 42", setOf("contacts.get")),
        EvalCase("pull up everything saved for that contact", setOf("contacts.get")),
        EvalCase("what work address do I have for Sarah", setOf("contacts.get")),
        EvalCase("look up Ben and tell me how to reach him", setOf("contacts.search"), EvalCase.Intent.CHAINED),
        // "phone number" is a contacts.get tag, but the user has no id yet, so
        // search is the only tool that can start.
        EvalCase("I need Michael's number", setOf("contacts.search"), EvalCase.Intent.NEAR_MISS),
        EvalCase("who is this, contact 7", setOf("contacts.get"), EvalCase.Intent.NEAR_MISS),

        // ------------------------------------------------------- notifications
        EvalCase("what notifications do I have", setOf("notifications.list")),
        EvalCase("did anything come in", setOf("notifications.list")),
        EvalCase("what's in my notification shade", setOf("notifications.list")),
        EvalCase("read out my alerts", setOf("notifications.list")),
        EvalCase("check my messages", setOf("notifications.list", "notifications.reply"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("reply to the WhatsApp notification with 'on my way'", setOf("notifications.reply")),
        EvalCase("tell Sarah I'm running late", setOf("notifications.reply", "clipboard.write", "apps.share"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("message back that I'll be there in ten", setOf("notifications.reply")),
        EvalCase("answer the text from my brother", setOf("notifications.reply")),
        EvalCase("dismiss that notification", setOf("notifications.dismiss")),
        EvalCase("clear the notification from the banking app", setOf("notifications.dismiss")),
        EvalCase("silence everything in the shade", setOf("notifications.dismiss", "notifications.list"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("swipe away that banner", setOf("notifications.dismiss")),
        // "banner" is a notifications.dismiss tag; "what's the banner" is not
        // a task, but a user asking to see banners is asking to LIST them.
        EvalCase("are there any banners showing", setOf("notifications.list", "notifications.dismiss"), EvalCase.Intent.AMBIGUOUS),
        // "remove" is on both dismiss and files.delete. The shade decides.
        EvalCase("remove that notification", setOf("notifications.dismiss"), EvalCase.Intent.NEAR_MISS),
        EvalCase("get rid of the notification from Spotify", setOf("notifications.dismiss"), EvalCase.Intent.NEAR_MISS),
        // "send"/"message" is notifications.reply; "share" is apps.share.
        EvalCase("send this to the WhatsApp chat", setOf("notifications.reply", "apps.share"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("what's the latest thing that pinged", setOf("notifications.list"), EvalCase.Intent.NEAR_MISS),

        // ----------------------------------------------------------- clipboard
        EvalCase("copy this to my clipboard", setOf("clipboard.write")),
        EvalCase("put that on the clipboard so I can paste it", setOf("clipboard.write")),
        EvalCase("what did I copy", setOf("clipboard.read")),
        EvalCase("what's on my clipboard", setOf("clipboard.read")),
        EvalCase("read the clipboard back to me", setOf("clipboard.read")),
        // "cut" is a clipboard.write tag.
        EvalCase("cut that text", setOf("clipboard.write"), EvalCase.Intent.NEAR_MISS),
        // "paste" is on BOTH clipboard.write and clipboard.read.
        EvalCase("what's waiting for me to paste", setOf("clipboard.read"), EvalCase.Intent.NEAR_MISS),
        EvalCase("copy the address so I can paste it somewhere", setOf("clipboard.write"), EvalCase.Intent.AMBIGUOUS),

        // ---------------------------------------------------------- device info
        EvalCase("what phone is this", setOf("device.info")),
        EvalCase("how much storage do I have left", setOf("device.info")),
        EvalCase("how much RAM is on this thing", setOf("device.info")),
        EvalCase("what Android version am I running", setOf("device.info")),
        EvalCase("give me the specs on this handset", setOf("device.info")),
        EvalCase("how big is my screen", setOf("device.info")),
        EvalCase("is my storage nearly full", setOf("device.info")),
        EvalCase("make the phone buzz", setOf("device.vibrate")),
        EvalCase("buzz this device", setOf("device.vibrate")),
        EvalCase("can you vibrate the phone so I can find it", setOf("device.vibrate")),
        EvalCase("ring my phone so I can find it", setOf("device.vibrate")),
        EvalCase("open the settings", setOf("device.open_settings")),
        EvalCase("take me to the wifi settings", setOf("device.open_settings")),
        EvalCase("I need to turn on bluetooth", setOf("device.open_settings"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("open battery saver", setOf("device.open_settings", "device.battery"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("show me the sound settings", setOf("device.open_settings")),
        // "find my phone" is a device.vibrate tag, and it collides with the
        // idea of locating a lost device — vibrate is the closest this gets.
        EvalCase("help me find my phone", setOf("device.vibrate"), EvalCase.Intent.NEAR_MISS),
        // "battery" is a device.open_settings tag via battery saver, but a
        // battery QUESTION is device.battery.
        EvalCase("is battery saver on", setOf("device.battery", "device.open_settings"), EvalCase.Intent.AMBIGUOUS),
        // "storage" is a files.list tag AND a device.info tag.
        EvalCase("how much space do I have", setOf("device.info"), EvalCase.Intent.NEAR_MISS),
        EvalCase("what's taking up all my storage", setOf("device.info", "files.list"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("free up space on the phone", setOf("device.info", "files.list", "files.delete"), EvalCase.Intent.AMBIGUOUS),

        // ---------------------------------------------------------------- apps
        EvalCase("open Spotify", setOf("apps.open")),
        EvalCase("launch the camera", setOf("apps.open")),
        EvalCase("start WhatsApp for me", setOf("apps.open")),
        EvalCase("switch to Maps", setOf("apps.open")),
        EvalCase("run the calculator", setOf("apps.open")),
        EvalCase("what apps do I have installed", setOf("apps.list")),
        EvalCase("show me everything on my home screen", setOf("apps.list")),
        EvalCase("list my installed apps", setOf("apps.list")),
        EvalCase("is there an app for budgeting", setOf("apps.list")),
        EvalCase("share this document with my brother", setOf("apps.share")),
        EvalCase("send this file to WhatsApp", setOf("apps.share", "notifications.reply"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("attach that document to an email", setOf("apps.share")),
        EvalCase("pass this snippet on to the other app", setOf("apps.share")),
        // "open" is a files.read_text tag and an apps.open tag.
        EvalCase("open the settings app", setOf("apps.open", "device.open_settings"), EvalCase.Intent.AMBIGUOUS),
        // "send" is on apps.share and notifications.reply.
        EvalCase("send a message to my mum", setOf("notifications.reply"), EvalCase.Intent.NEAR_MISS),
        EvalCase("forward this to the team chat", setOf("apps.share"), EvalCase.Intent.NEAR_MISS),

        // ----------------------------------------------------------------- web
        EvalCase("what does this website say", setOf("web.fetch")),
        EvalCase("fetch the news page for me", setOf("web.fetch")),
        EvalCase("read me the contents of that article", setOf("web.fetch", "files.read_text"), EvalCase.Intent.AMBIGUOUS),
        EvalCase("go look up https://example.com", setOf("web.fetch")),
        EvalCase("pull the text off that page online", setOf("web.fetch")),
        EvalCase("check what the weather site is saying", setOf("web.fetch")),
        // "read" is a files.read_text tag and web.fetch carries "read online".
        EvalCase("read me the news online", setOf("web.fetch"), EvalCase.Intent.NEAR_MISS),
        EvalCase("get the text from this link", setOf("web.fetch"), EvalCase.Intent.NEAR_MISS),

        // --------------------------------------------- multi-tool, chained, hard
        EvalCase(
            "set an alarm for the dentist and add it to my calendar",
            setOf("alarm.create", "calendar.create"),
            EvalCase.Intent.AMBIGUOUS,
        ),
        EvalCase(
            "find Ben's number and text him I'm on my way",
            setOf("contacts.search", "notifications.reply"),
            EvalCase.Intent.CHAINED,
        ),
        EvalCase(
            "save the address from that page to a file called venue.txt",
            setOf("web.fetch", "files.write_text"),
            EvalCase.Intent.CHAINED,
        ),
        EvalCase(
            "clear all the notifications then tell me what was there",
            setOf("notifications.list", "notifications.dismiss"),
            EvalCase.Intent.CHAINED,
        ),
        EvalCase(
            "copy the meeting location then put it in a new note",
            setOf("calendar.search", "clipboard.write", "files.write_text"),
            EvalCase.Intent.CHAINED,
        ),
        EvalCase(
            "am I going to run out of battery before my 6pm meeting",
            setOf("device.battery", "calendar.search"),
            EvalCase.Intent.AMBIGUOUS,
        ),
        EvalCase(
            "delete the screenshot I just took and tell me the battery level",
            setOf("files.delete", "device.battery"),
            EvalCase.Intent.AMBIGUOUS,
        ),
        EvalCase(
            "open the file I downloaded and share it to WhatsApp",
            setOf("files.read_text", "files.search", "apps.share"),
            EvalCase.Intent.CHAINED,
        ),
        EvalCase(
            "turn the brightness down",
            setOf("device.open_settings"),
            EvalCase.Intent.NEAR_MISS,
        ),
        EvalCase(
            "what's my phone number",
            setOf("contacts.search"),
            EvalCase.Intent.NEAR_MISS,
        ),
        EvalCase(
            "play some music",
            setOf("apps.open"),
            EvalCase.Intent.NEAR_MISS,
        ),
        EvalCase(
            "set a timer for ten minutes",
            setOf("alarm.create"),
            EvalCase.Intent.NEAR_MISS,
        ),
        EvalCase(
            "how much space is left on the SD card",
            setOf("device.info", "files.list"),
            EvalCase.Intent.AMBIGUOUS,
        ),
    )

    /**
     * Session-keyword cases, kept separate so the shape is obvious.
     *
     * These are the turns where a real user says "and at 4?" — the subject is
     * in the session, not the utterance. A selector that scores only the current
     * utterance cannot do these, and measuring that honestly is the point of
     * having them at all rather than leaving the field untested.
     */
    val withSessionContext: List<EvalCase> = listOf(
        EvalCase(
            "and set an alarm for 6:30?",
            setOf("alarm.create"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("morning", "wake up", "alarm", "early", "shift"),
        ),
        EvalCase(
            "cancel that one",
            setOf("alarm.cancel"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("alarm", "cancel", "remove", "turn off", "morning"),
        ),
        EvalCase(
            "what time is it then",
            setOf("calendar.search"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("calendar", "appointment", "meeting", "schedule", "event"),
        ),
        EvalCase(
            "delete it",
            setOf("files.delete"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("file", "document", "delete", "remove", "screenshot"),
        ),
        EvalCase(
            "and what's my battery at",
            setOf("device.battery"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("battery", "charge", "power", "drain", "level"),
        ),
        EvalCase(
            "reply to that one",
            setOf("notifications.reply"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("notification", "reply", "message", "chat", "shade"),
        ),
        EvalCase(
            "save it to a file",
            setOf("files.write_text"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("write", "save", "file", "note", "document"),
        ),
        EvalCase(
            "copy it",
            setOf("clipboard.write"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("clipboard", "copy", "paste", "text", "snippet"),
        ),
        EvalCase(
            "look up her number",
            setOf("contacts.search"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("contact", "number", "phone", "address book", "lookup"),
        ),
        EvalCase(
            "dismiss it",
            setOf("notifications.dismiss"),
            EvalCase.Intent.CHAINED,
            sessionKeywords = listOf("notification", "dismiss", "clear", "banner", "shade"),
        ),
    )

    /** Everything, in one list. The harness scores this. */
    val all: List<EvalCase> = cases + withSessionContext

    /**
     * Coverage by expected tool, for the harness to print.
     *
     * Exists so a silent gap is impossible: a tool that drifts out of the
     * dataset shows up as a zero in this table rather than as a slightly
     * flattering aggregate nobody questions.
     */
    fun coverageByTool(): Map<String, Int> =
        all.flatMap { it.expected }
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

    init {
        val names = EvalCase.ALL_TOOL_NAMES
        val uncovered = names - coverageByTool().keys
        require(uncovered.isEmpty()) {
            "these shipped tools have no case in the dataset: $uncovered. A retrieval " +
                "harness with a hole in its coverage reports a number nobody can trust."
        }
    }
}
