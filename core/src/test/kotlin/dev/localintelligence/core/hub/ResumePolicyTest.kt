package dev.localintelligence.core.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * Resume policy, SHA verification, and the `.part` -> final rename.
 *
 * These are filesystem tests, which is why they live in the JVM suite rather
 * than needing a device: the `.part` discipline is plain `java.io` and its
 * correctness is the single most important property in this feature.
 */
class ResumePolicyTest {

    @Test
    fun `no local bytes means start from zero`() {
        assertEquals(ResumeDecision.Restart, ResumePolicy.decide(0, 1_000_000))
    }

    @Test
    fun `a partial prefix is resumed`() {
        val decision = ResumePolicy.decide(500_000, 1_000_000)
        assertEquals(ResumeDecision.Resume(500_000), decision)
    }

    @Test
    fun `a local file longer than the remote is discarded not appended to`() {
        // Appending here would produce the right length with a duplicated chunk.
        val decision = ResumePolicy.decide(1_200_000, 1_000_000)
        assertTrue("got $decision", decision is ResumeDecision.Discard)
    }

    @Test
    fun `a complete-length local file is discarded rather than promoted blind`() {
        // Length alone is not evidence: a resumed file of exactly the right
        // length may be the wrong revision. The checksum is the arbiter, and
        // it needs a fresh transfer to be meaningful.
        val decision = ResumePolicy.decide(1_000_000, 1_000_000)
        assertTrue(decision is ResumeDecision.Discard)
    }

    @Test
    fun `an unknown server total resumes on the local length alone`() {
        val decision = ResumePolicy.decide(500_000, -1)
        assertEquals(ResumeDecision.Resume(500_000), decision)
    }

    @Test
    fun `a negative local length is discarded`() {
        assertTrue(ResumePolicy.decide(-1, 100) is ResumeDecision.Discard)
    }
}

class PartialFileTest {

    private lateinit var dir: File

    private fun fixture(name: String = "model"): Triple<PartialFile, File, File> {
        val part = File(dir, "$name.gguf.part")
        val final = File(dir, "$name.gguf")
        return Triple(PartialFile(part, final), part, final)
    }

    @org.junit.Before
    fun setUp() {
        dir = Files.createTempDirectory("li-hub-test").toFile()
    }

    @org.junit.After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a cancelled download leaves NO file where a valid one is expected`() {
        val (partial, _, final) = fixture()
        partial.openAppend(0).use { it.write(ByteArray(1000)) }

        // This is the invariant the whole class exists for. The loader looks at
        // `final`; a partial file there memory-maps half a GGUF and dies in
        // native code with no Java stack and no file name.
        assertFalse("a partial file must never be at the final path", final.exists())
        assertTrue(partial.partFile.exists())
    }

    @Test
    fun `promote moves a complete part into place`() {
        val (partial, _, final) = fixture()
        val bytes = ByteArray(5000) { it.toByte() }
        partial.openAppend(0).use { it.write(bytes) }

        val problem = partial.promote(expectedBytes = 5000)
        assertNull(problem)
        assertTrue(final.exists())
        assertEquals(5000, final.length())
        assertFalse(partial.partFile.exists())
        assertTrue(java.util.Arrays.equals(bytes, final.readBytes()))
    }

    @Test
    fun `promote refuses a short file and leaves it in place for inspection`() {
        val (partial, _, final) = fixture()
        partial.openAppend(0).use { it.write(ByteArray(100)) }
        val problem = partial.promote(expectedBytes = 5000)
        assertNotNullMessage(problem)
        assertTrue(problem!!.contains("expected 5000"))
        assertFalse("nothing may appear at the final path", final.exists())
    }

    @Test
    fun `discard removes the part file`() {
        val (partial, _, final) = fixture()
        partial.openAppend(0).use { it.write(ByteArray(100)) }
        partial.discard()
        assertFalse(partial.partFile.exists())
        assertFalse(final.exists())
    }

    @Test
    fun `opening for append does not truncate an existing part`() {
        val (partial, _, _) = fixture()
        partial.openAppend(0).use { it.write(ByteArray(1000) { 7 }) }
        // Resume: seek to 1000 and keep the original 1000 bytes.
        partial.openAppend(1000).use { it.write(ByteArray(500) { 9 }) }
        assertEquals(1500, partial.localBytes())
        val all = partial.partFile.readBytes()
        assertTrue(java.util.Arrays.equals(ByteArray(1000) { 7 }, all.copyOfRange(0, 1000)))
        assertTrue(java.util.Arrays.equals(ByteArray(500) { 9 }, all.copyOfRange(1000, 1500)))
    }

    @Test
    fun `isComplete requires both existence and the right length`() {
        val (partial, _, final) = fixture()
        assertFalse(partial.isComplete(100))
        partial.openAppend(0).use { it.write(ByteArray(100)) }
        assertFalse("not promoted yet", partial.isComplete(100))
        partial.promote(100)
        assertTrue(partial.isComplete(100))
        assertFalse(partial.isComplete(101))
    }

    @Test
    fun `promote replaces an existing model file`() {
        val (partial, _, final) = fixture()
        final.writeBytes(ByteArray(10))
        partial.openAppend(0).use { it.write(ByteArray(100)) }
        assertNull(partial.promote(100))
        assertEquals(100, final.length())
    }

    @Test
    fun `promote on a missing part file fails cleanly`() {
        val (partial, _, _) = fixture()
        assertNotNullMessage(partial.promote(100))
    }

    private fun assertNotNullMessage(value: String?) {
        assertTrue("expected a failure message, got null", value != null)
    }
}

class Sha256Test {

    @Test
    fun `matches the known digest of the empty input`() {
        val f = File.createTempFile("sha", ".bin")
        try {
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.ofFile(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `matches the known digest of abc`() {
        val f = File.createTempFile("sha", ".bin")
        try {
            f.writeBytes("abc".toByteArray())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.ofFile(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `digests a file larger than the internal buffer`() {
        val f = File.createTempFile("sha", ".bin")
        try {
            // 3 buffers plus a remainder, so the read loop actually iterates.
            val payload = ByteArray(8 * 1024 * 3 + 137) { (it % 251).toByte() }
            f.writeBytes(payload)
            val digest = Sha256.ofFile(f)!!
            // Recomputed independently through java.security directly.
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(payload)
            assertEquals(Sha256.toHex(expected), digest)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `a truncated file has a different digest than a complete one`() {
        val full = File.createTempFile("sha", ".bin")
        val cut = File.createTempFile("sha", ".bin")
        try {
            val payload = ByteArray(20_000) { 1 }
            full.writeBytes(payload)
            cut.writeBytes(payload.copyOfRange(0, 19_000))
            assertFalse(Sha256.matches(Sha256.ofFile(full)!!, Sha256.ofFile(cut)))
        } finally {
            full.delete()
            cut.delete()
        }
    }

    @Test
    fun `a missing file yields null, which must not read as a pass`() {
        assertNull(Sha256.ofFile(File("/definitely/not/here.gguf")))
        assertFalse(Sha256.matches("a".repeat(64), null))
    }

    @Test
    fun `a case difference in the expected digest does not fail a good file`() {
        val digest = "2222222222222222222222222222222222222222222222222222222222222222"
        // A false mismatch deletes a 2 GB file that was fine. The asymmetry
        // matters more than strictness.
        assertTrue(Sha256.matches(digest.uppercase(), digest))
        assertTrue(Sha256.matches(" $digest ", digest))
    }

    @Test
    fun `a different digest does not match`() {
        assertFalse(Sha256.matches("2".repeat(64), "3".repeat(64)))
    }

    @Test
    fun `a wrong-length expected digest does not match`() {
        assertFalse(Sha256.matches("abc", "a".repeat(64)))
    }
}

class HubResponseTest {

    private fun response(status: Int, headers: Map<String, String>, body: ByteArray? = null) =
        HubResponse(status, headers, body?.inputStream() ?: ByteArrayInputStream(ByteArray(0)), body?.size?.toLong() ?: -1)

    @Test
    fun `reads the start offset from content-range`() {
        val r = response(206, mapOf("Content-Range" to "bytes 100-199/1000"))
        assertEquals(100L, r.contentRangeStart())
        assertEquals(1000L, r.contentRangeTotal())
    }

    @Test
    fun `a 200 with no content-range starts at zero, which is why appending is wrong`() {
        // The server ignored the Range. Appending a 200 body to a partial file
        // produces the right length and completely wrong content.
        val r = response(200, mapOf("Content-Length" to "1000"))
        assertNull(r.contentRangeStart())
        assertFalse(r.isPartial)
    }

    @Test
    fun `handles a wildcard total`() {
        val r = response(206, mapOf("Content-Range" to "bytes 0-99/*"))
        assertEquals(0L, r.contentRangeStart())
        assertEquals(-1L, r.contentRangeTotal())
    }

    @Test
    fun `header lookup is case-insensitive`() {
        val r = response(206, mapOf("CONTENT-RANGE" to "bytes 5-9/10"))
        assertEquals(5L, r.contentRangeStart())
    }
}
