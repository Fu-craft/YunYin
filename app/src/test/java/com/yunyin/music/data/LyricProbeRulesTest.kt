// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the probe-then-paint-once rule.
 *
 * The user rejected the earlier pin-first strategy: the panel must stay in its loading state, check
 * the sources for word-by-word lyrics, and paint the winner exactly once. These tests lock the winner
 * rule the repository relies on.
 */
class LyricProbeRulesTest {

    private fun candidate(source: LyricSource, words: Int, lines: Int) =
        LyricProbeCandidate(source, wordCount = words, lineCount = lines)

    @Test
    fun `a word-by-word document beats any line-level document`() {
        val winner = LyricProbeRules.winner(
            listOf(
                candidate(LyricSource.Netease, words = 0, lines = 900),
                candidate(LyricSource.Amll, words = 8, lines = 2),
            ),
        )
        assertEquals(LyricSource.Amll, winner?.source)
        assertTrue(winner?.wordByWord == true)
    }

    @Test
    fun `among word-by-word documents the one with the most timed words wins`() {
        val winner = LyricProbeRules.winner(
            listOf(
                candidate(LyricSource.Netease, words = 120, lines = 40),
                candidate(LyricSource.Amll, words = 480, lines = 40),
            ),
        )
        assertEquals(LyricSource.Amll, winner?.source)
        assertEquals(480, winner?.wordCount)
    }

    @Test
    fun `a word-count tie is broken by source priority`() {
        val tie = listOf(
            candidate(LyricSource.Kugou, words = 100, lines = 20),
            candidate(LyricSource.Amll, words = 100, lines = 20),
            candidate(LyricSource.Netease, words = 100, lines = 20),
        )
        assertEquals(LyricSource.Netease, LyricProbeRules.winner(tie)?.source)
        assertEquals(
            "QQ QRC outranks NetEase when both are word-timed",
            LyricSource.Tx,
            LyricProbeRules.winner(tie + candidate(LyricSource.Tx, words = 100, lines = 20))?.source,
        )
    }

    @Test
    fun `with no word timing the document with the most lines wins`() {
        val winner = LyricProbeRules.winner(
            listOf(
                candidate(LyricSource.Amll, words = 0, lines = 30),
                candidate(LyricSource.Kuwo, words = 0, lines = 200),
                candidate(LyricSource.Kugou, words = 0, lines = 120),
            ),
        )
        assertEquals(LyricSource.Kuwo, winner?.source)
        assertEquals(200, winner?.lineCount)
    }

    @Test
    fun `fragments count as no document even if word-timed`() {
        assertNull(
            LyricProbeRules.winner(
                listOf(
                    candidate(LyricSource.Netease, words = 2, lines = 1),
                    candidate(LyricSource.Amll, words = 0, lines = 1),
                ),
            ),
        )
        assertNull(LyricProbeRules.winner(emptyList()))
        assertFalse(LyricProbeRules.isPaintable(0))
        assertFalse(LyricProbeRules.isPaintable(1))
        assertTrue(LyricProbeRules.isPaintable(LyricProbeRules.MIN_LINES))
    }

    @Test
    fun `the probe cap is a short bounded wait`() {
        assertTrue("the cap must stay inside the 2.0-3.0 s latency budget", LyricProbeRules.PROBE_CAP_MS in 2_000L..3_000L)
    }

    @Test
    fun `source tags and ranks match the diagnostics and tie-break contract`() {
        assertEquals("tx", LyricSource.Tx.tag)
        assertEquals("wy", LyricSource.Netease.tag)
        assertEquals("amll", LyricSource.Amll.tag)
        assertEquals("kw", LyricSource.Kuwo.tag)
        assertEquals("kg", LyricSource.Kugou.tag)
        assertEquals(
            "QQ QRC > NetEase > AMLL > Kuwo > Kugou",
            listOf("tx", "wy", "amll", "kw", "kg"),
            LyricSource.values().sortedBy { it.probeRank }.map { it.tag },
        )
    }
}
