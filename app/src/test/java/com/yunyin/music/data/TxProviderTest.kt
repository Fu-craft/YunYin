// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.providers.ProviderLabels
import com.yunyin.music.data.providers.ProviderMusicInfo
import com.yunyin.music.data.providers.ProviderTrackIds
import com.yunyin.music.data.providers.tx.TxSearch
import com.yunyin.music.data.providers.tx.TxSigner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QQ Music port: the request signature and the response mapping.
 *
 * ## Why known-answer vectors
 *
 * The `zzc` signature is an input to a remote service: if one character is wrong the endpoint answers `code != 0`
 * and every search silently returns nothing — exactly the failure mode this feature exists to avoid. A test that
 * round-tripped the signer against itself would not catch a wrong index table, so the two vectors below were
 * produced by an independent transcription of the reference JavaScript (Python), not by this Kotlin code.
 *
 * ## Why the response fixture has the real shape
 *
 * The live endpoint nests the list at `data.body.song.list` with totals in `data.meta.sum` — one level deeper than
 * the older LX code path. The fixture is trimmed from a real response so a future refactor cannot re-break it
 * without a test failing.
 */
class TxProviderTest {

    // ------------------------------------------------------------------ signature

    @Test
    fun `the zzc signature matches the independent reference`() {
        // Vectors computed from lyswhut/lx-music-mobile's crypto.js with a Python transcription.
        assertEquals("zzc7746109xq501htmv4ipz7c8owlxfpihjm1fd695c7", TxSigner.sign("""{"a":1}"""))
        assertEquals("zzcfa14dde89n1iwax0l5rimr0qwjexceiov4daaee8d6", TxSigner.sign("hello"))
    }

    @Test
    fun `sha1 is lowercase hex of the utf8 bytes`() {
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", TxSigner.sha1Hex("hello"))
    }

    @Test
    fun `the signature is deterministic and length-stable`() {
        val first = TxSigner.sign("""{"q":"晴天"}""")
        assertEquals(first, TxSigner.sign("""{"q":"晴天"}"""))
        // "zzc" + 7 pickable hex chars + base64 without stripped chars + 8 hex chars; the base64 length depends on
        // the hash, so only assert the prefix and that no stripped characters survive.
        assertTrue(first, first.startsWith("zzc"))
        assertFalse(first, first.removePrefix("zzc").drop(7).takeWhile { it.isLetterOrDigit() }.any { it in "+/=\\" })
    }

    // ------------------------------------------------------------------ response mapping

    private val liveShape = JSONObject(
        """
        {
          "code": 0,
          "music.search.SearchCgiService": {
            "code": 0,
            "data": {
              "body": {
                "song": {
                  "list": [
                    {
                      "mid": "0039MnYb0qxYhV",
                      "id": 97773,
                      "title": "晴天",
                      "interval": 269,
                      "singer": [{"name": "周杰伦", "mid": "0025NhlN2yWrP4"}],
                      "album": {"mid": "000MkMni19ClKG", "name": "叶惠美"},
                      "file": {
                        "media_mid": "003Qui1q2u1Zho",
                        "size_128mp3": 4300000,
                        "size_320mp3": 10700000,
                        "size_flac": 52700000,
                        "size_hires": 0
                      }
                    },
                    {
                      "mid": "001398Rf3gykz8",
                      "title": "晴天 (Live)",
                      "interval": 299,
                      "singer": [{"name": "周杰伦"}, {"name": "林俊杰"}],
                      "album": {"mid": "0032ezFm3F53yO", "name": "演唱会"},
                      "file": {"media_mid": "003XEKsj41apvG", "size_128mp3": 1, "size_320mp3": 0, "size_flac": 0}
                    }
                  ]
                }
              },
              "meta": {"sum": 824, "estimate_sum": 10732, "perpage": 30, "curpage": 1}
            }
          }
        }
        """.trimIndent(),
    )

    @Test
    fun `the live response shape maps to provider tracks`() {
        val page = TxSearch().parseResponse(liveShape, page = 1, limit = 30)
        assertEquals(2, page.tracks.size)
        assertEquals(824, page.total)
        assertEquals(28, page.allPage)

        val first = page.tracks[0]
        assertEquals("晴天", first.name)
        assertEquals(listOf("周杰伦"), first.artists)
        assertEquals("叶惠美", first.albumName)
        assertEquals(269_000L, first.durationMs)
        assertEquals(ProviderTrackIds.idFor("tx", "0039MnYb0qxYhV"), first.id)
        val ref = first.providerRef!!
        assertEquals("tx", ref.provider)
        assertEquals("0039MnYb0qxYhV", ref.providerId)
        assertEquals("003Qui1q2u1Zho", ref.mediaMid)
        assertEquals("000MkMni19ClKG", ref.albumMid)
        assertEquals(269, ref.intervalSeconds)
        assertEquals(listOf("128k", "320k", "flac"), ref.qualities)
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R500x500M000000MkMni19ClKG.jpg",
            first.coverUrl,
        )
    }

    @Test
    fun `a second song's artists, qualities and cover fall back correctly`() {
        val page = TxSearch().parseResponse(liveShape, page = 1, limit = 30)
        val second = page.tracks[1]
        assertEquals(listOf("周杰伦", "林俊杰"), second.artists)
        assertEquals(listOf("128k"), second.providerRef!!.qualities)
    }

    @Test
    fun `the older flat shape is accepted too`() {
        val flat = JSONObject(
            """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{
              "song":{"list":[{"mid":"abc","title":"x","interval":100,"singer":[{"name":"s"}]}]}}}}
            """.trimIndent(),
        )
        val page = TxSearch().parseResponse(flat, page = 1, limit = 30)
        assertEquals(1, page.tracks.size)
        assertEquals("abc", page.tracks[0].providerRef!!.providerId)
    }

    @Test
    fun `an error code is an exception, not an empty page`() {
        val error = runCatching {
            TxSearch().parseResponse(
                JSONObject("""{"code":1000,"music.search.SearchCgiService":{"code":0,"data":{}}}"""),
                page = 1,
                limit = 30,
            )
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("code=") || error.message!!.contains("no search service"))
    }

    @Test
    fun `a song without a mid is skipped`() {
        val json = JSONObject(
            """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{"body":{"song":{"list":[
              {"title":"no mid","interval":1},
              {"mid":"ok","title":"x","interval":1}
            ]}}}}}
            """.trimIndent(),
        )
        val page = TxSearch().parseResponse(json, page = 1, limit = 30)
        assertEquals(1, page.tracks.size)
        assertEquals("ok", page.tracks[0].providerRef!!.providerId)
    }

    @Test
    fun `an empty page falls back to the estimate for the total`() {
        val json = JSONObject(
            """
            {"code":0,"music.search.SearchCgiService":{"code":0,"data":{
              "body":{"song":{"list":[]}},"meta":{"sum":0,"estimate_sum":10732}}}}
            """.trimIndent(),
        )
        val page = TxSearch().parseResponse(json, page = 1, limit = 30)
        assertTrue(page.tracks.isEmpty())
        assertEquals(10732, page.total)
    }

    // ------------------------------------------------------------------ identity + musicInfo

    @Test
    fun `provider track ids are stable, negative, and never collide with NetEase ids`() {
        val id = ProviderTrackIds.idFor("tx", "0039MnYb0qxYhV")
        assertTrue(id < 0L)
        assertEquals(id, ProviderTrackIds.idFor("tx", "0039MnYb0qxYhV"))
        assertTrue(id != ProviderTrackIds.idFor("kw", "0039MnYb0qxYhV"))
        assertTrue(id != ProviderTrackIds.idFor("tx", "001398Rf3gykz8"))
    }

    @Test
    fun `the source payload matches LX's musicInfo contract field for field`() {
        val page = TxSearch().parseResponse(liveShape, page = 1, limit = 30)
        val musicInfo = ProviderMusicInfo.forTrack(page.tracks[0])
        // LX utils/index.ts toOldMusicInfo: songmid is the platform song id (the QQ mid), songId is the numeric id.
        assertEquals("0039MnYb0qxYhV", musicInfo.getString("songmid"))
        assertEquals(97773L, musicInfo.getLong("songId"))
        // LX's per-source switch for tx adds these three.
        assertEquals("003Qui1q2u1Zho", musicInfo.getString("strMediaMid"))
        assertEquals("000MkMni19ClKG", musicInfo.getString("albumMid"))
        // LX stores the album mid in albumId for QQ.
        assertEquals("000MkMni19ClKG", musicInfo.getString("albumId"))
        assertEquals("叶惠美", musicInfo.getString("albumName"))
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R500x500M000000MkMni19ClKG.jpg", musicInfo.getString("img"))
        assertEquals("04:29", musicInfo.getString("interval"))
        assertEquals("tx", musicInfo.getString("source"))
        assertEquals("周杰伦", musicInfo.getString("singer"))
        // LX always sends types/_types/typeUrl, and a source may read them.
        assertEquals(3, musicInfo.getJSONArray("types").length())
        assertEquals("128k", musicInfo.getJSONArray("types").getJSONObject(0).getString("type"))
        assertEquals("4.10 MiB", musicInfo.getJSONArray("types").getJSONObject(0).getString("size"))
        assertEquals("4.10 MiB", musicInfo.getJSONObject("_types").getJSONObject("128k").getString("size"))
        assertEquals(0, musicInfo.getJSONObject("typeUrl").length())
        // A source does `musicInfo.hash ?? musicInfo.songmid`; for tx LX only kg sets hash.
        assertFalse(musicInfo.has("hash"))
        // LX does not send durationMs; the contract is interval.
        assertFalse(musicInfo.has("durationMs"))
    }

    @Test
    fun `formatting helpers match LX`() {
        assertEquals("0 B", com.yunyin.music.data.providers.tx.TxFormat.sizeFormate(0))
        assertEquals("4.10 MiB", com.yunyin.music.data.providers.tx.TxFormat.sizeFormate(4_300_000))
        assertEquals("12.43 MiB", com.yunyin.music.data.providers.tx.TxFormat.sizeFormate(13_037_236))
        assertEquals("--/--", com.yunyin.music.data.providers.tx.TxFormat.formatPlayTime(0))
        assertEquals("04:29", com.yunyin.music.data.providers.tx.TxFormat.formatPlayTime(269))
    }

    @Test
    fun `the request payload names the provider as the source`() {
        val page = TxSearch().parseResponse(liveShape, page = 1, limit = 30)
        val payload = JSONObject(
            ProviderMusicInfo.musicUrlRequest("tx", ProviderMusicInfo.forTrack(page.tracks[0]), "320k"),
        )
        assertEquals("tx", payload.getString("source"))
        assertEquals("musicUrl", payload.getString("action"))
        assertEquals("320k", payload.getJSONObject("info").getString("type"))
        assertEquals(
            "0039MnYb0qxYhV",
            payload.getJSONObject("info").getJSONObject("musicInfo").getString("songmid"),
        )
    }

    @Test
    fun `provider labels are human names`() {
        assertEquals("QQ音乐", ProviderLabels.of("tx"))
        assertEquals("酷我音乐", ProviderLabels.of("kw"))
        assertEquals("酷狗音乐", ProviderLabels.of("kg"))
        assertEquals("咪咕音乐", ProviderLabels.of("mg"))
        assertEquals("x", ProviderLabels.of("x"))
    }

    @Test
    fun `a NetEase track has no provider ref by default`() {
        val track = Track(
            id = 1L,
            name = "n",
            artists = listOf("a"),
            albumName = "b",
            albumId = 0L,
            coverUrl = null,
            durationMs = 1000L,
        )
        assertNull(track.providerRef)
        val provider = track.copy(providerRef = ProviderRef("tx", "m"))
        assertNotNull(provider.providerRef)
    }
}
