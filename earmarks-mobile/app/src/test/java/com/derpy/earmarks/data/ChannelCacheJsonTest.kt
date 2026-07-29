package com.derpy.earmarks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trips the channel snapshot the app persists between launches. */
class ChannelCacheJsonTest {

    private fun sampleSync(): ChannelSync {
        val descriptor = ChannelDescriptor(
            id = "chan-1234",
            name = "Lisette",
            creator = "creatorpub",
            createdAt = 1_700_000_000
        )
        val state = ChannelState(
            channels = listOf(
                Channel(descriptor, members = listOf("creatorpub", "memberpub"), seq = 3, joinedAt = 1_700_000_100)
            ),
            invites = listOf(
                ChannelInvite(
                    descriptor = ChannelDescriptor("chan-5678", "Other", "otherpub", 1_700_000_200),
                    members = listOf("otherpub"),
                    from = "otherpub",
                    receivedAt = 1_700_000_300,
                    trusted = true
                )
            ),
            declined = listOf("chan-9999"),
            pins = listOf(ChannelPin("chan-1234", listOf("aa11", "bb22"), 1_700_000_400))
        )
        val earmark = Earmark(
            artist = "Artist",
            album = "Album",
            title = "Title",
            ts = 1_700_000_500,
            blossom = BlossomManifest(
                key = "hexkey",
                ext = ".mp3",
                chunks = listOf(Chunk(0, "sha", 1024, listOf("https://blossom.towerofsong.ca")))
            )
        )
        return ChannelSync(
            posts = listOf(ChannelPost("chan-1234", "memberpub", 1_700_000_600, earmark)),
            state = state
        )
    }

    @Test
    fun roundTripPreservesEverything() {
        val original = sampleSync()
        val restored = channelSyncFromJson(channelSyncToJson(original))
        assertEquals(original.state, restored.state)
        assertEquals(original.posts, restored.posts)
    }

    @Test
    fun emptySyncRoundTrips() {
        val restored = channelSyncFromJson(channelSyncToJson(ChannelSync(emptyList(), ChannelState())))
        assertTrue(restored.posts.isEmpty())
        assertTrue(restored.state.channels.isEmpty())
        assertTrue(restored.state.invites.isEmpty())
    }
}
