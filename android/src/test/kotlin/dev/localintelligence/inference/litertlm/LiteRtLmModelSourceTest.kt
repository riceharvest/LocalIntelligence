package dev.localintelligence.inference.litertlm

import dev.localintelligence.core.model.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [LiteRtLmModelSource] decides what is loadable, and a wrong answer here either
 * crashes the app in native code or refuses a model that would have worked.
 *
 * Every case below is a real user outcome: a deleted file, a GGUF picked by
 * mistake, a cloud-drive URI copied from another app, a path that no longer
 * resolves after a restore.
 */
class LiteRtLmModelSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var source: LiteRtLmModelSource

    @Before
    fun setUp() {
        root = temp.newFolder("models")
        source = LiteRtLmModelSource(root)
    }

    private fun spec(id: String) = ModelSpec(id = id, displayName = "test")

    private fun bundle(name: String = "gemma-3n.litertlm"): File =
        File(root, name).apply { writeText("bundle") }

    // ------------------------------------------------------------- the happy path

    @Test
    fun `an absolute path to a bundle resolves`() {
        val model = bundle()
        assertEquals(model.absolutePath, source.resolve(spec(model.absolutePath)).absolutePath)
    }

    @Test
    fun `a file uri resolves`() {
        val model = bundle()
        val uri = "file://${model.absolutePath}"
        assertEquals(model.absolutePath, source.resolve(spec(uri)).absolutePath)
    }

    @Test
    fun `a file uri with localhost resolves`() {
        val model = bundle()
        val uri = "file://localhost${model.absolutePath}"
        assertEquals(model.absolutePath, source.resolve(spec(uri)).absolutePath)
    }

    @Test
    fun `a percent-encoded file uri decodes`() {
        val dir = File(root, "a model dir")
        dir.mkdirs()
        val model = File(dir, "m.litertlm").apply { writeText("x") }
        val uri = "file://${model.absolutePath.replace(" ", "%20")}"
        assertEquals(model.absolutePath, source.resolve(spec(uri)).absolutePath)
    }

    @Test
    fun `a directory of model parts resolves`() {
        val dir = File(root, "gemma-3n").apply { mkdirs() }
        File(dir, "model.task").writeText("x")
        File(dir, "tokenizer.model").writeText("x")
        assertEquals(dir.absolutePath, source.resolve(spec(dir.absolutePath)).absolutePath)
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `a blank id is refused`() {
        val thrown = caught { source.resolve(spec("   ")) }
        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        assertTrue(thrown!!.message!!.contains("blank"))
    }

    @Test
    fun `a missing file is refused and named`() {
        val gone = File(root, "ghost.litertlm")
        val thrown = caught { source.resolve(spec(gone.absolutePath)) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        assertTrue(
            "the message must name the path: ${thrown?.message}",
            thrown!!.message!!.contains("ghost.litertlm"),
        )
        assertTrue(thrown.message!!.contains("does not exist"))
    }

    @Test
    fun `a relative path is refused with the reason explained`() {
        val thrown = caught { source.resolve(spec("models/relative.litertlm")) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        // The reason matters: a relative path looks fine until the engine opens
        // it from an unexpected working directory.
        assertTrue(thrown!!.message!!.contains("absolute path"))
    }

    @Test
    fun `a content uri is refused because LiteRT-LM cannot use a SAF descriptor`() {
        val uri = "content://com.android.providers.downloads.documents/document/42"
        val thrown = caught { source.resolve(spec(uri)) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        // This is the single most important refusal in the class: it is what a
        // user who picked a model with the system file picker will hit, and the
        // message has to say why copying it elsewhere is the answer.
        assertTrue(thrown!!.message!!.contains("content provider descriptor"))
    }

    @Test
    fun `a remote file uri is refused because LiteRT-LM reads local files only`() {
        val thrown = caught { source.resolve(spec("file://fileserver/share/model.litertlm")) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        assertTrue(thrown!!.message!!.contains("fileserver"))
    }

    @Test
    fun `an empty model directory is refused before the engine can crash on it`() {
        val dir = File(root, "empty").apply { mkdirs() }
        val thrown = caught { source.resolve(spec(dir.absolutePath)) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        assertTrue(thrown!!.message!!.contains("empty"))
    }

    @Test
    fun `a directory holding only a GGUF is refused, pointing at the other backend`() {
        val dir = File(root, "gguf-only").apply { mkdirs() }
        File(dir, "Llama-3.2-3B-Q4_K_M.gguf").writeText("GGUF")

        val thrown = caught { source.resolve(spec(dir.absolutePath)) }

        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
        // A GGUF is a perfectly good model -- for the other backend. Saying so
        // turns a dead end into a one-tap fix.
        assertTrue(thrown!!.message!!.contains("llamacpp"))
    }

    @Test
    fun `a single GGUF file is refused`() {
        val gguf = File(root, "model.gguf").apply { writeText("GGUF") }
        val thrown = caught { source.resolve(spec(gguf.absolutePath)) }

        // A bare file is passed through: LiteRT-LM is the one that knows whether
        // it can open it, and refusing here would block a valid bundle format.
        assertEquals(null, thrown)
        assertEquals(gguf.absolutePath, source.resolve(spec(gguf.absolutePath)).absolutePath)
    }

    @Test
    fun `a file uri with no path component is refused`() {
        val thrown = caught { source.resolve(spec("file://localhost")) }
        assertTrue("got ${thrown?.message}", thrown is LiteRtLmModelException)
    }

    // ------------------------------------------------------------ model metadata

    @Test
    fun `the model root is exposed for the downloader`() {
        assertEquals(root, source.modelRoot)
    }
}
