package com.yunyin.music.ui.screens

import android.animation.ValueAnimator
import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlinx.coroutines.isActive
import kotlin.math.PI

/**
 * A live, full-container **mesh-warp approximation** for the player's presentation change.
 *
 * This is deliberately not a snapshot effect. The AGSL pass is installed on the layer that holds
 * *both* presentations together, so every frame the actual cross-fading content is rasterised once
 * and displaced as a single image: the incoming page is deformed as it arrives, the outgoing page is
 * deformed as it leaves, and neither is frozen. That is what makes it read as geometry changing
 * rather than "the old frame wobbles slightly while it fades".
 *
 * The field is a superposition of a radial dolly (UV scale about the artwork's centre), a strong
 * vertical peel shear, multi-frequency ripple terms on both axes and a directional lean. The
 * amplitudes are set so the deformation is unmistakable at the midpoint but returns exactly to the
 * identity at both ends — progress 0 and 1 are pixel-identical to the ordinary rendering, so the
 * transition starts and ends on exact frames.
 *
 * ## Interruptibility
 *
 * The container is live, so a reversal needs no special handling: the second toggle makes the
 * `AnimatedContent` swap the other way inside the still-running warp, and the field keeps easing
 * through its envelope. Nothing is captured and nothing can be left frozen on screen.
 *
 * ## Fallback
 *
 * Runtime shaders need API 33+, a hardware-accelerated window and the system animation scale on
 * (`areAnimatorsEnabled`). If any of those fails — or the shader/RenderEffect cannot be built —
 * [rememberTransitionWarp] returns an inactive state with no effect installed, the container renders
 * untouched, and the player keeps its plain 560 ms cross-fade.
 *
 * ## Cost
 *
 * One full-screen shader pass per frame for the 720 ms transition only: one texture sample and a few
 * tens of ALU per pixel, no allocations. When the state is inactive no `RenderEffect` is installed
 * at all, so steady-state rendering is exactly as before.
 */
internal object TransitionWarpRules {

    /**
     * Warp length. Longer than the 560 ms cross-fade it replaces because a geometric deformation
     * needs time to be read; at 720 ms the peak still arrives at 360 ms, where the eye starts
     * judging the animation.
     */
    const val DURATION_MS = 720L

    /**
     * The smallest radial UV scale, reached at the midpoint: 0.90 samples a 90 % window and draws it
     * to full screen, i.e. a 1.11× dolly-in. Below ~0.85 starts to feel like a zoom rather than a
     * warp; above ~0.95 stops being visible.
     */
    const val PEAK_RADIAL_SCALE = 0.90f

    /**
     * Peak horizontal peel shear, in dp-equivalent at the envelope's peak: the top and bottom of the
     * frame are pushed in opposite directions, which is what makes it read as a rubber sheet.
     */
    const val PEEL_DP = 72f

    /**
     * Peak ripple amplitude, in dp-equivalent. The ripple is what breaks a uniform wobble into
     * different deformation at different parts of the frame ("mesh" rather than "shiver").
     */
    const val RIPPLE_DP = 32f

    /** Peak directional lean, in dp-equivalent, along the toggle's direction. */
    const val DRAG_DP = 40f

    /**
     * **The one number to raise for "more".** Multiplies every displacement (peel, ripple, lean)
     * and the radial-dolly delta, while leaving the timing and the zero-at-both-ends envelope
     * intact. 1.0 is the tuned default; 1.3 is dramatic, 0.6 is subtle.
     */
    const val AMPLITUDE_SCALE = 1f

    /** Two ripple waves per axis at different frequencies, so regions deform differently. */
    private const val RIPPLE_FREQ_Y1 = 9f
    private const val RIPPLE_FREQ_Y2 = 21f
    private const val RIPPLE_FREQ_X1 = 7f
    private const val RIPPLE_FREQ_X2 = 17f

    /** Ripple phase progress over the whole warp. */
    private const val PHASE_TURNS = 1.25f

    /**
     * The displacement envelope: `0` at both ends, `1` at the midpoint, smoothstep on each half.
     * Symmetric, so a reversal at any point leaves the same deformation magnitude.
     */
    fun envelope(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        val half = if (p < 0.5f) p * 2f else (1f - p) * 2f
        return smoothStep01(half)
    }

    /** Radial UV scale for [progress]: 1 at the ends, [PEAK_RADIAL_SCALE] at the midpoint. */
    fun radialScale(progress: Float): Float =
        1f - (1f - PEAK_RADIAL_SCALE) * AMPLITUDE_SCALE * envelope(progress)

    /** How much of a base amplitude is applied at [progress] (`0..AMPLITUDE_SCALE`). */
    fun amplitudeFactor(progress: Float): Float = AMPLITUDE_SCALE * envelope(progress)

    /** A base displacement in dp, scaled for [progress]. Pure, so tests can pin proportionality. */
    fun displacementDp(baseDp: Float, progress: Float): Float = baseDp * amplitudeFactor(progress)

    /** Ripple phase in radians; advances monotonically over the warp. */
    fun wavePhase(progress: Float): Float =
        progress.coerceIn(0f, 1f) * 2f * PI.toFloat() * PHASE_TURNS

    /** Field lean sign: +1 when the target is the lyrics page, −1 when leaving it. */
    fun direction(targetShowLyrics: Boolean): Float = if (targetShowLyrics) 1f else -1f

    /** Progress of the warp, clamped to `[0,1]`; a negative start reads as finished. */
    fun progress(startedAtMs: Long, nowMs: Long): Float {
        if (startedAtMs < 0L) return 1f
        return ((nowMs - startedAtMs).toFloat() / DURATION_MS).coerceIn(0f, 1f)
    }

    fun rippleFrequencyY(index: Int): Float = if (index == 0) RIPPLE_FREQ_Y1 else RIPPLE_FREQ_Y2

    fun rippleFrequencyX(index: Int): Float = if (index == 0) RIPPLE_FREQ_X1 else RIPPLE_FREQ_X2

    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return smoothStep01(t)
    }

    private fun smoothStep01(t: Float): Float = t * t * (3f - 2f * t)
}

/**
 * One [RuntimeShader] and its [RenderEffect]; constant amplitudes are installed once, per-frame
 * values are written from the draw phase (a handful of float uniforms, no allocation).
 */
internal class TransitionWarpEffect private constructor(
    private val shader: RuntimeShader,
    val renderEffect: RenderEffect,
) {
    private var installedWidth = -1
    private var installedHeight = -1

    fun configure(
        strength: Float,
        radialScale: Float,
        center: Offset,
        direction: Float,
        phase: Float,
        width: Int,
        height: Int,
    ) {
        shader.setFloatUniform("uStrength", strength)
        shader.setFloatUniform("uRadialScale", radialScale)
        shader.setFloatUniform("uCenter", center.x, center.y)
        shader.setFloatUniform("uDirection", direction)
        shader.setFloatUniform("uPhase", phase)
        if (width != installedWidth || height != installedHeight) {
            shader.setFloatUniform("uSize", width.toFloat(), height.toFloat())
            installedWidth = width
            installedHeight = height
        }
    }

    companion object {
        /** @return the effect, or `null` when this driver cannot build it. */
        fun create(peelPx: Float, ripplePx: Float, dragPx: Float): TransitionWarpEffect? {
            return runCatching {
                val shader = RuntimeShader(SHADER_SOURCE)
                shader.setFloatUniform("uPeel", peelPx)
                shader.setFloatUniform("uRipple", ripplePx)
                shader.setFloatUniform("uDrag", dragPx)
                shader.setFloatUniform("uSize", 1f, 1f)
                val effect = android.graphics.RenderEffect
                    .createRuntimeShaderEffect(shader, CONTENT_UNIFORM)
                    .asComposeRenderEffect()
                TransitionWarpEffect(shader, effect)
            }.getOrNull()
        }
    }
}

/**
 * The warp's timeline and its current field values.
 *
 * [begin] opens a warp at a frame boundary, [tick] advances it from a frame clock, and the modifier
 * reads [active]/[progress] in its draw phase so a tick invalidates the container layer without
 * recomposing the player.
 */
@Stable
internal class TransitionWarpState internal constructor(val effect: TransitionWarpEffect?) {
    var active by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var center by mutableStateOf(Offset(0.5f, 0.45f))
        private set
    private var direction = 1f
    private var startedAtMs = -1L

    fun begin(nowMs: Long, targetShowLyrics: Boolean, center: Offset) {
        if (effect == null) return
        this.center = Offset(center.x.coerceIn(0.05f, 0.95f), center.y.coerceIn(0.05f, 0.95f))
        direction = TransitionWarpRules.direction(targetShowLyrics)
        startedAtMs = nowMs
        progress = 0f
        active = true
    }

    /** Advances the warp; @return true while it is still running. */
    fun tick(nowMs: Long): Boolean {
        if (!active) return false
        val p = TransitionWarpRules.progress(startedAtMs, nowMs)
        progress = p
        if (p >= 1f) {
            active = false
            return false
        }
        return true
    }

    fun cancel() {
        active = false
    }

    fun currentDirection(): Float = direction
}

/**
 * Installs the warp on this layer. The effect is only present while a warp is active, so the
 * steady-state and fallback paths render exactly as before (no extra offscreen rasterisation).
 */
internal fun Modifier.transitionWarp(state: TransitionWarpState): Modifier {
    // The layer is only created while a warp is running, so steady-state rendering is untouched and
    // the fallback (no effect) is a plain, unmodified modifier chain.
    if (state.effect == null || !state.active) return this
    return graphicsLayer {
        val effect = state.effect
        val p = state.progress
        if (state.active && effect != null) {
            effect.configure(
                strength = TransitionWarpRules.envelope(p),
                radialScale = TransitionWarpRules.radialScale(p),
                center = state.center,
                direction = state.currentDirection(),
                phase = TransitionWarpRules.wavePhase(p),
                width = size.width.toInt(),
                height = size.height.toInt(),
            )
            renderEffect = effect.renderEffect
        } else {
            renderEffect = null
        }
    }
}

/**
 * Creates the warp state and its frame clock.
 *
 * The clock only runs while [TransitionWarpState.active]; idle frames cost nothing.
 */
@Composable
internal fun rememberTransitionWarp(): TransitionWarpState {
    val view = LocalView.current
    val density = LocalDensity.current
    val animatorsOn = remember {
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
    val effect = remember(animatorsOn, density.density) {
        if (!isRuntimeShaderSupported() || !view.isHardwareAccelerated || !animatorsOn) {
            null
        } else {
            runCatching {
                TransitionWarpEffect.create(
                    peelPx = with(density) { TransitionWarpRules.PEEL_DP.dp.toPx() },
                    ripplePx = with(density) { TransitionWarpRules.RIPPLE_DP.dp.toPx() },
                    dragPx = with(density) { TransitionWarpRules.DRAG_DP.dp.toPx() },
                )
            }.getOrNull()
        }
    }
    val state = remember(effect) { TransitionWarpState(effect) }
    LaunchedEffect(state.active) {
        if (!state.active) return@LaunchedEffect
        while (isActive) {
            val frameNs = withFrameNanos { it }
            if (!state.tick(frameNs / 1_000_000L)) break
        }
    }
    return state
}

/**
 * AGSL displacement pass over the live transition container.
 *
 * One sample per pixel. `uStrength` (the envelope) scales the whole field, `uRadialScale` is the
 * dolly about `uCenter` (the artwork's centre), and the peel/ripple/lean terms add a field that
 * varies across the frame. Coordinates are clamped to the texture, so no sample can leave it; the
 * container is opaque, so there are no black borders to hide.
 */
private const val SHADER_SOURCE = """
uniform shader content;
uniform float2 uSize;
uniform float uStrength;
uniform float uRadialScale;
uniform float2 uCenter;
uniform float uDirection;
uniform float uPhase;
uniform float uPeel;
uniform float uRipple;
uniform float uDrag;

half4 main(float2 coordinate) {
    float2 uv = coordinate / uSize;

    // 1. Radial dolly about the artwork's centre: 1 at the ends, uRadialScale at the midpoint.
    uv = uCenter + (uv - uCenter) * uRadialScale;

    // 2. Vertical peel shear: top and bottom pushed in opposite directions.
    uv.x += uStrength * (uPeel / uSize.x) * (uv.y - uCenter.y) * 2.0;

    // 3. Two ripple waves per axis: different parts of the frame stretch differently.
    float edge = smoothstep(0.25, 0.85, length(uv - uCenter) * 1.41421356);
    float rippleY = sin(uv.y * 9.0 + uPhase) * 0.62 + sin(uv.y * 21.0 - uPhase * 1.7 + 1.3) * 0.38;
    float rippleX = sin(uv.x * 7.0 - uPhase * 0.9) * 0.58 + sin(uv.x * 17.0 + uPhase * 1.4) * 0.42;
    uv.x += uStrength * (uRipple / uSize.x) * rippleY * (0.30 + 0.70 * edge);
    uv.y += uStrength * (uRipple * 0.7 / uSize.y) * rippleX * edge;

    // 4. Directional lean: the whole field leans the way the toggle is going.
    uv.x += uStrength * uDirection * (uDrag / uSize.x) * (0.25 + 0.75 * edge);

    // 5. Never sample outside the texture.
    float2 samplePosition = clamp(uv, float2(0.0), float2(1.0)) * uSize;
    samplePosition = clamp(samplePosition, float2(0.0), uSize - float2(1.0));
    return content.eval(samplePosition);
}
"""

private const val CONTENT_UNIFORM = "content"
