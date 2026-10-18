package com.yunyin.music.data.providers.tx

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * QQ Music QRC lyric decryption: a **non-standard 3DES (EDE3, ECB, no padding) + zlib inflate**.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0),
 * `src/utils/musicSdk/tx/qrcDecode.js` — which is itself a 1:1 translation of the original C++ native plugin
 * (`qrc_decode.node`). The algorithm, key, permutations, S-boxes and the bit-sliced DES structure are reproduced
 * faithfully; this file adds no behaviour of its own.
 *
 * ## Why the platform DES is not usable here
 *
 * QRC's DES is deliberately **not standard DES**: `S2[23] = 15` and `S4[53] = 10`, where standard DES has `14`
 * and `1` (the reference implementation documents this; both values match the native binary). Feeding a QRC
 * payload to `javax.crypto` would decrypt to plausible-looking garbage and fail the inflate. The whole cipher is
 * therefore ported by hand.
 *
 * ## Bit arithmetic
 *
 * The reference is JavaScript, where every operation is done on 32-bit numbers with `>>> 0` normalisation and
 * unsigned shifts. The port uses `Long` masked to 32 bits ([u32]) at exactly the points the reference normalises,
 * and `ushr` for its `>>>`, so a shift cannot leak sign bits. The tables are copied verbatim.
 *
 * `decode()` never throws: a malformed payload returns an empty string, matching the reference's `catch → ''`, so a
 * bad lyric cannot break playback.
 */
internal object QrcDecoder {

    private const val DES_ENCRYPT = 1
    private const val DES_DECRYPT = 0

    private const val MASK_32 = 0xFFFF_FFFFL

    /** `>>> 0` in JavaScript. */
    private fun Long.u32(): Long = this and MASK_32

    /**
     * QRC's DES S-boxes, copied verbatim — including the two non-standard entries noted in the reference
     * (`sbox[1][23] = 15`, `sbox[3][53] = 10`). Changing them to standard DES values breaks decryption.
     */
    private val SBOX: Array<IntArray> = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35,
    )
    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3,
    )
    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46,
        54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    /** QRC's fixed key: `!@#)(*$%123ZXC!@!@#)(NHL`, byte for byte as in the reference. */
    private val QRC_KEY: ByteArray = byteArrayOf(
        0x21, 0x40, 0x23, 0x29, 0x28, 0x2a, 0x24, 0x25, 0x31, 0x32, 0x33, 0x5a,
        0x58, 0x43, 0x21, 0x40, 0x21, 0x40, 0x23, 0x29, 0x28, 0x4e, 0x48, 0x4c,
    )

    /**
     * Hex string → decrypted, inflated UTF-8 lyric text.
     *
     * Empty string for empty/odd-length input, a failed decrypt, or undecodable output — exactly the reference's
     * contract, so callers only have to check for blank.
     */
    fun decode(hexData: String?): String {
        val hex = hexData?.trim().orEmpty()
        if (hex.isEmpty() || hex.length % 2 != 0) return ""
        val encrypted = hexToBytes(hex) ?: return ""
        if (encrypted.isEmpty()) return ""

        val schedule = tripleDesKeySetup(QRC_KEY, DES_DECRYPT)
        val decrypted = ByteArray(encrypted.size)
        val block = ByteArray(8)
        var offset = 0
        while (offset + 8 <= encrypted.size) {
            tripleDesCrypt(encrypted.copyOfRange(offset, offset + 8), schedule, block)
            block.copyInto(decrypted, offset)
            offset += 8
        }
        // A trailing partial block is ignored, as in the reference (`i + 8 <= length`).
        return inflateToString(decrypted.trimZeros())
    }

    /** Trailing zero bytes only ever come from block padding; they are not part of the zlib stream. */
    private fun ByteArray.trimZeros(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return if (end == size) this else copyOf(end)
    }

    private fun inflateToString(data: ByteArray): String {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream(data.size * 3)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val read = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    break
                }
                if (read == 0) break
                output.write(buffer, 0, read)
            }
            if (output.size() == 0) "" else output.toByteArray().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        } finally {
            runCatching { inflater.end() }
        }
    }

    private fun hexToBytes(hex: String): ByteArray? = try {
        ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    } catch (e: NumberFormatException) {
        null
    }

    // ---------------------------------------------------------------- bit helpers

    private fun bitnum(a: ByteArray, b: Int, c: Int): Long {
        val index = (b / 32) * 4 + 3 - ((b % 32) / 8)
        val value = (a[index].toInt() and 0xff).toLong()
        val bit = (value ushr (7 - (b % 8))) and 1L
        return ((bit shl c)).u32()
    }

    private fun bitnumIntr(a: Long, b: Int, c: Int): Long = (((a ushr (31 - b)) and 1L) shl c).u32()

    private fun bitnumIntl(a: Long, b: Int, c: Int): Long = ((a shl b) and 0x8000_0000L) ushr c

    private fun sboxBit(a: Long): Int = ((a and 32L) or ((a and 31L) ushr 1) or ((a and 1L) shl 4)).toInt()

    // ---------------------------------------------------------------- permutations

    private fun initialPermutation(input: ByteArray): Pair<Long, Long> {
        val s0 = (
            bitnum(input, 57, 31) or bitnum(input, 49, 30) or bitnum(input, 41, 29) or bitnum(input, 33, 28) or
                bitnum(input, 25, 27) or bitnum(input, 17, 26) or bitnum(input, 9, 25) or bitnum(input, 1, 24) or
                bitnum(input, 59, 23) or bitnum(input, 51, 22) or bitnum(input, 43, 21) or bitnum(input, 35, 20) or
                bitnum(input, 27, 19) or bitnum(input, 19, 18) or bitnum(input, 11, 17) or bitnum(input, 3, 16) or
                bitnum(input, 61, 15) or bitnum(input, 53, 14) or bitnum(input, 45, 13) or bitnum(input, 37, 12) or
                bitnum(input, 29, 11) or bitnum(input, 21, 10) or bitnum(input, 13, 9) or bitnum(input, 5, 8) or
                bitnum(input, 63, 7) or bitnum(input, 55, 6) or bitnum(input, 47, 5) or bitnum(input, 39, 4) or
                bitnum(input, 31, 3) or bitnum(input, 23, 2) or bitnum(input, 15, 1) or bitnum(input, 7, 0)
            ).u32()
        val s1 = (
            bitnum(input, 56, 31) or bitnum(input, 48, 30) or bitnum(input, 40, 29) or bitnum(input, 32, 28) or
                bitnum(input, 24, 27) or bitnum(input, 16, 26) or bitnum(input, 8, 25) or bitnum(input, 0, 24) or
                bitnum(input, 58, 23) or bitnum(input, 50, 22) or bitnum(input, 42, 21) or bitnum(input, 34, 20) or
                bitnum(input, 26, 19) or bitnum(input, 18, 18) or bitnum(input, 10, 17) or bitnum(input, 2, 16) or
                bitnum(input, 60, 15) or bitnum(input, 52, 14) or bitnum(input, 44, 13) or bitnum(input, 36, 12) or
                bitnum(input, 28, 11) or bitnum(input, 20, 10) or bitnum(input, 12, 9) or bitnum(input, 4, 8) or
                bitnum(input, 62, 7) or bitnum(input, 54, 6) or bitnum(input, 46, 5) or bitnum(input, 38, 4) or
                bitnum(input, 30, 3) or bitnum(input, 22, 2) or bitnum(input, 14, 1) or bitnum(input, 6, 0)
            ).u32()
        return s0 to s1
    }

    private fun inversePermutation(s0: Long, s1: Long, out: ByteArray) {
        fun byteOf(vararg parts: Long): Byte = (parts.fold(0L) { acc, v -> acc or v } and 0xff).toByte()
        out[3] = byteOf(
            bitnumIntr(s1, 7, 7), bitnumIntr(s0, 7, 6), bitnumIntr(s1, 15, 5), bitnumIntr(s0, 15, 4),
            bitnumIntr(s1, 23, 3), bitnumIntr(s0, 23, 2), bitnumIntr(s1, 31, 1), bitnumIntr(s0, 31, 0),
        )
        out[2] = byteOf(
            bitnumIntr(s1, 6, 7), bitnumIntr(s0, 6, 6), bitnumIntr(s1, 14, 5), bitnumIntr(s0, 14, 4),
            bitnumIntr(s1, 22, 3), bitnumIntr(s0, 22, 2), bitnumIntr(s1, 30, 1), bitnumIntr(s0, 30, 0),
        )
        out[1] = byteOf(
            bitnumIntr(s1, 5, 7), bitnumIntr(s0, 5, 6), bitnumIntr(s1, 13, 5), bitnumIntr(s0, 13, 4),
            bitnumIntr(s1, 21, 3), bitnumIntr(s0, 21, 2), bitnumIntr(s1, 29, 1), bitnumIntr(s0, 29, 0),
        )
        out[0] = byteOf(
            bitnumIntr(s1, 4, 7), bitnumIntr(s0, 4, 6), bitnumIntr(s1, 12, 5), bitnumIntr(s0, 12, 4),
            bitnumIntr(s1, 20, 3), bitnumIntr(s0, 20, 2), bitnumIntr(s1, 28, 1), bitnumIntr(s0, 28, 0),
        )
        out[7] = byteOf(
            bitnumIntr(s1, 3, 7), bitnumIntr(s0, 3, 6), bitnumIntr(s1, 11, 5), bitnumIntr(s0, 11, 4),
            bitnumIntr(s1, 19, 3), bitnumIntr(s0, 19, 2), bitnumIntr(s1, 27, 1), bitnumIntr(s0, 27, 0),
        )
        out[6] = byteOf(
            bitnumIntr(s1, 2, 7), bitnumIntr(s0, 2, 6), bitnumIntr(s1, 10, 5), bitnumIntr(s0, 10, 4),
            bitnumIntr(s1, 18, 3), bitnumIntr(s0, 18, 2), bitnumIntr(s1, 26, 1), bitnumIntr(s0, 26, 0),
        )
        out[5] = byteOf(
            bitnumIntr(s1, 1, 7), bitnumIntr(s0, 1, 6), bitnumIntr(s1, 9, 5), bitnumIntr(s0, 9, 4),
            bitnumIntr(s1, 17, 3), bitnumIntr(s0, 17, 2), bitnumIntr(s1, 25, 1), bitnumIntr(s0, 25, 0),
        )
        out[4] = byteOf(
            bitnumIntr(s1, 0, 7), bitnumIntr(s0, 0, 6), bitnumIntr(s1, 8, 5), bitnumIntr(s0, 8, 4),
            bitnumIntr(s1, 16, 3), bitnumIntr(s0, 16, 2), bitnumIntr(s1, 24, 1), bitnumIntr(s0, 24, 0),
        )
    }

    /** The Feistel round function, including the reference's bit-scrambled first/last stages. */
    private fun desF(state: Long, key: ByteArray): Long {
        val t1 = (
            bitnumIntl(state, 31, 0) or ((state and 0xF000_0000L) ushr 1) or bitnumIntl(state, 4, 5) or
                bitnumIntl(state, 3, 6) or ((state and 0x0F00_0000L) ushr 3) or bitnumIntl(state, 8, 11) or
                bitnumIntl(state, 7, 12) or ((state and 0x00F0_0000L) ushr 5) or bitnumIntl(state, 12, 17) or
                bitnumIntl(state, 11, 18) or ((state and 0x000F_0000L) ushr 7) or bitnumIntl(state, 16, 23)
            ).u32()
        val t2 = (
            bitnumIntl(state, 15, 0) or ((state and 0x0000_F000L) shl 15) or bitnumIntl(state, 20, 5) or
                bitnumIntl(state, 19, 6) or ((state and 0x0000_0F00L) shl 13) or bitnumIntl(state, 24, 11) or
                bitnumIntl(state, 23, 12) or ((state and 0x0000_00F0L) shl 11) or bitnumIntl(state, 28, 17) or
                bitnumIntl(state, 27, 18) or ((state and 0x0000_000FL) shl 9) or bitnumIntl(state, 0, 23)
            ).u32()

        val lrgstate = intArrayOf(
            ((t1 ushr 24) and 0xFF).toInt(), ((t1 ushr 16) and 0xFF).toInt(), ((t1 ushr 8) and 0xFF).toInt(),
            ((t2 ushr 24) and 0xFF).toInt(), ((t2 ushr 16) and 0xFF).toInt(), ((t2 ushr 8) and 0xFF).toInt(),
        )
        for (index in 0 until 6) lrgstate[index] = lrgstate[index] xor (key[index].toInt() and 0xff)

        val s = (
            (SBOX[0][sboxBit(lrgstate[0].toLong() ushr 2)].toLong() shl 28) or
                (SBOX[1][sboxBit((((lrgstate[0].toLong() and 0x03) shl 4) or (lrgstate[1].toLong() ushr 4)))].toLong() shl 24) or
                (SBOX[2][sboxBit((((lrgstate[1].toLong() and 0x0F) shl 2) or (lrgstate[2].toLong() ushr 6)))].toLong() shl 20) or
                (SBOX[3][sboxBit(lrgstate[2].toLong() and 0x3F)].toLong() shl 16) or
                (SBOX[4][sboxBit(lrgstate[3].toLong() ushr 2)].toLong() shl 12) or
                (SBOX[5][sboxBit((((lrgstate[3].toLong() and 0x03) shl 4) or (lrgstate[4].toLong() ushr 4)))].toLong() shl 8) or
                (SBOX[6][sboxBit((((lrgstate[4].toLong() and 0x0F) shl 2) or (lrgstate[5].toLong() ushr 6)))].toLong() shl 4) or
                SBOX[7][sboxBit(lrgstate[5].toLong() and 0x3F)].toLong()
            ).u32()

        return (
            bitnumIntl(s, 15, 0) or bitnumIntl(s, 6, 1) or bitnumIntl(s, 19, 2) or bitnumIntl(s, 20, 3) or
                bitnumIntl(s, 28, 4) or bitnumIntl(s, 11, 5) or bitnumIntl(s, 27, 6) or bitnumIntl(s, 16, 7) or
                bitnumIntl(s, 0, 8) or bitnumIntl(s, 14, 9) or bitnumIntl(s, 22, 10) or bitnumIntl(s, 25, 11) or
                bitnumIntl(s, 4, 12) or bitnumIntl(s, 17, 13) or bitnumIntl(s, 30, 14) or bitnumIntl(s, 9, 15) or
                bitnumIntl(s, 1, 16) or bitnumIntl(s, 7, 17) or bitnumIntl(s, 23, 18) or bitnumIntl(s, 13, 19) or
                bitnumIntl(s, 31, 20) or bitnumIntl(s, 26, 21) or bitnumIntl(s, 2, 22) or bitnumIntl(s, 8, 23) or
                bitnumIntl(s, 18, 24) or bitnumIntl(s, 12, 25) or bitnumIntl(s, 29, 26) or bitnumIntl(s, 5, 27) or
                bitnumIntl(s, 21, 28) or bitnumIntl(s, 10, 29) or bitnumIntl(s, 3, 30) or bitnumIntl(s, 24, 31)
            ).u32()
    }

    private fun desCrypt(input: ByteArray, schedule: Array<ByteArray>, output: ByteArray) {
        var (s0, s1) = initialPermutation(input)
        for (round in 0 until 15) {
            val previous = s1
            s1 = (desF(s1, schedule[round]) xor s0).u32()
            s0 = previous
        }
        s0 = (desF(s1, schedule[15]) xor s0).u32()
        inversePermutation(s0, s1, output)
    }

    private fun keySchedule(key: ByteArray, mode: Int): Array<ByteArray> {
        val schedule = Array(16) { ByteArray(6) }
        var c = 0L
        var d = 0L
        for (index in 0 until 28) {
            c = (c or bitnum(key, KEY_PERM_C[index], 31 - index)).u32()
            d = (d or bitnum(key, KEY_PERM_D[index], 31 - index)).u32()
        }
        for (round in 0 until 16) {
            val shift = KEY_RND_SHIFT[round]
            c = ((((c shl shift).u32()) or (c ushr (28 - shift))) and 0xFFFF_FFF0L).u32()
            d = ((((d shl shift).u32()) or (d ushr (28 - shift))) and 0xFFFF_FFF0L).u32()
            val target = if (mode == DES_DECRYPT) 15 - round else round
            for (j in 0 until 24) {
                val bit = bitnumIntr(c, KEY_COMPRESSION[j], 7 - (j % 8)).toInt()
                schedule[target][j / 8] = (schedule[target][j / 8].toInt() or bit).toByte()
            }
            for (j in 24 until 48) {
                val bit = bitnumIntr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8)).toInt()
                schedule[target][j / 8] = (schedule[target][j / 8].toInt() or bit).toByte()
            }
        }
        return schedule
    }

    private fun tripleDesKeySetup(key: ByteArray, mode: Int): Array<Array<ByteArray>> {
        fun slice(from: Int, to: Int) = key.copyOfRange(from, to)
        return if (mode == DES_ENCRYPT) {
            arrayOf(
                keySchedule(slice(0, 8), DES_ENCRYPT),
                keySchedule(slice(8, 16), DES_DECRYPT),
                keySchedule(slice(16, 24), DES_ENCRYPT),
            )
        } else {
            arrayOf(
                keySchedule(slice(16, 24), DES_DECRYPT),
                keySchedule(slice(8, 16), DES_ENCRYPT),
                keySchedule(slice(0, 8), DES_DECRYPT),
            )
        }
    }

    private fun tripleDesCrypt(input: ByteArray, schedule: Array<Array<ByteArray>>, output: ByteArray) {
        val buffer = ByteArray(8)
        desCrypt(input, schedule[0], buffer)
        desCrypt(buffer, schedule[1], output)
        desCrypt(output, schedule[2], buffer)
        buffer.copyInto(output)
    }
}
