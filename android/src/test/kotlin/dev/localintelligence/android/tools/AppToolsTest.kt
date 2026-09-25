package dev.localintelligence.android.tools

import dev.localintelligence.android.tools.apps.AppArgs
import dev.localintelligence.android.tools.apps.AppEntry
import dev.localintelligence.android.tools.apps.AppMatch
import dev.localintelligence.android.tools.apps.AppMatcher
import dev.localintelligence.android.tools.apps.AppQuery
import dev.localintelligence.android.tools.apps.AppShareTarget
import dev.localintelligence.android.tools.apps.ShareTarget
import dev.localintelligence.core.tool.ObservationTruncator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only tests for the app tools' pure logic.
 *
 * The load-bearing test in this file is
 * `Chr must not resolve to one of Chrome and Chrome Beta`: an agent that guesses
 * between two apps is an agent that opens the wrong one, and there is no recovery from
 * that after the fact. `PackageManager` and `Intent` cannot execute without a device, so
 * the decision logic lives in [AppMatcher] as pure functions over a list and is proven
 * here; the PlatformManager shell around it is verified by compilation only.
 */
class AppToolsTest {

    // ============================================================== the ambiguity rule

    @Test
    fun `Chr matching Chrome and Chrome Beta is ambiguous and picks neither`() {
        // THE test. "Chr" is a prefix of both labels. Picking the first match is how an
        // agent ends up in the wrong browser, so this must return Ambiguous with both
        // candidates and no Resolved anywhere.
        val apps = listOf(
            app("Chrome", "com.android.chrome"),
            app("Chrome Beta", "com.chrome.beta"),
        )

        val match = AppMatcher.match("Chr", apps)

        assertTrue("expected Ambiguous, got $match", match is AppMatch.Ambiguous)
        val ambiguous = match as AppMatch.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        assertEquals(setOf("com.android.chrome", "com.chrome.beta"), ambiguous.candidates.map { it.packageName }.toSet())
    }

    @Test
    fun `an ambiguous match is never collapsed to the first candidate`() {
        val apps = listOf(
            app("Chrome", "com.android.chrome"),
            app("Chrome Beta", "com.chrome.beta"),
            app("Chrome Canary", "com.chrome.canary"),
        )

        val match = AppMatcher.match("chr", apps)

        assertTrue(match is AppMatch.Ambiguous)
        // Every candidate is reported, so the model can pick by package name.
        assertEquals(3, (match as AppMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `the ambiguity observation names every candidate and the exact next call`() {
        val apps = listOf(app("Chrome", "com.android.chrome"), app("Chrome Beta", "com.chrome.beta"))
        val text = AppMatcher.ambiguityObservation(AppMatcher.match("Chr", apps) as AppMatch.Ambiguous)

        assertTrue(text.contains("2 apps match 'Chr'"))
        assertTrue(text.contains("Chrome (com.android.chrome)"))
        assertTrue(text.contains("Chrome Beta (com.chrome.beta)"))
        // Actionable recovery, not just a diagnostic: the model must be told what to do.
        assertTrue(text.contains("package"))
        assertTrue(text.contains("com.android.chrome"))
        assertTrue(text.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
    }

    @Test
    fun `an exact label match resolves even when a longer label shares its prefix`() {
        // "Chrome" exactly is unambiguous: the user named one app precisely. Only the
        // *fuzzy* tiers are allowed to be ambiguous.
        val apps = listOf(app("Chrome", "com.android.chrome"), app("Chrome Beta", "com.chrome.beta"))
        val match = AppMatcher.match("Chrome", apps)

        assertTrue(match is AppMatch.Resolved)
        assertEquals("com.android.chrome", (match as AppMatch.Resolved).entry.packageName)
    }

    @Test
    fun `an exact package name resolves even when the label is ambiguous`() {
        val apps = listOf(app("Chrome", "com.android.chrome"), app("Chrome Beta", "com.chrome.beta"))
        val match = AppMatcher.match("com.chrome.beta", apps)

        assertTrue(match is AppMatch.Resolved)
        assertEquals("com.chrome.beta", (match as AppMatch.Resolved).entry.packageName)
    }

    @Test
    fun `an installed package name is never fuzzy matched into a different app`() {
        // A caller who said a package name means it. If it is not installed the answer
        // is not-found, not "well, Chrome looks close".
        val apps = listOf(app("Chrome", "com.android.chrome"))
        val match = AppMatcher.match("com.example.absent", apps)

        assertTrue(match is AppMatch.None)
    }

    @Test
    fun `no match at all is reported as not found rather than as the closest app`() {
        val apps = listOf(app("Chrome", "com.android.chrome"), app("Maps", "com.google.android.apps.maps"))
        val match = AppMatcher.match("Skype", apps)

        assertTrue(match is AppMatch.None)
        assertEquals("Skype", (match as AppMatch.None).query)
    }

    @Test
    fun `an empty query never resolves to an arbitrary app`() {
        val apps = listOf(app("Chrome", "com.android.chrome"))
        for (junk in listOf("", "   ", "\t\n")) {
            assertTrue("'$junk' must not resolve", AppMatcher.match(junk, apps) is AppMatch.None)
        }
    }

    @Test
    fun `an empty app list resolves to nothing`() {
        assertTrue(AppMatcher.match("Chrome", emptyList()) is AppMatch.None)
    }

    @Test
    fun `a match is case insensitive on both sides`() {
        val apps = listOf(app("Google Maps", "com.google.android.apps.maps"))
        assertTrue(AppMatcher.match("maps", apps) is AppMatch.Resolved)
        assertTrue(AppMatcher.match("GOOGLE MAPS", apps) is AppMatch.Resolved)
        assertTrue(AppMatcher.match("gOoGlE mApS", apps) is AppMatch.Resolved)
    }

    @Test
    fun `a word-boundary match is preferred over a buried substring`() {
        // "maps" must find "My Maps", not only "Heatmaps": a match at a word boundary
        // outranks one buried inside another word.
        val apps = listOf(app("Heatmaps", "x.maps.heat"), app("My Maps", "y.maps"))
        val match = AppMatcher.match("maps", apps)

        assertTrue("expected Resolved, got $match", match is AppMatch.Resolved)
        assertEquals("y.maps", (match as AppMatch.Resolved).entry.packageName)
    }

    @Test
    fun `an exact label match outranks a longer label that contains it`() {
        val apps = listOf(app("Chrome Beta", "com.chrome.beta"), app("Maps", "com.google.maps"))
        val match = AppMatcher.match("Maps", apps)

        assertTrue(match is AppMatch.Resolved)
        assertEquals("com.google.maps", (match as AppMatch.Resolved).entry.packageName)
    }

    @Test
    fun `a multi-word query matches when every token appears`() {
        val apps = listOf(app("Google Play Music", "com.google.android.music"))
        assertTrue(AppMatcher.match("play music", apps) is AppMatch.Resolved)
        // A token that is absent must not be treated as a wildcard.
        assertTrue(AppMatcher.match("play video", apps) is AppMatch.None)
    }

    @Test
    fun `candidates are capped so a pathological install cannot flood the observation`() {
        val apps = (1..50).map { app("Chrome $it", "com.chrome.$it") }
        val match = AppMatcher.match("Chrome", apps)

        assertTrue(match is AppMatch.Ambiguous)
        assertEquals(AppMatcher.MAX_CANDIDATES, (match as AppMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `a package name is recognised by shape`() {
        assertTrue(AppMatcher.isPackageName("com.android.chrome"))
        assertTrue(AppMatcher.isPackageName("com.example.app_beta1"))
        assertFalse(AppMatcher.isPackageName("Chrome"))
        assertFalse(AppMatcher.isPackageName("noDots"))
        assertFalse(AppMatcher.isPackageName(""))
        assertFalse(AppMatcher.isPackageName(null))
        assertFalse(AppMatcher.isPackageName(".leadingdot"))
    }

    @Test
    fun `a label that merely looks like a path is not treated as a package name`() {
        // A hostile or confused "package" argument must not skip the ambiguity check.
        val apps = listOf(app("Chrome", "com.android.chrome"))
        val match = AppMatcher.match("com.android.chrome/../evil", apps)
        assertTrue(match is AppMatch.None)
    }

    // ============================================================== apps.list filtering

    @Test
    fun `filtering by label keeps matching apps and caps the result`() {
        val apps = listOf(
            app("Chrome", "com.android.chrome"),
            app("Chrome Beta", "com.chrome.beta"),
            app("Maps", "com.google.android.apps.maps"),
        )

        val result = AppQuery.filter(apps, "chrome", limit = 10)

        assertEquals(2, result.size)
        assertTrue(result.all { it.label.contains("Chrome") })
    }

    @Test
    fun `filtering by package name works as well as by label`() {
        val apps = listOf(app("Browser", "com.android.chrome"), app("Camera", "com.android.camera"))
        val result = AppQuery.filter(apps, "com.android.chrome", limit = 10)

        assertEquals(1, result.size)
        assertEquals("com.android.chrome", result.first().packageName)
    }

    @Test
    fun `an exact label outranks a merely containing one`() {
        val apps = listOf(
            app("Chrome Beta", "com.chrome.beta"),
            app("Chrome", "com.android.chrome"),
        )

        val result = AppQuery.filter(apps, "chrome", limit = 10)

        assertEquals("com.android.chrome", result.first().packageName)
    }

    @Test
    fun `filtering with no query returns every app sorted by label`() {
        val apps = listOf(app("Zebra", "z.app"), app("Apple", "a.app"), app("Mango", "m.app"))
        val result = AppQuery.filter(apps, null, limit = 10)

        assertEquals(listOf("Apple", "Mango", "Zebra"), result.map { it.label })
    }

    @Test
    fun `filtering with no matches yields an empty list, not the whole list`() {
        val apps = listOf(app("Chrome", "com.android.chrome"))
        assertTrue(AppQuery.filter(apps, "skype", limit = 10).isEmpty())
    }

    @Test
    fun `the filter limit is respected`() {
        val apps = (1..80).map { app("App $it", "com.example.a$it") }
        val result = AppQuery.filter(apps, "App", limit = 5)

        assertEquals(5, result.size)
    }

    @Test
    fun `filtering is deterministic across input order`() {
        val apps = (1..30).map { app("Tool $it", "com.example.t$it") }
        val a = AppQuery.filter(apps, "tool", limit = 10).map { it.packageName }
        val b = AppQuery.filter(apps.reversed(), "tool", limit = 10).map { it.packageName }

        assertEquals(a, b)
    }

    @Test
    fun `rank orders an exact package above an exact label above a prefix`() {
        // The tiers are only comparable for the SAME query, so the query has to be one
        // string that several of these apps can plausibly match.
        val exactPkg = app("Widget", "com.widget.app")
        val exactLabel = app("com.widget.app", "com.other.pkg")
        val prefix = app("com.widget.app Pro", "com.widget.pro")
        // No space anywhere: this must fall to the bare-substring tier, not the
        // word-boundary one, or the comparison would be between the same tier.
        val buried = app("zzcom.widget.appzz", "com.my.widget")

        val q = "com.widget.app"
        assertEquals(100, AppQuery.rank(exactPkg, q))
        assertEquals(90, AppQuery.rank(exactLabel, q))
        assertEquals(60, AppQuery.rank(prefix, q))
        assertEquals(20, AppQuery.rank(buried, q))
        assertTrue(AppQuery.rank(exactPkg, q) > AppQuery.rank(exactLabel, q))
        assertTrue(AppQuery.rank(exactLabel, q) > AppQuery.rank(prefix, q))
        assertTrue(AppQuery.rank(prefix, q) > AppQuery.rank(buried, q))
    }

    @Test
    fun `rank is zero for an unrelated app`() {
        assertEquals(0, AppQuery.rank(app("Maps", "com.google.maps"), "chrome"))
    }

    @Test
    fun `an app list observation states what was withheld`() {
        val apps = listOf(app("Chrome", "com.android.chrome"), app("Maps", "com.google.maps"))
        val text = AppQuery.format(apps, withheld = 40, what = "apps")

        assertTrue("got: $text", text.contains("2 apps"))
        assertTrue(text.contains("40 more not shown"))
        assertTrue(text.contains("Chrome (com.android.chrome)"))
        assertTrue(text.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
    }

    @Test
    fun `an empty app list says so in words with a next step`() {
        val text = AppQuery.format(emptyList(), withheld = 0, what = "apps matching 'skype'")

        assertTrue(text.startsWith("No apps matching 'skype' matched."))
        assertTrue(text.contains("apps.list"))
    }

    @Test
    fun `an app with no launcher is marked as such`() {
        val text = AppQuery.format(listOf(app("Thing", "com.thing", launchable = false)), 0, "apps")
        assertTrue(text.contains("[no launcher]"))
    }

    // ================================================================ share target

    @Test
    fun `a file URI is refused with the reason and the fix`() {
        // The load-bearing case. A file:// URI throws FileUriExposedException inside
        // startActivity on Android 7+, which the agent loop cannot recover from.
        val target = AppShareTarget.resolve("file:///storage/emulated/0/Download/a.pdf", null)

        assertTrue(target is ShareTarget.FileUriRefused)
    }

    @Test
    fun `the file URI refusal names the exception and the correct argument`() {
        val message = AppShareTarget.fileUriRefusal("file:///sdcard/a.pdf")

        assertTrue(message.contains("FileUriExposedException"))
        assertTrue(message.contains("Android 7.0"))
        assertTrue(message.contains("content://"))
        assertTrue(message.contains("files.search"))
    }

    @Test
    fun `a content URI is shareable`() {
        val target = AppShareTarget.resolve("content://media/external/downloads/5", null)

        assertTrue(target is ShareTarget.Shareable)
        assertEquals("content://media/external/downloads/5", (target as ShareTarget.Shareable).uri)
    }

    @Test
    fun `text with no attachment is shareable`() {
        val target = AppShareTarget.resolve(null, "hello there")

        assertTrue(target is ShareTarget.TextOnly)
        assertEquals("hello there", (target as ShareTarget.TextOnly).text)
    }

    @Test
    fun `a content URI wins over text when both are given`() {
        val target = AppShareTarget.resolve("content://x/1", "some caption")
        assertTrue(target is ShareTarget.Shareable)
    }

    @Test
    fun `neither a URI nor text is nothing to share`() {
        assertEquals(ShareTarget.Nothing, AppShareTarget.resolve(null, null))
        assertEquals(ShareTarget.Nothing, AppShareTarget.resolve("", "   "))
    }

    @Test
    fun `an unknown scheme is refused rather than passed to the chooser`() {
        for (uri in listOf("http://example.com/a", "/sdcard/a.txt", "android.resource://x", "content://")) {
            assertTrue(
                "'$uri' must be refused",
                AppShareTarget.resolve(uri, null) is ShareTarget.FileUriRefused,
            )
        }
    }

    @Test
    fun `a file URI is only accepted when a FileProvider is actually configured`() {
        // This build has no FileProvider, so the default must be refusal. The
        // allowFileUris branch exists to pin what a future manifest change would do.
        val refused = AppShareTarget.resolve("file:///sdcard/a.txt", null, allowFileUris = false)
        assertTrue(refused is ShareTarget.FileUriRefused)

        // With a provider present the caller's converter would take over; the pure
        // decision here still refuses a bare path it cannot validate.
        assertNotNull(refused)
    }

    @Test
    fun `the attachment mime type is inferred from the name and defaults safely`() {
        assertEquals("application/pdf", AppShareTarget.mimeFor("report.pdf"))
        assertEquals("text/plain", AppShareTarget.mimeFor("notes.txt"))
        assertEquals("image/png", AppShareTarget.mimeFor("shot.png"))
        assertEquals("application/octet-stream", AppShareTarget.mimeFor(null))
        assertEquals("application/octet-stream", AppShareTarget.mimeFor("mystery.qqq"))
    }

    // ==================================================================== coercion

    @Test
    fun `an app limit given as the string 8 is read as 8`() {
        assertEquals(8, AppArgs.limit(JsonPrimitive("8")))
    }

    @Test
    fun `an app limit is clamped and defaults like the file tools`() {
        assertEquals(AppArgs.MAX_LIMIT, AppArgs.limit(JsonPrimitive(9999)))
        assertEquals(AppArgs.DEFAULT_LIMIT, AppArgs.limit(JsonPrimitive(-1)))
        assertEquals(AppArgs.DEFAULT_LIMIT, AppArgs.limit(null))
        assertEquals(AppArgs.DEFAULT_LIMIT, AppArgs.limit(JsonNull))
        assertEquals(AppArgs.DEFAULT_LIMIT, AppArgs.limit(JsonPrimitive("lots")))
    }

    @Test
    fun `a null app argument never becomes the string null`() {
        assertNull(AppArgs.stringOrNull(JsonNull))
        assertNull(AppArgs.optionalString(JsonNull))
        assertNull(AppArgs.optionalString(JsonPrimitive("   ")))
        assertNull(AppArgs.optionalString(null))
    }

    @Test
    fun `an object where a string belongs is rejected not stringified`() {
        assertNull(AppArgs.stringOrNull(JsonObject(emptyMap())))
        assertNull(AppArgs.stringOrNull(kotlinx.serialization.json.buildJsonArray { }))
    }

    @Test
    fun `an over-long app query is truncated`() {
        val long = "a".repeat(AppArgs.MAX_QUERY_CHARS + 100)
        assertEquals(AppArgs.MAX_QUERY_CHARS, AppArgs.optionalString(JsonPrimitive(long))!!.length)
    }

    // =========================================================== observation budget

    @Test
    fun `worst case app observations stay inside the budget`() {
        val manyApps = (1..500).map { app("Application $it", "com.example.app$it") }
        val cases = listOf(
            AppQuery.format(manyApps, withheld = 400, what = "apps"),
            AppMatcher.ambiguityObservation(
                AppMatcher.match("App", manyApps.filter { it.label.contains("App") }.take(3)) as AppMatch.Ambiguous,
            ),
            AppShareTarget.fileUriRefusal("file://" + "p".repeat(4000)),
            AppMatcher.match("", manyApps).toString().take(4000),
        )

        for (case in cases) {
            assertTrue(
                "observation of ${case.length} chars exceeds the budget",
                ObservationTruncator.truncate(case).length <= ObservationTruncator.DEFAULT_BUDGET_CHARS,
            )
        }
    }

    @Test
    fun `an ambiguity observation with many candidates is still bounded`() {
        val apps = (1..200).map { app("Chrome Variant $it", "com.chrome.v$it") }
        val match = AppMatcher.match("Chrome", apps) as AppMatch.Ambiguous
        val text = AppMatcher.ambiguityObservation(match)

        assertEquals(AppMatcher.MAX_CANDIDATES, match.candidates.size)
        assertTrue(text.length < ObservationTruncator.DEFAULT_BUDGET_CHARS)
        // The cap is disclosed, so the model knows the list is not exhaustive.
        assertTrue(text.contains("possibly more"))
    }

    private fun app(label: String, pkg: String, launchable: Boolean = true) =
        AppEntry(packageName = pkg, label = label, launchable = launchable)
}
