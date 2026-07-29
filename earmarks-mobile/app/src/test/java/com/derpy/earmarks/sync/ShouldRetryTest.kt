package com.derpy.earmarks.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry decision is the worker's whole failure policy. Getting it wrong in
 * either direction is costly: never retrying strands a track until the next
 * scheduled pass hours later, and always retrying burns the backoff schedule on
 * fetches that can never succeed.
 */
class ShouldRetryTest {

    private fun synced(unavailable: Int) =
        EarmarkSyncer.Outcome.Synced(earmarks = emptyList(), downloaded = 0, unavailable = unavailable)

    @Test
    fun `transient failures earn a retry`() {
        assertTrue(shouldRetry(synced(unavailable = 1)))
    }

    @Test
    fun `a clean run does not`() {
        assertTrue(!shouldRetry(synced(unavailable = 0)))
    }

    @Test
    fun `a run that lost the lock is not a failure`() {
        // Another sync was already in flight and is doing this run's work for
        // it. Retrying would just lose the race again.
        assertFalse(shouldRetry(EarmarkSyncer.Outcome.AlreadyRunning))
    }

    @Test
    fun `no key stored is not a failure`() {
        // Before the first key is entered there is nothing to sync. Retrying
        // would back off a schedule that should simply run when a key appears.
        assertFalse(shouldRetry(EarmarkSyncer.Outcome.NoKey))
    }

    @Test
    fun `orphans alone do not trigger a retry`() {
        // Orphan cleanup already republished the list without them. Their
        // blobs are gone from every server, so a retry would re-fetch
        // something that provably cannot be fetched.
        val orphansOnly = EarmarkSyncer.Outcome.Synced(
            earmarks = emptyList(),
            downloaded = 3,
            unavailable = 0
        )
        assertFalse(shouldRetry(orphansOnly))
    }
}
