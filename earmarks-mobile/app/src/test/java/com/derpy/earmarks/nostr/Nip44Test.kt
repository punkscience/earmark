package com.derpy.earmarks.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-44 v2 cross-client conformance.
 *
 * Every fixture below was produced by `github.com/nbd-wtf/go-nostr/nip44` — the
 * implementation the CLI and derpy actually use. That is the contract that
 * matters: this Kotlin code is hand-written, and if it drifts from the Go side
 * the two clients silently stop being able to read each other.
 *
 * Round-trip tests cannot catch that. A bug that is symmetric within this file
 * passes a round trip happily. Only fixed bytes from the other implementation
 * fail, which is why these are pinned rather than computed.
 *
 * Regenerate with the fixture program in the channels work if go-nostr's
 * behaviour ever legitimately changes — and if it does, that is a protocol
 * change and `docs/PROTOCOL.md` needs updating too.
 */
class Nip44Test {

    private companion object {
        const val ALICE_SEC = "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268"
        const val ALICE_PUB = "6f7a47f239d292295f75afa6d672082ef722a114ddaf18fd682e8d3bde7aa227"
        const val BOB_SEC = "a1e37752c9fdc1273be53f68c5f74be7c8905728e8de75800b94262f9497c86e"
        const val BOB_PUB = "89174fc252d16ee01474b0685550a19578cf2ce18359637a181a5b9941b9c627"
        const val EVE_SEC = "98a5902fd67518a0c900f0fb62158f278f94a21d6f9d33d30cd3091195500311"
        const val EVE_PUB = "0ffeb0adb35bb834928dd06f9fb2cf21df5c0fc4db5cc4e34f23f180099eb740"

        const val ALICE_BOB_KEY = "b3b7c96865ddd626a598b87e8945e6a0febf6757d1b3be77d078c7bbe9c7d48c"
        const val ALICE_EVE_KEY = "ee425aed9b39217532e50fe109f1ecc244021a73f14dc81434a6707414ed68e6"
        const val BOB_EVE_KEY = "c9f6c60dfc5b3a98e44da450c582a78f9edda1189abd90a1ee182cc66311fa9d"
        const val ALICE_SELF_KEY = "c2074749067fdb66049890b1cddb26dfe4ab8e98f18753242bcd0c63a5fe0439"
    }

    // --- Public key derivation ----------------------------------------------

    @Test
    fun derivesTheSamePubkeysAsGo() {
        assertEquals(ALICE_PUB, Nip44.derivePubKeyHex(ALICE_SEC))
        assertEquals(BOB_PUB, Nip44.derivePubKeyHex(BOB_SEC))
        assertEquals(EVE_PUB, Nip44.derivePubKeyHex(EVE_SEC))
    }

    // --- Conversation keys --------------------------------------------------

    @Test
    fun conversationKeysMatchGo() {
        assertEquals(ALICE_BOB_KEY, Nip44.conversationKey(ALICE_SEC, BOB_PUB).toHex())
        assertEquals(ALICE_EVE_KEY, Nip44.conversationKey(ALICE_SEC, EVE_PUB).toHex())
        assertEquals(BOB_EVE_KEY, Nip44.conversationKey(BOB_SEC, EVE_PUB).toHex())
    }

    /**
     * Both ends must derive the same key. If this breaks, nothing sent between
     * two people can ever be read.
     */
    @Test
    fun conversationKeyIsSymmetric() {
        assertEquals(
            Nip44.conversationKey(ALICE_SEC, BOB_PUB).toHex(),
            Nip44.conversationKey(BOB_SEC, ALICE_PUB).toHex()
        )
        assertEquals(ALICE_BOB_KEY, Nip44.conversationKey(BOB_SEC, ALICE_PUB).toHex())
    }

    /**
     * Self-encryption is the peer case with your own pubkey — and it must still
     * produce the key the existing earmark list was encrypted under, or an app
     * upgrade stops being able to read its own data.
     */
    @Test
    fun selfConversationKeyMatchesGo() {
        assertEquals(ALICE_SELF_KEY, Nip44.conversationKey(ALICE_SEC, ALICE_PUB).toHex())
    }

    // --- Decrypting payloads produced by Go ---------------------------------

    @Test
    fun decryptsShortPayloadFromGo() {
        val payload = "AnY8DsmOudlg5R5Lhehbkhn+kaLuEoUswD5FjMP/ZVcw6p+wDhgZsmlbelXjftu1" +
            "wuk9uXTgjsbNgNsU8K+m9JIXSwBhgDf7RHyAmLylJEmwTSapcy9Y/Ixmo+M+2spIP8xD"
        assertEquals("a", Nip44.decryptFrom(BOB_SEC, ALICE_PUB, payload))
    }

    @Test
    fun decryptsChannelEnvelopeFromGo() {
        val payload = "AvBTvC4kVU5/oXDJB6BLYdQLZW3O0nZ8VNt3QVFUejWkMp9joQwD1R72i4p6uiJc" +
            "XFmn1qysi2NIYkdpGT25Yl7Lw3xlhybbjqfK81Zeu2AsvwYX7i5tK8WKDAwz7QenVeFW" +
            "px4oabKgb1HQPP7y/5lsC5tsGbSvEa/RPlhlbpl+TuI="
        assertEquals(
            """{"v":1,"chan":"deadbeef","type":"post"}""",
            Nip44.decryptFrom(BOB_SEC, ALICE_PUB, payload)
        )
    }

    /** Longer than one padding block, so it exercises the multi-block path. */
    @Test
    fun decryptsLongPayloadFromGo() {
        val payload = "AsD7adwmCOSq9v4PM/fXLOBAM59URtslwCEPFiL3uBd5NE3bN6pDsj+XqB4XkQsg" +
            "0woJY7YBd1ayue8NbmrWgT6E9ERLYnyUNVIDtGNdt9RTiKkvM9/ULVBlv1n0XiFtBAYI" +
            "05oYYXpOn+odChsNCqbVz8WVSDRczOCj66BPhevd0MvOAsnCdT6y/ar00Kp/iem9rDFq" +
            "SoKhCP3vd/xcDCFQBqsVZK3VSp35T98DRcArbeSEA94zLqvxFkG6i2P1Mqpn"
        assertEquals(
            "a much longer message that will cross the 32 byte padding boundary " +
                "and then some more text to push it further",
            Nip44.decryptFrom(BOB_SEC, ALICE_PUB, payload)
        )
    }

    @Test
    fun decryptsByConversationKeyDirectly() {
        val payload = "AnY8DsmOudlg5R5Lhehbkhn+kaLuEoUswD5FjMP/ZVcw6p+wDhgZsmlbelXjftu1" +
            "wuk9uXTgjsbNgNsU8K+m9JIXSwBhgDf7RHyAmLylJEmwTSapcy9Y/Ixmo+M+2spIP8xD"
        assertEquals("a", Nip44.decryptWithKey(ALICE_BOB_KEY.hexToBytes(), payload))
    }

    // --- Round trips --------------------------------------------------------

    @Test
    fun peerRoundTrip() {
        val message = """{"v":1,"chan":"abc","type":"post"}"""
        val sealed = Nip44.encryptTo(ALICE_SEC, BOB_PUB, message)
        assertEquals(message, Nip44.decryptFrom(BOB_SEC, ALICE_PUB, sealed))
    }

    /**
     * The entire existing earmark list is self-encrypted. A regression here
     * loses everyone's data, so it is asserted separately from the peer path.
     */
    @Test
    fun selfEncryptionStillRoundTrips() {
        val message = """[{"artist":"Coltrane","title":"Resolution","ts":1712345678}]"""
        assertEquals(message, Nip44.decrypt(ALICE_SEC, Nip44.encrypt(ALICE_SEC, message)))
    }

    @Test
    fun selfEncryptionIsThePeerCaseWithOwnPubkey() {
        val message = "same key both ways"
        assertEquals(message, Nip44.decryptFrom(ALICE_SEC, ALICE_PUB, Nip44.encrypt(ALICE_SEC, message)))
        assertEquals(message, Nip44.decrypt(ALICE_SEC, Nip44.encryptTo(ALICE_SEC, ALICE_PUB, message)))
    }

    // --- Negative cases -----------------------------------------------------

    @Test
    fun wrongRecipientCannotDecrypt() {
        val sealed = Nip44.encryptTo(ALICE_SEC, BOB_PUB, "not for you")
        assertTrue(
            "a third party decrypted a message addressed to someone else",
            failsToDecrypt { Nip44.decryptFrom(EVE_SEC, ALICE_PUB, sealed) }
        )
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val sealed = Nip44.encrypt(ALICE_SEC, "authentic")
        // Flip a byte in the middle of the ciphertext, leaving the framing intact.
        val chars = sealed.toCharArray()
        val i = chars.size / 2
        chars[i] = if (chars[i] == 'A') 'B' else 'A'
        assertTrue(
            "a tampered payload was accepted",
            failsToDecrypt { Nip44.decrypt(ALICE_SEC, String(chars)) }
        )
    }

    @Test
    fun unsupportedVersionIsRejected() {
        // Version byte 0x01 instead of 0x02.
        val v1 = java.util.Base64.getEncoder()
            .encodeToString(byteArrayOf(0x01) + ByteArray(96))
        assertTrue(failsToDecrypt { Nip44.decrypt(ALICE_SEC, v1) })
    }

    // --- Properties ---------------------------------------------------------

    /** Two encryptions of the same text must differ — the nonce must be random. */
    @Test
    fun noncesAreRandom() {
        assertNotEquals(Nip44.encrypt(ALICE_SEC, "same"), Nip44.encrypt(ALICE_SEC, "same"))
    }

    /**
     * Padding hides length within a bucket: everything up to 32 bytes encrypts
     * to the same size. It does not hide length across buckets — a 33-byte
     * message legitimately lands in the 64-byte bucket — so this asserts the
     * property the scheme actually provides.
     */
    @Test
    fun messagesWithinAPaddingBucketAreIndistinguishableByLength() {
        val a = Nip44.encrypt(ALICE_SEC, "a").length
        val b = Nip44.encrypt(ALICE_SEC, "thirty-two bytes or fewer, ok").length
        assertEquals("padding is not hiding plaintext length within a bucket", a, b)

        val big = Nip44.encrypt(ALICE_SEC, "a".repeat(33)).length
        assertTrue("crossing a padding bucket should grow the payload", big > a)
    }

    private fun failsToDecrypt(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (e: Exception) {
        true
    }
}
