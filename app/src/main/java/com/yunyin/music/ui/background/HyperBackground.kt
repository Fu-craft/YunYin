package com.yunyin.music.ui.background

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.yunyin.music.core.player.effects.AudioReactive
import kotlinx.coroutines.isActive

/**
 * The audio-reactive fluid background.
 *
 * Renders [HyperBackgroundPainter]'s shader over the given surface and drives it every frame.
 * The artwork contributes only the five palette colours; the motion comes from the shader's
 * displaced colour fields plus the audio-reactive uniforms.
 *
 * Implementation notes:
 *  - **Compose `Canvas` + `Paint(RuntimeShader)`**, with a transparent child bound to `uTex`.
 *    The shader treats a transparent child as "draw the generated colour", so nothing needs to
 *    be sampled.
 *  - Time comes from the **absolute** frame timestamp, so two instances show the same moment of
 *    the animation without coordination.
 *  - The frame counter is read **inside the draw lambda**, so advancing a frame invalidates only
 *    the draw phase — no recomposition per frame.
 *  - The field covers exactly this composable's bounds, with no sub-region or offset mode. A
 *    second instance given a different size would compute a different `uBound` and therefore a
 *    different pattern — that mismatch is what previously produced a visible band where two
 *    surfaces met. Callers wanting a frosted variant blur a full-size instance instead.
 *  - `RuntimeShader` needs API 33 and hardware acceleration; both are checked, and a flat
 *    two-tone gradient from the same palette is drawn otherwise.
 */
@Composable
fun HyperBackground(
    palette: DynamicBackgroundPalette,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    /**
     * How much to darken the result, 0..1.
     *
     * A plain black overlay rather than a shader change, for two reasons: it is trivially
     * predictable, and it sits *inside* this composable, so every instance of the backdrop is
     * dimmed identically. (Dimming only some layers would reintroduce the tonal step at their
     * boundary.) White lyric text over the background is what makes this necessary — the
     * generated colour field is bright by design.
     */
    dim: Float = 0f,
    /**
     * Freezes the animation without removing the surface.
     *
     * Used while the two player presentations cross-fade. The backdrop is behind both of them, so
     * advancing it during the transition buys nothing visually and costs a full-screen shader draw
     * per frame — measured on device, the switch window was where "Slow issue draw commands" spiked
     * (53 → 236 over the same number of frames). Skipping the redraw leaves the last frame on
     * screen, which is exactly what a frozen backdrop should look like.
     */
    paused: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val supportsShader = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val painter = remember(supportsShader) {
        if (!supportsShader) null else runCatching { HyperBackgroundPainter(context) }.getOrNull()
    }

    // A transparent child for `uTex`, so the shader always produces its own colour.
    val paint = remember(painter) {
        painter?.let {
            it.shader.setInputShader(
                "uTex",
                LinearGradient(
                    0f, 0f, 1f, 0f,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                ),
            )
            Paint().apply {
                shader = it.shader
                isAntiAlias = false
            }
        }
    }

    val latestPalette by rememberUpdatedState(palette)
    val latestIsDark by rememberUpdatedState(isDark)
    val latestPaused by rememberUpdatedState(paused)

    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(painter) {
        val shader = painter ?: return@LaunchedEffect
        shader.setIdlePoints()

        var nextRenderNs = Long.MIN_VALUE
        var boostedUntilNs = 0L

        var smoothLevel = 0f
        var smoothBeat = 0f

        var fromColors = latestPalette.toShaderColors()
        var toColors = fromColors
        var fadeStartNs = 0L
        var lastPalette = latestPalette
        var lastDark = latestIsDark

        shader.setPalette(fromColors, latestPalette.saturateOffset, latestPalette.lightOffset)

        while (isActive) {
            withFrameNanos { frameNs ->
                // Frozen: keep the last drawn frame and do no work at all.
                if (latestPaused) return@withFrameNanos

                val intervalNs = if (frameNs < boostedUntilNs) BOOST_INTERVAL_NS else STEADY_INTERVAL_NS
                if (nextRenderNs != Long.MIN_VALUE && frameNs < nextRenderNs) return@withFrameNanos
                nextRenderNs = if (frameNs - nextRenderNs > intervalNs * 2) {
                    frameNs + intervalNs
                } else {
                    nextRenderNs + intervalNs
                }

                // Absolute time: shared by every instance, so they stay in step.
                shader.setAnimTime((frameNs / 1_000_000_000f) % ANIM_TIME_WRAP)

                // --- palette crossfade -----------------------------------------
                val currentPalette = latestPalette
                if (currentPalette != lastPalette || latestIsDark != lastDark) {
                    fromColors = blendedColors(fromColors, toColors, fadeProgress(fadeStartNs, frameNs))
                    toColors = currentPalette.toShaderColors()
                    lastPalette = currentPalette
                    lastDark = latestIsDark
                    fadeStartNs = frameNs
                    boostedUntilNs = frameNs + BOOST_DURATION_NS
                }
                val colors = blendedColors(fromColors, toColors, fadeProgress(fadeStartNs, frameNs))
                shader.setPalette(colors, currentPalette.saturateOffset, currentPalette.lightOffset)

                // --- audio reactivity ------------------------------------------
                val targetLevel = AudioReactive.level.value.coerceIn(0f, 1f)
                val targetBeat = AudioReactive.beat.value.coerceIn(0f, 1f)
                // Asymmetric smoothing: fast attack, slow release, so beats read crisply
                // without the whole screen flickering.
                smoothLevel += (targetLevel - smoothLevel) *
                    (if (targetLevel > smoothLevel) LEVEL_ATTACK else LEVEL_RELEASE)
                smoothBeat += (targetBeat - smoothBeat) *
                    (if (targetBeat > smoothBeat) BEAT_ATTACK else BEAT_RELEASE)
                shader.setReactive(smoothLevel, smoothBeat)

                shader.updateMaterials()
            }
            frameTick++
        }
    }

    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        val activePaint = paint
        val activePainter = painter
        if (activePaint != null && activePainter != null && view.isHardwareAccelerated) {
            activePainter.setResolution(size.width, size.height)
            // Remap the colour field onto the band that gives the effect its diagonal
            // structure, rather than spreading it evenly (which reads as a soft blur).
            activePainter.setBoundForViewport(size.width, size.height, density.density)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, activePaint)
            }
        } else {
            // No shader (pre-API 33, software canvas, or setup failure): a flat two-tone
            // gradient from the same palette, so the surface is never blank.
            val first = Color(palette.colors.first())
            val second = Color(palette.colors.getOrElse(3) { palette.colors.first() })
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(first, second),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }

        if (dim > 0f) {
            drawRect(Color.Black.copy(alpha = dim.coerceIn(0f, 1f)))
        }
    }
}

/** Palette crossfade progress, 0..1. */
private fun fadeProgress(startNs: Long, nowNs: Long): Float {
    if (startNs == 0L) return 1f
    val elapsed = (nowNs - startNs).coerceAtLeast(0L)
    val raw = elapsed.toFloat() / PALETTE_FADE_NS
    if (raw >= 1f) return 1f
    // Ease the ends so the colour change does not snap.
    return raw * raw * (3f - 2f * raw)
}

private fun blendedColors(from: FloatArray, to: FloatArray, progress: Float): FloatArray {
    if (progress >= 1f || from.size != to.size) return to
    val out = FloatArray(to.size)
    for (i in to.indices) {
        out[i] = from[i] + (to[i] - from[i]) * progress
    }
    return out
}

/** Keeps the wrapped time in a range where the sine terms stay well conditioned. */
private const val ANIM_TIME_WRAP = 62.831852f

/**
 * Steady rate for the backdrop.
 *
 * This is the **frame pump for the whole player screen**: the backdrop is the only thing that asks
 * for a frame when nothing else is changing, and Compose coalesces everything into that one frame.
 * So this rate is also the rate the rest of the screen is drawn at.
 *
 * It was pinned to 45fps as a GPU saving. Measured on a 120Hz device with `dumpsys gfxinfo`, the
 * player's frame interval sat at exactly 1000/45 = 22.2ms — i.e. every frame the app produced,
 * including the lyrics fill and the presentation transitions, was paced by this constant. Trading
 * the app's smoothness for the backdrop's GPU time is the wrong side of that deal, so it now runs
 * at the panel's rate. (The `paused` flag handles the case where the backdrop genuinely should not
 * cost anything: during a presentation cross-fade.)
 */
private const val STEADY_INTERVAL_NS = 1_000_000_000L / 60L

/** Boosted rate, used while a palette crossfade is settling. */
private const val BOOST_INTERVAL_NS = 1_000_000_000L / 60L
private const val BOOST_DURATION_NS = 900_000_000L

private const val LEVEL_ATTACK = 0.12f
private const val LEVEL_RELEASE = 0.045f
private const val BEAT_ATTACK = 0.46f
private const val BEAT_RELEASE = 0.12f

private const val PALETTE_FADE_NS = 520_000_000f
