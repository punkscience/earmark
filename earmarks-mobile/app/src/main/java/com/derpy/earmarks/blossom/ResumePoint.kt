package com.derpy.earmarks.blossom

import com.derpy.earmarks.data.Chunk

/**
 * Per-chunk framing overhead in a Blossom blob: a 12-byte AES-GCM nonce prefix
 * plus a 16-byte authentication tag.
 *
 * `Chunk.size` is the size of the *encrypted* blob — `earmark-core/blossom.go`
 * records `len(encrypted)` where `encrypted = gcm.Seal(nonce, nonce, data, nil)`
 * — so the plaintext a chunk contributes to the assembled file is
 * `size - CHUNK_OVERHEAD`.
 */
internal const val CHUNK_OVERHEAD = 12 + 16

/**
 * Where to pick up a partially downloaded earmark.
 *
 * @property nextChunk index into the *index-sorted* chunk list of the first
 *   chunk still needed. Equals the chunk count when the file is already whole.
 * @property truncateTo the length the partial file must be cut back to before
 *   appending. Always a whole-chunk boundary.
 */
internal data class ResumePoint(val nextChunk: Int, val truncateTo: Long)

/**
 * Derives the resume point from the length of the partial file alone.
 *
 * There is deliberately no on-disk progress record. Chunk plaintext lengths are
 * already in the manifest, so the byte count on disk is the only state there
 * is, and single-sourced state cannot disagree with itself. A half-written tail
 * — the only thing a process kill mid-`write` can leave behind — lands between
 * two boundaries and is truncated back to the last complete one, costing the
 * current chunk rather than the whole track.
 *
 * [sortedChunks] must be sorted by index and must not contain a chunk smaller
 * than [CHUNK_OVERHEAD]; callers validate that before getting here.
 */
internal fun resumePoint(sortedChunks: List<Chunk>, partLength: Long): ResumePoint {
    var consumed = 0L
    for ((i, chunk) in sortedChunks.withIndex()) {
        val boundary = consumed + plainLength(chunk)
        if (partLength < boundary) return ResumePoint(i, consumed)
        consumed = boundary
    }
    return ResumePoint(sortedChunks.size, consumed)
}

/** Plaintext bytes this chunk contributes to the assembled file. */
internal fun plainLength(chunk: Chunk): Long = (chunk.size - CHUNK_OVERHEAD).toLong()

/** Total length the assembled file must have once every chunk has landed. */
internal fun expectedPlainLength(chunks: List<Chunk>): Long = chunks.sumOf { plainLength(it) }
