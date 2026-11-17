// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
//
// 移植自 AuraPlayer 的 ui/player/PlayerLayoutGeometry.kt（AGPL-3.0-or-later），函数体未作修改。
package com.yunyin.music.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compute once for both the cover position and the controls' additional inset. */
internal fun playerCoverInset(width: Dp, coverSize: Dp, outerMargin: Dp): Dp =
    ((width - coverSize) / 2 - outerMargin).coerceAtLeast(0.dp)

/**
 * Compute uniform control column X and width for non-landscape viewports so top rows
 * (title & progress slider) never narrow into a pyramid above wider transport/volume/bottom rows.
 */
internal fun portraitControlColumnRange(width: Dp, outerMargin: Dp): Pair<Dp, Dp> {
    val safeMargin = outerMargin.coerceAtLeast(0.dp)
    val columnWidth = (width - safeMargin * 2).coerceAtLeast(24.dp)
    return safeMargin to columnWidth
}

/** Existing mini-player material recipe; shared with the page blur cap, not a new setting. */
internal fun playerMiniBlurRadius(style: String, blurPercent: Int): Dp =
    (when (style) {
        "liquid_glass" -> 3f
        "harmony_os_floating" -> 18f * .29f
        else -> 18f * .24f
    }).dp * blurPercent / 100f

/** Page content stays sharper than the mini-player glass for the entire reversible morph. */
internal fun playerTransitionBlurRadius(miniBlurRadius: Dp, expansion: Float): Dp =
    miniBlurRadius * .8f * expansion.coerceIn(0f, 1f)

internal data class FullscreenPlayerCard(val left: Dp, val top: Dp, val width: Dp, val height: Dp)

/** Landscape 4:3, bottom-centered, with a readable cover area above and bounded tablet width. */
internal fun fullscreenPlayerCard(width: Dp, height: Dp, status: Dp, navigation: Dp): FullscreenPlayerCard {
    val availableHeight = (height - status - navigation - 64.dp).coerceAtLeast(1.dp)
    val cardWidth = minOf((width - 28.dp).coerceAtLeast(1.dp), 520.dp, availableHeight * (4f / 3f))
    val cardHeight = cardWidth * .75f
    return FullscreenPlayerCard((width - cardWidth) / 2, height - navigation - 14.dp - cardHeight, cardWidth, cardHeight)
}

internal fun isLandscapePlayerLayout(width: Dp, height: Dp): Boolean =
    width >= 480.dp && width > height * 1.08f

internal fun isCompactPlayerWindow(width: Dp, height: Dp, isMultiWindow: Boolean): Boolean =
    isMultiWindow || (!isLandscapePlayerLayout(width, height) && (height < 640.dp || width < 330.dp))

internal data class LandscapePlayerGeometry(
    val leftPaneX: Dp,
    val leftPaneWidth: Dp,
    val rightPaneX: Dp,
    val rightPaneWidth: Dp,
    val usableTop: Dp,
    val usableBottom: Dp,
    val usableHeight: Dp,
    val heroCoverSize: Dp,
    val heroCoverX: Dp,
    val heroCoverY: Dp,
    val lyricsCompactCoverSize: Dp,
    val lyricsCompactCoverX: Dp,
    val lyricsCompactCoverY: Dp,
    val controlStackHeight: Dp,
    val controlStackTop: Dp,
    val controlsScale: Float,
)

internal fun computeLandscapePlayerGeometry(
    width: Dp,
    height: Dp,
    leftInset: Dp,
    topInset: Dp,
    rightInset: Dp,
    bottomInset: Dp,
    showVolume: Boolean,
): LandscapePlayerGeometry {
    val edgeMargin = maxOf(18.dp, width * 0.032f)
    val safeLeft = leftInset + edgeMargin
    val safeRight = width - rightInset - edgeMargin
    val safeWidth = (safeRight - safeLeft).coerceAtLeast(220.dp)
    val paneGap = maxOf(22.dp, safeWidth * 0.045f)
    val leftPaneWidth = ((safeWidth - paneGap) * 0.43f).coerceAtLeast(120.dp)
    val rightPaneX = safeLeft + leftPaneWidth + paneGap
    val rightPaneWidth = (safeRight - rightPaneX).coerceAtLeast(180.dp)
    val usableTop = topInset + 24.dp
    val usableBottom = (height - bottomInset - 8.dp).coerceAtLeast(usableTop + 100.dp)
    val usableHeight = (usableBottom - usableTop).coerceAtLeast(100.dp)

    val heroCoverSize = minOf(leftPaneWidth * 0.88f, usableHeight * 0.84f, 340.dp).coerceAtLeast(84.dp)
    val heroCoverX = safeLeft + (leftPaneWidth - heroCoverSize) / 2
    val heroCoverY = usableTop + (usableHeight - heroCoverSize) / 2

    val baseControlHeight = if (showVolume) 276.dp else 234.dp
    val controlsScale = (usableHeight.value / baseControlHeight.value).coerceIn(0.76f, 1.04f)
    val controlStackHeight = minOf(usableHeight, (baseControlHeight * controlsScale).coerceAtLeast(168.dp))
    val controlStackTop = usableTop + (usableHeight - controlStackHeight) / 2

    // Non-immersed lyrics/queue mode uses a sleek compact cover + full metadata/controls in the left pane.
    val lyricsCompactCoverSize = minOf(usableHeight * 0.36f, leftPaneWidth * 0.40f, 128.dp).coerceAtLeast(64.dp)
    val lyricsCompactCoverX = safeLeft
    val lyricsCompactCoverY = usableTop + 6.dp

    return LandscapePlayerGeometry(
        leftPaneX = safeLeft,
        leftPaneWidth = leftPaneWidth,
        rightPaneX = rightPaneX,
        rightPaneWidth = rightPaneWidth,
        usableTop = usableTop,
        usableBottom = usableBottom,
        usableHeight = usableHeight,
        heroCoverSize = heroCoverSize,
        heroCoverX = heroCoverX,
        heroCoverY = heroCoverY,
        lyricsCompactCoverSize = lyricsCompactCoverSize,
        lyricsCompactCoverX = lyricsCompactCoverX,
        lyricsCompactCoverY = lyricsCompactCoverY,
        controlStackHeight = controlStackHeight,
        controlStackTop = controlStackTop,
        controlsScale = controlsScale,
    )
}

internal data class ImmersiveCoverGeometry(
    val coverX: Dp,
    val coverY: Dp,
    val coverWidth: Dp,
    val coverHeight: Dp,
    val titleY: Dp,
    val seekY: Dp,
)

internal fun computeImmersiveCoverGeometry(
    width: Dp,
    height: Dp,
    statusTop: Dp,
    seekCeiling: Dp,
    titleToSeek: Dp,
    compactWindow: Boolean,
): ImmersiveCoverGeometry {
    val targetSeek = height * (if (compactWindow) 0.575f else 0.625f)
    val minTitleY = statusTop + (if (compactWindow) 44.dp else 64.dp)
    val minSeekY = minTitleY + titleToSeek
    val seekY = if (seekCeiling <= minSeekY) {
        seekCeiling.coerceAtLeast(statusTop + titleToSeek + 12.dp)
    } else {
        targetSeek.coerceIn(minSeekY, seekCeiling)
    }
    val titleY = (seekY - titleToSeek).coerceAtLeast(statusTop + 28.dp)
    val coverHeight = (seekY + 12.dp).coerceIn(height * 0.46f, height * 0.69f)
    return ImmersiveCoverGeometry(
        coverX = 0.dp,
        coverY = 0.dp,
        coverWidth = width.coerceAtLeast(1.dp),
        coverHeight = coverHeight.coerceAtLeast(1.dp),
        titleY = titleY,
        seekY = seekY,
    )
}

internal fun computeLandscapeImmersiveCoverGeometry(
    width: Dp,
    height: Dp,
    rightPaneX: Dp,
    leftPaneWidth: Dp,
): ImmersiveCoverGeometry = ImmersiveCoverGeometry(
    coverX = 0.dp,
    coverY = 0.dp,
    coverWidth = (rightPaneX + 24.dp).coerceIn(
        (leftPaneWidth + 24.dp).coerceAtLeast(1.dp),
        maxOf(width * 0.60f, (leftPaneWidth + 24.dp).coerceAtLeast(1.dp)),
    ),
    coverHeight = height.coerceAtLeast(1.dp),
    titleY = 0.dp,
    seekY = 0.dp,
)

/** 沉浸封面的底角半径：从迷你条的圆角插值到「全屏 0dp」，再按歌词态插值回 lyricsCorner。 */
internal fun immersiveCoverBottomCornerRadius(
    miniCorner: Dp,
    lyricsCorner: Dp,
    expansion: Float,
    lyricsProgress: Float,
): Dp = androidx.compose.ui.unit.lerp(
    miniCorner,
    androidx.compose.ui.unit.lerp(0.dp, lyricsCorner, lyricsProgress.coerceIn(0f, 1f)),
    expansion.coerceIn(0f, 1f),
)

private fun coverSmoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** 动效封面上的遮罩透明度：随展开淡入、随歌词态淡出；混音/空间音频时直接为 0。 */
internal fun computeMotionCoverOverlayAlpha(
    expansion: Float,
    lyricsProgress: Float,
    mixActive: Boolean,
    spatialActive: Boolean,
): Float {
    if (mixActive || spatialActive) return 0f
    val expandFade = coverSmoothstep(0.14f, 0.56f, expansion)
    val lyricsFade = 1f - coverSmoothstep(0.08f, 0.52f, lyricsProgress)
    return (expandFade * lyricsFade).coerceIn(0f, 1f)
}

/** 是否显示「空间音频封面」按钮：开启空间封面且没有正在使用的动效视频时为真。 */
internal fun shouldShowSpatialCoverButton(
    spatialCoverEnabled: Boolean,
    appleMusicAnimatedCoverEnabled: Boolean,
    motionVideoPath: String?,
): Boolean = spatialCoverEnabled && !(appleMusicAnimatedCoverEnabled && !motionVideoPath.isNullOrBlank())

/** 是否走沉浸封面：开关打开（或动效封面可用）且不是全屏主题时为真。 */
internal fun shouldUseImmersiveCover(
    immersiveCoverSetting: Boolean,
    appleMusicAnimatedCoverEnabled: Boolean,
    appleMusicAnimatedCoverAvailable: Boolean,
    fullscreen: Boolean,
    @Suppress("UNUSED_PARAMETER") hasLandscapeGeometry: Boolean = false,
): Boolean =
    (immersiveCoverSetting || (appleMusicAnimatedCoverEnabled && appleMusicAnimatedCoverAvailable)) &&
        !fullscreen
