package com.yunyin.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yunyin.music.core.Track
import com.yunyin.music.data.net.NeteaseClient
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the player UI needs, as one immutable snapshot. */
data class PlaybackUiState(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val current: Track? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val repeatMode: Int = Player.REPEAT_MODE_ALL,
    val shuffle: Boolean = false,
    val error: String? = null,
    /**
     * Non-null when the current track is a member-only **preview**: the number of
     * milliseconds that are actually playable.
     */
    val trialEndMs: Long? = null,
    /** One-shot user-facing message (e.g. "member-only, 45s preview"); cleared once shown. */
    val notice: String? = null,
) {
    val hasTrack: Boolean get() = current != null
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isTrialPreview: Boolean get() = trialEndMs != null
}

/**
 * Process-wide player facade.
 *
 * Owns the connection to [PlaybackService]'s `MediaSession` and mirrors player state
 * into a [StateFlow] so Compose can render it. Position is polled only while playing.
 *
 * @param resolveStream resolves a track's stream info, used to detect member-only previews.
 */
@UnstableApi
class PlayerController(
    context: Context,
    private val resolveStream: suspend (Long) -> NeteaseClient.SongUrl?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var positionJob: Job? = null

    // The player publishes at 4 Hz, far too coarse to drive karaoke timing, so the UI asks
    // this for a per-frame interpolated position. See [PositionInterpolator] for why it must
    // not advance while buffering.
    private val clock = PositionInterpolator()

    /** Interpolated playback position in milliseconds; safe to call every frame. */
    fun positionMsNow(): Long =
        clock.positionAt(SystemClock.elapsedRealtime(), PositionInterpolator.MAX_EXTRAPOLATION_MS)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPositionTicker() else stopPositionTicker()
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = publish()

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // Reset the trial window on every track change, then re-resolve for the new one.
            publish()
            val trackId = mediaItem?.mediaId?.toLongOrNull()
            scope.launch { refreshTrialAndQueueMode(trackId) }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = error.message ?: "播放失败")
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(context, token).buildAsync()
        scope.launch {
            runCatching {
                val mediaController = future.await()
                controller = mediaController
                mediaController.addListener(listener)
                _state.value = _state.value.copy(connected = true)
                publish()
                // A queue may already be loaded if the service outlived the activity.
                refreshTrialAndQueueMode(mediaController.currentMediaItem?.mediaId?.toLongOrNull())            }.onFailure {
                _state.value = _state.value.copy(error = "播放服务连接失败")
            }
        }
    }

    // ---------------------------------------------------------------- trial handling

    /**
     * Resolves the member-only preview window for [trackId] and adapts queue behaviour.
     *
     * A `fee=1` track streams as a short fragment (measured: 45s at the same bitrate for
     * every quality level), and the queue defaults to repeat-all — so a preview would loop
     * forever, which is what made trial songs behave wrongly. On a preview the repeat mode
     * is forced off for that item so playback advances instead.
     */
    private suspend fun refreshTrialAndQueueMode(
        trackId: Long?,
        resolved: NeteaseClient.SongUrl? = null,
    ) {
        if (trackId == null) {
            _state.value = _state.value.copy(trialEndMs = null)
            return
        }
        val info = resolved ?: resolveStream(trackId)
        if (info == null) {
            _state.value = _state.value.copy(trialEndMs = null)
            return
        }
        val trial = info.trialEndMs
        _state.value = _state.value.copy(
            trialEndMs = trial,
            notice = when {
                info.unavailable -> "《${_state.value.current?.name ?: ""}》无版权或已下架，无法播放"
                trial != null -> "会员歌曲：仅可试听 ${trial / 1000} 秒"
                else -> null
            },
        )
        controller?.let { c ->
            // Repeat-one on a fragment is an infinite loop; repeat-all would also restart
            // the 45s preview rather than moving on.
            c.repeatMode = if (trial != null) Player.REPEAT_MODE_OFF else savedRepeatMode
        }
    }

    /** Remembers the user's chosen repeat mode so a trial can restore it afterwards. */
    private var savedRepeatMode: Int = Player.REPEAT_MODE_ALL

    /**
     * Re-checks the current track's trial window (e.g. after a quality or account change,
     * both of which alter what the service returns).
     */
    fun refreshCurrentTrial() {
        scope.launch { refreshTrialAndQueueMode(controller?.currentMediaItem?.mediaId?.toLongOrNull()) }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    // ---------------------------------------------------------------- queue

    /** Replaces the queue and starts at [startIndex]. */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        val mediaController = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map { TrackMediaItem.mediaItem(it) }
        // Record the concrete Track list: MediaItem metadata is lossy (no album id /
        // duration), and the UI renders richer rows than the player item carries.
        currentQueue = tracks
        mediaController.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        mediaController.prepare()
        mediaController.play()
        publish()
    }

    fun playSingle(track: Track, queue: List<Track> = listOf(track)) {
        val index = queue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        playQueue(if (queue.isEmpty()) listOf(track) else queue, index)
    }

    fun next() = controller?.let { it.seekToNextMediaItem(); publish() }
    fun previous() = controller?.let { it.seekToPreviousMediaItem(); publish() }

    fun togglePlayPause() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
        publish()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0L))
        clock.seekTo(ms.coerceAtLeast(0L), SystemClock.elapsedRealtime())
        _state.value = _state.value.copy(positionMs = ms.coerceAtLeast(0L))
    }

    fun seekToIndex(index: Int) {
        val mediaController = controller ?: return
        mediaController.seekTo(index.coerceIn(0, (mediaController.mediaItemCount - 1).coerceAtLeast(0)), 0L)
        mediaController.play()
        publish()
    }

    fun cycleRepeat() {
        val mediaController = controller ?: return
        val next = when (mediaController.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
        // Remember the user's choice: a member-only preview temporarily forces repeat off,
        // and this is what it restores afterwards.
        savedRepeatMode = next
        mediaController.repeatMode = if (_state.value.isTrialPreview) Player.REPEAT_MODE_OFF else next
        publish()
    }

    fun toggleShuffle() {
        val mediaController = controller ?: return
        mediaController.shuffleModeEnabled = !mediaController.shuffleModeEnabled
        publish()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ---------------------------------------------------------------- internals

    private var currentQueue: List<Track> = emptyList()

    private fun publish() {
        val mediaController = controller ?: return
        val index = mediaController.currentMediaItemIndex
        val current = mediaController.currentMediaItem?.let { item ->
            currentQueue.firstOrNull { it.id == item.mediaId.toLongOrNull() }
                ?: TrackMediaItem.trackOf(item)
        }
        val duration = mediaController.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: 0L
        val position = mediaController.currentPosition.coerceAtLeast(0L)

        // Re-anchor the interpolation on every authoritative poll. `advancing` is false while
        // buffering, so the lyric clock holds still instead of running ahead of the audio.
        clock.durationMs = duration
        clock.anchor(
            positionMs = position,
            realtimeMs = SystemClock.elapsedRealtime(),
            advancing = mediaController.isPlaying &&
                mediaController.playbackState != Player.STATE_BUFFERING,
        )

        _state.value = _state.value.copy(
            isPlaying = mediaController.isPlaying,
            isBuffering = mediaController.playbackState == Player.STATE_BUFFERING,
            current = current,
            positionMs = position,
            durationMs = duration.coerceAtLeast(0L),
            queue = currentQueue,
            index = index.coerceAtLeast(0),
            repeatMode = mediaController.repeatMode,
            shuffle = mediaController.shuffleModeEnabled,
        )
    }

    private fun startPositionTicker() {
        stopPositionTicker()
        positionJob = scope.launch {
            while (isActive) {
                publish()
                delay(250)
            }
        }
    }

    private fun stopPositionTicker() {
        positionJob?.cancel()
        positionJob = null
    }

    fun release() {
        stopPositionTicker()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
