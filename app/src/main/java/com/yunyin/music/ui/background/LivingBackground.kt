package com.yunyin.music.ui.background

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yunyin.music.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The player's "living background": two or three heavily blurred copies of the current cover
 * drifting and turning behind the content, plus an optional slow breathing pulse.
 *
 * Why this reads better than a static blurred cover:
 *
 *  - A single blurred copy is a still image; the eye reads it as a screenshot. Several copies
 *    moving at *different very slow periods* (74–118 s), each turning in a different direction,
 *    never repeat a visible cycle, so the backdrop feels alive without ever asking for attention.
 *  - The motion is deliberately below the threshold where it competes with the artwork: a few
 *    percent of scale over a minute, a rotation measured in degrees per second, and opacity low
 *    enough that the field's colour is felt rather than seen.
 *  - On top of that, [BreathingRules] adds an occasional gentle expansion (≤10 %) so the field
 *    "breathes" with the music instead of drifting only.
 *
 * ## Cost control
 *
 * - **One frame clock for both ② and ③.** A single `withFrameNanos` loop samples a timestamp at
 *   **≤30 fps** and writes one `MutableLongState`; every value is read inside `graphicsLayer`
 *   blocks, so a tick invalidates layers and never recomposes this composable.
 * - **One blur pass**, not one per copy: the copies are stacked first and the whole stack is
 *   blurred once (`[LIVING_BLUR]`). The copies are drawn oversized (1.6–1.8×) so their own detail
 *   is already enlarged before the blur runs, which is what lets a moderate radius read as heavy
 *   blur.
 * - **No per-frame allocation**: the rules return primitive `Float`s from constant arrays; nothing
 *   in the draw path builds an object.
 * - **Frozen means free**: the clock only runs while the layer is visible, playing, and the player
 *   is not mid-presentation; a frozen clock invalidates nothing, so the stack costs no frames and
 *   no blur work while paused. The last sampled pose is kept, so nothing snaps.
 * - Measured budget is stated in the player integration: one full-screen blurred layer redrawn at
 *   ≤30 fps while playing, zero while paused or on the lyrics presentation.
 *
 * ## Integration with [HyperBackground]
 *
 * This does **not** replace the audio-reactive field: it is layered on top of it, at low opacity,
 * so the palette field keeps providing the base colour and the covers provide the album's own
 * pigment. Both are inside the material post-composite, so they share saturation/floor/dither.
 *
 * ## Reduced motion
 *
 * `ValueAnimator.areAnimatorsEnabled()` (the same check [HyperBackground] and the vinyl record use)
 * and playback state gate the clock. With animations off, the covers are drawn once at their rest
 * pose and the stack cross-fades instantly on a track change; nothing loops.
 */
@Composable
internal fun LivingCoverBackground(
    cover: ImageBitmap?,
    /** Whether this presentation shows the stack at all (the artwork presentation only). */
    visible: Boolean,
    /** False while the player is paused or the two presentations are cross-fading. */
    active: Boolean,
    /** The player's background dim, 0..1; the stack follows it so it cannot brighten the page. */
    dim: Float,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.CrossfadeDuration, easing = Motion.EaseOut)),
        exit = fadeOut(tween(Motion.CrossfadeDuration, easing = Motion.EaseOut)),
    ) {
        LivingCoverStack(cover = cover, active = active, dim = dim, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun LivingCoverStack(
    cover: ImageBitmap?,
    active: Boolean,
    dim: Float,
    modifier: Modifier,
) {
    val animatorsOn = remember {
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
    val clock = rememberBackgroundClock(active = active && animatorsOn)
    val crossfade = remember { CoverCrossfade() }
    var fadeJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(cover, active, animatorsOn) {
        // The cross-fade is measured on the same clock the poses are sampled from, so its
        // progress cannot disagree with the fade of each copy. When the clock is not running
        // (paused, animations off) the swap is instant — a fade nobody can see ticking is worse
        // than no fade at all.
        val startedAt = if (active && animatorsOn) withFrameNanos { it / 1_000_000L } else -1L
        crossfade.swap(cover, startedAt)
        fadeJob?.cancel()
        if (startedAt >= 0L && crossfade.previous != null) {
            fadeJob = launch {
                delay(CoverCrossfadeRules.DURATION_MS)
                crossfade.previous = null
            }
        }
    }

    // The stack is recorded at 1/[LIVING_DOWNSCALE] of the screen and scaled back up. Blur cost is
    // paid per pixel of the layer, not of the screen, so this is ~1/9 of a full-resolution blur of
    // the same visual radius — the cheapest way to get a genuinely soft, low-detail field.
    Box(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize(1f / LIVING_DOWNSCALE)
                .graphicsLayer {
                    // Breathing and dim are layer values: a tick invalidates the layer, never
                    // composition. `LIVING_DOWNSCALE` is folded into the same scale so the upscale
                    // and the pulse are one transform.
                    val breath = BreathingRules.scale(clock.value)
                    scaleX = LIVING_DOWNSCALE * breath
                    scaleY = LIVING_DOWNSCALE * breath
                    alpha = 1f - dim.coerceIn(0f, 1f) * DIM_INFLUENCE
                }
                .clipToBounds()
                .blur(LIVING_BLUR, edgeTreatment = BlurredEdgeTreatment.Unbounded),
        ) {
            val previous = crossfade.previous
            if (previous != null) {
                CoverCopies(previous, clock, crossfade.startedAtMs, incoming = false)
            }
            CoverCopies(crossfade.current, clock, crossfade.startedAtMs, incoming = true)
        }
    }
}

/**
 * One copy of [bitmap] per instance of [CoverOrbitRules.COPY_COUNT], each posed by the rules for the
 * sampled moment. The outgoing bitmap (during a cross-fade) uses the same poses, so the two covers
 * dissolve into each other instead of sliding.
 */
@Composable
private fun CoverCopies(
    bitmap: ImageBitmap?,
    clock: State<Long>,
    startedAtMs: Long,
    incoming: Boolean,
) {
    if (bitmap == null) return
    for (index in 0 until CoverOrbitRules.COPY_COUNT) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val nowMs = clock.value
                    val progress = CoverCrossfadeRules.progress(startedAtMs, nowMs)
                    alpha = CoverOrbitRules.opacity(index) * if (incoming) progress else 1f - progress
                    rotationZ = CoverOrbitRules.rotationDegrees(index, nowMs)
                    val pose = CoverOrbitRules.scale(index, nowMs)
                    scaleX = pose
                    scaleY = pose
                    translationX = CoverOrbitRules.orbitX(index, nowMs) * size.width
                    translationY = CoverOrbitRules.orbitY(index, nowMs) * size.height
                },
        )
    }
}

/**
 * Pose rules for the drifting cover copies.
 *
 * Pure and unit-tested: every function is deterministic, bounded, and periodic where it claims to
 * be, so the background cannot drift off screen or start depending on composition order. The
 * constants are the whole tuning surface — edit them here, not at the call site.
 */
internal object CoverOrbitRules {

    /** Two would already read as "a couple of copies"; three gives the field enough asymmetry. */
    const val COPY_COUNT = 3

    /**
     * One turn period per copy, in milliseconds: 118 s, 91 s, 74 s.
     *
     * The ratios (≈1.6 and ≈1.23) are deliberately not integer multiples, so the three copies never
     * line up again within a listening session. All three stay inside the 70–120 s band where a
     * copy moves a couple of degrees per second — clearly alive, never distracting.
     */
    private val PERIOD_MS = longArrayOf(118_000L, 91_000L, 74_000L)

    /** Turn direction: two clockwise, one counter-clockwise, so the field does not rotate as a block. */
    private val DIRECTION = intArrayOf(1, -1, 1)

    /** Starting angle as a fraction of a turn, so the copies never start stacked. */
    private val PHASE = floatArrayOf(0.00f, 0.31f, 0.67f)

    /** Starting point of the orbit, offset from the turn phase to decouple spin from drift. */
    private val ORBIT_PHASE = floatArrayOf(0.13f, 0.58f, 0.86f)

    /**
     * Drift radius as a fraction of the screen, per axis. Small on purpose: the copies are 1.6–1.8×
     * the screen, and moving them by ~10 % of its width is enough parallax to be felt.
     */
    private val ORBIT_RADIUS_X = floatArrayOf(0.09f, 0.13f, 0.07f)
    private val ORBIT_RADIUS_Y = floatArrayOf(0.05f, 0.08f, 0.06f)

    /**
     * Rest scale of each copy. All are above √2 so a copy still covers the screen at any rotation,
     * even with the orbit offset added; the three differ so the copies never coincide.
     */
    private val BASE_SCALE = floatArrayOf(1.62f, 1.80f, 1.56f)

    /** Faint per-copy scale wobble (0.8–1.6 %), independent of the shared breathing pulse. */
    private val SCALE_BREATH = floatArrayOf(0.012f, 0.008f, 0.016f)

    /**
     * Opacity per copy. Low enough that the copies tint the field rather than being read as
     * pictures; the stack is blurred once on top of this.
     */
    private val OPACITY = floatArrayOf(0.20f, 0.13f, 0.16f)

    private fun copyIndex(index: Int): Int = index.coerceIn(0, COPY_COUNT - 1)

    /** Position within one period, 0..1, defined for negative times as well. */
    private fun fraction(timeMs: Long, periodMs: Long): Float {
        val wrapped = ((timeMs % periodMs) + periodMs) % periodMs
        return wrapped / periodMs.toFloat()
    }

    /** Absolute rotation in degrees, always in `[0, 360)`. */
    fun rotationDegrees(index: Int, timeMs: Long): Float {
        val i = copyIndex(index)
        val turn = PHASE[i] + DIRECTION[i] * fraction(timeMs, PERIOD_MS[i])
        val degrees = (turn % 1f) * 360f
        return (degrees + 360f) % 360f
    }

    /** Horizontal drift as a fraction of the screen width; bounded by [ORBIT_RADIUS_X]. */
    fun orbitX(index: Int, timeMs: Long): Float {
        val i = copyIndex(index)
        val phase = fraction(timeMs, PERIOD_MS[i]) + ORBIT_PHASE[i]
        return ORBIT_RADIUS_X[i] * cos(TWO_PI * phase).toFloat()
    }

    /** Vertical drift as a fraction of the screen height; same bound rule as [orbitX]. */
    fun orbitY(index: Int, timeMs: Long): Float {
        val i = copyIndex(index)
        // Half the angular rate on the vertical axis, so the path is a slow ellipse rather than a
        // straight diagonal.
        val phase = fraction(timeMs, PERIOD_MS[i]) * 0.5f + ORBIT_PHASE[i]
        return ORBIT_RADIUS_Y[i] * sin(TWO_PI * phase).toFloat()
    }

    /** Scale of one copy at [timeMs]: its rest scale plus a faint wobble. */
    fun scale(index: Int, timeMs: Long): Float {
        val i = copyIndex(index)
        val phase = fraction(timeMs, PERIOD_MS[i]) + ORBIT_PHASE[i]
        return BASE_SCALE[i] * (1f + SCALE_BREATH[i] * sin(TWO_PI * phase).toFloat())
    }

    /** Constant opacity of one copy; the cross-fade multiplies it. */
    fun opacity(index: Int): Float = OPACITY[copyIndex(index)]

    /** The static rest pose used when animations are off (time 0). */
    fun restRotationDegrees(index: Int): Float = rotationDegrees(index, 0L)
}

/** Cross-fade between the previous and the current cover artwork. */
internal object CoverCrossfadeRules {

    /**
     * Track-change cross-fade length. 360 ms: between the app's content cross-fade (280) and its
     * sheet timing, because a backdrop swap is felt rather than watched, and the covers are blurred
     * — a fast fade would read as a flash of a different colour.
     */
    const val DURATION_MS = 360L

    /**
     * Progress of a cross-fade started at [startedAtMs].
     *
     * `-1` means "no cross-fade": the current artwork is already at full weight. Otherwise a
     * smoothstep over [DURATION_MS], so the swap eases at both ends.
     */
    fun progress(startedAtMs: Long, nowMs: Long): Float {
        if (startedAtMs < 0L) return 1f
        val raw = ((nowMs - startedAtMs).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        return raw * raw * (3f - 2f * raw)
    }
}

/**
 * The "breathing" pulse: two rectified sines raised to high powers, so the field is still for most
 * of the cycle and swells briefly twice per period.
 *
 * The shape matters more than the exact frequency. Raising a sine to the 12th power keeps the
 * value under 10 % of its peak for most of the cycle, which is why the effect reads as an
 * occasional breath rather than a throb; the second, half-rate term (weight 0.14) is what stops
 * the two swells from looking metronomic.
 */
internal object BreathingRules {

    /** Peak scale increase: ≤10 % keeps the field's edge off the screen edges and reads as air. */
    const val AMPLITUDE = 0.10f

    /** Primary pulse rate. 1.15 Hz ≈ one swell every 0.87 s — a slow musical breath. */
    const val PRIMARY_HZ = 1.15f

    /** Secondary pulse at exactly half the primary rate, for the four-bar feel. */
    const val SECONDARY_HZ = 0.575f

    private const val PRIMARY_WEIGHT = 0.86f
    private const val SECONDARY_WEIGHT = 1f - PRIMARY_WEIGHT
    private const val SECONDARY_PHASE = 0.70f
    private const val PRIMARY_EXPONENT = 12
    private const val SECONDARY_EXPONENT = 8

    private fun pulse(value: Float, exponent: Int): Float {
        if (value <= 0f) return 0f
        var out = 1f
        repeat(exponent) { out *= value }
        return out
    }

    /** Waveform in `[0,1]`: near zero most of the time, peaking at 1 on the primary swell. */
    fun wave(timeMs: Long): Float {
        val seconds = timeMs / 1000.0
        val primary = pulse(sin(TWO_PI * PRIMARY_HZ * seconds).toFloat(), PRIMARY_EXPONENT)
        val secondary = pulse(
            sin(TWO_PI * SECONDARY_HZ * seconds + SECONDARY_PHASE).toFloat(),
            SECONDARY_EXPONENT,
        )
        return (PRIMARY_WEIGHT * primary + SECONDARY_WEIGHT * secondary).coerceIn(0f, 1f)
    }

    /** Scale applied to the cover stack: `1 .. 1 + AMPLITUDE`. */
    fun scale(timeMs: Long): Float = 1f + AMPLITUDE * wave(timeMs)
}

/** Shared 2π as a Double, used by both rule sets without recomputing it per frame. */
private const val TWO_PI = 2.0 * PI

/**
 * The one frame clock for the living background, capped at 30 fps.
 *
 * Returns a [State] rather than a `Long` so the caller can read it *inside* `graphicsLayer`: the
 * tick then invalidates layers only. Reading it in composition would recompose this subtree 30
 * times a second.
 */
@Composable
private fun rememberBackgroundClock(active: Boolean): State<Long> {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var nextFrameNs = 0L
        while (isActive) {
            withFrameNanos { frameNs ->
                if (nextFrameNs == 0L || frameNs >= nextFrameNs) {
                    clock.longValue = frameNs / 1_000_000L
                    nextFrameNs = if (nextFrameNs == 0L) {
                        frameNs + CLOCK_INTERVAL_NS
                    } else {
                        nextFrameNs + CLOCK_INTERVAL_NS
                    }
                    // After a long stall (backgrounded, GC) do not burn a burst of catch-up ticks.
                    if (frameNs - nextFrameNs > CLOCK_INTERVAL_NS * 2) {
                        nextFrameNs = frameNs + CLOCK_INTERVAL_NS
                    }
                }
            }
        }
    }
    return clock
}

/** 30 fps: a backdrop this slow needs nothing faster, and the blur pass is the frame's cost. */
private const val CLOCK_INTERVAL_NS = 1_000_000_000L / 30L

/**
 * Resolution the cover stack is recorded at: 1/3 of the screen in each axis.
 *
 * The stack is blurred *inside* this smaller layer and then scaled up, which cuts the blur and the
 * three image draws to ~1/9 of the full-screen cost while looking the same: the copies are already
 * enlarged 1.6–1.8× and blurred, so the upscale adds no detail the eye could miss.
 */
private const val LIVING_DOWNSCALE = 3f

/**
 * Blur radius of the stack, measured in the downscaled layer.
 *
 * 8 dp at 1/[LIVING_DOWNSCALE] resolution is visually ~24 dp at screen resolution, which is the
 * "heavy blur" this layer needs; asking for 24 dp on the small layer would smear the whole field.
 */
private val LIVING_BLUR = 8.dp

/** How much of the player's background dim the stack follows: it must not brighten immersive mode. */
private const val DIM_INFLUENCE = 0.6f

/** State of the artwork swap. Kept separate from the composable so the rules stay testable. */
@Stable
private class CoverCrossfade {
    var current by mutableStateOf<ImageBitmap?>(null)
        private set
    var previous by mutableStateOf<ImageBitmap?>(null)
    var startedAtMs by mutableLongStateOf(-1L)
        private set

    /**
     * Installs [next], starting a cross-fade from the current artwork when there was one and the
     * clock is running. A negative [nowMs] means "no animation available": the swap is instant.
     */
    fun swap(next: ImageBitmap?, nowMs: Long) {
        if (next === current) return
        val hadCurrent = current
        if (hadCurrent == null || nowMs < 0L) {
            previous = null
            startedAtMs = -1L
        } else {
            previous = hadCurrent
            startedAtMs = nowMs
        }
        current = next
    }
}
