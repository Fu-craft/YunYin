// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 浜戦煶 / YunYin contributors

package com.yunyin.music.ui.components

import android.content.Context
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.delay

/**
 * Owns the one ExoPlayer that backs the animated cover for as long as the player screen is alive.
 *
 * The screen swaps between the cover and the lyrics presentations, and the cover subtree is
 * disposed while the lyrics are up. A player created inside that subtree therefore had to re-prepare
 * the HLS stream on the way back, which is what made the returning cover sit on the static artwork
 * and then pop into video. Hoisting the player out of the swapped subtree keeps the decoder warm;
 * only the surface is re-attached.
 */
class AnimatedCoverPlayerHost(context: Context) {

    private val appContext = context.applicationContext
    private var instance: ExoPlayer? = null

    /** The URL whose first frame has already reached the screen, so a re-attach skips re-preparing. */
    @Volatile
    var renderedUrl: String? = null

    /** The last frame the video view produced, used as a bridge while a new surface attaches. */
    @Volatile
    var lastFrame: android.graphics.Bitmap? = null

    /** The view that produced [lastFrame]; a fresh view must not overwrite it before it draws. */
    @Volatile
    var renderedView: TextureView? = null

    /**
     * Freezes the current frame for the bridge. Called by the player screen when a presentation
     * change starts, because the cover subtree is composed with the *previous* `transitioning` value
     * and therefore never observes the flag itself.
     */
    fun captureCurrentFrame() {
        val view = renderedView ?: return
        runCatching { view.bitmap }.getOrNull()?.let { frame ->
            if (!frame.isRecycled) lastFrame = frame
        }
    }

    val player: ExoPlayer
        get() = instance ?: createAnimatedCoverPlayer(appContext).also { instance = it }

    fun release() {
        instance?.let {
            runCatching { it.release() }
        }
        instance = null
        renderedUrl = null
    }

    /**
     * Pauses the (possibly still prepared) player while the cover is off screen 鈥?the lyrics page
     * keeps this host alive, and a looping 1080p decode behind a full-screen lyrics page is pure
     * battery burn.
     */
    fun setScreenVisible(visible: Boolean) {
        instance?.playWhenReady = visible && pendingPlay
    }

    /** Whether the screen wants the cover animating; combined with lifecycle and current visibility. */
    var pendingPlay: Boolean = false
        private set

    fun setPlaying(playing: Boolean) {
        pendingPlay = playing
        instance?.playWhenReady = playing
    }
}

/**
 * One muted, reused ExoPlayer for the animated cover layer.
 *
 * - Pass [host] to keep the player across presentation changes; without one this composable owns and
 *   releases its own player (the behaviour used by previews and tests).
 * - It never requests audio focus and is always muted; the cover video is visual only.
 * - Pauses while the activity is not RESUMED and resumes when it is (if the stream is healthy).
 * - A first-frame timeout calls [onError] so a hanging HLS stream cannot sit black over the cover;
 *   the caller then keeps the static artwork.
 */
@Composable
fun AnimatedArtworkPlayer(
    url: String,
    modifier: Modifier = Modifier,
    /** Corner radius of the cover, in dp; the clip is applied by the view itself. */
    cornerRadiusDp: Float = 0f,
    /** Shared, long-lived player. Null means this composable owns a player for its own lifetime. */
    host: AnimatedCoverPlayerHost? = null,
    /** Mirrors playback: a paused track keeps its cover frozen, as Apple Music does. */
    playing: Boolean = true,
    /** True while a presentation change is running; the last frame is frozen for the swap. */
    captureFrame: Boolean = false,
    /** Fades only the video surface during a presentation change; the bridge frame stays opaque. */
    transitionFade: Float = 1f,
    onError: (String) -> Unit = {},
    /** Fires when the first decoded frame for [url] reaches the screen. */
    onFirstFrame: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnError by rememberUpdatedState(onError)
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val lifecycleOwner = LocalLifecycleOwner.current
    val cornerRadiusPx = with(LocalDensity.current) { Dp(cornerRadiusDp).toPx() }

    val owned = host == null
    val player = host?.player ?: remember { createAnimatedCoverPlayer(context) }

    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var failed by remember(url) { mutableStateOf(false) }
    var firstFrame by remember(url) { mutableStateOf(host?.renderedUrl == url) }
    // Stays transparent until a decoded frame exists: attaching the surface first paints a black
    // rectangle for a frame or two, which read as a flash between the static cover and the video.
    var revealed by remember(url) { mutableStateOf(host?.renderedUrl == url) }
    // Whether this composition's TextureView has a surface yet. Until it does, the video draws
    // nothing at all — the gap a returning cover used to fill with the static album art.
    var surfaceReady by remember(url) { mutableStateOf(false) }
    // Whether this particular composition has painted a frame yet; the bridge hangs on this, not on
    // the surface being created.
    var frameShown by remember(url) { mutableStateOf(false) }
    // The last frame this view produced, so a re-attach (returning from the lyrics page) shows the
    // frozen video instead of falling back to the flat album cover for a beat.
    val lastFrame = remember(url) { host?.lastFrame }
    var attachedView by remember { mutableStateOf<TextureView?>(null) }

    // Freeze the frame the moment the presentation starts changing, while the TextureView is still
    // attached and rendering. Capturing in `onDispose` alone was unreliable: by then the surface is
    // already being torn down and `getBitmap()` came back null, so the returning cover had no bridge
    // and fell back to the static album art.
    LaunchedEffect(captureFrame, revealed) {
        // Only the view that actually produced a frame may be captured: on the way back the
        // TextureView is brand new and has not drawn, and capturing it stored a black bitmap over
        // the good bridge frame (which flashed the cover).
        if (captureFrame && revealed && attachedView != null && attachedView === host?.renderedView) {
            runCatching { attachedView?.bitmap }.getOrNull()?.let { frame ->
                host?.lastFrame = frame
                Log.i(TAG, "captured bridge frame ${frame.width}x${frame.height}")
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                frameShown = true
                firstFrame = true
                revealed = true
                host?.renderedUrl = url
                host?.renderedView = attachedView
                Log.i(TAG, "first frame rendered url=$url")
                currentOnFirstFrame()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!failed) {
                    failed = true
                    Log.w(TAG, "play error url=$url ${error.errorCodeName}: ${error.message}")
                    currentOnError("play error reason=${error.message ?: error.errorCodeName}")
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            // Freeze the current frame before the surface goes away; it bridges the gap while the
            // next composition re-attaches a new TextureView.
            host?.let { shared ->
                runCatching { attachedView?.bitmap }.getOrNull()?.let { shared.lastFrame = it }
            }
            if (owned) player.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(url) {
        failed = false
        val alreadyRendered = host?.renderedUrl == url
        firstFrame = alreadyRendered
        Log.i(TAG, "prepare url=$url alreadyRendered=$alreadyRendered")
        if (!alreadyRendered) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            delay(10_000)
            if (!firstFrame && !failed) {
                failed = true
                Log.w(TAG, "first-frame timeout url=$url")
                currentOnError("first-frame timeout")
            }
        }
    }

    LaunchedEffect(url, resumed, failed, playing) {
        // A paused track freezes its cover, the way Apple Music does: no point decoding 30 fps for a
        // picture nobody is watching move.
        player.playWhenReady = resumed && !failed && playing
        if (playing) host?.setPlaying(true) else host?.setPlaying(false)
    }

    // Reveal only once a frame exists (state is set by the listener above).
    val revealAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 160),
        label = "animated-cover-reveal",
    )

    if (!failed) {
        androidx.compose.foundation.layout.Box(modifier) {
            // Bridge between compositions: the frozen last frame of this very video stays on screen
            // while the new TextureView attaches, so returning from the lyrics page never shows the
            // static album cover for a beat.
            // The bridge stays up until *this* view has produced a frame. Hiding it on
            // `surfaceReady` was wrong: the surface exists as soon as the TextureView is attached,
            // hundreds of milliseconds before the decoder paints into it, so the static album cover
            // showed through in that window.
            val bridge = lastFrame?.takeIf { host?.renderedUrl == url }
            if (!frameShown && bridge != null) {
                androidx.compose.foundation.Image(
                    bitmap = bridge.asImageBitmap(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            AndroidView(
                factory = { ctx ->
                    RoundedTextureView(ctx, cornerRadiusPx).also { view ->
                        attachedView = view
                        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                Log.i(TAG, "surface available ${w}x$h")
                                surfaceReady = true
                            }

                            override fun onSurfaceTextureSizeChanged(texture: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: android.graphics.SurfaceTexture): Boolean = true
                            override fun onSurfaceTextureUpdated(texture: android.graphics.SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = revealAlpha * transitionFade },
                update = { view ->
                    Log.i(TAG, "attach texture view url=$url")
                    attachedView = view
                    (view as? RoundedTextureView)?.apply {
                        setCornerRadius(cornerRadiusPx)
                        clipToOutline = true
                    }
                    player.setVideoTextureView(view)
                },
            )
        }
    }
}

/**
 * Process-wide HLS cache for the animated cover.
 *
 * [androidx.media3.datasource.cache.SimpleCache] may only be opened once per process, so it is a
 * guarded singleton rather than a field of the player screen. 64 MB holds dozens of square cover
 * streams and is evicted LRU, so it cannot grow without bound.
 */
internal object AnimatedCoverMediaCache {

    const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    @Volatile
    private var cache: androidx.media3.datasource.cache.SimpleCache? = null

    fun get(context: Context): androidx.media3.datasource.cache.SimpleCache =
        cache ?: synchronized(this) {
            cache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.applicationContext.cacheDir, "animated-cover-hls"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
}

private fun createAnimatedCoverPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context)
    // Segments live in a 64 MB LRU on disk: the same album loops without re-downloading and a
    // replayed cover starts on the first frame instead of a fresh playlist fetch.
    .setMediaSourceFactory(
        androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(AnimatedCoverMediaCache.get(context))
                .setUpstreamDataSourceFactory(
                    androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setUserAgent(AnimatedCoverMediaCache.UA),
                )
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        ),
    )
    .setTrackSelector(
        // Belt and braces over the AVC-variant preselection: even if a master playlist slips
        // through, never let the selector climb to a 2160p HEVC rendition the device may be unable
        // to decode 鈥?that is the "prepares forever, never shows a frame" failure. 1080 is the top
        // AVC rung Apple publishes for square artwork, so this does not cost sharpness.
        DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1080, 1080)
                    .setMaxVideoBitrate(6_000_000),
            )
        },
    )
    .build()
    .apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        playWhenReady = false
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false, // never take audio focus
        )
    }

/**
 * A [TextureView] that clips itself to a rounded rectangle.
 *
 * Compose's `clip`/`graphicsLayer` shapes do not reliably clip a TextureView: its content is drawn
 * from a separate SurfaceTexture rather than through the view's own RenderNode, so the cover video
 * came out as a hard square over rounded artwork. `clipToOutline` is the clip the view system
 * actually applies to that surface.
 */
private class RoundedTextureView(context: Context, radiusPx: Float) : TextureView(context) {

    private var radius = radiusPx
    private val outlineProviderImpl = object : android.view.ViewOutlineProvider() {
        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    init {
        outlineProvider = outlineProviderImpl
        clipToOutline = true
    }

    fun setCornerRadius(px: Float) {
        if (px == radius) return
        radius = px
        invalidateOutline()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
    }
}

private const val TAG = "YunYinArtwork"
