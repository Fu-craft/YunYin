package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

/**
 * Drives the mini-player → player transition.
 *
 * A spring rather than a tween, and the reason is the whole feel of the gesture: it is interruptible
 * and reversible from wherever it currently is, so tapping the card and immediately dismissing does not
 * jump, and a down-swipe that is released part way settles back under momentum. That is what a system
 * container transition does and what a fixed-duration tween cannot.
 */
@Composable
fun rememberExpandProgress(open: Boolean): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        progress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = 400f,
                visibilityThreshold = 0.001f,
            ),
        )
    }
    return progress.asState()
}

/**
 * Opacity of the player screen while it grows out of the mini player.
 *
 * The screen is transparent at the start so the *cover* is the thing that travels, and it fades in
 * behind it. Deliberately not a cross-fade of two whole screens: that is the hard jump this replaces.
 */
fun playerScreenAlpha(progress: Float): Float =
    ((progress - 0.12f) / 0.45f).coerceIn(0f, 1f)

/**
 * Opacity of the player's *own* cover, handed over from [FloatingCover] at the very end.
 *
 * The two must not both be drawn at full strength — at the handover they occupy exactly the same rect
 * and radius, so the swap is invisible. Starting it late keeps the travelling cover authoritative for
 * almost the whole animation.
 */
fun playerCoverAlpha(progress: Float): Float =
    ((progress - 0.86f) / 0.14f).coerceIn(0f, 1f)

/** Opacity of the background dim scrim — the system-sheet feel of the app receding. */
fun backdropDimAlpha(progress: Float): Float = (progress * 0.55f).coerceIn(0f, 1f)

/** Blur radius applied to the app behind the expanding player, in dp. */
fun backdropBlurRadius(progress: Float): androidx.compose.ui.unit.Dp =
    (progress * 18f).dp

/**
 * The cover, travelling between the mini player's artwork and the player's full-size cover.
 *
 * This is the element the whole animation hangs on: one image, on screen at all times, whose rect is
 * interpolated between the two measured positions. Everything else fades around it, which is what makes
 * the transition read as *the same object growing* rather than as two screens sliding.
 *
 * Why measure both ends rather than transform a screen: mapping a full-size player onto the mini player
 * needs a scale of roughly (0.91, 0.075). Any approach that scales the whole screen therefore shows a
 * flattened ribbon mid-flight — the previous implementation did exactly that. Animating one image
 * between two rects has no such problem, because a rect can change aspect ratio continuously.
 *
 * @param source the mini player's artwork rect, in root coordinates.
 * @param target the player's cover rect, in root coordinates.
 * @param visible false once the handover has completed, so nothing is drawn twice.
 */
@Composable
fun FloatingCover(
    source: Rect?,
    target: Rect?,
    progress: Float,
    cover: ImageBitmap?,
    cornerStart: androidx.compose.ui.unit.Dp,
    cornerEnd: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (cover == null || source == null || target == null) return
    if (progress <= 0f) return

    val alpha = 1f - playerCoverAlpha(progress)
    if (alpha <= 0f) return

    val density = LocalDensity.current
    val t = progress.coerceIn(0f, 1f)

    val left = lerp(source.left, target.left, t)
    val top = lerp(source.top, target.top, t)
    val width = lerp(source.width, target.width, t)
    val height = lerp(source.height, target.height, t)
    // The corner is interpolated too: the card's artwork is a small tile and the player's is a large
    // one, and a hard corner switch mid-flight is visible.
    val corner = androidx.compose.ui.unit.Dp(
        lerp(cornerStart.value, cornerEnd.value, t),
    )

    with(density) {
        Box(
            modifier
                .offset { IntOffset(left.toInt(), top.toInt()) }
                .size(width = width.toDp(), height = height.toDp())
                .shadow(
                    elevation = (14 * t).dp,
                    shape = ContinuousRoundedRectangle(corner),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.5f),
                )
                .clip(ContinuousRoundedRectangle(corner)),
        ) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width.toDp(), height.toDp()),
            )
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
