package com.derpy.earmarks

import com.derpy.earmarks.data.CHANNEL_STATE_D_TAG
import com.derpy.earmarks.data.channelStateFromJson
import com.derpy.earmarks.nostr.NostrService
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live diagnostic, not a CI test: runs the app's real fetch/decrypt/parse path
 * against the production relays with the key in EARMARK_TEST_KEY_HEX. Skipped
 * when the env var is absent, so CI never runs it.
 */
class LiveChannelStateDiagnostic {

    @Test
    fun fetchAndParseLiveChannelState() = runBlocking {
        val key = System.getenv("EARMARK_TEST_KEY_HEX")
        assumeTrue("EARMARK_TEST_KEY_HEX not set; skipping live diagnostic", !key.isNullOrEmpty())
        key!!

        val nostr = NostrService(OkHttpClient())
        val json = nostr.fetchSelfEncrypted(key, CHANNEL_STATE_D_TAG)
        println("DIAG raw state json: $json")
        if (json == null) {
            println("DIAG: fetchSelfEncrypted returned null — no event found or decrypt failed")
            return@runBlocking
        }
        val state = channelStateFromJson(json)
        println("DIAG channels=${state.channels.map { it.descriptor.name to it.members.size }}")
        println("DIAG invites=${state.invites.size} pins=${state.pins.size}")
    }
}
