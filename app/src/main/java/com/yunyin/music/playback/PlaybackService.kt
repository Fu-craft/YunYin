package com.yunyin.music.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yunyin.music.AppContainer
import kotlinx.coroutines.runBlocking

/**
 * Foreground media session hosting the [ExoPlayer] instance.
 *
 * Keeping playback in a service means audio survives the UI being backgrounded and
 * gives lock-screen / notification transport controls for free.
 *
 * Stream URLs are obtained per load through a [ResolvingDataSource]: NetEase hands out
 * short-lived signed URLs, so they are fetched at the moment a track is loaded rather
 * than when the queue is built.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * The concrete player, kept because the seamless toggle is applied with `ExoPlayer`'s own
     * `setSkipSilenceEnabled`. `MediaSession.player` is typed [Player], which does not expose it — only
     * `ExoPlayer` and `SimpleExoPlayer` do — so the session cannot be the handle used for that call.
     */
    private var exoPlayer: ExoPlayer? = null

    /**
     * Reacts to the seamless-transition preference while the service is alive.
     *
     * The toggle lives in Settings and is written to [com.yunyin.music.data.SettingsStore], but the
     * player that has to honour it is owned here — and `Player`/`MediaController` expose no skip-silence
     * control, so there is no way to reach it from the UI directly. Listening to the preference is what
     * makes the switch take effect on the current track instead of only after a restart.
     */
    private var seamlessPreferenceListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate() {
        super.onCreate()
        val container = AppContainer.from(this)

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val resolvingFactory = ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val trackId = TrackMediaItem.trackIdOf(dataSpec.uri)
            if (trackId == null) {
                dataSpec
            } else {
                // Runs on ExoPlayer's loading thread; a blocking resolve keeps the
                // data-source contract simple. On failure the original spec is kept so the
                // error surfaces as a normal playback error.
                //
                // A member-only track resolves to a short *trial* fragment; that is an
                // entitlement limit, so it is played as-is (see PlayerController, which
                // reports the trial window to the UI).
                val resolved = runBlocking {
                    container.music.streamUrl(trackId, container.settings.quality)
                }
                val url = resolved?.url
                if (url.isNullOrBlank()) dataSpec else dataSpec.withUri(android.net.Uri.parse(url))
            }
        }

        val player = ExoPlayer.Builder(this, ReactiveRenderersFactory(this, SeamlessAudioProcessor()))
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ALL
                setWakeMode(C.WAKE_MODE_NETWORK)
                // Silence trimming, applied through ExoPlayer rather than by our own processor so that
                // the skipped frames are added back into the reported position — without that the karaoke
                // lyrics would fall behind by however much silence was trimmed.
                setSkipSilenceEnabled(container.settings.seamlessTransition)
            }

        seamlessPreferenceListener = container.settings.observeSeamlessTransition { enabled ->
            exoPlayer?.setSkipSilenceEnabled(enabled)
        }

        exoPlayer = player
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Stop the service (and the player) once the app is swiped away. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        seamlessPreferenceListener?.let { AppContainer.from(this).settings.unobserve(it) }
        seamlessPreferenceListener = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) YunYin/1.0"
    }
}
