package com.derpy.earmarks.nostr

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * NIP-59 gift wrap — the transport for every channel message.
 *
 * Three layers:
 *   1. rumor      unsigned, kind [CHANNEL_RUMOR_KIND], content is the channel envelope
 *   2. seal       kind 13, NIP-44(rumor, sender -> recipient), signed by the sender's REAL key
 *   3. gift wrap  kind 1059, NIP-44(seal, ephemeral -> recipient), signed by a throwaway key
 *
 * A relay storing the result learns only that some pubkey it has never seen
 * before sent something to the recipient. Not the sender, not the channel, not
 * that a channel exists at all.
 *
 * This must stay byte-compatible with `earmark-core/giftwrap.go`. See the
 * Channels section of docs/PROTOCOL.md.
 */
object GiftWrap {

    const val CHANNEL_RUMOR_KIND = 1737
    const val SEAL_KIND = 13
    const val GIFT_WRAP_KIND = 1059

    /**
     * NIP-59 permits backdating a seal or wrap by up to two days, so a query for
     * the last 30 days of content has to reach back 32.
     */
    const val QUERY_BACKDATE_SECONDS = 2L * 24 * 60 * 60

    /** Backdating window used when sending. Matches go-nostr's six hours. */
    private const val MAX_BACKDATE_SECONDS = 6L * 60 * 60

    private val random = SecureRandom()

    /** An unwrapped message and the identity that provably sent it. */
    data class Unwrapped(val senderPubKey: String, val content: String)

    /**
     * Wraps [content] for exactly one recipient.
     *
     * @return a signed kind-1059 event ready to publish.
     */
    fun wrap(senderPrivHex: String, recipientPubHex: String, content: String): JSONObject {
        val senderPubHex = Nip44.derivePubKeyHex(senderPrivHex)
        val convKey = Nip44.conversationKey(senderPrivHex, recipientPubHex)

        // The rumor is unsigned by design — it carries an id but no signature,
        // so it can never be published as a standalone event.
        val rumor = unsignedEvent(
            pubKeyHex = senderPubHex,
            kind = CHANNEL_RUMOR_KIND,
            content = content,
            createdAt = System.currentTimeMillis() / 1000
        )

        val seal = NostrEvent.build(
            privKeyHex = senderPrivHex,
            kind = SEAL_KIND,
            content = Nip44.encryptWithKey(convKey, rumor.toString()),
            tags = emptyList(),
            createdAt = backdated()
        )

        val ephemeralPriv = generatePrivateKey()
        val ephemeralConvKey = Nip44.conversationKey(ephemeralPriv, recipientPubHex)
        return NostrEvent.build(
            privKeyHex = ephemeralPriv,
            kind = GIFT_WRAP_KIND,
            content = Nip44.encryptWithKey(ephemeralConvKey, seal.toString()),
            tags = listOf(listOf("p", recipientPubHex)),
            createdAt = backdated()
        )
    }

    /**
     * Reverses [wrap]. Returns null for anything we cannot or should not read:
     * a wrap addressed to someone else, a bad signature, a forged author. None
     * of those are errors worth surfacing — they are other people's mail, or
     * spam.
     */
    fun unwrap(myPrivHex: String, giftWrap: JSONObject): Unwrapped? = try {
        unwrapOrThrow(myPrivHex, giftWrap)
    } catch (e: Exception) {
        null
    }

    private fun unwrapOrThrow(myPrivHex: String, giftWrap: JSONObject): Unwrapped? {
        if (giftWrap.optInt("kind") != GIFT_WRAP_KIND) return null
        if (!verifyEvent(giftWrap)) return null

        val wrapperPub = giftWrap.getString("pubkey")
        val sealJson = Nip44.decryptFrom(myPrivHex, wrapperPub, giftWrap.getString("content"))
        val seal = JSONObject(sealJson)

        if (seal.optInt("kind") != SEAL_KIND) return null
        // Only the seal is signed, so this is the single point where identity is
        // established. Everything downstream trusts it.
        if (!verifyEvent(seal)) return null

        val sealPub = seal.getString("pubkey")
        val rumorJson = Nip44.decryptFrom(myPrivHex, sealPub, seal.getString("content"))
        val rumor = JSONObject(rumorJson)

        if (rumor.optInt("kind") != CHANNEL_RUMOR_KIND) return null

        // The rumor is unsigned, so its pubkey field is whatever the sender
        // typed. Anything other than agreement with the seal is an attempt to
        // impersonate someone, and is rejected rather than quietly corrected.
        val claimed = rumor.optString("pubkey", "")
        if (claimed.isNotEmpty() && claimed != sealPub) return null

        return Unwrapped(senderPubKey = sealPub, content = rumor.optString("content", ""))
    }

    /**
     * Verifies a signed event: the id must be the hash of its canonical
     * serialization, and the signature must check out against the pubkey.
     *
     * Recomputing the id matters as much as the signature — the signature only
     * covers the id, so without this an attacker could keep a valid signature
     * and swap the content underneath it.
     */
    fun verifyEvent(event: JSONObject): Boolean = try {
        val pubKeyHex = event.getString("pubkey")
        val tags = mutableListOf<List<String>>()
        val tagsArr = event.optJSONArray("tags")
        if (tagsArr != null) {
            for (i in 0 until tagsArr.length()) {
                val t = tagsArr.getJSONArray(i)
                tags.add((0 until t.length()).map { t.getString(it) })
            }
        }
        val serial = NostrEvent.canonicalSerialize(
            pubKeyHex,
            event.getLong("created_at"),
            event.getInt("kind"),
            tags,
            event.getString("content")
        )
        val computedId = sha256(serial.toByteArray(Charsets.UTF_8)).toHex()
        computedId == event.getString("id") &&
            Schnorr.verify(pubKeyHex.hexToBytes(), computedId.hexToBytes(),
                event.getString("sig").hexToBytes())
    } catch (e: Exception) {
        false
    }

    /**
     * Builds the unsigned rumor. Its id is computed the same way a signed
     * event's is, so the Go side sees an identical structure; only `sig` is
     * absent.
     */
    private fun unsignedEvent(
        pubKeyHex: String,
        kind: Int,
        content: String,
        createdAt: Long
    ): JSONObject {
        val serial = NostrEvent.canonicalSerialize(pubKeyHex, createdAt, kind, emptyList(), content)
        val id = sha256(serial.toByteArray(Charsets.UTF_8)).toHex()
        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubKeyHex)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", org.json.JSONArray())
            put("content", content)
        }
    }

    /** A random 32-byte secp256k1 private key, used once and discarded. */
    fun generatePrivateKey(): String {
        val bytes = ByteArray(32)
        do {
            random.nextBytes(bytes)
        } while (bytes.all { it == 0.toByte() })
        return bytes.toHex()
    }

    private fun backdated(): Long =
        System.currentTimeMillis() / 1000 - (random.nextDouble() * MAX_BACKDATE_SECONDS).toLong()

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
