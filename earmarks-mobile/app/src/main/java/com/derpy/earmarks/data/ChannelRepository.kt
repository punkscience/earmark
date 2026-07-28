package com.derpy.earmarks.data

import android.util.Log
import com.derpy.earmarks.nostr.GiftWrap
import com.derpy.earmarks.nostr.Nip44
import com.derpy.earmarks.nostr.NostrService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "ChannelRepository"

/** Result of a channel sync: the live posts plus the state they were judged against. */
data class ChannelSync(val posts: List<ChannelPost>, val state: ChannelState)

/**
 * Channel operations, mirroring `earmark-core`'s `roster.go` and
 * `channelfeed.go`. The rules enforced here are the protocol's, not this
 * client's — a client that skipped them would just be reading spam.
 */
class ChannelRepository(private val nostr: NostrService) {

    suspend fun loadState(privKeyHex: String): ChannelState = withContext(Dispatchers.IO) {
        val json = nostr.fetchSelfEncrypted(privKeyHex, CHANNEL_STATE_D_TAG)
            ?: return@withContext ChannelState()
        try {
            channelStateFromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "could not parse channel state: ${e.message}")
            ChannelState()
        }
    }

    suspend fun saveState(privKeyHex: String, state: ChannelState): Boolean =
        withContext(Dispatchers.IO) {
            nostr.publishSelfEncrypted(privKeyHex, CHANNEL_STATE_D_TAG, state.toJson()) > 0
        }

    /**
     * Fetches gift wraps, applies every roster, invite and leave to state, and
     * returns the live posts newest first.
     *
     * State is written back only when something actually changed, so polling
     * costs no relay writes.
     */
    suspend fun sync(privKeyHex: String): ChannelSync = withContext(Dispatchers.IO) {
        val myPub = Nip44.derivePubKeyHex(privKeyHex)
        var state = loadState(privKeyHex)
        val now = System.currentTimeMillis() / 1000

        val since = now - CHANNEL_POST_MAX_AGE_SECONDS - GiftWrap.QUERY_BACKDATE_SECONDS
        // Gift wraps land on the relays we advertise as our inbox, which is
        // where other people's clients were told to send them.
        val inbox = try {
            nostr.fetchInboxRelays(myPub)
        } catch (e: Exception) {
            emptyList()
        }
        val wraps = nostr.fetchGiftWraps(myPub, since, inbox)

        var changed = false
        dropExpiredPins(state, now)?.let { state = it; changed = true }

        // Follows decide only how loudly an invite arrives. Failing to fetch
        // them must not block the sync — an empty list just means nothing is
        // marked trusted.
        val follows = nostr.fetchFollows(myPub).toSet()

        // Rosters must be applied before posts are judged, or a post from
        // someone added in this same batch would be rejected as a stranger.
        val pendingPosts = mutableListOf<Pair<String, Envelope>>()

        for (wrap in wraps) {
            val opened = GiftWrap.unwrap(privKeyHex, wrap) ?: continue
            val env = parseEnvelope(opened.content) ?: continue
            if (env.v > CHANNEL_ENVELOPE_VERSION) continue

            when (env.type) {
                EnvelopeType.ROSTER -> applyRoster(state, myPub, opened.senderPubKey, env)
                    ?.let { state = it; changed = true }
                EnvelopeType.INVITE -> applyInvite(
                    state, opened.senderPubKey, env,
                    trusted = follows.contains(opened.senderPubKey)
                )
                    ?.let { state = it; changed = true }
                EnvelopeType.LEAVE -> applyLeave(state, opened.senderPubKey, env)
                    ?.let { state = it; changed = true }
                EnvelopeType.POST -> pendingPosts.add(opened.senderPubKey to env)
            }
        }

        val seen = HashSet<String>()
        val posts = mutableListOf<ChannelPost>()
        for ((sender, env) in pendingPosts) {
            val earmark = env.earmark ?: continue
            if (earmark.blossom == null) continue
            val channel = state.find(env.chan) ?: continue
            // A post from someone not on the roster is dropped without a word.
            // This is the spam defence.
            if (!channel.hasMember(sender)) continue

            val post = ChannelPost(env.chan, sender, env.postedAt, earmark)
            if (post.expired(now)) continue
            if (!seen.add(post.id)) continue
            posts.add(post)
        }
        posts.sortByDescending { it.postedAt }

        if (changed) saveState(privKeyHex, state)
        ChannelSync(posts, state)
    }

    /** Posts an earmark to a channel and pins its chunks against our own purge. */
    suspend fun post(privKeyHex: String, chanId: String, earmark: Earmark): Result<Unit> =
        withContext(Dispatchers.IO) {
            val manifest = earmark.blossom
                ?: return@withContext Result.failure(
                    IllegalArgumentException("that track was never uploaded")
                )
            val myPub = Nip44.derivePubKeyHex(privKeyHex)
            var state = loadState(privKeyHex)
            val channel = state.find(chanId)
                ?: return@withContext Result.failure(IllegalArgumentException("unknown channel"))

            val recipients = channel.members.filter { it != myPub }
            if (recipients.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("${channel.descriptor.name} has no other members yet")
                )
            }

            val now = System.currentTimeMillis() / 1000
            val envelope = Envelope(
                v = CHANNEL_ENVELOPE_VERSION,
                chan = chanId,
                type = EnvelopeType.POST,
                postedAt = now,
                earmark = earmark
            ).toJson()

            var delivered = 0
            for (member in recipients) {
                try {
                    if (nostr.publishEvent(GiftWrap.wrap(privKeyHex, member, envelope)) > 0) delivered++
                } catch (e: Exception) {
                    Log.w(TAG, "could not wrap for ${member.take(8)}: ${e.message}")
                }
            }
            if (delivered == 0) {
                return@withContext Result.failure(IllegalStateException("no relay accepted the post"))
            }

            state = state.copy(
                pins = state.pins + ChannelPin(chanId, manifest.chunks.map { it.sha256 }, now)
            )
            dropExpiredPins(state, now)?.let { state = it }
            saveState(privKeyHex, state)
            Result.success(Unit)
        }

    /** Accepts a pending invite. */
    suspend fun acceptInvite(privKeyHex: String, chanId: String): Result<Channel> =
        withContext(Dispatchers.IO) {
            val state = loadState(privKeyHex)
            val invite = state.findInvite(chanId)
                ?: return@withContext Result.failure(IllegalArgumentException("no pending invite"))
            state.find(chanId)?.let { return@withContext Result.success(it) }

            val channel = Channel(
                descriptor = invite.descriptor,
                members = invite.members,
                seq = 0,
                joinedAt = System.currentTimeMillis() / 1000
            )
            saveState(
                privKeyHex,
                state.copy(
                    channels = state.channels + channel,
                    invites = state.invites.filterNot { it.descriptor.id == chanId }
                )
            )
            Result.success(channel)
        }

    /** Declines an invite and remembers the decision so it is not re-surfaced. */
    suspend fun declineInvite(privKeyHex: String, chanId: String): Boolean =
        withContext(Dispatchers.IO) {
            val state = loadState(privKeyHex)
            saveState(
                privKeyHex,
                state.copy(
                    invites = state.invites.filterNot { it.descriptor.id == chanId },
                    declined = (state.declined + chanId).distinct()
                )
            )
        }

    /** Leaves a channel and tells the other members to stop encrypting to us. */
    suspend fun leave(privKeyHex: String, chanId: String): Boolean = withContext(Dispatchers.IO) {
        val myPub = Nip44.derivePubKeyHex(privKeyHex)
        val state = loadState(privKeyHex)
        val channel = state.find(chanId) ?: return@withContext false

        val envelope = Envelope(
            v = CHANNEL_ENVELOPE_VERSION,
            chan = chanId,
            type = EnvelopeType.LEAVE,
            leftAt = System.currentTimeMillis() / 1000
        ).toJson()
        for (member in channel.members.filter { it != myPub }) {
            try {
                nostr.publishEvent(GiftWrap.wrap(privKeyHex, member, envelope))
            } catch (e: Exception) {
                Log.w(TAG, "could not send leave to ${member.take(8)}: ${e.message}")
            }
        }
        saveState(
            privKeyHex,
            state.copy(
                channels = state.channels.filterNot { it.descriptor.id == chanId },
                pins = state.pins.filterNot { it.chan == chanId }
            )
        )
    }
}

// --- Rule application --------------------------------------------------------
// Each returns the new state, or null when the message changes nothing.

/**
 * A roster is honoured only when the seal author is the channel's creator and
 * the sequence advances. The first rule stops a member widening the audience
 * for music someone else posts; the second stops a replayed roster resurrecting
 * a removed member.
 */
internal fun applyRoster(
    state: ChannelState,
    self: String,
    sender: String,
    env: Envelope
): ChannelState? {
    val channel = state.find(env.chan) ?: return null
    if (sender != channel.descriptor.creator) return null
    if (env.seq <= channel.seq) return null

    // Being absent from a roster the creator signed is how removal arrives —
    // there is no separate "you're out" message.
    if (!env.members.contains(self)) {
        return state.copy(
            channels = state.channels.filterNot { it.descriptor.id == env.chan },
            pins = state.pins.filterNot { it.chan == env.chan }
        )
    }
    return state.copy(channels = state.channels.map {
        if (it.descriptor.id != env.chan) it
        else it.copy(
            descriptor = if (env.name.isNotEmpty()) it.descriptor.copy(name = env.name)
            else it.descriptor,
            members = env.members,
            seq = env.seq
        )
    })
}

/** A leave can only remove its own sender. One naming anyone else is meaningless. */
internal fun applyLeave(state: ChannelState, sender: String, env: Envelope): ChannelState? {
    val channel = state.find(env.chan) ?: return null
    if (!channel.hasMember(sender)) return null
    return state.copy(channels = state.channels.map {
        if (it.descriptor.id != env.chan) it
        else it.copy(members = it.members.filterNot { m -> m == sender })
    })
}

/**
 * Records a pending invite. Anyone may send an invite; nobody may send one on
 * someone else's behalf, so the creator it names must be the one who sealed it.
 */
internal fun applyInvite(
    state: ChannelState,
    sender: String,
    env: Envelope,
    trusted: Boolean
): ChannelState? {
    if (state.find(env.chan) != null) return null
    if (state.declined.contains(env.chan)) return null
    if (env.creator != sender) return null

    val invite = ChannelInvite(
        descriptor = ChannelDescriptor(env.chan, env.name, env.creator, env.createdAt),
        members = env.members,
        from = sender,
        receivedAt = System.currentTimeMillis() / 1000,
        trusted = trusted
    )
    val existing = state.findInvite(env.chan)
    return if (existing != null) {
        state.copy(invites = state.invites.map {
            if (it.descriptor.id == env.chan) invite else it
        })
    } else {
        state.copy(invites = state.invites + invite)
    }
}

/** Drops pins past their window. Returns null when nothing changed. */
internal fun dropExpiredPins(state: ChannelState, nowSeconds: Long): ChannelState? {
    val kept = state.pins.filter { it.postedAt >= nowSeconds - CHANNEL_POST_MAX_AGE_SECONDS }
    return if (kept.size == state.pins.size) null else state.copy(pins = kept)
}
