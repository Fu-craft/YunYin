// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunyin.music.ui.theme.Motion

/**
 * Thin, draggable playback progress bar.
 *
 * Follows the iOS media-scrubber idiom: a 4pt-ish track that grows thicker while being dragged,
 * continuous (squircle-ish) caps, and seek-on-release so scrubbing does not spam the player with
 * seeks.
 *
 * ## Why the fill is drawn, not laid out
 *
 * The player publishes its position four times a second, so a `fillMaxWidth(progress)` bar moves in
 * four visible steps per second. A frame ticker samples [positionProvider] (the player's
 * interpolated clock) instead, and both the value and the drag fraction are read *inside* the
 * draw lambda — a frame invalidates the draw phase only, never composition or layout. This is the
 * same arrangement [LyricsView] uses for the karaoke fill, and the same reason the backdrop reads
 * its frame counter in its draw lambda.
 *
 * The drag fraction still wins over the clock while the finger is down: the knob follows the finger
 * rather than a lagging reported position.
 */
@Composable
fun PlayerProgressBar(
    /** Interpolated playback position in ms; safe to poll once per frame. */
    positionProvider: () -> Long,
    durationMs: Long,
    /** Fraction 0..1. Called once on release. */
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 5.dp,
    activeTrackHeight: Dp = 9.dp,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.25f),
) {
    var width by remember { mutableStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val currentPosition by rememberUpdatedState(positionProvider)

    // The frame-ticked position. Read only inside the Canvas draw lambda.
    var tickedPositionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { tickedPositionMs = currentPosition() }
        }
    }

    // 0 = resting thickness, 1 = scrub thickness. Animating this and lerping in the draw lambda
    // keeps the growth off the layout: only the drawn height changes, never the measured one.
    val thick by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        // Motion.PressDuration / Motion.EaseOut are the exact values this used to hand-type; the
        // shared tokens keep every press response on the audit's press-feedback rule (160ms,
        // cubic-bezier(0.23, 1, 0.32, 1)).
        animationSpec = tween(Motion.PressDuration, easing = Motion.EaseOut),
        label = "progress-thickness",
    )

    val density = LocalDensity.current
    val restingPx = with(density) { trackHeight.toPx() }
    val activePx = with(density) { activeTrackHeight.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            // Tap to seek.
            .pointerInput(Unit) {
                detectTapGestures { offset: Offset ->
                    onSeek((offset.x / width).coerceIn(0f, 1f))
                }
            }
            // Drag to scrub: the fill follows the finger; the seek happens once, on release.
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek(dragFraction)
                    },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        val fraction = if (dragging) {
            dragFraction
        } else if (durationMs > 0L) {
            (tickedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }

        val thickness = restingPx + (activePx - restingPx) * thick
        val top = (size.height - thickness) / 2f
        val radius = CornerRadius(thickness / 2f, thickness / 2f)

        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(0f, top),
            size = Size(size.width, thickness),
            cornerRadius = radius,
        )
        // A rounded rect shorter than 2·radius has its corners clamped by the backend, which made the
        // leading end a square sliver for the first seconds of a track. Keep the drawn fill at least one
        // thickness wide (width ≥ 2·radius) so the capsule's end stays round at every progress; 0 draws
        // nothing at all.
        val fillWidth = ProgressCapsuleRules.drawnFillWidth(
            fillPx = size.width * fraction,
            thicknessPx = thickness,
            fullWidthPx = size.width,
        )
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(0f, top),
            size = Size(fillWidth, thickness),
            cornerRadius = radius,
        )
    }
}
