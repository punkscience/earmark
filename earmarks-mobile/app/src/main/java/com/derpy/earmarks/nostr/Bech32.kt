package com.derpy.earmarks.nostr

/**
 * Minimal bech32 decoder for nsec1 private keys.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val CHARSET_MAP = CHARSET.withIndex().associate { it.value to it.index }

    /** Decodes an nsec1 bech32 string to a 32-byte raw private key. */
    fun decodeNsec(nsec: String): ByteArray {
        val lower = nsec.lowercase()
        require(lower.startsWith("nsec1")) { "Not an nsec key" }
        val sep = lower.lastIndexOf('1')
        val dataStr = lower.substring(sep + 1)
        val data5 = dataStr.map { CHARSET_MAP[it] ?: error("Invalid bech32 char: $it") }.toIntArray()
        require(verifyChecksum("nsec", data5)) { "Invalid nsec checksum" }
        // Strip last 6 checksum characters, convert 5-bit groups to bytes
        val payload = convertBits(data5.copyOf(data5.size - 6), 5, 8, false)
        require(payload.size == 32) { "Expected 32-byte key, got ${payload.size}" }
        return payload
    }

    private fun polymod(values: IntArray): Long {
        val gen = longArrayOf(0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L)
        var chk = 1L
        for (v in values) {
            val top = chk ushr 25
            chk = (chk and 0x1ffffff) shl 5 xor v.toLong()
            for (i in 0..4) if (top ushr i and 1L != 0L) chk = chk xor gen[i]
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val result = IntArray(hrp.length * 2 + 1)
        for (i in hrp.indices) result[i] = hrp[i].code ushr 5
        result[hrp.length] = 0
        for (i in hrp.indices) result[hrp.length + 1 + i] = hrp[i].code and 31
        return result
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean =
        polymod(hrpExpand(hrp) + data) == 1L

    private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): ByteArray {
        var acc = 0
        var bits = 0
        val result = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                result.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad && bits > 0) result.add(((acc shl (toBits - bits)) and maxv).toByte())
        return result.toByteArray()
    }
}
