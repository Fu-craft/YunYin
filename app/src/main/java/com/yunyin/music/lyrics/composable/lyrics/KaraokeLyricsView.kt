package com.yunyin.music.lyrics.composable.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastRoundToInt
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.yunyin.music.lyrics.utils.isRtl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

internal data class FocusState(
    val firstIndex: Int,
    val allIndices: List<Int>,
    val activeInterludeIndex: Int?,
    val activeIntro: Boolean,
    /**
     * True while playback is still in the lead-in, before the first line is due.
     *
     * Distinct from [activeIntro], which also controls the breathing dots: this one drives
     * *blur*, so the first line starts out blurred (as in Lyricify) and only sharpens once
     * it actually begins.
     */
    val introActive: Boolean = false,
)

/**
 * Blur steps applied to the first line during the lead-in.
 *
 * The first line sits right under the breathing dots, so it takes slightly more than the
 * normal one step to read as "not started yet" rather than as the current lyric.
 */
private const val INTRO_BLUR_STEPS = 3

/**
 * Position jump treated as a seek rather than as playback advancing.
 *
 * Playback moves the clock by at most a frame's worth between ticks, so a jump this size can only be the
 * user scrubbing the progress bar or tapping a line. `PositionInterpolator` uses the same threshold for
 * the same reason (see its `DISCONTINUITY_MS`), so the two agree on what a seek looks like.
 */
private const val SEEK_DISCONTINUITY_MS = 800

/**
 * Opacity of the focused line's **not-yet-sung** words.
 *
 * The library's own default is 0.2, which is *dimmer* than the 0.28 that inactive lines are given
 * (see `inactiveLineAlpha`). That inversion is why the highlight looked weak: the line being sung
 * had its upcoming words darker than the lines around it, so the emphasis came only from the words
 * already sung past. Raising it above the inactive alpha keeps the focused line the brightest
 * element throughout, while the sung portion still reads as progress inside the line.
 */
private const val FOCUSED_UNSUNG_ALPHA = 0.45f

/**
 * Opacity of a non-focused line's not-yet-sung words.
 *
 * Matches the library default: for lines that are not current, the whole line is already dimmed by
 * `inactiveLineAlpha`, so an extra reduction just makes them illegible.
 */
private const val INACTIVE_UNSUNG_ALPHA = 0.2f

/**
 * Minimum lead-in before the breathing dots are shown.
 *
 * Any real lead-in qualifies: the request was for the Lyricify-style dots on every song.
 * The dots' own timeline compresses to fit a short intro, so this only needs to rule out
 * a lead-in of effectively zero.
 */
private const val MIN_INTRO_FOR_DOTS_MS = 1

/**
 * A comprehensive lyrics view that supports Karaoke and Synced lyrics with advanced rendering.
 *
 * This composable handles:
 * - Scrolling and auto-scrolling to the current line
 * - Rendering karaoke lines with syllable-level timing and animations
 * - Rendering synced lines
 * - Displaying breathing dots during instrumental interludes
 * - Determining active and accompaniment lines
 *
 * @param listState The scroll state for the lazy list.
 * @param lyrics The lyrics data to display.
 * @param currentPosition A lambda returning the current playback position in milliseconds.
 * @param onLineClicked Callback when a line is clicked (seek to position).
 * @param onLinePressed Callback when a line is long-pressed (share/menu).
 * @param modifier The modifier to apply to the layout.
 * @param normalLineTextStyle The style for normal text lines.
 * @param accompanimentLineTextStyle The style for accompaniment/background vocals lines.
 * @param textColor The primary text color.
 * @param breathingDotsDefaults Styling defaults for the breathing dots.
 * @param blendMode The blend mode used for rendering text (e.g., [BlendMode.Plus] for glowing effects).
 * @param useBlurEffect Whether to apply blur effect to non-active lines.
 * @param offset The vertical padding/offset at the start and end of the list.
 * @param showDebugRectangles Debug flag to draw bounding boxes around glyphs.
 */
/**
 * Measures every line of [lyrics] into [cache], off the main thread, publishing once.
 *
 * Extracted so a caller can **pre-warm** the cache before the lyrics are actually shown. That is
 * the difference between a smooth and a stuttering presentation switch: [KaraokeLineText] falls
 * back to measuring a line synchronously during composition when its cache entry is missing, so an
 * unwarmed cache means text measurement lands on the main thread *during* the transition animation.
 *
 * Batching matters too: an earlier version hopped to `Dispatchers.Main` to store each line's
 * layout, i.e. one dispatch and one snapshot-state write per line (~60 per song). Every write
 * invalidated the cache's subscribers, repeatedly recomposing the list mid-animation. Building the
 * map off-thread and assigning it once removes that churn.
 *
 * Returns immediately (doing nothing) when the cache is already complete.
 */
suspend fun measureAllLinesIntoCache(
    lyrics: SyncedLyrics,
    textMeasurer: TextMeasurer,
    normalLineTextStyle: TextStyle,
    accompanimentLineTextStyle: TextStyle,
    phoneticTextStyle: TextStyle,
    cache: MutableMap<Int, List<SyllableLayout>>,
) {
    if (cache.size >= lyrics.lines.size) return

    val measured = withContext(Dispatchers.Default) {
        val normalStyle = normalLineTextStyle.copy(textDirection = TextDirection.Content)
        val accompanimentStyle = accompanimentLineTextStyle.copy(textDirection = TextDirection.Content)
        val phoneticStyle = phoneticTextStyle.copy(textDirection = TextDirection.Content)

        val normalSpaceWidth = textMeasurer.measure(" ", normalStyle).size.width.toFloat()
        val accompanimentSpaceWidth = textMeasurer.measure(" ", accompanimentStyle).size.width.toFloat()

        val out = HashMap<Int, List<SyllableLayout>>(lyrics.lines.size)
        lyrics.lines.forEachIndexed { index, line ->
            if (!isActive) return@withContext out
            if (line is KaraokeLine) {
                val style =
                    if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentStyle else normalStyle
                val spaceWidth =
                    if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentSpaceWidth else normalSpaceWidth

                val processedSyllables = if (line.alignment == KaraokeAlignment.End) {
                    line.syllables.dropLastWhile { it.content.isBlank() }
                } else {
                    line.syllables
                }

                out[index] = measureSyllablesAndDetermineAnimation(
                    syllables = processedSyllables,
                    textMeasurer = textMeasurer,
                    style = style,
                    phoneticStyle = phoneticStyle,
                    isAccompanimentLine = line is KaraokeLine.AccompanimentKaraokeLine,
                    spaceWidth = spaceWidth
                )
            }
        }
        out
    }
    cache.putAll(measured)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KaraokeLyricsView(
    listState: LazyListState,
    lyrics: SyncedLyrics,
    currentPosition: () -> Int,
    onLineClicked: (ISyncedLine) -> Unit,
    onLinePressed: (ISyncedLine) -> Unit,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current.copy(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textMotion = TextMotion.Animated,
    ),
    textColor: Color = Color.White,
    breathingDotsDefaults: KaraokeBreathingDotsDefaults = KaraokeBreathingDotsDefaults(),
    phoneticTextStyle: TextStyle = normalLineTextStyle.copy(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
    ),
//    TODO: expose it
//    verticalFadeBrush: Brush = Brush.verticalGradient(
//        0f to Color.White.copy(0f),
//        0.05f to Color.White,
//        0.6f to Color.White,
//        1f to Color.White.copy(0f)
//    ),
    blendMode: BlendMode = BlendMode.Plus,
    useBlurEffect: Boolean = true,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    offset: Dp = 32.dp,
    keepAliveZone: Dp = 100.dp,
    /**
     * Vertical content padding at both ends of the list.
     *
     * Because `animateScrollToItem` aligns the focused item to the top of the content,
     * equal top and bottom padding is what places it at a fixed reading position — near
     * the middle for a half-screen value. This replaces the previous hand-computed offset.
     */
    centerPadding: Dp = 32.dp,
    /**
     * Height of the fade at the top / bottom of the list.
     *
     * Sized by the caller to whatever floats over the lyrics, so lines do not run into the
     * header or the controls when the list fills the whole screen.
     */
    topFadeZone: Dp = 20.dp,
    bottomFadeZone: Dp = 100.dp,
    blurDelta: Float = 3f,
    /**
     * Scale applied to the currently-sung line, and to the rest.
     *
     * Ported from the `cwuom` fork of this library (what NeriPlayer uses): Apple Music's lyrics
     * grow the active line slightly, which reads as "this is the line" far more strongly than
     * blur/alpha alone. Blur and fading say "these others are not current"; the growth says
     * "this one is".
     */
    focusedLineScale: Float = 1f,
    unfocusedLineScale: Float = 0.98f,
    /** Opacity of the active line. Kept near 1 so the line being sung is the brightest thing. */
    activeLineAlpha: Float = 1f,
    /** Opacity of every other line. The fork's NeriPlayer tuning is 0.28. */
    inactiveLineAlpha: Float = 0.4f,
    /**
     * Optional separate time source for *rendering*, defaulting to [currentPosition].
     *
     * Line selection uses [currentPosition] (the true playback time), while the syllable fill is
     * drawn from this. Feeding a smoothed value here removes the visible stepping from a 4Hz
     * playback callback without ever making line changes lag. Also ported from the fork.
     */
    renderCurrentPosition: (() -> Int)? = null,
    /**
     * Optional hoisted syllable-layout cache, keyed by line index.
     *
     * Every line's syllables have to be measured with [androidx.compose.ui.text.TextMeasurer]
     * before it can be drawn, which is far too slow to do on the frame a line first appears. The
     * results are cached here.
     *
     * Passing one in matters for switching between the artwork and lyrics presentations: this view
     * is disposed while the artwork is showing, so an internally-remembered cache is thrown away
     * and every switch back to the lyrics **re-measures the whole song** — measurably the main
     * reason the switch stutters. A caller that hoists the cache above the presentation switch
     * keeps it warm, so the second and later switches have no measuring to do at all.
     */
    layoutCache: MutableMap<Int, List<SyllableLayout>>? = null,
    showDebugRectangles: Boolean = false
) {
    val density = LocalDensity.current
    val stableNormalTextStyle = remember(normalLineTextStyle) { normalLineTextStyle }
    val stableAccompanimentTextStyle =
        remember(accompanimentLineTextStyle) { accompanimentLineTextStyle }
    val stablePhoneticTextStyle = remember(phoneticTextStyle) { phoneticTextStyle }
    val stableOffset = remember(offset) { offset }
    val stableOffsetPx =
        remember(stableOffset) { with(density) { stableOffset.toPx().fastRoundToInt() } }
    // Only used for the auto-scroll maths, where integer pixels are sufficient.
    val keepAliveZonePx = with(density) { keepAliveZone.toPx() }.fastRoundToInt()
    val stableBlendMode = remember(blendMode) { blendMode }

    val textMeasurer = rememberTextMeasurer()
    val internalLayoutCache = remember { mutableStateMapOf<Int, List<SyllableLayout>>() }
    val cache = layoutCache ?: internalLayoutCache

    LaunchedEffect(
        lyrics,
        stableNormalTextStyle,
        stableAccompanimentTextStyle,
        stablePhoneticTextStyle
    ) {
        // Already measured (e.g. the caller pre-warmed it, or this is a switch back to the lyrics
        // for a song whose cache was kept warm): there is nothing to do — and crucially nothing to
        // do *during* the presentation animation.
        if (cache.size >= lyrics.lines.size) return@LaunchedEffect

        cache.clear()
        measureAllLinesIntoCache(
            lyrics = lyrics,
            textMeasurer = textMeasurer,
            normalLineTextStyle = stableNormalTextStyle,
            accompanimentLineTextStyle = stableAccompanimentTextStyle,
            phoneticTextStyle = stablePhoneticTextStyle,
            cache = cache,
        )
    }

    val currentPositionState = rememberUpdatedState(currentPosition)
    /**
     * Time used for *line selection*, and for anything that drives the scroll.
     *
     * Deliberately the raw playback time: a smoothed value here would delay the moment a line
     * becomes current, which is more noticeable than the stepping it would remove.
     */
    val timeProvider: () -> Int = { currentPositionState.value() }

    val renderCurrentPositionState = rememberUpdatedState(renderCurrentPosition)
    /**
     * Time used for *drawing* the syllable fill, which can be smoothed.
     *
     * Falls back to the raw time when the caller supplies no renderer, so behaviour is unchanged
     * for callers that do not opt in.
     */
    val renderTimeProvider: () -> Int = {
        renderCurrentPositionState.value?.invoke() ?: currentPositionState.value()
    }

    val currentTimeMs: () -> Int = currentPosition

    val accompanimentToMainMap = remember(lyrics.lines) {
        val map = mutableMapOf<Int, Int>()
        val mainLinesIndices = lyrics.lines.indices.filter { index ->
            val line = lyrics.lines[index]
            line !is KaraokeLine || line !is KaraokeLine.AccompanimentKaraokeLine
        }
        if (mainLinesIndices.isNotEmpty()) {
            lyrics.lines.forEachIndexed { index, line ->
                if (line is KaraokeLine && line is KaraokeLine.AccompanimentKaraokeLine) {
                    // Find the main line that is closest in time (either the one just before or just after)
                    val beforeIdx = mainLinesIndices.findLast { it <= index }
                    val afterIdx = mainLinesIndices.find { it >= index }

                    val anchorIndex = when {
                        beforeIdx != null && afterIdx != null -> {
                            val distBefore =
                                (line.start - lyrics.lines[beforeIdx].start).absoluteValue
                            val distAfter =
                                (lyrics.lines[afterIdx].start - line.start).absoluteValue
                            if (distBefore <= distAfter) beforeIdx else afterIdx
                        }

                        beforeIdx != null -> beforeIdx
                        afterIdx != null -> afterIdx
                        else -> mainLinesIndices.first()
                    }
                    map[index] = anchorIndex
                }
            }
        }
        map
    }
    val effectiveEndTimes = remember(lyrics.lines) {
        IntArray(lyrics.lines.size) { index ->
            val line = lyrics.lines[index]
            var maxEnd = line.end

            if (line is KaraokeLine.MainKaraokeLine) {
                line.accompanimentLines?.forEach { acc ->
                    if (acc.end > maxEnd) maxEnd = acc.end
                }
            }
            maxEnd
        }
    }

    val firstLine = lyrics.lines.firstOrNull()

    // The lead-in indicator is shown for any song that has one (as in Lyricify).
    val haveDotsIntro by remember(firstLine) {
        derivedStateOf { (firstLine?.start ?: 0) >= MIN_INTRO_FOR_DOTS_MS }
    }

    // Plain arrays for the scroll math, rebuilt only when the lyrics change.
    val startsArray = remember(lyrics.lines) { IntArray(lyrics.lines.size) { lyrics.lines[it].start } }
    val effectiveEndsArray = remember(effectiveEndTimes) { effectiveEndTimes.copyOf() }

    val lyricsFocusState by remember(lyrics, effectiveEndTimes, accompanimentToMainMap, haveDotsIntro) {
        derivedStateOf {
            val time = currentTimeMs()
            val firstStart = firstLine?.start ?: 0
            val inIntro = haveDotsIntro && time < firstStart

            // During an instrumental gap nothing is "active". Previously the focus moved
            // to the *upcoming* line immediately, which scrolled the line just sung up and
            // out of the reading position; and because the active set was empty, the blur
            // weighting treated every line as far from the focus and blurred the whole
            // list. [LyricsScrollMath.anchorIndex] holds on the line most recently started
            // instead, so the position stays put (and stays sharp) until the next line
            // actually begins.
            val anchoredIndex: Int = LyricsScrollMath.anchorIndex(
                timeMs = time,
                starts = startsArray,
                ends = effectiveEndsArray,
            )

            val base = lyrics.lines.indices.filter { index ->
                time >= lyrics.lines[index].start && time < effectiveEndTimes[index]
            }
            // While a line is singing, its accompaniments are focused with it. During a gap
            // `base` is empty, so fall back to the anchored line. During the lead-in
            // nothing is focused: the dots represent the current position, and the first
            // line stays blurred until it begins.
            val focused = when {
                inIntro -> emptyList()
                else -> base.ifEmpty { listOf(anchoredIndex) }
            }

            val result = focused.toMutableSet()
            focused.forEach { index ->
                val line = lyrics.lines.getOrNull(index)
                if (line is KaraokeLine && line is KaraokeLine.AccompanimentKaraokeLine) {
                    accompanimentToMainMap[index]?.let { result.add(it) }
                }
            }

            val activeInterludeIndex = lyrics.lines.indices.find { index ->
                val line = lyrics.lines[index]
                val previousLine = lyrics.lines.getOrNull(index - 1)
                previousLine != null && (line.start - previousLine.end > 5000) && time in previousLine.end..line.start
            }

            FocusState(
                firstIndex = if (inIntro) 0 else anchoredIndex,
                allIndices = result.toList().sorted(),
                activeInterludeIndex = activeInterludeIndex,
                activeIntro = inIntro,
                introActive = inIntro,
            )
        }
    }

    // The focus state and lyrics must be read through `rememberUpdatedState`, not captured
    // directly. The effect below is keyed on the list/offset only, so a plain closure would
    // keep observing the **previous** song's focus state after a track change — nothing
    // would ever move again, which is exactly the "switching to the next song breaks it"
    // report.
    val currentFocus by rememberUpdatedState(lyricsFocusState)
    val currentLyrics by rememberUpdatedState(lyrics)

    // Whether a finger is currently dragging the list.
    //
    // Needed because `isScrollInProgress` is also true while *this* view's own scroll animation runs,
    // and the auto-scroll loop has to tell a deliberate drag from its own movement. A separate
    // pointer-driven flag is the only way to know, and it is what replaced the old
    // `isAutoScrolling`/`lastUserInteracting` juggling.
    val isDragging = remember { mutableStateOf(false) }

    // A new song starts from the top of its own lyrics.
    //
    // The scroll signal is the *lyrics document*, not the ambient `currentFocus`. Keying it on the
    // focus state meant this fired whenever the focus changed, and since `scrollToItem(0)` is
    // non-animated it would snap the list back to the top mid-song — fighting the auto-scroll for
    // the rest of that line. Reset only when the song actually changes.
    LaunchedEffect(currentLyrics) {
        runCatching { listState.scrollToItem(0) }
    }

    /**
     * Auto-scroll.
     *
     * The focused line is placed with the plain `animateScrollToItem(index)`, and its vertical
     * position comes from the list's **symmetric content padding** rather than from a measured
     * offset. That combination is what NeriPlayer uses, and it removes the whole class of
     * problems the previous hand-rolled version had: there is no offset convention to get wrong,
     * no dependence on `LazyListLayoutInfo` accounting, and no per-frame chase to drift.
     *
     * A user drag is honoured until playback moves on, mirroring NeriPlayer's behaviour: the
     * viewport is held on the line the user scrolled to, then released on the next line change.
     *
     * Three details here are load-bearing and each was a real defect:
     *
     *  - **A scroll is judged by *where the list is*, not by a flag this loop owns.** The previous
     *    version set `isAutoScrolling = true` around its own `animateScrollToItem` and treated any
     *    other scroll as a user drag. But the list also scrolls for reasons this loop knows nothing
     *    about — fling settling, and the item animations inside each row — and any of those would
     *    latch `holdIndex`, which suppresses following until the line index happens to change. The
     *    lyrics would then sit on the wrong line for the rest of that line's duration. Comparing the
     *    list's current index against the line we last *asked* for is stable and needs no flag.
     *  - **The first placement is immediate.** Starting a song with `animateScrollToItem` from index
     *    0 animates the whole list past every line from the top, which is the visible "lyrics rush
     *    up from the bottom" at track start. The first jump belongs at the target with no animation.
     *  - **A drag is detected by touch, not by `isScrollInProgress`.** That flag is also true during
     *    our own animation, and the old flag juggling to tell the two apart is exactly what made the
     *    hold logic misfire.
     */
    LaunchedEffect(listState) {
        val decider = LyricScrollDecider()
        var lastPositionMs = currentPosition()

        while (true) {
            withFrameNanos { }

            // A seek arrives as a large jump in the position. Playback never moves the clock by more
            // than a frame or so, so anything this large is the user having scrubbed — and the list
            // must follow it *now*, as a jump, rather than being left where it was or treating the far
            // target as another animated line change. Resetting the decider makes the placement that
            // follows a first placement, which is the non-animated one.
            val positionMs = currentPosition()
            if (kotlin.math.abs(positionMs - lastPositionMs) > SEEK_DISCONTINUITY_MS) {
                decider.reset()
            }
            lastPositionMs = positionMs

            val focus = currentFocus
            // Nothing to follow during the lead-in: the dots represent the position and the first
            // line already sits at the reading position.
            val target = if (focus.introActive) null else focus.firstIndex
            if (target != null && (target < 0 || target >= lyrics.lines.size)) continue

            when (val decision = decider.decide(
                index = target,
                dragging = isDragging.value,
                firstVisibleIndex = listState.firstVisibleItemIndex,
                scrolling = listState.isScrollInProgress,
            )) {
                is LyricScrollDecider.Decision.Wait -> continue
                is LyricScrollDecider.Decision.Scroll -> {
                    try {
                        if (decision.animate) {
                            listState.animateScrollToItem(decision.index)
                        } else {
                            listState.scrollToItem(decision.index)
                        }
                    } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                        // `LazyListState` scrolls are serialised through a mutex, so a *preempting*
                        // scroll — the user starting a drag, or a seek arriving — cancels this call and
                        // it throws. That is routine, and the loop must survive it: rethrowing
                        // unconditionally killed the whole `LaunchedEffect` on the first preemption,
                        // after which the lyrics never auto-scrolled again for the rest of the song.
                        // `ensureActive()` rethrows only when the effect itself is being cancelled.
                        ensureActive()
                    } catch (_: Exception) {
                        // Any other failure is retried next frame: the decider only treats a request as
                        // settled once the list has actually arrived (or a scroll is still in flight).
                    }
                }
            }
        }
    }
    LookaheadScope {
        // Keyed per song so each track gets a fresh list subtree, and so exactly one list
        // exists at a time. This was a `Crossfade(lyrics)`, which during its transition
        // rendered *two* LazyColumns bound to the same LazyListState — two owners of one
        // scroll position, which is a further source of erratic scrolling.
        key(lyrics) {
        Box(modifier = modifier.clipToBounds()) {
            LazyColumn(
                state = listState,
                modifier = modifier
                    .fillMaxSize()
                    // Report drags so the auto-scroll can yield to them. `pointerInput` observes
                    // touches without consuming them, so tapping and long-pressing a line — which are
                    // handled per row — keep working unchanged.
                    //
                    // A drag is only reported once the finger has actually moved past the touch slop.
                    // Treating every touch-down as a drag would make a tap on a line pause the
                    // auto-scroll and hold the viewport, which is the hold-latch behaviour this
                    // rewrite exists to remove.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var dragging = false
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.all { !it.pressed }) break
                                    if (!dragging && event.changes.any {
                                            (it.position - down.position).getDistance() >
                                                viewConfiguration.touchSlop
                                        }
                                    ) {
                                        dragging = true
                                        isDragging.value = true
                                    }
                                }
                            } finally {
                                isDragging.value = false
                            }
                        }
                    }
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithCache {
                        onDrawWithContent {
                            drawContent()
                            // The fade zones must match what actually sits over the lyrics.
                            //
                            //  - The top zone spans the header, so lines are hidden behind it.
                            //  - The bottom zone is passed as the height of whatever covers the
                            //    list from below (the control panel), and the gradient reaches
                            //    **fully transparent at that edge**. Previously it only reached
                            //    zero well past it, so lyric text still showed through the panel
                            //    — which is why the panel looked unblurred rather than frosted.
                            val topFade = (topFadeZone.toPx() / size.height).coerceIn(0f, 0.5f)
                            val bottomFade = (bottomFadeZone.toPx() / size.height).coerceIn(0f, 0.6f)
                            val ramp = 0.07f
                            drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color.Transparent,
                                            topFade * 0.75f to Color.Transparent,
                                            topFade to Color.Black,
                                            // Visible right up to the covering panel, then
                                            // cleared over a short ramp so it does not snap.
                                            (1f - bottomFade - ramp).coerceAtLeast(topFade) to Color.Black,
                                            (1f - bottomFade).coerceAtLeast(topFade) to Color.Transparent,
                                            1f to Color.Transparent
                                        )
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        },
                    // Symmetric content padding centres the list's scroll anchor, so the
                    // focused line lands at a fixed reading position with no offset maths.
                    contentPadding = PaddingValues(vertical = centerPadding)
                ) {
                    itemsIndexed(
                        items = lyrics.lines,
                        key = { index, line -> "${line.start}-${line.end}-$index" }
                    ) { index, line ->
                        val isCurrentFocusLine = index in lyricsFocusState.allIndices
                        val isLineRtl =
                            when (line) {
                                is KaraokeLine -> {
                                    remember(line.syllables) { line.syllables.any { it.content.isRtl() } }
                                }

                                else -> false
                            }
                        val isLineRightAligned = when (line) {
                            is KaraokeLine -> {
                                remember { line.alignment == KaraokeAlignment.End }
                            }

                            else -> false
                        }
                        val isVisualRightAligned = remember(isLineRightAligned, isLineRtl) {
                            if (isLineRightAligned) !isLineRtl
                            else isLineRtl
                        }

                        val distanceWeightState = remember(useBlurEffect, lyricsFocusState) {
                            derivedStateOf {
                                // During the lead-in nothing is focused. The first line is
                                // nonetheless laid out just under the breathing dots, so it
                                // needs a *clearly* larger blur weight than one step,
                                // otherwise it reads as if the lyrics had already started.
                                if (lyricsFocusState.introActive) {
                                    return@derivedStateOf (index + INTRO_BLUR_STEPS)
                                        .coerceAtMost(LyricsScrollMath.MAX_BLUR_STEPS)
                                }
                                val start = lyricsFocusState.allIndices.firstOrNull() ?: lyricsFocusState.firstIndex
                                val end = lyricsFocusState.allIndices.lastOrNull() ?: lyricsFocusState.firstIndex
                                LyricsScrollMath.blurWeight(index = index, firstFocused = start, lastFocused = end)
                            }
                        }

                        Column(
                            // No per-item spring placement.
                            //
                            // It animated every line's position independently, so any change
                            // to the list's scroll offset made all lines slide and settle
                            // individually — the unexplained "text keeps moving down"
                            // animation at the start of a song, and a further source of
                            // erratic scrolling on line/song changes. The LazyColumn already
                            // scrolls smoothly on its own.
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isVisualRightAligned) Alignment.End else Alignment.Start
                        ) {
                            val animDuration = 600

                            val previousLine = lyrics.lines.getOrNull(index - 1)

                            val showDotsInterlude = lyricsFocusState.activeInterludeIndex == index
                            val showDotsIntro = lyricsFocusState.activeIntro && index == 0

                            AnimatedVisibility(showDotsInterlude || showDotsIntro) {
                                KaraokeBreathingDots(
                                    alignment = when (val line = previousLine ?: firstLine) {
                                        is KaraokeLine -> line.alignment
                                        is SyncedLine -> if (line.content.isRtl()) KaraokeAlignment.End else KaraokeAlignment.Start
                                        else -> KaraokeAlignment.Start
                                    },
                                    startTimeMs = previousLine?.end ?: 0,
                                    endTimeMs = if (showDotsIntro) firstLine!!.start else line.start,
                                    currentTimeProvider = timeProvider,
                                    defaults = breathingDotsDefaults,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }


                            // Distance-based blur, with no dependency on scroll state.
                            //
                            // The original also required `!listState.isScrollInProgress`, which
                            // zeroed the blur for *every* line whenever the list was scrolling
                            // or still settling. Since this view auto-scrolls continuously
                            // during playback, the blur was therefore off much of the time —
                            // which is why non-current lines often looked unblurred.
                            val blurRadiusState = animateFloatAsState(
                                targetValue = if (!useBlurEffect) {
                                    0f
                                } else {
                                    distanceWeightState.value * blurDelta
                                },
                                animationSpec = tween(300),
                            )

                            when (line) {
                                is KaraokeLine -> {
                                    if (line is KaraokeLine.MainKaraokeLine) {
                                        LyricsLineItem(
                                            isFocused = isCurrentFocusLine,
                                            isRightAligned = isVisualRightAligned,
                                            onLineClicked = { onLineClicked(line) },
                                            onLinePressed = { onLinePressed(line) },
                                            blurRadius = { blurRadiusState.value },
                                            focusedScale = focusedLineScale,
                                            unfocusedScale = unfocusedLineScale,
                                            activeAlpha = activeLineAlpha,
                                            inactiveAlpha = inactiveLineAlpha,
                                            blendMode = stableBlendMode,
                                        ) {
                                            KaraokeLineText(
                                                line = line,
                                                // The syllable fill is drawn from the smoothed
                                                // render clock, so a coarse playback callback does
                                                // not show up as stepping in the fill.
                                                currentTimeProvider = renderTimeProvider,
                                                normalLineTextStyle = stableNormalTextStyle,
                                                accompanimentLineTextStyle = stableAccompanimentTextStyle,
                                                phoneticTextStyle = stablePhoneticTextStyle,
                                                activeColor = textColor,
                                                blendMode = stableBlendMode,
                                                showDebugRectangles = showDebugRectangles,
                                                showTranslation = showTranslation,
                                                showPhonetic = showPhonetic,
                                                precalculatedLayouts = cache[index],
                                                // The focused line keeps its unsung words bright, so
                                                // the highlight is the strongest thing on screen at
                                                // every point in the line, not just on the words
                                                // already sung past.
                                                unsungAlpha = if (isCurrentFocusLine) {
                                                    FOCUSED_UNSUNG_ALPHA
                                                } else {
                                                    INACTIVE_UNSUNG_ALPHA
                                                },
                                            )
                                        }
                                    }
                                }

                                is SyncedLine -> {
                                    val isLineRtl = remember(line.content) { line.content.isRtl() }
                                    LyricsLineItem(
                                        isFocused = isCurrentFocusLine,
                                        isRightAligned = isLineRtl,
                                        onLineClicked = { onLineClicked(line) },
                                        onLinePressed = { onLinePressed(line) },
                                        blurRadius = { blurRadiusState.value },
                                        focusedScale = focusedLineScale,
                                        unfocusedScale = unfocusedLineScale,
                                        activeAlpha = activeLineAlpha,
                                        inactiveAlpha = inactiveLineAlpha,
                                        blendMode = stableBlendMode,
                                    ) {
                                        SyncedLineText(
                                            line = line,
                                            isLineRtl = isLineRtl,
                                            textStyle = stableNormalTextStyle.copy(lineHeight = 1.2.em),
                                            textColor = textColor,
                                            showTranslation = showTranslation,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item("BottomSpacing") {
                        Spacer(
                            modifier = Modifier.fillMaxWidth().height(2000.dp)
                        )
                    }
                }
        }
        }
    }
}
