package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the source-pinning rule behind "no mid-song lyric source swap".
 *
 * The visible bug: a fast line-level document was painted, then a different provider's word-by-word
 * document replaced it. Once anything is displayed it is pinned; only the **same** provider may
 * finish its own richer payload (line-level -> word-by-word), and that upgrade is cross-faded by the
 * UI rather than swapped.
 */
class LyricPinRulesTest {

    @Test
    fun `the first document pins its source`() {
        assertEquals(
            LyricPinDecision.First,
            LyricPinRules.decide(null, LyricSource.Netease, incomingWordByWord = false),
        )
        assertEquals(
            LyricPinDecision.First,
            LyricPinRules.decide(null, LyricSource.Amll, incomingWordByWord = true),
        )
    }

    @Test
    fun `the same source may finish its own word-by-word payload`() {
        assertEquals(
            LyricPinDecision.SameProviderUpgrade,
            LyricPinRules.decide(
                PinnedLyric(LyricSource.Netease, wordByWord = false),
                LyricSource.Netease,
                incomingWordByWord = true,
            ),
        )
        assertEquals(
            LyricPinDecision.SameProviderUpgrade,
            LyricPinRules.decide(
                PinnedLyric(LyricSource.Kuwo, wordByWord = false),
                LyricSource.Kuwo,
                incomingWordByWord = true,
            ),
        )
    }

    @Test
    fun `a different source is blocked once something is displayed`() {
        assertEquals(
            LyricPinDecision.DifferentProviderBlocked,
            LyricPinRules.decide(
                PinnedLyric(LyricSource.Netease, wordByWord = false),
                LyricSource.Amll,
                incomingWordByWord = true,
            ),
        )
        assertEquals(
            LyricPinDecision.DifferentProviderBlocked,
            LyricPinRules.decide(
                PinnedLyric(LyricSource.Amll, wordByWord = true),
                LyricSource.Netease,
                incomingWordByWord = true,
            ),
        )
        assertEquals(
            LyricPinDecision.DifferentProviderBlocked,
            LyricPinRules.decide(
                PinnedLyric(LyricSource.Kuwo, wordByWord = false),
                LyricSource.Kugou,
                incomingWordByWord = true,
            ),
        )
    }

    @Test
    fun `nothing may replace an already word-by-word document`() {
        assertEquals(
            LyricPinDecision.DifferentProviderBlocked,
            LyricPinRules.decide(
                PinnedLyric(LyricSource.Netease, wordByWord = true),
                LyricSource.Netease,
                incomingWordByWord = true,
            ),
        )
        assertEquals(
            LyricPinDecision.DifferentProviderBlocked,
            LyricPinRules.decide(
                PinnedLyric(LyricSource.Amll, wordByWord = true),
                LyricSource.Amll,
                incomingWordByWord = false,
            ),
        )
    }

    @Test
    fun `fragments are treated as no document`() {
        assertFalse(LyricPinRules.isPaintable(0))
        assertFalse(LyricPinRules.isPaintable(1))
        assertTrue(LyricPinRules.isPaintable(LyricPinRules.MIN_LINES))
        assertTrue(LyricPinRules.isPaintable(42))
    }

    @Test
    fun `the grace window is bounded and only waits before a line-level first paint`() {
        assertEquals(0L, LyricPinRules.graceWindowMs(firstDocumentIsWordByWord = true))
        assertEquals(LyricPinRules.GRACE_WINDOW_MS, LyricPinRules.graceWindowMs(firstDocumentIsWordByWord = false))
        assertTrue(
            "the grace window must stay in the requested 400-600 ms band",
            LyricPinRules.GRACE_WINDOW_MS in 400L..600L,
        )
    }

    @Test
    fun `source tags match the diagnostics contract`() {
        assertEquals("wy", LyricSource.Netease.tag)
        assertEquals("amll", LyricSource.Amll.tag)
        assertEquals("tx", LyricSource.Tx.tag)
        assertEquals("kw", LyricSource.Kuwo.tag)
        assertEquals("kg", LyricSource.Kugou.tag)
        assertEquals("first", LyricPinDecision.First.tag)
        assertEquals("same-provider-upgrade", LyricPinDecision.SameProviderUpgrade.tag)
        assertEquals("different-provider-blocked", LyricPinDecision.DifferentProviderBlocked.tag)
    }
}
