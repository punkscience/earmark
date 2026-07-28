package com.derpy.earmarks.data

import org.json.JSONArray
import org.json.JSONObject

data class Chunk(
    val index: Int,
    val sha256: String,
    val size: Int,
    val servers: List<String>
)

data class BlossomManifest(
    val key: String,
    val ext: String,
    val chunks: List<Chunk>
)

data class Earmark(
    val artist: String,
    val album: String,
    val title: String,
    val ts: Long,
    val blossom: BlossomManifest?
)

fun earmarksToJson(earmarks: List<Earmark>): String {
    val arr = JSONArray()
    for (e in earmarks) {
        val obj = JSONObject()
        obj.put("artist", e.artist)
        obj.put("album", e.album)
        obj.put("title", e.title)
        obj.put("ts", e.ts)
        if (e.blossom != null) {
            val b = JSONObject()
            b.put("key", e.blossom.key)
            b.put("ext", e.blossom.ext)
            val chunks = JSONArray()
            for (c in e.blossom.chunks) {
                val co = JSONObject()
                co.put("index", c.index)
                co.put("sha256", c.sha256)
                co.put("size", c.size)
                val servers = JSONArray()
                c.servers.forEach { servers.put(it) }
                co.put("servers", servers)
                chunks.put(co)
            }
            b.put("chunks", chunks)
            obj.put("blossom", b)
        } else {
            obj.put("blossom", JSONObject.NULL)
        }
        arr.put(obj)
    }
    return arr.toString()
}

fun parseEarmarkList(json: String): List<Earmark> {
    val arr = JSONArray(json)
    return (0 until arr.length()).mapNotNull { i ->
        try {
            parseEarmark(arr.getJSONObject(i))
        } catch (_: Exception) { null }
    }.filter { it.blossom != null }
}

/**
 * Parses a single earmark object. Shared by the earmark list and by channel
 * post envelopes, which carry the identical shape (minus `path`, which is
 * stripped before sharing — it names a directory on someone else's machine).
 */
fun parseEarmark(obj: JSONObject): Earmark {
    val blossomObj = if (obj.isNull("blossom")) null else obj.optJSONObject("blossom")
    val blossom = blossomObj?.let { b ->
        val chunksArr = b.getJSONArray("chunks")
        BlossomManifest(
            key = b.getString("key"),
            ext = b.optString("ext", ""),
            chunks = (0 until chunksArr.length()).map { j ->
                val c = chunksArr.getJSONObject(j)
                val serversArr = c.optJSONArray("servers")
                Chunk(
                    index = c.getInt("index"),
                    sha256 = c.getString("sha256"),
                    size = c.getInt("size"),
                    servers = serversArr?.let { s ->
                        (0 until s.length()).map { k -> s.getString(k) }
                    } ?: emptyList()
                )
            }
        )
    }
    return Earmark(
        artist = obj.optString("artist", ""),
        album = obj.optString("album", ""),
        title = obj.optString("title", ""),
        ts = obj.getLong("ts"),
        blossom = blossom
    )
}

/** Serialises a single earmark. Inverse of [parseEarmark]. */
fun earmarkToJsonObject(e: Earmark): JSONObject = JSONObject().apply {
    put("artist", e.artist)
    put("album", e.album)
    put("title", e.title)
    put("ts", e.ts)
    if (e.blossom != null) {
        put("blossom", JSONObject().apply {
            put("key", e.blossom.key)
            put("ext", e.blossom.ext)
            put("chunks", JSONArray().also { chunks ->
                e.blossom.chunks.forEach { c ->
                    chunks.put(JSONObject().apply {
                        put("index", c.index)
                        put("sha256", c.sha256)
                        put("size", c.size)
                        put("servers", JSONArray().also { s -> c.servers.forEach { s.put(it) } })
                    })
                }
            })
        })
    } else {
        put("blossom", JSONObject.NULL)
    }
}
