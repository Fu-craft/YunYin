package com.yunyin.music.lyrics.composable.lyrics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.yunyin.music.lyrics.utils.LayerPaint
import com.yunyin.music.lyrics.utils.easing.Swell
import com.yunyin.music.lyrics.utils.isCjk
import com.yunyin.music.lyrics.utils.isPunctuation
import com.yunyin.music.lyrics.utils.isRtl
import com.yunyin.music.ui.theme.Motion
import kotlin.math.roundToInt

/**
 * Lyricify's documented float/glow defaults, translated into a draw-time-only effect.
 *
 * Values: 2 dp maximum lift; 180 ms total (90 ms rise, 90 ms settle); glow max alpha 0.28 at
 * half-fill and zero at the syllable's end; 6 dp glow radius. The closed app documents float on,
 * height 2.0, High level, Android progress width ~100, animation speed 1.0; it does not publish an
 * easing curve or glow radius, so those are our choices (ease-out rise -> ease-in-out settle).
 */
internal object LyricKaraokeMotion {
    const val MaxFloatDp = 2f
    const val FloatDurationMs = 180
    const val FloatRiseMs = 90
    const val MaxGlowAlpha = 0.28f
    const val GlowRadiusDp = 6f

    fun floatFraction(elapsedMs: Int, durationMs: Int = FloatDurationMs): Float {
        // A short syllable shortens the whole pulse rather than snapping back at its end.
        val duration = durationMs.coerceIn(1, FloatDurationMs)
        if (elapsedMs <= 0 || elapsedMs >= duration) return 0f
        val rise = duration / 2f
        return if (elapsedMs <= rise) {
            Motion.EaseOut.transform(elapsedMs / rise)
        } else {
            1f - Motion.EaseInOut.transform((elapsedMs - rise) / (duration - rise))
        }
    }

    /** Zero at both ends of the syllable, low peak in the middle; never glows an inactive word. */
    fun glowAlpha(fillProgress: Float): Float {
        val p = fillProgress.coerceIn(0f, 1f)
        return 4f * p * (1f - p) * MaxGlowAlpha
    }

    fun shouldUseWholeLineHighlight(text: String, cjkKaraokeEnabled: Boolean): Boolean =
        !cjkKaraokeEnabled && text.any { it.isCjk() }

    /** Reduced motion / system animations off means no draw-time float, but the fill remains. */
    fun shouldFloat(animationsEnabled: Boolean): Boolean = animationsEnabled
}

/**
 * Creates a horizontal gradient brush that represents the karaoke progress.
 * The gradient moves from inactive color to active color based on the current time.
 *
 * @param lineLayout The layout information for syllables in the line.
 * @param currentTimeMs The current playback time in milliseconds.
 * @param isRtl Whether the layout direction is Right-to-Left.
 */
private fun createLineGradientBrush(
    rowData: RowRenderData,
    currentTimeMs: Int,
    isRtl: Boolean,
    lineColor: Color,
    unsungAlpha: Float
): Brush {
    // Two levels of the same colour: fully opaque for what has been sung, dimmer for what has not.
    //
    // `unsungAlpha` is supplied per line so the *focused* line can be kept brighter overall than
    // its neighbours. That matters because the line's own alpha is applied on top of this mask:
    // with a flat 0.2 here, the focused line's upcoming words came out dimmer (0.2) than the
    // surrounding lines (0.28), so the "highlight" read as washed out rather than emphasised.
    val activeColor = lineColor.copy(alpha = 1f)
    val inactiveColor = lineColor.copy(alpha = unsungAlpha.coerceIn(0f, 1f))
    val minFadeWidth = 100f

    val lineLayout = rowData.rowLayouts
    val totalMinX = rowData.totalMinX
    val totalMaxX = rowData.totalMaxX
    val totalWidth = rowData.totalWidth

    if (totalWidth <= 0f) {
        val isFinished = currentTimeMs >= lineLayout.last().syllable.end
        val color = if (isFinished) activeColor else inactiveColor
        return SolidColor(color)
    }

    val firstSyllableStart = rowData.firstSyllableStart
    val lastSyllableEnd = rowData.lastSyllableEnd

    val lineProgress = run {
        if (currentTimeMs <= firstSyllableStart) return Brush.horizontalGradient(
            listOf(inactiveColor, inactiveColor)
        )
        if (currentTimeMs >= lastSyllableEnd) return Brush.horizontalGradient(
            listOf(activeColor, activeColor)
        )

        val activeSyllableLayout = lineLayout.find {
            currentTimeMs in it.syllable.start until it.syllable.end
        }

        val currentPixelPosition = when {
            activeSyllableLayout != null -> {
                val syllableProgress = activeSyllableLayout.syllable.progress(currentTimeMs)
                if (isRtl) {
                    activeSyllableLayout.position.x + activeSyllableLayout.width * (1f - syllableProgress)
                } else {
                    activeSyllableLayout.position.x + activeSyllableLayout.width * syllableProgress
                }
            }

            else -> {
                val lastFinished = lineLayout.lastOrNull { currentTimeMs >= it.syllable.end }
                if (isRtl) {
                    lastFinished?.position?.x ?: totalMaxX
                } else {
                    lastFinished?.let { it.position.x + it.width } ?: totalMinX
                }
            }
        }
        ((currentPixelPosition - totalMinX) / totalWidth).coerceIn(0f, 1f)
    }

    val fadeRange = (minFadeWidth / totalWidth).coerceAtMost(1f)
    val fadeCenterStart = -fadeRange / 2f
    val fadeCenterEnd = 1f + fadeRange / 2f
    val fadeCenter = fadeCenterStart + (fadeCenterEnd - fadeCenterStart) * lineProgress
    val fadeStart = fadeCenter - fadeRange / 2f
    val fadeEnd = fadeCenter + fadeRange / 2f

    val colorStops = if (isRtl) {
        arrayOf(
            0.0f to inactiveColor,
            fadeStart.coerceIn(0f, 1f) to inactiveColor,
            fadeEnd.coerceIn(0f, 1f) to activeColor,
            1.0f to activeColor
        )
    } else {
        arrayOf(
            0.0f to activeColor,
            fadeStart.coerceIn(0f, 1f) to activeColor,
            fadeEnd.coerceIn(0f, 1f) to inactiveColor,
            1.0f to inactiveColor
        )
    }

    return Brush.horizontalGradient(
        colorStops = colorStops,
        startX = totalMinX,
        endX = totalMaxX
    )
}

/**
 * Draws a multi-row lyrics line into the canvas.
 * Handles row wrapping, padding, and applying the karaoke progress gradient.
 *
 * @param lineLayouts The pre-calculated layout of syllables, organized by rows.
 * @param currentTimeMs The current playback time in milliseconds.
 * @param color The base text color.
 * @param blendMode The blend mode to use for drawing.
 * @param isRtl Whether the layout direction is Right-to-Left.
 * @param showDebugRectangles Whether to draw debug outlines around glyphs.
 */
fun DrawScope.drawLyricsLine(
    rowRenderData: List<RowRenderData>,
    currentTimeMs: Int,
    color: Color,
    blendMode: BlendMode,
    isRtl: Boolean,
    showDebugRectangles: Boolean = false,
    showPhonetic: Boolean = true,
    /** Opacity of the not-yet-sung part of a line. See [createLineGradientBrush]. */
    unsungAlpha: Float = 0.2f,
    /** CJK karaoke disabled: one line-level highlight instead of a syllable wipe. */
    wholeLineHighlight: Boolean = false,
    /** False when system animations are off; the text is drawn at its layout position. */
    floatEnabled: Boolean = true
) {
    rowRenderData.forEach { rowData ->
        val rowLayouts = rowData.rowLayouts
        val lastSyllableEnd = rowData.lastSyllableEnd

        if (wholeLineHighlight) {
            val rowColor = if (currentTimeMs >= rowData.firstSyllableStart) {
                color
            } else {
                color.copy(alpha = unsungAlpha)
            }
            drawRowText(
                rowLayouts,
                rowColor,
                blendMode,
                showDebugRectangles,
                currentTimeMs,
                showPhonetic,
                floatEnabled,
            )
            return@forEach
        }

        if (currentTimeMs >= lastSyllableEnd) {
            drawRowText(
                rowLayouts,
                color,
                blendMode,
                showDebugRectangles,
                currentTimeMs,
                showPhonetic,
                floatEnabled,
            )
            return@forEach
        }

        drawIntoCanvas { canvas ->
            val layerBounds = rowData.layerBounds
            canvas.saveLayer(layerBounds, LayerPaint)

            drawRowText(
                rowLayouts,
                color,
                blendMode,
                showDebugRectangles,
                currentTimeMs,
                showPhonetic,
                floatEnabled,
            )

            val progressBrush = createLineGradientBrush(rowData, currentTimeMs, isRtl, color, unsungAlpha)
            drawRect(
                brush = progressBrush,
                topLeft = layerBounds.topLeft,
                size = layerBounds.size,
                blendMode = BlendMode.DstIn
            )
            canvas.restore()
        }
    }
}

/**
 * Draws text for a single row, handling word and character animations.
 *
 * @param rowLayouts The layouts for syllables in this row.
 * @param drawColor The color to draw the text with.
 * @param blendMode The blend mode to use.
 * @param showDebugRectangles Whether to show debug bounds.
 * @param currentTimeMs Current playback time.
 * @param showPhonetic Whether to show syllable-level phonetics.
 */
private fun DrawScope.drawRowText(
    rowLayouts: List<SyllableLayout>,
    drawColor: Color,
    blendMode: BlendMode,
    showDebugRectangles: Boolean,
    currentTimeMs: Int,
    showPhonetic: Boolean = true,
    floatEnabled: Boolean = true
) {
    rowLayouts.forEachIndexed { index, syllableLayout ->
        val wordAnimInfo = syllableLayout.wordAnimInfo
        val phoneticDrawColor = drawColor.copy(alpha = 0.4f)
        val isActiveSyllable = currentTimeMs in syllableLayout.syllable.start until syllableLayout.syllable.end
        val floatFraction = if (floatEnabled && isActiveSyllable) {
            LyricKaraokeMotion.floatFraction(
                currentTimeMs - syllableLayout.syllable.start,
                syllableLayout.syllable.end - syllableLayout.syllable.start,
            )
        } else {
            0f
        }
        val floatOffsetY = if (LyricKaraokeMotion.shouldFloat(floatEnabled) && isActiveSyllable) {
            LyricKaraokeMotion.MaxFloatDp.dp.toPx() * floatFraction
        } else {
            0f
        }
        val glowAlpha = if (isActiveSyllable) {
            LyricKaraokeMotion.glowAlpha(syllableLayout.syllable.progress(currentTimeMs))
        } else {
            0f
        }
        val glowShadow = if (glowAlpha > 0f) {
            Shadow(
                color = drawColor.copy(alpha = glowAlpha),
                offset = Offset.Zero,
                blurRadius = LyricKaraokeMotion.GlowRadiusDp.dp.toPx(),
            )
        } else {
            null
        }

        if (wordAnimInfo != null) {
            val fastCharAnimationThresholdMs = 200f
            val awesomeDuration = wordAnimInfo.wordDuration * 0.8f

            val charLayouts = syllableLayout.charLayouts ?: emptyList()
            val charBounds = syllableLayout.charOriginalBounds ?: emptyList()

            val numCharsInWord = wordAnimInfo.wordContent.length
            val earliestStartTime = wordAnimInfo.wordStartTime
            val latestStartTime = wordAnimInfo.wordEndTime - awesomeDuration
            val animationIntensityBase =
                ((wordAnimInfo.wordDuration - fastCharAnimationThresholdMs * numCharsInWord) / 1000)
            val swell = Swell((0.1 * animationIntensityBase).coerceIn(0.0, 0.1))

            syllableLayout.syllable.content.forEachIndexed { charIndex, _ ->
                val singleCharLayoutResult =
                    charLayouts.getOrNull(charIndex) ?: return@forEachIndexed
                val charBox = charBounds.getOrNull(charIndex) ?: return@forEachIndexed

                val absoluteCharIndex = syllableLayout.charOffsetInWord + charIndex
                val charRatio =
                    if (numCharsInWord > 1) absoluteCharIndex.toFloat() / (numCharsInWord - 1) else 0.5f
                val awesomeStartTime =
                    (earliestStartTime + (latestStartTime - earliestStartTime) * charRatio).toLong()
                val awesomeProgress =
                    ((currentTimeMs - awesomeStartTime).toFloat() / awesomeDuration).coerceIn(
                        0f, 1f
                    )

                val scale = 1f + swell.transform(awesomeProgress)

                val centeredOffsetX = (charBox.width - singleCharLayoutResult.size.width) / 2f
                val xPos = syllableLayout.position.x + charBox.left + centeredOffsetX
                val yPos = syllableLayout.position.y + charBox.top + floatOffsetY

                withTransform({ scale(scale = scale, pivot = syllableLayout.wordPivot) }) {
                    drawText(
                        textLayoutResult = singleCharLayoutResult,
                        color = drawColor,
                        topLeft = Offset(xPos, yPos),
                        shadow = glowShadow,
                    )

                    if (showDebugRectangles) {
                        drawRect(
                            color = Color.Red, topLeft = Offset(xPos, yPos), size = Size(
                                singleCharLayoutResult.size.width.toFloat(),
                                singleCharLayoutResult.size.height.toFloat()
                            ), style = Stroke(1f)
                        )
                    }
                }
            }

            if (showPhonetic) {
                syllableLayout.phoneticLayoutResult?.let { phoneticLayout ->
                    val syllableMidIndex =
                        syllableLayout.charOffsetInWord + (syllableLayout.syllable.content.length - 1) / 2f
                    val syllableRatio =
                        if (numCharsInWord > 1) syllableMidIndex / (numCharsInWord - 1) else 0.5f
                    val awesomeStartTime =
                        (earliestStartTime + (latestStartTime - earliestStartTime) * syllableRatio).toLong()
                    val awesomeProgress =
                        ((currentTimeMs - awesomeStartTime).toFloat() / awesomeDuration).coerceIn(
                            0f, 1f
                        )

                    val scale = 1f + swell.transform(awesomeProgress)

                    val phoneticX = syllableLayout.position.x
                    val phoneticY =
                        syllableLayout.position.y - phoneticLayout.size.height + 4.dp.toPx() + floatOffsetY

                    withTransform({ scale(scale = scale, pivot = syllableLayout.wordPivot) }) {
                        drawText(
                            textLayoutResult = phoneticLayout,
                            color = phoneticDrawColor,
                            topLeft = Offset(phoneticX, phoneticY)
                        )
                    }
                }
            }
        } else {
            val finalPosition = syllableLayout.position.copy(
                y = syllableLayout.position.y + floatOffsetY
            )

            drawText(
                textLayoutResult = syllableLayout.textLayoutResult,
                color = drawColor,
                topLeft = finalPosition,
                shadow = glowShadow,
            )

            if (showPhonetic) {
                syllableLayout.phoneticLayoutResult?.let { phoneticLayout ->
                    val phoneticX = syllableLayout.position.x
                    val phoneticY = finalPosition.y - phoneticLayout.size.height + 4.dp.toPx()
                    drawText(
                        textLayoutResult = phoneticLayout,
                        color = phoneticDrawColor,
                        topLeft = Offset(phoneticX, phoneticY),
                    )
                }
            }

            if (showDebugRectangles) {
                drawRect(
                    color = Color.Green, topLeft = finalPosition, size = Size(
                        syllableLayout.textLayoutResult.size.width.toFloat(),
                        syllableLayout.textLayoutResult.size.height.toFloat()
                    ), style = Stroke(2f)
                )
            }
        }
        }
    }

/**
 * Renders a single karaoke line, capable of handling multi-row wrapping.
 *
 * This composable pre-calculates the text layout and then
 * renders the frames using an efficient Canvas drawing strategy. It handles:
 * - Text measurement and line breaking
 * - Syllable-level float/glow plus the existing character swell
 * - Karaoke fill gradient application
 *
 * @param line The karaoke line data.
 * @param currentTimeProvider Provider for the current playback time.
 * @param modifier Modifier for the layout.
 * @param normalLineTextStyle Style for normal lines.
 * @param accompanimentLineTextStyle Style for accompaniment lines.
 * @param phoneticTextStyle Style for phonetics.
 * @param activeColor Color for the active (sung) portion of text.
 * @param blendMode Blend mode for drawing.
 * @param showDebugRectangles Debug flag for layout bounds.
 * @param showTranslation Whether to show line-level translations.
 * @param showPhonetic Whether to show phonetics (both syllable and line level).
 * @param precalculatedLayouts Optional pre-calculated layouts (optimization).
 * @param isDuoView Whether this line is part of a duet view.
 * @param textMeasurer Text measurer for layout (default provided).
 */
@Composable
fun KaraokeLineText(
    line: KaraokeLine,
    currentTimeProvider: () -> Int,
    modifier: Modifier = Modifier,
    normalLineTextStyle: TextStyle = LocalTextStyle.current,
    accompanimentLineTextStyle: TextStyle = LocalTextStyle.current,
    phoneticTextStyle: TextStyle = LocalTextStyle.current,
    activeColor: Color = Color.White,
    blendMode: BlendMode = BlendMode.SrcOver,
    showDebugRectangles: Boolean = false,
    showTranslation: Boolean = true,
    showPhonetic: Boolean = true,
    precalculatedLayouts: List<SyllableLayout>? = null,
    isDuoView: Boolean = false,
    /**
     * Opacity of this line's not-yet-sung words.
     *
     * Set higher for the line being sung than for the rest, so the focused line is the brightest
     * thing on screen at every point in the line rather than only on the words already sung.
     */
    unsungAlpha: Float = 0.2f,
    cjkKaraokeEnabled: Boolean = true,
    textMeasurer: TextMeasurer = rememberTextMeasurer()
) {
    val isLineRtl = remember(line.syllables) { line.syllables.any { it.content.isRtl() } }
    val animationsEnabled = remember {
        runCatching { android.animation.ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
    val wholeLineHighlight = remember(line.syllables, cjkKaraokeEnabled) {
        LyricKaraokeMotion.shouldUseWholeLineHighlight(
            line.syllables.joinToString("") { it.content },
            cjkKaraokeEnabled,
        )
    }

    val isRightAligned = remember(line.alignment, isLineRtl) {
        when (line.alignment) {
            KaraokeAlignment.Start, KaraokeAlignment.Unspecified -> isLineRtl
            KaraokeAlignment.End -> !isLineRtl
        }
    }

    val translationTextAlign = remember(isRightAligned) {
        if (isRightAligned) TextAlign.End else TextAlign.Start
    }

    val columnHorizontalAlignment = remember(isRightAligned) {
        if (isRightAligned) Alignment.End else Alignment.Start
    }

    val mainLine = line as? KaraokeLine.MainKaraokeLine
    val accompanimentLinesBeforeMain =
        mainLine?.accompanimentLines?.filter { it.start < line.start }.orEmpty()
    val accompanimentLinesAfterMain =
        mainLine?.accompanimentLines?.filter { it.start >= line.start }.orEmpty()

    @Composable
    fun AccompanimentLines(isBefore: Boolean, accompanimentLines: List<KaraokeLine>) {
        accompanimentLines.forEach { bgLine ->
            val isAccompanimentVisible by remember(bgLine) {
                derivedStateOf {
                    val currentTime = currentTimeProvider()
                    // Use a simplified visibility range for nested lines, or pass ranges down
                    currentTime >= (bgLine.start - 600) && currentTime <= (bgLine.end + 600)
                }
            }

            // Accompaniment (duet / backing-vocal) lines enter and leave while the line is sung, so
            // this is UI motion on a fast, repeated surface: 220ms with the app's ease-out, not the
            // upstream 600ms bare tween (default easing > 300ms is the audit's "sluggish" pattern).
            // `expandVertically`/`shrinkVertically` are kept because they are what stop the lines
            // below from jumping when a line appears; shortening them shortens that layout churn too.
            AnimatedVisibility(
                visible = isAccompanimentVisible,
                enter = scaleIn(
                    tween(Motion.CrossfadeDuration, easing = Motion.EaseOut),
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(
                        if (isRightAligned) 1f else 0f, if (isBefore) 1f else 0f
                    )
                ) + fadeIn(tween(Motion.CrossfadeDuration, easing = Motion.EaseOut)) + slideInVertically(
                    tween(Motion.CrossfadeDuration, easing = Motion.EaseOut)
                ) + expandVertically(
                    tween(Motion.CrossfadeDuration, easing = Motion.EaseOut),
                    if (isBefore) Alignment.Top else Alignment.Bottom,
                ),
                exit = scaleOut(
                    tween(Motion.CrossfadeDuration, easing = Motion.EaseOut),
                    targetScale = 0.92f,
                    transformOrigin = TransformOrigin(
                        if (isRightAligned) 1f else 0f, if (isBefore) 1f else 0f
                    )
                ) + fadeOut(tween(Motion.CrossfadeDuration, easing = Motion.EaseOut)) + slideOutVertically(
                    tween(Motion.CrossfadeDuration, easing = Motion.EaseOut)
                ) + shrinkVertically(
                    tween(Motion.CrossfadeDuration, easing = Motion.EaseOut),
                    if (isBefore) Alignment.Top else Alignment.Bottom,
                ),
            ) {
                LyricsLineItem(
                    isFocused = true,
                    isRightAligned = isRightAligned,
                    onLineClicked = { },
                    onLinePressed = { },
                    blurRadius = { 0f },
                    blendMode = blendMode,
                    activeAlpha = 0.6f,
                    inactiveAlpha = 0.2f
                ) {
                    KaraokeLineText(
                        line = bgLine,
                        currentTimeProvider = currentTimeProvider,
                        normalLineTextStyle = normalLineTextStyle,
                        accompanimentLineTextStyle = accompanimentLineTextStyle,
                        phoneticTextStyle = phoneticTextStyle,
                        activeColor = activeColor,
                        blendMode = blendMode,
                        showDebugRectangles = showDebugRectangles,
                        showTranslation = showTranslation,
                        showPhonetic = showPhonetic,
                        cjkKaraokeEnabled = cjkKaraokeEnabled,
                        textMeasurer = textMeasurer
                    )
                }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(
            vertical = 8.dp, horizontal = if (line is KaraokeLine.AccompanimentKaraokeLine) 0.dp
            else 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = columnHorizontalAlignment
    ) {
        AccompanimentLines(true, accompanimentLinesBeforeMain)

        BoxWithConstraints {
            val density = LocalDensity.current
            val availableWidthPx = with(density) { maxWidth.toPx() }

            // Keyed on the style object itself, NOT on the line kind.
            //
            // This was `remember(line is AccompanimentKaraokeLine)`, i.e. a Boolean. The activity
            // declares `configChanges="orientation|screenSize|..."`, so rotation does *not* recreate
            // it and the list items survive the rotation — the Boolean never changes, so this
            // remembered value kept the pre-rotation style and the type size was frozen at whatever
            // it was on first composition, however the caller retuned it. (That is the "landscape
            // lyrics never change size" report.) Keying on the style makes a retune take effect.
            val baseTextStyle =
                if (line is KaraokeLine.AccompanimentKaraokeLine) accompanimentLineTextStyle
                else normalLineTextStyle
            val textStyle = remember(baseTextStyle) {
                baseTextStyle.copy(textDirection = TextDirection.Content)
            }

            val spaceWidth = remember(textMeasurer, textStyle) {
                textMeasurer.measure(" ", textStyle).size.width.toFloat()
            }

            val processedSyllables = remember(line.syllables, line.alignment) {
                if (line.alignment == KaraokeAlignment.End) {
                    line.syllables.dropLastWhile { it.content.isBlank() }
                } else {
                    line.syllables
                }
            }

            // The fallback measurement is keyed on everything it reads. A `derivedStateOf` here only
            // tracks snapshot state reads, not the plain values its lambda closes over, so a style
            // or width that changed while `precalculatedLayouts` stayed the same kept the old
            // measurement; naming the inputs makes the recomputation explicit instead.
            val initialLayouts: List<SyllableLayout> = remember(
                precalculatedLayouts, processedSyllables, textStyle, phoneticTextStyle, spaceWidth,
            ) {
                precalculatedLayouts ?: measureSyllablesAndDetermineAnimation(
                    syllables = processedSyllables,
                    textMeasurer = textMeasurer,
                    style = textStyle,
                    phoneticStyle = phoneticTextStyle,
                    isAccompanimentLine = line is KaraokeLine.AccompanimentKaraokeLine,
                    spaceWidth = spaceWidth
                )
            }

            // Keyed on the style and the available width, both of which change on rotation. With
            // the width and style captured but not keyed (an earlier version), the wrapping was
            // computed once and never revisited — so after a rotation the lines stayed wrapped for
            // the old width and drawn at the old style's metrics.
            val wrappedLines = remember(initialLayouts, availableWidthPx, textStyle, textMeasurer) {
                calculateBalancedLines(
                    syllableLayouts = initialLayouts,
                    availableWidthPx = availableWidthPx,
                    textMeasurer = textMeasurer,
                    style = textStyle
                )
            }

            val lineHeight = remember(textStyle) {
                textMeasurer.measure("M", textStyle).size.height.toFloat()
            }

            val phoneticHeight = remember(phoneticTextStyle) {
                textMeasurer.measure("M", phoneticTextStyle).size.height.toFloat()
            }

            val finalLineLayouts = remember(
                wrappedLines, availableWidthPx, lineHeight, isLineRtl, isRightAligned, showPhonetic
            ) {
                calculateStaticLineLayout(
                    wrappedLines = wrappedLines,
                    isLineRightAligned = isRightAligned,
                    canvasWidth = availableWidthPx,
                    lineHeight = lineHeight,
                    phoneticHeight = if (showPhonetic) phoneticHeight else 0f,
                    isRtl = isLineRtl
                )
            }

            val rowRenderData = remember(finalLineLayouts, showPhonetic, density) {
                calculateRowRenderData(
                    lineLayouts = finalLineLayouts,
                    showPhonetic = showPhonetic,
                    density = density.density
                )
            }

            val hasPhonetics = remember(initialLayouts, showPhonetic) {
                showPhonetic && initialLayouts.any { it.phoneticLayoutResult != null }
            }

            val totalHeight = remember(wrappedLines, lineHeight, hasPhonetics, phoneticHeight) {
                var height = lineHeight * wrappedLines.size
                if (hasPhonetics) {
                    height += phoneticHeight * wrappedLines.size
                }
                height
            }

            Canvas(modifier = Modifier.size(maxWidth, (totalHeight.roundToInt() + 8).toDp())) {
                val time = currentTimeProvider()
                drawLyricsLine(
                    rowRenderData = rowRenderData,
                    currentTimeMs = time,
                    color = activeColor,
                    blendMode = blendMode,
                    isRtl = isLineRtl,
                    showDebugRectangles = showDebugRectangles,
                    showPhonetic = showPhonetic,
                    unsungAlpha = unsungAlpha
                )
            }
        }

        if (showTranslation) {
            line.translation?.let { translation ->
                Text(
                    text = translation,
                    color = activeColor.copy(0.4f),
                    modifier = Modifier.graphicsLayer {
                        this.blendMode = blendMode
                    },
                    textAlign = translationTextAlign
                )
            }
        }

        if (showPhonetic) {
            line.phonetic?.let { phonetic ->
                Text(
                    text = phonetic,
                    style = phoneticTextStyle,
                    color = activeColor.copy(alpha = 0.6f),
                    modifier = Modifier.graphicsLayer {
                        this.blendMode = blendMode
                    },
                    textAlign = translationTextAlign
                )
            }
        }
        AccompanimentLines(false, accompanimentLinesAfterMain)
    }
}

@Composable
private fun Int.toDp(): Dp = with(LocalDensity.current) { this@toDp.toDp() }
