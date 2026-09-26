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
     * Progress of a long-press-to-download on the artwork.
     *
     * Held here rather than in the screen so it survives a recomposition of the player (and the
     * lyric/artwork presentation switch), and so the download is not cancelled by the user rotating
     * or navigating the player's own UI.
     */
    var download by mutableStateOf<DownloadState>(DownloadState.Idle)
        private set

    private var downloadJob: Job? = null

    /**
     * Downloads the current track and files it in the device's music collection.
     *
     * Resolves the stream URL first: NetEase hands out short-lived signed links, so the URL is not
     * reusable and cannot be pre-fetched. The state machine is deliberately coarse (downloading →
     * saved / failed) because the transfer reports no progress of its own, and a fake percentage
     * would be worse than an honest indeterminate indicator.
     */
    fun downloadCurrent() {
        val track = container.player.state.value.current ?: return
        if (download is DownloadState.Downloading) return
        downloadJob?.cancel()
        download = DownloadState.Downloading
        downloadJob = viewModelScope.launch {
            val quality = container.settings.quality
            val resolved = container.music.streamUrl(track.id, quality)
            val url = resolved?.url
            if (url.isNullOrBlank()) {
                download = DownloadState.Failed(
                    when {
                        resolved == null -> "无法获取下载地址"
                        resolved.isTrial -> "会员歌曲仅可试听，无法下载"
                        else -> "该歌曲当前不可下载"
                    },
                )
                return@launch
            }
            val result = container.downloads.save(url, track)
            download = result.fold(
                onSuccess = { DownloadState.Saved },
                onFailure = { DownloadState.Failed(it.message ?: "下载失败") },
            )
        }
    }

    /** Clears the transient saved/failed badge once it has been seen. */
    fun clearDownloadState() {
        if (download !is DownloadState.Downloading) download = DownloadState.Idle
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
    data object Saved : DownloadState
    data class Failed(val message: String) : DownloadState
}
