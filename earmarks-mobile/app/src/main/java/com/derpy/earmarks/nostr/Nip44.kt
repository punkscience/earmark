package com.derpy.earmarks.nostr

import android.util.Base64
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-44 v2 decryption.
 *
 * Spec: https://github.com/nostr-protocol/nips/blob/master/44.md
 *
 * Key derivation (this is NOT standard HKDF — it is split into Extract-only and Expand-only):
 *   conversation_key = HKDF-Extract(salt="nip44-v2", IKM=shared_x)   ← Extract only
 *   message_keys     = HKDF-Expand(PRK=conversation_key, info=nonce, L=76)  ← Expand only
 *
 * For derpy earmarks the sender and recipient are the same key (self-encryption).
 */
object Nip44 {
    private val CURVE_PARAMS = SECNamedCurves.getByName("secp256k1")
    private val DOMAIN = ECDomainParameters(
        CURVE_PARAMS.curve, CURVE_PARAMS.g, CURVE_PARAMS.n, CURVE_PARAMS.h
    )

    /**
     * Decrypts a NIP-44 v2 encrypted event content using self-encryption
     * (sender == recipient, both use [privKeyHex]).
     */
    fun decrypt(privKeyHex: String, encryptedContent: String): String {
        val payload = Base64.decode(encryptedContent, Base64.DEFAULT)
        require(payload.isNotEmpty() && payload[0] == 0x02.toByte()) {
            "Unsupported NIP-44 version (expected 0x02)"
        }
        require(payload.size >= 1 + 32 + 32) { "NIP-44 payload too short" }

        // Layout: [0x02][nonce 32][ciphertext][mac 32]
        val nonce32 = payload.copyOfRange(1, 33)
        val mac = payload.copyOfRange(payload.size - 32, payload.size)
        val ciphertext = payload.copyOfRange(33, payload.size - 32)

        // conversation_key = HKDF-Extract(salt="nip44-v2", IKM=shared_x)
        val conversationKey = deriveConversationKey(privKeyHex)

        // message_keys = HKDF-Expand(PRK=conversation_key, info=nonce, L=76)
        val messageKeys = hkdfExpand(conversationKey, nonce32, 76)
        val chachaKey = messageKeys.copyOfRange(0, 32)
        val chachaNonce = messageKeys.copyOfRange(32, 44)
        val hmacKey = messageKeys.copyOfRange(44, 76)

        // MAC = HMAC-SHA256(key=hmacKey, data=nonce32 || ciphertext)
        val expectedMac = hmacSha256(hmacKey, nonce32 + ciphertext)
        require(mac.contentEquals(expectedMac)) {
            "NIP-44 MAC verification failed — wrong key or corrupt data"
        }

        val decrypted = chacha20(chachaKey, chachaNonce, ciphertext)

        // Unpad: first 2 bytes are big-endian uint16 plaintext length
        require(decrypted.size >= 2) { "Decrypted content too short to unpad" }
        val plaintextLen = ((decrypted[0].toInt() and 0xFF) shl 8) or (decrypted[1].toInt() and 0xFF)
        require(2 + plaintextLen <= decrypted.size) { "NIP-44 padding: length out of bounds" }
        return String(decrypted, 2, plaintextLen, Charsets.UTF_8)
    }

    /**
     * Derives the public key hex (x-coordinate only, BIP-340) from a private key hex string.
     */
    fun derivePubKeyHex(privKeyHex: String): String {
        val priv = BigInteger(1, privKeyHex.hexToBytes())
        val pubPoint = CURVE_PARAMS.g.multiply(priv).normalize()
        return pubPoint.xCoord.encoded.toHex()
    }

    /**
     * NIP-44 v2 self-encryption. Encrypts [plaintext] using the user's own
     * private key as both sender and recipient.
     */
    fun encrypt(privKeyHex: String, plaintext: String): String {
        val conversationKey = deriveConversationKey(privKeyHex)
        val nonce32 = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val messageKeys = hkdfExpand(conversationKey, nonce32, 76)
        val chachaKey = messageKeys.copyOfRange(0, 32)
        val chachaNonce = messageKeys.copyOfRange(32, 44)
        val hmacKey = messageKeys.copyOfRange(44, 76)

        val padded = pad(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertext = chacha20(chachaKey, chachaNonce, padded)
        val mac = hmacSha256(hmacKey, nonce32 + ciphertext)

        // Layout: [0x02][nonce 32][ciphertext][mac 32]
        val payload = ByteArray(1 + 32 + ciphertext.size + 32)
        payload[0] = 0x02
        System.arraycopy(nonce32, 0, payload, 1, 32)
        System.arraycopy(ciphertext, 0, payload, 33, ciphertext.size)
        System.arraycopy(mac, 0, payload, 33 + ciphertext.size, 32)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /**
     * NIP-44 v2 padding scheme.
     * Layout: [2-byte BE plaintext length][plaintext][zero pad to padded_len]
     */
    private fun pad(plaintext: ByteArray): ByteArray {
        val unpaddedLen = plaintext.size
        require(unpaddedLen in 1..65535) { "plaintext must be 1..65535 bytes" }
        val paddedLen = calcPaddedLen(unpaddedLen)
        val out = ByteArray(2 + paddedLen)
        out[0] = ((unpaddedLen ushr 8) and 0xFF).toByte()
        out[1] = (unpaddedLen and 0xFF).toByte()
        System.arraycopy(plaintext, 0, out, 2, unpaddedLen)
        return out
    }

    private fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(unpaddedLen - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * (((unpaddedLen - 1) / chunk) + 1)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun deriveConversationKey(privKeyHex: String): ByteArray {
        val privBigInt = BigInteger(1, privKeyHex.hexToBytes())
        val pubPoint = CURVE_PARAMS.g.multiply(privBigInt).normalize()
        val compressedPub = pubPoint.getEncoded(true)  // 33 bytes

        val privParam = ECPrivateKeyParameters(privBigInt, DOMAIN)
        val pubParam = ECPublicKeyParameters(CURVE_PARAMS.curve.decodePoint(compressedPub), DOMAIN)

        val agreement = ECDHBasicAgreement()
        agreement.init(privParam)
        val sharedBigInt = agreement.calculateAgreement(pubParam)

        // Normalise to exactly 32 bytes (big-endian, zero-padded on the left)
        val sharedBytes = sharedBigInt.toByteArray()
        val sharedX = ByteArray(32)
        val srcOff = maxOf(0, sharedBytes.size - 32)
        val dstOff = maxOf(0, 32 - sharedBytes.size)
        System.arraycopy(sharedBytes, srcOff, sharedX, dstOff, minOf(32, sharedBytes.size))

        // conversation_key = HKDF-Extract(salt="nip44-v2", IKM=shared_x)
        //                  = HMAC-SHA256(key="nip44-v2", data=shared_x)
        return hkdfExtract("nip44-v2".toByteArray(Charsets.UTF_8), sharedX)
    }

    /**
     * HKDF-Extract: PRK = HMAC-SHA256(key=salt, data=ikm)
     */
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmacSha256(salt, ikm)

    /**
     * HKDF-Expand: iterative HMAC to produce [length] bytes.
     *   T(0) = empty
     *   T(n) = HMAC-SHA256(PRK, T(n-1) || info || n)
     *   OKM  = T(1) || T(2) || ...
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        var t = ByteArray(0)
        var counter = 1
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun chacha20(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        // ChaCha20 stream cipher: encrypt and decrypt are the same XOR operation.
        val engine = ChaCha7539Engine()  // IETF ChaCha20, 96-bit (12-byte) nonce
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        return ByteArray(input.size).also { out ->
            engine.processBytes(input, 0, input.size, out, 0)
        }
    }
}

// ── Extension helpers ─────────────────────────────────────────────────────────

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Odd hex string length" }
    return ByteArray(length / 2) { i -> Integer.parseInt(substring(i * 2, i * 2 + 2), 16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
