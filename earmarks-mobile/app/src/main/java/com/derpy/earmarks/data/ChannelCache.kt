package com.derpy.earmarks.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk snapshot of the last successful channel sync.
 *
 * Channel state lives on the relays, so without this cache a cold start shows
 * no channels until a full relay round-trip finishes — and a single slow relay
 * holds the chip row hostage for its whole 15s query timeout. The snapshot
 * renders instantly at startup; the next live sync overwrites it.
 *
 * Lives in filesDir next to earmarks.json and, like it, holds decrypted
 * metadata (including file keys) — app-private storage is the trust boundary.
 */
class ChannelCache(context: Context) {
    private val file = File(context.filesDir, "channels.json")

    fun save(sync: ChannelSync) {
        try {
            val json = channelSyncToJson(sync)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        } catch (_: Exception) {
            // Cache only — a failed write just means the next launch syncs cold.
        }
    }

    fun load(): ChannelSync? = try {
        if (file.exists()) channelSyncFromJson(file.readText()) else null
    } catch (_: Exception) {
        null
    }
}

fun channelSyncToJson(sync: ChannelSync): String = JSONObject().apply {
    put("state", JSONObject(sync.state.toJson()))
    put("posts", JSONArray().also { arr ->
        sync.posts.forEach { p ->
            arr.put(JSONObject().apply {
                put("chan", p.chan)
                put("sender", p.sender)
                put("posted_at", p.postedAt)
                put("earmark", earmarkToJsonObject(p.earmark))
            })
        }
    })
}.toString()

fun channelSyncFromJson(json: String): ChannelSync {
    val o = JSONObject(json)
    val state = channelStateFromJson(o.getJSONObject("state").toString())
    val posts = o.optJSONArray("posts")?.let { arr ->
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            ChannelPost(
                chan = p.getString("chan"),
                sender = p.optString("sender", ""),
                postedAt = p.optLong("posted_at", 0),
                earmark = parseEarmark(p.getJSONObject("earmark"))
            )
        }
    } ?: emptyList()
    return ChannelSync(posts, state)
}
