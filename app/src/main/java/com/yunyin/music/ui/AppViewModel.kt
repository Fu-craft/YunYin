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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Both search catalogues page in this size; the offset given to the repository is a multiple of it. */
private const val SEARCH_PAGE_SIZE = 30

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
    /** True when the signed-in user created this playlist; gates edit/delete/remove affordances. */
    val owned: Boolean = false,
    /** True only for a real playlist (track + playlist ids exist), so 心动模式 has the ids it needs. */
    val canBeatMode: Boolean = false,
)

/** Search tab content. */
data class SearchUiState(
    val keyword: String = "",
    val loading: Boolean = false,
    /** True while an additional page is being appended (the first page is [loading]). */
    val loadingMore: Boolean = false,
    /** Whether another page exists in either catalogue. */
    val canLoadMore: Boolean = false,
    val results: List<Track> = emptyList(),
    val hot: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val searched: Boolean = false,
    /** Typed-ahead suggestions while the field has text and no search has been submitted. */
    val suggestions: com.yunyin.music.data.net.SearchSuggestions =
        com.yunyin.music.data.net.SearchSuggestions(),
    /** Songs that matched by lyric; shown as an extra section, never in place of normal results. */
    val lyricResults: List<Track> = emptyList(),
)

/**
 * Artist page content.
 *
 * `name`/`coverUrl` are seeded from what the caller already has (the tapped row's first artist name), then
 * replaced by the detail response when it lands — so the page is never blank while loading.
 */
enum class CommentsTab { HOT, ALL }

/** Read-only comment sheet: hot and paged-all lists with independent loading/error state. */
data class CommentsUiState(
    val visible: Boolean = false,
    val trackId: Long = 0L,
    val tab: CommentsTab = CommentsTab.HOT,
    val hot: List<com.yunyin.music.core.Comment> = emptyList(),
    val hotLoading: Boolean = false,
    val hotError: String? = null,
    val all: List<com.yunyin.music.core.Comment> = emptyList(),
    val allOffset: Int = 0,
    val allMore: Boolean = false,
    val allLoading: Boolean = false,
    val allError: String? = null,
)

data class ArtistUiState(
    val id: Long = 0L,
    val name: String = "",
    val coverUrl: String? = null,
    val briefDesc: String = "",
    val albumSize: Int = 0,
    val musicSize: Int = 0,
    val hotSongs: List<Track> = emptyList(),
    val allSongs: List<Track> = emptyList(),
    val allOffset: Int = 0,
    val allMore: Boolean = true,
    val loadingSongs: Boolean = false,
    val songsError: String? = null,
    val albums: List<com.yunyin.music.core.AlbumInfo> = emptyList(),
    val albumsOffset: Int = 0,
    val albumsMore: Boolean = true,
    val loadingAlbums: Boolean = false,
    val albumsError: String? = null,
    val loading: Boolean = true,
    /** Header/profile failure; sections still render independently. */
    val error: String? = null,
) {
    /** Kept for the existing play-all/queue call sites: the hot list is the page's headline list. */
    val tracks: List<Track> get() = hotSongs
}

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

    private val _comments = MutableStateFlow(CommentsUiState())
    val comments: StateFlow<CommentsUiState> = _comments.asStateFlow()
    private var commentsJob: kotlinx.coroutines.Job? = null
    private var suggestJob: Job? = null
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

    /** Zero-based index of the page currently shown; the repository derives the provider page from the offset. */
    private var searchPage = 0
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
     * `verify_init_order.py` fails the build if any delegated property drifts below this line.
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
        suggestJob?.cancel()
        if (value.isBlank()) {
            _search.value = _search.value.copy(suggestions = com.yunyin.music.data.net.SearchSuggestions())
            return
        }
        val query = value
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(com.yunyin.music.data.net.DiscoveryRules.SUGGEST_DEBOUNCE_MS)
            val result = runCatching { container.music.searchSuggest(query) }.getOrNull()
            // A stale response must not replace the suggestions of a newer query.
            if (_search.value.keyword != query) return@launch
            _search.value = _search.value.copy(
                suggestions = (result as? com.yunyin.music.core.NetResult.Ok)?.value
                    ?: com.yunyin.music.data.net.SearchSuggestions(),
            )
        }
    }

    /** Lyric-fragment fallback, only when the normal search found no songs. Silent on failure. */
    private suspend fun loadLyricMatches(keyword: String) {
        val result = runCatching { container.music.searchLyric(keyword) }.getOrNull()
        if (_search.value.keyword != keyword) return
        _search.value = _search.value.copy(
            lyricResults = (result as? com.yunyin.music.core.NetResult.Ok)?.value.orEmpty(),
        )
    }

    fun submitSearch(keyword: String = _search.value.keyword) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        container.searchHistory.add(trimmed)
        searchJob?.cancel()
        _search.value = _search.value.copy(
            keyword = trimmed,
            loading = true,
            loadingMore = false,
            canLoadMore = false,
            searched = true,
            history = container.searchHistory.load(),
            suggestions = com.yunyin.music.data.net.SearchSuggestions(),
            lyricResults = emptyList(),
        )
        searchJob = viewModelScope.launch {
            when (val result = container.music.search(trimmed, offset = 0)) {
                is com.yunyin.music.core.NetResult.Ok -> {
                    searchPage = 0
                    _search.value = _search.value.copy(
                        loading = false,
                        results = result.value.tracks,
                        canLoadMore = result.value.hasMore,
                    )
                    if (result.value.tracks.isEmpty()) loadLyricMatches(trimmed)
                }
                is com.yunyin.music.core.NetResult.Err -> {
                    _search.value = _search.value.copy(loading = false, results = emptyList())
                    loadLyricMatches(trimmed)
                }
            }
        }
    }

    /**
     * Appends the next page of both catalogues.
     *
     * The offset is in NetEase's units and the provider page is derived from it, so the two lists advance
     * together; results are appended by id (and by "same recording") so a provider duplicate of an earlier NetEase
     * row cannot appear twice as the user scrolls.
     */
    fun loadMoreSearch() {
        val state = _search.value
        val keyword = state.keyword.trim()
        if (keyword.isEmpty() || state.loading || state.loadingMore || !state.canLoadMore) return
        _search.value = state.copy(loadingMore = true)
        searchJob = viewModelScope.launch {
            val nextPage = searchPage + 1
            when (val result = container.music.search(keyword, offset = nextPage * SEARCH_PAGE_SIZE)) {
                is com.yunyin.music.core.NetResult.Ok -> {
                    searchPage = nextPage
                    _search.value = _search.value.copy(
                        loadingMore = false,
                        results = com.yunyin.music.data.providers.SearchMerge.appendUnique(
                            _search.value.results,
                            result.value.tracks,
                        ),
                        canLoadMore = result.value.hasMore,
                    )
                }
                is com.yunyin.music.core.NetResult.Err ->
                    _search.value = _search.value.copy(loadingMore = false)
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchPage = 0
        _search.value = _search.value.copy(
            keyword = "",
            results = emptyList(),
            searched = false,
            loading = false,
            loadingMore = false,
            canLoadMore = false,
            suggestions = com.yunyin.music.data.net.SearchSuggestions(),
            lyricResults = emptyList(),
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

    fun openPlaylist(playlist: Playlist, uid: Long = 0L) {
        collectionJob?.cancel()
        val requestId = ++collectionRequestId
        _collection.value = CollectionUiState(
            id = playlist.id,
            title = playlist.name,
            subtitle = "${playlist.trackCount} 首",
            coverUrl = playlist.coverUrl,
            description = playlist.description,
            playCount = playlist.playCount,
            owned = com.yunyin.music.data.playlist.PlaylistOwnership.canEdit(playlist, uid),
            canBeatMode = true,
        )
        collectionJob = viewModelScope.launch {
            // Progressive: the first batch renders immediately, later batches append in order. A 566/1267-track
            // playlist used to stop at 200 because the loader made one `limit = 200` request.
            val tracks = container.music.playlistTracksProgressive(playlist.id, totalHint = playlist.trackCount) { partial ->
                val shown = _collection.value?.id
                if (shown != null &&
                    collectionResultApplies(requestId, collectionRequestId, shown, playlist.id)
                ) {
                    _collection.value = _collection.value?.copy(
                        tracks = partial,
                        loading = partial.isEmpty(),
                        subtitle = "${partial.size} 首",
                    )
                }
            }
            val shown = _collection.value?.id
            if (shown != null &&
                collectionResultApplies(requestId, collectionRequestId, shown, playlist.id)
            ) {
                _collection.value = _collection.value?.copy(
                    tracks = tracks,
                    loading = false,
                    subtitle = "${tracks.size} 首",
                )
            }
        }
    }

    /** Re-reads the visible collection's tracks after a write, and reloads playlist metadata (name/description). */
    fun refreshCollection(forceNetwork: Boolean = false) {
        val current = _collection.value ?: return
        if (current.id <= 0L) return
        collectionJob?.cancel()
        val requestId = ++collectionRequestId
        collectionJob = viewModelScope.launch {
            val tracks = container.music.playlistTracksProgressive(
                id = current.id,
                totalHint = current.tracks.size.coerceAtLeast(0),
                forceRefresh = forceNetwork,
            ) { partial ->
                if (requestId == collectionRequestId) {
                    _collection.value = _collection.value?.copy(
                        tracks = partial,
                        loading = partial.isEmpty(),
                        subtitle = "${partial.size} 首",
                    )
                }
            }
            val detail = container.music.playlist(current.id)
            if (requestId == collectionRequestId) {
                _collection.value = _collection.value?.copy(
                    title = detail?.name ?: current.title,
                    description = detail?.description ?: current.description,
                    tracks = tracks,
                    loading = false,
                    subtitle = "${tracks.size} 首",
                )
            }
        }
    }

    /** Force-refreshes the library after a playlist write (create/rename/delete). */
    fun refreshUserPlaylists(account: Account?) {
        loadedForUid = 0L
        loadUserPlaylists(account)
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
        _artist.value = ArtistUiState(id = artistId, name = seedName, loading = true)
        artistJob = viewModelScope.launch {
            // Header and each section are requested together but applied independently: an album failure must not
            // blank the header or the song lists.
            coroutineScope {
                val detail = async { runCatching { container.music.artistDetail(artistId) } }
                val hot = async { runCatching { container.music.artistTopSongs(artistId) } }
                val all = async { runCatching { container.music.artistSongsPage(artistId, 0, ALL_PAGE, "time") } }
                val albums = async { runCatching { container.music.artistAlbumsPage(artistId, 0, ALBUM_PAGE) } }

                detail.await().getOrNull()?.let { info ->
                    if (artistRequestId == requestId && _artist.value?.id == artistId) {
                        _artist.value = _artist.value?.copy(
                            name = info.name.ifBlank { seedName },
                            coverUrl = info.coverUrl,
                            briefDesc = info.briefDesc,
                            albumSize = info.albumSize,
                            musicSize = info.musicSize,
                            loading = false,
                        )
                    }
                }
                val hotResult = hot.await()
                if (artistRequestId == requestId && _artist.value?.id == artistId) {
                    _artist.value = _artist.value?.copy(
                        hotSongs = hotResult.getOrNull().orEmpty(),
                        loading = false,
                        error = if (detail.await().isFailure && hotResult.isFailure) "歌手信息加载失败" else _artist.value?.error,
                    )
                }
                all.await().getOrNull()?.let { page ->
                    if (artistRequestId == requestId && _artist.value?.id == artistId) {
                        _artist.value = _artist.value?.copy(
                            allSongs = page.tracks,
                            allOffset = page.tracks.size,
                            allMore = page.more,
                            songsError = null,
                        )
                    }
                } ?: run {
                    if (artistRequestId == requestId && _artist.value?.id == artistId) {
                        _artist.value = _artist.value?.copy(songsError = "全部歌曲加载失败")
                    }
                }
                albums.await().getOrNull()?.let { page ->
                    if (artistRequestId == requestId && _artist.value?.id == artistId) {
                        _artist.value = _artist.value?.copy(
                            albums = page.albums,
                            albumsOffset = page.albums.size,
                            albumsMore = page.more,
                            albumsError = null,
                        )
                    }
                } ?: run {
                    if (artistRequestId == requestId && _artist.value?.id == artistId) {
                        _artist.value = _artist.value?.copy(albumsError = "专辑加载失败")
                    }
                }
            }
        }
    }

    /** Loads the next page of 全部歌曲, appending without duplicates. */
    fun loadMoreArtistSongs() {
        val current = _artist.value ?: return
        if (current.loadingSongs || !current.allMore) return
        val requestId = artistRequestId
        _artist.value = current.copy(loadingSongs = true, songsError = null)
        viewModelScope.launch {
            val page = runCatching {
                container.music.artistSongsPage(current.id, current.allOffset, ALL_PAGE, "time")
            }.getOrNull()
            val now = _artist.value ?: return@launch
            if (artistRequestId != requestId || now.id != current.id) return@launch
            _artist.value = if (page == null) {
                now.copy(loadingSongs = false, songsError = "加载更多歌曲失败")
            } else {
                now.copy(
                    allSongs = com.yunyin.music.ui.screens.ArtistUiRules.appendUnique(now.allSongs, page.tracks),
                    allOffset = now.allOffset + page.tracks.size,
                    allMore = page.more,
                    loadingSongs = false,
                )
            }
        }
    }

    /** Loads the next page of 专辑. */
    fun loadMoreArtistAlbums() {
        val current = _artist.value ?: return
        if (current.loadingAlbums || !current.albumsMore) return
        val requestId = artistRequestId
        _artist.value = current.copy(loadingAlbums = true, albumsError = null)
        viewModelScope.launch {
            val page = runCatching {
                container.music.artistAlbumsPage(current.id, current.albumsOffset, ALBUM_PAGE)
            }.getOrNull()
            val now = _artist.value ?: return@launch
            if (artistRequestId != requestId || now.id != current.id) return@launch
            _artist.value = if (page == null) {
                now.copy(loadingAlbums = false, albumsError = "加载更多专辑失败")
            } else {
                val seen = now.albums.mapTo(HashSet()) { it.id }
                now.copy(
                    albums = now.albums + page.albums.filter { seen.add(it.id) },
                    albumsOffset = now.albumsOffset + page.albums.size,
                    albumsMore = page.more,
                    loadingAlbums = false,
                )
            }
        }
    }

    /** Opens an album in the existing collection page (`/album?id=` sources its track list). */
    fun openAlbum(albumId: Long, title: String, coverUrl: String?) {
        collectionJob?.cancel()
        if (albumId <= 0L) return
        val requestId = ++collectionRequestId
        _collection.value = CollectionUiState(id = albumId, title = title, coverUrl = coverUrl, loading = true)
        collectionJob = viewModelScope.launch {
            val tracks = runCatching { container.music.albumTracks(albumId) }.getOrElse { emptyList() }
            if (requestId == collectionRequestId) {
                _collection.value = _collection.value?.copy(
                    tracks = tracks,
                    loading = false,
                    subtitle = "${tracks.size} 首",
                )
            }
        }
    }
    fun closeArtist() {
        artistJob?.cancel()
        artistJob = null
        artistRequestId++
        _artist.value = null
    }

    fun openComments(trackId: Long) {
        if (trackId <= 0L) return
        commentsJob?.cancel()
        _comments.value = CommentsUiState(
            visible = true,
            trackId = trackId,
            hotLoading = true,
            allLoading = true,
        )
        commentsJob = viewModelScope.launch {
            launch {
                val result = runCatching { container.music.hotComments(trackId) }.getOrNull()
                if (_comments.value.trackId != trackId) return@launch
                _comments.value = when (result) {
                    null -> _comments.value.copy(hotLoading = false, hotError = "热门评论加载失败")
                    is com.yunyin.music.core.NetResult.Ok ->
                        _comments.value.copy(hot = result.value, hotLoading = false, hotError = null)
                    is com.yunyin.music.core.NetResult.Err ->
                        _comments.value.copy(hotLoading = false, hotError = result.message)
                }
            }
            launch {
                val result = runCatching { container.music.comments(trackId, offset = 0) }.getOrNull()
                if (_comments.value.trackId != trackId) return@launch
                _comments.value = when (result) {
                    null -> _comments.value.copy(allLoading = false, allError = "评论加载失败")
                    is com.yunyin.music.core.NetResult.Ok -> _comments.value.copy(
                        all = result.value.comments,
                        allOffset = result.value.comments.size,
                        allMore = result.value.more,
                        allLoading = false,
                        allError = null,
                    )
                    is com.yunyin.music.core.NetResult.Err ->
                        _comments.value.copy(allLoading = false, allError = result.message)
                }
            }
        }
    }

    fun selectCommentsTab(tab: CommentsTab) {
        _comments.value = _comments.value.copy(tab = tab)
    }

    fun loadMoreComments() {
        val current = _comments.value
        if (!com.yunyin.music.data.net.CommentPagingRules.canLoadMore(current.allMore, current.allLoading)) return
        val trackId = current.trackId
        _comments.value = current.copy(allLoading = true, allError = null)
        commentsJob = viewModelScope.launch {
            val result = runCatching { container.music.comments(trackId, offset = current.allOffset) }.getOrNull()
            val now = _comments.value
            if (now.trackId != trackId) return@launch
            _comments.value = when (result) {
                null -> now.copy(allLoading = false, allError = "加载更多评论失败")
                is com.yunyin.music.core.NetResult.Ok -> now.copy(
                    all = com.yunyin.music.data.net.CommentPagingRules.appendUnique(now.all, result.value.comments),
                    allOffset = now.allOffset + result.value.comments.size,
                    allMore = result.value.more,
                    allLoading = false,
                    allError = null,
                )
                is com.yunyin.music.core.NetResult.Err -> now.copy(allLoading = false, allError = result.message)
            }
        }
    }

    fun closeComments() {
        commentsJob?.cancel()
        commentsJob = null
        _comments.value = CommentsUiState()
    }

    companion object {
        private const val ALL_PAGE = 30
        private const val ALBUM_PAGE = 20

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
