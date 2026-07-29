package com.derpy.earmarks.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The playlist now grows underneath the listener: background downloads land one
 * at a time and each completed sync rebuilds the playlist to include them.
 * That only works if the rebuild hands playback back at the same track and
 * position — otherwise every track that finishes downloading would yank the
 * listener somewhere else, which is worse than the problem being solved.
 */
class ResumeIndexTest {

    @Test
    fun `finds the loaded track in a playlist that grew`() {
        // Two tracks were playable; ten more finished downloading.
        val grown = listOf(300L, 100L, 500L, 200L, 400L)
        assertEquals(3, resumeIndex("200", grown))
    }

    @Test
    fun `reports absent when the loaded track is gone`() {
        // Deleted, expired off the list, or pruned as an orphan. The caller
        // starts the new playlist from the top rather than guessing.
        assertEquals(-1, resumeIndex("999", listOf(100L, 200L)))
    }

    @Test
    fun `reports absent when nothing is loaded`() {
        assertEquals(-1, resumeIndex(null, listOf(100L, 200L)))
    }

    @Test
    fun `reports absent for an empty playlist`() {
        assertEquals(-1, resumeIndex("100", emptyList()))
    }

    @Test
    fun `matches on exact id not prefix`() {
        // Timestamps share leading digits — 1783616284 and 1783616999 differ
        // only near the end — so a prefix match would hand playback to the
        // wrong track.
        assertEquals(1, resumeIndex("1783616999", listOf(1_783_616_284L, 1_783_616_999L)))
        assertEquals(-1, resumeIndex("178361", listOf(1_783_616_284L, 1_783_616_999L)))
    }
}
