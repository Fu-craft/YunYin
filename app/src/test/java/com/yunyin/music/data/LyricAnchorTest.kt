package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credit-line detection for lyric alignment anchoring.
 *
 * The measured failure this guards: NetEase lyric documents commonly open with a song's credits
 * while AMLL documents open with the first sung line. Using the first line of each as alignment
 * anchors compared unlike things — on one measured track (id 1851652156) the NetEase document
 * started at 8240ms with "出品：网易音乐人x青云LAB" while AMLL started at 23749ms with the actual
 * first lyric, yielding a bogus ~15.5s offset that shifted the lyrics that far early.
 */
class LyricAnchorTest {

    @Test
    fun `real credit lines from a measured track are detected`() {
        // Verbatim from NetEase /lyric/new for track 1851652156.
        assertTrue(LyricTimeShift.isCreditLine("出品：网易音乐人x青云LAB"))
        assertTrue(LyricTimeShift.isCreditLine("制作人：h3R3"))
        assertTrue(LyricTimeShift.isCreditLine("编曲：DIESI"))
    }

    @Test
    fun `common credit forms are detected`() {
        listOf(
            "作词 : 张三",
            "作曲 : 李四",
            "混音：王五",
            "母带：赵六",
            "Producer: Someone",
            "Written by A. Person",
            "Composed by B. Person",
            "Lyrics: C. Person",
            "Arranged by D. Person",
        ).forEach { assertTrue("should be a credit: $it", LyricTimeShift.isCreditLine(it)) }
    }

    @Test
    fun `real sung lyrics are not mistaken for credits`() {
        // Verbatim first lines from the same measured track, which ARE the real lyrics.
        listOf(
            "有多么痛 是我没勇气敢问你一句",
            "多么想 你放开的太过于容易",
            "多可笑 只剩我忘不掉你",
            "该怎么去形容你最贴切",
            "拿什么跟你作比较才算特别",
        ).forEach { assertFalse("must not be a credit: $it", LyricTimeShift.isCreditLine(it)) }
    }

    @Test
    fun `blank lines are credits because they cannot anchor`() {
        assertTrue(LyricTimeShift.isCreditLine(""))
        assertTrue(LyricTimeShift.isCreditLine("   "))
    }

    @Test
    fun `a colon inside a lyric does not make it a credit`() {
        // Only a short leading token before the separator counts as a credit head.
        assertFalse(LyricTimeShift.isCreditLine("我爱你：这是一个很长的歌词句子不应该被当成制作人员信息"))
        assertFalse(LyricTimeShift.isCreditLine("Hello, world"))
    }

    @Test
    fun `first anchor skips leading credits`() {
        val lines = listOf(
            8_240 to "出品：网易音乐人x青云LAB",
            9_670 to "制作人：h3R3",
            10_750 to "编曲：DIESI",
            13_590 to "",
            23_990 to "有多么痛 是我没勇气敢问你一句",
            26_650 to "多么想 你放开的太过于容易",
        )
        // The anchor must be the real lyric, not the 8240ms credit line.
        assertEquals(23_990, LyricTimeShift.firstAnchorStartMs(lines))
    }

    @Test
    fun `first anchor falls back to the first line when all are credits`() {
        val lines = listOf(1_000 to "作词 : A", 2_000 to "作曲 : B")
        assertEquals(1_000, LyricTimeShift.firstAnchorStartMs(lines))
    }

    @Test
    fun `first anchor is null for no lines`() {
        assertNull(LyricTimeShift.firstAnchorStartMs(emptyList()))
    }

    @Test
    fun `aligning by anchors corrects the credit-induced offset`() {
        // Reproduces the measured track: AMLL's first lyric at 23749ms, NetEase's first *lyric* at
        // 23990ms. Only a 241ms difference, so nothing should move.
        val amll = listOf(23_749 to "有多么痛 是我没勇气敢问你一句")
        val netease = listOf(
            8_240 to "出品：网易音乐人x青云LAB",
            23_990 to "有多么痛 是我没勇气敢问你一句",
        )
        val current = LyricTimeShift.firstAnchorStartMs(amll)!!
        val reference = LyricTimeShift.firstAnchorStartMs(netease)!!
        assertEquals(23_749, current)
        assertEquals(23_990, reference)
        // 241ms is well inside tolerance, so alignment is a no-op rather than a 15.5s shift.
        assertTrue(kotlin.math.abs(reference - current) < LyricTimeShift.ALIGNMENT_TOLERANCE_MS)
    }
}
