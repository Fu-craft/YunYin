package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * Grows [content] out of [sourceBounds] and back into it, like a system container transition.
 *
 * The effect the user asked for: tapping the mini player does not *navigate* to the player, it makes
 * the small card become the player — one continuous scaling of the same surface — and closing shrinks
 * it back into the card. This is a "container transform", and it is what makes the gesture feel like
 * part of the same object rather than a page change.
 *
 * ## How it works, and why not `SharedTransitionLayout`
 *
 * The obvious tool is a shared element (`sharedBounds`), and this app does use one — but for the
 * album cover *inside* the player, between its two presentations. Spanning the mini player and the
 * player screen with one would mean hoisting `SharedTransitionLayout` to the activity and threading
 * its two scopes through `PlayerScreen`, which already threads them internally. That is a large
 * restructuring for one transition, and it couples the activity to the player's internals.
 *
 * Instead the player is laid out at full size from the first frame and **transformed**: scaled and
 * translated so it starts exactly where the mini player is. [progress] runs 0 (card-sized) to 1 (full
 * screen), and the transform interpolates between the two rects. Nothing about layout changes during
 * the animation, so it cannot reflow mid-flight, and the same code runs the exit in reverse.
 *
 * At progress 0 the content is genuinely tiny — text and artwork are scaled down with everything else,
 * which is what a container transform looks like in flight, and it is why this reads as the card
 * growing rather than as a new screen appearing.
 *
 * @param sourceBounds where the mini player is, in root coordinates; null until it has been measured,
 *        in which case the animation is skipped and the player simply appears (a missing measurement
 *        must not leave the player invisible).
 */
@Composable
fun PlayerExpandOverlay(
    progress: Float,
    sourceBounds: Rect?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rootWidthPx = with(density) { maxWidth.toPx() }
        val rootHeightPx = with(density) { maxHeight.toPx() }

        // The transform is derived from the two rects, so the same maths serves both directions.
        //
        // Packed into a small value class rather than destructured from a FloatArray: an array would
        // need custom component operators (and those operators would then be visible to every file in
        // the module), which is worse than one named type.
        val transform = if (sourceBounds == null || rootWidthPx <= 0f || rootHeightPx <= 0f) {
            null
        } else {
            CardTransform(
                scaleX = (sourceBounds.width / rootWidthPx).coerceIn(0.05f, 1f),
                scaleY = (sourceBounds.height / rootHeightPx).coerceIn(0.05f, 1f),
                // Translation that puts the scaled root's centre on the source's centre.
                dx = sourceBounds.center.x - rootWidthPx / 2f,
                dy = sourceBounds.center.y - rootHeightPx / 2f,
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Faded in over the first part of the growth.
                    //
                    // Measured, this is not a nicety: mapping a 1080x2400 player onto the card needs a
                    // scale of roughly (0.91, 0.075), so a visible player at small progress is a
                    // vertically flattened ribbon. Material's container transform has the same geometry
                    // and hides it the same way — the card is what you see at the start, and the
                    // destination emerges as it grows past it. Without this the transition is legible
                    // as *distortion* rather than as expansion.
                    alpha = expandContentAlpha(progress)
                    if (transform != null) {
                        // Interpolating the *transform*, not a size: at progress 1 every term is the
                        // identity, so the player is pixel-exact when open.
                        val t = progress.coerceIn(0f, 1f)
                        scaleX = lerp(transform.scaleX, 1f, t)
                        scaleY = lerp(transform.scaleY, 1f, t)
                        translationX = transform.dx * (1f - t)
                        translationY = transform.dy * (1f - t)
                        // The surface starts clipped to the card's rounded rect and relaxes as it
                        // grows; without this the tiny state shows the player's square corners.
                        clip = t < 1f
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * Opacity of the growing player at [progress].
 *
 * Zero until the growth is under way, full before the end. The window is where the shape is already
 * large enough not to read as squashed.
 */
fun expandContentAlpha(progress: Float): Float =
    ((progress - 0.22f) / 0.40f).coerceIn(0f, 1f)

/**
 * Opacity of the mini-player card at [progress] — the complement of [expandContentAlpha].
 *
 * The card has to stay **visible underneath** while the player fades in, which is what makes the
 * transition start from the card rather than from a flattened rectangle. It leaves a little earlier
 * than the player arrives so the two are not both half-drawn for long.
 */
fun expandCardAlpha(progress: Float): Float =
    1f - ((progress - 0.10f) / 0.35f).coerceIn(0f, 1f)

/** Where the card is, expressed as the transform that maps the full-size player onto it. */
private class CardTransform(
    val scaleX: Float,
    val scaleY: Float,
    val dx: Float,
    val dy: Float,
)

/**
 * Drives an expand/collapse progress for [PlayerExpandOverlay].
 *
 * Uses a spring rather than a tween: the request was explicitly for the feel of a system transition,
 * which is interruptible and slightly settling. A spring also means a reversal mid-flight continues
 * from the current value and velocity instead of restarting, so tapping the card and immediately
 * closing does not jump.
 */
@Composable
fun rememberExpandProgress(open: Boolean): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        progress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.86f,
                stiffness = 420f,
                visibilityThreshold = 0.001f,
            ),
        )
    }
    return progress.asState()
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
