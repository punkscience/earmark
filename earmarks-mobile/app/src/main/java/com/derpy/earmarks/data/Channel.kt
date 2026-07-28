package com.derpy.earmarks.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Channel model, mirroring `earmark-core/channel.go` and `channelstore.go`.
 * Changing any shape here is a protocol change — see the Channels section of
 * docs/PROTOCOL.md.
 */

const val CHANNEL_ENVELOPE_VERSION = 1
const val CHANNEL_STATE_D_TAG = "earmark-channels"

/** A channel post is live for 30 days from its posted_at. */
const val CHANNEL_POST_MAX_AGE_SECONDS = 30L * 24 * 60 * 60

object EnvelopeType {
    const val INVITE = "invite"
    const val ROSTER = "roster"
    const val POST = "post"
    const val LEAVE = "leave"
}

data class ChannelDescriptor(
    val id: String,
    val name: String,
    val creator: String,
    val createdAt: Long
)

data class Channel(
    val descriptor: ChannelDescriptor,
    val members: List<String>,
    val seq: Int,
    val joinedAt: Long
) {
    fun isCreator(pubHex: String) = descriptor.creator == pubHex
    fun hasMember(pubHex: String) = members.contains(pubHex)
}

data class ChannelInvite(
    val descriptor: ChannelDescriptor,
    val members: List<String>,
    val from: String,
    val receivedAt: Long,
    /** Whether the sender was in the user's NIP-02 follow list when it arrived. */
    val trusted: Boolean
)

/** Chunks this user posted and must keep alive for the post's 30-day window. */
data class ChannelPin(
    val chan: String,
    val chunks: List<String>,
    val postedAt: Long
)

/** A post received from a channel. [sender] is authenticated — it is the seal's pubkey. */
data class ChannelPost(
    val chan: String,
    val sender: String,
    val postedAt: Long,
    val earmark: Earmark
) {
    /** Stable id, used to dedupe the same post arriving from several relays. */
    val id: String get() = "${chan.take(8)}:${sender.take(8)}:$postedAt"

    fun expired(nowSeconds: Long) = postedAt < nowSeconds - CHANNEL_POST_MAX_AGE_SECONDS
}

/** Everything the client knows about its channels. Stored self-encrypted. */
data class ChannelState(
    val channels: List<Channel> = emptyList(),
    val invites: List<ChannelInvite> = emptyList(),
    val declined: List<String> = emptyList(),
    val pins: List<ChannelPin> = emptyList()
) {
    fun find(chanId: String): Channel? = channels.firstOrNull { it.descriptor.id == chanId }
    fun findInvite(chanId: String): ChannelInvite? =
        invites.firstOrNull { it.descriptor.id == chanId }

    /** Chunk hashes protected from deletion: those under a pin still in its window. */
    fun pinnedChunks(nowSeconds: Long): Set<String> =
        pins.filter { it.postedAt >= nowSeconds - CHANNEL_POST_MAX_AGE_SECONDS }
            .flatMap { it.chunks }
            .toSet()
}

// --- Serialisation -----------------------------------------------------------
// Field names must match the Go structs exactly; this is the on-the-wire shape.

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }

private fun List<String>.toJsonArray(): JSONArray =
    JSONArray().also { arr -> forEach { arr.put(it) } }

fun descriptorFromJson(o: JSONObject) = ChannelDescriptor(
    id = o.getString("id"),
    name = o.optString("name", ""),
    creator = o.optString("creator", ""),
    createdAt = o.optLong("created_at", 0)
)

fun ChannelDescriptor.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("creator", creator)
    put("created_at", createdAt)
}

fun channelStateFromJson(json: String): ChannelState {
    val o = JSONObject(json)

    val channels = o.optJSONArray("channels")?.let { arr ->
        (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            Channel(
                descriptor = descriptorFromJson(c.getJSONObject("descriptor")),
                members = c.optJSONArray("members")?.toStringList() ?: emptyList(),
                seq = c.optInt("seq", 0),
                joinedAt = c.optLong("joined_at", 0)
            )
        }
    } ?: emptyList()

    val invites = o.optJSONArray("invites")?.let { arr ->
        (0 until arr.length()).map { i ->
            val v = arr.getJSONObject(i)
            ChannelInvite(
                descriptor = descriptorFromJson(v.getJSONObject("descriptor")),
                members = v.optJSONArray("members")?.toStringList() ?: emptyList(),
                from = v.optString("from", ""),
                receivedAt = v.optLong("received_at", 0),
                trusted = v.optBoolean("trusted", false)
            )
        }
    } ?: emptyList()

    val pins = o.optJSONArray("pins")?.let { arr ->
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            ChannelPin(
                chan = p.optString("chan", ""),
                chunks = p.optJSONArray("chunks")?.toStringList() ?: emptyList(),
                postedAt = p.optLong("posted_at", 0)
            )
        }
    } ?: emptyList()

    return ChannelState(
        channels = channels,
        invites = invites,
        declined = o.optJSONArray("declined")?.toStringList() ?: emptyList(),
        pins = pins
    )
}

fun ChannelState.toJson(): String = JSONObject().apply {
    put("v", CHANNEL_ENVELOPE_VERSION)
    put("channels", JSONArray().also { arr ->
        channels.forEach { c ->
            arr.put(JSONObject().apply {
                put("descriptor", c.descriptor.toJson())
                put("members", c.members.toJsonArray())
                put("seq", c.seq)
                put("joined_at", c.joinedAt)
            })
        }
    })
    if (invites.isNotEmpty()) put("invites", JSONArray().also { arr ->
        invites.forEach { v ->
            arr.put(JSONObject().apply {
                put("descriptor", v.descriptor.toJson())
                put("members", v.members.toJsonArray())
                put("from", v.from)
                put("received_at", v.receivedAt)
                put("trusted", v.trusted)
            })
        }
    })
    if (declined.isNotEmpty()) put("declined", declined.toJsonArray())
    if (pins.isNotEmpty()) put("pins", JSONArray().also { arr ->
        pins.forEach { p ->
            arr.put(JSONObject().apply {
                put("chan", p.chan)
                put("chunks", p.chunks.toJsonArray())
                put("posted_at", p.postedAt)
            })
        }
    })
}.toString()

/**
 * A decoded channel envelope. One class covers all four message types; [type]
 * says which fields are meaningful.
 */
data class Envelope(
    val v: Int,
    val chan: String,
    val type: String,
    val name: String = "",
    val members: List<String> = emptyList(),
    val creator: String = "",
    val createdAt: Long = 0,
    val seq: Int = 0,
    val updatedAt: Long = 0,
    val postedAt: Long = 0,
    val earmark: Earmark? = null,
    val leftAt: Long = 0
)

/** Returns null for anything unparseable — a malformed envelope is dropped, not raised. */
fun parseEnvelope(json: String): Envelope? = try {
    val o = JSONObject(json)
    val chan = o.optString("chan", "")
    if (chan.isEmpty()) null else Envelope(
        v = o.optInt("v", 0),
        chan = chan,
        type = o.optString("type", ""),
        name = o.optString("name", ""),
        members = o.optJSONArray("members")?.toStringList() ?: emptyList(),
        creator = o.optString("creator", ""),
        createdAt = o.optLong("created_at", 0),
        seq = o.optInt("seq", 0),
        updatedAt = o.optLong("updated_at", 0),
        postedAt = o.optLong("posted_at", 0),
        earmark = o.optJSONObject("earmark")?.let { parseEarmark(it) },
        leftAt = o.optLong("left_at", 0)
    )
} catch (e: Exception) {
    null
}

fun Envelope.toJson(): String = JSONObject().apply {
    put("v", v)
    put("chan", chan)
    put("type", type)
    if (name.isNotEmpty()) put("name", name)
    if (members.isNotEmpty()) put("members", members.toJsonArray())
    if (creator.isNotEmpty()) put("creator", creator)
    if (createdAt != 0L) put("created_at", createdAt)
    if (seq != 0) put("seq", seq)
    if (updatedAt != 0L) put("updated_at", updatedAt)
    if (postedAt != 0L) put("posted_at", postedAt)
    if (earmark != null) put("earmark", earmarkToJsonObject(earmark))
    if (leftAt != 0L) put("left_at", leftAt)
}.toString()
