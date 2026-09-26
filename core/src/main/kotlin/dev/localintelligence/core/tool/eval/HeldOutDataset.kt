package dev.localintelligence.core.tool.eval

/**
 * The held-out corpus, and the rules it is held to.
 *
 * ## WHY THIS FILE IS THE PROJECT'S HEADLINE NUMBER, AND WHY 176/176 WAS NOT
 *
 * The single-turn corpus of 176 utterances reported 176/176. It was written by
 * an agent that chose the tool TAGS while reading the utterances, so the tags
 * and the utterances were written to fit each other. 100% is the shape of an
 * overfit, and it was reported as such at the time. A 25-case probe then
 * measured 22/25, and 25 cases cannot separate a real improvement from noise:
 * a single case is 4 points, so 22/25 and 25/25 are not distinguishable at any
 * useful confidence.
 *
 * This corpus exists to replace both with a number that means something. It is
 * 150+ utterances written under the rules below, and the rules are the point.
 *
 * ## THE RULES, stated once and kept
 *
 * **R1 — Written without reading a single tag.**
 * The 25 descriptions in [HeldOutToolSnapshot] are what a user is implicitly
 * told the app can do; they are the fair basis for deciding which tool a
 * request is FOR. The 185 tags are retrieval fuel, tuned against a different
 * corpus by whoever wrote them. An utterance written while looking at a tag
 * list is written to hit that list, which measures the list, not the selector.
 * No tag was read while authoring these, and [HeldOutHarness] proves the claim
 * mechanically rather than asking the reader to trust it (see the tautology
 * check).
 *
 * **R2 — Never tuned against.**
 * The corpus was run ONCE. The number in the report is that run. No tag, no
 * description, no weight and no constant was changed afterwards on the basis of
 * what this corpus showed, because doing so converts a held-out set into a
 * training set and the number becomes a measurement of the author again. If a
 * future change is made because a case here failed, this corpus is spent: it
 * must be marked contaminated in the commit message and in [HELDOUT_STATUS], and
 * the honest number becomes the new, smaller, freshly authored set.
 * [HELDOUT_STATUS] records the contamination state as data so it cannot be
 * quietly reset by a later commit.
 *
 * **R3 — Different order of operations from the original.**
 * The original agent enumerated tools, read their tags, and wrote utterances to
 * exercise those tags. This corpus was authored the other way round: a person's
 * day was imagined first (a morning alarm, a meeting, a file someone sent, a
 * message that needs answering), and each scenario was written in the phrasing
 * that scenario suggests, and only then resolved to the tool that would serve
 * it. That order produces the phrasing nobody would have thought to write while
 * looking at a tag list, which is the entire reason the number differs.
 *
 * **R4 — Hard on purpose, and honest about what is hard.**
 * [HeldOutCase.Intent] marks near-misses, elliptical phrasing, chained
 * lookups and referential follow-ups, and the harness reports the number for
 * each population separately. A corpus of clean single-tool requests would
 * flatter any selector and measure nothing a user would recognise.
 *
 * **R5 — The languages the team actually speaks.**
 * The shipped scorer splits on `[^a-z0-9]+` and drops fragments of length <= 2,
 * so it cannot read a non-ASCII word at all. [HeldOutCase.Dialect] records EN,
 * NL and DE, and the harness reports recall per dialect rather than averaging
 * the cliff away. This is a real limitation of the shipped selector, stated as
 * a number instead of a caveat in a comment.
 *
 * ## WHAT IS IN HERE, AND WHAT IS NOT
 *
 * The mix is deliberately NOT uniform. Real requests are dominated by battery,
 * alarms, calendar, notifications, contacts and files, and a uniform corpus
 * would imply a workload this product does not have. Per-category counts are
 * printed by the harness so the shape is visible rather than asserted.
 *
 * These are hand-written plausible utterances. They are NOT transcribed from a
 * conversation log: this repository has no telemetry, so a "real user phrasing"
 * claim would be a fiction. They are realistic phrasing for tasks this app can
 * genuinely perform, written in the order described by R3.
 *
 * Multi-turn is handled as [HeldOutCase.Intent.REFERENTIAL] plus
 * [HeldOutCase.sessionKeywords], replayed through the REAL
 * [dev.localintelligence.core.agent.Session] by [HeldOutHarness] so the keywords
 * are the production ones. A follow-up that needs an earlier RESULT is marked
 * [HeldOutCase.Intent.CHAINED] and scored on the first call, because selection
 * happens per turn.
 */
object HeldOutDataset {

    /**
     * The contamination state of this corpus, as data.
     *
     * ## Why this is a constant and not a commit message
     *
     * R2 is the rule that makes any number in this package worth quoting, and
     * the way rules like that actually fail is not defiance — it is a
     * well-meaning fix three weeks later, in a commit whose message says "improve
     * retrieval for Dutch requests" and does not say "I looked at the held-out
     * set to find out which cases failed". Once that happens the number is a
     * training-set score wearing a held-out label, and the only defence is that
     * the label travels with the data.
     *
     * So the state lives here, the harness prints it in the report header, and
     * [HeldOutHarness] refuses to describe a CLEAN corpus as anything stronger
     * than "never consulted" once this is [CONTAMINATED]. An auditor does not
     * have to trust a commit message; they read the number in the report.
     */
    enum class Contamination {
        /**
         * Authored blind, run once, never consulted to change anything. The
         * only state in which these numbers are a held-out measurement.
         */
        CLEAN,

        /**
         * Someone changed a tag, description, weight or constant BECAUSE of a
         * result from this corpus. Every number here is now a training-set
         * score. Kept, not deleted: the honest thing to publish is the number
         * and the fact that it stopped being held out, not a quietly fresh
         * corpus that pretends the episode did not happen.
         */
        CONTAMINATED,
    }

    val CONTAMINATION: Contamination = Contamination.CLEAN

    /**
     * The one run these numbers come from.
     *
     * Recorded so "never consulted" is a claim with a date against it rather
     * than an intention. Change it only if the run is repeated, and repeat it
     * only after a deliberate decision to re-measure — never to pick a better
     * number.
     */
    const val AUTHORED_BLIND_ON = "2026-09-26"

    /**
     * Cases with no session history: the first turn of a fresh conversation.
     *
     * The honest default for most traffic. A corpus that always supplies
     * keywords measures a warmer selector than a new user ever meets.
     */
    private val plain: List<HeldOutCase> = listOf(
        // ---- alarm.create -------------------------------------------------
        HeldOutCase(
            utterance = "set an alarm for 6:45 tomorrow",
            expected = setOf("alarm.create"),
        ),
        HeldOutCase(
            utterance = "wake me up at quarter past five",
            expected = setOf("alarm.create"),
        ),
        HeldOutCase(
            utterance = "I need to be up before the kids, put a wake-up call for 6",
            expected = setOf("alarm.create"),
        ),
        HeldOutCase(
            utterance = "can you set my alarm for 7am?",
            expected = setOf("alarm.create"),
        ),
        HeldOutCase(
            utterance = "remind me to get up at 5:30 in the morning",
            expected = setOf("alarm.create"),
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        HeldOutCase(
            utterance = "zorg dat ik om half zes wakker word",
            expected = setOf("alarm.create"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Weck mich morgen um halb sechs",
            expected = setOf("alarm.create"),
            dialect = HeldOutCase.Dialect.DE,
        ),
        HeldOutCase(
            utterance = "zwei wecker bitte, einer um sechs und einer um halb sieben",
            expected = setOf("alarm.create"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- alarm.list ---------------------------------------------------
        HeldOutCase(
            utterance = "what alarms do I have set?",
            expected = setOf("alarm.list"),
        ),
        HeldOutCase(
            utterance = "show me my morning wake-ups",
            expected = setOf("alarm.list"),
        ),
        HeldOutCase(
            utterance = "welke wekkers heb ik staan?",
            expected = setOf("alarm.list"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "welche Wecker sind eingestellt?",
            expected = setOf("alarm.list"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- alarm.cancel -------------------------------------------------
        HeldOutCase(
            utterance = "cancel my 6am alarm",
            expected = setOf("alarm.cancel"),
        ),
        HeldOutCase(
            utterance = "turn off the alarm I set for tomorrow",
            expected = setOf("alarm.cancel"),
        ),
        HeldOutCase(
            utterance = "I don't need that early one any more, kill it",
            expected = setOf("alarm.cancel"),
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        HeldOutCase(
            utterance = "verwijder mijn wekker van morgenochtend",
            expected = setOf("alarm.cancel"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Wecker für morgen früh wieder aus",
            expected = setOf("alarm.cancel"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- apps.list ----------------------------------------------------
        HeldOutCase(
            utterance = "what apps are installed on this phone?",
            expected = setOf("apps.list"),
        ),
        HeldOutCase(
            utterance = "do I have a banking app installed?",
            expected = setOf("apps.list"),
        ),
        HeldOutCase(
            utterance = "list everything on my home screen",
            expected = setOf("apps.list"),
            intent = HeldOutCase.Intent.AMBIGUOUS,
        ),
        HeldOutCase(
            utterance = "welke apps staan er op mijn telefoon?",
            expected = setOf("apps.list"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "zeig mir alle installierten Apps",
            expected = setOf("apps.list"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- apps.open ----------------------------------------------------
        HeldOutCase(
            utterance = "open Spotify",
            expected = setOf("apps.open"),
        ),
        HeldOutCase(
            utterance = "launch the camera",
            expected = setOf("apps.open"),
        ),
        HeldOutCase(
            utterance = "can you start Maps for me",
            expected = setOf("apps.open"),
        ),
        HeldOutCase(
            utterance = "open the settings",
            expected = setOf("apps.open", "device.open_settings"),
            intent = HeldOutCase.Intent.AMBIGUOUS,
        ),
        HeldOutCase(
            utterance = "open Instacats kapot",
            expected = setOf("apps.open"),
        ),
        HeldOutCase(
            utterance = "start de agenda app",
            expected = setOf("apps.open"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "öffne bitte WhatsApp",
            expected = setOf("apps.open"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- apps.share ---------------------------------------------------
        HeldOutCase(
            utterance = "share this document with my brother",
            expected = setOf("apps.share"),
        ),
        HeldOutCase(
            utterance = "pass this photo on to Dave",
            expected = setOf("apps.share"),
        ),
        HeldOutCase(
            utterance = "send the file I just opened to my sister's email",
            expected = setOf("apps.share"),
            intent = HeldOutCase.Intent.CHAINED,
        ),
        HeldOutCase(
            utterance = "deel dit bestand met Jan",
            expected = setOf("apps.share"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "teil dieses Foto mit meiner Schwester",
            expected = setOf("apps.share"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- calendar.create ----------------------------------------------
        HeldOutCase(
            utterance = "put a dentist appointment in my calendar for Tuesday at 14:00",
            expected = setOf("calendar.create"),
        ),
        HeldOutCase(
            utterance = "book me in with the dentist Thursday morning",
            expected = setOf("calendar.create"),
        ),
        HeldOutCase(
            utterance = "schedule a call with Marta on Friday at 11",
            expected = setOf("calendar.create"),
        ),
        HeldOutCase(
            utterance = "don't let me forget: parents' evening next Wednesday",
            expected = setOf("calendar.create"),
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        HeldOutCase(
            utterance = "zet een afspraak in mijn agenda voor morgen om tien",
            expected = setOf("calendar.create"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Trag einen Termin ein: Montag um 15 Uhr, Zahnarzt",
            expected = setOf("calendar.create"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- calendar.search ----------------------------------------------
        HeldOutCase(
            utterance = "what's on my calendar tomorrow?",
            expected = setOf("calendar.search"),
        ),
        HeldOutCase(
            utterance = "do I have anything scheduled this week?",
            expected = setOf("calendar.search"),
        ),
        HeldOutCase(
            utterance = "when is my next meeting?",
            expected = setOf("calendar.search"),
        ),
        HeldOutCase(
            utterance = "heb ik die week nog iets staan?",
            expected = setOf("calendar.search"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "was steht diese Woche noch in meinem Kalender?",
            expected = setOf("calendar.search"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- clipboard.write ----------------------------------------------
        HeldOutCase(
            utterance = "copy the tracking number to my clipboard",
            expected = setOf("clipboard.write"),
        ),
        HeldOutCase(
            utterance = "put my wifi password on the clipboard",
            expected = setOf("clipboard.write"),
        ),
        HeldOutCase(
            utterance = "copy this address so I can paste it somewhere",
            expected = setOf("clipboard.write"),
        ),
        HeldOutCase(
            utterance = "zet dat adres op mijn klembord",
            expected = setOf("clipboard.write"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "kopiere das bitte in die Zwischenablage",
            expected = setOf("clipboard.write"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- clipboard.read -----------------------------------------------
        HeldOutCase(
            utterance = "what did I just copy?",
            expected = setOf("clipboard.read"),
        ),
        HeldOutCase(
            utterance = "read what's on my clipboard",
            expected = setOf("clipboard.read"),
        ),
        HeldOutCase(
            utterance = "wat heb ik net gekopieerd?",
            expected = setOf("clipboard.read"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "was steht gerade in der Zwischenablage?",
            expected = setOf("clipboard.read"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- contacts.search ----------------------------------------------
        HeldOutCase(
            utterance = "what's Jan's phone number?",
            expected = setOf("contacts.search"),
        ),
        HeldOutCase(
            utterance = "look up Marta in my contacts",
            expected = setOf("contacts.search"),
        ),
        HeldOutCase(
            utterance = "do I know a Peter van Dijk?",
            expected = setOf("contacts.search"),
        ),
        HeldOutCase(
            utterance = "find the number for the plumber",
            expected = setOf("contacts.search"),
        ),
        HeldOutCase(
            utterance = "wat is het telefoonnummer van Anouk?",
            expected = setOf("contacts.search"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "wie ist Anna Schmidt in meinen Kontakten?",
            expected = setOf("contacts.search"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- contacts.get -------------------------------------------------
        HeldOutCase(
            utterance = "show me everything you have on file for Jan",
            expected = setOf("contacts.get"),
            intent = HeldOutCase.Intent.CHAINED,
        ),
        HeldOutCase(
            utterance = "give me Jan's email, phone and work address",
            expected = setOf("contacts.get"),
        ),
        HeldOutCase(
            utterance = "all the ways I can reach Petra?",
            expected = setOf("contacts.get"),
        ),
        HeldOutCase(
            utterance = "toen Jan belt wil ik zijn privénummer, niet dat van het werk",
            expected = setOf("contacts.get"),
            dialect = HeldOutCase.Dialect.NL,
        ),

        // ---- device.battery ------------------------------------------------
        HeldOutCase(
            utterance = "how much battery is left?",
            expected = setOf("device.battery"),
        ),
        HeldOutCase(
            utterance = "will my phone make it to the airport?",
            expected = setOf("device.battery"),
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        HeldOutCase(
            utterance = "is it charging right now?",
            expected = setOf("device.battery"),
        ),
        HeldOutCase(
            utterance = "how long till this thing dies?",
            expected = setOf("device.battery"),
        ),
        HeldOutCase(
            utterance = "hoeveel juice heb ik nog?",
            expected = setOf("device.battery"),
        ),
        HeldOutCase(
            utterance = "wie viel Akku ist noch da?",
            expected = setOf("device.battery"),
            dialect = HeldOutCase.Dialect.DE,
        ),
        HeldOutCase(
            utterance = "laat de telefoon aan de kabel, ik moet om 5 in de stad zijn",
            expected = setOf("device.battery"),
            dialect = HeldOutCase.Dialect.NL,
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),

        // ---- device.info ---------------------------------------------------
        HeldOutCase(
            utterance = "what phone is this?",
            expected = setOf("device.info"),
        ),
        HeldOutCase(
            utterance = "how much storage space have I got left?",
            expected = setOf("device.info"),
        ),
        HeldOutCase(
            utterance = "how much memory does this thing have?",
            expected = setOf("device.info"),
        ),
        HeldOutCase(
            utterance = "which Android version is this running?",
            expected = setOf("device.info"),
        ),
        HeldOutCase(
            utterance = "hoeveel geheugen is er nog vrij op de telefoon?",
            expected = setOf("device.info"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "wie viel RAM hat dieses Gerät?",
            expected = setOf("device.info"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- device.open_settings ------------------------------------------
        HeldOutCase(
            utterance = "open the battery settings",
            expected = setOf("device.open_settings"),
        ),
        HeldOutCase(
            utterance = "take me to the accessibility settings",
            expected = setOf("device.open_settings"),
        ),
        HeldOutCase(
            utterance = "I need the developer options",
            expected = setOf("device.open_settings"),
        ),
        HeldOutCase(
            utterance = "open de instellingen voor wifi",
            expected = setOf("device.open_settings"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Öffne die Einstellungen für den Speicher",
            expected = setOf("device.open_settings"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- device.vibrate -------------------------------------------------
        HeldOutCase(
            utterance = "buzz the phone, my phone's in the bag",
            expected = setOf("device.vibrate"),
        ),
        HeldOutCase(
            utterance = "can you vibrate it so I can find it?",
            expected = setOf("device.vibrate"),
        ),
        HeldOutCase(
            utterance = "trill even, de telefoon ligt in de jas",
            expected = setOf("device.vibrate"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "mach bitte kurz vibrieren, das Handy liegt in der Tasche",
            expected = setOf("device.vibrate"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- files.list -----------------------------------------------------
        HeldOutCase(
            utterance = "what files have I got?",
            expected = setOf("files.list"),
        ),
        HeldOutCase(
            utterance = "show me my downloads",
            expected = setOf("files.list"),
        ),
        HeldOutCase(
            utterance = "anything in my documents folder I should know about?",
            expected = setOf("files.list"),
        ),
        HeldOutCase(
            utterance = "wat staat er in mijn Downloads?",
            expected = setOf("files.list"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "zeig mir, was im Download-Ordner liegt",
            expected = setOf("files.list"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- files.search ---------------------------------------------------
        HeldOutCase(
            utterance = "find the invoice from ABN AMRO",
            expected = setOf("files.search"),
        ),
        HeldOutCase(
            utterance = "where's that PDF I downloaded yesterday?",
            expected = setOf("files.search"),
        ),
        HeldOutCase(
            utterance = "look for a document called huurcontract",
            expected = setOf("files.search"),
        ),
        HeldOutCase(
            utterance = "find every photo I took this month",
            expected = setOf("files.search"),
        ),
        HeldOutCase(
            utterance = "zoek het bestand met de hypotheeknotitie",
            expected = setOf("files.search"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Suche die Rechnung von Siemens",
            expected = setOf("files.search"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- files.read_text -------------------------------------------------
        HeldOutCase(
            utterance = "what does notes.txt say?",
            expected = setOf("files.read_text"),
        ),
        HeldOutCase(
            utterance = "read me the shopping list file",
            expected = setOf("files.read_text"),
        ),
        HeldOutCase(
            utterance = "open the meeting notes and tell me what's in them",
            expected = setOf("files.read_text", "files.search"),
            intent = HeldOutCase.Intent.CHAINED,
        ),
        HeldOutCase(
            utterance = "wat staat er in dat document met de agenda?",
            expected = setOf("files.read_text"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "lies mir die Notizen aus der Datei vor",
            expected = setOf("files.read_text"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- files.write_text -----------------------------------------------
        HeldOutCase(
            utterance = "save this to a file called notities.txt",
            expected = setOf("files.write_text"),
        ),
        HeldOutCase(
            utterance = "write the address into a new document",
            expected = setOf("files.write_text"),
        ),
        HeldOutCase(
            utterance = "put that shopping list in a file so I don't lose it",
            expected = setOf("files.write_text"),
        ),
        HeldOutCase(
            utterance = "schrijf dit in een bestand met de naam notities",
            expected = setOf("files.write_text"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Speicher das bitte in einer Datei",
            expected = setOf("files.write_text"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- files.delete ----------------------------------------------------
        HeldOutCase(
            utterance = "delete that screenshot",
            expected = setOf("files.delete"),
        ),
        HeldOutCase(
            utterance = "get rid of the old tax form, I don't need it",
            expected = setOf("files.delete"),
        ),
        HeldOutCase(
            utterance = "remove IMG_4471 from my phone",
            expected = setOf("files.delete"),
        ),
        HeldOutCase(
            utterance = "verwijder die oude foto's",
            expected = setOf("files.delete"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "lösch bitte die alte Rechnung",
            expected = setOf("files.delete"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- notifications.list ----------------------------------------------
        HeldOutCase(
            utterance = "what notifications are showing right now?",
            expected = setOf("notifications.list"),
        ),
        HeldOutCase(
            utterance = "anything new in the shade?",
            expected = setOf("notifications.list"),
        ),
        HeldOutCase(
            utterance = "did my train just ping me?",
            expected = setOf("notifications.list"),
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        HeldOutCase(
            utterance = "welke meldingen staan er nu?",
            expected = setOf("notifications.list"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "welche Benachrichtigungen gibt es gerade?",
            expected = setOf("notifications.list"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- notifications.reply ---------------------------------------------
        HeldOutCase(
            utterance = "reply to Sarah's message saying I'll be there in ten minutes",
            expected = setOf("notifications.reply"),
        ),
        HeldOutCase(
            utterance = "tell the group chat I'm on my way",
            expected = setOf("notifications.reply"),
        ),
        HeldOutCase(
            utterance = "answer that WhatsApp with 'yes, that works'",
            expected = setOf("notifications.reply"),
        ),
        HeldOutCase(
            utterance = "antwoord Jan dat het geen probleem is",
            expected = setOf("notifications.reply"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Antworte Sarah, dass ich in zehn Minuten da bin",
            expected = setOf("notifications.reply"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- notifications.dismiss --------------------------------------------
        HeldOutCase(
            utterance = "clear that notification, it's annoying",
            expected = setOf("notifications.dismiss"),
        ),
        HeldOutCase(
            utterance = "get rid of the banner from the bank",
            expected = setOf("notifications.dismiss"),
        ),
        HeldOutCase(
            utterance = "silence that popup",
            expected = setOf("notifications.dismiss"),
        ),
        HeldOutCase(
            utterance = "haal die melding van het scherm",
            expected = setOf("notifications.dismiss"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "wisch diese Benachrichtigung weg",
            expected = setOf("notifications.dismiss"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- web.fetch ---------------------------------------------------------
        HeldOutCase(
            utterance = "look up the opening hours of the bike shop",
            expected = setOf("web.fetch"),
        ),
        HeldOutCase(
            utterance = "what does this page say?",
            expected = setOf("web.fetch"),
        ),
        HeldOutCase(
            utterance = "check the weather forecast for tomorrow",
            expected = setOf("web.fetch"),
        ),
        HeldOutCase(
            utterance = "read me the article at nu.nl/dieping",
            expected = setOf("web.fetch"),
        ),
        HeldOutCase(
            utterance = "zoek op het internet wat de openingstijden zijn",
            expected = setOf("web.fetch"),
            dialect = HeldOutCase.Dialect.NL,
        ),
        HeldOutCase(
            utterance = "Schau mal im Netz nach, wann der Supermarkt aufmacht",
            expected = setOf("web.fetch"),
            dialect = HeldOutCase.Dialect.DE,
        ),

        // ---- NEAR-MISS: lexically adjacent to a neighbour -------------------
        // Every one of these shares vocabulary with a tool it must NOT be
        // pulled to. They exist because the selector's tie-break is
        // alphabetical, so a tie at the cut is decided by a name rather than by
        // relevance and only a near-miss can catch that.
        HeldOutCase(
            utterance = "delete the alarm I set for 6am",
            expected = setOf("alarm.cancel"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "cancel the event on my calendar",
            expected = setOf("calendar.search"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "open the file I downloaded",
            expected = setOf("files.read_text", "files.search"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "search my contacts for Peter's work number",
            expected = setOf("contacts.search"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "don't show me notifications any more",
            expected = setOf("notifications.dismiss"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "open the banking app and show me my balance",
            expected = setOf("apps.open"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "save the page I just looked up to a file",
            expected = setOf("files.write_text"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "read out the message from my sister",
            expected = setOf("notifications.list"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "put the meeting in my phone's diary",
            expected = setOf("calendar.create"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "which of my apps can I use for maps?",
            expected = setOf("apps.list"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "write the file with the shopping list",
            expected = setOf("files.write_text"),
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "staat de wekker van zeven nog aan?",
            expected = setOf("alarm.list"),
            dialect = HeldOutCase.Dialect.NL,
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "soll der Wecker noch klingeln?",
            expected = setOf("alarm.list"),
            dialect = HeldOutCase.Dialect.DE,
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "heb ik die agenda-afspraak nog staan?",
            expected = setOf("calendar.search"),
            dialect = HeldOutCase.Dialect.NL,
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
        HeldOutCase(
            utterance = "zeig mir die Benachrichtigung von WhatsApp",
            expected = setOf("notifications.list"),
            dialect = HeldOutCase.Dialect.DE,
            intent = HeldOutCase.Intent.NEAR_MISS,
        ),
    )

    /**
     * Turns whose subject was established EARLIER in the conversation.
     *
     * ## Why these are the cases the 176-utterance corpus structurally cannot hold
     *
     * A single-turn corpus has no way to express "the thing I asked about two
     * turns ago". Every case in it is self-contained, so a selector that
     * remembered nothing would score exactly as well. This population is the
     * one where forgetting is visible, and it is the population a real
     * conversation is mostly made of.
     *
     * The keywords are NOT written by hand here. [HeldOutHarness] builds a real
     * [dev.localintelligence.core.agent.Session], replays [priorTurns] and a
     * realistic tool RESULT through the production API, and takes whatever
     * `Session.currentKeywords()` returns. A hand-written keyword list would
     * quietly grant the selector memory it does not have, which is precisely
     * the failure these cases exist to measure.
     *
     * @param priorTurns what the user said before this one, oldest first.
     * @param priorCall the tool the loop invoked to answer them, or null if the
     *   prior turn was answered in prose.
     * @param priorObservation what that call returned. Present so a follow-up
     *   that needs a RESULT is honest: "text her" is only answerable if the
     *   lookup two turns back returned something.
     */
    private val referential: List<ReferentialCase> = listOf(
        ReferentialCase(
            utterance = "put that in my calendar for Thursday",
            expected = setOf("calendar.create"),
            priorTurns = listOf("put a dentist appointment in my calendar for Tuesday at 14:00"),
        ),
        ReferentialCase(
            utterance = "and one for Friday",
            expected = setOf("calendar.create"),
            priorTurns = listOf("put a dentist appointment in my calendar for Tuesday at 14:00"),
        ),
        ReferentialCase(
            utterance = "make it half an hour later",
            expected = setOf("calendar.create"),
            priorTurns = listOf("schedule a call with Marta on Friday at 11"),
        ),
        ReferentialCase(
            utterance = "delete that one",
            expected = setOf("files.delete"),
            priorCall = "files.search",
            priorObservation = "invoice_abnamro.pdf  284 KB  2026-09-24",
            priorTurns = listOf("find the invoice from ABN AMRO"),
        ),
        ReferentialCase(
            utterance = "put its number on the clipboard",
            expected = setOf("clipboard.write"),
            priorCall = "contacts.search",
            priorObservation = "Jan de Vries  06-12345678",
            priorTurns = listOf("what's Jan's phone number?"),
        ),
        ReferentialCase(
            utterance = "now tell him I'll be ten minutes late",
            expected = setOf("notifications.reply"),
            priorCall = "contacts.search",
            priorObservation = "Jan de Vries  06-12345678",
            priorTurns = listOf("what's Jan's phone number?"),
        ),
        ReferentialCase(
            utterance = "what was in it again?",
            expected = setOf("files.read_text"),
            priorCall = "files.read_text",
            priorObservation = "Shopping list: eggs, milk, coffee, bread",
            priorTurns = listOf("read me the shopping list file"),
        ),
        ReferentialCase(
            utterance = "silence it",
            expected = setOf("notifications.dismiss"),
            priorCall = "notifications.list",
            priorObservation = "key=n7 ABN AMRO: your statement is ready",
            priorTurns = listOf("did my bank just notify me?"),
        ),
        ReferentialCase(
            utterance = "and set one for the day after",
            expected = setOf("alarm.create"),
            priorCall = "alarm.create",
            priorObservation = "alarm set for 2026-09-27 06:45 id=a1",
            priorTurns = listOf("set an alarm for 6:45 tomorrow"),
        ),
        ReferentialCase(
            utterance = "cancel that instead",
            expected = setOf("alarm.cancel"),
            priorCall = "alarm.list",
            priorObservation = "1. 06:45 weekdays (id=a1)  2. 07:30 weekdays (id=a2)",
            priorTurns = listOf("what alarms do I have set?"),
        ),
        ReferentialCase(
            utterance = "how long till it's full?",
            expected = setOf("device.battery"),
            priorCall = "device.battery",
            priorObservation = "battery 42%, not charging, about 4h 10m left",
            priorTurns = listOf("how much battery is left?"),
        ),
        ReferentialCase(
            utterance = "put that on the clipboard and open it",
            expected = setOf("clipboard.write", "apps.open"),
            priorCall = "web.fetch",
            priorObservation = "opening hours: Mon-Fri 09:00-18:00, Sat 10:00-16:00",
            priorTurns = listOf("look up the opening hours of the bike shop"),
            intent = HeldOutCase.Intent.AMBIGUOUS,
        ),
        ReferentialCase(
            utterance = "remind me to check it again at seven",
            expected = setOf("alarm.create"),
            priorCall = "web.fetch",
            priorObservation = "opening hours: Mon-Fri 09:00-18:00",
            priorTurns = listOf("look up the opening hours of the bike shop"),
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        ReferentialCase(
            utterance = "stuur hem dat bericht",
            expected = setOf("notifications.reply", "apps.share"),
            priorCall = "contacts.search",
            priorObservation = "Jan de Vries  06-12345678",
            priorTurns = listOf("wat is het telefoonnummer van Jan?"),
            dialect = HeldOutCase.Dialect.NL,
            intent = HeldOutCase.Intent.AMBIGUOUS,
        ),
        ReferentialCase(
            utterance = "schick ihm das",
            expected = setOf("notifications.reply", "apps.share"),
            priorCall = "clipboard.read",
            priorObservation = "Der Termin ist am Dienstag um 15 Uhr",
            priorTurns = listOf("lies vor, was auf der Zwischenablage steht"),
            dialect = HeldOutCase.Dialect.DE,
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        ReferentialCase(
            utterance = "what else is on that day?",
            expected = setOf("calendar.search"),
            priorCall = "calendar.search",
            priorObservation = "2026-09-24  14:00  dentist  2026-09-24  16:30  team retro",
            priorTurns = listOf("what's on my calendar tomorrow?"),
        ),
        ReferentialCase(
            utterance = "move it to 9",
            expected = setOf("calendar.create"),
            priorCall = "calendar.search",
            priorObservation = "2026-09-24  14:00  dentist",
            priorTurns = listOf("what's on my calendar tomorrow?"),
        ),
        ReferentialCase(
            utterance = "email him the same thing",
            expected = setOf("notifications.reply", "apps.share"),
            priorCall = "clipboard.read",
            priorObservation = "the meeting moved to Thursday",
            priorTurns = listOf("what's on my clipboard?"),
        ),
        ReferentialCase(
            utterance = "and put a copy in my downloads",
            expected = setOf("files.write_text"),
            priorCall = "web.fetch",
            priorObservation = "opening hours: Mon-Fri 09:00-18:00",
            priorTurns = listOf("look up the opening hours of the bike shop"),
        ),
        ReferentialCase(
            utterance = "zet dat er ook in",
            expected = setOf("files.write_text"),
            priorCall = "clipboard.read",
            priorObservation = "adres: Prinsengracht 263, Amsterdam",
            priorTurns = listOf("wat heb ik net gekopieerd?"),
            dialect = HeldOutCase.Dialect.NL,
            intent = HeldOutCase.Intent.ELLIPTICAL,
        ),
        ReferentialCase(
            utterance = "wie ist sie?",
            expected = setOf("contacts.search", "contacts.get"),
            priorCall = "notifications.list",
            priorObservation = "key=n3 Petra: bin im Zug, kannst du das Foto schicken?",
            priorTurns = listOf("did my train just ping me?"),
            intent = HeldOutCase.Intent.AMBIGUOUS,
        ),
        ReferentialCase(
            utterance = "delete them all",
            expected = setOf("files.delete"),
            priorCall = "files.search",
            priorObservation = "old_photo1.jpg old_photo2.jpg old_photo3.jpg",
            priorTurns = listOf("find every photo I took this month"),
        ),
    )

    /**
     * A turn that leans on an earlier turn, plus the earlier turn itself.
     *
     * @param intent marked explicitly per case because a referential turn that
     *   is also elliptical is harder than either alone, and averaging them into
     *   one number would hide that.
     */
    data class ReferentialCase(
        val utterance: String,
        val expected: Set<String>,
        val priorTurns: List<String>,
        val priorCall: String? = null,
        val priorObservation: String? = null,
        val intent: HeldOutCase.Intent = HeldOutCase.Intent.REFERENTIAL,
        val dialect: HeldOutCase.Dialect = HeldOutCase.Dialect.EN,
    )

    val referentialCases: List<ReferentialCase> = referential

    /** Single-turn cases, no session history. */
    val freshSession: List<HeldOutCase> = plain

    /** Total single-turn cases. */
    val totalSingleTurn: Int get() = plain.size

    /** Total referential turns. */
    val totalReferential: Int get() = referential.size

    /** Everything, as one number a reader can hold. */
    val total: Int get() = plain.size + referential.size

    /**
     * Cases per expected tool, counting a multi-tool case once per tool.
     *
     * Printed by the harness so "every tool is exercised" is a checkable
     * statement rather than an aspiration. A tool with a zero here is a hole in
     * the corpus, and the harness says so loudly.
     */
    fun coverageByTool(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (name in HeldOutCase.ALL_TOOL_NAMES) counts[name] = 0
        for (case in plain) for (name in case.expected) counts[name] = (counts[name] ?: 0) + 1
        for (case in referential) for (name in case.expected) counts[name] = (counts[name] ?: 0) + 1
        return counts
    }
}
