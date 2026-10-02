package com.yunyin.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
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
    /**
     * Matches what [PlaybackService] configures on the player (`REPEAT_MODE_ALL`), so the mirror
     * describes the real default rather than a guess. Note this means "list repeat" is genuinely on
     * at startup and the loop control is correctly shown as active from the first track.
     */
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
    private var trialJob: Job? = null
    private var trialRequestId = 0L

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

        /**
         * Repeat and shuffle changes must republish.
         *
         * These were missing, which matters for any change that does not come from this class's own
         * tap handlers. The concrete case: [refreshTrialAndQueueMode] forces `repeatMode` to OFF for a
         * member-only preview, and with no callback the UI kept showing the user's old mode — the
         * control lied about the player for the whole preview. The same gap meant a change made from
         * the notification or the lock screen never reached the in-app controls.
         */
        override fun onRepeatModeChanged(repeatMode: Int) = publish()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // Clear per-track trial state immediately so the previous song's notice cannot leak across a
            // transition. The resolver below then fills in the new track's state if needed.
            _state.value = _state.value.copy(trialEndMs = null, notice = null)
            publish()
            val trackId = mediaItem?.mediaId?.toLongOrNull()
            trialJob?.cancel()
            val requestId = ++trialRequestId
            trialJob = scope.launch { refreshTrialAndQueueMode(trackId, requestId = requestId) }
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
        requestId: Long = ++trialRequestId,
    ) {
        if (trackId == null) {
            if (PlayerControllerRules.clearTrial(requestId, trialRequestId)) {
                _state.value = _state.value.copy(trialEndMs = null, notice = null)
            }
            return
        }
        val info = resolved ?: resolveStream(trackId)
        // The stream lookup is asynchronous. Its result is valid only for the track/request that started
        // it; never let a slow previous response overwrite the current song's state or repeat mode.
        if (!PlayerControllerRules.trialResultApplies(
                requestId = requestId,
                currentRequestId = trialRequestId,
                forTrackId = trackId,
                currentTrackId = controller?.currentMediaItem?.mediaId?.toLongOrNull(),
            )
        ) {
            return
        }
        if (info == null) {
            _state.value = _state.value.copy(trialEndMs = null, notice = null)
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
            if (c.currentMediaItem?.mediaId?.toLongOrNull() == trackId) {
                // Repeat-one on a fragment is an infinite loop; repeat-all would also restart
                // the 45s preview rather than moving on.
                c.repeatMode = if (trial != null) Player.REPEAT_MODE_OFF else savedRepeatMode
            }
        }
    }

    /**
     * The loop mode to restore once a member-only preview is over.
     *
     * Kept in sync from [publish] rather than only from [cycleRepeat], and only while *not* on a
     * preview. That distinction matters: a preview forces repeat off, and an earlier version
     * remembered only what the user had cycled to — defaulting to repeat-all — so playing a trial
     * song and then a normal one would switch the user into list repeat that they had never chosen.
     * Mirroring the live mode while no override is in effect makes the restore faithful to whatever
     * the user (or the notification) last set, and never captures the forced off.
     */
    private var savedRepeatMode: Int = Player.REPEAT_MODE_OFF

    /**
     * Applies a newly-selected audio quality to the track that is playing.
     *
     * Needed because the stream URL is resolved **once, at load time**, from the quality setting (see
     * `PlaybackService`'s resolving data source). Writing the new quality to settings therefore has no
     * audible effect until the next track loads — the user picks 无损 and hears no change, which reads
     * as a broken control.
     *
     * The fix is to make ExoPlayer load the current item again, so the data source runs at the new
     * quality:
     *
     *  - the item is **replaced with a fresh instance** rather than re-seeked. A seek does not force a
     *    re-read — the buffered data for the current URI is reused — so it would leave the old stream
     *    playing. A new `MediaItem` means a new load and a new resolve.
     *  - the position and play state are captured first and restored after, so the song continues from
     *    where it was rather than restarting.
     *
     * Does nothing when paused with no track, and never throws: this is a convenience action on a user
     * gesture, so a failure must leave playback alone rather than stop it.
     */
    fun applyQualityChange() {
        val mediaController = controller ?: return
        val index = mediaController.currentMediaItemIndex
        val item = mediaController.currentMediaItem ?: return
        val track = currentQueue.firstOrNull { it.id == item.mediaId.toLongOrNull() }
            ?: TrackMediaItem.trackOf(item)
        val wasPlaying = mediaController.isPlaying
        val position = mediaController.currentPosition.coerceAtLeast(0L)

        runCatching {
            mediaController.replaceMediaItem(index, TrackMediaItem.mediaItem(track))
            mediaController.seekTo(index, position)
            if (wasPlaying) mediaController.play()
        }.onFailure { error ->
            Log.w(TAG, "Could not reload the current track at the new quality", error)
        }
        publish()
    }

    /**
     * Re-checks the current track's trial window after a change that alters what the service returns.
     *
     * Called alongside [applyQualityChange] because a different quality can change whether the track
     * resolves as a member-only fragment.
     */
    fun refreshCurrentTrial() {
        scope.launch { refreshTrialAndQueueMode(controller?.currentMediaItem?.mediaId?.toLongOrNull()) }
    }

    fun clearNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    // ---------------------------------------------------------------- queue

    /** Replaces the queue and starts at [startIndex], playing unless [autoPlay] says otherwise. */
    fun playQueue(tracks: List<Track>, startIndex: Int, autoPlay: Boolean = true) {
        val mediaController = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map { TrackMediaItem.mediaItem(it) }
        // Record the concrete Track list: MediaItem metadata is lossy (no album id /
        // duration), and the UI renders richer rows than the player item carries.
        currentQueue = tracks
        mediaController.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        mediaController.prepare()
        if (autoPlay) mediaController.play()
        publish()
    }

    fun playSingle(track: Track, queue: List<Track> = listOf(track), autoPlay: Boolean = true) {
        val index = queue.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        playQueue(if (queue.isEmpty()) listOf(track) else queue, index, autoPlay = autoPlay)
    }

    /**
     * Plays [track] from [queue], starting only when [autoPlay] asks, positioned at its index.
     *
     * [queue] is already resolved, so this is the single place a listen-together correction reaches the
     * player. The play state is a parameter because the caller may be reproducing someone else's state: a
     * room `GOTO` sent while the sender was paused must land paused, and `playQueue`'s unconditional
     * `play()` would silently override exactly that.
     */
    fun playWithinQueue(track: Track, queue: List<Track>, autoPlay: Boolean) {
        val ordered = queue.ifEmpty { listOf(track) }
        val index = ordered.indexOfFirst { it.id == track.id }.takeIf { it >= 0 } ?: 0
        playQueue(ordered, index, autoPlay = autoPlay)
    }

    fun next() = controller?.let { it.seekToNextMediaItem(); publish() }
    fun previous() = controller?.let { it.seekToPreviousMediaItem(); publish() }

    fun togglePlayPause() {
        val mediaController = controller ?: return
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
        publish()
    }

    /**
     * Discrete play/pause, for callers that must set a state rather than flip one.
     *
     * Listen-together needs these: the peer's state is "playing" or "paused", and toggling would
     * invert whatever this device happens to be doing — right only half the time, and visibly wrong
     * the other half. Idempotent, so an unchanged state is a no-op.
     */
    fun setPlaying(playing: Boolean) {
        val mediaController = controller ?: return
        if (mediaController.isPlaying == playing) return
        if (playing) mediaController.play() else mediaController.pause()
        publish()
    }

    fun seekTo(ms: Long) {
        // Bounded by the *known* duration, and held short of the very end.
        //
        // The bound fixes "tapping near the end skips the track": asking for a position past the end
        // lands the player at the end, which ends the item and advances. The end guard is the second half
        // of that: `duration` is metadata, and the streamed audio can be a little shorter than it, so a
        // request *at* the duration can still be past the last decodable frame. Landing a short guard
        // before the end leaves audible audio after the seek, so a near-end tap plays rather than ends.
        //
        // A stale or rounded duration upstream would otherwise reach the player unchecked, which is why
        // the bound lives here as well as at the fraction-based call sites.
        val knownDuration = controller?.duration
            ?.takeIf { it != androidx.media3.common.C.TIME_UNSET && it > 0L } ?: 0L
        val target = if (knownDuration > SEEK_END_GUARD_MS) {
            ms.coerceIn(0L, knownDuration - SEEK_END_GUARD_MS)
        } else {
            ms.coerceAtLeast(0L)
        }
        controller?.seekTo(target)
        clock.seekTo(target, SystemClock.elapsedRealtime())
        _state.value = _state.value.copy(positionMs = target)
    }

    fun seekToIndex(index: Int) {
        val mediaController = controller ?: return
        mediaController.seekTo(index.coerceIn(0, (mediaController.mediaItemCount - 1).coerceAtLeast(0)), 0L)
        mediaController.play()
        publish()
    }

    /**
     * Advances the loop mode: list → one → off → list.
     *
     * The mode is read from and written back to [_state] rather than read back from the controller.
     * A `MediaController` does update its cached `PlayerInfo` before returning, so a read-back would
     * work here — but it dispatches the actual change to the service over IPC, and reading our own
     * mirror is both cheaper and independent of that ordering. The authoritative value still arrives
     * via [Player.Listener.onRepeatModeChanged].
     */
    fun cycleRepeat() {
        val mediaController = controller ?: return
        val next = when (_state.value.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
        // Remember the user's choice: a member-only preview temporarily forces repeat off,
        // and this is what it restores afterwards.
        savedRepeatMode = next
        val applied = if (_state.value.isTrialPreview) Player.REPEAT_MODE_OFF else next
        mediaController.repeatMode = applied
        _state.value = _state.value.copy(repeatMode = applied)
    }

    /** Toggles shuffle, mirroring it into [_state] as [cycleRepeat] does. */
    fun toggleShuffle() {
        val mediaController = controller ?: return
        val next = !_state.value.shuffle
        mediaController.shuffleModeEnabled = next
        _state.value = _state.value.copy(shuffle = next)
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
        // Clamp into the track.
        //
        // The player's raw position is *not* guaranteed to be within the duration: near the end it can
        // report a value past it (the audio sink adds skipped silence back into the position it reports,
        // and the last written frame can land a hair beyond the metadata duration). Unclamped, that
        // surfaced as the progress bar filling to the end on its own and the elapsed time reading longer
        // than the track — so the bound belongs here, at the single place the value enters the app,
        // rather than at each thing that displays it.
        val rawPosition = mediaController.currentPosition.coerceAtLeast(0L)
        val position = if (duration > 0L) rawPosition.coerceIn(0L, duration) else rawPosition

        // Track the user's real loop mode whenever no preview override is in effect, so leaving a
        // preview restores what they actually had rather than a hard-coded default.
        if (!_state.value.isTrialPreview) savedRepeatMode = mediaController.repeatMode

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

    private companion object {
        const val TAG = "YunYin/Player"

        /**
         * How far short of the end a seek is held.
         *
         * Long enough to guarantee audible audio after a near-end seek (the streamed file can be shorter
         * than its metadata duration, and a seek onto or past the last frame ends the item), short enough
         * that "jump to the end" still visibly reaches the end of the bar.
         */
        const val SEEK_END_GUARD_MS = 600L
    }
}

/**
 * Pure identity checks for the asynchronous trial/stream lookup.
 *
 * Extracted so the two rules that prevent a stale response from corrupting the current track can be
 * unit-tested directly; they were both missing, and their absence is what let a previous song's preview
 * window and repeat-mode override land on the next song.
 */
internal object PlayerControllerRules {

    /**
     * Whether a resolved stream result may still be applied.
     *
     * False when a newer request has superseded this one, or when the player has already moved to a
     * different track. Either means the answer describes a song that is no longer playing.
     */
    fun trialResultApplies(
        requestId: Long,
        currentRequestId: Long,
        forTrackId: Long,
        currentTrackId: Long?,
    ): Boolean = requestId == currentRequestId && currentTrackId == forTrackId

    /** Whether a null-track transition may clear the trial state (only when it is the current request). */
    fun clearTrial(requestId: Long, currentRequestId: Long): Boolean = requestId == currentRequestId
}
