// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import android.animation.ValueAnimator
import android.graphics.RuntimeShader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.isActive
import kotlin.math.PI

/**
 * An approximate **mesh warp** for the player's presentation change (artwork ↔ lyrics).
 *
 * A real vertex-grid deformation needs a vertex shader over the composables, which Compose does not
 * expose, so this reproduces the *look* of one at the only place a per-pixel shader can reach: a
 * one-shot frozen frame of the outgoing presentation, drawn as a full-screen textured layer whose
 * sampling coordinates are displaced by a time-varying field. The incoming presentation renders
 * normally underneath, so the result reads as the old screen being dragged and rippled away, not as
 * a flat cross-fade.
 *
 * Flow, in order:
 *
 *  1. A presentation toggle arms [PresentationWarpState.armed]. The frame that follows records the
 *     whole player (backdrop + presentation) into a [GraphicsLayer] while still drawing it, so
 *     nothing changes on screen.
 *  2. That layer is rasterised once with `toImageBitmap()` — a genuinely frozen frame, not a live
 *     display list (a recorded layer would still track its children's own layers, which keep
 *     animating during the change). This is the only readback in the whole feature.
 *  3. [PresentationWarpOverlay] draws the bitmap with a [RuntimeShader] `RenderEffect` whose
 *     displacement grows from zero at progress 0, peaks at the midpoint (the "rubber sheet" moment)
 *     and returns to zero, while the snapshot's alpha fades out over the second half. Start and end
 *     are therefore exact frames.
 *  4. When the progress finishes, the overlay is removed and the live screen is what remains.
 *
 * ## Interruptibility
 *
 * A second toggle during the warp never leaves a frozen snapshot behind: [PresentationWarpState]
 * gets a cancel request, the overlay fades out over [PresentationWarpRules.CANCEL_FADE_MS] while the
 * underlying `AnimatedContent` reverses normally, and the state is discarded. If the presentation
 * state changes for any other reason mid-warp, the overlay is discarded the same way.
 *
 * ## Cost
 *
 * One capture per toggle (a full-screen layer record for one frame + one rasterisation), then for
 * the warp's 560 ms: one full-screen image draw with a single texture sample per pixel and a handful
 * of arithmetic operations. Nothing is allocated per frame: the uniforms are three float writes and
 * the progress lives in two `MutableFloatState`s read only in the layer phase.
 *
 * ## Fallback
 *
 * Runtime shaders need API 33+ *and* a working driver, and the system animation scale must be on
 * (`areAnimatorsEnabled`). If any of those is false — or the shader fails to build —
 * [rememberPresentationWarpEffect] returns `null` and the player uses its existing 560 ms cross-fade
 * unchanged. A failed capture (the
 * layer could not be rasterised) also falls back for that one transition, leaving no overlay.
 */
internal object PresentationWarpRules {

    /**
     * Warp length on the snapshot-overlay path. Longer than the 560 ms cross-fade it accompanies so
     * a geometric deformation has time to be read; the plain fallback keeps the 560 ms cross-fade.
     */
    const val DURATION_MS = 720L

    /**
     * How quickly the snapshot ramps in. The snapshot is a frame or two older than the live screen,
     * so a short ramp hides that gap instead of cutting to it.
     */
    const val FADE_IN_MS = 70L

    /** Fast fade used when the transition is reversed: the snapshot must leave, not linger. */
    const val CANCEL_FADE_MS = 140L

    /**
     * How long the outgoing presentation itself stays visible underneath the snapshot. Much shorter
     * than the warp, so the live cross-fade never shows as a double exposure through the overlay.
     */
    const val OUTGOING_FADE_MS = 240L

    /**
     * Peak vertical peel, in dp: the top and bottom of the frozen frame are pushed in opposite
     * directions, which is what reads as a rubber sheet rather than a slide.
     */
    const val PEEL_DP = 72f

    /**
     * Peak ripple amplitude, in dp, at the edges. Two frequencies per axis so different parts of the
     * frame deform differently; this is the mesh part rather than a uniform wobble.
     */
    const val RIPPLE_DP = 32f

    /** Peak directional lean, in dp, along the toggle's direction. */
    const val DRAG_DP = 40f

    /**
     * The smallest radial UV scale of the dolly around the artwork's centre: 0.90 samples a 90 %
     * window and draws it to full screen, i.e. a 1.11x zoom-in at the midpoint.
     */
    const val PEAK_RADIAL_SCALE = 0.90f

    /**
     * **The single knob for more.** Multiplies peel, ripple, lean and the dolly delta while leaving
     * the timing and the exact-ends envelope intact. 1.0 is the tuned default.
     */
    const val AMPLITUDE_SCALE = 1f

    /** Edge drag as a fraction of the distance from the centre (samples pushed outward). */
    const val EDGE_DRAG = 0.045f

    /** Two ripple waves per axis at different frequencies, so regions deform differently. */
    private const val RIPPLE_FREQ_Y1 = 9f
    private const val RIPPLE_FREQ_Y2 = 21f
    private const val RIPPLE_FREQ_X1 = 7f
    private const val RIPPLE_FREQ_X2 = 17f

    /** Ripple phase progress over the whole warp. */
    private const val PHASE_TURNS = 1.5f

    /**
     * The displacement envelope: `0` at both ends, `1` at the midpoint, smoothstep-shaped on each
     * half. Pinning this is what guarantees that progress 0 and progress 1 are the exact start and
     * end frames, and that the warp never exceeds its amplitude budget.
     */
    fun envelope(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val half = if (p < 0.5f) p * 2f else (1f - p) * 2f
        return smoothStep01(half)
    }

    /**
     * Snapshot opacity: ramps in over [FADE_IN_MS], holds through the first half, then fades out
     * across the second half so no empty edge can ever be seen at the borders.
     */
    fun overlayAlpha(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val ramp = smoothstep(0f, FADE_IN_MS.toFloat() / DURATION_MS, p)
        val fadeOut = if (p <= 0.5f) 1f else 1f - smoothstep(0.5f, 1f, p)
        return ramp * fadeOut
    }

    /** Radial UV scale: 1 at both ends, [PEAK_RADIAL_SCALE] at the midpoint. */
    fun radialScale(progress: Float): Float =
        1f - (1f - PEAK_RADIAL_SCALE) * AMPLITUDE_SCALE * envelope(progress)

    /** How much of a base amplitude is applied at [progress] (0..AMPLITUDE_SCALE). */
    fun amplitudeFactor(progress: Float): Float = AMPLITUDE_SCALE * envelope(progress)

    /** A base displacement in dp, scaled for [progress]. Pure so tests can pin proportionality. */
    fun displacementDp(baseDp: Float, progress: Float): Float = baseDp * amplitudeFactor(progress)

    /** Field lean sign: +1 when going to the lyrics page, -1 when leaving it. */
    fun direction(targetShowLyrics: Boolean): Float = if (targetShowLyrics) 1f else -1f

    /** Progress of the 0..1 warp, clamped; a negative start means "finished". */
    fun progress(startedAtMs: Long, nowMs: Long): Float {
        if (startedAtMs < 0L) return 1f
        return ((nowMs - startedAtMs).toFloat() / DURATION_MS).coerceIn(0f, 1f)
    }

    /** How far the cancel fade has advanced, `0..1`; `0` while not cancelled. */
    fun cancelProgress(cancelAtMs: Long, nowMs: Long): Float {
        if (cancelAtMs < 0L) return 0f
        return ((nowMs - cancelAtMs).toFloat() / CANCEL_FADE_MS).coerceIn(0f, 1f)
    }

    /** Multiplier applied to the overlay when the transition is cancelled. */
    fun cancelAlpha(cancelProgress: Float): Float =
        1f - smoothStep01(cancelProgress.coerceIn(0f, 1f))

    /** Travelling-ripple phase: one and a half waves over the warp. */
    fun wavePhase(progress: Float): Float = progress.coerceIn(0f, 1f) * 2f * PI.toFloat() * 1.5f

    /** Horizontal ripple phase used by the shader's second axis (kept here so tests can pin it). */
    fun crossPhase(phase: Float): Float = phase * 0.8f

    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return smoothStep01(t)
    }

    private fun smoothStep01(t: Float): Float = t * t * (3f - 2f * t)
}

/**
 * The shader wrapper: one [RuntimeShader], one [RenderEffect], constants installed once.
 *
 * Mutable uniforms are written from the draw phase each frame; the only allocation-free path
 * Android offers for that is [RuntimeShader.setFloatUniform], so the per-frame cost is three float
 * writes (plus a size write only when the layout size actually changes).
 */
internal class PresentationWarpEffect private constructor(
    private val shader: RuntimeShader,
    val renderEffect: RenderEffect,
) {
    private var installedWidth = -1
    private var installedHeight = -1

    fun configure(
        envelope: Float,
        alpha: Float,
        radialScale: Float,
        center: Offset,
        direction: Float,
        phase: Float,
        width: Int,
        height: Int,
    ) {
        shader.setFloatUniform("uEnvelope", envelope)
        shader.setFloatUniform("uAlpha", alpha)
        shader.setFloatUniform("uRadialScale", radialScale)
        shader.setFloatUniform("uCenter", center.x.coerceIn(0f, 1f), center.y.coerceIn(0f, 1f))
        shader.setFloatUniform("uDirection", direction)
        shader.setFloatUniform("uPhase", phase)
        if (width != installedWidth || height != installedHeight) {
            shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
            installedWidth = width
            installedHeight = height
        }
    }

    companion object {
        /** @return the effect, or `null` when the shader or its RenderEffect cannot be built. */
        fun create(peelPx: Float, ripplePx: Float, dragPx: Float): PresentationWarpEffect? {
            val shader = RuntimeShader(SHADER_SOURCE)
            shader.setFloatUniform("uPeel", peelPx)
            shader.setFloatUniform("uRipple", ripplePx)
            shader.setFloatUniform("uDrag", dragPx)
            shader.setFloatUniform("uSize", 1f, 1f)
            return runCatching {
                val effect = android.graphics.RenderEffect
                    .createRuntimeShaderEffect(shader, CONTENT_UNIFORM)
                    .asComposeRenderEffect()
                PresentationWarpEffect(shader, effect)
            }.getOrNull()
        }
    }
}

/**
 * Builds the warp effect once per capability/density state.
 *
 * @return `null` when runtime shaders are unavailable, the window is software-rendered, or the
 *     shader failed to build on this driver. Callers then keep the plain cross-fade.
 */
@Composable
internal fun rememberPresentationWarpEffect(): PresentationWarpEffect? {
    val view = LocalView.current
    val density = LocalDensity.current
    // Animations off is a hard "no": the warp is a per-frame animation, so the plain cross-fade
    // runs instead. Same capability gate the rest of the player uses.
    val animatorsOn = remember {
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
    val supported = isRuntimeShaderSupported() && view.isHardwareAccelerated && animatorsOn
    return remember(supported, density.density) {
        if (!supported) {
            null
        } else {
            runCatching {
                PresentationWarpEffect.create(
                    peelPx = with(density) { PresentationWarpRules.PEEL_DP.dp.toPx() },
                    ripplePx = with(density) { PresentationWarpRules.RIPPLE_DP.dp.toPx() },
                    dragPx = with(density) { PresentationWarpRules.DRAG_DP.dp.toPx() },
                )
            }.getOrNull()
        }
    }
}

/**
 * State of one warp attempt.
 *
 * [armed] is set by the toggle handler and cleared once the frame has been recorded; [begin] opens
 * a session with the frozen frame; [discard] removes it. All of it is draw-phase-readable except the
 * capture arm, which is read by the capture modifier.
 */
@Stable
internal class PresentationWarpState internal constructor(internal val layer: GraphicsLayer) {
    var armed by mutableStateOf(false)
    var snapshot by mutableStateOf<ImageBitmap?>(null)
        private set
    var startedAtMs by mutableLongStateOf(-1L)
        private set
    var cancelAtMs by mutableLongStateOf(-1L)

    /** Centre of the dolly, in normalised screen coordinates (the artwork cover's centre). */
    var center by mutableStateOf(Offset(0.5f, 0.45f))
        private set

    /** Field lean sign for this warp; captured at [begin]. */
    private var direction = 1f

    /** Set by the toggle handler when a second toggle arrives mid-warp; consumed by the overlay. */
    var cancelRequested by mutableStateOf(false)

    /** The presentation value the warp was started for, so a foreign change can be told apart. */
    var targetShowLyrics by mutableStateOf(false)

    val sessionActive: Boolean get() = snapshot != null && startedAtMs >= 0L

    fun begin(bitmap: ImageBitmap, startMs: Long, center: Offset, targetShowLyrics: Boolean) {
        snapshot = bitmap
        startedAtMs = startMs
        this.center = Offset(center.x.coerceIn(0.05f, 0.95f), center.y.coerceIn(0.05f, 0.95f))
        direction = PresentationWarpRules.direction(targetShowLyrics)
        cancelAtMs = -1L
        cancelRequested = false
    }

    fun currentDirection(): Float = direction

    fun discard() {
        snapshot = null
        startedAtMs = -1L
        cancelAtMs = -1L
        cancelRequested = false
        armed = false
    }
}

/** Creates the remembered graphics layer every warp capture records into. */
@Composable
internal fun rememberPresentationWarpState(): PresentationWarpState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { PresentationWarpState(layer) }
}

/**
 * The capture half of the warp: while [armed], the decorated content is recorded into [layer] and
 * then drawn through it, which is pixel-identical to drawing it directly. When not armed the
 * modifier costs nothing and draws normally.
 *
 * `record` swaps the draw context's canvas for one frame, so `drawContent()` inside it lands in the
 * layer while the same content is composited to the screen by `drawLayer`.
 */
internal fun Modifier.capturePresentationInto(layer: GraphicsLayer, armed: Boolean): Modifier =
    drawWithContent {
        if (armed) {
            // `GraphicsLayer.record` is a member extension of `DrawScope`: it swaps the draw
            // context's canvas for the recorded frame, so `drawContent()` inside lands in the layer
            // while `drawLayer` keeps showing the same pixels on screen.
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        } else {
            drawContent()
        }
    }

/**
 * The overlay half: draws the frozen outgoing frame over the live player and warps it away.
 *
 * The overlay removes itself (through [PresentationWarpState.discard]) when the warp completes or
 * its cancel fade finishes, so there is never a stale snapshot on screen.
 */
@Composable
internal fun PresentationWarpOverlay(
    state: PresentationWarpState,
    effect: PresentationWarpEffect,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot ?: return
    val progress = remember(state.startedAtMs) { mutableFloatStateOf(0f) }
    val cancelProgress = remember(state.startedAtMs) { mutableFloatStateOf(0f) }

    LaunchedEffect(state.startedAtMs, state.cancelRequested) {
        if (state.startedAtMs < 0L) return@LaunchedEffect
        while (isActive) {
            val nowMs = withFrameNanos { it } / 1_000_000L
            if (state.cancelRequested && state.cancelAtMs < 0L) {
                state.cancelAtMs = nowMs
            }
            val p = PresentationWarpRules.progress(state.startedAtMs, nowMs)
            val cp = PresentationWarpRules.cancelProgress(state.cancelAtMs, nowMs)
            progress.floatValue = p
            cancelProgress.floatValue = cp
            if (p >= 1f || cp >= 1f) break
        }
        state.discard()
    }

    Image(
        bitmap = snapshot,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.floatValue
                effect.configure(
                    envelope = PresentationWarpRules.envelope(p),
                    alpha = PresentationWarpRules.overlayAlpha(p) *
                        PresentationWarpRules.cancelAlpha(cancelProgress.floatValue),
                    radialScale = PresentationWarpRules.radialScale(p),
                    center = state.center,
                    direction = state.currentDirection(),
                    phase = PresentationWarpRules.wavePhase(p),
                    width = size.width.toInt(),
                    height = size.height.toInt(),
                )
                renderEffect = effect.renderEffect
            },
    )
}

/**
 * AGSL displacement pass over the frozen frame.
 *
 * One sample per pixel. The field is a sum of a vertical peel (strongest at top and bottom), a
 * travelling ripple that is amplified towards the borders, and an outward drag near the edges,
 * scaled by the single envelope uniform so the start and end frames are exact. The displaced
 * coordinate is clamped to the bitmap, which is what keeps the borders free of empty pixels; the
 * alpha fade-out over the second half is the second guard, so even at peak displacement the overlay
 * is already on its way out.
 */
private const val SHADER_SOURCE = """
uniform shader content;
uniform float2 uSize;
uniform float uEnvelope;
uniform float uAlpha;
uniform float uRadialScale;
uniform float2 uCenter;
uniform float uDirection;
uniform float uPhase;
uniform float uPeel;
uniform float uRipple;
uniform float uDrag;

half4 main(float2 coordinate) {
    float2 uv = coordinate / uSize;

    // 1. Radial dolly about the artwork cover's centre: 1 at the ends, uRadialScale at the midpoint.
    uv = uCenter + (uv - uCenter) * uRadialScale;

    // 2. Vertical peel shear: top and bottom pushed in opposite directions.
    uv.x += uEnvelope * (uPeel / uSize.x) * (uv.y - uCenter.y) * 2.0;

    // 3. Two ripple waves per axis: different regions of the frozen frame stretch differently.
    float edge = smoothstep(0.25, 0.85, length(uv - uCenter) * 1.41421356);
    float rippleY = sin(uv.y * 9.0 + uPhase) * 0.62 + sin(uv.y * 21.0 - uPhase * 1.7 + 1.3) * 0.38;
    float rippleX = sin(uv.x * 7.0 - uPhase * 0.9) * 0.58 + sin(uv.x * 17.0 + uPhase * 1.4) * 0.42;
    uv.x += uEnvelope * (uRipple / uSize.x) * rippleY * (0.30 + 0.70 * edge);
    uv.y += uEnvelope * (uRipple * 0.7 / uSize.y) * rippleX * edge;

    // 4. Directional lean: the whole field leans the way the toggle is going.
    uv.x += uEnvelope * uDirection * (uDrag / uSize.x) * (0.25 + 0.75 * edge);

    // 5. Outward edge drag, then never sample outside the texture.
    uv += uEnvelope * (uv - uCenter) * edge * (uDrag / uSize.x) * 0.25;
    float2 samplePosition = clamp(uv, float2(0.0), float2(1.0)) * uSize;
    samplePosition = clamp(samplePosition, float2(0.0), uSize - float2(1.0));
    half4 color = content.eval(samplePosition);
    return color * uAlpha;
}
"""

private const val CONTENT_UNIFORM = "content"
