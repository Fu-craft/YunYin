package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlin.math.roundToInt

/**
 * Drives the mini-player → player transition.
 *
 * A spring, not a tween: it is interruptible and reversible from wherever it currently is, so tapping
 * the card and immediately dismissing does not jump, and a down-swipe released part way settles under
 * momentum. That is what a system container transition does and what a fixed-duration tween cannot.
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
 * Grows the player out of the mini player's rect, the way a system container transition does.
 *
 * ## Why this is a growing *container* and not a scaled screen
 *
 * Two earlier attempts scaled the player: first the whole screen (a (0.91, 0.075) squash, so mid-flight
 * it was a flattened ribbon), then just the cover. Frames extracted from the reference recording show
 * what it actually does, and it is neither: **a rounded rect grows from the mini player's rect to the
 * full screen, and the player is revealed inside it at its true size.** Nothing is scaled, which is why
 * nothing can look distorted — the controls are the same size in the first frame as in the last.
 *
 * ## The content is anchored to the cover, not to its own centre
 *
 * Rendered both ways and compared against the reference: centring the *player* on the container makes the
 * window open onto the controls, because the player's centre is well below its cover. The reference
 * always shows the cover in the opening window, so the content is placed with the cover's centre at the
 * container's centre, and that placement is then released to the exact full-screen position as the
 * container reaches full size. That combination is what makes the cover appear to rise and grow while
 * the frame around it widens.
 *
 * @param source the mini player's rect in root coordinates — where the container starts. Measured, not
 *        computed, because its width depends on the screen.
 * @param focusFraction where the cover's centre sits in the player, as a fraction of its height.
 *        Portrait puts an 87%-width square cover centred at y≈0.38 of the screen; the value only decides
 *        which part of the player the window opens onto, so a drift here is cosmetic rather than
 *        structural.
 */
@Composable
fun ContainerExpand(
    progress: Float,
    source: Rect?,
    background: Color,
    modifier: Modifier = Modifier,
    focusFraction: Float = 0.38f,
    content: @Composable () -> Unit,
) {
    if (progress <= 0f || source == null) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Captured as locals: inside the `with(density)` block below there is no implicit
        // BoxWithConstraints receiver in scope, so `maxWidth`/`maxHeight` cannot be read there.
        val screenSize = maxWidth to maxHeight
        val screenW = with(density) { screenSize.first.toPx() }
        val screenH = with(density) { screenSize.second.toPx() }
        if (screenW <= 0f || screenH <= 0f) return@BoxWithConstraints

        val t = progress.coerceIn(0f, 1f)
        val left = lerp(source.left, 0f, t)
        val top = lerp(source.top, 0f, t)
        val width = lerp(source.width, screenW, t)
        val height = lerp(source.height, screenH, t)
        // The capsule's pill radius relaxing to square, so the shape morphs rather than snaps. At the
        // end the corners are off-screen anyway, so the exact terminal value does not show.
        val corner = lerp(source.height / 2f, 0f, t)

        // Where the content sits inside the container.
        //
        // At p=0 the cover's centre is brought onto the container's centre; by p=1 that displacement has
        // been released to zero, so the player ends exactly where it would be without any animation.
        val focusY = focusFraction * screenH
        val centredOffsetX = (left + width / 2f) - screenW / 2f
        val centredOffsetY = (top + height / 2f) - focusY
        val offsetX = lerp(centredOffsetX, 0f, t)
        val offsetY = lerp(centredOffsetY, 0f, t)

        with(density) {
            Box(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(width = width.toDp(), height = height.toDp())
                    .clip(ContinuousRoundedRectangle(corner.toDp()))
                    // Opaque from the first frame: the container is the player's surface, and a
                    // transparent one would show the dimmed app through the growing player.
                    .background(background),
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                        .size(width = screenSize.first, height = screenSize.second)
                        .alpha(contentAlpha(progress)),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Opacity of the player's content as the container grows.
 *
 * Starts a little above zero rather than at zero: the container's own fill is already the player's
 * surface colour, so a small initial value lets the player's real background establish itself instead of
 * the fill visibly changing.
 */
fun contentAlpha(progress: Float): Float =
    ((progress - 0.06f) / 0.34f).coerceIn(0f, 1f)

/** Opacity of the app behind as the player opens — it recedes rather than vanishing. */
fun backdropDimAlpha(progress: Float): Float = (progress * 0.5f).coerceIn(0f, 1f)

/** Blur applied to the app behind, in dp. */
fun backdropBlurRadius(progress: Float): androidx.compose.ui.unit.Dp = (progress * 16f).dp

/** Opacity of the floating chrome underneath, which the growing container covers. */
fun chromeAlpha(progress: Float): Float = 1f - (progress * 1.6f).coerceIn(0f, 1f)

/**
 * The dim + blur laid over the app behind the growing container.
 *
 * Separate from [ContainerExpand] because it must cover the whole screen rather than the container, and
 * because a blur applies to what is *under* it, so it has to be its own layer.
 */
@Composable
fun BackdropRecede(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f) return
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { clip = false }
            .blur(backdropBlurRadius(progress))
            .background(Color.Black.copy(alpha = backdropDimAlpha(progress))),
    )
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
