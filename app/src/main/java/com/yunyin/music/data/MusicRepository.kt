package com.yunyin.music.data

import com.yunyin.music.core.HomeFeed
import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.net.NeteaseClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Read-side facade over [NeteaseClient].
 *
 * Groups the per-endpoint calls into the shapes the screens consume, and hides the
 * fact that some sections require a signed-in account (those fail soft to empty).
 */
class MusicRepository(private val client: NeteaseClient) {

    /**
     * Assembles the Discover tab.
     *
     * Sections are independent, so they are fetched concurrently and each is allowed
     * to fail on its own: a logged-out user still sees featured playlists and charts.
     */
    suspend fun loadHome(): NetResult<HomeFeed> = coroutineScope {
        val featured = async { client.personalizedPlaylists(limit = 10).listOrEmpty() }
        val daily = async { client.dailySongs().listOrEmpty() }
        val recommended = async { client.recommendedPlaylists().listOrEmpty() }
        val fresh = async { client.newSongs(limit = 10).listOrEmpty() }
        val charts = async { client.toplists().listOrEmpty() }

        val featuredPlaylists = featured.await()
        val feed = HomeFeed(
            featuredPlaylists = featuredPlaylists,
            dailySongs = daily.await(),
            recommendedPlaylists = recommended.await().ifEmpty { featuredPlaylists },
            newSongs = fresh.await(),
            charts = charts.await().take(12),
        )
        if (feed.featuredPlaylists.isEmpty() && feed.charts.isEmpty()) {
            NetResult.Err("无法连接音乐服务")
        } else {
            NetResult.Ok(feed)
        }
    }

    suspend fun search(keyword: String, offset: Int = 0): NetResult<List<Track>> =
        when (val result = client.search(keyword, offset = offset)) {
            is NetResult.Err -> result
            is NetResult.Ok -> {
                // Search returns no artwork, so hydrate the page in a single extra call.
                val byId = client.songDetail(result.value.map { it.id }).listOrEmpty().associateBy { it.id }
                // Preserve search ordering, backfilling anything detail omitted.
                NetResult.Ok(result.value.map { byId[it.id] ?: it })
            }
        }

    suspend fun hotSearch(): List<String> = client.hotSearch().listOrEmpty()

    /** Resolves a stream URL, or null when the track is unavailable. Trials pass through. */
    suspend fun streamUrl(id: Long, quality: String): com.yunyin.music.data.net.NeteaseClient.SongUrl? =
        client.songUrl(id, quality).valueOrNull()

    suspend fun playlist(id: Long): Playlist? = client.playlistDetail(id).valueOrNull()

    suspend fun playlistTracks(id: Long): List<Track> = client.playlistTracks(id).listOrEmpty()

    /** The signed-in user's playlists. Empty for a guest session, which is not an error. */
    suspend fun userPlaylists(uid: Long): List<Playlist> = client.userPlaylists(uid).listOrEmpty()

    /**
     * The liked-songs list, resolved through `/likelist` + batched `/song/detail`.
     *
     * Preferred over `/playlist/track/all` for the built-in liked playlist: its virtual id
     * is not a normal playlist, while `likelist` is the authoritative source.
     */
    suspend fun likedSongs(uid: Long): List<Track> {
        val ids = client.likedSongIds(uid).listOrEmpty()
        if (ids.isEmpty()) return emptyList()
        return client.songsByIds(ids).listOrEmpty()
    }

    suspend fun artistTopSongs(artistId: Long): List<Track> = client.artistTopSongs(artistId).listOrEmpty()

    /**
     * Likes or unlikes [id], returning an error message on failure.
     *
     * Returns the reason as a string rather than a boolean so the caller can say *why* the toggle did
     * not stick — a guest session and a network failure need different wording.
     */
    suspend fun setLiked(id: Long, like: Boolean): String? =
        when (val result = client.likeSong(id, like)) {
            is NetResult.Ok -> null
            is NetResult.Err -> result.message
        }

    /** Whether [id] is liked; false when the session cannot answer (guest, or the call failed). */
    suspend fun isLiked(uid: Long, id: Long): Boolean =
        client.isLiked(uid, id).valueOrNull() ?: false
}

/** Unwraps a list result, treating any failure as "no items". */
internal fun <T> NetResult<List<T>>.listOrEmpty(): List<T> = when (this) {
    is NetResult.Ok -> value
    is NetResult.Err -> emptyList()
}

/** Unwraps a nullable result, treating any failure as absent. */
internal fun <T> NetResult<T>.valueOrNull(): T? = (this as? NetResult.Ok)?.value
