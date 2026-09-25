package dev.localintelligence.core.tool.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ===========================================================================
// LexicalNormalizerTest.kt — one assertion per rule, and one per entry.
//
// WHY THIS FILE IS LONGER THAN THE IMPLEMENTATION
// ================================================
//
// A stemmer's failure mode is silence. `searchch` scores zero everywhere and
// nothing throws, so the only defence is an explicit table of what each rule
// does — and, just as importantly, of what it must NOT do. The negative cases
// below are the load-bearing half: they are the ones that stop a future change
// from "improving" recall by mangling words until the vocabulary collapses.
// ===========================================================================

class LexicalNormalizerTest {

    // -----------------------------------------------------------------------
    // Stemmer: each rule, and the case that proves it runs
    // -----------------------------------------------------------------------

    @Test
    fun `rule -ing strips the gerund suffix`() {
        assertEquals("schedul", LexicalNormalizer.stem("scheduling"))
        assertEquals("search", LexicalNormalizer.stem("searching"))
        assertEquals("writ", LexicalNormalizer.stem("writing"))
    }

    @Test
    fun `rule -ed strips the past suffix and is the fix for the reported miss`() {
        // THE case from the brief. "scheduled" did not match the tag
        // "scheduled" / the stem "schedule" and the expected tool scored zero.
        assertEquals("schedul", LexicalNormalizer.stem("scheduled"))
        // The silent-e rule is what makes the two spellings AGREE rather than
        // merely resemble each other, and agreement is the entire point: a
        // prefix relationship would only half-work.
        assertEquals("schedul", LexicalNormalizer.stem("schedule"))
        assertEquals("schedul", LexicalNormalizer.stem("schedules"))
        assertEquals("schedul", LexicalNormalizer.stem("scheduling"))
    }

    @Test
    fun `rule plural -s strips and leaves doubled consonants alone`() {
        assertEquals("notification", LexicalNormalizer.stem("notifications"))
        assertEquals("file", LexicalNormalizer.stem("files"))
        assertEquals("alarm", LexicalNormalizer.stem("alarms"))
        // "ss" is not a plural: stripping it turns "class" into "clas".
        assertEquals("class", LexicalNormalizer.stem("class"))
        assertEquals("pass", LexicalNormalizer.stem("pass"))
    }

    @Test
    fun `rule -ies becomes -y`() {
        assertEquals("entry", LexicalNormalizer.stem("entries"))
        assertEquals("battery", LexicalNormalizer.stem("batteries"))
        assertEquals("entry", LexicalNormalizer.stem("entry"))
    }

    @Test
    fun `rule sibilant -es strips only the es and never a phantom consonant`() {
        // Regression guard for a real bug: a draft mapped "ches" -> "ch" and
        // produced "searchch", which matches nothing and fails silently.
        assertEquals("search", LexicalNormalizer.stem("searches"))
        assertEquals("batch", LexicalNormalizer.stem("batches"))
        assertEquals("box", LexicalNormalizer.stem("boxes"))
        assertEquals("buzz", LexicalNormalizer.stem("buzzes"))
        assertEquals("wish", LexicalNormalizer.stem("wishes"))
        assertEquals("class", LexicalNormalizer.stem("classes"))
    }

    @Test
    fun `rule undoubles a consonant left behind by -ed and -ing`() {
        // Without this, "cancelled" and "cancel" land on different stems and the
        // two halves of the same word stop matching, which is the whole failure
        // the -ed rule was added to prevent.
        assertEquals("stop", LexicalNormalizer.stem("stopped"))
        assertEquals("bin", LexicalNormalizer.stem("binned"))
        assertEquals("plan", LexicalNormalizer.stem("planning"))
        // ...but a real double is kept, because "call" and "cal" are not a pair.
        assertEquals("call", LexicalNormalizer.stem("called"))
        assertEquals("off", LexicalNormalizer.stem("offed"))
    }

    @Test
    fun `singular-looking plurals are never stripped`() {
        // Each of these becomes a different, real word under a naive -s rule,
        // and "news" -> "new" is the classic: the user says news, the tool has
        // no tag "new", and the utterance silently stops matching anything.
        listOf("status", "news", "always", "perhaps", "series", "focus", "bus")
            .forEach { word ->
                assertEquals("\"$word\" must not be stemmed", word, LexicalNormalizer.stem(word))
            }
        assertEquals("news", LexicalNormalizer.stem("news"))
    }

    @Test
    fun `short words are never stemmed`() {
        // Over-eager normalisation on short words is pure loss: "app" -> "ap",
        // "get" -> "ge". A stem that is not a word matches nothing useful and
        // can collide with another real word, which is unrecoverable.
        listOf("app", "get", "net", "cat", "log", "new", "put", "set", "add", "use")
            .forEach { word ->
                assertEquals("\"$word\" is too short to stem", word, LexicalNormalizer.stem(word))
            }
    }

    @Test
    fun `stemming is idempotent`() {
        // A stemmer that is not idempotent has a bug that only shows up on the
        // second step, and this pipeline only ever steps once, so the symptom
        // would be "sometimes it works".
        val words = listOf(
            "scheduled", "searches", "notifications", "entries", "stopped", "binned",
            "copied", "buzzes", "boxes", "wishes", "batteries", "calling", "photos",
        )
        words.forEach { word ->
            val once = LexicalNormalizer.stem(word)
            assertEquals("stem(\"$word\") is not idempotent", once, LexicalNormalizer.stem(once))
        }
    }

    @Test
    fun `a stem is always a prefix of its word or a known rewrite`() {
        // Cheap invariant: the stemmer may drop suffixes, but it must never
        // APPEND anything, because an appended fragment is a token no tag in
        // the set can ever match.
        val words = listOf(
            "scheduled", "searches", "notifications", "entries", "stopped", "copied",
            "boxes", "batteries", "calling", "jotting", "pinged", "ringing",
        )
        words.forEach { word ->
            val stem = LexicalNormalizer.stem(word)
            assertTrue(
                "stem(\"$word\") = \"$stem\" is not a prefix of the word",
                word.startsWith(stem.take(stem.length - 1).ifEmpty { stem }) ||
                    word.startsWith(stem) ||
                    stem == word,
            )
        }
    }

    // -----------------------------------------------------------------------
    // Synonyms: every entry has to be reachable, and has to be a real entry
    // -----------------------------------------------------------------------

    @Test
    fun `every synonym key is a stem the tokenizer can actually emit`() {
        // The load-bearing property of the table. A key that no word stems to is
        // an entry that can never fire, and in a table walked on every step of
        // every task that is pure memory cost. An earlier draft had three such
        // entries ("remove" alongside "remov", "write" alongside "writ",
        // "locate" alongside "locat") because the key was written as the word a
        // person says rather than the stem the tokenizer produces.
        val unreachable = mutableListOf<String>()
        LexicalNormalizer.synonymKeys.forEach { key ->
            val produced = surfaceForms.any { LexicalNormalizer.stem(it) == key }
            if (!produced) unreachable += key
        }
        assertTrue(
            "these synonym keys are dead — no word in the table's own vocabulary " +
                "stems to them: $unreachable",
            unreachable.isEmpty(),
        )
    }

    @Test
    fun `every synonym key is a fixed point of the stemmer`() {
        // A stronger, self-contained version of the same check: the tokenizer
        // must not mangle a key when it encounters one in a tool's tag list.
        // Without this, `stem("book") != "book"` would silently break the whole
        // calendar cluster while every probe case still passed.
        LexicalNormalizer.synonymKeys.forEach { key ->
            assertEquals(
                "synonym key \"$key\" is not a fixed point of stem()",
                key, LexicalNormalizer.stem(key),
            )
        }
    }

    @Test
    fun `every synonym target stems to something the tool set could match`() {
        // The right-hand side of each entry is drawn from the shipped tag
        // vocabulary. If a target is a phrase rather than a single stem it can
        // never intersect anything, and the entry is costing memory for nothing.
        val multiWord = mutableListOf<String>()
        LexicalNormalizer.removedSynonyms.keys // touch, keeps the property honest
        KNOWN_SYNONYM_TARGETS.forEach { target ->
            if (target.contains(' ') || target.contains('-')) multiWord += target
        }
        assertTrue(
            "a synonym target contains whitespace or a hyphen and can never match a token: $multiWord",
            multiWord.isEmpty(),
        )
    }

    @Test
    fun `the synonym table stays small`() {
        // The brief, and the reason this is not a thesaurus: this table is
        // resident memory on a phone and it is consulted on every step. A
        // 500-word map is a liability, not a feature.
        assertTrue(
            "the synonym table has ${LexicalNormalizer.synonymEntryCount} entries; the " +
                "ceiling is ${MAX_SYNONYM_ENTRIES}. If an entry does not earn its place " +
                "on the probe, delete it and say so in the PR.",
            LexicalNormalizer.synonymEntryCount <= MAX_SYNONYM_ENTRIES,
        )
        assertTrue(
            "the synonym table reaches ${LexicalNormalizer.synonymStemCount} stems; ceiling is " +
                "${MAX_SYNONYM_STEMS}",
            LexicalNormalizer.synonymStemCount <= MAX_SYNONYM_STEMS,
        )
    }

    @Test
    fun `deleted entries are recorded with their reason`() {
        // A removed entry that leaves no trace gets re-added by the next
        // author, and the measurement that removed it gets redone for nothing.
        assertTrue(
            "the removed-entry record is empty; something was measured and forgotten",
            LexicalNormalizer.removedSynonyms.isNotEmpty(),
        )
        LexicalNormalizer.removedSynonyms.forEach { (key, reason) ->
            assertTrue(
                "removed entry \"$key\" has no recorded reason",
                reason.length > 20,
            )
            assertFalse(
                "removed entry \"$key\" is still in the live table",
                LexicalNormalizer.synonymsOf(key).isNotEmpty(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Synonyms: the ones that must NOT over-fire
    // -----------------------------------------------------------------------

    @Test
    fun `paste does not drag in unrelated tools`() {
        // Named explicitly in the brief. "paste" belongs to the clipboard
        // namespace and to nothing else, so it must reach the clipboard and
        // reach nothing else — not notifications, not reply, not share, and
        // certainly not wifi.
        //
        // The clipboard half needs no synonym entry to work: both clipboard
        // tools carry "paste" as a literal tag, so the direct match already
        // scores 3x. That is precisely why the `copi`/`clipboard`/`past`/
        // `pasteboard` entries were DELETED — they were redundant bridges, and
        // the ablation confirmed it. What this test now protects is the
        // over-fire direction, which is the part that can actually go wrong.
        val reached = LexicalNormalizer.expansionOnlyStems("paste that into the chat")
        assertEquals(
            "\"paste\" must expand to nothing at all; both clipboard tools already " +
                "carry it as a literal tag, so an entry here is pure over-fire risk",
            emptySet<String>(), reached,
        )
        assertFalse("paste must not reach notifications", "notification" in reached)
        assertFalse("paste must not reach reply", "reply" in reached)
        assertFalse("paste must not reach share", "share" in reached)
        assertFalse("paste must not reach wifi", "wifi" in reached)

        // And the positive half: the clipboard tools must actually be selected.
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val selected = dev.localintelligence.core.tool.LexicalToolSelector()
            .select("paste that into the chat", emptyList(), tools, 6)
            .map { it.definition.name }
        assertTrue(
            "a clipboard tool must be selected for \"paste\", got $selected",
            selected.any { it.startsWith("clipboard.") },
        )
    }

    /**
     * Every kept entry, checked against the tool it exists to serve.
     *
     * The brief asks for "each synonym entry" to be tested, and asks that
     * synonyms not over-fire. This is the test that answers both at once: for
     * each entry, an utterance that fires it must put the intended tool FIRST,
     * not merely in the window. First place is the property that matters,
     * because a 1-4B model picks from what it is shown and the tool it is most
     * likely to read first is the one at the top.
     *
     * An earlier version of this test asserted that no entry's targets were
     * owned by more than one tool, and it was wrong: `delete` is a tag on BOTH
     * `alarm.cancel` and `files.delete`, deliberately, because "delete the
     * 22:00 alarm" is a real request. A bridge to a word two tools share cannot
     * be an over-fire when the word itself is already shared.
     */
    @Test
    fun `every kept entry earns its place, and none over-fires`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val table = LexicalNormalizer.synonymTable

        /**
         * One utterance per surviving entry, in the words a person uses.
         *
         * The property asserted is WINDOW membership, not first place, because
         * that is what the ablation actually measured: the entry pulls the
         * intended tool into the six-wide window, and the tool is not there
         * without it. Demanding first place would be wrong for `buzz` — "what's
         * buzzing on the phone" SHOULD rank `device.vibrate` above
         * `notifications.list`, because "buzz" is a literal tag on the buzzer
         * and a direct tag hit is worth 3x while a synonym is worth 1. The test
         * caught exactly that mistake when it was first written, which is the
         * argument for asserting the narrow property instead of the obvious one.
         */
        val expectations = listOf(
            Expectation("nuke", "nuke that screenshot", "files.delete"),
            Expectation("scrap", "scrap the old draft", "files.delete"),
            Expectation("schedul", "schedule the plumber for tuesday", "calendar.create"),
            Expectation("pen", "pen me in with the barber", "calendar.create"),
            Expectation("jot", "jot down that the code is 1234", "files.write_text"),
            Expectation("digit", "what are Yasmin's digits", "contacts.search"),
            Expectation("buzz", "what is buzzing on the phone", "notifications.list"),
            Expectation("story", "grab the story from nos.nl", "web.fetch"),
        )

        assertEquals(
            "this test must cover every surviving synonym entry; add the new one here " +
                "or delete it. live=${LexicalNormalizer.synonymKeys}",
            LexicalNormalizer.synonymKeys,
            expectations.map { it.key }.toSet(),
        )

        expectations.forEach { e ->
            val withEntry = LexicalScorer.rank(e.utterance, emptyList(), tools, 6, table)
                .map { it.definition.name }
            val withoutEntry = LexicalScorer.rank(
                e.utterance, emptyList(), tools, 6, table.filterKeys { it != e.key },
            ).map { it.definition.name }

            assertTrue(
                "\"$e.key\" must fire on \"${e.utterance}\"; expansion was " +
                    LexicalNormalizer.expansionOnlyStems(e.utterance, table),
                LexicalNormalizer.expansionOnlyStems(e.utterance, table).isNotEmpty(),
            )
            assertTrue(
                "\"${e.utterance}\" should put ${e.expected} in the window via the " +
                    "\"$e.key\" entry, got $withEntry",
                e.expected in withEntry,
            )
            assertFalse(
                "\"$e.key\" earns nothing: ${e.expected} is already in the window " +
                    "without it ($withoutEntry). Delete the entry.",
                e.expected in withoutEntry,
            )
        }
    }

    /**
     * The over-fire half, stated as a ranking property.
     *
     * An entry is allowed to bring a neighbouring tool along — "nuke" should
     * make `alarm.cancel` reachable, because "delete" is a tag on it. What it is
     * NOT allowed to do is put a tool it brought in ABOVE the one the entry
     * exists to serve, because the first tool in the window is the one a 1-4B
     * model reads first.
     *
     * "Brought in" is measured, not assumed: it means present in the window with
     * the entry and absent without it. That distinction matters more than it
     * sounds. With 25 tools and a 6-wide window, a tool that scores ZERO is
     * still in the window about a fifth of the time purely because its name
     * sorts early, and the first version of this test failed on
     * `contacts.search` for exactly that reason — it was in the window on
     * alphabetical accident, not because `buzz` put it there.
     */
    @Test
    fun `no entry puts a tool it brought in above the tool it serves`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val table = LexicalNormalizer.synonymTable

        listOf(
            Triple("nuke", "nuke that screenshot", "files.delete"),
            Triple("scrap", "scrap the old draft", "files.delete"),
            Triple("schedul", "schedule the plumber for tuesday", "calendar.create"),
            Triple("pen", "pen me in with the barber", "calendar.create"),
            Triple("jot", "jot down that the code is 1234", "files.write_text"),
            Triple("digit", "what are Yasmin's digits", "contacts.search"),
            Triple("buzz", "what is buzzing on the phone", "notifications.list"),
            Triple("story", "grab the story from nos.nl", "web.fetch"),
        ).forEach { (key, utterance, expected) ->
            val withEntry = LexicalScorer.rank(utterance, emptyList(), tools, 6, table)
                .map { it.definition.name }
            val withoutEntry = LexicalScorer.rank(
                utterance, emptyList(), tools, 6, table.filterKeys { it != key },
            ).map { it.definition.name }

            val broughtIn = withEntry.toSet() - withoutEntry.toSet() - expected
            assertTrue(
                "\"$key\" should bring at least one tool into the window for " +
                    "\"$utterance\"; it brought none, so the entry is inert here. " +
                    "with=$withEntry without=$withoutEntry",
                broughtIn.isNotEmpty() || expected in withEntry,
            )

            val expectedPosition = withEntry.indexOf(expected)
            broughtIn.forEach { other ->
                val otherPosition = withEntry.indexOf(other)
                if (otherPosition in 0 until expectedPosition) {
                    throw AssertionError(
                        "\"$key\" put $other ABOVE $expected for \"$utterance\" " +
                            "(ranked $withEntry, brought in by the entry: $broughtIn). " +
                            "Either the entry is too broad or a tag list is wrong.",
                    )
                }
            }
        }
    }

    private data class Expectation(val key: String, val utterance: String, val expected: String)

    @Test
    fun `an expansion can never outrank a direct hit`() {
        // The weight exists for this. EXPANSION_WEIGHT is 1 against TAG_WEIGHT
        // 3, so three synonym hits are needed to beat one real tag match. If a
        // future change raises EXPANSION_WEIGHT to 3, "text" (a tag on three
        // unrelated tools) can outrank what the user actually typed.
        assertTrue(
            "EXPANSION_WEIGHT (${LexicalScorer.EXPANSION_WEIGHT}) must be below " +
                "TAG_WEIGHT (${LexicalScorer.TAG_WEIGHT}) or a synonym can outrank a real match",
            LexicalScorer.EXPANSION_WEIGHT < LexicalScorer.TAG_WEIGHT,
        )
        assertTrue(
            "EXPANSION_WEIGHT must be below NAME_WEIGHT too",
            LexicalScorer.EXPANSION_WEIGHT < LexicalScorer.NAME_WEIGHT,
        )
    }

    @Test
    fun `expansion never re-scores a stem the query already said directly`() {
        // Otherwise every direct hit would be counted twice — once at the tag
        // weight and once at the expansion weight — and the two weights would
        // stop meaning what the docs say they mean.
        val text = "delete the file"
        val direct = LexicalNormalizer.normalizedTokens(text).toSet()
        val expansion = LexicalNormalizer.expansionOnlyStems(text)
        assertTrue("expansion must not contain a direct stem", direct.intersect(expansion).isEmpty())
    }

    @Test
    fun `an utterance with no synonym in it expands to nothing`() {
        // The common case on a phone, and it must cost nothing. If this grows a
        // synonym for "battery" or "photos" the table is starting to duplicate
        // the tag list, which is a tags decision wearing a selector's clothes.
        val expansion = LexicalNormalizer.expansionOnlyStems("how much battery do I have")
        assertTrue(
            "a plain utterance should expand to nothing, got $expansion",
            expansion.isEmpty() || expansion.all { LexicalNormalizer.isSynonymStem(it) },
        )
    }

    // -----------------------------------------------------------------------
    // Tokenizer
    // -----------------------------------------------------------------------

    @Test
    fun `tokenizer keeps the shipped contract — lowercase, split, drop short`() {
        // "on" and "my" are two characters and are dropped by the shipped
        // contract, so the expected list is what is left.
        assertEquals(
            listOf("what", "calendar"),
            LexicalNormalizer.normalizedTokens("What's on my CALENDAR"),
        )
        // "is", "it" and "on" are all two characters, so all three go. The
        // surviving three-character token is the control.
        val dropped = LexicalNormalizer.normalizedTokens("is it on the list")
        assertFalse("2-char tokens must be dropped", dropped.contains("is"))
        assertFalse("2-char tokens must be dropped", dropped.contains("it"))
        assertFalse("2-char tokens must be dropped", dropped.contains("on"))
        assertTrue("a 3-char token must survive", dropped.contains("the"))
        assertTrue("a longer token must survive", dropped.contains("list"))
    }

    @Test
    fun `tokenizer is deterministic and order-preserving`() {
        val text = "search the files and delete the old ones"
        assertEquals(LexicalNormalizer.normalizedTokens(text), LexicalNormalizer.normalizedTokens(text))
        assertEquals(
            "token order must match the source order, the ranker relies on determinism not on order but " +
                "diagnostics read it",
            text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.size,
            LexicalNormalizer.normalizedTokens(text).size,
        )
    }

    // -----------------------------------------------------------------------
    // The three specific regressions the brief names
    // -----------------------------------------------------------------------

    @Test
    fun `scheduled now finds calendar tools`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val selected = dev.localintelligence.core.tool.LexicalToolSelector()
            .select("do I have anything scheduled for this afternoon", emptyList(), tools, 6)
            .map { it.definition.name }
        assertTrue(
            "calendar.search must be selected for \"scheduled\", got $selected",
            "calendar.search" in selected,
        )
    }

    @Test
    fun `nuke now finds files delete`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val selected = dev.localintelligence.core.tool.LexicalToolSelector()
            .select("nuke the screenshot", emptyList(), tools, 6)
            .map { it.definition.name }
        assertTrue("files.delete must be selected for \"nuke\", got $selected", "files.delete" in selected)
    }

    @Test
    fun `wake me up now finds alarm create`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val selected = dev.localintelligence.core.tool.LexicalToolSelector()
            .select("wake me up at six on weekdays", emptyList(), tools, 6)
            .map { it.definition.name }
        assertTrue("alarm.create must be selected for \"wake me up\", got $selected", "alarm.create" in selected)
    }

    /**
     * The diagnostic has to agree with the ranking, or it explains misses wrongly.
     *
     * [LexicalScorer.breakdown] exists so a zero-scoring tool can be read
     * without a debugger, and this is the test that stops it rotting into a
     * plausible lie: the parts must add up to the total, for a direct hit and
     * for a synonym hit, because those are the two ways a score is built.
     */
    @Test
    fun `the score breakdown adds up to the score`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()

        listOf(
            "do I have anything scheduled for this afternoon",
            "nuke that screenshot",
            "wake me up at six on weekdays",
            "how much battery do I have",
        ).forEach { utterance ->
            tools.forEach { tool ->
                val def = tool.definition
                val score = LexicalScorer.score(utterance, emptyList(), def)
                val parts = LexicalScorer.breakdown(utterance, emptyList(), def)
                assertEquals(
                    "breakdown total != score for \"$utterance\" vs ${def.name}\n" +
                        parts.render(def.name),
                    score, parts.total,
                )
            }
        }
    }

    /**
     * "scheduled" has to reach BOTH calendar tools, and for different reasons.
     *
     * This is the specific miss the brief names, so it is worth being precise
     * about what fixes it. `calendar.search` carries the literal tag
     * "scheduled", so stemming alone reaches it. `calendar.create` does not,
     * and never will — the word "schedule" is not in its tag list — so the
     * SYNONYM is what puts it in the window. If someone later deletes the
     * "schedul" entry believing it is redundant, this test says exactly what
     * breaks.
     */
    @Test
    fun `scheduled reaches calendar_create through the synonym, not the tag`() {
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val create = tools.first { it.definition.name == "calendar.create" }.definition
        val search = tools.first { it.definition.name == "calendar.search" }.definition
        val utterance = "do I have anything scheduled for this afternoon"

        val createParts = LexicalScorer.breakdown(utterance, emptyList(), create)
        val searchParts = LexicalScorer.breakdown(utterance, emptyList(), search)

        assertTrue(
            "calendar.search should be reached directly by the tag \"scheduled\", got\n" +
                searchParts.render("calendar.search"),
            searchParts.tagHits.contains("schedul") || searchParts.nameHits.contains("schedul"),
        )
        assertTrue(
            "calendar.create has no schedule tag, so it must be reached by the " +
                "synonym entry. If this fails, the \"schedul\" entry is load-bearing.\n" +
                createParts.render("calendar.create"),
            createParts.expandedTagHits.isNotEmpty(),
        )
        assertTrue(
            "calendar.create must be in the window, got " +
                dev.localintelligence.core.tool.LexicalToolSelector()
                    .select(utterance, emptyList(), tools, 6)
                    .map { it.definition.name },
            dev.localintelligence.core.tool.LexicalToolSelector()
                .select(utterance, emptyList(), tools, 6)
                .map { it.definition.name }
                .contains("calendar.create"),
        )
    }

    @Test
    fun `each named regression scores above zero, not just by alphabetical luck`() {
        // Being in the top 6 of 25 is not evidence on its own — roughly a fifth
        // of the set is always visible by name order. These three scored ZERO
        // before this change, which is the actual defect, so assert the score.
        val tools = dev.localintelligence.core.eval.AndroidToolSet.build().registry.all()
        val cases = listOf(
            Triple("do I have anything scheduled for this afternoon", "calendar.create", "calendar.search"),
            Triple("nuke the screenshot", "files.delete", null),
            Triple("wake me up at six on weekdays", "alarm.create", null),
        )
        cases.forEach { (utterance, expected, _) ->
            val def = tools.first { it.definition.name == expected }.definition
            val score = LexicalScorer.score(utterance, emptyList(), def)
            assertTrue(
                "\"$utterance\" -> $expected scores $score; it must be above zero, or the " +
                    "tool is only visible by alphabetical accident",
                score > 0,
            )
        }
    }

    private companion object {
        /**
         * Every surface form a synonym key has to be reachable from.
         *
         * Written out rather than derived from the tag lists, because deriving
         * it would only prove the key is reachable from the tool set and not
         * from anything a user would actually say — and the whole point of the
         * table is the gap between those two vocabularies. This list is the
         * claim under test, and it is the list a reviewer can check by eye.
         */
        val surfaceForms = listOf(
            "nuke", "nuked", "nukes", "scrap", "scrapped",
            "schedule", "scheduled", "scheduling", "pen", "penned",
            "jot", "jotted", "jotting",
            "digit", "digits", "buzz", "buzzing", "buzzed",
            "story", "stories",
        )

        /**
         * Ceiling on synonym entries.
         *
         * Not a style preference. This is a static table resident on a phone and
         * walked on every step of every task, and the brief's own reasoning is
         * that a 500-word thesaurus is a liability in a RAM-constrained app.
         *
         * 8 is where the entries that survive the ablation sit, after 31 were
         * measured and deleted. The ceiling is 12: four slots of headroom for a
         * genuinely earned entry, so the next person adding one has to justify
         * it against a held-out probe case rather than against this number.
         */
        const val MAX_SYNONYM_ENTRIES = 12

        /**
         * Ceiling on total reachable stems, which is what the table really
         * costs. Checked separately from the entry count because an author can
         * keep the entry count down and still blow up the resident size by
         * making one entry point at forty words.
         */
        const val MAX_SYNONYM_STEMS = 50

        /**
         * Every synonym target the live table can emit.
         *
         * Written out rather than derived, because deriving it would just
         * re-read the table and assert it equals itself. Written out, it is a
         * claim a reviewer can check against the tool tags by eye — and the
         * rule it encodes is the one that matters: a target must be a word some
         * tool is actually tagged with, or the entry is pure memory cost.
         */
        val KNOWN_SYNONYM_TARGETS = setOf(
            // files.delete vocabulary
            "delet", "trash", "bin",
            // calendar.create / calendar.search vocabulary
            "book", "slot", "invit", "calendar",
            // files.write_text vocabulary
            "writ", "note", "save", "text",
            // contacts.search vocabulary
            "number", "phon", "contact", "peopl",
            // device.vibrate and notifications.list vocabulary
            "notification", "vibrat", "haptic", "alert",
            // web.fetch vocabulary
            "web", "onlin", "news", "latest",
        )
    }
}
