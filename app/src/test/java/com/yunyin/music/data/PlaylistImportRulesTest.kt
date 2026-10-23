package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.yunyin.music.data.import.ImportedLine
import com.yunyin.music.data.import.PlaylistImportRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure import rules: text separators, NetEase link/id parsing, provider-matcher decisions and batch chunking. */
class PlaylistImportRulesTest {

    private fun track(id: Long, name: String, artist: String, duration: Long = 200_000L) = Track(
        id = id,
        name = name,
        artists = listOf(artist),
        albumName = "Album",
        albumId = 1,
        coverUrl = null,
        durationMs = duration,
    )

    @Test
    fun `text parsing tolerates separators, blank lines and comments`() {
        val lines = PlaylistImportRules.parseText(
            """
            # 我的歌单
            夜曲 - 周杰伦

            晴天 – 周杰伦
            Lemon — 米津玄師
            最后 | 某某
            没有歌手的一首
            """.trimIndent(),
        )
        assertEquals(listOf("夜曲", "晴天", "Lemon", "最后", "没有歌手的一首"), lines.map { it.title })
        assertEquals("周杰伦", lines[0].artist)
        assertEquals("周杰伦", lines[1].artist)
        assertEquals("米津玄師", lines[2].artist)
        assertEquals("某某", lines[3].artist)
        assertEquals("", lines[4].artist)
    }

    @Test
    fun `playlist id parses from a bare number or a NetEase URL`() {
        assertEquals(123456789L, PlaylistImportRules.parsePlaylistId("123456789"))
        assertEquals(987654321L, PlaylistImportRules.parsePlaylistId("https://music.163.com/playlist?id=987654321&userid=1"))
        assertEquals(555L, PlaylistImportRules.parsePlaylistId("https://music.163.com/#/playlist/555"))
        assertNull(PlaylistImportRules.parsePlaylistId("not a playlist"))
        assertNull(PlaylistImportRules.parsePlaylistId(""))
    }

    @Test
    fun `matching reuses the name and singer guard and rejects a different singer`() {
        val line = ImportedLine(title = "夜曲", artist = "周杰伦", raw = "夜曲 - 周杰伦")
        val correct = track(1, "夜曲", "周杰伦")
        assertEquals(correct, PlaylistImportRules.match(line, listOf(track(2, "夜曲", "翻唱歌手"), correct)))
        assertNull(PlaylistImportRules.match(line, listOf(track(3, "完全不同的歌", "周杰伦"))))
        assertNull(PlaylistImportRules.match(line, listOf(track(4, "夜曲", "另一个人"))))
    }

    @Test
    fun `a line without an artist still requires a related title`() {
        val line = ImportedLine(title = "晴天", artist = "", raw = "晴天")
        assertEquals(track(10, "晴天", "周杰伦"), PlaylistImportRules.match(line, listOf(track(10, "晴天", "周杰伦"))))
        assertNull(PlaylistImportRules.match(line, listOf(track(11, "雨天", "周杰伦"))))
    }

    @Test
    fun `ids are split into request-sized batches preserving order`() {
        val ids = (1L..205L).toList()
        val chunks = PlaylistImportRules.chunks(ids, size = 100)
        assertEquals(listOf(100, 100, 5), chunks.map { it.size })
        assertEquals(ids, chunks.flatten())
        assertTrue(PlaylistImportRules.chunks(emptyList()).isEmpty())
    }
}
