package com.derpy.earmarks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostRecipientsTest {

    private fun channel(members: List<String>) = Channel(
        descriptor = ChannelDescriptor("chan1", "Family Jams", "self", 0),
        members = members,
        seq = 1,
        joinedAt = 0
    )

    @Test
    fun `a post fans out to every member including the sender`() {
        assertEquals(
            listOf("self", "friend"),
            postRecipients(channel(listOf("self", "friend")), "self")
        )
    }

    @Test
    fun `posting into a members-of-one channel is rejected`() {
        assertNull(postRecipients(channel(listOf("self")), "self"))
    }
}
