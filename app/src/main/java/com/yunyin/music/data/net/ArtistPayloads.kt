package com.yunyin.music.data.net

import com.yunyin.music.core.AlbumInfo
import com.yunyin.music.core.ArtistAlbumsPage
import com.yunyin.music.core.ArtistInfo
import org.json.JSONObject

/**
 * Defensive parsing of the deployed instance's artist payloads.
 *
 * Probe (id=6452) returned `data.artist` for `/artist/detail` and `hotAlbums[]` for `/artist/album`, but the
 * documented module versions differ ("artist" at top level, "albums" instead of "hotAlbums"), so both shapes are
 * accepted rather than assuming this deployment forever.
 */
object ArtistPayloads {

    fun parseArtist(json: JSONObject, seedId: Long): ArtistInfo {
        val artist = json.optJSONObject("data")?.optJSONObject("artist")
            ?: json.optJSONObject("artist")
            ?: json
        return ArtistInfo(
            id = artist.optLong("id", seedId).takeIf { it > 0L } ?: seedId,
            name = artist.optString("name").ifBlank { "未知歌手" },
            coverUrl = artist.optString("avatar").ifBlank {
                artist.optString("img1v1Url").ifBlank { artist.optString("picUrl").ifBlank { null } }
            },
            briefDesc = artist.optString("briefDesc").orEmpty(),
            albumSize = artist.optInt("albumSize"),
            musicSize = artist.optInt("musicSize"),
            followed = artist.optBoolean("followed"),
        )
    }

    fun parseAlbumsPage(json: JSONObject): ArtistAlbumsPage {
        val arr = json.optJSONArray("hotAlbums") ?: json.optJSONArray("albums")
        val albums = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            AlbumInfo(
                id = o.optLong("id"),
                name = o.optString("name").ifBlank { "未知专辑" },
                coverUrl = o.optString("picUrl").ifBlank { o.optString("blurPicUrl").ifBlank { null } },
                trackCount = o.optInt("size"),
                publishTime = o.optLong("publishTime"),
            )
        }.filter { it.id > 0L }
        return ArtistAlbumsPage(albums = albums, more = json.optBoolean("more"))
    }
}
