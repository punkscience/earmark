package com.derpy.earmarks.nostr

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest

/**
 * BIP-340 Schnorr signatures over secp256k1.
 *
 * Spec: https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki
 *
 * Used to sign Nostr events (NIP-01) and Blossom HTTP-auth events (BUD-01).
 */
object Schnorr {
    private val CURVE = SECNamedCurves.getByName("secp256k1")
    private val N: BigInteger = CURVE.n

    /**
     * Signs [msg] (a 32-byte message hash) with the private key [skBytes] (32 bytes).
     * Returns the 64-byte BIP-340 signature.
     */
    fun sign(skBytes: ByteArray, msg: ByteArray, auxRand: ByteArray = ByteArray(32)): ByteArray {
        require(skBytes.size == 32) { "Private key must be 32 bytes" }
        require(msg.size == 32) { "Message must be 32 bytes" }
        require(auxRand.size == 32) { "Aux rand must be 32 bytes" }

        val dPrime = BigInteger(1, skBytes)
        require(dPrime > BigInteger.ZERO && dPrime < N) { "Invalid private key" }

        val pPoint = CURVE.g.multiply(dPrime).normalize()
        val d = if (hasEvenY(pPoint)) dPrime else N.subtract(dPrime)

        val pxBytes = bytes32(pPoint.xCoord.toBigInteger())

        val t = xor(bytes32(d), taggedHash("BIP0340/aux", auxRand))
        val rand = taggedHash("BIP0340/nonce", t + pxBytes + msg)
        val kPrime = BigInteger(1, rand).mod(N)
        require(kPrime != BigInteger.ZERO) { "k' is zero" }

        val rPoint = CURVE.g.multiply(kPrime).normalize()
        val k = if (hasEvenY(rPoint)) kPrime else N.subtract(kPrime)

        val rxBytes = bytes32(rPoint.xCoord.toBigInteger())
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rxBytes + pxBytes + msg)).mod(N)

        val s = k.add(e.multiply(d)).mod(N)
        return rxBytes + bytes32(s)
    }

    /**
     * Returns the x-only public key (32 bytes) for [skBytes].
     */
    fun xOnlyPubKey(skBytes: ByteArray): ByteArray {
        val d = BigInteger(1, skBytes)
        val p = CURVE.g.multiply(d).normalize()
        return bytes32(p.xCoord.toBigInteger())
    }

    private fun hasEvenY(point: ECPoint): Boolean =
        point.yCoord.toBigInteger().mod(BigInteger.TWO) == BigInteger.ZERO

    private fun bytes32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        if (b.size == 32) return b
        if (b.size == 33 && b[0] == 0.toByte()) return b.copyOfRange(1, 33)
        val out = ByteArray(32)
        val src = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
        System.arraycopy(src, 0, out, 32 - src.size, src.size)
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val tagHash = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(tagHash + tagHash + msg)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
