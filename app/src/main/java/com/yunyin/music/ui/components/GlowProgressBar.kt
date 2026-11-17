// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 发光的进度条：胶囊轨道 + 渐变填充 + 辉光 + 拖拽时的光晕。
 *
 * 视觉照搬 AuraPlayer 的 `PlayerGlowProgress`（HYPER 样式，AGPL-3.0-or-later）。只保留绘制与拖拽，
 * 去掉了他那边依赖音频频谱的 ONE 样式与波形数据——那部分需要 DSP 管线，与我们的播放层不匹配。
 *
 * 与系统 Slider 的区别在于三处叠加：填充本身是横向渐变（暗→亮），填充外沿有一层 BlurMaskFilter
 * 扩散出的辉光，端点处再叠一个压扁的径向光晕。三者叠加才有"发光胶囊"的观感，少一层就变成普通进度条。
 */
@Composable
fun GlowProgressBar(
    value: Float,
    accentColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    isLightSurface: Boolean = false,
    enabled: Boolean = true,
    label: String = "播放进度",
    onChange: (Float) -> Unit = {},
    onFinished: () -> Unit = {},
) {
    val onChangeState by rememberUpdatedState(onChange)
    val onFinishedState by rememberUpdatedState(onFinished)
    val valueState by rememberUpdatedState(value.coerceIn(0f, 1f))
    val haptics = LocalHapticFeedback.current

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    // 拖拽时轨道变粗一点，给手指一个"抓住了"的反馈。
    val thickness by animateDpAsState(
        targetValue = if (dragging) 8.5.dp else 6.5.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "glow-thickness",
    )

    val displayed = if (dragging) dragFraction else valueState

    Canvas(
        modifier = modifier
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(displayed, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        val clamped = target.coerceIn(0f, 1f)
                        onChangeState(clamped)
                        onFinishedState()
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    dragFraction = (down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onChangeState(dragFraction)
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        pointer.consume()
                        dragFraction = (pointer.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onChangeState(dragFraction)
                        pressed = pointer.pressed
                    }
                    dragging = false
                    onFinishedState()
                }
            },
    ) {
        drawGlowProgress(
            fraction = displayed,
            dragging = dragging,
            thicknessPx = thickness.toPx().coerceAtMost(size.height),
            accentColor = accentColor,
            trackColor = trackColor,
            isLightSurface = isLightSurface,
        )
    }
}

private fun DrawScope.drawGlowProgress(
    fraction: Float,
    dragging: Boolean,
    thicknessPx: Float,
    accentColor: Color,
    trackColor: Color,
    isLightSurface: Boolean,
) {
    val cy = size.height / 2f
    val top = cy - thicknessPx / 2f
    val corner = CornerRadius(thicknessPx / 2f, thicknessPx / 2f)

    drawRoundRect(
        color = trackColor,
        topLeft = Offset(0f, top),
        size = Size(size.width, thicknessPx),
        cornerRadius = corner,
    )

    val rawFill = (size.width * fraction).coerceIn(0f, size.width)
    if (rawFill <= 0.5f) return

    val fillWidth = max(rawFill, thicknessPx).coerceAtMost(size.width)
    val headX = (rawFill - thicknessPx * 0.25f).coerceIn(thicknessPx * 0.5f, size.width)

    // 辉光不能超出轨道上下边界，否则会被裁掉一半，看起来像"缺角"。
    val expandY = (if (dragging) 1.55.dp else 1.1.dp).toPx()
    val maxBlurExtent = (cy - thicknessPx / 2f - expandY - 0.5.dp.toPx()).coerceAtLeast(1f)
    val blurPx = (if (dragging) 7.4.dp else 5.9.dp).toPx().coerceAtMost(maxBlurExtent * 0.72f)

    val auraStart = if (isLightSurface) {
        accentColor.copy(alpha = 0.28f)
    } else {
        lerp(Color.White, accentColor, 0.35f).copy(alpha = 0.22f)
    }
    val auraEnd = if (isLightSurface) accentColor.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.72f)

    drawIntoCanvas { canvas ->
        val glowPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(
                0f, cy, fillWidth.coerceAtLeast(1f), cy,
                intArrayOf(auraStart.toArgb(), auraEnd.toArgb()),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.nativeCanvas.drawRoundRect(
            0f,
            top - expandY,
            fillWidth,
            top + thicknessPx + expandY,
            (thicknessPx + expandY * 2f) / 2f,
            (thicknessPx + expandY * 2f) / 2f,
            glowPaint,
        )
    }

    val haloRadius = (if (dragging) 22.dp else 18.5.dp).toPx()
    val maxVerticalScale = ((cy - 0.5.dp.toPx()).coerceAtLeast(1f) / haloRadius).coerceIn(0.2f, 1f)
    val haloScaleY = minOf(0.616f, maxVerticalScale)
    val haloColor = if (isLightSurface) accentColor else Color.White
    withTransform({
        translate(headX, cy)
        scale(1.45f, haloScaleY, pivot = Offset.Zero)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    haloColor.copy(alpha = if (dragging) 0.72f else 0.58f),
                    lerp(haloColor, accentColor, 0.45f).copy(alpha = 0.20f),
                    Color.Transparent,
                ),
                center = Offset.Zero,
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = Offset.Zero,
        )
    }

    val coreStart = if (isLightSurface) {
        lerp(accentColor, Color.White, 0.16f)
    } else {
        lerp(Color.White, accentColor, 0.18f).copy(alpha = 0.86f)
    }
    val coreEnd = if (isLightSurface) lerp(accentColor, Color.White, 0.62f) else Color.White
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(coreStart, coreEnd),
            startX = 0f,
            endX = fillWidth.coerceAtLeast(1f),
        ),
        topLeft = Offset(0f, top),
        size = Size(fillWidth, thicknessPx),
        cornerRadius = corner,
    )
}

/**
 * 播放页用的发光进度条：内部按帧读取插值后的位置，再交给 [GlowProgressBar] 绘制。
 *
 * 之所以在内部 tick 而不是由调用方传 value：播放位置来自 `positionProvider`（可安全每帧轮询），
 * 上升到调用方会让整个 `PlayerControls` 每帧重组；在这里 tick，只有 Canvas 那一层失效。
 */
/**
 * 播放页用的发光进度条：内部按帧读取插值后的位置，再交给 [GlowProgressBar]。
 *
 * 位置在这里 tick 而不是由调用方传 value：播放位置来自可每帧轮询的 `positionProvider`，
 * 若在调用方读取，整个 `PlayerControls` 会每帧重组；在这里 tick，只有 Canvas 那一层失效。
 */
@Composable
fun GlowPlayerProgress(
    positionProvider: () -> Long,
    durationMs: Long,
    accentColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    isLightSurface: Boolean = false,
) {
    val current by rememberUpdatedState(positionProvider)
    var positionMs by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { positionMs = current() }
        }
    }

    val fraction = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    GlowProgressBar(
        value = fraction,
        accentColor = accentColor,
        trackColor = trackColor,
        modifier = modifier,
        isLightSurface = isLightSurface,
        enabled = durationMs > 0L,
        onChange = { seekTarget = it },
        onFinished = { onSeek(seekTarget.takeIf { it > 0f } ?: fraction) },
    )
}