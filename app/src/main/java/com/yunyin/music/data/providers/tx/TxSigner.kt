package com.yunyin.music.data.providers.tx

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * QQ Music's `zzc` request signature.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0), `src/utils/musicSdk/tx/utils/crypto.js`
 * (function `zzcSign`) and the SHA-1 helper it uses in `src/utils/nativeModules/crypto.ts` /
 * `android/.../crypto/CryptoModule.java`. The algorithm itself is a client-side obfuscation of the QQ Music
 * desktop API, not a security primitive — it is reproduced faithfully rather than "improved", because the server
 * checks the exact string.
 *
 * ## What it does
 *
 * `sha1(json)` → 40 lowercase hex characters, then:
 *  - `part1` = characters at [PART_1_INDEXES] (one index, 40, is deliberately out of range; JavaScript's
 *    `Array.join` renders it as empty, and the server's expectation was built the same way);
 *  - `part2` = characters at [PART_2_INDEXES];
 *  - `part3` = 20 bytes, [SCRAMBLE_VALUES] XOR-ed with each hex byte of the hash;
 *  - base64 of `part3` with `/`, `+`, `=`, `\` removed;
 *  - `zzc` + part1 + base64 + part2, lower-cased.
 *
 * Pure Kotlin (no Android, no JS), so the known-answer test runs on the JVM.
 */
internal object TxSigner {

    private val PART_1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
    private val PART_2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
    private val SCRAMBLE_VALUES = intArrayOf(
        89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179,
    )

    /** Characters to strip from the base64 part, exactly as the reference implementation does. */
    private val STRIPPED = Regex("[\\\\/+=]")

    fun sign(text: String): String {
        val hash = sha1Hex(text)
        val part1 = pick(hash, PART_1_INDEXES)
        val part2 = pick(hash, PART_2_INDEXES)
        val scrambled = ByteArray(SCRAMBLE_VALUES.size) { index ->
            val hashByte = hash.substring(index * 2, index * 2 + 2).toInt(16)
            ((SCRAMBLE_VALUES[index] xor hashByte) and 0xff).toByte()
        }
        val base64 = Base64.getEncoder().encodeToString(scrambled).replace(STRIPPED, "")
        return ("zzc$part1$base64$part2").lowercase(Locale.ROOT)
    }

    fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    /** Picks characters by index; an out-of-range index contributes nothing (JavaScript's `join` semantics). */
    private fun pick(hash: String, indexes: IntArray): String =
        buildString { indexes.forEach { index -> if (index < hash.length) append(hash[index]) } }
}
