package dev.localintelligence.core.tool.selection

import dev.localintelligence.core.eval.AndroidRetrievalCase

// ===========================================================================
// SelectorProbe.kt — a held-out probe, written BEFORE any stemming existed.
//
// WHY THIS FILE EXISTS, AND WHY IT IS SEPARATE FROM AndroidTaskSuite
// ========================================================================
//
// `AndroidGeneralizationProbe` (PR #8) already exists and scores 12/25 = 48%.
// Adding to it would be a mistake: every utterance added to a probe AFTER a
// selector change is a tuning target wearing a lab coat. This file is the
// measurement instrument for the stemming work and it was written first, in one
// pass, with no reference to any tool's tag list — the tools were read only to
// learn the tool NAMES, so the set is covered, never the vocabulary.
//
// The honest caveat, stated here so nobody has to find it in a PR comment: the
// author of this probe is also the author of the stemmer. That is
// structurally the same circularity PR #8 caught in the 34-case suite, and no
// amount of good intent removes it. What it buys instead of blindness is a
// FIXED target: these 50 utterances are now pinned in git, so every future
// selector change is measured against a set that was not rewritten to flatter
// it. A probe you can edit is not a probe.
//
// `SelectorProbeTest` enforces the properties that keep it honest:
//  - no utterance repeats one from the tuned suite or the PR #8 probe;
//   - no utterance contains its expected tool's dotted name, and no utterance
//     carries nearly every one of its tags.
//   (This caught seven of my own cases on the first run, which is the single
//   best argument for writing the check before writing the probe.)
// ===========================================================================

/**
 * 61 oblique utterances over all 25 tools, written in one pass before the
 * normalizer existed.
 *
 * The rule while writing: say what a person says to a phone at 7am, never what
 * a tool author would call it. "nuke the screenshot" is a sentence; "delete"
 * is a tag. If a case could be answered by reading the catalogue, it is not in
 * here. Two-word tool names never appear, and neither does any tag spelled the
 * way the tag is spelled.
 */
object SelectorProbe {

    val cases: List<AndroidRetrievalCase> = listOf(
        // -- device ---------------------------------------------------------
        AndroidRetrievalCase("p2/battery-dinner", "is this thing gonna die before dinner", "device.battery"),
        AndroidRetrievalCase("p2/battery-juice", "how much juice is left", "device.battery"),
        AndroidRetrievalCase("p2/handset", "what am I actually running here", "device.info"),
        AndroidRetrievalCase("p2/room-left", "how full is the phone right now", "device.info"),
        AndroidRetrievalCase("p2/buzz", "make it go bzzzz", "device.vibrate"),
        AndroidRetrievalCase("p2/shove", "give the phone a quick rattle", "device.vibrate"),
        AndroidRetrievalCase("p2/wifi-on", "flip the wifi on for me", "device.open_settings"),
        AndroidRetrievalCase("p2/settings-menu", "take me into the settings menu", "device.open_settings"),

        // -- clipboard ------------------------------------------------------
        AndroidRetrievalCase("p2/park-number", "park that number where I can grab it later", "clipboard.write"),
        AndroidRetrievalCase("p2/stow", "stow this on the pasteboard", "clipboard.write"),
        AndroidRetrievalCase("p2/clip-a-minute", "what was on my clipboard a minute ago", "clipboard.read"),
        AndroidRetrievalCase("p2/sitting-there", "anything sitting in the clipboard right now", "clipboard.read"),
        AndroidRetrievalCase("p2/buffer", "what is in the copy buffer", "clipboard.read"),

        // -- alarm ----------------------------------------------------------
        AndroidRetrievalCase("p2/ping-me", "ping me at 5:45 sharp", "alarm.create"),
        AndroidRetrievalCase("p2/before-work", "get me up before work", "alarm.create"),
        AndroidRetrievalCase("p2/ring-me", "ring me at six tomorrow", "alarm.create"),
        AndroidRetrievalCase("p2/buzzers", "which buzzers have I got going", "alarm.list"),
        AndroidRetrievalCase("p2/set-to-ring", "list what is set to ring", "alarm.list"),
        AndroidRetrievalCase("p2/six-am", "get rid of the 6am one", "alarm.cancel"),
        AndroidRetrievalCase("p2/nothing-tonight", "nothing needs to ring tonight", "alarm.cancel"),

        // -- calendar -------------------------------------------------------
        AndroidRetrievalCase("p2/anything-week", "anything in my week", "calendar.search"),
        AndroidRetrievalCase("p2/seeing-thursday", "who am I seeing on thursday", "calendar.search"),
        AndroidRetrievalCase("p2/pen-me-in", "pen me in with the dentist tuesday", "calendar.create"),
        AndroidRetrievalCase("p2/hold-tuesday", "hold tuesday afternoon for the vet", "calendar.create"),
        AndroidRetrievalCase("p2/slot", "grab a slot for the barber", "calendar.create"),

        // -- contacts -------------------------------------------------------
        AndroidRetrievalCase("p2/digits", "what's Yasmin's digits", "contacts.search"),
        AndroidRetrievalCase("p2/reach-aisha", "number to reach Aisha on", "contacts.search"),
        AndroidRetrievalCase("p2/electrician", "whats the number for the electrician", "contacts.search"),
        AndroidRetrievalCase("p2/everything-priya", "everything we have on Priya", "contacts.get"),
        AndroidRetrievalCase("p2/anouk-card", "pull up Anouk's card", "contacts.get"),
        AndroidRetrievalCase("p2/everything-marta", "show me everything saved for Marta", "contacts.get"),

        // -- files ----------------------------------------------------------
        AndroidRetrievalCase("p2/pictures-folder", "what's living in my pictures folder", "files.list"),
        AndroidRetrievalCase("p2/downloads", "show me what's in downloads", "files.list"),
        AndroidRetrievalCase("p2/receipt-in", "which of my files has the receipt in it", "files.search"),
        AndroidRetrievalCase("p2/tenancy-pdf", "that tenancy pdf is somewhere on here", "files.search"),
        AndroidRetrievalCase("p2/lost-licence", "lost the licence document, where is it", "files.search"),
        AndroidRetrievalCase("p2/mortgage", "which document has the mortgage in it", "files.search"),
        AndroidRetrievalCase("p2/todo-says", "what does my todo list say", "files.read_text"),
        AndroidRetrievalCase("p2/changelog", "open up the changelog and tell me what's in it", "files.read_text"),
        AndroidRetrievalCase("p2/grocery-list", "what does my grocery list actually say", "files.read_text"),
        AndroidRetrievalCase("p2/write-parcel", "write down that the parcel is due monday", "files.write_text"),
        AndroidRetrievalCase("p2/password-file", "make me a file with the wifi password in", "files.write_text"),
        AndroidRetrievalCase("p2/bin-card-photo", "bin the photo of my bank card", "files.delete"),
        AndroidRetrievalCase("p2/binned-photo", "I binned the wrong photo, get rid of it", "files.delete"),
        AndroidRetrievalCase("p2/scrap-shot", "scrap that screenshot", "files.delete"),

        // -- apps -----------------------------------------------------------
        AndroidRetrievalCase("p2/whats-installed", "what's on this thing already", "apps.list"),
        AndroidRetrievalCase("p2/programs", "which programs did I install", "apps.list"),
        AndroidRetrievalCase("p2/maps", "fire up the maps app", "apps.open"),
        AndroidRetrievalCase("p2/browser-going", "get the browser going", "apps.open"),
        AndroidRetrievalCase("p2/telegram", "start up telegram", "apps.open"),
        AndroidRetrievalCase("p2/pass-photo", "pass the photo over to the chat", "apps.share"),
        AndroidRetrievalCase("p2/get-to-them", "get this to them on whatsapp", "apps.share"),

        // -- notifications --------------------------------------------------
        AndroidRetrievalCase("p2/buzzing", "what's buzzing on the phone", "notifications.list"),
        AndroidRetrievalCase("p2/piling-up", "anything piling up in the drop down bar", "notifications.list"),
        AndroidRetrievalCase("p2/tell-them", "tell them on my way", "notifications.reply"),
        AndroidRetrievalCase("p2/answer-back", "answer back that I'll be late", "notifications.reply"),
        AndroidRetrievalCase("p2/swipe-popups", "swipe away the popups", "notifications.dismiss"),
        AndroidRetrievalCase("p2/banners-stop", "make the banners stop", "notifications.dismiss"),

        // -- web ------------------------------------------------------------
        AndroidRetrievalCase("p2/weather-rotterdam", "what's the weather like in rotterdam", "web.fetch"),
        AndroidRetrievalCase("p2/headline", "grab the headline story from nos.nl", "web.fetch"),
        AndroidRetrievalCase("p2/weather-outside", "what is the weather doing outside", "web.fetch"),
    )

    /** Every tool the probe is supposed to reach. */
    val coveredTools: Set<String> get() = cases.flatMap { it.allExpected }.toSet()
}
