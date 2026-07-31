package com.derpy.earmarks.nostr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ResolveNewestTest {

    private fun event(createdAt: Long) = JSONObject().put("created_at", createdAt)

    @Test
    fun `newest event wins across relays`() {
        val newest = event(200)
        val result = resolveNewest(
            listOf(QueryOutcome.Found(event(100)), QueryOutcome.Found(newest))
        )
        assertEquals(200L, result!!.getLong("created_at"))
    }

    @Test
    fun `absence is trusted when every relay answered`() {
        assertNull(resolveNewest(listOf(QueryOutcome.Empty, QueryOutcome.Empty)))
    }

    @Test
    fun `a found event survives another relay being down`() {
        val result = resolveNewest(
            listOf(QueryOutcome.Unreachable, QueryOutcome.Found(event(100)))
        )
        assertEquals(100L, result!!.getLong("created_at"))
    }

    @Test
    fun `nothing found with a relay down is an error, not absence`() {
        // The exact shape that wiped the channel chip: the relay holding the
        // state event unreachable, the other answering "nothing here".
        assertThrows(IOException::class.java) {
            resolveNewest(listOf(QueryOutcome.Unreachable, QueryOutcome.Empty))
        }
    }

    @Test
    fun `all relays down is an error`() {
        assertThrows(IOException::class.java) {
            resolveNewest(listOf(QueryOutcome.Unreachable, QueryOutcome.Unreachable))
        }
    }
}
