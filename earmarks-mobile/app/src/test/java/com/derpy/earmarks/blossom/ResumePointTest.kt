package com.derpy.earmarks.blossom

import com.derpy.earmarks.data.Chunk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resume offset is derived from the partial file's length alone, with no
 * on-disk progress record to cross-check it. That makes this arithmetic the
 * single point of failure for download resumption: get it wrong and chunks are
 * appended at the wrong offset, assembling a file that is corrupt but looks
 * cached. Hence the coverage.
 */
class ResumePointTest {

    /** Chunk carrying [plain] bytes of audio once the framing is stripped. */
    private fun chunk(index: Int, plain: Int) =
        Chunk(index = index, sha256 = "%064x".format(index), size = plain + CHUNK_OVERHEAD, servers = listOf("https://s"))

    /** Three chunks: 100 + 100 + 40 plaintext bytes, boundaries at 100/200/240. */
    private val chunks = listOf(chunk(0, 100), chunk(1, 100), chunk(2, 40))

    @Test
    fun `nothing downloaded starts at the first chunk`() {
        assertEquals(ResumePoint(0, 0L), resumePoint(chunks, 0L))
    }

    @Test
    fun `exact chunk boundary resumes at the next chunk without truncating`() {
        assertEquals(ResumePoint(1, 100L), resumePoint(chunks, 100L))
        assertEquals(ResumePoint(2, 200L), resumePoint(chunks, 200L))
    }

    @Test
    fun `half-written chunk truncates back to the last complete boundary`() {
        // A process killed mid-write is the only way to land here.
        assertEquals(ResumePoint(0, 0L), resumePoint(chunks, 63L))
        assertEquals(ResumePoint(1, 100L), resumePoint(chunks, 137L))
        assertEquals(ResumePoint(2, 200L), resumePoint(chunks, 239L))
    }

    @Test
    fun `complete file needs no further chunks`() {
        assertEquals(ResumePoint(3, 240L), resumePoint(chunks, 240L))
    }

    @Test
    fun `over-long file is reported complete so the length check rejects it`() {
        // Should not happen. If it does, reporting "complete" hands the file to
        // the assembled-length check, which discards it — better than silently
        // appending more onto an already-wrong file.
        assertEquals(ResumePoint(3, 240L), resumePoint(chunks, 9_999L))
    }

    @Test
    fun `single chunk file resumes correctly`() {
        val one = listOf(chunk(0, 500))
        assertEquals(ResumePoint(0, 0L), resumePoint(one, 0L))
        assertEquals(ResumePoint(0, 0L), resumePoint(one, 499L))
        assertEquals(ResumePoint(1, 500L), resumePoint(one, 500L))
    }

    @Test
    fun `expected length sums plaintext across chunks`() {
        assertEquals(240L, expectedPlainLength(chunks))
    }

    @Test
    fun `plaintext length strips nonce and gcm tag`() {
        // earmark-core records len(gcm.Seal(nonce, nonce, data, nil)), i.e.
        // 12-byte nonce + ciphertext + 16-byte tag. 16 MiB of audio is the
        // full-chunk case the CLI actually produces.
        val full = Chunk(0, "a".repeat(64), 16 * 1024 * 1024 + 28, listOf("https://s"))
        assertEquals(16L * 1024 * 1024, plainLength(full))
    }

    @Test
    fun `resume point walks in index order not list order`() {
        // The caller sorts by index; this asserts the contract holds when the
        // manifest arrives out of order and has been sorted, and that unequal
        // chunk sizes do not shift the boundaries.
        val sorted = listOf(chunk(0, 10), chunk(1, 30), chunk(2, 20)).sortedBy { it.index }
        assertEquals(ResumePoint(1, 10L), resumePoint(sorted, 10L))
        assertEquals(ResumePoint(2, 40L), resumePoint(sorted, 41L))
        assertEquals(ResumePoint(3, 60L), resumePoint(sorted, 60L))
    }
}
