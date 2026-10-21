package com.yunyin.music.data.net

import org.json.JSONObject

/** One playlist card in the 歌单广场. */
data class DiscoveryPlaylist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long,
    val description: String?,
)

data class DiscoveryPlaylistPage(val playlists: List<DiscoveryPlaylist>, val more: Boolean)

data class PlaylistSquarePage(val playlists: List<DiscoveryPlaylist>, val more: Boolean)

/** Pure 歌单广场 rules: category parsing, page parsing and append/paging. */
object PlaylistSquareRules {
    const val PAGE_SIZE = 20

    fun categories(json: JSONObject): List<String> {
        val out = LinkedHashSet<String>()
        json.optJSONObject("all")?.optString("name")?.ifBlank { null }?.let { out += it }
        val sub = json.optJSONArray("sub")
        if (sub != null) {
            for (i in 0 until sub.length()) {
                sub.optJSONObject(i)?.optString("name")?.ifBlank { null }?.let { out += it }
            }
        }
        return out.toList()
    }

    fun page(json: JSONObject): DiscoveryPlaylistPage {
        val arr = json.optJSONArray("playlists") ?: return DiscoveryPlaylistPage(emptyList(), false)
        val playlists = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optLong("id")
            val name = o.optString("name")
            if (id <= 0L || name.isBlank()) return@mapNotNull null
            DiscoveryPlaylist(
                id = id,
                name = name,
                coverUrl = o.optString("coverImgUrl").ifBlank { null },
                trackCount = o.optInt("trackCount"),
                playCount = o.optLong("playCount"),
                description = o.optString("description").ifBlank { null },
            )
        }
        return DiscoveryPlaylistPage(playlists, json.optBoolean("more"))
    }

    fun appendUnique(existing: List<DiscoveryPlaylist>, incoming: List<DiscoveryPlaylist>): List<DiscoveryPlaylist> {
        val seen = existing.mapTo(HashSet()) { it.id }
        return existing + incoming.filter { seen.add(it.id) }
    }

    fun nextOffset(current: Int, pageSize: Int = PAGE_SIZE, more: Boolean): Int? =
        if (more) current + pageSize else null

    fun canLoadMore(more: Boolean, loading: Boolean): Boolean = more && !loading
}
