package com.derpy.earmarks.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelResyncThrottleTest {

    @Test
    fun `first ever sync is never throttled`() {
        assertTrue(shouldResyncChannels(lastSyncMs = 0L, nowMs = 1L))
    }

    @Test
    fun `a just-finished sync suppresses the next one`() {
        val now = 1_000_000L
        assertFalse(shouldResyncChannels(lastSyncMs = now, nowMs = now))
        assertFalse(
            shouldResyncChannels(
                lastSyncMs = now,
                nowMs = now + CHANNEL_RESYNC_MIN_INTERVAL_MS - 1
            )
        )
    }

    @Test
    fun `sync is allowed again once the interval has fully elapsed`() {
        val now = 1_000_000L
        assertTrue(
            shouldResyncChannels(
                lastSyncMs = now,
                nowMs = now + CHANNEL_RESYNC_MIN_INTERVAL_MS
            )
        )
    }
}
