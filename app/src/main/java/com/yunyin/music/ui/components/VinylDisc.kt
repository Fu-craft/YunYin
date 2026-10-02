package com.yunyin.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme

/**
 * An album cover drawn as a vinyl record, turning while the music plays.
 *
 * ## Why it is drawn rather than assembled from shapes
 *
 * A record is mostly *grooves*: dozens of concentric rings whose spacing is what makes it read as vinyl
 * rather than as a black circle. Drawing them in one `Canvas` keeps that spacing exact and proportional to
 * the diameter, which composables-in-a-Column cannot express.
 *
 * ## The rotation
 *
 * Driven by `withFrameNanos` instead of `rememberInfiniteTransition`, for two reasons that both matter:
 *
 *  - **Pause has to freeze, not reset.** An infinite transition restarts from its initial value when it is
 *    toggled off and on, so pausing and resuming would jump the record round. Accumulating the angle per
 *    frame means resume continues from wherever it stopped, which is what a real turntable does. (Measured
 *    waiting on the system clock, so a dropped frame advances the angle by the real elapsed time rather
 *    than by one nominal frame — otherwise the speed would drift with the frame rate.)
 *  - **A rotation is the one animation where "reduced motion" is not decoration.** The app respects the
 *    system animation setting elsewhere, so when it is off the disc is drawn static rather than spinning
 *    anyway.
 *
 * ## What is deliberately not here
 *
 * No tonearm. It is the usual next detail, but a convincing one needs to sit *over* the disc at a
 * believable angle in both orientations, and a badly placed arm reads as a bug rather than as a turntable.
 * The record plus its label carries the idea on its own.
 */
@Composable
fun VinylDisc(
    url: String?,
    loader: ArtworkLoader,
    size: Dp,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette

    // Degrees, accumulated rather than derived, so pausing holds the angle.
    var angle by remember { mutableFloatStateOf(0f) }
    val spin = rememberAnimationEnabled()

    LaunchedEffect(isPlaying, spin) {
        if (!isPlaying || !spin) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val elapsedSeconds = (now - last) / 1_000_000_000f
                    angle = (angle + DEGREES_PER_SECOND * elapsedSeconds) % 360f
                }
                last = now
            }
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = angle }
            // Clip to the circle so the square label never pokes past the record's edge.
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // `this.size` is the Canvas scope's own size — do not shadow it with the `size` parameter.
            val extent = this.size.minDimension
            val radius = extent / 2f
            val centre = Offset(this.size.width / 2f, this.size.height / 2f)

            // The disc, with the sheen a pressed record has: light catching one side more than the other.
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF2A2A2C),
                        Color(0xFF121214),
                        Color(0xFF1C1C1F),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(this.size.width, this.size.height),
                ),
                radius = radius,
                center = centre,
            )

            // Grooves: many thin rings, denser towards the edge, with a gap where the label sits.
            val labelRadius = radius * LABEL_FRACTION
            val outer = radius * 0.97f
            var r = labelRadius * 1.06f
            var step = radius * 0.012f
            while (r < outer) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.035f),
                    radius = r,
                    center = centre,
                    style = Stroke(width = 0.7f),
                )
                // Spacing widens slightly outwards, as it does on a real pressing, so the grooves do not
                // read as a printed grid.
                r += step
                step *= 1.0045f
            }

            // The rim: a slightly lighter edge, which is what separates the record from a dark background.
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = outer,
                center = centre,
                style = Stroke(width = 1.5f),
            )

            // The run-out groove, just outside the label — the one ring everyone recognises.
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = labelRadius * 1.02f,
                center = centre,
                style = Stroke(width = 1.2f),
            )
        }

        // The label: the album cover itself.
        Artwork(
            url = url,
            loader = loader,
            corner = size * LABEL_FRACTION / 2f,
            requestSize = 400,
            placeholderIcon = SfIcons.MusicNote,
            modifier = Modifier
                .fillMaxSize(LABEL_FRACTION),
        )

        // The spindle hole. Drawn last so it is never covered by the label.
        Spindle(SPINDLE_FRACTION, palette.background)
    }
}

/** A record's label is a little under half the disc; this is the proportion used everywhere here. */
private const val LABEL_FRACTION = 0.42f

/** The centre hole, as a fraction of the radius. */
private const val SPINDLE_FRACTION = 0.028f

/** The centre hole: a dark dot with a light ring, which is what reads as "a record on a spindle". */
@Composable
private fun Spindle(fraction: Float, background: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val radius = this.size.minDimension / 2f
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        drawCircle(
            color = Color.White.copy(alpha = 0.18f),
            radius = radius * fraction * 1.9f,
            center = centre,
            style = Stroke(width = 1f),
        )
        drawCircle(color = background, radius = radius * fraction, center = centre)
    }
}

/** 33⅓ rpm is 200°/s, which is far too fast to look calm; this is the same feel as a slow turntable. */
private const val DEGREES_PER_SECOND = 33f

/**
 * Whether the system has animations enabled.
 *
 * Read through `ValueAnimator.areAnimatorsEnabled()` — the same check `SplashScreen` uses — because the app
 * honours that setting elsewhere and a spinning record is exactly the kind of motion someone turns off.
 */
@Composable
private fun rememberAnimationEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { android.animation.ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
}
