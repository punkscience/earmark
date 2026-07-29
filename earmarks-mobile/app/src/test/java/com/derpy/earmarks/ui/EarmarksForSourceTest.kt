package com.derpy.earmarks.ui

import com.derpy.earmarks.data.ChannelPost
import com.derpy.earmarks.data.Earmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun earmark(ts: Long, title: String) =
    Earmark(artist = "", album = "", title = title, ts = ts, blossom = null)

private fun post(chan: String, e: Earmark) =
    ChannelPost(chan = chan, sender = "abc", postedAt = e.ts, earmark = e)

class EarmarksForSourceTest {

    private val mine = listOf(earmark(1, "mine one"), earmark(2, "mine two"))
    private val lisette = earmark(3, "lisette one")
    private val other = earmark(4, "other one")
    private val posts = mapOf(
        "lisette" to listOf(post("lisette", lisette)),
        "other" to listOf(post("other", other))
    )

    @Test
    fun `the personal stash yields only the personal list`() {
        val result = earmarksForSource(PlayerSource.MyEarmarks, mine, posts)
        assertEquals(mine, result)
    }

    @Test
    fun `a channel yields only its own posts`() {
        val result = earmarksForSource(PlayerSource.Channel("lisette", "Lisette"), mine, posts)
        assertEquals(listOf(lisette), result)
    }

    @Test
    fun `a channel never picks up another channel's posts`() {
        val result = earmarksForSource(PlayerSource.Channel("lisette", "Lisette"), mine, posts)
        assertFalse(result.contains(other))
    }

    @Test
    fun `an empty channel yields nothing rather than falling back to the stash`() {
        val result = earmarksForSource(PlayerSource.Channel("empty", "Empty"), mine, posts)
        assertTrue(result.isEmpty())
    }
}

class CanDeleteFromTest {

    @Test
    fun `your own stash may be deleted from`() {
        assertTrue(canDeleteFrom(PlayerSource.MyEarmarks))
    }

    @Test
    fun `a channel feed may not`() {
        // The track belongs to whoever posted it; deleting would republish the
        // personal list from a playlist that was never in it and sweep the
        // poster's blobs off Blossom.
        assertFalse(canDeleteFrom(PlayerSource.Channel("lisette", "Lisette")))
    }
}
