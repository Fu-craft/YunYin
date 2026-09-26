package com.yunyin.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards stripping of the credit/metadata lines that lyric files begin with.
 *
 * This is the actual "lyrics start before the intro" bug. Measured on real tracks, NetEase lyric
 * files open with the credits **including their own timestamps**, and because the whole document is
 * displayed, the player highlighted a credit line from t≈0. The timing was correct all along — the
 * *content* was wrong, which is why fixing the alignment anchor did not resolve the symptom.
 *
 * Both cases below are verbatim from tracks that reproduced the bug.
 */
class LyricMetadataTest {

    @Test
    fun `real opening credits are recognised`() {
        // Track 1827600686, YRC: the first three lines are credits at 0ms / 11620ms.
        assertTrue(LyricMetadata.isMetadataLine("出品：网易云音乐x云上工作室"))
        assertTrue(LyricMetadata.isMetadataLine("林达浪："))
        // Track 1922888354, LRC.
        assertTrue(LyricMetadata.isMetadataLine("出品：网易清人LAB"))
        assertTrue(LyricMetadata.isMetadataLine("企划：荀之"))
        assertTrue(LyricMetadata.isMetadataLine("Starling8:"))
    }

    @Test
    fun `real first lyrics are not metadata`() {
        // The lines that must survive, from the same tracks.
        for (t in listOf(
            "还是会想你 还是会怪你",
            "怪你轻而易举潇洒抽离",
            "怎样才能满足你的野心",
            "主动掉进陷阱还是往你脸上贴金",
        )) {
            assertFalse("must survive: $t", LyricMetadata.isMetadataLine(t))
        }
    }

    @Test
    fun `blank lines are metadata so they cannot become the first line`() {
        assertTrue(LyricMetadata.isMetadataLine(""))
        assertTrue(LyricMetadata.isMetadataLine("   "))
    }

    @Test
    fun `stripping removes only the leading run`() {
        val lines = listOf(
            "出品：网易云音乐x云上工作室",
            "林达浪：",
            "还是会想你 还是会怪你",
            "怪你轻而易举潇洒抽离",
        )
        val kept = LyricMetadata.stripLeading(lines) { it }
        assertEquals(2, kept.size)
        assertEquals("还是会想你 还是会怪你", kept[0])
        // The intro is preserved as an empty gap: stripping must not re-time anything.
        assertEquals(listOf("还是会想你 还是会怪你", "怪你轻而易举潇洒抽离"), kept)
    }

    @Test
    fun `a credit-looking line in the middle is kept`() {
        // Only the *leading* run is stripped: a later "作词：" is not touched.
        val lines = listOf(
            "出品：某个工作室",
            "第一句歌词",
            "作词：某人",
            "第二句歌词",
        )
        val kept = LyricMetadata.stripLeading(lines) { it }
        assertEquals(3, kept.size)
        assertTrue(kept.contains("作词：某人"))
    }

    @Test
    fun `a document that is entirely metadata is returned unchanged`() {
        // Better to show the document than to show nothing at all.
        val lines = listOf("出品：A", "制作人：B")
        val kept = LyricMetadata.stripLeading(lines) { it }
        assertEquals(lines, kept)
    }

    @Test
    fun `old isCreditLine API still behaves`() {
        assertTrue(LyricTimeShift.isCreditLine("作词 : 张三"))
        assertFalse(LyricTimeShift.isCreditLine("Hello, world"))
    }
}
