// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.core.graphics.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.yunyin.music.core.Track
import kotlin.math.roundToInt
import com.yunyin.music.core.player.effects.AudioReactive
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.AudioQuality
import com.yunyin.music.ui.LYRIC_OFFSET_LIMIT_MS
import com.yunyin.music.ui.PlayerStyle
import com.yunyin.music.ui.background.DynamicBackgroundPalette
import com.yunyin.music.ui.background.CoverBlurBackground
import com.yunyin.music.ui.background.BackgroundStyle
import com.yunyin.music.ui.background.BackgroundStyleRules
import com.yunyin.music.ui.background.HyperBackground
import com.yunyin.music.ui.background.QpClassicBackground
import com.yunyin.music.ui.background.QpMeshBackground
import com.yunyin.music.ui.background.QpPixiBackground
import com.yunyin.music.ui.background.OkLabFlowBackground
import com.yunyin.music.ui.background.NowPlayingBackgroundRules
import com.yunyin.music.ui.background.StaticPaletteBackdrop
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.AnimatedArtworkPlayer
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.components.VinylDisc
import com.yunyin.music.ui.components.formatDuration
import com.yunyin.music.ui.components.pressableGestures
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleColors
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.CoverMotion
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import com.yunyin.music.data.download.DownloadPhase
import com.yunyin.music.data.download.TrackDownloadState
import com.yunyin.music.ui.download.LocalDownloadController
import com.yunyin.music.ui.beat.LocalBeatModeActions
import com.yunyin.music.ui.comments.LocalCommentActions
import com.yunyin.music.ui.playlist.LocalPlaylistActions
import com.yunyin.music.ui.DownloadState
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen player, in two presentations.
 *
 * **Artwork**: the large square cover with the title block and controls laid out beneath it.
 *
 * **Lyrics**: the lyrics fill the *entire* screen — including the area behind the controls —
 * with the header and transport floating over them. Because the chrome is an overlay rather
 * than a sibling in the column, fading it out changes nothing about the lyrics layout: the
 * lyrics keep the same position and simply carry on filling the screen top to bottom.
 */
@Composable
fun PlayerScreen(
    state: PlaybackUiState,
    cover: ImageBitmap?,
    palette: DynamicBackgroundPalette,
    lyrics: SyncedLyrics?,
    lyricsLoading: Boolean,
    /** Shown when the track has no lyric document; distinguishes lyricless from not-found. */
    lyricsEmptyText: String = "暂无歌词",
    loader: ArtworkLoader,
    /**
     * The audio quality to request, and its setter.
     *
     * The tier is passed as the enum rather than as a display string so the chip and the sheet cannot
     * disagree: the chip's label *is* the enum's label, and there is no second mapping to drift. It was
     * previously a `qualityLabel: String` produced in `MainActivity` while the selectable levels were
     * listed separately in Settings — two lists of one thing.
     */
    quality: AudioQuality,
    onQualityChange: (AudioQuality) -> Unit,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onShowQueue: () -> Unit,
    /** Opens the lyric-card share flow; null keeps the row hidden (e.g. no lyrics/current track). */
    onShareLyrics: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onShowSettings: () -> Unit,
    /** Whether the lyrics page may auto-hide the chrome after the idle delay. */
    autoImmersiveLyrics: Boolean = false,
    /**
     * The three now-playing background settings (ported from NeriPlayer; persisted in SettingsStore).
     *
     * - [nowPlayingCoverBlurBackground]: draw the current cover, blurred, as the page background.
     * - [nowPlayingAudioReactive]: let the dynamic shader consume the audio taps.
     * - [nowPlayingDynamicBackground]: run the dynamic shader at all; off leaves a still
     *   palette/cover backdrop and stops the shader pump.
     */
    nowPlayingCoverBlurBackground: Boolean = false,
    nowPlayingAudioReactive: Boolean = true,
    nowPlayingDynamicBackground: Boolean = true,
    /** 云音 / 色阶流动. */
    backgroundStyle: BackgroundStyle = BackgroundStyle.default,
    /** Animated cover is opt-in and only active when an operator endpoint is configured. */
    animatedCoverEnabled: Boolean = false,
    animatedArtwork: com.yunyin.music.data.AnimatedArtworkRepository? = null,
    onAnimatedArtworkLog: (String) -> Unit = {},
    /** Current per-track lyric offset, and its setter, for the manual timing control. */
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    /** State of a long-press-to-save (the cover image), and the actions that drive it. */
    download: DownloadState = DownloadState.Idle,
    onSaveCover: () -> Unit = {},
    onDownloadDismissed: () -> Unit = {},
    /** Whether the current track is liked, and its toggle (the heart). */
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    /** Shares the current track: the caller owns the clipboard and the chooser. */
    onShareTrack: () -> Unit = {},
    /** Opens the artist page for the current track's first artist. */
    onArtistClick: (String) -> Unit = {},
    /** Called with a long-pressed lyric's text; null when the line had nothing to copy. */
    onCopyLyric: (String?) -> Unit = {},
    /**
     * Which centrepiece to draw: the classic rounded cover, or a rotating vinyl record.
     *
     * Only the cover area changes between the two — title, controls and the lyrics page are shared, so a
     * style cannot be a half-implemented second player.
     */
    playerStyle: PlayerStyle = PlayerStyle.Classic,
    /** Tabletop foldable: stack the player instead of using the landscape rail. */
    tabletop: Boolean = false,
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
) {
    val track = state.current

    // Battery-saver state, observed so toggling it takes effect without leaving the screen.
    val powerSaveMode = rememberPowerSaveMode()

    // The actions sheet (ellipsis), the lyric-timing sheet it can open, and the quality picker the
    // chip opens.
    var showActions by remember { mutableStateOf(false) }
    var showLyricOffset by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }

    /**
     * True for the duration of a presentation change.
     *
     * Derived from `showLyrics` with a timer rather than read from the shared-transition scope,
     * because the backdrop is drawn *outside* that scope (it must stay put while the presentations
     * swap). The window covers the cross-fade plus the shared cover's spring settle, since both
     * finish at roughly the same time and overshooting slightly only keeps the backdrop still for a
     * moment longer.
     */
    var transitionActive by remember { mutableStateOf(false) }
    var transitionInitialised by remember { mutableStateOf(false) }
    LaunchedEffect(showLyrics) {
        // Do not count the initial composition as a transition.
        if (!transitionInitialised) {
            transitionInitialised = true
            return@LaunchedEffect
        }
        transitionActive = true
        delay(TRANSITION_SETTLE_MS)
        transitionActive = false
    }

    var dragDistance by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    var dragSettleJob by remember { mutableStateOf<Job?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var immersive by remember { mutableStateOf(false) }

    LaunchedEffect(showLyrics, interaction, state.isPlaying, autoImmersiveLyrics) {
        if (!showLyrics) {
            immersive = false
            return@LaunchedEffect
        }
        if (!autoImmersiveLyrics) {
            // Auto-entry is off: never start the idle timer. An immersion that is already on is left
            // alone — turning the setting off must not yank the chrome away mid-read; the next tap
            // on the lyrics wakes it as usual.
            return@LaunchedEffect
        }
        immersive = false
        delay(IMMERSIVE_IDLE_MS)
        if (state.isPlaying) immersive = true
    }

    // Manual wake: any interaction leaves immersive mode — including when auto-entry is disabled,
    // which only stops the idle timer from re-entering and must never trap the user in a state they
    // were already in.
    LaunchedEffect(interaction) {
        if (interaction > 0) immersive = false
    }

    // Only analyse audio while the player is actually visible, so the tee costs nothing
    // during normal browsing.
    DisposableEffect(Unit) {
        AudioReactive.enabled = true
        onDispose { AudioReactive.enabled = false }
    }

    // The player reports its position four times a second, which is what the elapsed-time label
    // shows. The progress bar itself samples `positionProvider` per frame instead.
    val displayedPosition = state.positionMs

    // The cover recedes while the transport is paused: it settles to 92 % **and** dims by 6 %,
    // both driven from one progress value so they cannot separate.
    //
    // Driven by `playWhenReady` — the transport's *intent* — rather than `isPlaying`, which is the
    // audio *output*: the two disagree for the whole buffering window, so an output-driven recede
    // would dip the cover on every seek and every track load and pop straight back up.
    //
    // The specs are deliberately asymmetric. Pausing is the user's considered action, so it settles
    // with one soft rebound ([Motion.CoverPauseSettle]); resuming is the system answering the user —
    // the music is already back — so the cover catches up crisper ([Motion.CoverResumeSettle]).
    //
    // Both are under-damped **on purpose**: the overshoot is what gives the paused cover its springy,
    // Apple-Music feel. The progress can therefore leave `0..1` for a few frames, which is why every
    // consumer clamps — [sharedCoverPausedScale] and [sharedCoverPausedAlpha] both coerce, so the
    // rebound can only ever dip the cover a little past 92 % and come back, never invert the dim state.
    //
    // Not delegated, and never read during composition: `pausedCoverProgress.value` is read only
    // inside the `graphicsLayer` lambdas (the scale and the dim derived from it), so the settle costs
    // a layer update instead of recomposing the player once per frame.
    // User-tunable cover settle (「封面动效手感」). Read once per composition; it changes only while
    // the tuner is open, so this does not add per-frame work.
    val coverMotion by CoverMotion.params.collectAsState()

    // One ExoPlayer for the whole player screen, so leaving for the lyrics page and coming back does
    // not re-prepare the animated cover (which is what made the returning cover flash the static
    // artwork). The host is released when this screen leaves composition.
    val coverHostContext = androidx.compose.ui.platform.LocalContext.current
    val animatedCoverPlayer = remember {
        if (animatedArtwork == null) null
        else com.yunyin.music.ui.components.AnimatedCoverPlayerHost(coverHostContext)
    }
    DisposableEffect(animatedCoverPlayer) {
        onDispose { animatedCoverPlayer?.release() }
    }

    // Warm the next track's cover while this one plays. Keyed on the track identity rather than the
    // queue instance, because the queue is republished as a fresh list on unrelated state changes.
    LaunchedEffect(state.current?.id, state.index) {
        val next = state.queue.getOrNull(state.index + 1)
        if (next != null && next.id != state.current?.id) {
            animatedArtwork?.prefetch(next.artistLine, next.albumName, next.name)
        }
    }
    val pausedCoverProgress = animateFloatAsState(
        targetValue = if (state.playWhenReady) 0f else 1f,
        // The settle is user-tunable (「封面动效手感」), so both specs come from `CoverMotion` at the
        // current parameters instead of the frozen `Motion` constants.
        animationSpec = if (state.playWhenReady) {
            CoverMotion.resumeSpec(coverMotion)
        } else {
            CoverMotion.pauseSpec(coverMotion)
        },
        label = "paused-artwork-progress",
    )

    // **One** scale, applied to both renderings of the shared cover: the full artwork in the artwork
    // presentation and the 56 dp header thumbnail in the lyrics presentation. The dim in the same
    // graphicsLayer is derived from it by [sharedCoverPausedAlpha], so the two properties — and both
    // ends of the shared element — can never disagree.
    //
    // During a presentation change the two are the two ends of one shared element: [AnimatedContent]
    // composes both presentations for the length of the cross-fade, and the shared element animates
    // the cover's *bounds* from one end to the other. If only the full artwork carried the paused
    // scale, the unscaled thumbnail would fade in over the settled 94 % artwork and the cover would
    // visibly enlarge from 0.92 to 1.0 before the share animation started shrinking it — the
    // "enlarge then shrink" wobble. With one value on both ends the scale is constant through the
    // move, so the shared element's bounds are the only animated quantity; because a constant times a
    // monotone sequence is monotone, the cover can only travel one way. See
    // [sharedCoverPausedScale] and its unit test.
    val effectivePausedScale = FullScreenCoverRules.pausedScale(
        playerStyle,
        coverMotion.pausedScale,
    )
    val sharedCoverScale: () -> Float = {
        // Read in the layer phase: `pausedCoverProgress.value`, not a captured Float.
        sharedCoverPausedScale(1f - pausedCoverProgress.value * (1f - effectivePausedScale))
    }

    // 1 = chrome fully present, 0 = immersed. Everything that belongs to the chrome — the header,
    // the transport, the bottom scrim and the lyric fade zones — is derived from this one value,
    // so the whole state change is a single coordinated move rather than several independent
    // snaps.
    //
    // The progress curve is deliberately **linear**, and the character of the departure is carried by
    // the travel term in `chromeSlide` instead. Coupling the two (the previous version used one
    // decelerating bezier for both) forces a trade-off: the fade comes out front-loaded, so the chrome
    // drops most of its opacity in the first quarter of the time and then lingers as a faint ghost.
    // Measured, that curve spent 0.76 of its opacity in the first quarter; a linear presence spends
    // 0.32.
    val chromeProgress by animateFloatAsState(
        targetValue = if (immersive) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (immersive) CHROME_EXIT_MS else CHROME_ENTER_MS,
            // Linear is its own inverse, so the return retraces the departure exactly.
            easing = LinearEasing,
        ),
        label = "chrome-progress",
    )
    val wakeOnTap = if (immersive) {
        Modifier.pointerInput(Unit) { detectTapGestures { interaction++ } }
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // Drag-to-dismiss feedback. `dragDistance` is read only inside a layer block, so a
            // pointer move invalidates the layer and never recomposes the player.
            .graphicsLayer { translationY = dragDistance },
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val dismissDistancePx = with(density) { 120.dp.toPx() }

        // Called on every pointer move while dragging. Cancels an in-flight settle so a new drag
        // starts exactly under the finger.
        val onDrag: (Float) -> Unit = { distance ->
            dragSettleJob?.cancel()
            dragDistance = distance
        }

        // Called once on release.
        val onDragFinished: (distancePx: Float, elapsedMs: Long) -> Unit = { distance, elapsedMs ->
            val velocity = distance / elapsedMs // px per ms
            if (distance > dismissDistancePx || velocity > 0.11f) {
                // Leave `dragDistance` as it is: the existing slide-out exit continues from here,
                // so the card keeps moving down instead of snapping back to 0 first.
                onCollapse()
            } else {
                dragSettleJob = dragScope.launch {
                    animate(
                        initialValue = distance,
                        targetValue = 0f,
                        // Apple's 0.5s / bounce 0.2 spring: dampingRatio 0.8 is a subtle
                        // overshoot, stiffness 400 settles in about half a second.
                        animationSpec = Motion.SpringSettle,
                    ) { value, _ ->
                        dragDistance = value
                    }
                }
            }
        }

        val screenHeightPx = with(density) { maxHeight.toPx() }
        val screenWidthPx = with(density) { maxWidth.toPx() }
        var panelHeightPx by remember { mutableIntStateOf(0) }

        /**
         * Whether to lay the player out side-by-side rather than stacked.
         *
         * Measured on a 1080x2400 device rotated to landscape: the available height becomes ~384dp,
         * and once the controls take their ~250dp the artwork's `weight(1f)` region collapsed to
         * zero — the cover disappeared entirely. Stacking vertically simply cannot fit, so landscape
         * puts the cover beside the controls instead.
         */
        val isLandscape = if (tabletop) false else maxWidth > maxHeight
        val fullScreenCover = FullScreenCoverRules.usesOwnBackdrop(playerStyle, isLandscape)

        // The immersive page fades the cover's top/bottom edges into its backdrop. The static artwork
        // gets that as a mask on its own pixels; the video cannot be masked the same way, so it fades
        // by painting the backdrop's own field colours over its edges. Computed from the same inputs
        // as FullScreenCoverBackdrop, so the two gradients agree.
        val immersiveEdgeField by produceState<IntArray?>(null, cover, state.current?.id, fullScreenCover) {
            value = if (!fullScreenCover || cover == null) null else withContext(Dispatchers.Default) {
                val dominant = runCatching { CoverFieldCache.average(state.current?.id ?: 0L, cover!!) }
                    .getOrNull()
                    ?: palette.colors.firstOrNull()
                FullScreenCoverRules.fieldColors(dominant ?: FullScreenCoverRules.FALLBACK_COLOR)
            }
        }

        // Lyric typography is decided here, once, and shared by the pre-warmer *and* the lyrics
        // view below.
        //
        // It used to be derived independently inside the view from its own measured height, while
        // the pre-warm always measured with the portrait sizes. Wherever the two disagreed — which
        // is precisely what happens in landscape, where the view scales the type down — the cached
        // syllable layouts described glyphs at one size while the rows were spaced for another, so
        // wrapped rows overlapped and the canvas came up short of the text spilling into the
        // translation. Hoisting it removes the whole failure mode: one source of truth, one style,
        // and the cache is re-measured whenever the scale changes (e.g. on rotation).
        val systemBarInset = with(density) {
            WindowInsets.systemBars.getTop(this).toDp() +
                WindowInsets.systemBars.getBottom(this).toDp()
        }
        // The portrait artwork stack measures its spacing against the same available height. Captured
        // here because this is the scope where the constraints are readable; the stack itself is
        // composed further down, where `maxHeight` is not in scope.
        val artworkAvailableHeight = (maxHeight - systemBarInset).coerceAtLeast(0.dp)
        // The lyrics area is the whole viewport in portrait, and the viewport minus the system
        // bars in landscape, where the row holding it is inset for them. Kept as a dp magnitude for
        // `lyricTypeScale`.
        val lyricsAreaHeight = if (isLandscape) {
            (maxHeight - systemBarInset).coerceAtLeast(0.dp)
        } else {
            maxHeight
        }.value
        // The lyric type scale, per orientation (see `lyricTypeScale`). Portrait is height-driven;
        // landscape measures against its own short reference so the type is *larger* than portrait,
        // which is what fills a wide screen.
        val lyricScale = lyricTypeScale(isLandscape, lyricsAreaHeight)

        /**
         * Syllable layouts for the current song, owned here — above the presentation switch.
         *
         * The lyrics view is disposed while the artwork is showing, so a cache remembered inside it
         * was rebuilt from scratch on every switch back to the lyrics: every line re-measured with
         * `TextMeasurer` during the transition, which is what made switching stutter. Keyed on the
         * track *and* the type scale, so a rotation starts from a clean cache rather than reusing
         * layouts measured at the other orientation's size.
         */
        val lyricLayoutCache = remember(track?.id, lyricScale) {
            mutableStateMapOf<Int, List<com.yunyin.music.lyrics.composable.lyrics.SyllableLayout>>()
        }

        val lyricsStyles = remember(lyricScale) {
            LyricsTextStyles(
                normal = TextStyle(
                    fontFamily = SFPro,
                    fontSize = 36.sp * lyricScale,
                    lineHeight = 47.sp * lyricScale,
                    fontWeight = FontWeight.Bold,
                    textMotion = TextMotion.Animated,
                ),
                accompaniment = TextStyle(
                    fontFamily = SFPro,
                    fontSize = 24.sp * lyricScale,
                    lineHeight = 32.sp * lyricScale,
                    fontWeight = FontWeight.SemiBold,
                    textMotion = TextMotion.Animated,
                ),
                phonetic = TextStyle(
                    fontFamily = SFPro,
                    fontSize = 14.sp * lyricScale,
                    fontWeight = FontWeight.Normal,
                ),
            )
        }

        // Pre-warm that cache as soon as the lyrics exist, without waiting for the user to switch.
        //
        // `KaraokeLineText` measures a line synchronously during composition when its cache entry is
        // missing, so an unwarmed cache puts text measurement on the main thread *during* the
        // presentation transition. Measuring while the artwork is still on screen moves that cost
        // off the switch entirely, which is the remaining source of the stutter.
        val warmMeasurer = rememberTextMeasurer()
        LaunchedEffect(lyrics, lyricsStyles) {
            val doc = lyrics ?: return@LaunchedEffect
            runCatching {
                com.yunyin.music.lyrics.composable.lyrics.measureAllLinesIntoCache(
                    lyrics = doc,
                    textMeasurer = warmMeasurer,
                    normalLineTextStyle = lyricsStyles.normal,
                    accompanimentLineTextStyle = lyricsStyles.accompaniment,
                    phoneticTextStyle = lyricsStyles.phonetic,
                    cache = lyricLayoutCache,
                )
            }
        }

        // Fluid, audio-reactive background. The artwork contributes only its palette.
        //
        // One continuous field for the whole page. An earlier version drew it twice (a blurred copy
        // under a sharp copy masked off inside the control panel) to fake a frosted panel; once the
        // two copies became translucent over the NeriPlayer-style base, the mask's edge turned a
        // soft blur boundary into a visible tonal step above the controls. The page background now
        // runs continuously from top to bottom, and contrast for the controls comes from a soft
        // bottom scrim instead (see the control bar).
        //
        // Recede slightly further while immersed so the lyrics have the most contrast exactly when
        // nothing else competes with them.
        val backgroundDim = BACKGROUND_DIM + (1f - chromeProgress) * IMMERSIVE_EXTRA_DIM

        // Freeze the backdrop while the two presentations cross-fade: it sits behind both of them,
        // so animating it during the transition adds no visible motion but does add a full-screen
        // shader draw every frame. Measured, this window is where "Slow issue draw commands" spiked.
        val transitioning = transitionActive

        // Why a resolved animated cover can still not appear: the video layer is withheld while the
        // cover/lyrics presentations cross-fade (a TextureView cannot take part in the shared-element
        // travel). If that flag ever sticks, the cover looks static with no explanation.
        LaunchedEffect(transitioning, animatedCoverEnabled) {
            if (animatedCoverEnabled && transitioning) {
                onAnimatedArtworkLog("stage=animated-artwork skipped reason=transitioning")
            }
        }

        // ---- approximate mesh warp for the presentation change -------------------------------
        //
        // A vertex-grid warp is not expressible over composables, so the outgoing presentation is
        // frozen once and that frame is displaced per pixel in AGSL while the incoming presentation
        // renders normally underneath. Only the *outgoing* picture is treated; see
        // [PresentationWarpOverlay] for the field and the fallbacks.
        val warpEffect = rememberPresentationWarpEffect()
        val warp = rememberPresentationWarpState()
        val warpAvailable = warpEffect != null
        // The artwork cover's centre in root coordinates: the dolly's focus, measured by the
        // layout rather than assumed to be the screen centre. Written in layout, read only by
        // the capture effect, so it never triggers a recomposition of the player.
        var artworkCoverCenter by remember { mutableStateOf<Offset?>(null) }
        val latestShowLyrics = rememberUpdatedState(showLyrics)
        val togglePresentation: () -> Unit = {
            if (warpAvailable && !transitionActive && !warp.sessionActive && !warp.armed) {
                // Freeze the frame we are leaving, then flip. The capture frame still shows the
                // outgoing presentation in full, so the snapshot starts pixel-identical.
                warp.targetShowLyrics = !showLyrics
                warp.armed = true
                onToggleLyrics()
            } else {
                // Reversal (or a toggle during capture): retire the snapshot with a quick fade and
                // let the plain cross-fade handle the change. Never leave a frozen frame behind.
                if (warp.sessionActive) warp.cancelRequested = true
                onToggleLyrics()
            }
        }

        // A presentation change we did not initiate is a reversal for our purposes: drop the warp.
        LaunchedEffect(showLyrics) {
            if (warp.sessionActive && showLyrics != warp.targetShowLyrics) {
                warp.cancelRequested = true
            }
        }

        // Capture: arm -> one recorded frame -> freeze to a bitmap -> open the warp session.
        LaunchedEffect(warp.armed) {
            if (!warp.armed) return@LaunchedEffect
            // The armed frame has drawn (and recorded) by the time the next frame starts.
            withFrameNanos { }
            // Disarm before the readback: `toImageBitmap` can take a frame or two, and re-recording
            // meanwhile would freeze a mid-transition frame instead of the frame we are leaving.
            warp.armed = false
            val snapshot = runCatching { warp.layer.toImageBitmap() }.getOrNull()
            if (snapshot == null || latestShowLyrics.value != warp.targetShowLyrics) {
                warp.discard()
                return@LaunchedEffect
            }
            val startNs = withFrameNanos { it }
            val centre = artworkCoverCenter ?: Offset(screenWidthPx / 2f, screenHeightPx * 0.42f)
            warp.begin(
                bitmap = snapshot,
                startMs = startNs / 1_000_000L,
                center = Offset(
                    (centre.x / screenWidthPx).coerceIn(0.05f, 0.95f),
                    (centre.y / screenHeightPx).coerceIn(0.05f, 0.95f),
                ),
                targetShowLyrics = warp.targetShowLyrics,
            )
        }

        // Now-playing background stack, ported from NeriPlayer (see NowPlayingBackdrop.kt):
        // a static palette backdrop at the bottom, the blurred cover above it, and the dynamic
        // shader on top at 80 % opacity. The three settings decide which layers are composed at
        // all, so a disabled layer stops drawing *and* stops pumping frames.
        val animatorsOn = remember {
            runCatching { android.animation.ValueAnimator.areAnimatorsEnabled() }
                .getOrDefault(true)
        }
        val dynamicMotionEnabled = NowPlayingBackgroundRules.shouldRunDynamic(
            nowPlayingDynamicBackground, animatorsOn, powerSaveMode,
        )
        val audioReactiveEnabled = NowPlayingBackgroundRules.shouldUseAudioReactive(
            nowPlayingAudioReactive, animatorsOn, powerSaveMode,
        )

        // Everything inside this box is what a warp snapshot contains: the backdrop plus both
        // presentations. The capture modifier is inert (draws normally) unless `warp.armed` is set.
        Box(
            Modifier
                .fillMaxSize()
                .capturePresentationInto(warp.layer, warp.armed),
        ) {
            // Always present, always cheap: a still palette gradient. It is what the page shows
            // when both motion layers are off, so switching the background off never leaves black.
            if (fullScreenCover) {
                FullScreenCoverBackdrop(
                    palette = palette,
                    cover = cover,
                    trackId = state.current?.id ?: 0L,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
            StaticPaletteBackdrop(
                palette = palette,
                dim = backgroundDim,
                modifier = Modifier.fillMaxSize(),
            )
            if (NowPlayingBackgroundRules.shouldShowCoverBlur(nowPlayingCoverBlurBackground)) {
                // The blurred artwork itself. Static: no clock, so with the dynamic layer off this
                // is the only background cost and it only redraws when the screen redraws.
                CoverBlurBackground(
                    cover = cover,
                    dim = backgroundDim,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 云音 keeps its historical rule (only composed when 动态背景 is on). The two new
            // clean-room styles still paint one static frame when 动态背景 is off, as specified.
            if (BackgroundStyleRules.shouldCompose(backgroundStyle, nowPlayingDynamicBackground)) {
                val styleMotion = dynamicMotionEnabled && nowPlayingDynamicBackground
                val styleAudio = audioReactiveEnabled && nowPlayingDynamicBackground
                val backgroundModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = NowPlayingBackgroundRules.DYNAMIC_ALPHA }
                when (backgroundStyle) {
                    BackgroundStyle.Yunyin -> HyperBackground(
                        palette = palette,
                        isDark = true,
                        dim = backgroundDim,
                        paused = transitioning,
                        audioReactive = styleAudio,
                        staticMotion = !styleMotion,
                        modifier = backgroundModifier,
                    )
                    BackgroundStyle.OkLabFlow -> OkLabFlowBackground(
                        palette = palette,
                        trackId = state.current?.id ?: 0L,
                        isDark = true,
                        dim = backgroundDim,
                        paused = transitioning,
                        audioReactive = styleAudio,
                        staticMotion = !styleMotion,
                        modifier = backgroundModifier,
                    )
                    BackgroundStyle.QpPixi -> QpPixiBackground(
                        cover = cover,
                        trackId = state.current?.id ?: 0L,
                        palette = palette,
                        dim = backgroundDim,
                        paused = transitioning,
                        staticMotion = !styleMotion,
                        modifier = backgroundModifier,
                    )
                    BackgroundStyle.QpMesh -> QpMeshBackground(
                        cover = cover,
                        trackId = state.current?.id ?: 0L,
                        palette = palette,
                        dim = backgroundDim,
                        paused = transitioning,
                        staticMotion = !styleMotion,
                        modifier = backgroundModifier,
                    )
                    BackgroundStyle.QpClassic -> QpClassicBackground(
                        cover = cover,
                        trackId = state.current?.id ?: 0L,
                        palette = palette,
                        dim = backgroundDim,
                        paused = transitioning,
                        staticMotion = !styleMotion,
                        modifier = backgroundModifier,
                    )
                }
            }
            }
        }

        // The two presentations cross-fade instead of swapping in a single frame, and the cover is
        // a **shared element**: it animates between the full-size artwork and the header thumbnail,
        // so changing presentation reads as one object moving rather than two screens replacing
        // each other. Both sides declare the same key; [AnimatedContent] tells the framework which
        // is incoming and which is outgoing.
        SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = {
                    // A pure cross-fade for everything that is *not* shared. A slide or scale would
                    // imply a spatial relationship between the presentations that does not exist —
                    // the cover is the only element with one, and it is handled by the shared
                    // element instead.
                    //
                    // `sizeTransform = null` is what makes this seamless: the default SizeTransform
                    // animates the container size and **clips to bounds** while it does, which
                    // exposed the page background as a black flash part-way through. Both
                    // presentations already fill the screen, so there is no size to animate —
                    // disabling it removes the clip entirely.
                    //
                    // A positive `targetContentZIndex` puts the incoming layer on top for the whole
                    // transition. The default (0 for both) leaves the ordering unspecified while
                    // they cross, which reorders draw calls and repaints the backdrop underneath
                    // twice — the source of the stutter.
                    //
                    // `sizeTransform` is set through the `using` infix because its setter is
                    // internal on ContentTransform; `targetContentZIndex` has a public setter.
                    //
                    // The warp path is longer than the plain cross-fade: the deformation needs time
                    // to be read, while the live outgoing presentation still leaves early underneath
                    // the overlay, so the second half never looks like a double exposure. The
                    // fallback keeps the original 560 ms cross-fade.
                    val incomingFadeMs = if (warpAvailable) {
                        PresentationWarpRules.DURATION_MS.toInt()
                    } else {
                        PRESENTATION_FADE_MS
                    }
                    val outgoingFadeMs = if (warpAvailable) {
                        PresentationWarpRules.OUTGOING_FADE_MS.toInt()
                    } else {
                        PRESENTATION_FADE_MS
                    }
                    (
                        (fadeIn(tween(incomingFadeMs, easing = Motion.EaseOut)) togetherWith
                            fadeOut(tween(outgoingFadeMs, easing = Motion.EaseOut))) using null
                        ).apply { targetContentZIndex = 1f }
                },
                label = "presentation",
            ) { lyricsShown ->
                // The hoisted cover player outlives this swap, so stop decoding while the lyrics
                // presentation owns the screen and resume the moment the cover comes back.
                LaunchedEffect(lyricsShown) {
                    animatedCoverPlayer?.setScreenVisible(!lyricsShown)
                }
                // ---------------------------------------------- presentations
                //
                // Portrait and landscape differ in *where the chrome lives*, so the two branches
                // below share one rule: landscape puts the chrome in a left-hand rail and gives the
                // remaining (right-hand) area to the content — the cover, or the lyrics. That is
                // also what the chrome-side scrim follows, so it never has to move between
                // presentations.
                if (isLandscape) {
                    // ---------------------------------------- landscape: full-bleed + rail overlay
                    //
                    // The content fills the entire screen and the chrome is an *overlay* rail on one
                    // side — the same shape as the portrait lyrics presentation, and for the same
                    // reason: because the chrome is not a sibling taking layout space, fading it out
                    // changes nothing about the content's layout. In particular the lyrics are
                    // already full-bleed, so entering immersive mode needs no re-layout at all; only
                    // the leading padding (which clears the rail) ramps away.
                    //
                    // An earlier version used a `Row(content, rail)` and shrank the rail's width to
                    // let the lyrics expand. That made the controls reflow mid-animation (their
                    // `fillMaxWidth` rows squeezed as the box narrowed), so the overlay is both
                    // simpler and steadier.
                    //
                    // Which side the rail takes depends on the presentation: lyrics keep it on the
                    // left (so the text reads from the wide right-hand area, as the reference has
                    // it), the artwork puts it on the right (so the cover leads on the left). The
                    // rail's scrim keys off the same flag, so the two cannot disagree.
                    val railOnLeft = lyricsShown
                    val railWidth = LANDSCAPE_CONTROLS_WIDTH

                    Box(Modifier.fillMaxSize()) {
                        // ---------------- content
                        if (lyricsShown) {
                            BoxWithConstraints(
                                Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                                    // Clear the rail while it is up, and keep a comfortable margin
                                    // once it is gone. Ramping only the rail term means the text
                                    // slides left into the reclaimed space as the chrome leaves, and
                                    // never sits flush against the screen edge. The trailing margin
                                    // is constant so a long line cannot run into the right edge.
                                    .padding(
                                        start = railWidth * chromeProgress + LANDSCAPE_LYRIC_INSET,
                                        end = LANDSCAPE_LYRIC_INSET,
                                    ),
                            ) {
                                // A generous edge fade keeps the reading position clear of both
                                // edges while the chrome is up. It retracts with the chrome, exactly
                                // like portrait's panel-sized bottom zone: once immersed there is
                                // nothing to mask, and the lyrics should use the whole height.
                                // Holding the fade constant (an earlier version) left the lyrics
                                // stranded in the middle with dead bands above and below.
                                val edgeFade = maxHeight * LANDSCAPE_LYRIC_EDGE_FRACTION * chromeProgress
                                LyricsView(
                                    lyrics = lyrics,
                                    loading = lyricsLoading,
                                    emptyText = lyricsEmptyText,
                                    isPlaying = state.isPlaying,
                                    positionProvider = positionProvider,
                                    onSeekToLine = { onSeek(it.toLong()) },
                                    onLineLongPress = onCopyLyric,
                                    modifier = Modifier.fillMaxSize(),
                                    focusFraction = LANDSCAPE_LYRICS_FOCUS_FRACTION,
                                    topFadeZone = edgeFade,
                                    bottomFadeZone = edgeFade,
                                    lowPowerRendering = powerSaveMode,
                                    offsetMs = lyricOffsetMs,
                                    layoutCache = lyricLayoutCache,
                                    lyricStyles = lyricsStyles,
                                )
                            }
                        } else {
                            BoxWithConstraints(
                                Modifier
                                    .fillMaxSize()
                                    .statusBarsPadding()
                                    .navigationBarsPadding()
                                    // The cover leads on the left and the rail overlays the right,
                                    // so the cover's area excludes the rail — and grows into it as
                                    // the chrome leaves.
                                    .padding(end = railWidth * chromeProgress),
                                contentAlignment = Alignment.Center,
                            ) {
                                val available = minOf(maxWidth, maxHeight) * 0.94f

                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    ArtworkWithGestures(
                                        artSize = available,
                                        track = track,
                                        cover = cover,
                                        loader = loader,
                                        download = download,
                                        onToggleLyrics = { interaction++; togglePresentation() },
                                        onSaveCover = { interaction++; onSaveCover() },
                                        onDownloadDismissed = onDownloadDismissed,
                                        setDragDistance = onDrag,
                                        onDragFinished = onDragFinished,
                                        playerStyle = playerStyle,
                                        isPlaying = state.isPlaying,
                                        // The raw toggle goes down; the composable decides for itself
                                        // whether a cross-fade should withhold the video. Collapsing
                                        // both signals into one boolean here made "toggle on but
                                        // nothing happens" indistinguishable from "transition stuck".
                                        animatedCoverEnabled = animatedCoverEnabled,
                                        coverTransitioning = transitioning,
                                        animatedCoverPlayer = animatedCoverPlayer,
                                        animatedArtwork = animatedArtwork,
                                        onAnimatedArtworkLog = onAnimatedArtworkLog,
                                        videoEdgeField = if (fullScreenCover) immersiveEdgeField else null,
                                        artworkScale = sharedCoverScale,
                                        sharedScope = this@SharedTransitionLayout,
                                        visibilityScope = this@AnimatedContent,
                                        onCoverCenter = { artworkCoverCenter = it },
                                    )
                                }
                            }
                        }

                        // Tap-to-wake catches taps anywhere the immersive overlay does not.
                        if (immersive) {
                            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { interaction++ } })
                        }

                        // ---------------- chrome rail (overlay)
                        Box(
                            Modifier
                                .align(if (railOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                                .width(railWidth)
                                .fillMaxHeight()
                                .chromeSlide(
                                    chromeProgress,
                                    if (railOnLeft) ChromeEdge.Start else ChromeEdge.End,
                                )
                                .then(wakeOnTap),
                        ) {
                            // The scrim spans the rail's *full* height so it reaches the screen's
                            // top and bottom edges. Padding it for the system bars (an earlier
                            // version put the padding on the enclosing row) made the scrim stop dead
                            // at the status bar and nav bar, which read as a hard horizontal step
                            // across the panel — the "layering" this fixes.
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(
                                        if (railOnLeft) {
                                            Brush.horizontalGradient(
                                                0f to Color.Black.copy(alpha = CONTROL_BAR_TINT),
                                                0.80f to Color.Black.copy(alpha = CONTROL_BAR_TINT),
                                                1f to Color.Transparent,
                                            )
                                        } else {
                                            Brush.horizontalGradient(
                                                0f to Color.Transparent,
                                                0.20f to Color.Black.copy(alpha = CONTROL_BAR_TINT),
                                                1f to Color.Black.copy(alpha = CONTROL_BAR_TINT),
                                            )
                                        },
                                    ),
                            )
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp)
                                    .statusBarsPadding()
                                    .navigationBarsPadding(),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                if (lyricsShown) {
                                    // Lyrics presentation keeps the compact header: thumbnail,
                                    // identity, actions. Tapping the thumbnail returns to the
                                    // artwork, animating through the shared element.
                                    LyricsHeader(
                                        track = track,
                                        loader = loader,
                                        onMoreClick = {
                                            interaction++
                                            showActions = true
                                        },
                                        liked = liked,
                                        onToggleLike = { interaction++; onToggleLike() },
                                        onCoverClick = {
                                            interaction++
                                            togglePresentation()
                                        },
                                        onArtistClick = onArtistClick,
                                        cover = cover,
                                        artworkScale = sharedCoverScale,
                                        sharedScope = this@SharedTransitionLayout,
                                        visibilityScope = this@AnimatedContent,
                                    )
                                } else {
                                    TitleBlock(
                                        track = track,
                                        liked = liked,
                                        onToggleLike = { interaction++; onToggleLike() },
                                        onMoreClick = {
                                            interaction++
                                            showActions = true
                                        },
                                        onArtistClick = onArtistClick,
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                PlayerControls(
                                    state = state,
                                    displayedPosition = displayedPosition,
                                    positionProvider = positionProvider,
                                    quality = quality,
                                    onQualityClick = {
                                        interaction++
                                        showQuality = true
                                    },
                                    showLyrics = showLyrics,
                                    onSeek = { fraction ->
                                        interaction++
                                        onSeek((state.durationMs * fraction).toLong())
                                    },
                                    onTogglePlay = { interaction++; onTogglePlay() },
                                    onNext = { interaction++; onNext() },
                                    onPrevious = { interaction++; onPrevious() },
                                    onCycleRepeat = {
                                        interaction++
                                        if (state.shuffle) onToggleShuffle() else onCycleRepeat()
                                    },
                                    onToggleShuffle = { interaction++; onToggleShuffle() },
                                    onToggleLyrics = { interaction++; togglePresentation() },
                                    onShowQueue = { interaction++; onShowQueue() },
                                )
                            }
                        }
                    }
                } else if (lyricsShown) {
                    Box(Modifier.fillMaxSize()) {
                        // ------------------------------------ lyrics: full-bleed + overlay (portrait)
                        Box(Modifier.fillMaxSize()) {
                            LyricsView(
                                lyrics = lyrics,
                                loading = lyricsLoading,
                                    emptyText = lyricsEmptyText,
                                isPlaying = state.isPlaying,
                                positionProvider = positionProvider,
                                onSeekToLine = { onSeek(it.toLong()) },
                                onLineLongPress = onCopyLyric,
                                modifier = Modifier.fillMaxSize(),
                                // Constant in both states: the chrome only changes alpha, so the
                                // reading position never moves on entering immersive mode.
                                focusFraction = LYRICS_FOCUS_FRACTION,
                                // Fades exactly where the chrome overlaps the lyrics, and retracts in
                                // step with it so nothing jumps: when immersed both zones reach zero
                                // and the lyrics fill the screen. The bottom zone is the panel's
                                // measured height, so the lyrics are fully cleared at the panel's top
                                // edge rather than bleeding into it.
                                //
                                // The bottom zone must *cover the panel*: clamping it to a fraction of
                                // the viewport (an earlier version, to make landscape fit) left lyric
                                // text visible straight through the panel, which is exactly what made
                                // the controls read as broken. Landscape is a separate branch above
                                // and does not need the clamp.
                                topFadeZone = CHROME_TOP_FADE * chromeProgress,
                                bottomFadeZone = if (panelHeightPx == 0) {
                                    0.dp
                                } else {
                                    with(density) {
                                        ((panelHeightPx + LYRIC_FADE_REACH_PX) * chromeProgress).toDp()
                                    }
                                },
                                lowPowerRendering = powerSaveMode,
                                offsetMs = lyricOffsetMs,
                                layoutCache = lyricLayoutCache,
                                lyricStyles = lyricsStyles,
                            )
                            // While immersed the first tap only wakes the UI (so it cannot also seek).
                            if (immersive) {
                                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { interaction++ } })
                            }
                        }

                        Column(
                            Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        ) {
                            Box(Modifier.chromeSlide(chromeProgress, ChromeEdge.Top).then(wakeOnTap)) {
                                LyricsHeader(
                                    track = track,
                                    loader = loader,
                                    onMoreClick = {
                                        interaction++
                                        showActions = true
                                    },
                                    liked = liked,
                                    onToggleLike = { interaction++; onToggleLike() },
                                    onCoverClick = {
                                        interaction++
                                        togglePresentation()
                                    },
                                    onArtistClick = onArtistClick,
                                    cover = cover,
                                    artworkScale = sharedCoverScale,
                                    sharedScope = this@SharedTransitionLayout,
                                    visibilityScope = this@AnimatedContent,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }

                        // Soft bottom scrim. Longer than the control bar itself, so the page
                        // background fades into the controls instead of meeting them at an edge:
                        // alpha is zero at its top and eases up to the control tint at the screen
                        // bottom, over roughly the bottom third of the page. It sits above the
                        // lyrics (which are already faded out there) and below the controls, and it
                        // is independent of which background layers are enabled, so the same
                        // continuous tone reaches the bottom whether the dynamic shader is on, the
                        // blurred cover is on, or only the static palette remains.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(with(density) { (screenHeightPx * BOTTOM_SCRIM_FRACTION).toDp() })
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.35f to Color.Black.copy(alpha = CONTROL_BAR_TINT * 0.18f),
                                        0.72f to Color.Black.copy(alpha = CONTROL_BAR_TINT * 0.62f),
                                        1f to Color.Black.copy(alpha = CONTROL_BAR_TINT),
                                    ),
                                ),
                        )

                        // Control bar. The panel itself is fully transparent; the page background
                        // (and the scrim above) runs underneath it.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { panelHeightPx = it.height }
                                .chromeSlide(chromeProgress, ChromeEdge.Bottom)
                                .then(wakeOnTap),
                        ) {
                            PlayerControls(
                                state = state,
                                displayedPosition = displayedPosition,
                                positionProvider = positionProvider,
                                quality = quality,
                                onQualityClick = {
                                    interaction++
                                    showQuality = true
                                },
                                showLyrics = showLyrics,
                                onSeek = { fraction ->
                                    interaction++
                                    onSeek((state.durationMs * fraction).toLong())
                                },
                                onTogglePlay = { interaction++; onTogglePlay() },
                                onNext = { interaction++; onNext() },
                                onPrevious = { interaction++; onPrevious() },
                                onCycleRepeat = {
                                    interaction++
                                    if (state.shuffle) onToggleShuffle() else onCycleRepeat()
                                },
                                onToggleShuffle = { interaction++; onToggleShuffle() },
                                onToggleLyrics = { interaction++; togglePresentation() },
                                onShowQueue = { interaction++; onShowQueue() },
                            )
                        }
                    }
                } else {
                    // ------------------------------------- artwork: stacked (portrait)
                    Column(
                        Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                    ) {
                        // Vertical rhythm: one measured rule for the whole portrait artwork stack
                        // (see [PlayerArtworkLayoutRules]). The old fixed 56dp spacer plus a
                        // `weight(1f)` artwork region whose size was width-capped is what put a dead
                        // band above the cover and a second one between the cover and the title.
                        // Both spacing values and the cover size come from the measured rule; the
                        // available height was captured at the top of the player for this stack.
                        Spacer(
                            Modifier.height(
                                if (fullScreenCover) 0.dp
                                else PlayerArtworkLayoutRules.topInset(artworkAvailableHeight),
                            ),
                        )

                        BoxWithConstraints(
                            Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            // `fill = false`: the region is measured against the height left after
                            // the title row and the transport block, and the cover is the smaller of
                            // the width fraction and that height — a short screen shrinks the cover
                            // and can never clip the controls. On a tall screen the leftover height
                            // sits below the controls, never between the cover and its title.
                            val artSize = if (fullScreenCover) maxWidth
                            else PlayerArtworkLayoutRules.artworkSize(maxWidth, maxHeight)
                            ArtworkWithGestures(
                                artSize = artSize,
                                track = track,
                                cover = cover,
                                loader = loader,
                                download = download,
                                onToggleLyrics = { interaction++; togglePresentation() },
                                onSaveCover = { interaction++; onSaveCover() },
                                onDownloadDismissed = onDownloadDismissed,
                                setDragDistance = onDrag,
                                onDragFinished = onDragFinished,
                                playerStyle = playerStyle,
                                isPlaying = state.isPlaying,
                                flat = fullScreenCover,
                                soften = fullScreenCover && !transitioning,
                                corner = if (fullScreenCover) 0.dp else ARTWORK_CORNER,
                                // The portrait cover is the other half of the same feature: without
                                // these four the phone layout never built the video layer at all,
                                // which is why the tablet/landscape branch looked wired while the
                                // phone silently stayed static.
                                animatedCoverEnabled = animatedCoverEnabled,
                                coverTransitioning = transitioning,
                                animatedCoverPlayer = animatedCoverPlayer,
                                animatedArtwork = animatedArtwork,
                                onAnimatedArtworkLog = onAnimatedArtworkLog,
                                videoEdgeField = if (fullScreenCover) immersiveEdgeField else null,
                                artworkScale = sharedCoverScale,
                                sharedScope = this@SharedTransitionLayout,
                                visibilityScope = this@AnimatedContent,
                                onCoverCenter = { artworkCoverCenter = it },
                            )

                        }

                        Spacer(
                            Modifier.height(
                                PlayerArtworkLayoutRules.coverTitleGap(artworkAvailableHeight),
                            ),
                        )

                        TitleBlock(
                            track = track,
                            liked = liked,
                            onToggleLike = { interaction++; onToggleLike() },
                            onMoreClick = {
                                interaction++
                                showActions = true
                            },
                            onArtistClick = onArtistClick,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(18.dp))

                        PlayerControls(
                            state = state,
                            displayedPosition = displayedPosition,
                            positionProvider = positionProvider,
                            quality = quality,
                            onQualityClick = {
                                interaction++
                                showQuality = true
                            },
                            showLyrics = showLyrics,
                            onSeek = { fraction ->
                                interaction++
                                onSeek((state.durationMs * fraction).toLong())
                            },
                            onTogglePlay = { interaction++; onTogglePlay() },
                            onNext = { interaction++; onNext() },
                            onPrevious = { interaction++; onPrevious() },
                            onCycleRepeat = {
                                interaction++
                                if (state.shuffle) onToggleShuffle() else onCycleRepeat()
                            },
                            onToggleShuffle = { interaction++; onToggleShuffle() },
                            onToggleLyrics = { interaction++; togglePresentation() },
                            onShowQueue = { interaction++; onShowQueue() },
                        )
                    }
                }
            }
        }

        // The frozen outgoing frame, warped and faded away over the live (already incoming) player.
        // Absent on every fallback path, in which case the cross-fade above is what runs.
        warpEffect?.let { effect ->
            PresentationWarpOverlay(
                state = warp,
                effect = effect,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Both sheets are rendered last so they layer over whichever presentation is showing.
        if (showActions) {
            TrackActionsSheet(
                track = track,
                onPlayNext = onPlayNext,
                onDismiss = { showActions = false },
                onShare = onShareTrack,
                onShareLyrics = onShareLyrics,
                onShowQueue = {
                    interaction++
                    onShowQueue()
                },
            )
        }

        if (showLyricOffset) {
            LyricOffsetSheet(
                offsetMs = lyricOffsetMs,
                hasLyrics = lyrics != null,
                onChange = onLyricOffsetChange,
                onDismiss = { showLyricOffset = false },
            )
        }

        if (showQuality) {
            QualitySheet(
                current = quality,
                onSelect = { tier ->
                    onQualityChange(tier)
                    showQuality = false
                },
                onDismiss = { showQuality = false },
            )
        }
    }
}

/**
 * Full-screen-cover backdrop: one static vertical field from the cover's dominant palette colour.
 * Top is lightened 8 %, the middle is the dominant colour, bottom is darkened 12 %. No shader, no
 * frame pump and no bitmap work; the player style owns this surface.
 */
@Composable
private fun FullScreenCoverBackdrop(
    palette: DynamicBackgroundPalette,
    cover: ImageBitmap?,
    trackId: Long,
    modifier: Modifier = Modifier,
) {
    val average by produceState<Int?>(initialValue = null, cover, trackId) {
        value = if (cover == null) null else withContext(Dispatchers.Default) {
            runCatching { CoverFieldCache.average(trackId, cover) }.getOrNull()
        }
    }
    val dominant = average
        ?: palette.colors.firstOrNull()
        ?: FullScreenCoverRules.FALLBACK_COLOR
    val field = FullScreenCoverRules.fieldColors(dominant)
    Box(
        modifier.background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color(field[0]),
                    FullScreenCoverRules.GRADIENT_MIDDLE to Color(field[1]),
                    1f to Color(field[2]),
                ),
            ),
        ),
    )
}

/**
 * Fades the cover's own top and bottom edges into the page field, inline on the artwork itself.
 *
 * This is the third and correct approach. Blurring a copy inside the artwork rect was invisible (the
 * outermost pixel stays sharp); drawing an enlarged blurred copy behind it read as "the whole cover
 * is blurry". Masking the artwork's own pixels fades the rectangle out over [EDGE_FADE_DP] at each
 * end, which is what NetEase shows: sharp artwork in the middle, no visible border, background
 * colour taking over at the top and bottom.
 */
private fun Modifier.coverEdgeFade(fadePx: Float): Modifier = this    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height.coerceAtLeast(1f)
        val fade = (fadePx / h).coerceIn(0f, 0.45f)
        // **Smoothstep**, not linear. A linear ramp leaves a visible crease where its slope changes
        // (the end of the band reads as a line on covers with hard tonal edges), which is exactly the
        // "still see the border" the user reported. Sampling the smoothstep curve at six stops keeps
        // the derivative continuous at both ends of the band.
        val stops = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
        val alphas = floatArrayOf(0f, 0.104f, 0.352f, 0.648f, 0.896f, 1f)
        val colors = arrayOfNulls<Color>(12)
        val positions = FloatArray(12)
        // Top band: edge → inside.
        for (i in stops.indices) {
            positions[i] = fade * stops[i]
            colors[i] = Color.White.copy(alpha = alphas[i])
        }
        // Bottom band: mirrored, inside → edge.
        for (i in stops.indices) {
            positions[6 + i] = 1f - fade * stops[stops.size - 1 - i]
            colors[6 + i] = Color.White.copy(alpha = alphas[stops.size - 1 - i])
        }
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = Array(12) { positions[it] to (colors[it] ?: Color.White) },
                startY = 0f,
                endY = h,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** How much of the cover's top and bottom dissolves into the page field. */
private const val EDGE_FADE_DP = 190f

/**
 * The same edge dissolve for the animated cover.
 *
 * The static artwork can mask its own pixels (`coverEdgeFade`), but a TextureView draws from its own
 * surface, so that mask does not reliably reach it. Painting the immersive backdrop's own field
 * colours over the video's top and bottom bands gives the identical result from the other direction.
 */
private fun Modifier.videoEdgeFade(fadePx: Float, topColor: Color, bottomColor: Color): Modifier =
    drawWithContent {
        drawContent()
        val height = size.height.coerceAtLeast(1f)
        val fade = (fadePx / height).coerceIn(0f, 0.45f) * height
        if (fade > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to topColor,
                    1f to topColor.copy(alpha = 0f),
                    startY = 0f,
                    endY = fade,
                ),
                size = Size(size.width, fade),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    0f to bottomColor.copy(alpha = 0f),
                    1f to bottomColor,
                    startY = height - fade,
                    endY = height,
                ),
                topLeft = Offset(0f, height - fade),
                size = Size(size.width, fade),
            )
        }
    }


/**
 * The album cover with its gestures, shared by the portrait and landscape layouts.
 *
 * Kept in one place so the shared-element declaration exists exactly once: both arrangements must
 * register the same `COVER_SHARED_KEY`, and duplicating it would risk the two drifting apart.
 *
 * The drag offset lives in the parent; the gesture reports distance and elapsed time on release —
 * the offset, and the dismissal threshold that reads it, belong to the screen that moves.
 *
 * Gestures on the cover, in the order the user meets them:
 *  - **tap** switches to the lyrics presentation;
 *  - **long press** saves the cover art ([onSaveCover]), with the state surfaced over it;
 *  - **drag down** dismisses the player.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ArtworkWithGestures(
    artSize: androidx.compose.ui.unit.Dp,
    track: Track?,
    cover: ImageBitmap?,
    loader: ArtworkLoader,
    download: DownloadState,
    onToggleLyrics: () -> Unit,
    onSaveCover: () -> Unit,
    onDownloadDismissed: () -> Unit,
    setDragDistance: (Float) -> Unit,
    onDragFinished: (distancePx: Float, elapsedMs: Long) -> Unit,
    /** Classic rounded cover, or the rotating record. Only this centrepiece differs. */
    playerStyle: PlayerStyle,
    /** Drives the record's rotation; ignored by the classic cover. */
    isPlaying: Boolean,
    /** Full-screen style: no shadow and a caller-supplied (normally zero) corner. */
    flat: Boolean = false,
    /** Full-screen style: pre-blurred top 56 dp / bottom 72 dp bands over the sharp cover. */
    soften: Boolean = false,
    corner: androidx.compose.ui.unit.Dp = ARTWORK_CORNER,
    animatedCoverEnabled: Boolean = false,
    /** True while the cover and lyrics presentations cross-fade; the video is withheld meanwhile. */
    coverTransitioning: Boolean = false,
    /** Screen-lifetime player, so a presentation change only re-attaches the surface. */
    animatedCoverPlayer: com.yunyin.music.ui.components.AnimatedCoverPlayerHost? = null,
    animatedArtwork: com.yunyin.music.data.AnimatedArtworkRepository? = null,
    onAnimatedArtworkLog: (String) -> Unit = {},
    /**
     * Immersive page only: the backdrop's field colours (`top`, `mid`, `bottom`). The video fades its
     * own edges into them, since a TextureView cannot be masked the way the static artwork is.
     */
    videoEdgeField: IntArray? = null,
    /**
     * The paused settle, as a **lambda**: read inside a `graphicsLayer`, so the spring animates in
     * the layer phase instead of recomposing the player once per frame. The layer also derives its
     * opacity dim from this same value ([sharedCoverPausedAlpha]) — there is no second animation.
     */
    artworkScale: () -> Float,
    // Receiver scopes are passed in rather than captured: this is a separate composable, so the
    // caller's `this@SharedTransitionLayout` / `this@AnimatedContent` are not in scope here.
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    /** Reports the cover area's centre in root coordinates; the warp dollies around it. */
    onCoverCenter: ((Offset) -> Unit)? = null,
) {
    var animatedUrl by remember(track?.id, playerStyle, animatedCoverEnabled, animatedArtwork) {
        mutableStateOf<String?>(null)
    }
    // A still frame pulled from the motion artwork: shown while the HLS stream is still opening, so
    // the square is already the album's moving-art frame instead of the flat cover.
    var animatedPoster by remember(track?.id) { mutableStateOf<String?>(null) }
    var animatedVideoReady by remember(track?.id) { mutableStateOf(false) }
    // Records *why* a resolved cover still produces no video layer: the only inputs that can
    // suppress it are the toggle, the cross-fade and the repository.
    LaunchedEffect(animatedUrl, animatedCoverEnabled, playerStyle, animatedArtwork, coverTransitioning) {
        onAnimatedArtworkLog(
            "stage=animated-artwork gate url=${animatedUrl != null} enabled=$animatedCoverEnabled " +
                "style=${playerStyle.level} repo=${animatedArtwork != null} transition=$coverTransitioning",
        )
    }
    // The vinyl record has no square to put the video on; say so instead of leaving the last lookup
    // status on screen.
    if (animatedCoverEnabled && playerStyle == PlayerStyle.Vinyl && animatedArtwork != null) {
        LaunchedEffect(playerStyle) {
            onAnimatedArtworkLog("stage=animated-artwork skipped reason=style-${playerStyle.level}")
        }
    }
    LaunchedEffect(track?.id, playerStyle, animatedCoverEnabled, animatedArtwork) {
        animatedUrl = null
        // Every style that draws a square cover can play the motion artwork: classic, and the
        // full-bleed immersive page (which is the same square at screen width). The vinyl style has
        // no square at all — a spinning disc with a video inside would be a different design.
        if (playerStyle == PlayerStyle.Vinyl || !animatedCoverEnabled || animatedArtwork == null || track == null) {
            return@LaunchedEffect
        }
        // The endpoint matches on the catalogue artist and album, so a multi-artist track
        // ("A / B", the form this app renders) misses even when "A" is in the catalogue. Fall back to
        // the primary artist before giving up; the repository caches each attempt separately.
        val resolved = animatedArtwork.resolve(track.artistLine, track.albumName, track.name)
            ?: track.artists.firstOrNull()
                ?.takeIf { it.isNotBlank() && it != track.artistLine }
                ?.let { primary -> animatedArtwork.resolve(primary, track.albumName, track.name) }
        android.util.Log.i(
            "YunYinArtwork",
            "resolve artist=${track.artistLine} album=${track.albumName} -> ${resolved?.url ?: "miss"}",
        )
        if (resolved != null && resolved.url.isNotBlank()) {
            animatedUrl = resolved.url
            animatedPoster = resolved.posterUrl
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { onCoverCenter?.invoke(it.boundsInRoot().center) }
            // Tap / long press / drag decided by ONE detector.
            //
            // These were previously `detectVerticalDragGestures` plus `combinedClickable` on the same
            // box, and they compete: a long press needs the pointer within touch slop for the whole
            // system timeout, while the drag detector watches the same movement, so a hold that
            // drifted at all was handed to the drag detector and the long press never fired. That is
            // what made the download both unreliable and feel like it had to be held for ages.
            // `pressableGestures` owns the pointer from down to up, so exactly one outcome wins.
            .pressableGestures(
                onTap = onToggleLyrics,
                onLongPress = onSaveCover,
                setDragDistance = setDragDistance,
                onDragEnd = { distance, elapsedMs -> onDragFinished(distance, elapsedMs) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (playerStyle == PlayerStyle.Vinyl) {
            // The record. Gestures above are unchanged, so tap-to-lyrics, long-press-to-save and
            // drag-down-to-collapse all still work on it.
            //
            // It deliberately does NOT take part in the cover's shared-element transition: the record's
            // label is a fraction of the disc, so morphing it into the 56dp header thumbnail would
            // animate one shape into a much smaller piece of itself. Switching to the lyrics page still
            // cross-fades (the AnimatedContent wrapper), the cover simply does not fly into the header.
            VinylDisc(
                url = track?.coverUrl,
                loader = loader,
                size = artSize,
                isPlaying = isPlaying,
                modifier = Modifier
                    .size(artSize)
                    .graphicsLayer {
                        val scale = artworkScale()
                        scaleX = scale
                        scaleY = scale
                        // The paused dim is derived from the very same scale, so the record recedes
                        // with the cover instead of only shrinking.
                        alpha = sharedCoverPausedAlpha(scale)
                    }
                    .shadow(14.dp, CircleShape),
            )
        } else {

            Artwork(
                url = track?.coverUrl,
                loader = loader,
                corner = corner,
                requestSize = 1000,
                // The single decoded cover, shared with the header thumbnail: both halves of the shared
                // element must be the same pixels, and reusing one bitmap avoids a second fetch/decode
                // mid-transition.
                preloaded = cover,
                modifier = Modifier
                    .size(artSize)
                    // The other half of the shared cover: switching to the lyrics presentation shrinks
                    // this into the header thumbnail.
                    .then(
                        with(sharedScope) {
                            Modifier.sharedElement(
                                rememberSharedContentState(key = COVER_SHARED_KEY),
                                animatedVisibilityScope = visibilityScope,
                                // Both ends of the shared element pass the same curve, so the travel is
                                // tuned here instead of inheriting the framework default.
                                boundsTransform = { _, _ ->
                                    tween(PRESENTATION_FADE_MS, easing = Motion.EaseOut)
                                },
                            )
                        },
                    )
                    // Inside the shared element, so the transition still animates full-size bounds:
                    // the paused settle scales the pixels, not the element the cover travels as.
                    .graphicsLayer {
                        val scale = artworkScale()
                        scaleX = scale
                        scaleY = scale
                        alpha = sharedCoverPausedAlpha(scale)
                    }
                    .then(
                        if (flat) Modifier
                        else Modifier.shadow(14.dp, ContinuousRoundedRectangle(corner)),
                    )
                    .then(
                        if (soften) {
                            Modifier.coverEdgeFade(
                                with(androidx.compose.ui.platform.LocalDensity.current) {
                                    EDGE_FADE_DP.dp.toPx()
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
            )
            // Between "URL resolved" and "first video frame" the square would otherwise fall back to
            // the flat cover. The motion artwork's own preview frame bridges that gap; the video then
            // paints over it.
            val poster = animatedPoster
            if (poster != null && animatedUrl != null && !animatedVideoReady) {
                Artwork(
                    url = poster,
                    loader = loader,
                    corner = corner,
                    requestSize = 600,
                    modifier = Modifier
                        .size(artSize)
                        .clip(ContinuousRoundedRectangle(corner))
                        .graphicsLayer { alpha = sharedCoverPausedAlpha(artworkScale()) },
                )
            }
            val videoUrl = animatedUrl
            if (videoUrl != null && animatedCoverEnabled && animatedArtwork != null && playerStyle != PlayerStyle.Vinyl) {
                // The player is kept alive through a presentation change and merely faded out: tearing
                // it down (as the old `!coverTransitioning` condition did) released the ExoPlayer, so
                // coming back from lyrics had to re-prepare the stream and the static cover was
                // visible until the first frame arrived again.
                val videoAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (coverTransitioning) 0f else 1f,
                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 180),
                    label = "animated-cover-alpha",
                )
                LaunchedEffect(videoUrl) {
                    onAnimatedArtworkLog("stage=animated-artwork compose")
                }
                AnimatedArtworkPlayer(
                    url = videoUrl,
                    cornerRadiusDp = corner.value,
                    host = animatedCoverPlayer,
                    playing = isPlaying,
                    modifier = Modifier
                        .size(artSize)
                        // The static cover is the shared element; its transition layer must never end up
                        // painting over the video, so the video is explicitly raised above it.
                        .zIndex(1f)
                        .clip(ContinuousRoundedRectangle(corner))
                        .graphicsLayer {
                            val scale = artworkScale()
                            scaleX = scale
                            scaleY = scale
                            alpha = sharedCoverPausedAlpha(scale) * videoAlpha
                        }
                        .then(
                            if (soften && videoEdgeField != null) {
                                val field = videoEdgeField!!
                                Modifier.videoEdgeFade(
                                    with(androidx.compose.ui.platform.LocalDensity.current) {
                                        EDGE_FADE_DP.dp.toPx()
                                    },
                                    Color(field[0]),
                                    Color(field[2]),
                                )
                            } else {
                                Modifier
                            },
                        ),
                    onError = { reason ->
                        if (reason.startsWith("play error")) {
                            onAnimatedArtworkLog("stage=animated-artwork play error reason=$reason")
                        }
                        animatedUrl = null
                        onAnimatedArtworkLog("stage=animated-artwork fallback reason=$reason")
                    },
                    onFirstFrame = {
                        animatedVideoReady = true
                        onAnimatedArtworkLog("stage=animated-artwork playing")
                    },
                )
            }
        }

        // Download feedback, drawn over the cover so the gesture's result appears exactly where the
        // gesture was made.
        DownloadOverlay(state = download, size = artSize, onDismissed = onDownloadDismissed)

    }
}

/**
 * The result of a long-press-to-download, rendered over the artwork.
 *
 * Three states, and each is a different *kind* of thing, so each gets a different treatment:
 *
 *  - **downloading** — a scrim and a spinner, both fading in. The scrim is what makes the spinner
 *    readable over arbitrary artwork, and fading rather than appearing avoids a flash.
 *  - **saved** — the scrim lifts, the cover springs back to full size, and a checkmark scales in
 *    *with a slight overshoot* before the whole thing fades away. The overshoot is deliberate and is
 *    the one iOS idiom worth borrowing here: a confirmation should feel like it landed.
 *  - **failed** — same as saved, in the system orange, and it lingers a little longer because it
 *    carries a message the user has to read.
 *
 * Everything animates on `alpha`/`scale` only (never size), so it cannot re-layout the player mid
 * gesture, and the overlay takes no touches: the cover's own gestures must keep working underneath.
 */
@Composable
private fun DownloadOverlay(
    state: DownloadState,
    size: androidx.compose.ui.unit.Dp,
    onDismissed: () -> Unit,
) {
    val visible = state !is DownloadState.Idle
    // Hold the badge a moment after it arrives so the eye can catch it, then retract. Driven by a
    // key on the state so each new result restarts it rather than inheriting the previous run.
    var badgeVisible by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is DownloadState.Saved || state is DownloadState.Failed) {
            badgeVisible = true
            delay(if (state is DownloadState.Failed) BADGE_HOLD_ERROR_MS else BADGE_HOLD_MS)
            badgeVisible = false
            delay(BADGE_FADE_MS_L)
            onDismissed()
        }
    }

    val scrim by animateFloatAsState(
        targetValue = if (state is DownloadState.Downloading) 0.55f else 0f,
        animationSpec = tween(Motion.ScrimDuration, easing = Motion.EaseOut),
        label = "download-scrim",
    )
    // The badge stays up while `badgeVisible`, and the whole overlay unmounts once it has faded.
    // Opacity is a plain fade; the *scale* is a separate spring so the documented "slight overshoot"
    // actually lands — a monotonic tween only ever grows to 1.0. The spring starts at the same 0.86
    // the old `pop` formula used, so the arrival reads the same until the overshoot.
    val badge by animateFloatAsState(
        targetValue = if (badgeVisible) 1f else 0f,
        animationSpec = tween(BADGE_FADE_MS, easing = Motion.EaseOut),
        label = "download-badge",
    )
    val badgeScale by animateFloatAsState(
        targetValue = if (badgeVisible) 1f else 0.86f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "download-badge-scale",
    )

    if (!visible && badge == 0f) return

    Box(
        Modifier
            .size(size)
            .clip(ContinuousRoundedRectangle(ARTWORK_CORNER))
            // No touches: the cover's tap / long press / drag must keep working under the overlay.
            .pointerInput(Unit) { },
        contentAlignment = Alignment.Center,
    ) {
        // Always composed, alpha in the layer phase: the 320 ms scrim fade must not recompose this
        // overlay per frame (the rest of this file follows the same rule).
        Box(Modifier.matchParentSize().graphicsLayer { alpha = scrim }.background(Color.Black))

        if (state is DownloadState.Downloading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(46.dp).graphicsLayer { alpha = scrim / 0.55f },
            )
        }

        // Always composed too; only the layer reads `badge`/`badgeScale`, so the fade and the spring
        // are draw-phase work. `saved`/`caption` read the current state, which is stable for one run.
        val saved = state is DownloadState.Saved
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .graphicsLayer {
                    alpha = badge
                    scaleX = badgeScale
                    scaleY = badgeScale
                },
        ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            if (saved) Color.Black.copy(alpha = 0.62f)
                            else AppleColors.systemOrange.copy(alpha = 0.82f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (saved) SfIcons.Checkmark else SfIcons.ExclamationTriangle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(if (saved) 34.dp else 30.dp),
                    )
                }
                // A failure message, or the saved file's name — the latter so a completed download
                // says *what* it saved and where it went, rather than leaving the user to hunt for it.
                val caption = when (state) {
                    is DownloadState.Failed -> state.message
                    is DownloadState.Saved -> state.fileName
                    else -> null
                }
                if (caption != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = caption,
                        fontFamily = SFPro,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = size * 0.86f)
                            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
    }
}

/** The three text styles the lyrics view renders with, shared by the view and the pre-warmer. */
class LyricsTextStyles(
    val normal: TextStyle,
    val accompaniment: TextStyle,
    val phonetic: TextStyle,
)

/**
 * Reference lyrics-area heights the type scale is measured against, in dp.
 *
 * The scale holds the *number of visible lines* roughly constant by dividing the lyrics area by a
 * reference. Portrait uses a tall reference so a short viewport shrinks the type rather than showing
 * only two enormous lines.
 *
 * Landscape needs the opposite treatment: it is very wide and only ~360dp tall, so the portrait
 * rule rendered small text on a large screen. Its reference is therefore the area itself (slightly
 * under it, so the type comes out a little larger than portrait), which is what a landscape lyrics
 * screen should look like.
 */
internal const val PORTRAIT_LYRICS_REFERENCE_DP = 620f
internal const val LANDSCAPE_LYRICS_REFERENCE_DP = 260f

/** Floor on the scale, so a very short area still produces readable (not tiny) lyrics. */
internal const val MIN_LYRIC_SCALE = 0.55f

/**
 * Ceiling for landscape, above the base size.
 *
 * Landscape has width to spare, so its type is allowed to grow past the base size.
 */
internal const val MAX_LANDSCAPE_LYRIC_SCALE = 1.2f

/**
 * Ceiling for portrait, slightly below the base size.
 *
 * The base 36sp read a little large in portrait against the reference layout, so portrait renders at
 * 90% of it. The height term sits well above this on a phone (800dp / 620dp = 1.29), so this ceiling
 * is what actually decides the portrait size — the same way the landscape reference decides
 * landscape. A genuinely short portrait viewport still scales down from here rather than up.
 */
internal const val MAX_PORTRAIT_LYRIC_SCALE = 0.9f

/**
 * The lyric type scale for an orientation and lyrics-area height.
 *
 * Extracted and unit-tested ([com.yunyin.music.ui.screens.LyricTypeScaleTest]) because the value is
 * invisible until it reaches a device: the previous landscape form divided by a tall reference and
 * then clamped, which pinned the type to the floor and left it small no matter how the reference was
 * tuned. The test pins both orientations' results, so a regression cannot come back unnoticed.
 */
internal fun lyricTypeScale(isLandscape: Boolean, lyricsAreaHeightDp: Float): Float =
    if (isLandscape) {
        (lyricsAreaHeightDp / LANDSCAPE_LYRICS_REFERENCE_DP)
            .coerceIn(MIN_LYRIC_SCALE, MAX_LANDSCAPE_LYRIC_SCALE)
    } else {
        (lyricsAreaHeightDp / PORTRAIT_LYRICS_REFERENCE_DP)
            .coerceIn(MIN_LYRIC_SCALE, MAX_PORTRAIT_LYRIC_SCALE)
    }

/**
 * Observes the system battery-saver state.
 *
 * `isPowerSaveMode` is a snapshot value, not a flow, so it is read once and then refreshed from
 * the broadcast the system sends when the user toggles it — otherwise a change would only take
 * effect after leaving and re-entering the player.
 */
@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    var saveMode by remember { mutableStateOf(readPowerSaveMode(context)) }
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                saveMode = readPowerSaveMode(context)
            }
        }
        val filter = android.content.IntentFilter(
            android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED,
        )
        runCatching {
            context.registerReceiver(receiver, filter)
        }.onFailure {
            // Registration can fail on some OEM builds; the initial value still applies.
        }
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
    return saveMode
}

private fun readPowerSaveMode(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
        ?.isPowerSaveMode == true

/** Progress, times, transport and the bottom row. Shared by both presentations. */
@Composable
private fun PlayerControls(
    state: PlaybackUiState,
    displayedPosition: Long,
    /** Interpolated position, safe to poll per frame; the progress bar samples this. */
    positionProvider: () -> Long,
    quality: AudioQuality,
    onQualityClick: () -> Unit,
    showLyrics: Boolean,
    onSeek: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleLyrics: () -> Unit,
    onShowQueue: () -> Unit,
) {
    Column {
        PlayerProgressBar(
            positionProvider = positionProvider,
            durationMs = state.durationMs,
            onSeek = onSeek,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp),
        ) {
            Text(
                text = formatDuration(displayedPosition),
                fontFamily = SFPro,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.CenterStart),
            )
            QualityChip(
                quality = quality,
                onClick = onQualityClick,
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                text = formatDuration(state.durationMs),
                fontFamily = SFPro,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        Spacer(Modifier.height(30.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(SfIcons.Backward, "上一首", 40.dp, onPrevious)
            PlayPauseButton(
                isPlaying = state.isPlaying,
                isBuffering = state.isBuffering,
                onClick = onTogglePlay,
            )
            TransportButton(SfIcons.Forward, "下一首", 40.dp, onNext)
        }

        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomIcon(SfIcons.QuoteOpening, "歌词", active = showLyrics, onClick = onToggleLyrics)
            // Shuffle and repeat are two separate controls with one job each.
            //
            // They used to share a single button: tap cycled the loop mode, *long press* toggled
            // shuffle, and the icon swapped between a shuffle glyph and a repeat glyph to match. That
            // was wrong three ways. Two unrelated settings behind one control are not discoverable —
            // a long press is not a control, it is a secret. The repeat mode disappeared from view
            // whenever shuffle was on, because the icon had been swapped away. And there was no way to
            // reach repeat-one at all without first turning shuffle off. One tap, one setting, and a
            // glyph that always shows its own state.
            BottomIcon(
                icon = SfIcons.Shuffle,
                description = if (state.shuffle) "随机播放：已开启" else "随机播放：已关闭",
                active = state.shuffle,
                onClick = onToggleShuffle,
            )
            BottomIcon(
                icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) SfIcons.RepeatOne
                else SfIcons.Repeat,
                description = repeatDescription(state.repeatMode),
                active = state.repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onCycleRepeat,
            )
            BottomIcon(SfIcons.ListBullet, "播放队列", active = false, onClick = onShowQueue)
        }
        Spacer(Modifier.height(26.dp))
    }
}

/**
 * Compact header used by the lyrics presentation: thumbnail, track identity, actions.
 *
 * The ellipsis opens the manual lyric-timing sheet. That button previously did nothing, and this
 * is the natural home for a per-track correction — right where the lyrics it affects are on
 * screen.
 */
@Composable
private fun LyricsHeader(
    track: Track?,
    loader: ArtworkLoader,
    onMoreClick: () -> Unit,
    /** Whether this track is in the user's liked songs, and the toggle. */
    liked: Boolean,
    onToggleLike: () -> Unit,
    /** Tapping the thumbnail returns to the artwork presentation. */
    onCoverClick: () -> Unit,
    /** Opens the artist page for the track's first artist. */
    onArtistClick: (String) -> Unit,
    /** The already-decoded cover, so the thumbnail never shows a placeholder mid-transition. */
    cover: ImageBitmap?,
    /**
     * The paused settle, shared with the full artwork. Both ends of the shared element must render at
     * one scale or the cross-fade between them reads as an enlargement — see
     * [sharedCoverPausedScale].
     */
    artworkScale: () -> Float,
    // Receiver scopes required to declare a shared element. Both are provided by the surrounding
    // SharedTransitionLayout / AnimatedContent in PlayerScreen.
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        with(sharedScope) {
            Artwork(
                url = track?.coverUrl,
                loader = loader,
                corner = HEADER_COVER_CORNER,
                requestSize = 200,
                preloaded = cover,
                modifier = Modifier
                    .size(56.dp)
                    // Tapping the thumbnail returns to the artwork presentation. The shared
                    // element then animates it back out to full size, so the gesture is the exact
                    // reverse of the one that brought the user here.
                    .clickable(
                        indication = rememberControlRipple(),
                        interactionSource = null,
                        onClick = onCoverClick,
                    )
                    .sharedElement(
                        rememberSharedContentState(key = COVER_SHARED_KEY),
                        animatedVisibilityScope = visibilityScope,
                        // Must match the full artwork's boundsTransform exactly (same key): the two ends
                        // are one element, so a different curve would fight itself mid-flight.
                        boundsTransform = { _, _ ->
                            tween(PRESENTATION_FADE_MS, easing = Motion.EaseOut)
                        },
                    )
                    // The same paused scale and dim as the full artwork. This is the other end of the
                    // shared element, so it must render identically: when only the artwork shrank, the
                    // unscaled thumbnail faded in over it and the cover appeared to enlarge from
                    // 0.92 back to 1.0 before shrinking into the header. See
                    // [sharedCoverPausedScale] / [sharedCoverPausedAlpha].
                    .graphicsLayer {
                        val scale = artworkScale()
                        scaleX = scale
                        scaleY = scale
                        alpha = sharedCoverPausedAlpha(scale)
                    },
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // The lyrics-header title can overflow for the same reason as the artwork title, and the
            // fix is the same shared marquee: keyed so a new track restarts it, static when it fits.
            key(track?.id) {
                MarqueeTrackTitle(
                    text = track?.name ?: "未在播放",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(1.dp))
            // The artist line is the way into the artist page, and *each* name on it is its own tap
            // target, so a collaboration does not hide its second artist behind the first.
            ArtistLine(
                line = track?.artistLine.orEmpty(),
                artists = track?.artists.orEmpty(),
                style = TextStyle(
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.75f),
                ),
                maxLines = 1,
                onArtistClick = onArtistClick,
            )
        }
        Spacer(Modifier.width(10.dp))
        LikeButton(liked = liked, size = 44.dp, onClick = onToggleLike)
        Spacer(Modifier.width(10.dp))
        CircleGlassIcon(SfIcons.Ellipsis, "更多操作", 44.dp, onClick = onMoreClick)
    }
}

/**
 * Actions for the current track, opened from the ellipsis.
 *
 * A plain iOS action sheet: a list of rows in a rounded card over a scrim. Deliberately not a
 * platform `DropdownMenu` — the player is full-bleed and a floating menu anchored to a small circle
 * reads as an Android artefact, whereas a sheet from the bottom is what the rest of this app already
 * does (the queue).
 *
 * Every row performs a real action; none is decorative. Rows that cannot work in the current state
 * (no lyrics to calibrate) are shown disabled rather than hidden, so the sheet's shape does not
 * change under the user between songs.
 */
@Composable
private fun TrackActionsSheet(
    track: Track?,
    onPlayNext: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onShowQueue: () -> Unit,
    onShareLyrics: (() -> Unit)? = null,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
        sheetModifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(enabled = false, indication = null, interactionSource = null) { }
            .padding(vertical = 6.dp),
    ) { close ->
        if (onPlayNext != null) {
            ActionRow(SfIcons.Forward, "下一首播放", track != null) { close { onPlayNext() } }
        }
        Text(
            text = track?.name ?: "未在播放",
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = AppTheme.palette.label.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        val beatModeActions = LocalBeatModeActions.current
        if (beatModeActions != null) {
            // A guest is known without a round trip: the row says 请先登录 and is inert. The host owns the
            // request, the queue replacement and the messages.
            // Outline heart to match the bar's other outline glyphs; the filled heart stays where it
            // means "liked" (the heart toggle and the comment like count).
            ActionRow(
                SfIcons.Heart,
                if (beatModeActions.signedIn) "心动模式" else "心动模式（请先登录）",
                enabled = track != null && beatModeActions.signedIn,
            ) {
                close { beatModeActions.start(null) }
            }
        }
        val playlistActions = LocalPlaylistActions.current
        if (playlistActions != null && track != null) {
            ActionRow(SfIcons.Plus, "添加到歌单", true) {
                close { playlistActions.onAddToPlaylist(track) }
            }
        }
        // Downloads are orthogonal to the player chrome, so the sheet row reads the same controller the heart's
        // neighbour does; the label carries the state (waiting / percent / done / failure reason).
        val downloads = LocalDownloadController.current
        val downloadTrack = track
        if (downloads != null && downloadTrack != null) {
            val state = downloads.stateOf(downloadTrack)
            ActionRow(downloadIcon(state), downloadLabel(state), true) {
                downloads.toggle(downloadTrack)
                close()
            }
        }
        ActionRow(SfIcons.SquareAndArrowUp, "分享歌曲", track != null) {
            close { onShare() }
        }
        if (onShareLyrics != null) {
            ActionRow(SfIcons.QuoteOpening, "分享歌词", track != null) {
                close { onShareLyrics() }
            }
        }
        val commentActions = LocalCommentActions.current
        if (commentActions != null) {
            ActionRow(SfIcons.BubbleLeft, "评论", track != null) {
                close { commentActions.onOpen() }
            }
        }
        ActionRow(SfIcons.ListBullet, "播放队列", true) {
            close { onShowQueue() }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "取消",
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            color = AppTheme.palette.label,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = rememberControlRipple(bounded = false, color = AppTheme.palette.label.copy(alpha = 0.24f)),
                    interactionSource = null,
                    onClick = { close() },
                )
                .padding(vertical = 14.dp),
        )
    }
}

/** Icon for a download state: arrow while not saved, tick when done, retry arrow after a failure. */
private fun downloadIcon(state: TrackDownloadState?) = when (state?.phase) {
    DownloadPhase.DONE -> SfIcons.Checkmark
    DownloadPhase.FAILED -> SfIcons.ArrowClockwise
    DownloadPhase.PAUSED -> SfIcons.Play
    else -> SfIcons.SquareAndArrowDown
}

/** Action-sheet label, including the honest state (the sheet has no subtitle row). */
private fun downloadLabel(state: TrackDownloadState?): String = when (state?.phase) {
    null, DownloadPhase.IDLE -> "下载到本地"
    DownloadPhase.WAITING -> "等待下载（点按取消）"
    DownloadPhase.RESOLVING -> "正在解析地址…"
    DownloadPhase.DOWNLOADING -> "取消下载（${state.progress?.let { "${(it * 100).toInt()}%" } ?: "进行中"}）"
    DownloadPhase.DONE -> "已下载"
    DownloadPhase.PAUSED -> "继续下载（已暂停）"
    DownloadPhase.FAILED -> "重试下载：${state.message ?: "下载失败"}"
}

/** One row of the actions sheet: icon, label, chevron-free, disabled when unavailable. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.35f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                // Indication uses the theme ink (white on dark, black on light), matching the row's
                // label colour; bounded because the row is a full-width list row.
                indication = rememberControlRipple(bounded = true, color = AppTheme.palette.label.copy(alpha = 0.24f)),
                interactionSource = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.palette.label.copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            fontFamily = SFPro,
            fontSize = 17.sp,
            color = AppTheme.palette.label.copy(alpha = alpha),
        )
    }
}

/**
 * Manual lyric-timing sheet.
 *
 * A plain slider rather than a numeric field: the user is matching lyrics to what they hear, so
 * the useful feedback loop is "nudge, listen, nudge" — and the sign convention is spelled out
 * because "positive is later" is not self-evident.
 */
@Composable
private fun LyricOffsetSheet(
    offsetMs: Long,
    hasLyrics: Boolean,
    onChange: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // The sheet follows the theme now, so its pill buttons use the theme ink for the indication.
    // Bounded here: a pill's ripple should stay inside the pill rather than spill past its edge.
    val resetRipple = rememberControlRipple(bounded = true)
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
        placement = SheetPlacement.Center,
        sheetModifier = Modifier
            .fillMaxWidth(0.86f)
            // Swallow taps so clicking the card does not dismiss it.
            .clickable(
                enabled = false,
                indication = null,
                interactionSource = null,
            ) { }
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.secondaryBackground)
            .padding(20.dp),
    ) { close ->
        Text(
            text = "歌词校准",
            fontFamily = SFPro,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = AppTheme.palette.label,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasLyrics) {
                "歌词比声音早就向右拖，晚就向左拖。只对当前歌曲生效。"
            } else {
                "当前歌曲还没有歌词。"
            },
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.label.copy(alpha = 0.65f),
        )
        Spacer(Modifier.height(16.dp))

        // Direct mapping: the slider's right-hand side is "later", which is a positive
        // offset. The clock applies it as a lag (see LyricsView.offsetClock).
        val sliderValue = offsetMs.toFloat() / LYRIC_OFFSET_LIMIT_MS
        androidx.compose.material3.Slider(
            value = sliderValue,
            onValueChange = { onChange((it * LYRIC_OFFSET_LIMIT_MS).toLong()) },
            valueRange = -1f..1f,
            enabled = hasLyrics,
        )

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "提前",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.label.copy(alpha = 0.55f),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (offsetMs == 0L) "0 ms" else "%+d ms".format(offsetMs),
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = AppTheme.palette.label,
                )
            }
            Text(
                text = "延后",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.label.copy(alpha = 0.55f),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "重置",
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = AppTheme.palette.label.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .clickable(
                        indication = resetRipple,
                        interactionSource = null,
                    ) { onChange(0L) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "完成",
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(AppTheme.palette.label.copy(alpha = 0.16f))
                    .clickable(indication = resetRipple, interactionSource = null, onClick = { close() })
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

/** Title + artist block shown under the artwork. */
/**
 * The player's track title: one line that only moves when it has to.
 *
 * `basicMarquee` measures the text against its incoming constraint and **does nothing when the text
 * fits**, which is the whole requirement: a short title stays the old static line, a long one
 * scrolls. One pass flows straight into the next (no repeat delay — only the 32 dp spacing between
 * the tail and the head), at 40 dp/s, slow enough to read a 26 sp line. A first 1.2 s pause lets the
 * 140 ms identity cross-fade settle before the title moves.
 *
 * While it scrolls, a 20 dp `DstIn` edge mask dissolves the text into the background instead of
 * clipping it; the mask is only composed when the measured line actually overflows, so a short title
 * has no fade and no offscreen layer.
 *
 * With system animations off `basicMarquee` rests at offset 0, and the `Clip` overflow it needs would
 * cut a word mid-letter — so the fallback is the previous presentation, `Ellipsis`. The check is the
 * app's existing `ValueAnimator.areAnimatorsEnabled()` (SplashScreen / VinylDisc / HyperBackground);
 * Compose 1.12's foundation exposes no `LocalMotionDurationScale` composition local (verified against
 * the resolved artifacts: only the `MotionDurationScale` interface and its platform impl exist), and
 * that impl observes the same system flag.
 *
 * The caller keys this by track id, so a new title starts its marquee from the beginning instead of
 * inheriting the previous title's offset.
 */
/** Short, soft dissolve at each end of a scrolling title; only used when the title overflows. */
private val MARQUEE_EDGE_FADE = 20.dp

@Composable
private fun MarqueeTrackTitle(
    text: String,
    fontWeight: FontWeight,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val animatorsOn = remember { ValueAnimator.areAnimatorsEnabled() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = remember(fontWeight, fontSize) {
        TextStyle(fontFamily = SFPro, fontWeight = fontWeight, fontSize = fontSize)
    }
    // Overflow is measured once per (text, style, container width), never per frame — and with the
    // same comparison the marquee modifier itself makes (unbounded intrinsic width vs the container),
    // so the edge mask and the scroll can only switch on together.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val containerPx = with(density) { maxWidth.toPx() }
        val overflows = remember(text, style, containerPx) {
            measurer.measure(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            ).size.width.toFloat() > containerPx
        }
        val scrolling = animatorsOn && overflows
        val edgePx = with(density) { MARQUEE_EDGE_FADE.toPx() }
        Text(
            text = text,
            fontFamily = SFPro,
            fontWeight = fontWeight,
            fontSize = fontSize,
            color = Color.White,
            maxLines = 1,
            // The marquee needs a single unwrapped line so it can measure the text wider than the box.
            softWrap = false,
            // `Clip` is what lets the measured line go past the edge; an ellipsis would truncate the
            // text that is about to scroll. When it fits (or animations are off) we use `Ellipsis`,
            // so a static title is byte-for-byte the old presentation, fade-free.
            overflow = if (scrolling) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (scrolling) {
                        Modifier
                            // Offscreen, so the DstIn edge mask cannot erase anything drawn behind
                            // the title; the layer is only allocated for an actually scrolling title.
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val edge = (edgePx / size.width).coerceIn(0f, 0.5f)
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        edge to Color.White,
                                        1f - edge to Color.White,
                                        1f to Color.Transparent,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                            .basicMarquee(
                                iterations = Int.MAX_VALUE,
                                // No gap between passes: the next pass flows straight out of the
                                // previous one, with only `spacing` between the tail and the head.
                                repeatDelayMillis = 0,
                                initialDelayMillis = 1_200,
                                spacing = MarqueeSpacing(32.dp),
                                velocity = 40.dp,
                            )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
private fun TitleBlock(
    track: Track?,
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    /** Opens the artist page for the track's first artist; absent when there is nowhere to go. */
    onArtistClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // Cross-faded on track id, matching the mini player: skipping a track should read as
            // the identity changing, not as text being overwritten in place. 140 ms in / 80 ms out —
            // same reasoning as the mini player (a skip is frequent; two titles must not both be
            // legible mid-fade).
            CrossfadeContent(
                targetState = track?.id,
                modifier = Modifier.fillMaxWidth(),
                durationMillis = 140,
                label = "title-block",
            ) {
                Column {
                    // Keyed by track id: a new title starts its marquee at offset 0 after the initial
                    // delay, not from the old title's position. `basicMarquee` is a no-op when the
                    // text fits, so short titles stay perfectly still.
                    key(track?.id) {
                        MarqueeTrackTitle(
                            text = track?.name ?: "未在播放",
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // The artist line is the way into the artist page, and *each* name on it is its own
                    // tap target, so a collaboration does not hide its second artist behind the first.
                    ArtistLine(
                        line = track?.artistLine.orEmpty(),
                        artists = track?.artists.orEmpty(),
                        style = TextStyle(
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = Color.White.copy(alpha = 0.78f),
                        ),
                        maxLines = 1,
                        onArtistClick = onArtistClick,
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        LikeButton(liked = liked, size = 36.dp, onClick = onToggleLike)
        Spacer(Modifier.width(10.dp))
        CircleGlassIcon(SfIcons.Ellipsis, "更多操作", 36.dp, onClick = onMoreClick)
    }
}

/**
 * The heart, with a filled/outline state and a spring that makes the toggle felt.
 *
 * The icon cross-fades rather than swapping between two [Icon]s so the change reads as the same
 * control changing state, and the small scale pop gives the tap a physical acknowledgement — a heart
 * is the one control in the player whose whole job is to feel responsive.
 */
@Composable
private fun LikeButton(
    liked: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (liked) 1.12f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.42f,
            stiffness = 900f,
        ),
        label = "like-pop",
    )
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        CircleGlassIcon(SfIcons.Heart, "喜欢", size, onClick = onClick)
        // The filled heart is drawn on top and cross-faded in, so the two glyphs never both show at
        // an intermediate opacity that would look like a half-filled heart.
        androidx.compose.animation.AnimatedVisibility(
            visible = liked,
            enter = fadeIn(tween(Motion.SmallDuration)),
            exit = fadeOut(tween(Motion.SmallDuration)),
        ) {
            CircleGlassIcon(SfIcons.HeartFill, "取消喜欢", size, onClick = onClick)
        }
    }
}

@Composable
private fun CircleGlassIcon(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    // Same white unbounded indication as the transport controls: these circles sit on the same dark
    // bar, so the default (dark, bounded) ripple showed here too.
    val ripple = rememberControlRipple()
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(indication = ripple, interactionSource = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/**
 * The audio-quality chip between the elapsed and remaining times.
 *
 * Tappable, and that is the whole point of it: quality is something people change *while listening*,
 * so the control belongs on the player rather than buried in Settings. It carries the tier's own icon
 * so the level is readable without reading — the bars grow and the lossless tiers switch to a badge.
 *
 * The label and icon cross-fade on change rather than snapping, because the chip is small and a hard
 * swap of both glyph and text in one frame reads as a flicker.
 */
@Composable
private fun QualityChip(
    quality: AudioQuality,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrossfadeContent(
            targetState = quality,
            durationMillis = Motion.SmallDuration,
            label = "quality-chip",
        ) { active ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = active.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = active.label,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * The quality picker, opened from the chip.
 *
 * A bottom sheet like the other secondary choices in this app (queue, lyric calibration, track
 * actions), so the interaction is one the user has already met here rather than a new pattern.
 *
 * Rows carry their own tier icon plus a one-line description, because the level names alone
 * ("较高" vs "极高") do not tell anyone what actually differs. The selected row is marked with the
 * accent colour *and* a checkmark — colour alone would not be enough for a user who cannot separate
 * the accent from white.
 */
@Composable
private fun QualitySheet(
    current: AudioQuality,
    onSelect: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
        sheetModifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.secondaryBackground)
            // Swallows taps on the sheet body so they do not reach the dismiss scrim behind it.
            .clickable(enabled = false, indication = null, interactionSource = null) { }
            .padding(vertical = 6.dp),
    ) { close ->
        Text(
            text = "音质",
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            color = AppTheme.palette.label.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
        AudioQuality.entries.forEach { tier ->
            QualityRow(
                tier = tier,
                selected = tier == current,
                onClick = { close { onSelect(tier) } },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "取消",
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            color = AppTheme.palette.label,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = rememberControlRipple(bounded = false, color = AppTheme.palette.label.copy(alpha = 0.24f)),
                    interactionSource = null,
                    onClick = { close() },
                )
                .padding(vertical = 14.dp),
        )
    }
}

/** One tier in [QualitySheet]. */
@Composable
private fun QualityRow(
    tier: AudioQuality,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true, color = AppTheme.palette.label.copy(alpha = 0.24f)),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tier.icon,
            contentDescription = null,
            // The theme accent; the sheet's surface and labels are theme tokens too, so selection stays
            // legible in both appearances.
            tint = if (selected) AppTheme.palette.accent else AppTheme.palette.label.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = tier.label,
                fontFamily = SFPro,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 17.sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = tier.detail,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.label.copy(alpha = 0.5f),
            )
        }
        if (selected) {
            Icon(
                imageVector = SfIcons.Checkmark,
                contentDescription = "已选择",
                tint = AppTheme.palette.accent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Press feedback for the transport controls: the control scales to [pressedScale] while held and back
 * on release, [Motion.PressDuration] each way with the app's ease-out.
 *
 * The ripple alone is a paint-level acknowledgement on a dark panel; a small scale is what makes the
 * press feel physical on the control users hit most often. 0.94 is subtle enough not to read as the
 * button sinking into the screen, and it composes on top of the ripple rather than replacing it.
 */
@Composable
private fun rememberPressScale(
    interaction: MutableInteractionSource,
    pressedScale: Float = 0.94f,
): Float {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = tween(Motion.PressDuration, easing = Motion.EaseOut),
        label = "transport-press-scale",
    )
    return scale
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    // A white, unbounded ripple: the default indication is a dark bounded one, which showed as a
    // black rectangle under the glyph on the dark control bar.
    val ripple = rememberControlRipple()
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        Modifier
            .size(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = ripple, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit) {
    val ripple = rememberControlRipple()
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Box(
        Modifier
            .size(72.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(interactionSource = interaction, indication = ripple, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Cross-faded so pressing it reads as the control changing state rather than the glyph
        // being swapped; the size difference between the two icons is preserved per state.
        CrossfadeContent(
            targetState = isPlaying,
            durationMillis = Motion.SmallDuration,
            label = "play-pause",
        ) { playing ->
            Icon(
                imageVector = if (playing) SfIcons.Pause else SfIcons.Play,
                contentDescription = if (playing) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(if (playing) 44.dp else 42.dp),
            )
        }
        if (isBuffering) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.55f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

/**
 * One control in the player's bottom row.
 *
 * Tap-only, deliberately: the row used to hide a second setting behind a long press, which is not a
 * control a user can find. See the shuffle/repeat pair at the call site.
 *
 * Sized at 48dp — the minimum comfortable touch target — rather than the 56dp it used to be, so four
 * controls fit this row on the narrowest phones without the spacing collapsing.
 */
@Composable
private fun BottomIcon(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val ripple = rememberControlRipple()
    val accent = AppTheme.palette.accent
    Box(
        Modifier
            .size(48.dp)
            .clickable(indication = ripple, interactionSource = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Active state is carried by *colour*, not by brightness.
        //
        // It used to be white at 0.72 vs 1.0 alpha, which is not a distinction anyone can see: both
        // states are white on a dark panel, so turning either control on looked identical to leaving
        // it off. The accent colour is what makes "on" unmistakable, and it matches how the rest of
        // the app marks an active control (the current lyric line, the liked heart).
        //
        // Plus a filled container behind the glyph, so the state is legible at a glance from the
        // shape of the control and not only from its colour — colour alone would fail for anyone who
        // cannot separate the accent from white.
        val tint by animateColorAsState(
            targetValue = if (active) accent else Color.White.copy(alpha = 0.72f),
            // Named token, not Compose's unnamed 300 ms default: the glyph swaps at 200 ms, so a
            // slower badge would lag behind its own icon; 200 ms is also the app's small-motion budget.
            animationSpec = tween(Motion.SmallDuration, easing = Motion.EaseOut),
            label = "bottom-icon-tint",
        )
        val container by animateColorAsState(
            targetValue = if (active) accent.copy(alpha = 0.16f) else Color.Transparent,
            // Same spec as the tint, so the container and glyph cannot separate.
            animationSpec = tween(Motion.SmallDuration, easing = Motion.EaseOut),
            label = "bottom-icon-container",
        )
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            CrossfadeContent(
                targetState = icon.name,
                durationMillis = Motion.SmallDuration,
                label = "bottom-icon",
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * Spoken state of the loop control.
 *
 * The three modes are indistinguishable from the tint alone — repeat-all and repeat-one are both
 * "on" — so the description is the only place the distinction is stated in words, and the glyph
 * carries it visually.
 */
private fun repeatDescription(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ONE -> "单曲循环"
    Player.REPEAT_MODE_ALL -> "列表循环"
    else -> "不循环"
}

/** Idle time before the lyrics presentation fades its chrome away. */
private const val IMMERSIVE_IDLE_MS = 4000L

/**
 * Duration of the chrome leaving for immersive mode, and of its return.
 *
 * The return is a touch longer than the departure: leaving should feel decisive (the user is reading;
 * the chrome yields), while arriving has to settle without a snap.
 */
private const val CHROME_EXIT_MS = 400
private const val CHROME_ENTER_MS = 440

/**
 * Duration of the cross-fade between the artwork and lyrics presentations, and of the shared
 * cover's travel.
 *
 * Raised 260 → 400 → **560 ms**: the first raise was still judged too quick (the user has now asked
 * twice), and for the app's largest moving object the flight needs time to read as travel rather
 * than a cut. 560 ms is the middle of the requested 520–600 band; it also matches the slower 3.41.4
 * vocabulary (sheets 420/340, chrome 400/440) instead of standing apart. [Motion.EaseOut] starts
 * decisively and settles gently, which is the right shape for a spatial hand-off; `EaseInOut` would
 * hesitate first and read as sluggishness on a gesture the user just made.
 *
 * Both ends of the shared element use this one duration as their `boundsTransform`, and the
 * presentation content cross-fade uses the same number, so the two ends' opacity and the geometry
 * finish together instead of the travel outliving the fade. `AnimatedContent`/the shared element
 * retarget from the current position when `showLyrics` is toggled again mid-flight, so it remains
 * interruptible; the paused-cover scale is still a single shared value, so monotonicity is unchanged.
 */
private const val PRESENTATION_FADE_MS = 560

/**
 * Key identifying the shared album cover across the two presentations.
 *
 * Both sides declare it, which is what lets the framework treat the full-size artwork and the
 * header thumbnail as one object travelling between two positions.
 */
private const val COVER_SHARED_KEY = "album-cover"

/** Corner radius of the full-size artwork, and of the header thumbnail it shrinks into. */
private val ARTWORK_CORNER = 14.dp
private val HEADER_COVER_CORNER = 10.dp

/**
 * How far the cover settles down while the transport is paused.
 *
 * Small on purpose: the cover is the player's anchor, and the change should read as "the music
 * stopped", not as a different layout. Applied as a layer transform on the artwork only, so nothing
 * around it reflows — the drag-to-dismiss translation is a layer on the player body and the cover
 * transition's shared element keeps full-size bounds, so the three transform systems compose
 * without fighting (see [ArtworkWithGestures]). The paired dim is [PAUSED_ARTWORK_DIM].
 */
private const val PAUSED_ARTWORK_SCALE = 0.94f

/**
 * How much the cover dims at the paused rest value, as a fraction of full opacity.
 *
 * 6 % sits in the band where the artwork reads as *receded* — the surface steps back rather than
 * only changing size — without the player looking dimmed or broken. The chrome is untouched, so the
 * cover looks like it moved behind the controls. It is derived from the same single scale
 * ([sharedCoverPausedAlpha]), never animated separately.
 */
private const val PAUSED_ARTWORK_DIM = 0.06f

/**
 * The paused-cover scale as seen by **both** renderings of the shared album cover — the full artwork
 * and the lyrics-header thumbnail.
 *
 * They are the two ends of one shared element, and [AnimatedContent] composes both presentations for
 * the length of a presentation change, so at every instant of the cross-fade the two renderings are
 * blended and their shared bounds are animating. That means the two ends must render at the **same**
 * scale. The earlier code applied the paused scale to the full artwork only, so the unscaled header
 * thumbnail faded in over the settled 94 % artwork: the apparent scale slid 0.94 -> 1.0 and only then
 * started shrinking into the header, which is the "enlarge then shrink" the user saw. Sharing one
 * value keeps the scale constant through the move, so the transition's own bounds animation is the
 * only animated quantity and the visible size (bounds x scale) is monotone by construction.
 *
 * Kept as a pure function so the curve can be unit-tested (see `SharedCoverScaleTest`).
 */
internal fun sharedCoverPausedScale(pausedCoverScale: Float): Float =
    pausedCoverScale.coerceIn(0f, 1f)

/**
 * The artwork dim that accompanies the paused scale, derived from the **same** single value.
 *
 * Keeping it a function of the scale (rather than a second animation) is what preserves the shared
 * cover's invariant: both renderings of the shared element compute the same alpha from the same
 * scale, so they cannot drift, and because the mapping is monotone the paired scale/alpha path only
 * moves one way. At the paused rest value (0.94) this is 1 - [PAUSED_ARTWORK_DIM] = 0.94; at full
 * size it is 1. Values outside the paused range clamp, so a stray scale can never make the cover
 * darker than the paused rest state.
 *
 * Kept as a pure function so the mapping can be unit-tested (see `SharedCoverScaleTest`).
 */
internal fun sharedCoverPausedAlpha(pausedCoverScale: Float): Float {
    val pausedFraction =
        ((1f - pausedCoverScale) / (1f - PAUSED_ARTWORK_SCALE)).coerceIn(0f, 1f)
    return 1f - pausedFraction * PAUSED_ARTWORK_DIM
}

/**
 * The full-size cover's corner radius, exposed for the cover transition.
 *
 * Its destination radius, so the travelling cover arrives at the exact radius the player would have
 * drawn. Duplicating the number would let the two drift, and the jump would show at the handover.
 */
val PlayerArtworkCorner = ARTWORK_CORNER

/** How long the backdrop stays frozen after a presentation change begins. */
private const val TRANSITION_SETTLE_MS = 800L

/**
 * Fraction of the chrome's travel that happens *after* it has faded out completely.
 *
 * Opacity is deliberately not locked 1:1 to the position, as it was: it reaches zero with this much of
 * the move still to come, so the chrome is gone before it stops. Locking the two together left a faint
 * ghost hanging over the lyrics for the tail of the transition, which is what made the exit read as a
 * fade rather than as a departure.
 */
private const val CHROME_FADE_TAIL = 0.22f

/**
 * How far the chrome travels on its way in/out, as a fraction of its own extent.
 *
 * Sized so the *visible* travel still reads after the fade tail is accounted for: the chrome is gone at
 * 78% of the way, by which point this fraction has squared to roughly a third of the chrome's height.
 */
private const val CHROME_SLIDE_FRACTION = 0.55f

/** How much the chrome shrinks as it leaves, so it recedes instead of sliding flat over the lyrics. */
private const val CHROME_EXIT_SCALE = 0.03f

/**
 * Extra backdrop darkening applied as the chrome leaves.
 *
 * While immersed the lyrics are the only thing on screen, so the backdrop can afford to recede
 * further: this takes the total dim from [BACKGROUND_DIM] to roughly 0.44, deepening white-text
 * contrast exactly when nothing else competes with the text.
 */
private const val IMMERSIVE_EXTRA_DIM = 0.14f

/** The screen edge the chrome belongs to, and therefore the way it leaves when immersive mode starts. */
private enum class ChromeEdge { Top, Bottom, Start, End }

/**
 * Fades the chrome out while moving it toward the edge it belongs to.
 *
 * The header leaves upward, the transport downward, and the landscape rail sideways — each toward the
 * edge it sits against, which is what makes the exit read as the chrome "going home" rather than as a
 * generic dissolve. Sliding *and* fading together is what keeps this from reading as a plain opacity
 * change: the movement gives the direction, the fade hides the exit.
 *
 * Four details do the work of making it feel deliberate rather than switched off:
 *
 *  - **Opacity leads the movement** ([CHROME_FADE_TAIL]): the chrome is invisible with a fifth of the
 *    move still to run, so nothing lingers half-faded over the lyrics.
 *  - **The travel accelerates**, via the square on the offset. A departure that gathers pace reads as
 *    the chrome being whisked away; one at constant speed reads as a mechanical slide. Because the
 *    square is on the *offset*, not on time, it inverts for free: on the way out the offset grows and
 *    the motion accelerates, and on the way back it shrinks and the motion decelerates into place. That
 *    is the mirror Apple asks for, without a second curve to keep in sync.
 *  - **The travel is relative to the edge's own axis** — height for the top/bottom chrome, width for
 *    the landscape rail. A full-height rail measured against its height (an earlier version) would
 *    have slid by a third of the *screen*, which is far too far.
 *  - **A slight recession** ([CHROME_EXIT_SCALE]), anchored to that edge, so the chrome drops back into
 *    depth instead of sliding flat. This is draw-phase `graphicsLayer` state, so it costs no
 *    re-measure — the trap the deleted container-expand transition fell into.
 *
 * Alpha also drives hit-testing, so the chrome stops accepting taps as soon as it is visually gone.
 */
private fun Modifier.chromeSlide(progress: Float, edge: ChromeEdge): Modifier =
    graphicsLayer {
        alpha = ((progress - CHROME_FADE_TAIL) / (1f - CHROME_FADE_TAIL)).coerceIn(0f, 1f)

        val gone = 1f - progress
        val travel = CHROME_SLIDE_FRACTION * gone * gone

        when (edge) {
            ChromeEdge.Top -> translationY = -travel * size.height
            ChromeEdge.Bottom -> translationY = travel * size.height
            ChromeEdge.Start -> translationX = -travel * size.width
            ChromeEdge.End -> translationX = travel * size.width
        }

        val scale = 1f - CHROME_EXIT_SCALE * gone
        scaleX = scale
        scaleY = scale
        transformOrigin = when (edge) {
            ChromeEdge.Top -> TransformOrigin(0.5f, 0f)
            ChromeEdge.Bottom -> TransformOrigin(0.5f, 1f)
            ChromeEdge.Start -> TransformOrigin(0f, 0.5f)
            ChromeEdge.End -> TransformOrigin(1f, 0.5f)
        }
    }

/**
 * Reading position of the focused lyric line, as a fraction of the lyrics area.
 *
 * The value is measured, not guessed: comparing the reference layout against ours showed our
 * first line sitting at ~50% of the frame where the reference has it at ~32%, which works out
 * to this fraction (see `tools/solve_lyrics_position.py`). The lead-in dots are rendered inside
 * the first item, so that item's text sits lower than later ones — about 32% during the lead-in
 * and 25% once singing starts.
 *
 * Identical in both chrome states — the chrome only changes alpha, so the lyrics never move.
 */
private const val LYRICS_FOCUS_FRACTION = 0.24f

/**
 * Height of the lyrics fade behind the header (status bar inset, thumbnail, title, actions).
 */
private val CHROME_TOP_FADE = 140.dp

/**
 * Height of the lyrics fade behind the controls, measured so the whole control block ends up
 * inside the faded zone.
 */
private val CHROME_BOTTOM_FADE = 300.dp

/**
 * Maximum alpha of the soft bottom scrim behind the controls.
 *
 * Enough to hold the buttons against bright artwork, low enough that the continuous background
 * still reads through the bottom of the page.
 */
private const val CONTROL_BAR_TINT = 0.38f

/**
 * Share of the screen height covered by the soft bottom scrim.
 *
 * 0.34 puts its transparent start above the control bar, so the scrim eases the background into
 * the controls instead of beginning at their top edge; the gradient reaches full tint only at the
 * very bottom of the page.
 */
private const val BOTTOM_SCRIM_FRACTION = 0.34f

/** Width of the landscape chrome rail, sized so the transport still fits. */
private val LANDSCAPE_CONTROLS_WIDTH = 340.dp

/**
 * Leading margin the landscape lyrics take on as the rail collapses.
 *
 * Once immersed the lyrics own the full width, so without this the first glyph would sit flush
 * against the screen edge; the reference keeps a comfortable margin. Ramped by `1 - chromeProgress`
 * so it is zero while the rail is present and full when the rail is gone.
 */
private val LANDSCAPE_LYRIC_INSET = 28.dp

/**
 * Fraction of the landscape lyrics viewport faded at the top and bottom.
 *
 * Nothing overlays the lyrics in landscape, so this fade is not hiding a panel — it hides the
 * list's own outer rows. Without it a 384dp-tall viewport shows most of the song at once, which
 * reads as crammed rather than as lyrics you can follow.
 */
private const val LANDSCAPE_LYRIC_EDGE_FRACTION = 0.16f

/**
 * Reading position in landscape.
 *
 * Higher than portrait: the rail occupies the left third, so a line sitting at portrait's 24%
 * of a much shorter viewport leaves almost no room for the lines that follow.
 */
private const val LANDSCAPE_LYRICS_FOCUS_FRACTION = 0.30f

/**
 * How much the backdrop is darkened.
 *
 * The generated colour field is bright by design, which costs contrast for the white lyric text
 * on top of it. Measured over real covers (`tools/measure_real_covers_dim.py`), mean white-text
 * contrast rises from 2.8:1 undimmed to ~3.8:1 here — the value large, bold lyrics are held to.
 * A darker backdrop would score better still but would flatten the effect, so this sits at the
 * shallow end that still clears the bar.
 *
 * Applied to *every* backdrop layer (see [HyperBackground.dim]) so the blur/sharp boundary
 * gains no tonal step.
 */
private const val BACKGROUND_DIM = 0.30f

/** Begin the lyrics fade this far above the panel, so it completes at its top edge. */
private const val LYRIC_FADE_REACH_PX = 90



/**
 * Download-feedback timings.
 *
 * The badge is held long enough to be read without being dismissed by reflex, then fades. A failure
 * is held longer because it carries a message; a plain success only has to register.
 */
private const val BADGE_HOLD_MS = 900L
private const val BADGE_HOLD_ERROR_MS = 1900L

/** Fade duration. Two spellings because `tween` takes an Int and `delay` a Long. */
private const val BADGE_FADE_MS = 320
private const val BADGE_FADE_MS_L = 260L
