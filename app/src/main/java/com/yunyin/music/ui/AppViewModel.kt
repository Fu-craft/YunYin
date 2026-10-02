package com.yunyin.music.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yunyin.music.AppContainer
import com.yunyin.music.core.Account
import com.yunyin.music.core.HomeFeed
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.together.LocalPlayback
import com.yunyin.music.data.together.SyncAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Discover-tab content. */
data class HomeUiState(
    val loading: Boolean = true,
    val feed: HomeFeed = HomeFeed(),
    val error: String? = null,
)

/** A playlist/chart detail page. */
data class CollectionUiState(
    /** The playlist id, used to build a shareable link. Zero for the liked list, which has none. */
    val id: Long = 0L,
    val title: String = "",
    val subtitle: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val playCount: Long = 0L,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
)

/** Search tab content. */
data class SearchUiState(
    val keyword: String = "",
    val loading: Boolean = false,
    val results: List<Track> = emptyList(),
    val hot: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val searched: Boolean = false,
)

/**
 * Artist page content.
 *
 * `name`/`coverUrl` are seeded from what the caller already has (the tapped row's first artist name), then
 * replaced by the detail response when it lands — so the page is never blank while loading.
 */
data class ArtistUiState(
    val id: Long = 0L,
    val name: String = "",
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Owns catalog state for the browsing tabs.
 *
 * One view model rather than one per tab: the tabs share the [AppContainer] repositories
 * and the sessions are short-lived, so isolating them would add ceremony without benefit.
 */
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _collection = MutableStateFlow<CollectionUiState?>(null)
    val collection: StateFlow<CollectionUiState?> = _collection.asStateFlow()

    private val _artist = MutableStateFlow<ArtistUiState?>(null)
    val artist: StateFlow<ArtistUiState?> = _artist.asStateFlow()
    private var artistJob: Job? = null
    private var artistRequestId = 0L

    /** Recently played tracks, newest first, recorded locally as songs are played. */
    var recentTracks by mutableStateOf<List<Track>>(emptyList())
        private set

    /** Re-reads play history; called on start and whenever a track is played. */
    fun refreshRecentTracks() {
        recentTracks = container.playHistory.load()
    }

    /** The signed-in user's playlists; empty for a guest session. */
    var userPlaylists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    var userPlaylistsLoading by mutableStateOf(false)
        private set

    /** Set when a real (non-anonymous) account is known, so the library can load. */
    private var loadedForUid: Long = 0L

    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var collectionJob: Job? = null
    private var collectionRequestId = 0L
    private var playlistsJob: Job? = null

    /**
     * The number of songs in the liked list, for the library's 喜欢 row.
     *
     * Computed by [com.yunyin.music.data.LikedSongsRepository] — the same rule the list itself uses — so
     * this number and the number of rows inside the list are the same by construction. It was previously a
     * second implementation, and that is how it came to read "1 首" beside a 564-song list.
     *
     * `null` means "the cloud half is not known yet", which is not the same as zero: the row then falls
     * back to the cloud playlist's own count rather than briefly showing the local total alone.
     */
    var likedCount by mutableStateOf<Int?>(null)
        private set

    private var likedCountJob: Job? = null

    /**
     * **Property initialisation order — do not move this below a `by`-delegated property.**
     *
     * A class body runs in source order, so during `init` every property declared *after* it is still
     * uninitialised. `likedCount` is `by mutableStateOf`, i.e. the backing field does not exist until its
     * own line runs, and `refreshLikedCount()` writes to it from the coroutine it launches. With `init`
     * above that line the write lands on a null delegate and the process dies on the first frame:
     *
     * ```
     * Attempt to invoke interface method 'void ...MutableState.setValue(java.lang.Object)'
     *   on a null object reference
     * ```
     *
     * So every delegated-state property must be declared above this block, not merely referenced by it.
     */
    init {
        refreshHome()
        refreshRecentTracks()
        refreshLikedCount()
        _search.value = _search.value.copy(
            hot = emptyList(),
            history = container.searchHistory.load(),
        )
        viewModelScope.launch {
            _search.value = _search.value.copy(hot = container.music.hotSearch())
        }
        wireTogether()
    }

    // ---------------------------------------------------------------- listen together

    private val together get() = container.together
    val togetherState get() = together.state

    /**
     * Connects the room engine to the player, in both directions.
     *
     * Reading playback and applying a correction are supplied here rather than inside the engine so
     * that `data.together` stays free of the playback and UI layers — it can then be exercised with a
     * fake in a test, and the dependency arrow keeps pointing the way the rest of the app points it.
     */
    private fun wireTogether() {
        together.localPlayback = {
            val playback = container.player.state.value
            playback.current?.let { track ->
                LocalPlayback(
                    songId = track.id,
                    // `positionMsNow()` rather than the state's `positionMs`: the state only republishes
                    // four times a second, so the published figure lags by up to 250ms, and the peer is
                    // comparing against this.
                    positionMs = container.player.positionMsNow(),
                    playing = playback.isPlaying,
                )
            }
        }
        together.applyAction = { action -> applyTogetherAction(action) }
        // The room publishes this device's queue once when a session starts, so the server side of the room
        // is fully set up rather than only half of it (see TogetherSession.localQueue).
        together.localQueue = { container.player.state.value.queue.map { it.id } }
    }

    /**
     * Applies one correction from the room.
     *
     * A different song has to be *resolved* first: the peer only sends an id, and the player needs a
     * full [com.yunyin.music.core.Track]. That fetch is asynchronous, so the action is handled in a
     * coroutine and the resulting track is checked again against what the room says before it plays —
     * otherwise a slow lookup could start a song the room moved on from.
     */
    private fun applyTogetherAction(action: SyncAction) {
        when (action) {
            SyncAction.None -> Unit
            SyncAction.Play -> container.player.setPlaying(true)
            SyncAction.Pause -> container.player.setPlaying(false)
            is SyncAction.Seek -> container.player.seekTo(action.positionMs)
            is SyncAction.PlaySong -> {
                val wanted = action.songId
                viewModelScope.launch {
                    val track = container.music.track(wanted) ?: return@launch
                    // Re-check: the room may have moved on while the lookup was in flight.
                    val stillWanted = together.state.value.peers.any { it.songId == wanted }
                    if (!stillWanted) return@launch
                    container.player.playSingle(track)
                    container.player.seekTo(action.positionMs)
                    container.playHistory.record(track)
                    refreshRecentTracks()
                }
            }
        }
    }

    /** Creates a room as this installation, or reports that a real login is needed. */
    fun createTogetherRoom(): String? {
        val uid = togetherMemberId() ?: return "请先登录后再使用一起听"
        together.createRoom(uid = uid, name = togetherName())
        return null
    }

    fun joinTogetherRoom(code: String): String? {
        val uid = togetherMemberId() ?: return "请先登录后再使用一起听"
        together.joinRoom(code = code, uid = uid, name = togetherName())
        return null
    }

    /**
     * Publishes the queue this device is playing from, so a joiner receives the list and not only the
     * current song.
     *
     * The version is a millisecond stamp, which is what the endpoint expects to order successive
     * replacements. The account id is resolved in the transport (it has to be a real one for the server
     * to accept the command), so nothing account-shaped is needed here.
     */
    fun publishTogetherQueue(songIds: List<Long>) {
        val uid = togetherMemberId() ?: return
        together.publishQueue(songIds, uid, System.currentTimeMillis())
    }

    fun leaveTogetherRoom() {
        val uid = togetherMemberId() ?: return
        together.leaveRoom(uid)
    }

    /**
     * Clears a transient room notice (an ended room, a failed join) without leaving.
     *
     * Called when the sheet closes, so reopening it does not re-present a message the user has
     * already read.
     */
    fun dismissTogetherNotice() {
        together.dismissEnded()
    }

    /**
     * This installation's member id, or null when not signed in to a real account.
     *
     * The id itself is per-installation rather than per-account, which is what makes the feature
     * usable from one account on two devices — see `SettingsStore.togetherMemberId`. The login check
     * is about identity to the *peer* (a name worth showing), not about the transport.
     */
    private fun togetherMemberId(): String? {
        val account = container.settings.account ?: return null
        if (account.isAnonymous) return null
        return container.settings.togetherMemberId
    }

    /** The name shown to the other member. */
    private fun togetherName(): String {
        val account = container.settings.account
        return account?.nickname?.takeIf { it.isNotBlank() } ?: "云音用户"
    }

    fun refreshHome() {
        homeJob?.cancel()
        _home.value = _home.value.copy(loading = true, error = null)
        homeJob = viewModelScope.launch {
            when (val result = container.music.loadHome()) {
                is com.yunyin.music.core.NetResult.Ok ->
                    _home.value = HomeUiState(loading = false, feed = result.value)
                is com.yunyin.music.core.NetResult.Err ->
                    _home.value = _home.value.copy(loading = false, error = result.message)
            }
        }
    }

    // ---------------------------------------------------------------- search

    fun onKeywordChange(value: String) {
        _search.value = _search.value.copy(keyword = value)
    }

    fun submitSearch(keyword: String = _search.value.keyword) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        container.searchHistory.add(trimmed)
        searchJob?.cancel()
        _search.value = _search.value.copy(
            keyword = trimmed,
            loading = true,
            searched = true,
            history = container.searchHistory.load(),
        )
        searchJob = viewModelScope.launch {
            when (val result = container.music.search(trimmed)) {
                is com.yunyin.music.core.NetResult.Ok ->
                    _search.value = _search.value.copy(loading = false, results = result.value)
                is com.yunyin.music.core.NetResult.Err ->
                    _search.value = _search.value.copy(loading = false, results = emptyList())
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = _search.value.copy(
            keyword = "",
            results = emptyList(),
            searched = false,
            loading = false,
        )
    }

    fun removeHistory(keyword: String) {
        container.searchHistory.remove(keyword)
        _search.value = _search.value.copy(history = container.searchHistory.load())
    }

    fun clearHistory() {
        container.searchHistory.clear()
        _search.value = _search.value.copy(history = emptyList())
    }

    // ---------------------------------------------------------------- detail

    fun openPlaylist(playlist: Playlist) {
        collectionJob?.cancel()
        val requestId = ++collectionRequestId
        _collection.value = CollectionUiState(
            id = playlist.id,
            title = playlist.name,
            subtitle = "${playlist.trackCount} 首",
            coverUrl = playlist.coverUrl,
            description = playlist.description,
            playCount = playlist.playCount,
        )
        collectionJob = viewModelScope.launch {
            val tracks = container.music.playlistTracks(playlist.id)
            val shown = _collection.value?.id
            if (shown != null &&
                collectionResultApplies(requestId, collectionRequestId, shown, playlist.id)
            ) {
                _collection.value = _collection.value?.copy(tracks = tracks, loading = false)
            }
        }
    }

    /**
     * Opens the built-in liked list: the local likes merged with the account's cloud likes.
     *
     * The local half is what makes the player's heart meaningful — it is written by tapping the heart and
     * shows up here immediately, for a guest as well as a signed-in user. The cloud half is best-effort
     * and simply absent when there is no account or no connection; see `MusicRepository.likedSongs`.
     */
    fun openLikedSongs(uid: Long, coverUrl: String?) {
        collectionJob?.cancel()
        val requestId = ++collectionRequestId
        // A song added locally is newer than anything the cloud cover can show, so it takes the cover.
        // Without this the cover stayed on the cloud playlist's own artwork and liking a song here never
        // changed it.
        val cover = container.liked.newestAddedCoverUrl() ?: coverUrl
        _collection.value = CollectionUiState(title = "我喜欢的音乐", coverUrl = cover)
        collectionJob = viewModelScope.launch {
            val tracks = container.liked.tracks(uid)
            val shown = _collection.value?.id
            // The liked list has no playlist id, so it is identified by the zero id it is opened with.
            if (shown != null && collectionResultApplies(requestId, collectionRequestId, shown, 0L)) {
                _collection.value = _collection.value?.copy(
                    subtitle = "${tracks.size} 首",
                    tracks = tracks,
                    loading = false,
                )
            }
        }
    }

    /**
     * Recomputes [likedCount] from the shared repository.
     *
     * Safe to call on every like tap: the cloud half is cached inside the repository, so after the first
     * load this is a local computation. The result is applied only if it still describes the account that
     * asked, which is the same stale-answer rule used for the other async loads in this class.
     */
    fun refreshLikedCount() {
        val uid = accountUid()
        likedCountJob?.cancel()
        likedCountJob = viewModelScope.launch {
            val count = container.liked.count(uid)
            if (accountUid() == uid) likedCount = count
        }
    }

    /** The uid to key liked data on; 0 for a guest, which means "no cloud half". */
    private fun accountUid(): Long {
        val current = container.settings.account ?: return 0L
        return if (current.isAnonymous) 0L else current.userId
    }

    /**
     * Loads the user's playlists once a real account is known.
     *
     * Keyed on the uid so re-logging into a different account refreshes, and calling it
     * repeatedly for the same account is a no-op.
     */
    fun loadUserPlaylists(account: Account?) {
        // The liked count depends on the account too (its cloud likes), so the same entry points that
        // refresh the playlists also refresh it — otherwise signing in would leave the count describing
        // the previous session. The repository's cloud cache is dropped for the same reason.
        container.liked.forgetCloud()
        refreshLikedCount()

        val uid = account?.userId ?: 0L
        if (account == null || account.isAnonymous || uid == 0L) {
            userPlaylists = emptyList()
            loadedForUid = 0L
            return
        }
        if (uid == loadedForUid && userPlaylists.isNotEmpty()) return
        playlistsJob?.cancel()
        userPlaylistsLoading = true
        playlistsJob = viewModelScope.launch {
            // Keep failure distinct from an empty library: only a successful (possibly empty) response may
            // be cached against the uid, so a failed load is retried when the screen is revisited.
            when (val result = container.music.userPlaylists(uid)) {
                is com.yunyin.music.core.NetResult.Ok -> {
                    userPlaylists = result.value
                    loadedForUid = uid
                }
                is com.yunyin.music.core.NetResult.Err -> {
                    userPlaylists = emptyList()
                }
            }
            userPlaylistsLoading = false
        }
    }

    fun clearUserPlaylists() {
        userPlaylists = emptyList()
        loadedForUid = 0L
    }

    fun openChart(id: Long, title: String, coverUrl: String?) {
        collectionJob?.cancel()
        val requestId = ++collectionRequestId
        _collection.value = CollectionUiState(id = id, title = title, coverUrl = coverUrl)
        collectionJob = viewModelScope.launch {
            val tracks = container.music.playlistTracks(id)
            val shown = _collection.value?.id
            if (shown != null && collectionResultApplies(requestId, collectionRequestId, shown, id)) {
                _collection.value = _collection.value?.copy(tracks = tracks, loading = false)
            }
        }
    }

    fun closeCollection() {
        collectionJob?.cancel()
        collectionJob = null
        collectionRequestId++
        _collection.value = null
    }

    // ---------------------------------------------------------------- artist

    /**
     * Opens an artist page from the artist's *name*, as printed on a row.
     *
     * Resolving the id is a search round trip, so the page opens immediately seeded with the name and
     * fills in as the lookups land; a name that resolves to nothing simply does not open a page, which is
     * preferable to a page about the wrong artist.
     */
    fun openArtistByName(name: String) {
        artistJob?.cancel()
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val requestId = ++artistRequestId
        artistJob = viewModelScope.launch {
            val id = container.music.artistIdFor(trimmed) ?: return@launch
            if (artistRequestId != requestId) return@launch
            openArtist(id, trimmed)
        }
    }

    /**
     * Opens an artist page for [artistId].
     *
     * [seedName] is the artist name as printed on the row that was tapped, shown immediately so the page
     * never opens blank; the detail call then replaces the cover and confirms the name. Results are gated
     * by the same request-identity rule the playlist uses, since a user can leave the page before the
     * artist call returns.
     */
    fun openArtist(artistId: Long, seedName: String) {
        artistJob?.cancel()
        if (artistId == 0L) return
        val requestId = ++artistRequestId
        _artist.value = ArtistUiState(id = artistId, name = seedName)
        artistJob = viewModelScope.launch {
            // Fire both together: the identity and the songs are independent, and the page renders what
            // has arrived rather than waiting for the slower of the two.
            coroutineScope {
                val info = async { container.music.artistInfo(artistId) }
                val top = async { container.music.artistTopSongs(artistId) }

                info.await()?.let { artist ->
                    if (artistRequestId == requestId && _artist.value?.id == artistId) {
                        _artist.value = _artist.value?.copy(
                            name = artist.name.ifBlank { seedName },
                            coverUrl = artist.coverUrl,
                        )
                    }
                }
                val songs = top.await()
                if (artistRequestId == requestId && _artist.value?.id == artistId) {
                    _artist.value = _artist.value?.copy(tracks = songs, loading = false)
                }
            }
        }
    }

    fun closeArtist() {
        artistJob?.cancel()
        artistJob = null
        artistRequestId++
        _artist.value = null
    }

    companion object {
        /**
         * Whether an async collection result may still be applied.
         *
         * Two ways a result is stale, and both were real: a newer request has started (the user opened
         * something else while this one was in flight), or the page on screen is no longer the one this
         * request was for. Applying either would put one playlist's tracks under another's header.
         */
        internal fun collectionResultApplies(
            requestId: Long,
            currentRequestId: Long,
            shownId: Long,
            resultId: Long,
        ): Boolean = requestId == currentRequestId && shownId == resultId
    }
}
