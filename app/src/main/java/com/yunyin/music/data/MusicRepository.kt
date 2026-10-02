package com.yunyin.music.data

import com.yunyin.music.core.HomeFeed
import com.yunyin.music.core.ArtistInfo
import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.TogetherRemoteState
import com.yunyin.music.core.TogetherRoomInfo
import com.yunyin.music.core.TogetherStatus
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
     * The account's own liked songs, as a result rather than a bare list.
     *
     * A `NetResult` on purpose: the caller (the liked-songs repository) has to tell "the account has no
     * cloud likes" from "the request failed", because only the first may be counted as zero. Collapsing
     * both to an empty list is what would make an un-liked song reappear or a count understate.
     */
    suspend fun cloudLikedTracks(uid: Long): NetResult<List<Track>> {
        if (uid == 0L) return NetResult.Ok(emptyList())
        return when (val ids = client.likedSongIds(uid)) {
            is NetResult.Err -> ids
            is NetResult.Ok -> if (ids.value.isEmpty()) {
                NetResult.Ok(emptyList())
            } else {
                client.songsByIds(ids.value)
            }
        }
    }

    suspend fun artistTopSongs(artistId: Long): List<Track> = client.artistTopSongs(artistId).listOrEmpty()

    /** The artist's name and cover, for the artist page's header. */
    suspend fun artistInfo(artistId: Long): ArtistInfo? =
        client.artistDetail(artistId).valueOrNull()

    /** Resolves an artist *name* to the id its page needs; null when the search finds no such artist. */
    suspend fun artistIdFor(name: String): Long? = client.artistIdByName(name)

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

    /**
     * A single track by id, for listen-together.
     *
     * The relay carries only a song id (that is all the peer knows), so the receiving device has to
     * turn it back into a playable [com.yunyin.music.core.Track]. Goes through `/song/detail`, the same
     * call [coverUrlFor] uses.
     */
    suspend fun track(id: Long): com.yunyin.music.core.Track? =
        client.songDetail(listOf(id)).valueOrNull()?.firstOrNull()

    // ---------------------------------------------------------------- listen together (official API)
    //
    // Thin passthroughs, deliberately *not* flattened to null-on-failure: the session needs to tell
    // "the room ended" from "the network is down" to decide whether to keep polling or stop.

    suspend fun togetherCreateRoom(): NetResult<TogetherRoomInfo> = client.togetherRoomCreate()

    /**
     * The signed-in account, for the one thing the room engine needs it for: recognising its own
     * commands. The official room records the *author* of its current command, and a device that
     * followed its own echo would chase itself.
     */
    suspend fun account(): com.yunyin.music.core.Account? =
        (client.loginStatus() as? NetResult.Ok)?.value

    suspend fun togetherRoomCheck(roomId: String): NetResult<Boolean> = client.togetherRoomCheck(roomId)

    suspend fun togetherAccept(roomId: String, inviterId: Long): NetResult<Unit> =
        client.togetherAccept(roomId, inviterId)

    suspend fun togetherRemoteState(roomId: String): NetResult<TogetherRemoteState> =
        client.togetherPlaylistGet(roomId)

    suspend fun togetherStatus(): NetResult<TogetherStatus> = client.togetherStatus()

    suspend fun togetherHeartbeat(roomId: String, songId: Long, playing: Boolean, positionMs: Long): NetResult<Unit> =
        client.togetherHeartbeat(roomId, songId, playing, positionMs)

    suspend fun togetherCommand(
        roomId: String,
        commandType: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        clientSeq: Long,
    ): NetResult<Unit> =
        client.togetherPlayCommand(roomId, commandType, songId, positionMs, playing, clientSeq)

    suspend fun togetherSyncList(
        roomId: String,
        displayList: List<Long>,
        userId: Long,
        version: Long,
    ): NetResult<Unit> = client.togetherSyncList(roomId, displayList, userId, version)

    suspend fun togetherEnd(roomId: String): NetResult<Unit> = client.togetherEnd(roomId)
}

/**
 * Unwraps a list result, treating any failure as "no items".
 *
 * The liked-list logic deliberately does *not* use this: it needs failures to stay visible (see
 * `cloudLikedTracks`). It is for the callers where "no items" is the right degradation.
 */
internal fun <T> NetResult<List<T>>.listOrEmpty(): List<T> = when (this) {
    is NetResult.Ok -> value
    is NetResult.Err -> emptyList()
}

/** Unwraps a nullable result, treating any failure as absent. */
internal fun <T> NetResult<T>.valueOrNull(): T? = (this as? NetResult.Ok)?.value
