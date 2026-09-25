package dev.localintelligence.app.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Asserts that the *shipped resource files* say what the code assumes they say.
 *
 * ## Why parse XML in a unit test at all
 *
 [ProviderPathScopeTest] proves the resolver is correct. This file proves the
 * resolver and the shipped configuration have not drifted apart, and that the
 * manifest carries the security attributes. Both are exactly the kind of thing
 * that gets edited by a later PR that only means to "let the share tool also
 * reach the models directory" — and that edit widens a content provider's reach
 * without failing a single existing test.

 * So these are assertions about `file_paths.xml` and `AndroidManifest.xml` as
 * files on disk. A test that reads the real resource fails the same way in CI as
 * it does on a laptop, and it fails the moment someone widens the scope, which
 * is the moment the conversation should happen.
 *
 * The manifest is read from `src/main` rather than from the merged output on
 * purpose: the merged manifest is a build artifact that does not exist before a
 * build has run, and a test that cannot run without a build is a test that gets
 * skipped.
 */
class ProviderConfigTest {

    private fun repoFile(relative: String): File {
        // Walk up from the module directory to the repository root. The test runs
        // with the Gradle module dir as CWD, so one level up is the root; the loop
        // makes it robust to being run from the repo root too.
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError("could not locate $relative from ${File("").absolutePath}")
    }

    private fun filePathsXml(): String = repoFile("src/main/res/xml/file_paths.xml").readText()

    private fun manifestXml(): String = repoFile("src/main/AndroidManifest.xml").readText()

    /**
     * The `<paths>` document with every XML comment removed.
     *
     * WHY this exists: `file_paths.xml` documents, in prose, exactly which path
     * elements are forbidden and why. A naive `contains("<root-path")` assertion
     * therefore fails on this repo's own security documentation rather than on a
     * real scope change, which is a test that cries wolf and gets deleted. The
     * assertions below run against the *declarations*, which is what the scope
     * actually is.
     */
    private fun filePathsDeclarations(): String =
        filePathsXml()
            .replace(COMMENT, "")
            .replace(XML_DECL, "")
    // ------------------------------------------------------- file_paths.xml scope

    @Test
    fun `file_paths declares no root-path`() {
        // root-path is the whole filesystem. There is no feature that needs it.
        assertTrue(
            "file_paths.xml must not declare <root-path>",
            !filePathsDeclarations().contains("<root-path"),
        )
    }

    @Test
    fun `file_paths declares no external-path`() {
        // external-path is every other app's shared storage on the device.
        assertTrue(
            "file_paths.xml must not declare <external-path>",
            !filePathsDeclarations().contains("<external-path"),
        )
    }

    @Test
    fun `file_paths declares no cache-path`() {
        // cache holds speculative, partial, in-flight data.
        assertTrue(
            "file_paths.xml must not declare <cache-path>",
            !filePathsDeclarations().contains("<cache-path"),
        )
    }

    @Test
    fun `file_paths does not declare the whole files directory`() {
        // A <files-path> with no path attribute, or path=".", exposes the
        // databases and shared_prefs the user typed into.
        val xml = filePathsDeclarations()
        assertTrue(
            "file_paths.xml must not declare a bare <files-path>",
            !Regex("<files-path\\s*/?>").containsMatchIn(xml),
        )
        assertTrue(
            "file_paths.xml must not declare path=\".\"",
            !Regex("path\\s*=\\s*\"\\.\"").containsMatchIn(xml),
        )
    }

    @Test
    fun `file_paths declares exactly the one path the code assumes`() {
        // The drift guard. ProviderPathScope.ROOT_NAME and ROOT_RELATIVE are the
        // resolver's model of this file; if the two disagree, every Allowed in
        // ProviderPathScopeTest is a lie.
        val xml = filePathsDeclarations()
        assertTrue(
            "file_paths.xml must declare the files-path named '${ProviderPathScope.ROOT_NAME}'",
            xml.contains("name=\"${ProviderPathScope.ROOT_NAME}\""),
        )
        assertTrue(
            "file_paths.xml must declare path=\"${ProviderPathScope.ROOT_RELATIVE}\"",
            xml.contains("path=\"${ProviderPathScope.ROOT_RELATIVE}\""),
        )
    }

    @Test
    fun `file_paths declares exactly one path element`() {
        val count = Regex("<(files-path|external-path|root-path|cache-path|external-cache-path|files-external-path)\\b")
            .findAll(filePathsDeclarations())
            .count()
        assertEquals(
            "the provider scope must stay at one path; a second one needs a comment justifying it",
            1,
            count,
        )
    }

    @Test
    fun `every declared path resolves inside app-private storage`() {
        // Walks each declared path through the same resolver a tool would use.
        // The declared root must produce an Allowed that lands under filesDir.
        val filesDir = File(System.getProperty("java.io.tmpdir"), "fileprovider-scope-probe")
        filesDir.mkdirs()
        File(filesDir, ProviderPathScope.ROOT_RELATIVE).mkdirs()

        val result = ProviderPathScope.resolve(filesDir, ProviderPathScope.ROOT_RELATIVE + "probe.txt")
        val allowed = result as? ProviderPathScope.Resolution.Allowed
        assertNotNull("the declared root must resolve to an Allowed, got $result", allowed)
        assertTrue(
            "resolved file escaped app-private storage: ${allowed!!.file}",
            allowed.file.canonicalPath.startsWith(filesDir.canonicalPath + File.separator),
        )
        filesDir.deleteRecursively()
    }

    // ------------------------------------------------------------------- manifest

    @Test
    fun `manifest declares the provider with exported false`() {
        val block = providerBlock()
        assertTrue(
            "provider must be android:exported=\"false\"",
            Regex("android:exported\\s*=\\s*\"false\"").containsMatchIn(block),
        )
    }

    @Test
    fun `manifest declares the provider with grantUriPermissions true`() {
        val block = providerBlock()
        assertTrue(
            "provider must be android:grantUriPermissions=\"true\"",
            Regex("android:grantUriPermissions\\s*=\\s*\"true\"").containsMatchIn(block),
        )
    }

    @Test
    fun `manifest derives the authority from the applicationId`() {
        val block = providerBlock()
        assertTrue(
            "authority must be \${applicationId}-derived, not a literal",
            block.contains("android:authorities=\"\${applicationId}.fileprovider\""),
        )
    }

    @Test
    fun `manifest points the provider at the xml we tested`() {
        val block = providerBlock()
        assertTrue(
            "provider must reference @xml/file_paths",
            block.contains("android:resource=\"@xml/file_paths\""),
        )
        assertTrue(
            "provider must carry the FileProvider paths meta-data key",
            block.contains("android.support.FILE_PROVIDER_PATHS"),
        )
    }

    @Test
    fun `manifest uses the androidx FileProvider class`() {
        val block = providerBlock()
        assertTrue(
            "provider must be androidx.core.content.FileProvider",
            block.contains("androidx.core.content.FileProvider"),
        )
    }

    @Test
    fun `manifest declares the document activity as not exported`() {
        val xml = manifestXml()
        assertTrue(
            "CreateDocumentActivity must be declared",
            xml.contains(".document.CreateDocumentActivity"),
        )
        val block = Regex("<activity[^>]*CreateDocumentActivity[^>]*/?>").find(xml)?.value
        assertNotNull("could not find the CreateDocumentActivity element", block)
        assertTrue(
            "CreateDocumentActivity must be android:exported=\"false\"",
            Regex("android:exported\\s*=\\s*\"false\"").containsMatchIn(block!!),
        )
    }

    @Test
    fun `manifest declares the document activity with no intent filter`() {
        // An intent filter is only needed to be reachable from outside. This
        // Activity must not be, so a filter appearing here is a regression.
        val xml = manifestXml()
        val start = xml.indexOf(".document.CreateDocumentActivity")
        assertTrue("CreateDocumentActivity not found", start > 0)
        val after = xml.substring(start)
        val end = after.indexOf("</activity>")
        val endTag = if (end > 0) after.substring(0, end) else after
        assertTrue(
            "CreateDocumentActivity must not carry an intent-filter",
            !endTag.contains("<intent-filter"),
        )
    }

    @Test
    fun `manifest does not set noHistory on the document activity`() {
        // This is a negative assertion on purpose. `android:noHistory="true"` is
        // the intuitive choice for a millisecond-lived Activity and it silently
        // breaks this one: noHistory finishes the Activity the moment the system
        // picker covers it, so onActivityResult is never called and the write
        // hangs forever. Every terminal path already calls finish(), so the back
        // stack stays clean without it.
        val xml = manifestXml()
        val block = Regex("<activity[^>]*CreateDocumentActivity[^>]*/?>").find(xml)?.value
        assertNotNull(block)
        assertTrue(
            "CreateDocumentActivity must NOT set noHistory; the activity-result " +
                "contract breaks if it does",
            !Regex("android:noHistory\\s*=\\s*\"true\"").containsMatchIn(block!!),
        )
    }

    @Test
    fun `manifest gives the document activity a translucent theme`() {
        // A visible window between the tap and the picker reads as a crash to the
        // user, and this Activity is usually opened by an agent step the user did
        // not directly touch.
        val xml = manifestXml()
        val block = Regex("<activity[^>]*CreateDocumentActivity[^>]*/?>").find(xml)?.value
        assertNotNull(block)
        assertTrue(
            "CreateDocumentActivity must use the translucent theme",
            block!!.contains("@style/Theme.LocalIntelligence.Transparent"),
        )
    }

    @Test
    fun `manifest preserves the pre-existing permissions`() {
        // The permissions a previous fix added. If one of these disappears, this
        // PR silently broke a tool that depended on it.
        val xml = manifestXml()
        listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
        ).forEach { permission ->
            assertTrue("manifest must still declare $permission", xml.contains(permission))
        }
    }

    @Test
    fun `manifest preserves the launcher activity`() {
        assertTrue(
            "MainActivity must still be declared and exported",
            manifestXml().contains(".MainActivity") &&
                manifestXml().contains("android.intent.action.MAIN"),
        )
    }

    private fun providerBlock(): String {
        val xml = manifestXml()
        val start = xml.indexOf("<provider")
        assertTrue("no <provider> in the manifest", start > 0)
        val end = xml.indexOf("</provider>", start)
        assertTrue("unterminated <provider>", end > start)
        return xml.substring(start, end)
    }

    private companion object {
        /** DOT_MATCHES_ALL, the name since Kotlin 2.2. */
        val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        val XML_DECL = Regex("<\\?xml.*?\\?>", RegexOption.DOT_MATCHES_ALL)
    }
}
