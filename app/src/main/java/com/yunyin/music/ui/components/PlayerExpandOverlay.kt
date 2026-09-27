package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle

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
 * ## What it draws
 *
 * A rounded container grows from the mini player's rect to the full screen, and the player is revealed
 * inside it at its **true size** — nothing is scaled, so nothing can distort. The content is anchored so
 * the *cover's* centre sits at the container's centre, which is what makes the window open onto the
 * cover rather than onto the controls.
 *
 * ## Why this is entirely a draw-phase effect
 *
 * The first version of this resized the container each frame with a `layout` modifier. That looks
 * harmless and is not: a parent whose size changes forces its children to be re-measured, so the whole
 * player screen — the lyric document, its wrapped syllable layouts, the lists — was **re-measured every
 * frame**. That is the stutter, and it is why it survived the previous round of fixes.
 *
 * So the content is measured exactly once at full screen size, and each frame only:
 *
 *  - `translate`s the content (draw-phase, no layout),
 *  - `clipPath`s it to the container's rounded rect (draw-phase, no layout).
 *
 * With [progress] read inside the draw lambda, a frame of this animation costs one draw pass and zero
 * composition and zero measure. That is the only way a container transition over a screen this heavy is
 * smooth.
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

    Box(
        modifier
            .fillMaxSize()
            .drawWithContent {
                val t = progress().coerceIn(0f, 1f)
                // The container's rect: from the capsule to the whole screen.
                val left = lerp(source.left, 0f, t)
                val top = lerp(source.top, 0f, t)
                val width = lerp(source.width, size.width, t)
                val height = lerp(source.height, size.height, t)
                // Pill radius relaxing to square. At the end the corners are off-screen anyway.
                val corner = lerp(source.height / 2f, 0f, t)

                // Where the content sits, relative to the container. Anchoring puts the cover's centre
                // (at `focusFraction` of the screen) on the container's centre, and the displacement is
                // released to zero by the end so the finished player is exactly where it would be with
                // no animation at all.
                val focusY = focusFraction() * size.height
                val anchorX = lerp(width / 2f - size.width / 2f, 0f, t)
                val anchorY = lerp(height / 2f - focusY, 0f, t)

                val path = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = top,
                            right = left + width,
                            bottom = top + height,
                            radiusX = corner,
                            radiusY = corner,
                        ),
                    )
                }
                clipPath(path) {                    // The container's own opaque surface. Drawn inside the clip so the rounded corners
                    // are filled too, rather than showing the app through them.
                    drawRect(
                        color = background,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                    )
                    translate(anchorX, anchorY) {
                        this@drawWithContent.drawContent()
                    }
                }
            },
    ) {
        content()
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
