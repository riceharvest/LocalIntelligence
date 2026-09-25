package dev.localintelligence.app.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The tests that matter most in this PR.
 *
 * `ProviderPathScope` is the JVM-testable model of what the FileProvider will do
 * with a path. A FileProvider misconfiguration is not a crash — it is another app
 * reading a file the user never chose to share — and it is invisible to review
 * unless someone writes a test that walks the attack surface. This file is that
 * test.
 *
 * Every case here runs on the JVM against a real temporary directory. No
 * emulator, no Robolectric, no instrumentation. `./gradlew :app:test` proves the
 * traversal defence in about a second.
 */
class ProviderPathScopeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        File(filesDir, ProviderPathScope.ROOT_RELATIVE).mkdirs()
    }

    private fun resolve(path: String?): ProviderPathScope.Resolution =
        ProviderPathScope.resolve(filesDir, path)

    private fun assertRejected(path: String?, expected: ProviderPathScope.Rejection) {
        val result = resolve(path)
        assertTrue(
            "expected $expected for <$path> but got $result",
            result is ProviderPathScope.Resolution.Rejected &&
                result.rejection == expected,
        )
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `accepts a file inside the declared root`() {
        val result = resolve("out/note.txt")
        assertTrue("expected Allowed, got $result", result is ProviderPathScope.Resolution.Allowed)
        val allowed = result as ProviderPathScope.Resolution.Allowed
        assertEquals("out/note.txt", allowed.uriPath)
        assertEquals(
            File(filesDir, "out/note.txt").canonicalPath,
            allowed.file.canonicalPath,
        )
    }

    @Test
    fun `accepts a file in a nested directory under the root`() {
        assertTrue(resolve("out/2026/09/note.txt") is ProviderPathScope.Resolution.Allowed)
    }

    @Test
    fun `accepts a percent-encoded space in a filename`() {
        // A legitimate name, not an attack: decoding must not break normal files.
        assertTrue(resolve("out/my%20note.txt") is ProviderPathScope.Resolution.Allowed)
    }

    @Test
    fun `accepted file is inside app-private storage`() {
        val allowed = resolve("out/note.txt") as ProviderPathScope.Resolution.Allowed
        val root = filesDir.canonicalFile.path + File.separator
        assertTrue(
            "resolved file escaped the files dir: ${allowed.file}",
            allowed.file.canonicalPath.startsWith(root),
        )
    }

    // ----------------------------------------------------------------- traversal

    @Test
    fun `rejects classic dot-dot traversal`() {
        assertRejected("out/../../../etc/passwd", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects dot-dot traversal that starts at the root segment`() {
        assertRejected("../etc/passwd", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects percent-encoded dot-dot`() {
        assertRejected("out/%2e%2e/%2e%2e/etc/passwd", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects uppercase percent-encoded dot-dot`() {
        assertRejected("out/%2E%2E/%2E%2E/etc/passwd", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects mixed-case percent-encoded dot-dot`() {
        assertRejected("out/%2e%2E/secret", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects double percent-encoded dot-dot`() {
        assertRejected("out/%252e%252e/%252e%252e/etc/passwd", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects triple percent-encoded dot-dot`() {
        // Three layers still decode to a plain `..` inside the budget, so the
        // per-round re-check catches it as traversal. Being caught one layer
        // earlier than the decode limit is the better outcome: the specific
        // reason is reported rather than a generic "too encoded".
        assertRejected("out/%25252e%25252e/secret", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects encoding deeper than the decode budget`() {
        // Four layers exceeds MAX_DECODE_ROUNDS, so the string never stabilises
        // and is refused as a payload rather than trusted as a path. This is the
        // bounded-decode-loop guarantee: a self-referential encoding cannot spin
        // the resolver forever.
        assertRejected("out/%2525252e%2525252e/secret", ProviderPathScope.Rejection.OVER_ENCODED)
    }

    @Test
    fun `rejects percent-encoded slash used to build a traversal`() {
        // %2f is a separator. Decoded it becomes ../../etc/passwd.
        assertRejected("out%2f..%2f..%2fetc%2fpasswd", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects backslash traversal`() {
        assertRejected("out\\..\\..\\etc\\passwd", ProviderPathScope.Rejection.BACKSLASH)
    }

    @Test
    fun `rejects percent-encoded backslash`() {
        assertRejected("out/%5c%5cetc%5cpasswd", ProviderPathScope.Rejection.BACKSLASH)
    }

    @Test
    fun `rejects an absolute path`() {
        assertRejected("/etc/passwd", ProviderPathScope.Rejection.ABSOLUTE)
    }

    @Test
    fun `rejects an absolute path that looks in scope`() {
        assertRejected("/data/data/dev.localintelligence.app/files/out/note.txt", ProviderPathScope.Rejection.ABSOLUTE)
    }

    @Test
    fun `rejects a file scheme prefix`() {
        assertRejected("file:///etc/passwd", ProviderPathScope.Rejection.ABSOLUTE)
    }

    @Test
    fun `rejects a windows drive letter`() {
        assertRejected("C:/Windows/system32/config", ProviderPathScope.Rejection.ABSOLUTE)
    }

    @Test
    fun `rejects a nul byte`() {
        // A NUL truncates the path in every C-based consumer downstream. Both the
        // raw form and the encoded form are refused.
        assertRejected("out/note.txt\u0000.png", ProviderPathScope.Rejection.CONTROL_CHARACTER)
        assertRejected("out/note.txt%00.png", ProviderPathScope.Rejection.OVER_ENCODED)
    }

    @Test
    fun `rejects a malformed percent escape`() {
        assertRejected("out/%zz", ProviderPathScope.Rejection.OVER_ENCODED)
        assertRejected("out/%2", ProviderPathScope.Rejection.OVER_ENCODED)
    }

    @Test
    fun `rejects a dot-dot hidden behind a redundant separator`() {
        assertRejected("out//..//..//etc", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects a dot-dot with trailing content`() {
        assertRejected("out/../secret", ProviderPathScope.Rejection.TRAVERSAL)
    }

    @Test
    fun `rejects a path that only escapes via the files dir itself`() {
        assertRejected("out/../../..", ProviderPathScope.Rejection.TRAVERSAL)
    }

    // --------------------------------------------------------------------- scope

    @Test
    fun `rejects a path outside the declared root`() {
        assertRejected("databases/session.db", ProviderPathScope.Rejection.OUT_OF_SCOPE)
        assertRejected("shared_prefs/agent.xml", ProviderPathScope.Rejection.OUT_OF_SCOPE)
        assertRejected("no_backup/trace.json", ProviderPathScope.Rejection.OUT_OF_SCOPE)
    }

    @Test
    fun `rejects a sibling directory that merely shares a prefix`() {
        // "out-evil/" passes a naive startsWith("out") check. It must not pass here.
        assertRejected("out-evil/secret.txt", ProviderPathScope.Rejection.OUT_OF_SCOPE)
    }

    @Test
    fun `rejects a path that is just the root`() {
        assertRejected("out/", ProviderPathScope.Rejection.OUT_OF_SCOPE)
    }

    @Test
    fun `rejects a blank path`() {
        assertRejected("", ProviderPathScope.Rejection.BLANK)
        assertRejected("   ", ProviderPathScope.Rejection.BLANK)
        assertRejected(null, ProviderPathScope.Rejection.BLANK)
    }

    @Test
    fun `rejects an over-long path`() {
        val long = "out/" + "a".repeat(ProviderPathScope.MAX_PATH_CHARS)
        assertRejected(long, ProviderPathScope.Rejection.TOO_LONG)
    }

    // ------------------------------------------------------------------- symlink

    @Test
    fun `rejects a symlink inside the root that points outside it`() {
        // The one attack that string inspection alone cannot see. A symlink is a
        // perfectly legal path with no ".." in it that resolves outside the root,
        // so the canonical containment check is the only thing that catches it.
        val secret = temp.newFile("secret.txt")
        secret.writeText("private")
        val link = File(filesDir, "out/leak.txt")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        }
        org.junit.Assume.assumeTrue(
            "filesystem does not support symlinks",
            created.isSuccess,
        )

        assertRejected("out/leak.txt", ProviderPathScope.Rejection.ESCAPES_ROOT)
    }

    // ------------------------------------------------------------------ authority

    @Test
    fun `authority is derived from the application id`() {
        assertEquals(
            "dev.localintelligence.app.fileprovider",
            ProviderPathScope.authority("dev.localintelligence.app"),
        )
    }

    @Test
    fun `authority differs per application id`() {
        // The collision test. Two apps declaring the same authority cannot both be
        // installed, so a literal authority is an install-time failure waiting to
        // happen and a cross-app takeover waiting to be attempted.
        val debug = ProviderPathScope.authority("dev.localintelligence.app.debug")
        val release = ProviderPathScope.authority("dev.localintelligence.app")
        assertTrue("authorities must not collide", debug != release)
    }

    @Test
    fun `authority does not depend on a hardcoded package name`() {
        val other = ProviderPathScope.authority("com.someone.else")
        assertEquals("com.someone.else.fileprovider", other)
    }

    // ------------------------------------------------------- returned-URI validation

    @Test
    fun `accepts a normal document uri from the picker`() {
        assertEquals(
            null,
            ProviderPathScope.validateDocumentUri(
                "content://com.android.providers.downloads.documents/document/msf%3A42",
                "dev.localintelligence.app",
            ),
        )
    }

    @Test
    fun `rejects a null or blank picker result`() {
        val appId = "dev.localintelligence.app"
        assertEquals(
            ProviderPathScope.Rejection.BLANK,
            ProviderPathScope.validateDocumentUri(null, appId),
        )
        assertEquals(
            ProviderPathScope.Rejection.BLANK,
            ProviderPathScope.validateDocumentUri("", appId),
        )
    }

    @Test
    fun `rejects a garbage picker result`() {
        val appId = "dev.localintelligence.app"
        assertEquals(
            ProviderPathScope.Rejection.NOT_CONTENT_URI,
            ProviderPathScope.validateDocumentUri("not a uri at all", appId),
        )
        assertEquals(
            ProviderPathScope.Rejection.NOT_CONTENT_URI,
            ProviderPathScope.validateDocumentUri("content://", appId),
        )
    }

    @Test
    fun `rejects a file uri returned by the picker`() {
        // A picker that hands back file:// has chosen a filesystem path for us.
        // Accepting it would put us one line from FileUriExposedException.
        assertEquals(
            ProviderPathScope.Rejection.NOT_CONTENT_URI,
            ProviderPathScope.validateDocumentUri("file:///data/data/dev.localintelligence.app/files/out/x", "dev.localintelligence.app"),
        )
    }

    @Test
    fun `rejects our own authority handed back by the picker`() {
        // Confused deputy: a foreign process naming a file in our own private
        // scope turns "write what the user picked" into "write what that app says".
        assertEquals(
            ProviderPathScope.Rejection.OWN_AUTHORITY,
            ProviderPathScope.validateDocumentUri(
                "content://dev.localintelligence.app.fileprovider/out/anything.txt",
                "dev.localintelligence.app",
            ),
        )
    }

    @Test
    fun `rejects a picker uri carrying a traversal in its path`() {
        val appId = "dev.localintelligence.app"
        assertEquals(
            ProviderPathScope.Rejection.TRAVERSAL,
            ProviderPathScope.validateDocumentUri("content://some.provider/../../etc/passwd", appId),
        )
        assertEquals(
            ProviderPathScope.Rejection.BACKSLASH,
            ProviderPathScope.validateDocumentUri("content://some.provider/a\\b", appId),
        )
    }

    @Test
    fun `rejects an over-long picker uri`() {
        val long = "content://some.provider/" + "a".repeat(ProviderPathScope.MAX_URI_CHARS)
        assertEquals(
            ProviderPathScope.Rejection.TOO_LONG,
            ProviderPathScope.validateDocumentUri(long, "dev.localintelligence.app"),
        )
    }

    @Test
    fun `rejection reasons are safe to show a model`() {
        // A rejection carries the rule that fired and nothing about the value, so
        // a hostile path can never reach an agent observation through it.
        val rejected = resolve("out/../../../etc/passwd") as ProviderPathScope.Resolution.Rejected
        assertNotNull(rejected.rejection)
        assertTrue(
            "rejection must not embed the path",
            rejected.rejection.name.none { it == '/' || it == '%' },
        )
    }
}
