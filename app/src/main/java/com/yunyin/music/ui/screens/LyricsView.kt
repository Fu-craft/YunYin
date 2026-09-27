package com.yunyin.music.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.yunyin.music.data.LyricClipboard
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.SFPro
import com.yunyin.music.lyrics.composable.lyrics.KaraokeLyricsView
import com.yunyin.music.lyrics.composable.lyrics.LyricTimeSmoothing
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics


/**
 * Word-by-word lyrics surface.
 *
 * Wraps `accompanist-lyrics-ui`'s [KaraokeLyricsView], which renders the per-syllable
 * karaoke fill, duet alignment, translations and instrumental "breathing dots".
 *
 * The player only reports its position four times a second, which is far too coarse for
 * syllable timing, so a frame ticker samples the interpolated position every frame. That
 * state is read inside the lyrics view's draw lambda, so a frame invalidates the draw
 * phase without recomposing the screen.
 */
@Composable
fun LyricsView(
    lyrics: SyncedLyrics?,
    loading: Boolean,
    isPlaying: Boolean,
    /** Interpolated playback position, safe to poll per frame. */
    positionProvider: () -> Long,
    /** Called when the user taps a line; receives the line's start time in ms. */
    onSeekToLine: (Int) -> Unit,
    /**
     * Called when the user long-presses a line.
     *
     * Receives the line's copyable text (content plus translation, or nothing when the line has no
     * real content), so the view does not have to know how a line becomes a string.
     */
    onLineLongPress: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * Reading position of the focused line, as a fraction of the lyrics area.
     *
     * Applied as symmetric content padding, which is what positions the scrolled-to line: the
     * focused item's top lands at this fraction, so its text sits a little below that.
     */
    focusFraction: Float = 0.24f,
    /** Height of the fade at the top of the list, covering the floating header. */
    topFadeZone: androidx.compose.ui.unit.Dp = 20.dp,
    /** Height of the fade at the bottom, covering the floating controls. */
    bottomFadeZone: androidx.compose.ui.unit.Dp = 100.dp,
    /**
     * Halves the frame ticker rate and smooths harder.
     *
     * For battery-saver / low-power rendering, matching how NeriPlayer degrades: the lyric fill
     * is redrawn at half rate, which is not perceptible on this kind of motion but cuts the
     * per-frame cost.
     */
    lowPowerRendering: Boolean = false,
    /**
     * Manual timing offset in milliseconds; positive shows the lyrics later.
     *
     * Applied to the clock the view consumes rather than by re-timing the lyric document, so
     * dragging the offset takes effect immediately and does not interact with the one-shot
     * cross-source alignment.
     */
    offsetMs: Long = 0L,
    /**
     * Syllable layouts for the current [lyrics], hoisted by the caller.
     *
     * Owned *above* the presentation switch so it survives this view being disposed while the
     * artwork is showing. Without that, every return to the lyrics re-measures the whole song,
     * which is the measured cause of the stutter when switching presentations.
     */
    layoutCache: MutableMap<Int, List<com.yunyin.music.lyrics.composable.lyrics.SyllableLayout>>? = null,
    /**
     * The three text styles to render with, supplied by the caller.
     *
     * Deliberately *not* derived here from the measured height. The caller pre-warms
     * [layoutCache] by measuring every line, and a cache whose style differs from the one used to
     * draw produces rows spaced for one font size and glyphs sized for another — so wrapped rows
     * overlap. Keeping the decision in one place (the caller) makes the two agree by construction,
     * and lets it re-measure when the scale changes.
     */
    lyricStyles: LyricsTextStyles = LyricsTextStyles(
        normal = TextStyle(
            fontFamily = SFPro,
            fontSize = 36.sp,
            lineHeight = 47.sp,
            fontWeight = FontWeight.Bold,
            textMotion = TextMotion.Animated,
        ),
        accompaniment = TextStyle(
            fontFamily = SFPro,
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            textMotion = TextMotion.Animated,
        ),
        phonetic = TextStyle(
            fontFamily = SFPro,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
        ),
    ),
) {
    var framePositionMs by remember { mutableLongStateOf(0L) }
    // The smoothed clock actually drawn, plus its own scratch value. Kept separate so the raw
    // position can still drive line selection and scrolling without lag.
    var smoothPositionMs by remember { mutableLongStateOf(0L) }
    val currentPosition by rememberUpdatedState(positionProvider)
    val lowPower by rememberUpdatedState(lowPowerRendering)
    val latestOffset by rememberUpdatedState(offsetMs)

    /**
     * Playback clock with the manual offset folded in.
     *
     * The offset is *subtracted*: a positive offset means "the lyrics should appear later", and
     * making a line become current later requires the clock to lag real playback. (Adding it would
     * make lines activate early — the opposite of what the sheet promises.)
     */
    fun offsetClock(): Long = currentPosition() - latestOffset

    LaunchedEffect(isPlaying, lyrics) {
        if (!isPlaying) {
            // Keep following the position while paused, at a low rate.
            //
            // This used to take a single sample and return, so a seek made while paused did **not**
            // move the lyrics at all — they stayed on whatever line was current when playback
            // stopped, until the user pressed play. Dragging the progress bar and watching the
            // lyrics ignore it is the visible form of that bug. There is no karaoke fill to animate
            // while paused, so a frame ticker would be waste; a slow poll reflects a seek quickly
            // enough to feel immediate and costs nothing measurable.
            while (true) {
                val now = offsetClock()
                if (now != framePositionMs) {
                    framePositionMs = now
                    // No smoothing while paused: there is no advancing clock to smooth, and easing
                    // toward a seek target would look like the lyrics sliding into place.
                    smoothPositionMs = now
                }
                delay(PAUSED_POLL_MS)
            }
        }
        var lastFrameNs = 0L
        while (true) {
            var frameNs = 0L
            withFrameNanos { frameNs = it }
            // On the low-power path the ticker itself runs at half rate, which is most of the
            // saving; the smoothing then hides the coarser steps.
            if (lowPower && lastFrameNs != 0L &&
                frameNs - lastFrameNs < LOW_POWER_FRAME_INTERVAL_NS
            ) {
                continue
            }
            lastFrameNs = frameNs
            framePositionMs = offsetClock()
            smoothPositionMs = LyricTimeSmoothing.next(
                reported = framePositionMs.toInt(),
                current = smoothPositionMs.toInt(),
            ).toLong()
        }
    }

    // The loading placeholder used to be replaced by the lyrics in a single frame. Cross-fading
    // makes the arrival read as the lyrics appearing rather than the text being overwritten.
    val phase = when {
        loading -> "loading"
        lyrics == null || lyrics.lines.isEmpty() -> "empty"
        else -> "lyrics"
    }

    Box(modifier = modifier.fillMaxSize()) {
        CrossfadeContent(targetState = phase, label = "lyrics-phase") { active ->
        when (active) {
            "loading" -> LyricsPlaceholder(text = null, loading = true)

            "empty" -> LyricsPlaceholder(text = "暂无歌词")

            else -> {
                // Captured locally because branching on a String loses the smart cast that the
                // original `when { lyrics == null -> ... }` gave us.
                val doc = lyrics ?: return@CrossfadeContent
                val listState = rememberLazyListState()

                // `KaraokeLyricsView` places the focused line from the list's symmetric content
                // padding, so the reading position is expressed as a fraction of the lyrics area
                // rather than as a hard-coded offset.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // The focused line's position comes from symmetric content padding, which is
                    // how the list's scroll anchor is placed. A fraction of the viewport keeps
                    // the reading position in the same place regardless of screen size.
                    val centerPadding = (maxHeight * focusFraction).coerceAtLeast(24.dp)

                    // No scrim here.
                    //
                    // A translucent band behind the lyrics darkened only part of the screen,
                    // so it was visible as a horizontal step against the background — and it
                    // stacked on top of the background's own darkening. Contrast comes from
                    // the backdrop being deepened uniformly instead, so nothing is overlaid
                    // on the lyrics and there is no band, blurred or otherwise.

                    KaraokeLyricsView(
                        listState = listState,
                        lyrics = doc,
                        // Line selection and scrolling use the raw playback time, so switching
                        // lines is never delayed by the smoothing below.
                        currentPosition = { framePositionMs.toInt() },
                        // The syllable fill is drawn from the smoothed clock, which removes the
                        // stepping a coarse playback callback would otherwise produce.
                        renderCurrentPosition = { smoothPositionMs.toInt() },
                        onLineClicked = { line -> onSeekToLine(line.start) },
                        onLinePressed = { line -> onLineLongPress(LyricClipboard.textFor(line)) },
                        modifier = Modifier.fillMaxSize(),
                        // Styles come from the caller, which also pre-warms `layoutCache` with
                        // them, so the measurements in the cache match what is drawn.
                        normalLineTextStyle = lyricStyles.normal,
                        accompanimentLineTextStyle = lyricStyles.accompaniment,
                        phoneticTextStyle = lyricStyles.phonetic,
                        textColor = Color.White,
                        // SrcOver keeps white lyrics readable over a bright, moving
                        // cover; Plus (the default) targets dark static backgrounds.
                        blendMode = BlendMode.SrcOver,
                        // Height of the top/bottom fades, sized to cover the floating chrome.
                        topFadeZone = topFadeZone,
                        bottomFadeZone = bottomFadeZone,
                        useBlurEffect = true,
                        // Library default strength; the falloff across lines is what makes the
                        // current line stand out.
                        blurDelta = 3f,
                        // Active-line growth and inactive dimming, matching the NeriPlayer
                        // tuning. Blur alone only says "these are not current"; the scale is
                        // what makes the current line read as current.
                        focusedLineScale = 1.015f,
                        unfocusedLineScale = 0.965f,
                        activeLineAlpha = 1f,
                        inactiveLineAlpha = 0.28f,
                        showTranslation = true,
                        showPhonetic = true,
                        centerPadding = centerPadding,
                        layoutCache = layoutCache,
                    )
                }
            }
        }
        }
    }
}

/**
 * Half-rate ticker interval used when low-power rendering is on (~30fps).
 *
 * Redrawing the karaoke fill at half rate is not perceptible on this kind of motion but halves
 * the per-frame work, which is the same degradation NeriPlayer applies in low-power mode.
 */
private const val LOW_POWER_FRAME_INTERVAL_NS = 33_000_000L

/**
 * How often the paused path re-reads the position.
 *
 * Only needs to be fast enough that a seek is reflected without the user noticing a lag; there is no
 * fill animation to drive while paused. 100ms is comfortably below the threshold of feeling delayed
 * and is ~10x cheaper than the playing ticker.
 */
private const val PAUSED_POLL_MS = 100L

/**
 * Kaomoji shown while lyrics load.
 *
 * It pulses (see [LyricsPlaceholder]); kept to one short line so it reads as decoration rather
 * than content, with the actual status in the text beneath it.
 */
private const val KAOMOJI_LOADING = "♪(｡･ω･｡)"

/**
 * Empty / loading state.
 *
 * Loading shows a short line of text rather than a glyph: the previous music-note icon was
 * unidentifiable at a glance, and a waiting state should say what it is waiting for. The kaomoji
 * carries the pulse so the text itself stays steady and readable.
 */
@Composable
private fun LyricsPlaceholder(text: String?, loading: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "placeholder")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        if (loading) {
            Text(
                text = KAOMOJI_LOADING,
                fontFamily = SFPro,
                fontSize = 26.sp,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.alpha(pulse),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "正在加载中",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = Color.White,
            )
        } else if (text != null) {
            Text(
                text = text,
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                color = Color.White,
            )
        }
    }
}
