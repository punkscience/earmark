package com.derpy.earmarks.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanSyncChannelsTest {

    @Test
    fun `player up allows a re-sync`() {
        assertTrue(canSyncChannels(AppState.Playing(emptyList())))
    }

    @Test
    fun `an error screen still allows a re-sync`() {
        // An invited account with an empty stash lives on the error screen;
        // channels are how music first arrives for it.
        assertTrue(canSyncChannels(AppState.Error("No earmarks in your stash yet.")))
    }

    @Test
    fun `startup states do not`() {
        assertFalse(canSyncChannels(AppState.KeyMissing))
        assertFalse(canSyncChannels(AppState.Loading("Starting…")))
    }
}
