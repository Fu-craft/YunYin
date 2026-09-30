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

    /** The signed-in user's playlists. Errors remain distinguishable from a genuinely empty library. */
    suspend fun userPlaylists(uid: Long): NetResult<List<Playlist>> = client.userPlaylists(uid)

    /**
     * The liked-songs list: local likes merged with the account's cloud likes.
     *
     * Three details are deliberate.
     *
     *  - **Local likes come first.** The user just tapped the heart on them, so they belong at the top
     *    where the action is visible; the cloud list is history and follows.
     *  - **Cloud failures are not fatal.** A guest has no cloud list at all and a signed-in user may be
     *    offline, so the cloud half is best-effort: whatever it does or does not return, the local likes
     *    are still shown. Showing nothing because the network failed is the behaviour being removed.
     *  - **De-duplication is by id**, keeping the local copy when both know a track: the local one was
     *    stored from a full [Track] the app was actually playing, whereas the cloud mapping is a snapshot.
     */
    suspend fun likedSongs(uid: Long, local: List<Track>): List<Track> {
        val cloud = if (uid == 0L) emptyList() else cloudLikedSongs(uid)
        return mergeLiked(local, cloud)
    }

    /**
     * The ids in the account's cloud liked list.
     *
     * Exposed so a caller can compute the *merged* count cheaply: the id set is enough to know which
     * local likes are new, and it avoids fetching every track's detail just to count them. Returns an
     * empty set for a guest, and an empty set if the request fails — the local half is what must not be
     * lost, and the caller treats an unknown cloud half as "no extras" rather than as a reason to hide
     * the list.
     */
    suspend fun cloudLikedIds(uid: Long): Set<Long> =
        if (uid == 0L) emptySet() else client.likedSongIds(uid).listOrEmpty().toHashSet()

    /**
     * The account's own liked songs, resolved through `/likelist` + batched `/song/detail`.
     *
     * Preferred over `/playlist/track/all` for the built-in liked playlist: its virtual id
     * is not a normal playlist, while `likelist` is the authoritative source.
     */
    private suspend fun cloudLikedSongs(uid: Long): List<Track> {
        val ids = client.likedSongIds(uid).listOrEmpty()
        if (ids.isEmpty()) return emptyList()
        return client.songsByIds(ids).listOrEmpty()
    }

    suspend fun artistTopSongs(artistId: Long): List<Track> = client.artistTopSongs(artistId).listOrEmpty()

    /**
     * The cover URL for [id].
     *
     * Search results are mapped without artwork (`/search` does not return an album object), so a
     * track the user just found and played can carry a null cover. Asking for the detail fills that
     * in, which is what makes saving the cover work from every entry point rather than only from the
     * shelves that happen to include one.
     */
    suspend fun coverUrlFor(id: Long): String? =
        client.songDetail(listOf(id)).valueOrNull()?.firstOrNull()?.coverUrl
}

/**
 * The liked list's merge rule: local likes first, then cloud likes that are not already known locally.
 *
 * Extracted as a pure function so the three requirements behind it — the local list is authoritative and
 * comes first, a track in both sources appears once, and an empty half never empties the result — are
 * unit-tested rather than only observable by using the app.
 *
 * The local copy is kept when both know a track: it was stored from the [Track] the app was actually
 * playing (with its real cover and duration), whereas the cloud mapping is a list snapshot.
 */
internal fun mergeLiked(local: List<Track>, cloud: List<Track>): List<Track> {
    val localIds = local.mapTo(HashSet()) { it.id }
    return local + cloud.filterNot { it.id in localIds }
}

/**
 * How many songs the liked list holds, given the local list and the account's cloud ids.
 *
 * This exists so the number shown next to the 喜欢 row is the *size of the list the user will get when
 * they open it*, rather than the size of one of its halves. The bug it fixes: the row counted only the
 * locally added songs, so liking one song on top of 563 cloud likes displayed "1 首".
 *
 * Counted from ids rather than by building the list because the cloud tracks' details are not needed to
 * count them — only to display them — so this stays cheap enough to run on every like change.
 */
internal fun mergedLikedCount(local: List<Track>, cloudIds: Set<Long>): Int {
    val localIds = local.mapTo(HashSet()) { it.id }
    return local.size + cloudIds.count { it !in localIds }
}

/** Unwraps a list result, treating any failure as "no items". */
internal fun <T> NetResult<List<T>>.listOrEmpty(): List<T> = when (this) {
    is NetResult.Ok -> value
    is NetResult.Err -> emptyList()
}

/** Unwraps a nullable result, treating any failure as absent. */
internal fun <T> NetResult<T>.valueOrNull(): T? = (this as? NetResult.Ok)?.value
