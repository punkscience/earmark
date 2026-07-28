package com.derpy.earmarks.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Builds and signs Nostr events per NIP-01.
 *
 * Event id = sha256( canonical serialization of [0, pubkey, created_at, kind, tags, content] )
 * Signature = BIP-340 schnorr( private key, event id )
 *
 * IMPORTANT: we do NOT use JSONArray.toString() to produce the hash input.
 * Android's org.json.JSONStringer escapes forward-slash as `\/`, which
 * NIP-01 explicitly forbids. Base64-encoded NIP-44 ciphertext contains `/`
 * characters, so every one of those would double-escape in the hash input
 * but NOT in the relay's re-canonicalization, producing a mismatched id.
 * The relay then rejects the event with "invalid: bad event id" and every
 * publish silently fails. We roll our own serializer below that escapes
 * only the seven characters the spec lists.
 */
object NostrEvent {

    /**
     * Builds a signed Nostr event JSON object ready to send to a relay.
     *
     * @param tags list of tag arrays, e.g. listOf(listOf("d", "derpy-earmarks"))
     */
    fun build(
        privKeyHex: String,
        kind: Int,
        content: String,
        tags: List<List<String>>,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): JSONObject {
        val pubKeyHex = Nip44.derivePubKeyHex(privKeyHex)

        val serial = canonicalSerialize(pubKeyHex, createdAt, kind, tags, content)
        val id = sha256(serial.toByteArray(Charsets.UTF_8)).toHex()

        val skBytes = privKeyHex.hexToBytes()
        val sig = Schnorr.sign(skBytes, id.hexToBytes()).toHex()

        // For the wire-format event we can use JSONObject — relays parse JSON
        // and re-canonicalize before validating the id, so permissive
        // escaping on the wire is fine. Only the hash input must be strict.
        val tagsArr = JSONArray().apply {
            tags.forEach { tag ->
                put(JSONArray().apply { tag.forEach { put(it) } })
            }
        }
        return JSONObject().apply {
            put("id", id)
            put("pubkey", pubKeyHex)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", tagsArr)
            put("content", content)
            put("sig", sig)
        }
    }

    /**
     * NIP-01 canonical serialization of the six-element event array used for
     * the id hash. Per spec, only the following characters are escaped in
     * strings; everything else (including `/`) is emitted verbatim:
     *   0x08 → \b,  0x09 → \t,  0x0A → \n,  0x0C → \f,  0x0D → \r,
     *   0x22 → \",  0x5C → \\,  other control chars (< 0x20) → \uXXXX.
     */
    private fun canonicalSerialize(
        pubKeyHex: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): String = buildString {
        append("[0,")
        appendCanonicalString(pubKeyHex)
        append(',').append(createdAt)
        append(',').append(kind)
        append(',').append('[')
        tags.forEachIndexed { i, tag ->
            if (i > 0) append(',')
            append('[')
            tag.forEachIndexed { j, s ->
                if (j > 0) append(',')
                appendCanonicalString(s)
            }
            append(']')
        }
        append(']').append(',')
        appendCanonicalString(content)
        append(']')
    }

    private fun StringBuilder.appendCanonicalString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"'  -> append("\\\"")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> {
                    if (c.code < 0x20) {
                        append("\\u").append("%04x".format(c.code))
                    } else {
                        append(c)
                    }
                }
            }
        }
        append('"')
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
