package com.yunyin.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.yunyin.music.core.Track
import com.yunyin.music.core.player.effects.AudioReactive
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.playback.PlaybackUiState
import com.yunyin.music.ui.LYRIC_OFFSET_LIMIT_MS
import com.yunyin.music.ui.background.DynamicBackgroundPalette
import com.yunyin.music.ui.background.HyperBackground
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.formatDuration
import com.yunyin.music.ui.components.pressableGestures
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleColors
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.SFPro
import com.yunyin.music.ui.DownloadState
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.delay

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
    loader: ArtworkLoader,
    qualityLabel: String,
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
    onShowSettings: () -> Unit,
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
    /** Called with a long-pressed lyric's text; null when the line had nothing to copy. */
    onCopyLyric: (String?) -> Unit = {},
    positionProvider: () -> Long,
    modifier: Modifier = Modifier,
) {
    val track = state.current

    // Battery-saver state, observed so toggling it takes effect without leaving the screen.
    val powerSaveMode = rememberPowerSaveMode()

    // The actions sheet (ellipsis) and the lyric-timing sheet it can open.
    var showActions by remember { mutableStateOf(false) }
    var showLyricOffset by remember { mutableStateOf(false) }

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
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    var interaction by remember { mutableIntStateOf(0) }
    var immersive by remember { mutableStateOf(false) }

    LaunchedEffect(showLyrics, interaction, state.isPlaying) {
        if (!showLyrics) {
            immersive = false
            return@LaunchedEffect
        }
        immersive = false
        delay(IMMERSIVE_IDLE_MS)
        if (state.isPlaying) immersive = true
    }

    // Only analyse audio while the player is actually visible, so the tee costs nothing
    // during normal browsing.
    DisposableEffect(Unit) {
        AudioReactive.enabled = true
        onDispose { AudioReactive.enabled = false }
    }

    val displayedPosition = if (scrubFraction >= 0f) {
        (state.durationMs * scrubFraction).toLong()
    } else {
        state.positionMs
    }

    // 1 = chrome fully present, 0 = immersed. Everything that belongs to the chrome — the header,
    // the transport, the frosted split and the lyric fade zones — is derived from this one value,
    // so the whole state change is a single coordinated move rather than several independent
    // snaps.
    val chromeProgress by animateFloatAsState(
        targetValue = if (immersive) 0f else 1f,
        animationSpec = tween(durationMillis = CHROME_FADE_MS, easing = ChromeEasing),
        label = "chrome-progress",
    )
    val wakeOnTap = if (immersive) {
        Modifier.pointerInput(Unit) { detectTapGestures { interaction++ } }
    } else {
        Modifier
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
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
        val isLandscape = maxWidth > maxHeight

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
        // Frosting is achieved by drawing the *same* full-screen field twice: once blurred and
        // once sharp, with the sharp copy masked off inside the control panel. Both copies use
        // identical geometry and uniforms, so their colours are identical and the only
        // difference at the boundary is sharpness — no tonal step, which is what a second,
        // separately-sized shader instance produced.
        //
        // The panel sits at the bottom in portrait. In landscape it follows the chrome rail, which
        // is on the *left* in the lyrics presentation (lyrics take the wide right-hand area) and on
        // the *right* in the artwork presentation (the cover leads on the left). The mask is
        // derived from the same `chromeOnLeft` flag as the layout, so the two cannot disagree.
        val chromeOnLeft = isLandscape && showLyrics
        val frostEdge = when {
            !isLandscape -> FrostEdge.Bottom
            chromeOnLeft -> FrostEdge.Left
            else -> FrostEdge.Right
        }
        // The *settled* panel size, i.e. with the chrome fully visible. The landscape rail is an
        // animation away from its full width (`LANDSCAPE_CONTROLS_WIDTH * chromeProgress`), so
        // taking its measured width here would fold the fade into the size and make the frost
        // retract twice as fast as the scrim. The portrait bar keeps its size and only fades, so
        // its measured height is already the settled one.
        val frostPanelPx = if (isLandscape) {
            with(density) { LANDSCAPE_CONTROLS_WIDTH.roundToPx() }
        } else {
            panelHeightPx
        }
        val frostExtentPx = if (isLandscape) screenWidthPx else screenHeightPx

        val frostFraction = if (showLyrics && frostExtentPx > 0f && frostPanelPx > 0) {
            // How much of the axis the frost should cover right now: its settled share of the
            // panel, shrinking to nothing as the chrome leaves. Expressed as the fraction of the
            // sharp layer that SURVIVES, which is what the mask consumes:
            //   * far end (bottom / right): the panel hugs the far edge, so the sharp region is
            //     everything before it -> `1 - frosted`.
            //   * near end (left): the panel hugs the near edge, so the sharp region is everything
            //     after it -> `frosted`.
            // Driving the retraction from this single product keeps the boundary exactly on the
            // panel's edge at full chrome and correct at every point of the fade — the previous
            // two-term interpolation retracted in the wrong direction for the near end.
            val panelFraction = (frostPanelPx.toFloat() / frostExtentPx).coerceIn(0f, 1f)
            val frosted = panelFraction * chromeProgress
            if (frostEdge == FrostEdge.Left) frosted else 1f - frosted
        } else {
            1f
        }

        // Recede slightly further while immersed so the lyrics have the most contrast exactly
        // when nothing else competes with them. Applied to both layers, so the split stays even.
        val backgroundDim = BACKGROUND_DIM + (1f - chromeProgress) * IMMERSIVE_EXTRA_DIM

        // Freeze the backdrop while the two presentations cross-fade: it sits behind both of them,
        // so animating it during the transition adds no visible motion but does add a full-screen
        // shader draw every frame. Measured, this window is where "Slow issue draw commands" spiked.
        val transitioning = transitionActive

        // Bottom layer: the same field, blurred. Only visible inside the panel, because the
        // sharp layer above masks itself off there.
        HyperBackground(
            palette = palette,
            isDark = true,
            dim = backgroundDim,
            paused = transitioning,
            modifier = Modifier.fillMaxSize().blur(CONTROL_BAR_BLUR),
        )
        // Top layer: the field, sharp, masked to finish at the panel's leading edge. The mask is a
        // rounded rectangle, so the frosted region reads as an inset panel.
        HyperBackground(
            palette = palette,
            isDark = true,
            dim = backgroundDim,
            paused = transitioning,
            modifier = Modifier
                .fillMaxSize()
                .sharpAbove(frostFraction, frostEdge),
        )

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
                    (
                        (fadeIn(tween(PRESENTATION_FADE_MS)) togetherWith
                            fadeOut(tween(PRESENTATION_FADE_MS))) using null
                        ).apply { targetContentZIndex = 1f }
                },
                label = "presentation",
            ) { lyricsShown ->
                // ---------------------------------------------- presentations
                //
                // Portrait and landscape differ in *where the chrome lives*, so the two branches
                // below share one rule: landscape puts the chrome in a left-hand rail and gives the
                // remaining (right-hand) area to the content — the cover, or the lyrics. That is
                // what the frosted backdrop keys off too, so the frost never has to move between
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
                    // frosted backdrop keys off the same flag, so the two cannot disagree.
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
                                ArtworkWithGestures(
                                    artSize = minOf(maxWidth, maxHeight) * 0.94f,
                                    track = track,
                                    cover = cover,
                                    loader = loader,
                                    download = download,
                                    onToggleLyrics = { interaction++; onToggleLyrics() },
                                    onSaveCover = { interaction++; onSaveCover() },
                                    onDownloadDismissed = onDownloadDismissed,
                                    onCollapse = onCollapse,
                                    dragDistance = { dragDistance },
                                    setDragDistance = { dragDistance = it },
                                    sharedScope = this@SharedTransitionLayout,
                                    visibilityScope = this@AnimatedContent,
                                )
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
                                .chromeSlide(chromeProgress, slideUp = false)
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
                                        cover = cover,
                                        onCoverClick = {
                                            interaction++
                                            onToggleLyrics()
                                        },
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
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                PlayerControls(
                                    state = state,
                                    displayedPosition = displayedPosition,
                                    qualityLabel = qualityLabel,
                                    showLyrics = showLyrics,
                                    onSeek = { fraction ->
                                        scrubFraction = -1f
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
                                    onToggleLyrics = { interaction++; onToggleLyrics() },
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
                                // the frost look broken. Landscape is a separate branch above and does
                                // not need the clamp.
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
                            Box(Modifier.chromeSlide(chromeProgress, slideUp = true).then(wakeOnTap)) {
                                LyricsHeader(
                                    track = track,
                                    loader = loader,
                                    onMoreClick = {
                                        interaction++
                                        showActions = true
                                    },
                                    liked = liked,
                                    onToggleLike = { interaction++; onToggleLike() },
                                    cover = cover,
                                    onCoverClick = {
                                        interaction++
                                        onToggleLyrics()
                                    },
                                    sharedScope = this@SharedTransitionLayout,
                                    visibilityScope = this@AnimatedContent,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }

                        // Control bar.
                        //
                        // The frost comes from the masked backdrop layers above, so the panel itself
                        // only supplies a *very* light scrim for button legibility. Its alpha ramps in
                        // from zero at the top edge, so there is no step where the panel begins.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .onSizeChanged { panelHeightPx = it.height }
                                .chromeSlide(chromeProgress, slideUp = false)
                                .then(wakeOnTap),
                        ) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0f to Color.Transparent,
                                            0.55f to Color.Black.copy(alpha = CONTROL_BAR_TINT),
                                            1f to Color.Black.copy(alpha = CONTROL_BAR_TINT),
                                        ),
                                    ),
                            )
                            PlayerControls(
                                state = state,
                                displayedPosition = displayedPosition,
                                qualityLabel = qualityLabel,
                                showLyrics = showLyrics,
                                onSeek = { fraction ->
                                    scrubFraction = -1f
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
                                onToggleLyrics = { interaction++; onToggleLyrics() },
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
                        Spacer(Modifier.height(56.dp))

                        BoxWithConstraints(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            val artSize = minOf(maxWidth * 0.87f, maxHeight)
                            ArtworkWithGestures(
                                artSize = artSize,
                                track = track,
                                cover = cover,
                                loader = loader,
                                download = download,
                                onToggleLyrics = { interaction++; onToggleLyrics() },
                                onSaveCover = { interaction++; onSaveCover() },
                                onDownloadDismissed = onDownloadDismissed,
                                onCollapse = onCollapse,
                                dragDistance = { dragDistance },
                                setDragDistance = { dragDistance = it },
                                sharedScope = this@SharedTransitionLayout,
                                visibilityScope = this@AnimatedContent,
                            )
                        }

                        TitleBlock(
                            track = track,
                            liked = liked,
                            onToggleLike = { interaction++; onToggleLike() },
                            onMoreClick = {
                                interaction++
                                showActions = true
                            },
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(18.dp))

                        PlayerControls(
                            state = state,
                            displayedPosition = displayedPosition,
                            qualityLabel = qualityLabel,
                            showLyrics = showLyrics,
                            onSeek = { fraction ->
                                scrubFraction = -1f
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
                            onToggleLyrics = { interaction++; onToggleLyrics() },
                            onShowQueue = { interaction++; onShowQueue() },
                        )
                    }
                }
            }
        }

        // Both sheets are rendered last so they layer over whichever presentation is showing.
        if (showActions) {
            TrackActionsSheet(
                track = track,
                hasLyrics = lyrics != null,
                onDismiss = { showActions = false },
                onSaveCover = onSaveCover,
                onShare = onShareTrack,
                onCalibrateLyrics = {
                    interaction++
                    showLyricOffset = true
                },
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
    }
}

/**
 * The album cover with its gestures, shared by the portrait and landscape layouts.
 *
 * Kept in one place so the shared-element declaration exists exactly once: both arrangements must
 * register the same `COVER_SHARED_KEY`, and duplicating it would risk the two drifting apart.
 *
 * `dragDistance` is passed as a getter/setter pair because the value lives in the parent's state —
 * the gesture and the dismissal threshold belong together.
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
    onCollapse: () -> Unit,
    dragDistance: () -> Float,
    setDragDistance: (Float) -> Unit,
    // Receiver scopes are passed in rather than captured: this is a separate composable, so the
    // caller's `this@SharedTransitionLayout` / `this@AnimatedContent` are not in scope here.
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
) {
    Box(
        Modifier
            .fillMaxSize()
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
                dragDistance = dragDistance,
                setDragDistance = setDragDistance,
                onDragEnd = { distance ->
                    if (distance > 90f) onCollapse()
                    setDragDistance(0f)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Artwork(
            url = track?.coverUrl,
            loader = loader,
            corner = ARTWORK_CORNER,
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
                        )
                    },
                )
                .shadow(14.dp, ContinuousRoundedRectangle(ARTWORK_CORNER)),
        )

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
        animationSpec = tween(320, easing = ChromeEasing),
        label = "download-scrim",
    )
    // The badge stays up while `badgeVisible`, and the whole overlay unmounts once it has faded.
    val badge by animateFloatAsState(
        targetValue = if (badgeVisible) 1f else 0f,
        animationSpec = tween(BADGE_FADE_MS, easing = ChromeEasing),
        label = "download-badge",
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
        if (scrim > 0.01f) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = scrim)))
        }

        if (state is DownloadState.Downloading) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(46.dp).graphicsLayer { alpha = scrim / 0.55f },
            )
        }

        if (badge > 0.01f) {
            val saved = state is DownloadState.Saved
            // A small overshoot on arrival, settling at 1: the confirmation "lands".
            val pop = 0.86f + 0.14f * badge
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = badge
                        scaleX = pop * (1f + (1f - badge) * 0.06f)
                        scaleY = pop * (1f + (1f - badge) * 0.06f)
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
    qualityLabel: String,
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
            progress = if (state.durationMs > 0) {
                (displayedPosition.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            },
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
            QualityChip(level = qualityLabel, modifier = Modifier.align(Alignment.Center))
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
            BottomIcon(
                icon = if (state.shuffle) SfIcons.Shuffle else SfIcons.Repeat,
                description = if (state.shuffle) "随机播放（长按切换循环）" else "循环模式（长按开启随机）",
                active = state.shuffle || state.repeatMode != Player.REPEAT_MODE_OFF,
                onClick = onCycleRepeat,
                onLongClick = onToggleShuffle,
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
    /** The already-decoded cover, so the thumbnail never shows a placeholder mid-transition. */
    cover: ImageBitmap?,
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
                    ),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track?.name ?: "未在播放",
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = track?.artistLine.orEmpty(),
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
 * does (the queue, lyric calibration).
 *
 * Every row performs a real action; none is decorative. Rows that cannot work in the current state
 * (no lyrics to calibrate) are shown disabled rather than hidden, so the sheet's shape does not
 * change under the user between songs.
 */
@Composable
private fun TrackActionsSheet(
    track: Track?,
    hasLyrics: Boolean,
    onDismiss: () -> Unit,
    onSaveCover: () -> Unit,
    onShare: () -> Unit,
    onCalibrateLyrics: () -> Unit,
    onShowQueue: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(indication = null, interactionSource = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                    .background(Color(0xFF1C1C1E))
                    .clickable(enabled = false, indication = null, interactionSource = null) { }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = track?.name ?: "未在播放",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
                ActionRow(SfIcons.SquareAndArrowUp, "保存封面", true) {
                    onDismiss(); onSaveCover()
                }
                ActionRow(SfIcons.QuoteOpening, "分享歌曲", track != null) {
                    onDismiss(); onShare()
                }
                ActionRow(SfIcons.Clock, "歌词校准", hasLyrics) {
                    onDismiss(); onCalibrateLyrics()
                }
                ActionRow(SfIcons.ListBullet, "播放队列", true) {
                    onDismiss(); onShowQueue()
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "取消",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 17.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .clickable(
                            indication = rememberControlRipple(bounded = true),
                            interactionSource = null,
                            onClick = onDismiss,
                        )
                        .padding(vertical = 14.dp),
                )
            }
        }
    }
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
                // The sheet is a fixed dark surface with white ink in either appearance, so the
                // theme's own ink would paint a dark ripple here on a light theme.
                indication = rememberControlRipple(bounded = true),
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
            tint = Color.White.copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            fontFamily = SFPro,
            fontSize = 17.sp,
            color = Color.White.copy(alpha = alpha),
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
    // The sheet is a dark surface too, so its pill buttons get the white indication as well. Bounded
    // here: a pill's ripple should stay inside the pill rather than spill past its rounded edge.
    val resetRipple = rememberControlRipple(bounded = true)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                // A scrim that dims the screen to catch a dismissal tap should not paint anything
                // when pressed; the default indication would flash a large dark rectangle.
                .clickable(indication = null, interactionSource = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth(0.86f)
                    // Swallow taps so clicking the card does not dismiss it.
                    .clickable(
                        enabled = false,
                        indication = null,
                        interactionSource = null,
                    ) { }
                    .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
                    .background(Color(0xFF1C1C1E))
                    .padding(20.dp),
            ) {
                Text(
                    text = "歌词校准",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = Color.White,
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
                    color = Color.White.copy(alpha = 0.65f),
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
                        color = Color.White.copy(alpha = 0.55f),
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (offsetMs == 0L) "0 ms" else "%+d ms".format(offsetMs),
                            fontFamily = SFPro,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Color.White,
                        )
                    }
                    Text(
                        text = "延后",
                        fontFamily = SFPro,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "重置",
                        fontFamily = SFPro,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.75f),
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
                        color = Color.White,
                        modifier = Modifier
                            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable(indication = resetRipple, interactionSource = null, onClick = onDismiss)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Title + artist block shown under the artwork. */
@Composable
private fun TitleBlock(
    track: Track?,
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // Cross-faded on track id, matching the mini player: skipping a track should read as
            // the identity changing, not as text being overwritten in place.
            CrossfadeContent(
                targetState = track?.id,
                modifier = Modifier.fillMaxWidth(),
                durationMillis = 260,
                label = "title-block",
            ) {
                Column {
                    Text(
                        text = track?.name ?: "未在播放",
                        fontFamily = SFPro,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = track?.artistLine.orEmpty(),
                        fontFamily = SFPro,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(120)),
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

@Composable
private fun QualityChip(level: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Text(
            text = level,
            fontFamily = SFPro,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
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
    Box(
        Modifier.size(64.dp).clickable(indication = ripple, interactionSource = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, onClick: () -> Unit) {
    val ripple = rememberControlRipple()
    Box(
        Modifier.size(72.dp).clickable(indication = ripple, interactionSource = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Cross-faded so pressing it reads as the control changing state rather than the glyph
        // being swapped; the size difference between the two icons is preserved per state.
        CrossfadeContent(
            targetState = isPlaying,
            durationMillis = 180,
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BottomIcon(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val ripple = rememberControlRipple()
    Box(
        Modifier
            .size(56.dp)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        indication = ripple,
                        interactionSource = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier.clickable(indication = ripple, interactionSource = null, onClick = onClick)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Tint eases between active/inactive instead of snapping, and the glyph cross-fades so
        // toggling shuffle (which swaps the repeat icon for the shuffle icon) reads as the control
        // changing rather than the button being redrawn.
        val tint by animateColorAsState(
            targetValue = if (active) Color.White else Color.White.copy(alpha = 0.72f),
            label = "bottom-icon-tint",
        )
        CrossfadeContent(
            targetState = icon.name,
            durationMillis = 200,
            label = "bottom-icon",
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** Idle time before the lyrics presentation fades its chrome away. */
private const val IMMERSIVE_IDLE_MS = 4000L

/** Chrome transition duration when entering or leaving immersive mode. */
private const val CHROME_FADE_MS = 420

/**
 * Duration of the cross-fade between the artwork and lyrics presentations.
 *
 * The *cover* does not use this: it is a shared element and gets its own curve below, so the two
 * can be tuned independently rather than the whole screen moving at one rate.
 */
private const val PRESENTATION_FADE_MS = 260

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

/** How long the backdrop stays frozen after a presentation change begins. */
private const val TRANSITION_SETTLE_MS = 600L

/**
 * Easing for the immersive transition.
 *
 * A decelerating curve: the chrome leaves quickly at first, then settles, which reads as the UI
 * "getting out of the way" rather than being switched off.
 */
private val ChromeEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

/** How far the chrome travels on its way in/out, as a fraction of its own height. */
private const val CHROME_SLIDE_FRACTION = 0.35f

/**
 * Extra backdrop darkening applied as the chrome leaves.
 *
 * While immersed the lyrics are the only thing on screen, so the backdrop can afford to recede
 * further: this takes the total dim from [BACKGROUND_DIM] to roughly 0.44, deepening white-text
 * contrast exactly when nothing else competes with the text.
 */
private const val IMMERSIVE_EXTRA_DIM = 0.14f

/**
 * Fades the chrome out while sliding it toward the screen edge it belongs to.
 *
 * The header leaves upward and the transport downward, so the two halves part rather than
 * dissolve in place. Sliding *and* fading together is what keeps this from reading as a plain
 * opacity change: the movement gives the direction, the fade hides the exit. Alpha also drives
 * hit-testing, so the chrome stops accepting taps as soon as it is visually gone.
 */
private fun Modifier.chromeSlide(progress: Float, slideUp: Boolean): Modifier =
    graphicsLayer {
        alpha = progress
        val travel = CHROME_SLIDE_FRACTION * size.height * (1f - progress)
        translationY = if (slideUp) -travel else travel
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
 * Tint over the frosted control bar.
 *
 * Enough to hold the buttons against bright artwork, low enough that the blurred mesh is
 * still clearly present (the bar must not look like a flat panel).
 */
private const val CONTROL_BAR_TINT = 0.38f

/** Blur applied to the reused background behind the control bar. */
private val CONTROL_BAR_BLUR = 40.dp

/** Corner radius of the frosted control panel. */
private val PANEL_CORNER_RADIUS = 20.dp

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
 * Masks the content so the frosted region ends at [keepFraction] of the axis.
 *
 * Used to confine the sharp copy of the backdrop to the area outside the control panel. The
 * transition is a short alpha ramp rather than a hard cut, and because the layer it reveals
 * underneath is a blurred copy of the *same* image, the ramp changes only sharpness — never
 * colour — so it cannot read as a tonal band.
 *
 * [keepFraction] is the fraction of the axis on the **content** side of the boundary, i.e. where
 * the sharp layer survives: on the far end (bottom / right) the sharp region is everything before
 * the boundary, so it is `1 - frosted`; on the near end (left) it is everything after it, so it is
 * `frosted` itself. The caller computes it from the panel's share of the axis times the chrome's
 * progress, so the boundary sits exactly on the panel's edge at full chrome and retracts to nothing
 * as the chrome leaves. Deriving the boundary directly here (rather than interpolating from a
 * measured panel) is what keeps all three edges consistent.
 *
 * The cut-out for the bottom edge is a rounded-top rectangle so the frosted region reads as an
 * inset panel. The operation is `DstOut` — it *removes* the sharp layer inside that shape,
 * revealing the blurred copy beneath. (`DstIn` with the same path would do the opposite and keep
 * the sharp content inside the panel.)
 */
private fun Modifier.sharpAbove(
    keepFraction: Float,
    edge: FrostEdge = FrostEdge.Bottom,
): Modifier =
    this
        .graphicsLayer {
            // Only request an offscreen layer when a mask will actually be drawn.
            //
            // `CompositingStrategy.Offscreen` allocates a full-screen buffer and composites it
            // back — the price of being able to `DstOut` the content. On the artwork screen
            // `keepFraction` is 1f, so the draw lambda returns immediately without masking, and
            // that buffer was pure waste on every frame.
            compositingStrategy = if (keepFraction < 1f) {
                CompositingStrategy.Offscreen
            } else {
                CompositingStrategy.Auto
            }
        }
        .drawWithContent {
            drawContent()
            val end = keepFraction.coerceIn(0f, 1f)
            // Fully sharp (no chrome) or fully frosted (nothing left to keep): nothing to mask.
            if (end >= 1f) return@drawWithContent
            if (end <= 0f) return@drawWithContent

            val ramp = 0.05f
            val horizontal = edge != FrostEdge.Bottom
            val along = if (horizontal) size.width else size.height

            // The panel hugs either the far end of the axis (bottom / right) or the near end
            // (left). Which one decides which side of the boundary is cut, and which way the soft
            // ramp faces, so it is derived once here rather than duplicated per edge.
            val farEnd = edge != FrostEdge.Left
            val rampPx = ramp * along
            val boundary = end * along
            val solidBoundary = if (farEnd) {
                (boundary + rampPx).coerceAtMost(along)
            } else {
                (boundary - rampPx).coerceAtLeast(0f)
            }

            val soft = androidx.compose.ui.graphics.Path().apply {
                when (edge) {
                    FrostEdge.Bottom ->
                        addRect(androidx.compose.ui.geometry.Rect(0f, boundary, size.width, solidBoundary))
                    FrostEdge.Right ->
                        addRect(androidx.compose.ui.geometry.Rect(boundary, 0f, solidBoundary, size.height))
                    FrostEdge.Left ->
                        addRect(androidx.compose.ui.geometry.Rect(solidBoundary, 0f, boundary, size.height))
                }
            }
            val solid = androidx.compose.ui.graphics.Path().apply {
                when (edge) {
                    FrostEdge.Bottom -> {
                        // The portrait panel floats above the screen's bottom edge, so its
                        // top-facing corners are rounded.
                        val radius = PANEL_CORNER_RADIUS.toPx()
                        val round = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                        val zero = androidx.compose.ui.geometry.CornerRadius.Zero
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = 0f, top = solidBoundary, right = size.width, bottom = size.height,
                                topLeftCornerRadius = round, topRightCornerRadius = round,
                                bottomLeftCornerRadius = zero, bottomRightCornerRadius = zero,
                            ),
                        )
                    }
                    // A landscape rail is flush with the screen's top and bottom edges, so its
                    // corners must be square: rounding them cut two notches out of the frost
                    // boundary, which read as a band across the top of the panel.
                    FrostEdge.Right ->
                        addRect(androidx.compose.ui.geometry.Rect(solidBoundary, 0f, size.width, size.height))
                    FrostEdge.Left ->
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, solidBoundary, size.height))
                }
            }
            drawPath(soft, Color.Black.copy(alpha = 0.55f), blendMode = BlendMode.DstOut)
            drawPath(solid, Color.Black, blendMode = BlendMode.DstOut)
        }

/** Which screen edge the frosted control panel occupies. */
private enum class FrostEdge { Bottom, Left, Right }

/**
 * Download-feedback timings.
 *
 * The badge is held long enough to be read without being dismissed by reflex, then fades. A failure
 * is held longer because it carries a message; a plain success only has to register.
 */
private const val BADGE_HOLD_MS = 900L
private const val BADGE_HOLD_ERROR_MS = 1900L

/** Fade duration. Two spellings because `tween` takes an Int and `delay` a Long. */
private const val BADGE_FADE_MS = 260
private const val BADGE_FADE_MS_L = 260L
