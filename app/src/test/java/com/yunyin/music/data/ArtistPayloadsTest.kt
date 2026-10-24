// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.data.net.ArtistPayloads
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Artist payload parsing: both documented envelopes (`data.artist` / flat) and `hotAlbums` / `albums`. */
class ArtistPayloadsTest {

    @Test
    fun `nested data artist shape parses the header and counts`() {
        val json = JSONObject(
            """{"code":200,"data":{"artist":{"id":6452,"name":"周杰伦","avatar":"https://a/b.jpg",
            "briefDesc":"简介","albumSize":45,"musicSize":581,"followed":false}}}""",
        )
        val info = ArtistPayloads.parseArtist(json, seedId = 6452L)
        assertEquals("周杰伦", info.name)
        assertEquals("https://a/b.jpg", info.coverUrl)
        assertEquals("简介", info.briefDesc)
        assertEquals(45, info.albumSize)
        assertEquals(581, info.musicSize)
    }

    @Test
    fun `flat artist shape and missing avatar still parse`() {
        val json = JSONObject("""{"artist":{"id":7,"name":"某歌手","briefDesc":""},"code":200}""")
        val info = ArtistPayloads.parseArtist(json, seedId = 7L)
        assertEquals("某歌手", info.name)
        assertEquals(null, info.coverUrl)
        assertEquals(0, info.albumSize)
    }

    @Test
    fun `albums parse from hotAlbums and from albums`() {
        val hot = JSONObject(
            """{"code":200,"more":true,"hotAlbums":[{"id":403900711,"name":"太阳之子","picUrl":"p","size":13,"publishTime":1700000000000}]}""",
        )
        val fromHot = ArtistPayloads.parseAlbumsPage(hot)
        assertTrue(fromHot.more)
        assertEquals(1, fromHot.albums.size)
        assertEquals("太阳之子", fromHot.albums[0].name)
        assertEquals(13, fromHot.albums[0].trackCount)

        val legacy = JSONObject("""{"more":false,"albums":[{"id":1,"name":"A","blurPicUrl":"b"}]}""")
        val fromAlbums = ArtistPayloads.parseAlbumsPage(legacy)
        assertFalse(fromAlbums.more)
        assertEquals("A", fromAlbums.albums[0].name)
        assertEquals("b", fromAlbums.albums[0].coverUrl)
    }

    @Test
    fun `rows without ids are dropped rather than producing an unopenable album`() {
        val json = JSONObject("""{"hotAlbums":[{"name":"no id"},{"id":5,"name":"ok"}]}""")
        val page = ArtistPayloads.parseAlbumsPage(json)
        assertEquals(1, page.albums.size)
        assertEquals(5L, page.albums[0].id)
    }
}