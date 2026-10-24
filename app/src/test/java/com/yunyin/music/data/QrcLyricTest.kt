// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.providers.tx.QrcDecoder
import com.yunyin.music.data.providers.tx.TxLyricParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QRC decoder and the QRC→LRC/YRC parser.
 *
 * ## The known-answer vector
 *
 * QRC is a **non-standard 3DES** (two S-box entries differ from DES) plus zlib, so a "looks decodable" test proves
 * nothing: standard crypto would also produce bytes. The vector below was produced by running the **original
 * JavaScript implementation** (`qrcDecode.js` from lyswhut/lx-music-mobile, loaded unmodified into Node 24 with a
 * thin pako→`node:zlib` shim) *in encrypt mode* on a synthetic lyric, then hex-encoded. The committed test asserts
 * that this Kotlin port decrypts it back to exactly the same text — an independent answer, not a round-trip of
 * this code. Synthetic content on purpose: committing real decrypted lyrics would embed copyrighted text.
 *
 * The live endpoint was also verified from this host: it returned a 9808-character QRC for QQ songID 97773 and the
 * reference decoder produced the real 晴天 lyric XML; the port produces the same plaintext (reported separately,
 * the payload itself is not committed).
 */
class QrcLyricTest {

    /** Built with the reference implementation in ENCRYPT mode; see the class doc. */
    private val syntheticPlaintext =
        "[ti:测试歌词]\n[0,2250]这是(0,400)一行(400,300)测试(700,500)\n[2250,1000]第二(2250,400)行(2650,300)\n"

    private val syntheticHex =
        "af52cccedf3e0e065bb65b3d9a110ca5a296d6e6309b1e7a2933fe14ab20fecd0d83b01312607a5b00e6eaf63e" +
            "80915ad4bb754db508dcfb67d44b057e5703d0c454ada85aca46f5991a504d405895052ffc3feb8a627545f38d" +
            "14314f6db1e3d5d01003ae198508cee5bbd1f24b78e2"

    @Test
    fun `decrypts a vector produced by the reference implementation`() {
        assertEquals(syntheticPlaintext, QrcDecoder.decode(syntheticHex))
    }

    @Test
    fun `a QRC envelope is unwrapped and parsed into line and word timing`() {
        val parsed = TxLyricParser.parse(
            "<QrcInfos><LyricInfo><Lyric_1 LyricType=\"1\" LyricContent=\"$syntheticPlaintext\"/></LyricInfo></QrcInfos>",
            null,
            null,
        )
        assertTrue(parsed.lyric, parsed.lyric.contains("[00:00.000]这是一行测试"))
        assertTrue(parsed.lyric, parsed.lyric.contains("[00:02.250]第二行"))
        // Word timings become YRC-shaped tags on the word-by-word track: <offsetFromLineStart,duration>.
        assertTrue(parsed.lxlyric, parsed.lxlyric.contains("[00:00.000]<0,400>这是"))
        assertTrue(parsed.lxlyric, parsed.lxlyric.contains("<400,300>一行"))
        assertTrue(parsed.lxlyric, parsed.lxlyric.contains("<700,500>测试"))
        assertTrue(parsed.lxlyric, parsed.lxlyric.contains("[00:02.250]<0,400>第二"))
    }

    @Test
    fun `a QRC line without word timings still produces a plain line`() {
        val parsed = TxLyricParser.parse("[ti:x]\n[1000,2000]只有一行\n", null, null)
        // Metadata tags are dropped exactly as the reference drops them (its second regex only accepts time tags);
        // the lyric itself keeps its `[mm:ss.mmm]` timestamp.
        assertEquals("[00:01.000]只有一行", parsed.lyric)
        // The reference only emits a word-by-word line when the line HAS word timing, so the karaoke track is
        // empty here and the caller falls back to the plain document — same as the reference.
        assertEquals("", parsed.lxlyric)
    }

    @Test
    fun `word offsets are relative to the line start and never negative`() {
        val parsed = TxLyricParser.parse("[5000,1000]甲(5000,100)乙(4000,100)\n", null, null)
        assertTrue(parsed.lxlyric, parsed.lxlyric.contains("<0,100>甲"))
        // A word whose absolute time is before the line start clamps to zero rather than going negative.
        assertTrue(parsed.lxlyric, parsed.lxlyric.contains("<0,100>乙"))
    }

    @Test
    fun `translation lines are re-timed onto the lyric lines`() {
        val parsed = TxLyricParser.parse(
            "[1000,2000]第一行\n[3000,2000]第二行\n",
            "[00:01.00]first line\n[00:02.50]unmatched\n[00:03.00]second line\n",
            null,
        )
        assertEquals("[00:01.000]first line\n[00:03.000]second line", parsed.tlyric)
    }

    @Test
    fun `romanisation is decoded from QRC form and re-timed`() {
        // The roma payload is QRC too (the reference runs it through decodeQrc), so it arrives in
        // `[startMs,duration]text(wordOffset,wordDuration)` form.
        val parsed = TxLyricParser.parse(
            "[1000,2000]第一行\n",
            null,
            "[1000,2000]di(1000,200) yi(1200,200) hang(1400,300)\n",
        )
        assertEquals("[00:01.000]di yi hang", parsed.rlyric)
    }

    @Test
    fun `empty or malformed payloads are empty, never exceptions`() {
        assertEquals("", QrcDecoder.decode(null))
        assertEquals("", QrcDecoder.decode(""))
        assertEquals("", QrcDecoder.decode("abc")) // odd length
        assertEquals("", QrcDecoder.decode("zzzz")) // not hex
        assertTrue(TxLyricParser.parse(null, null, null).isEmpty)
        assertTrue(TxLyricParser.parse("", "", "").isEmpty)
    }

    @Test
    fun `getIntv keeps the reference's scaling`() {
        // The reference multiplies the first component by 3,600,000 (it pads `[mm:ss]` to `h:m:s` before parsing).
        // Only relative comparisons use it, so this quirk is preserved rather than "fixed" — see getIntv's KDoc.
        assertEquals(0L, TxLyricParser.getIntv(""))
        assertEquals(3_601_000L, TxLyricParser.getIntv("01:01"))
        assertEquals(3_601_500L, TxLyricParser.getIntv("01:01.500"))
        assertEquals(3_602_003L, TxLyricParser.getIntv("01:02:03"))
    }
}
