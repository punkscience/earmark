package com.derpy.earmarks.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for the frozen-playlist bug: an active player session may
 * keep its timeline only when it already holds exactly the incoming tracks.
 * A partial timeline (2 of 12 downloaded, the rest arriving later) must be
 * replaced, or the player loops those first tracks forever.
 */
class SameTrackSetTest {

    @Test
    fun sameTracksInDifferentOrderArePreserved() {
        assertTrue(sameTrackSet(listOf("30", "10", "20"), listOf(10L, 20L, 30L)))
    }

    @Test
    fun partialTimelineIsNotPreserved() {
        // The Lisette regression: 2 tracks reached the player before the other
        // 10 finished downloading. The grown playlist must win.
        assertFalse(sameTrackSet(listOf("10", "20"), listOf(10L, 20L, 30L, 40L)))
    }

    @Test
    fun shrunkenPlaylistIsNotPreserved() {
        assertFalse(sameTrackSet(listOf("10", "20", "30"), listOf(10L, 20L)))
    }

    @Test
    fun disjointPlaylistsAreNotPreserved() {
        assertFalse(sameTrackSet(listOf("10"), listOf(99L)))
    }

    @Test
    fun emptyTimelineNeverMatchesTracks() {
        assertFalse(sameTrackSet(emptyList(), listOf(10L)))
    }
}
