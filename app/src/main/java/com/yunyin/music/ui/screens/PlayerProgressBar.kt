package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Thin, draggable playback progress bar.
 *
 * Follows the iOS media-scrubber idiom: a 4pt track that grows thicker while being
 * dragged, continuous (squircle) caps, and seek-on-release so scrubbing does not spam
 * the player with seeks. While dragging, the knob follows the finger rather than the
 * reported position, which would otherwise fight the gesture.
 */
@Composable
fun PlayerProgressBar(
    progress: Float,
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

    val displayFraction = if (dragging) dragFraction else progress.coerceIn(0f, 1f)
    val height = if (dragging) activeTrackHeight else trackHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset: Offset ->
                    onSeek((offset.x / width).coerceIn(0f, 1f))
                }
            }
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .align(androidx.compose.ui.Alignment.CenterStart)
                .clip(ContinuousRoundedRectangle(height / 2))
                .background(inactiveColor),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(displayFraction)
                    .height(height)
                    .clip(ContinuousRoundedRectangle(height / 2))
                    .background(activeColor),
            )
        }
    }
}
