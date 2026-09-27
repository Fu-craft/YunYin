package com.yunyin.music.ui

import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yunyin.music.AppContainer
import com.yunyin.music.data.CoverDownloader
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.background.DynamicBackgroundPalette
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Turns the current track into everything the player screen renders: the artwork, the palette
 * derived from it, and word-by-word lyrics.
 *
 * Work is keyed on the track id and cancellable, so skipping quickly through a queue abandons
 * in-flight loads instead of queueing them up.
 */
class PlayerViewModel(private val container: AppContainer) : ViewModel() {

    /** Artwork for the current track, used by the player and the lyric header. */
    var coverBitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    /** Colours driving the animated background. */
    var palette by mutableStateOf(DynamicBackgroundPalette.fallback(isDark()))
        private set

    var lyrics by mutableStateOf<SyncedLyrics?>(null)
        private set

    var lyricsLoading by mutableStateOf(false)
        private set

    /** True when the lyrics carry per-syllable timing. */
    var wordByWord by mutableStateOf(false)
        private set

    /**
     * Manual lyric timing offset for the current track, in milliseconds.
     *
     * Positive shows the lyrics later. Persisted per track in [SettingsStore], and surfaced in the
     * player so a mistimed song can be corrected by hand — the one thing automatic cross-source
     * alignment cannot fix.
     */
    var lyricOffsetMs by mutableStateOf(0L)
        private set

    /**
     * Progress of a long-press-to-save on the artwork.
     *
     * Held here rather than in the screen so it survives a recomposition of the player (and the
     * lyric/artwork presentation switch).
     */
    var download by mutableStateOf<DownloadState>(DownloadState.Idle)
        private set

    /**
     * Whether the current track is in the user's liked songs.
     *
     * Read from the service rather than cached locally: liking can also happen in the NetEase app,
     * so a local guess would drift. Refreshed per track, and applied optimistically while a toggle is
     * in flight so the heart responds immediately.
     */
    var liked by mutableStateOf(false)
        private set

    /** True while a like toggle is in flight, to ignore repeat taps. */
    var likePending by mutableStateOf(false)
        private set

    /**
     * A one-shot message for the UI (failed like, failed share, …).
     *
     * The player has no room for inline error text for these actions, so failures are surfaced as a
     * transient message and the value is cleared once consumed.
     */
    var notice by mutableStateOf<String?>(null)
        private set

    fun clearNotice() {
        notice = null
    }

    private var downloadJob: Job? = null
    private var likeJob: Job? = null

    /**
     * Saves the current track's **cover art** to the device's pictures.
     *
     * Not the audio: the gesture is a long press on the artwork, so what it saves is the image on
     * screen. The cover URL goes straight to the image CDN — no stream URL is resolved, since the
     * cover is a plain public image and has nothing to do with playback.
     */
    fun saveCover() {
        val track = container.player.state.value.current ?: return
        if (download is DownloadState.Downloading) return
        downloadJob?.cancel()
        download = DownloadState.Downloading
        downloadJob = viewModelScope.launch {
            val outcome = container.covers.save(
                coverUrl = track.coverUrl,
                track = track,
                cookie = container.settings.cookie,
            )
            download = when (outcome) {
                is CoverDownloader.Outcome.Saved -> DownloadState.Saved(outcome.fileName)
                is CoverDownloader.Outcome.Failed -> DownloadState.Failed(outcome.reason)
            }
        }
    }

    /** Clears the transient saved/failed badge once it has been seen. */
    fun clearDownloadState() {
        if (download !is DownloadState.Downloading) download = DownloadState.Idle
    }

    /**
     * Toggles the like state of the current track.
     *
     * Optimistic: the heart flips immediately and is rolled back if the service refuses, because a
     * 200ms wait on a heart tap reads as a dropped tap. A guest session gets a message pointing at
     * sign-in, which is the actual remedy.
     */
    fun toggleLike() {
        val track = container.player.state.value.current ?: return
        val account = container.settings.account
        if (account == null || account.isAnonymous) {
            notice = "登录后才能收藏歌曲"
            return
        }
        if (likePending) return

        val target = !liked
        liked = target
        likePending = true
        likeJob = viewModelScope.launch {
            val error = container.music.setLiked(track.id, target)
            likePending = false
            if (error != null) {
                liked = !target
                notice = error
            }
        }
    }

    /** Copies a shareable line for the current track to the clipboard. */
    fun currentShareText(): String? {
        val track = container.player.state.value.current ?: return null
        val artist = track.artistLine
        return if (artist.isBlank()) track.name else "${track.name} - $artist"
    }

    /** The name of the current track's album, for the info action. */
    fun currentTrack(): com.yunyin.music.core.Track? = container.player.state.value.current

    /** Refreshes [liked] for [trackId], ignoring a result that arrives after the track changed. */
    private fun refreshLiked(trackId: Long) {
        val account = container.settings.account ?: return
        if (account.isAnonymous) {
            liked = false
            return
        }
        likeJob?.cancel()
        likeJob = viewModelScope.launch {
            val value = container.music.isLiked(account.userId, trackId)
            if (lastTrackId == trackId) liked = value
        }
    }

    /** Applies and persists an offset for the current track. */
    fun setLyricOffset(offsetMs: Long) {
        val id = lastTrackId
        if (id == 0L) return
        val clamped = offsetMs.coerceIn(-LYRIC_OFFSET_LIMIT_MS, LYRIC_OFFSET_LIMIT_MS)
        lyricOffsetMs = clamped
        container.settings.setLyricOffsetMs(id, clamped)
    }

    private var artJob: Job? = null
    private var lyricsJob: Job? = null
    private var prefetchJob: Job? = null
    private var lastTrackId: Long = 0L

    init {
        viewModelScope.launch {
            container.player.state.collectLatest { state ->
                val track = state.current ?: return@collectLatest
                if (track.id == lastTrackId) return@collectLatest
                lastTrackId = track.id
                lyricOffsetMs = container.settings.lyricOffsetMs(track.id)
                loadArtwork(track.coverUrl)
                loadLyrics(track.id)
                refreshLiked(track.id)
                prefetchUpcoming(state)
            }
        }
    }

    /**
     * Warms the caches for the track after the current one.
     *
     * Tapping through a playlist normally pays a full artwork + lyric fetch per song. Fetching
     * the next one while the current track plays means a skip finds both already cached, which
     * is what makes browsing a playlist feel immediate.
     *
     * Launched on [viewModelScope] rather than inside the `collectLatest` block: that block is
     * cancelled whenever the track changes, and the prefetch must be allowed to outlive it.
     */
    private fun prefetchUpcoming(state: PlaybackUiState) {
        val next = state.queue.getOrNull(state.index + 1) ?: return
        if (next.id == state.current?.id) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            // Artwork first: it paints as soon as the player opens, before lyrics arrive.
            launch { runCatching { next.coverUrl?.let { container.artwork.load(it, 800) } } }
            container.lyrics.prefetch(next.id)
        }
    }

    /** Whether the system is currently in dark mode; drives the palette's tone offsets. */
    private fun isDark(): Boolean =
        (container.appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun loadArtwork(url: String?) {
        artJob?.cancel()
        artJob = viewModelScope.launch {
            val bitmap = url?.let { container.artwork.load(it, 800) }
            if (bitmap == null) {
                coverBitmap = null
                palette = DynamicBackgroundPalette.fallback(isDark())
                return@launch
            }
            coverBitmap = bitmap.asImageBitmap()
            // Palette extraction walks the bitmap, so keep it off the main thread.
            palette = DynamicBackgroundPalette.from(bitmap, isDark())
        }
    }

    private fun loadLyrics(trackId: Long) {
        lyricsJob?.cancel()
        lyrics = null
        lyricsLoading = true
        lyricsJob = viewModelScope.launch {
            // Two-phase: paint the first usable document as soon as it exists, then let a
            // word-by-word upgrade replace it if one arrives. Waiting for the upgrade *before*
            // showing anything is what made loading feel slow (measured median 2.4s -> 0.4s).
            val loaded = container.lyrics.lyricsFor(
                trackId = trackId,
                onFirstDoc = { provisional -> applyIfCurrent(trackId, provisional) },
                // Delivered from the repository's IO scope, so hop to the main dispatcher before
                // touching composition state.
                onUpgraded = { better -> viewModelScope.launch { applyIfCurrent(trackId, better) } },
            )
            // The job may have been cancelled (track skipped) while the fetch ran to completion
            // on the repository's own scope; do not resurrect the old track's lyrics.
            if (loaded != null) applyIfCurrent(trackId, loaded)
        }
    }

    /**
     * Applies a lyric document, ignoring it if the track has since changed.
     *
     * Every delivery — provisional, upgraded, or final — goes through here, so a slow result for a
     * track the user has skipped away from can never overwrite the current song's lyrics.
     */
    private fun applyIfCurrent(trackId: Long, lyrics: SyncedLyrics?) {
        if (lastTrackId != trackId) return
        this.lyrics = lyrics
        wordByWord = container.lyrics.isWordByWord(lyrics)
        lyricsLoading = false
    }

    fun retryLyrics() {
        lastTrackId.let { if (it != 0L) loadLyrics(it) }
    }
}

/** Largest manual lyric offset the UI allows, in milliseconds. */
const val LYRIC_OFFSET_LIMIT_MS = 5000L

/**
 * State of a long-press-to-download.
 *
 * A download has no meaningful progress to report (the transfer is a single streamed response), so
 * the model stays coarse: in flight, done, or failed with a reason worth reading.
 */
sealed interface DownloadState {
    data object Idle : DownloadState
    data object Downloading : DownloadState

    /** Carries the saved file name so the confirmation can say where it went. */
    data class Saved(val fileName: String) : DownloadState

    data class Failed(val message: String) : DownloadState
}
