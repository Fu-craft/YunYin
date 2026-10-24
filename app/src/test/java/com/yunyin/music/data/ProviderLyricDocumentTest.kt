// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.yunyin.music.data.providers.ProviderLyricDocument
import com.yunyin.music.data.providers.tx.TxLyric
import com.yunyin.music.data.providers.tx.TxLyricParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning QRC word timing into the app's karaoke model.
 *
 * The live check that motivated this code is why it exists as a separate module: feeding the provider's
 * word-by-word *text* to the app's `NeteaseYrcParser` produced **0 lines** (LX writes `<start,dur>` while NetEase
 * YRC writes `(start,dur,0)`), so a song with perfect word timing would have shown plain lyrics. The document is
 * built from structured timings instead, and these tests pin that shape without any network.
 */
class ProviderLyricDocumentTest {

    private fun parsed(vararg lines: TxLyricParser.WordLine) = TxLyric.Parsed(
        plain = "[00:00.000]第一行",
        wordByWord = "[00:00.000]<0,400>第<400,300>一<700,500>行",
        translation = null,
        romanization = null,
        lines = lines.toList(),
    )

    @Test
    fun `word timings become karaoke syllables on the line's timeline`() {
        val document = ProviderLyricDocument.build(
            parsed(
                TxLyricParser.WordLine(
                    startMs = 1000L,
                    durationMs = 2000L,
                    words = listOf(
                        TxLyricParser.Word("第", 0L, 400L),
                        TxLyricParser.Word("一", 400L, 300L),
                        TxLyricParser.Word("行", 700L, 500L),
                    ),
                ),
            ),
        )
        assertEquals(1, document!!.lines.size)
        val line = document.lines.single() as KaraokeLine
        assertEquals(1000, line.start)
        assertEquals(3000, line.end)
        assertEquals(listOf("第", "一", "行"), line.syllables.map { it.content })
        assertEquals(listOf(1000, 1400, 1700), line.syllables.map { it.start })
        assertEquals(listOf(1400, 1700, 2200), line.syllables.map { it.end })
    }

    @Test
    fun `the line end covers the word tails even when the header is short`() {
        val document = ProviderLyricDocument.build(
            parsed(TxLyricParser.WordLine(1000L, 100L, listOf(TxLyricParser.Word("啊", 0L, 900L)))),
        )
        val line = document!!.lines.single() as KaraokeLine
        assertEquals(1900, line.end)
    }

    @Test
    fun `a payload without word timing builds no document`() {
        assertNull(
            ProviderLyricDocument.build(
                TxLyric.Parsed(plain = "x", wordByWord = null, translation = null, romanization = null, lines = emptyList()),
            ),
        )
    }

    @Test
    fun `lines with no usable words are dropped`() {
        val document = ProviderLyricDocument.build(
            parsed(
                TxLyricParser.WordLine(0L, 100L, emptyList()),
                TxLyricParser.WordLine(100L, 100L, listOf(TxLyricParser.Word("字", 0L, 100L))),
            ),
        )
        assertEquals(1, document!!.lines.size)
        assertTrue(document.lines.single() is KaraokeLine)
    }
}
