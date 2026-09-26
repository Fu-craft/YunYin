package com.yunyin.music.lyrics.composable.lyrics

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

@Composable
fun LyricsLineItem(
    isFocused: Boolean,
    isRightAligned: Boolean,
    onLineClicked: () -> Unit,
    onLinePressed: () -> Unit,
    blurRadius: () -> Float,
    modifier: Modifier = Modifier,
    /**
     * Scale for the focused line and for the others.
     *
     * Growth of the active line is what reads as "this is the line being sung"; blur and alpha
     * only say the others are *not*. Values come from the caller so the tuning lives with the
     * host app (NeriPlayer uses 1.015 / 0.965).
     */
    focusedScale: Float = 1f,
    unfocusedScale: Float = 0.98f,
    activeAlpha: Float = 1f,
    inactiveAlpha: Float = 0.4f,
    blendMode: BlendMode = BlendMode.SrcOver,
    isInteractive: Boolean = true,
    content: @Composable () -> Unit
) {
    val scaleState by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else unfocusedScale,
        animationSpec = if (isFocused) {
            tween(durationMillis = 600, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 300, easing = EaseInOut)
        },
        label = "scale"
    )

    val alphaState by animateFloatAsState(
        targetValue = if (isFocused) activeAlpha else inactiveAlpha,
        label = "alpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scaleState
                scaleY = scaleState
                alpha = alphaState
                transformOrigin = TransformOrigin(if (isRightAligned) 1f else 0f, 1f)
                this.blendMode = blendMode
                compositingStrategy = CompositingStrategy.Offscreen

                val radius = blurRadius()
                if (radius > 0f) {
                    renderEffect = BlurEffect(
                        radiusX = radius,
                        radiusY = radius,
                        edgeTreatment = TileMode.Clamp
                    )
                }
            }
            .then(
                if (isInteractive) Modifier.clip(ContinuousRoundedRectangle(8.dp))
                    .combinedClickable(
                        onClick = onLineClicked,
                        onLongClick = onLinePressed
                    )
                else Modifier
            )

    ) {
        content()
    }
}
