// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

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
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yunyin.music.AppContainer
import com.yunyin.music.R
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
    private var crossfadeCoordinator: CrossfadeCoordinator? = null
    private lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.from(this)

        // The standard media notification had Media3's generic white small icon. Use our
        // white-on-transparent note silhouette instead: it is the platform convention (alpha-masked,
        // tinted by the system) and matches the Flyme ticker mark.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(this).apply {
                setSmallIcon(R.drawable.ic_ticker_note_white)
            },
        )

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        // DefaultDataSource wraps HTTP (with the same factory) **and** content:// / file:// URIs. Local music streams
        // from MediaStore content URIs, so the resolving data source must accept them as well as network URLs.
        val resolvingBase = androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)

        val resolvingFactory = ResolvingDataSource.Factory(resolvingBase) { dataSpec ->
            val trackId = TrackMediaItem.trackIdOf(dataSpec.uri)
            if (trackId == null) {
                dataSpec
            } else {
                // Runs on ExoPlayer's loading thread; a blocking resolve keeps the
                // data-source contract simple.
                //
                // A member-only track resolves to a short *trial* fragment; that is an
                // entitlement limit, so it is played as-is (see PlayerController, which
                // reports the trial window to the UI).
                //
                // No URL is a **specific** failure, not an opaque one: returning the synthetic `netease://` spec
                // unchanged made ExoPlayer report "Source error", which the user saw on QQ tracks when the custom
                // source could not produce a link. Throwing with a message that says so lets PlayerController show
                // it, and the diagnostics log holds the source's own response.
                val url = runBlocking {
                    container.music.streamUrl(trackId, container.settings.quality)
                }?.url
                if (url.isNullOrBlank()) {
                    throw java.io.IOException("音源未返回可播放地址（详见设置 → 复制音源错误日志）")
                }
                dataSpec.withUri(android.net.Uri.parse(url))
            }
        }

        val createPlayer: () -> ExoPlayer = {
            ExoPlayer.Builder(
                this,
                // The preference is read live by the processor, so the Settings switch takes effect without
                // reaching across the session — `Player`/`MediaController` expose no audio-processing control.
                ReactiveRenderersFactory(this, SeamlessAudioProcessor { container.settings.seamlessTransition }),
            )
                .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
                .setHandleAudioBecomingNoisy(true)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .setLoadControl(
                    androidx.media3.exoplayer.DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
                        .build(),
                )
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_ALL
                    setWakeMode(C.WAKE_MODE_NETWORK)
                    volume = 1f
                }
        }
        val player = createPlayer()
        mediaSession = MediaSession.Builder(this, player).build()
        // The Flyme ticker rides on the media notification (NeriPlayer's approach); a separate ticker
        // notification is not used. The provider mutates only ticker fields/flags, never the media content.
        crossfadeCoordinator = CrossfadeCoordinator(
            session = mediaSession!!,
            initial = player,
            createPlayer = createPlayer,
            settings = container.settings,
            log = container.lxLog,
        )

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
        runCatching { crossfadeCoordinator?.release() }
        crossfadeCoordinator = null
        mediaSession?.run {
            runCatching { player.release() }
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) YunYin/1.0"
    }
}
