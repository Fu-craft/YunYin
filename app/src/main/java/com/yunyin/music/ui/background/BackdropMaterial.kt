package com.yunyin.music.ui.background

import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import com.kyant.backdrop.isRuntimeShaderSupported
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Post-composite for the player's backdrop, as an AGSL `RenderEffect`.
 *
 * The player already draws its backdrop twice (a blurred copy under a sharp, masked copy) so the
 * control panel reads as frosted glass. That arrangement has two visible quality problems:
 *
 *  1. **Grey wash.** Blurring the colour field averages its pigments towards their mean, which for
 *     a field whose palette is already muted by the palette builder comes out grey. Over darker
 *     artwork the backdrop then sits close to the page black and the glass loses its separation
 *     from what is behind it.
 *  2. **Banding.** The field is a slow ramp across ~2000 px of screen; at 8 bits per channel the
 *     dark end of that ramp shows visible contour stripes, most obviously on OLED panels.
 *
 * This file fixes both in a single post-processing pass over the composited background: a mild
 * saturation lift, a black-floor remap, a light scrim where the picture is bright and detailed, and
 * interleaved-gradient dither to break the contours. It has to run *after* the two backdrop copies
 * are composited, because the banding appears in the final ramp, not in either source copy.
 *
 * ## Why a `RenderEffect` and not a shader inside [HyperBackground]
 *
 * The two backdrop copies are separate composables (one blurred, one masked). Adding the effect to
 * the shared parent means one pass over the finished backdrop instead of one per copy, it covers
 * the living cover layer as well, and it costs **no recomposition**: the effect is installed on the
 * existing graphics layer and re-runs only when that layer is redrawn.
 *
 * ## Capability and fallback
 *
 * `isRuntimeShaderSupported()` (the project's existing capability check, from the backdrop library)
 * plus a hardware-accelerated view are both required. When either is false — or when the shader
 * fails to build on a particular driver — [rememberBackdropMaterialEffect] returns `null` and
 * [backdropMaterial] adds **no modifier at all**, so the backdrop is byte-for-byte what it is today.
 * A compile failure is caught and degrades silently; it can never crash the player.
 *
 * The constants below are the only tuning surface. They are exposed as pure functions
 * ([BackdropMaterialRules]) so the same arithmetic can be unit-tested against the shader's intent.
 */
internal object BackdropMaterialRules {

    /**
     * Saturation multiplier around Rec.709 luminance.
     *
     * Problem solved: the blurred field reads grey. A lift restores the pigment the blur averaged
     * away. 1.28 rather than the 1.4–1.5 range such compositors often use, because the palette
     * builder already softens saturated covers (`saturateOffset`); 1.4 on top of that turns album
     * art garish, and the point here is a calm backdrop, not a vivid one.
     */
    const val SATURATION = 1.28f

    /**
     * Lower bound of the remapped tone range: `0 → BLACK_FLOOR`, `1 → 1`.
     *
     * Problem solved: the field collapses into the page black over dark artwork, so the frosted
     * panel and the page behind it merge into one grey smear. Lifting the floor by 5 % keeps the
     * backdrop a shade above true black, which is what lets the glass edge read as glass. Higher
     * than ~0.06 starts to look washed out in dark themes, hence 0.05.
     */
    const val BLACK_FLOOR = 0.05f

    /**
     * Maximum darkening of the scrim, applied only to bright *and* locally detailed regions.
     *
     * Problem solved: white title/lyric text loses contrast where the backdrop is bright (the
     * artwork's light side, a pale album cover). A flat scrim would also dull the calm areas, so
     * this one is weighted by luminance and by a cheap two-sample detail probe: smooth dark ramps
     * are left alone, busy bright patches are pulled down by up to 22 %.
     */
    const val SCRIM = 0.22f

    /**
     * Dither amplitude in units of one 8-bit step (so 1.0 = ±½ LSB of the final quantisation).
     *
     * Problem solved: contour stripes in the dark gradient. The noise is a position hash, not a
     * random number: it is stable frame to frame, so the contour is broken up without the surface
     * shimmering while the backdrop animates.
     */
    const val DITHER_STRENGTH = 1f

    /** Distance, in pixels, of the detail probe used by the scrim weighting. */
    const val DETAIL_PROBE_PX = 2f

    /** Rec.709 luminance weights — the same ones the saturation lift rotates around. */
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** Sensitivity of the detail probe: how much a luminance step counts as "busy". */
    private const val DETAIL_GAIN = 6f

    fun luminance(r: Float, g: Float, b: Float): Float =
        r * LUMA_R + g * LUMA_G + b * LUMA_B

    /**
     * Lifts (or lowers) one channel away from the pixel's luminance.
     * `saturation == 1` is the identity; grey pixels are invariant for every value.
     */
    fun saturate(channel: Float, luma: Float): Float =
        luma + (channel - luma) * SATURATION

    /** Monotone remap of `[0,1]` onto `[BLACK_FLOOR,1]`; fixes both endpoints. */
    fun applyBlackFloor(channel: Float): Float =
        BLACK_FLOOR + channel * (1f - BLACK_FLOOR)

    /** `0..1` measure of how much the neighbourhood differs from this pixel. */
    fun detailOf(neighbourLuma: Float, luma: Float): Float =
        (abs(neighbourLuma - luma) * DETAIL_GAIN).coerceIn(0f, 1f)

    /** `1 - scrim`, i.e. the factor the tone is multiplied by. Never below `1 - SCRIM`. */
    fun scrimFactor(luma: Float, detail: Float): Float {
        val brightness = smoothstep(0.20f, 0.85f, luma)
        val weight = (0.35f + 0.65f * detail.coerceIn(0f, 1f)) * brightness
        return 1f - SCRIM * weight.coerceIn(0f, 1f)
    }

    /** Offset added to a channel for a `[0,1)` noise sample. Symmetric, at most ±½ LSB. */
    fun ditherOffset(noise: Float): Float =
        (noise.coerceIn(0f, 1f) - 0.5f) * (DITHER_STRENGTH / 255f)

    /** Hermite smoothstep, the same curve the shader uses for the scrim weight. */
    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge0 == edge1) return if (value < edge0) 0f else 1f
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Clamp helper so the Kotlin mirror can be read against the shader line for line. */
    fun clamp01(value: Float): Float = max(0f, min(1f, value))
}

/**
 * Installs the backdrop material on the existing graphics layer.
 *
 * `null` deliberately adds no modifier: the fallback is the current rendering, not an approximation
 * of it.
 */
internal fun Modifier.backdropMaterial(effect: RenderEffect?): Modifier =
    if (effect == null) this else graphicsLayer { renderEffect = effect }

/**
 * Builds the material effect once per capability state.
 *
 * @return `null` when runtime shaders are unavailable, the window is software-rendered, or the
 *     shader failed to compile on this driver. Callers fall back to the plain backdrop.
 */
@Composable
internal fun rememberBackdropMaterialEffect(): RenderEffect? {
    val view = LocalView.current
    val supported = isRuntimeShaderSupported() && view.isHardwareAccelerated
    return remember(supported) {
        if (supported) runCatching { buildBackdropMaterialEffect() }.getOrNull() else null
    }
}

/**
 * AGSL post-composite. Original code for this app: one input (`content`, bound by
 * `createRuntimeShaderEffect`), Rec.709 luminance, a saturation lift, a black-floor remap, a
 * luminance/detail weighted scrim, and an interleaved-gradient hash for the dither.
 */
private const val BACKDROP_MATERIAL_SHADER = """
uniform shader content;
uniform float uSaturation;
uniform float uFloor;
uniform float uScrim;
uniform float uDither;
uniform float uProbe;

float lumaOf(float3 color) {
    return dot(color, float3(0.2126, 0.7152, 0.0722));
}

float hermite(float edge0, float edge1, float value) {
    float t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

half4 main(float2 coordinate) {
    float3 color = content.eval(coordinate).rgb;
    float luma = lumaOf(color);

    // 1. Saturation lift around luminance: restores the pigment that the blur averaged away.
    color = mix(float3(luma), color, uSaturation);

    // 2. Black-floor remap: keep the field a shade above the page black.
    color = uFloor + color * (1.0 - uFloor);

    // 3. Scrim only where the picture is bright and locally detailed.
    float3 neighbour = content.eval(coordinate + float2(uProbe, uProbe)).rgb;
    float detail = clamp(abs(lumaOf(neighbour) - luma) * 6.0, 0.0, 1.0);
    float veil = uScrim * (0.35 + 0.65 * detail) * hermite(0.20, 0.85, luma);
    color *= 1.0 - veil;

    // 4. Position-hashed dither breaks the 8-bit contours without shimmering.
    float noise = fract(52.9829189 * fract(dot(coordinate, float2(0.06711056, 0.00583715))));
    color += (noise - 0.5) * uDither;

    return half4(clamp(color, 0.0, 1.0), 1.0);
}
"""

/** Uniform name `createRuntimeShaderEffect` binds the layer content to. */
private const val CONTENT_UNIFORM = "content"

private fun buildBackdropMaterialEffect(): RenderEffect {
    val shader = RuntimeShader(BACKDROP_MATERIAL_SHADER)
    shader.setFloatUniform("uSaturation", BackdropMaterialRules.SATURATION)
    shader.setFloatUniform("uFloor", BackdropMaterialRules.BLACK_FLOOR)
    shader.setFloatUniform("uScrim", BackdropMaterialRules.SCRIM)
    shader.setFloatUniform("uDither", BackdropMaterialRules.DITHER_STRENGTH / 255f)
    shader.setFloatUniform("uProbe", BackdropMaterialRules.DETAIL_PROBE_PX)
    return android.graphics.RenderEffect
        .createRuntimeShaderEffect(shader, CONTENT_UNIFORM)
        .asComposeRenderEffect()
}
