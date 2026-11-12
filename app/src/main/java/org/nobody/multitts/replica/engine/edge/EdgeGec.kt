package org.nobody.multitts.replica.engine.edge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Microsoft Edge Read Aloud 的 Sec-MS-GEC v2 令牌。
 *
 * 算法来自开源 edge-tts 的 drm.py；它是对 SHA-256 与一段自定义 CS64 混合函数的
 * 兼容实现，不能简单替换成普通 SHA-256。
 */
object EdgeGec {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val SEC_MS_GEC_VERSION = "2-155.0.4283.45"

    @Volatile
    var clockSkewSeconds: Double = 0.0

    fun token(unixTimestamp: Double = currentUnixTimestamp()): String {
        var ticks = unixTimestamp + WINDOWS_EPOCH_SECONDS
        ticks -= ticks % TOKEN_WINDOW_SECONDS
        ticks *= 10_000_000.0
        val data = "${ticks.toLong()}$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (cs64A, cs64B) = cs64Digest(data, digest)
        val cs64 = hexUppercase(cs64A) + hexUppercase(cs64B)
        return "$cs64-${digest.toHexUpperCase()}"
    }

    fun currentUnixTimestamp(): Double =
        System.currentTimeMillis() / 1000.0 + clockSkewSeconds

    fun adjustClockFromServerDate(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val server = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
        }.getOrNull() ?: return false
        val client = currentUnixTimestamp()
        clockSkewSeconds += server - client
        return true
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val padding = ((55 - length) % 64 + 64) % 64
        val padded = ByteArray(data.size - 7 + 1 + padding + 8)
        data.copyInto(padded, 0, 7, data.size)
        padded[data.size - 7] = 0x80.toByte()
        val bitLength = length.toLong() * 8
        for (index in 0 until 8) {
            padded[padded.size - 1 - index] = (bitLength ushr (index * 8)).toByte()
        }

        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = IntArray(64)
            for (index in 0 until 16) {
                val pos = offset + index * 4
                words[index] = ((padded[pos].toInt() and 0xFF) shl 24) or
                    ((padded[pos + 1].toInt() and 0xFF) shl 16) or
                    ((padded[pos + 2].toInt() and 0xFF) shl 8) or
                    (padded[pos + 3].toInt() and 0xFF)
            }
            expandSha256Schedule(words, 16)
            val working = sha256Rounds(state, words)
            state = IntArray(8) { (state[it] + working[it]) }
            offset += 64
        }
        return ByteArray(32).also { out ->
            for (index in 0 until 8) {
                val value = state[index]
                out[index * 4] = (value ushr 24).toByte()
                out[index * 4 + 1] = (value ushr 16).toByte()
                out[index * 4 + 2] = (value ushr 8).toByte()
                out[index * 4 + 3] = value.toByte()
            }
        }
    }

    private fun sha256Init(data: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] = ((data[0].toInt() and 0xFF) shl 16) or
            ((data[1].toInt() and 0xFF) shl 8) or
            (data[2].toInt() and 0xFF)
        words[15] = ((data[3].toInt() and 0xFF) shl 24) or
            ((data[4].toInt() and 0xFF) shl 16) or
            ((data[5].toInt() and 0xFF) shl 8) or
            (data[6].toInt() and 0xFF)
        for (index in 16..29) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) {
                    smallSigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                } else {
                    smallSigma1(words[index - 2])
                }
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) {
                    smallSigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                } else {
                    smallSigma0(words[index - 15])
                }
            }
            words[index] = value
        }
        words[30] = smallSigma1(words[28]) + words[23] + smallSigma0(words[15]) +
            words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
        expandSha256Schedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, firstRound = 14)
        return IntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    private fun sha256Rounds(
        initial: IntArray,
        words: IntArray,
        firstRound: Int = 0,
    ): IntArray {
        var a = initial[0]
        var b = initial[1]
        var c = initial[2]
        var d = initial[3]
        var e = initial[4]
        var f = initial[5]
        var g = initial[6]
        var h = initial[7]
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        for (index in firstRound until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = g xor (e and (f xor g))
            val temp1 = h + s1 + ch + constants[index] + words[index]
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) or (c and (a or b))
            val temp2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSha256Schedule(words: IntArray, firstWord: Int) {
        for (index in firstWord until 64) {
            words[index] = smallSigma1(words[index - 2]) + words[index - 7] +
                smallSigma0(words[index - 15]) + words[index - 16]
        }
    }

    private fun smallSigma0(value: Int): Int =
        (value ushr 7) xor (value shl 25) xor (value ushr 18) xor
            (value shl 14) xor (value ushr 3)

    private fun smallSigma1(value: Int): Int =
        (value ushr 17) xor (value shl 15) xor (value ushr 19) xor
            (value shl 13) xor (value ushr 10)

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
        val keyA = readLeInt(digest, 0) or 1
        val keyB = readLeInt(digest, 4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)
        val terms = IntArray(14 + wordsLe4Even(byteArrayOf(0) + data).size)
        val offsets = IntArray(terms.size)
        CS64_COEFFICIENTS.forEachIndexed { index, pair ->
            terms[index] = pair[0] * factors[index % 2]
            offsets[index] = pair[1]
        }
        val words = wordsLe4Even(byteArrayOf(0) + data)
        words.forEachIndexed { index, word ->
            val termIndex = 14 + index
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt()
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms[termIndex] = term
            offsets[termIndex] = offset
        }

        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16(offsets[index] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q

            t = rol16(offsets[index + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9.toInt() + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state

            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
            index += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    private fun wordsLe4Even(data: ByteArray): IntArray {
        val paddedSize = ((data.size + 3) / 4) * 4
        val padded = data.copyOf(paddedSize)
        val words = IntArray(paddedSize / 4)
        ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words)
        return if (words.size % 2 == 0) words else words + 0
    }

    private fun readLeInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    private fun rol16(value: Int): Int = (value shl 16) or (value ushr 16)

    private fun rotr(value: Int, bits: Int): Int = (value ushr bits) or (value shl (32 - bits))

    private fun hexUppercase(value: Int): String = buildString(8) {
        for (index in 0 until 4) {
            append(String.format(Locale.ROOT, "%02X", (value ushr (index * 8)) and 0xFF))
        }
    }

    private fun ByteArray.toHexUpperCase(): String = joinToString("") {
        String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)
    }

    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600.0
    private const val TOKEN_WINDOW_SECONDS = 300.0

    private val SHA256_INITIAL = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SHA256_STATE = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2,
    )
    private val SHA256_SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA,
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060.toInt(), 0x72766564,
    )
    private val SHA256_ROUND_CONSTANTS = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
    private val SHA256_BLOCK_CONSTANTS = IntArray(64) { index ->
        if (index == 14) SHA256_ADJUSTMENT else SHA256_ROUND_CONSTANTS[index]
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0

    private val CS64_COEFFICIENTS = arrayOf(
        intArrayOf(0xE82E91B1.toInt(), 0xB32700D0.toInt()),
        intArrayOf(0x44DE24C6, 0x88DFF51D.toInt()),
        intArrayOf(0x3BEB65BC, 0x29514038),
        intArrayOf(0xC89C5933.toInt(), 0xB328CBDF.toInt()),
        intArrayOf(0x8D1EC211.toInt(), 0xD405B4D5.toInt()),
        intArrayOf(0x314EF5D5, 0x26E55F3C),
        intArrayOf(0xE92491B1.toInt(), 0x607801C6),
        intArrayOf(0xF0A5B9D5.toInt(), 0x82B2C1FB.toInt()),
        intArrayOf(0x06064CF3, 0xFA51A3DA.toInt()),
        intArrayOf(0xDA79FA60.toInt(), 0x207CC464),
        intArrayOf(0xBED20560.toInt(), 0x3C3C74F3),
        intArrayOf(0x89F9C9F8.toInt(), 0xD2D14B61.toInt()),
        intArrayOf(0x35FA38C6, 0xBB04A2C1.toInt()),
        intArrayOf(0xCB49E3C6.toInt(), 0xC5626167.toInt()),
    )
    private const val CS64_FACTOR = 0x82893933.toInt()
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000
}
