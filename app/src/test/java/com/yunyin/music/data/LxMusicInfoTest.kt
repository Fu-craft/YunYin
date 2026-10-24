// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxMusicInfo
import com.yunyin.music.data.lxsource.LxQuality
import com.yunyin.music.data.lxsource.LxTrackMemory
import com.yunyin.music.data.lxsource.parseLxMusicUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mapping between this app's track and the `musicInfo` a `wy` LX source reads.
 *
 * The field names are not arbitrary: LX's own NetEase mapping produces `songmid`, `name`, `singer`, `albumName`,
 * `interval`, and a source written against that mapping reads exactly those. Two of these tests pin details that
 * are easy to "improve" into a bug — numbering `songmid` as a string, or adding an empty `hash` that defeats a
 * source's `hash ?? songmid` fallback.
 */
class LxMusicInfoTest {

    private val track = Track(
        id = 42L,
        name = "测试歌曲",
        artists = listOf("歌手甲", "歌手乙"),
        albumName = "测试专辑",
        albumId = 7L,
        coverUrl = null,
        durationMs = 252_000L,
    )

    @Test
    fun `musicInfo mirrors LX's NetEase mapping`() {
        val info = LxMusicInfo.musicInfo(trackId = 42L, track = track)
        // LX passes the numeric NetEase id as `songmid` (musicSearch.js: songmid: item.id).
        assertEquals(42L, info.getLong("songmid"))
        assertEquals(42L, info.getLong("id"))
        assertEquals("测试歌曲", info.getString("name"))
        assertEquals("歌手甲 / 歌手乙", info.getString("singer"))
        assertEquals("测试专辑", info.getString("albumName"))
        assertEquals(7L, info.getLong("albumId"))
        assertEquals("04:12", info.getString("interval"))
        assertEquals(252_000L, info.getLong("durationMs"))
    }

    @Test
    fun `hash is absent so a source's hash-or-songmid fallback works`() {
        // A source does `musicInfo.hash ?? musicInfo.songmid`; an empty string here would survive `??` and break
        // every request. Absent is what makes the fallback take the id.
        assertFalse(LxMusicInfo.musicInfo(42L, track).has("hash"))
    }

    @Test
    fun `an id-only info still carries the field sources need`() {
        // The stream resolver can run on a loading thread where only the id is available.
        val info = LxMusicInfo.musicInfo(trackId = 99L, track = null)
        assertEquals(99L, info.getLong("songmid"))
        assertEquals("", info.getString("name"))
    }

    @Test
    fun `interval formats like LX's play-time helper`() {
        assertEquals("00:00", LxMusicInfo.formatInterval(0L))
        assertEquals("00:59", LxMusicInfo.formatInterval(59_999L))
        assertEquals("04:12", LxMusicInfo.formatInterval(252_000L))
        assertEquals("60:00", LxMusicInfo.formatInterval(3_600_000L))
    }

    @Test
    fun `the request payload has the documented shape`() {
        val payload = JSONObject(LxMusicInfo.musicUrlRequest(42L, track, LxQuality.QFLAC))
        assertEquals("wy", payload.getString("source"))
        assertEquals("musicUrl", payload.getString("action"))
        assertEquals("flac", payload.getJSONObject("info").getString("type"))
        assertEquals(42L, payload.getJSONObject("info").getJSONObject("musicInfo").getLong("songmid"))
    }

    @Test
    fun `app quality levels map onto LX tiers`() {
        assertEquals(LxQuality.Q128, LxMusicInfo.desiredTier("standard"))
        assertEquals(LxQuality.Q320, LxMusicInfo.desiredTier("higher"))
        assertEquals(LxQuality.Q320, LxMusicInfo.desiredTier("exhigh"))
        assertEquals(LxQuality.QFLAC, LxMusicInfo.desiredTier("lossless"))
        assertEquals(LxQuality.QFLAC24, LxMusicInfo.desiredTier("hires"))
        // An unknown level must not throw on a playback path.
        assertEquals(LxQuality.Q320, LxMusicInfo.desiredTier("something-new"))
    }

    @Test
    fun `a supported tier is requested as-is`() {
        assertEquals(
            LxQuality.QFLAC,
            LxMusicInfo.chooseQuality(LxQuality.QFLAC, listOf(LxQuality.Q320, LxQuality.QFLAC)),
        )
    }

    @Test
    fun `a missing tier falls back to the closest lower one`() {
        // flac24bit is not offered; flac is the nearest lossless tier, better than silently downgrading to 128k.
        assertEquals(
            LxQuality.QFLAC,
            LxMusicInfo.chooseQuality(LxQuality.QFLAC24, listOf(LxQuality.Q128, LxQuality.Q320, LxQuality.QFLAC)),
        )
        assertEquals(
            LxQuality.Q128,
            LxMusicInfo.chooseQuality(LxQuality.Q320, listOf(LxQuality.Q128)),
        )
    }

    @Test
    fun `when only a higher tier is offered it is used rather than failing`() {
        assertEquals(
            LxQuality.Q320,
            LxMusicInfo.chooseQuality(LxQuality.Q128, listOf(LxQuality.Q320)),
        )
    }

    @Test
    fun `a source that declared no tiers is asked for the desired one`() {
        // LX filters an all-unknown quality list down to []; the source may still handle a tier it did not list,
        // and refusing to ask would make it unusable.
        assertEquals(LxQuality.QFLAC, LxMusicInfo.chooseQuality(LxQuality.QFLAC, emptyList()))
    }

    @Test
    fun `the resolved value is read as a plain string or a url object`() {
        assertEquals("https://example.com/a.mp3", parseLxMusicUrl("\"https://example.com/a.mp3\""))
        assertEquals("https://example.com/a.mp3", parseLxMusicUrl("""{"url":"https://example.com/a.mp3"}"""))
        assertEquals("http://example.com/a.mp3", parseLxMusicUrl("\"http://example.com/a.mp3\""))
    }

    @Test
    fun `anything that is not an http url is rejected`() {
        // A source returning a non-URL must fall back rather than hand the player a data source that cannot load.
        assertNull(parseLxMusicUrl("\"ftp://example.com/a.mp3\""))
        assertNull(parseLxMusicUrl("\"\""))
        assertNull(parseLxMusicUrl("null"))
        assertNull(parseLxMusicUrl("not json"))
        assertNull(parseLxMusicUrl("""{"other":"https://example.com/a.mp3"}"""))
    }

    @Test
    fun `track memory hands the full track to a resolver that only has an id`() {
        LxTrackMemory.remember(track)
        assertEquals(track, LxTrackMemory.find(42L))
        assertNull(LxTrackMemory.find(-1L))
    }
}
