package com.derpy.earmarks.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The strike store is the only thing standing between one 404 and permanent,
 * unrecoverable deletion of a track. Every test here is about refusing to
 * delete, or about not refusing forever.
 *
 * Real history behind it: four of Darryl's earmarks were swept off Blossom and
 * out of the published list on a single sync pass, on evidence that turned out
 * to be a bug rather than an absence (#38). The blobs were gone by the time
 * anyone looked. Nothing about orphan cleanup gets to be fast.
 */
class OrphanStrikeStoreTest {

    private lateinit var dir: File
    private var clock = 1_700_000_000_000L

    private fun store() = OrphanStrikeStore(dir) { clock }

    /** Far enough ahead that the next strike counts. */
    private fun anHourLater() {
        clock += 61L * 60L * 1000L
    }

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "orphan-strikes-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `one sync reporting an orphan is not enough to delete`() {
        // The whole point. A single pass sees every server 404 and must still
        // leave the earmark alone.
        assertFalse(store().record(TS))
    }

    @Test
    fun `two syncs an hour apart are still not enough`() {
        assertFalse(store().record(TS))
        anHourLater()
        assertFalse(store().record(TS))
    }

    @Test
    fun `the third strike an hour apart authorises the delete`() {
        assertFalse(store().record(TS))
        anHourLater()
        assertFalse(store().record(TS))
        anHourLater()
        assertTrue(store().record(TS))
    }

    @Test
    fun `strikes in quick succession count once`() {
        // Opening the app runs a sync. Someone who notices a broken track and
        // reopens the app three times must not thereby authorise its deletion —
        // that is one outage read three times, not three observations.
        val store = store()
        assertFalse(store.record(TS))
        repeat(20) {
            clock += 30_000L
            assertFalse("a burst of reopens must not reach the threshold", store.record(TS))
        }
        assertEquals(1, store.load()[TS]?.count)
    }

    @Test
    fun `an uncounted strike does not move the clock forward`() {
        // If a rejected strike refreshed lastSeen, a sync every 59 minutes would
        // keep pushing the deadline out and a real orphan would never be swept.
        val store = store()
        store.record(TS)

        clock += 59L * 60L * 1000L
        store.record(TS)          // too soon — must not count, must not refresh

        clock += 2L * 60L * 1000L // 61 minutes after the counted strike
        store.record(TS)

        assertEquals(
            "the hour is measured from the last counted strike",
            2,
            store.load()[TS]?.count
        )
    }

    @Test
    fun `a successful download wipes the evidence`() {
        val store = store()
        store.record(TS)
        anHourLater()
        store.record(TS)

        store.clear(TS)

        anHourLater()
        assertFalse("a recovered track starts from zero again", store.record(TS))
    }

    @Test
    fun `strikes survive a new instance`() {
        // Each sync builds a fresh EarmarkSyncer, and the process dies between
        // scheduled passes. Counts that lived in memory would never reach three.
        assertFalse(store().record(TS))
        anHourLater()
        assertFalse(store().record(TS))
        anHourLater()
        assertTrue(store().record(TS))
    }

    @Test
    fun `an entry already at the threshold stays there`() {
        // cleanupOrphans deletes blobs then republishes; if the publish fails,
        // the next run has to be able to finish the job immediately.
        val store = store()
        store.record(TS)
        anHourLater()
        store.record(TS)
        anHourLater()
        assertTrue(store.record(TS))
        clock += 60_000L
        assertTrue("a failed prune must be retryable at once", store.record(TS))
    }

    @Test
    fun `counts are tracked per earmark`() {
        val store = store()
        store.record(TS)
        anHourLater()
        store.record(TS)
        anHourLater()

        assertFalse("an unrelated track is unaffected", store.record(OTHER_TS))
        assertTrue(store.record(TS))
    }

    @Test
    fun `retainOnly drops earmarks that left the list`() {
        val store = store()
        store.record(TS)
        store.record(OTHER_TS)

        store.retainOnly(listOf(OTHER_TS))

        assertEquals(setOf(OTHER_TS), store.load().keys)
    }

    @Test
    fun `a corrupt file is discarded rather than fatal`() {
        File(dir, "orphan_strikes.json").writeText("{not json")
        val store = store()
        assertTrue(store.load().isEmpty())
        assertFalse("losing counts delays a delete, never causes one", store.record(TS))
    }

    private companion object {
        const val TS = 1_690_000_000L
        const val OTHER_TS = 1_690_000_001L
    }
}
