package com.yunyin.music.data

import com.yunyin.music.data.net.DiscoveryParsers
import com.yunyin.music.data.net.DiscoveryRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discovery rules/parsers: grouping caps, suggestion visibility, lyric-section trigger, paging, hot words. */
class DiscoveryRulesTest {

    @Test
    fun `suggestions are grouped and capped at three each`() {
        val json = JSONObject(
            """{"result":{"songs":[{"id":1,"name":"a","artists":[{"name":"x"}],"album":{"id":2,"name":"al"},"duration":1000},
            {"id":3,"name":"b"},{"id":4,"name":"c"},{"id":5,"name":"d"}],
            "artists":[{"id":10,"name":"X"},{"id":11,"name":"Y"},{"id":12,"name":"Z"},{"id":13,"name":"W"}],
            "albums":[{"id":20,"name":"A","artist":{"name":"X"}}]}}""",
        )
        val suggestions = DiscoveryParsers.parseSuggest(json)
        assertEquals(3, suggestions.songs.size)
        assertEquals(3, suggestions.artists.size)
        assertEquals(1, suggestions.albums.size)
        assertEquals("x", suggestions.songs[0].artists.first())
        assertEquals(2L, suggestions.songs[0].albumId)
        assertEquals("X", suggestions.albums[0].artistName)
    }

    @Test
    fun `suggestion visibility belongs to the typing state only`() {
        assertTrue(DiscoveryRules.suggestionsVisible("晴天", searched = false))
        assertFalse(DiscoveryRules.suggestionsVisible("晴天", searched = true))
        assertFalse(DiscoveryRules.suggestionsVisible("  ", searched = false))
    }

    @Test
    fun `lyric matches appear only when normal results are empty`() {
        assertTrue(DiscoveryRules.lyricSectionVisible(emptyList(), listOf(track(1))))
        assertFalse(DiscoveryRules.lyricSectionVisible(listOf(track(1)), listOf(track(2))))
        assertFalse(DiscoveryRules.lyricSectionVisible(emptyList(), emptyList()))
    }

    @Test
    fun `suggest debounce is 300ms and paging offsets advance only while more`() {
        assertEquals(300L, DiscoveryRules.SUGGEST_DEBOUNCE_MS)
        assertEquals(20, DiscoveryRules.nextOffset(0, 20, more = true))
        assertEquals(null, DiscoveryRules.nextOffset(20, 20, more = false))
    }

    @Test
    fun `hot detail words and lyric songs parse from the deployed shapes`() {
        val hot = JSONObject("""{"code":200,"data":[{"searchWord":"我不难过"},{"searchWord":"天龙八部之宿敌"}]}""")
        assertEquals(listOf("我不难过", "天龙八部之宿敌"), DiscoveryParsers.hotWords(hot))
        val lyric = JSONObject("""{"result":{"songs":[{"id":9,"name":"s","artists":[{"name":"a"}],"album":{"id":8,"name":"x"},"duration":1000}]}}""")
        assertEquals(9L, DiscoveryParsers.lyricSongs(lyric).first().id)
    }

    private fun track(id: Long) = com.yunyin.music.core.Track(
        id = id, name = "t$id", artists = listOf("a"), albumName = "", albumId = 0L,
        coverUrl = null, durationMs = 1_000L,
    )
}
