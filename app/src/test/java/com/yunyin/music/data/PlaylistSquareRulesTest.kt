package com.yunyin.music.data

import com.yunyin.music.data.net.PlaylistSquareRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌单广场: category/page parsing and append/paging rules. */
class PlaylistSquareRulesTest {

    @Test
    fun `categories include all plus sub names without duplicates`() {
        val json = JSONObject(
            """{"all":{"name":"全部"},"sub":[{"name":"流行"},{"name":"流行"},{"name":"摇滚"}]}""",
        )
        assertEquals(listOf("全部", "流行", "摇滚"), PlaylistSquareRules.categories(json))
    }

    @Test
    fun `playlists parse from the deployed shape`() {
        val json = JSONObject(
            """{"more":true,"playlists":[{"id":7,"name":"P","coverImgUrl":"c","trackCount":12,"playCount":99,"description":"d"}]}""",
        )
        val page = PlaylistSquareRules.page(json)
        assertTrue(page.more)
        assertEquals(1, page.playlists.size)
        assertEquals("P", page.playlists[0].name)
        assertEquals(12, page.playlists[0].trackCount)
    }

    @Test
    fun `appending pages never duplicates a playlist`() {
        val page = PlaylistSquareRules.page(
            JSONObject("""{"playlists":[{"id":7,"name":"P"},{"id":8,"name":"Q"}]}"""),
        )
        val merged = PlaylistSquareRules.appendUnique(page.playlists, page.playlists)
        assertEquals(listOf(7L, 8L), merged.map { it.id })
    }

    @Test
    fun `paging offsets advance only while more and never overlap a load`() {
        assertEquals(20, PlaylistSquareRules.nextOffset(0, more = true))
        assertNull(PlaylistSquareRules.nextOffset(20, more = false))
        assertTrue(PlaylistSquareRules.canLoadMore(more = true, loading = false))
        assertFalse(PlaylistSquareRules.canLoadMore(more = true, loading = true))
    }
}
