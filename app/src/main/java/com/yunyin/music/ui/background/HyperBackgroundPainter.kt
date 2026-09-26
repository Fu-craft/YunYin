package com.yunyin.music.ui.background

import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Drives the animated background shader.
 *
 * Direct Kotlin port of the approach used by NeriPlayer's `BgEffectPainter` (itself derived
 * from HyperCeiler): a single AGSL program in `assets/shaders/hyper_background_effect.glsl`
 * compiled by the platform with [RuntimeShader], with every visual parameter supplied as a
 * uniform.
 *
 * The effect is a set of five soft colour fields blended by an inverse-square weight, with the
 * sampling coordinate displaced by beat/motion waves. It is **not** a blurred cover: the
 * artwork only contributes the five palette colours (see [DynamicBackgroundPalette]).
 *
 * Audio reactivity is expressed through derived, smoothed uniforms — [uLevelEase],
 * [uBeatEase], [uMotionEase], [uZoom], [uColorPulse] — computed from the raw level/beat in
 * [setReactive]. Feeding raw values straight in would make the screen flicker.
 *
 * Requires API 33 for `android.graphics.RuntimeShader`.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class HyperBackgroundPainter(context: Context) {

    val shader: RuntimeShader

    private val uResolution = floatArrayOf(1f, 1f)

    private var animTime = System.nanoTime() / 1.0E9f

    /** Region remap for the colour field. Full screen by default. */
    private var bound = floatArrayOf(0f, 0f, 1f, 1f)

    /** Five colour-field centres: x, y, radius. */
    private var points = floatArrayOf(
        0.52f, 0.46f, 0.92f,
        0.14f, 0.32f, 0.74f,
        0.92f, 0.30f, 0.76f,
        0.26f, 0.88f, 0.80f,
        0.84f, 0.86f, 0.84f,
    )

    /** Five palette colours as RGBA; alpha is used as a blend weight. */
    private var colors = floatArrayOf(
        0.68f, 0.82f, 0.98f, 1f,
        0.96f, 0.85f, 0.74f, 1f,
        0.94f, 0.76f, 0.88f, 1f,
        0.74f, 0.72f, 0.94f, 1f,
        0.80f, 0.88f, 0.92f, 1f,
    )

    private var saturateOffset = 0.2f
    private var lightOffset = 0.1f

    private var musicLevel = 0f
    private var beat = 0f
    private var reactiveDirty = true

    private var levelEase = 0f
    private var beatEase = 0f
    private var motionEase = 0f
    private var zoom = 1f
    private var colorPulse = 0f
    private val globalMotion = floatArrayOf(0f, 0f)

    private val animatedPoints = FloatArray(POINT_COUNT * POINT_STRIDE)

    init {
        val source = context.assets.open("shaders/hyper_background_effect.glsl")
            .bufferedReader()
            .use { it.readText() }
        shader = RuntimeShader(source).apply {
            setFloatUniform("uTranslateY", 0f)
            setFloatUniform("uColors", colors)
            setFloatUniform("uSaturateOffset", saturateOffset)
            setFloatUniform("uBound", bound)
            setFloatUniform("uAlphaMulti", 1f)
            setFloatUniform("uLightOffset", lightOffset)
            setFloatUniform("uResolution", uResolution)
        }
        updateReactiveUniforms()
        updateGlobalMotion()
        updateAnimatedPoints()
    }

    /** Raw audio inputs, 0..1. Cheap to call every frame; no-op when unchanged. */
    fun setReactive(level: Float, beat: Float) {
        val boundedLevel = level.coerceIn(0f, 1f)
        val boundedBeat = beat.coerceIn(0f, 1f)
        if (musicLevel == boundedLevel && this.beat == boundedBeat) return
        musicLevel = boundedLevel
        this.beat = boundedBeat
        reactiveDirty = true
    }

    fun setAnimTime(seconds: Float) {
        animTime = seconds
    }

    /** Uploads the per-frame uniforms. Call once per rendered frame. */
    fun updateMaterials() {
        shader.setFloatUniform("uAnimTime", animTime)
        if (reactiveDirty) updateReactiveUniforms()
        updateGlobalMotion()
        updateAnimatedPoints()
    }

    fun setResolution(width: Float, height: Float) {
        if (uResolution[0] == width && uResolution[1] == height) return
        uResolution[0] = width
        uResolution[1] = height
        shader.setFloatUniform("uResolution", uResolution)
    }

    /**
     * Remaps the colour field onto a band of the viewport.
     *
     * This is what produces the effect's characteristic elongated diagonal bands: the field is
     * laid out over a band roughly [REFERENCE_HEIGHT_DP] tall and then stretched to fill the
     * view, rather than being spread evenly over the whole screen (which reads as a soft blur).
     *
     * Mirrors the reference implementation: when the view is taller than the band the band is
     * anchored to the bottom and stretched vertically; in a wider-than-tall view it is centred
     * horizontally and stretched to the view's height.
     */
    fun setBoundForViewport(widthPx: Float, heightPx: Float, density: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        val bandHeightPx = REFERENCE_HEIGHT_DP * density
        val fraction = bandHeightPx / heightPx

        val next = if (widthPx <= bandHeightPx) {
            floatArrayOf(0f, 1f - fraction, 1f, fraction)
        } else {
            floatArrayOf(((widthPx - bandHeightPx) / 2f) / widthPx, 1f - fraction, bandHeightPx / widthPx, fraction)
        }

        if (next.contentEquals(bound)) return
        bound = next
        shader.setFloatUniform("uBound", bound)
    }

    /** Sets the five palette colours as RGBA, plus the tone offsets for the current theme. */
    fun setPalette(colors: FloatArray, saturateOffset: Float, lightOffset: Float) {
        this.colors = colors
        this.saturateOffset = saturateOffset
        this.lightOffset = lightOffset
        shader.setFloatUniform("uColors", colors)
        shader.setFloatUniform("uSaturateOffset", saturateOffset)
        shader.setFloatUniform("uLightOffset", lightOffset)
    }

    /** Idle point layout: the five fields spread across the screen. */
    fun setIdlePoints() {
        points = floatArrayOf(
            0.52f, 0.46f, 0.92f,
            0.14f, 0.32f, 0.74f,
            0.92f, 0.30f, 0.76f,
            0.26f, 0.88f, 0.80f,
            0.84f, 0.86f, 0.84f,
        )
    }

    // ------------------------------------------------------------------ internals

    private fun updateReactiveUniforms() {
        levelEase = smoothStep(0.04f, 0.82f, musicLevel)
        beatEase = smoothStep(0.03f, 0.62f, beat)
        motionEase = clamp01(0.42f * levelEase + 0.82f * beatEase)
        zoom = 1f + 0.024f * levelEase + 0.105f * beatEase
        colorPulse = clamp01(0.68f * levelEase + 0.32f * beatEase)
        shader.setFloatUniform("uLevelEase", levelEase)
        shader.setFloatUniform("uBeatEase", beatEase)
        shader.setFloatUniform("uMotionEase", motionEase)
        shader.setFloatUniform("uZoom", zoom)
        shader.setFloatUniform("uColorPulse", colorPulse)
        reactiveDirty = false
    }

    private fun updateGlobalMotion() {
        if (motionEase == 0f && globalMotion[0] == 0f && globalMotion[1] == 0f) return
        globalMotion[0] = motionEase * 0.0060f * kotlin.math.sin(animTime * 1.9f)
        globalMotion[1] = motionEase * 0.0060f * kotlin.math.cos(animTime * 1.6f)
        shader.setFloatUniform("uGlobalMotion", globalMotion)
    }

    private fun updateAnimatedPoints() {
        val pointOffset = POINT_OFFSET_BASE + 0.022f * levelEase + 0.108f * beatEase
        val radiusMulti = 1f + 0.045f * levelEase + 0.220f * beatEase
        for (i in 0 until POINT_COUNT) {
            val offset = i * POINT_STRIDE
            var x = points[offset]
            var y = points[offset + 1]
            val radius = points[offset + 2] * radiusMulti

            x += kotlin.math.sin(animTime + y) * pointOffset
            y += kotlin.math.cos(animTime + x) * pointOffset

            // Beats push the fields radially outward from the centre.
            val pushX = x - 0.5f + 1.0E-4f
            val pushY = y - 0.5f + 1.0E-4f
            val pushLength = kotlin.math.sqrt(pushX * pushX + pushY * pushY)
            val pushScale = if (pushLength > 0f) beatEase * 0.118f / pushLength else 0f

            animatedPoints[offset] = x + pushX * pushScale
            animatedPoints[offset + 1] = y + pushY * pushScale
            animatedPoints[offset + 2] = radius
        }
        shader.setFloatUniform("uPoints", animatedPoints)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        val t = clamp01((value - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    private fun clamp01(value: Float) = value.coerceIn(0f, 1f)

    private companion object {
        const val POINT_COUNT = 5
        const val POINT_STRIDE = 3
        const val POINT_OFFSET_BASE = 0.1f

        /** Layout height of the colour band, in dp, before it is stretched to the viewport. */
        const val REFERENCE_HEIGHT_DP = 416f * 1.3f
    }
}
