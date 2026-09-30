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
import kotlinx.coroutines.Job
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

    init {
        refreshHome()
        refreshRecentTracks()
        _search.value = _search.value.copy(
            hot = emptyList(),
            history = container.searchHistory.load(),
        )
        viewModelScope.launch {
            _search.value = _search.value.copy(hot = container.music.hotSearch())
        }
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
        _collection.value = CollectionUiState(title = "我喜欢的音乐", coverUrl = coverUrl)
        collectionJob = viewModelScope.launch {
            val tracks = container.music.likedSongs(uid, container.likedSongs.load())
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
     * Re-reads the local liked count for the library row.
     *
     * The library's "喜欢" row shows a count, and since liking is local that count is now known without a
     * network call — so it can be shown for a guest too, and refreshed the moment the heart is tapped.
     */
    var likedCount by mutableStateOf(container.likedSongs.load().size)
        private set

    fun refreshLikedCount() {
        likedCount = container.likedSongs.load().size
    }

    /**
     * Loads the user's playlists once a real account is known.
     *
     * Keyed on the uid so re-logging into a different account refreshes, and calling it
     * repeatedly for the same account is a no-op.
     */
    fun loadUserPlaylists(account: Account?) {
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
