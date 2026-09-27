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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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
 * nothing can look distorted.
 *
 * ## The content is anchored to the cover, not to its own centre
 *
 * Rendered both ways and compared against the reference: centring the *player* on the container makes the
 * window open onto the controls, because the player's centre is well below its cover. The reference
 * always shows the cover in the opening window, so the content is placed with the cover's centre at the
 * container's centre, and that placement is released to the exact full-screen position as the container
 * reaches full size.
 *
 * ## Why [progress] is a lambda and every use of it is deferred
 *
 * This is the difference between a smooth transition and a janky one, and it was the actual cause of the
 * reported stutter. Reading an animated value in a composable's **body** makes Compose recompose that
 * composable — and with it the whole of `content`, i.e. the entire player screen — on every frame. With a
 * `State<Float>` read in the caller, the caller's whole subtree (the tab content, the list, the chrome)
 * was recomposing 60 times a second.
 *
 * Reading it inside `layout`/`offset`/`graphicsLayer` lambdas instead means the value is only consumed in
 * the **layout and draw** phases, so composition runs once and the animation costs a measure and a draw.
 *
 * @param source the mini player's rect in root coordinates — where the container starts. Measured, not
 *        computed, because its width depends on the screen.
 * @param focusFraction where the cover's centre sits in the player, as a fraction of its height, also
 *        read on demand for the same reason.
 */
@Composable
fun ContainerExpand(
    progress: () -> Float,
    source: Rect?,
    background: Color,
    focusFraction: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (source == null) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Read once: the screen does not change during the animation, and these are needed to derive the
        // end rect. Everything that *does* change is read inside a deferred lambda.
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        if (screenW <= 0f || screenH <= 0f) return@BoxWithConstraints

        Box(
            Modifier
                // No `size()` call: the container's size is decided in the measure pass, so composition
                // does not have to run to resize it.
                .layout { measurable, constraints ->
                    val t = progress().coerceIn(0f, 1f)
                    val width = lerp(source.width, screenW, t).roundToInt()
                    val height = lerp(source.height, screenH, t).roundToInt()
                    // The content is measured at full screen size — it is never resized — and then the
                    // container simply reports a smaller frame around it.
                    val placeable = measurable.measure(constraints)
                    layout(width, height) { placeable.place(0, 0) }
                }
                .offset {
                    val t = progress().coerceIn(0f, 1f)
                    IntOffset(
                        lerp(source.left, 0f, t).roundToInt(),
                        lerp(source.top, 0f, t).roundToInt(),
                    )
                }
                .graphicsLayer {
                    val t = progress().coerceIn(0f, 1f)
                    // The capsule's pill radius relaxing to square, read here so the shape is a
                    // draw-phase concern. At the end the corners are off-screen anyway.
                    val cornerPx = lerp(source.height / 2f, 0f, t)
                    shape = ContinuousRoundedRectangle(cornerPx.toDp())
                    clip = true
                }
                // Opaque: the container *is* the player's surface, and a transparent one would show the
                // dimmed app through the growing player.
                .background(background),
        ) {
            Box(
                Modifier.offset {
                    val t = progress().coerceIn(0f, 1f)
                    val focusY = focusFraction() * screenH
                    // The offsets are **relative to the container's own top-left**, which is why they
                    // depend only on the sizes and not on where the container happens to be. An earlier
                    // version added the container's absolute position, which pushed the player a whole
                    // container-height off the window — showing an empty fill instead of the cover.
                    //
                    // Aligning the centres means putting the cover's centre (at `focusY` in the player)
                    // on the container's centre (`height / 2`), and the same for x:
                    val anchorX = lerp(source.width, screenW, t) / 2f - screenW / 2f
                    val anchorY = lerp(source.height, screenH, t) / 2f - focusY
                    // Released to zero by the end, so the finished player is where it would be with no
                    // animation at all.
                    IntOffset(
                        lerp(anchorX, 0f, t).roundToInt(),
                        lerp(anchorY, 0f, t).roundToInt(),
                    )
                },
            ) {
                content()
            }
        }
    }
}

/**
 * No content fade: the container's clipping is what limits how much of the player is visible.
 *
 * An earlier version animated this on a full-screen layer, which forces Compose to keep a whole-screen
 * offscreen buffer and re-rasterise it every frame — measurable cost for an effect the clipping already
 * provides. The container is opaque and starts at the capsule's size, so at low progress only a sliver
 * of the player is on screen anyway.
 */

/**
 * Opacity of the app behind as the player opens — it recedes rather than vanishing.
 *
 * **Dim only, deliberately: no blur.** An earlier version also animated a full-screen `Modifier.blur`
 * radius, and that was the main cause of the transition being janky — a full-screen blur re-allocates
 * its render effect every time the radius changes, so it was re-blurring the whole screen on every
 * frame of the animation. The backdrop of a real device showed the result as dropped frames rather than
 * as a smooth fade.
 *
 * Dimming alone gives the same "something is opening over this" cue, and it is what the reference
 * recording shows: the app behind is darkened, not visibly blurred.
 */
fun backdropDimAlpha(progress: Float): Float = (progress * 0.62f).coerceIn(0f, 1f)

/**
 * The scrim laid over the app behind the growing container.
 *
 * A plain translucent fill rather than a blur (see [backdropDimAlpha]), and it fades in by *opacity*
 * rather than by animating a colour, so it is a single cached draw per frame.
 */
@Composable
fun BackdropRecede(progress: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            // The alpha is read in the layer's own lambda, so this composable never recomposes while the
            // value animates — only its layer does.
            .graphicsLayer { alpha = backdropDimAlpha(progress()) },
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
