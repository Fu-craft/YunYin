// Portions ported from Lyricify-Backgrounds (Apache-2.0), © Lyricify contributors.
// Source: Lyricify.Backgrounds.AppleMusicInspired.Ios.Shared/Resources/AppleMusicIosBackground.hlsl
// and .../Ios.WinUI/AppleMusicIosRenderer.cs. AGSL/Compose reimplementation for YunYin.

package com.yunyin.music.ui.background

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.yunyin.music.core.player.effects.AudioReactive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Every visual constant of the Apple Music iOS material. Kept free of Android types so the table can
 * be pinned by a JVM test. Values are from Lyricify-Backgrounds' AppleMusicIosRenderer.cs.
 */
internal object AppleIosRules {
    const val MAX_ARTWORK_DIMENSION = 300
    const val BLUR_SURFACE_DOWNSAMPLE = 4
    const val GAUSSIAN_SIGMA = 42.5f
    const val LYRICS_BLUR_SIGMA = 42.5f
    const val ORDINARY_BLUR_SIGMA = 80f
    const val DARK_BEHIND_LYRICS_SCRIM = 0.4f
    const val LIGHT_APPEARANCE_SCRIM = 1f / 3f
    const val DITHER_STRENGTH = 1f
    const val PORTRAIT_TEXTURE_SCALE = 1f
    const val LANDSCAPE_TEXTURE_SCALE = 0.8f

    const val LAYER_0_SCALE = 1.4f
    const val LAYER_1_SCALE = 0.7f
    const val LAYER_2_SCALE = 0.7f
    const val LAYER_1_X = -0.25f
    const val LAYER_1_Y = 0.15f
    const val LAYER_2_X = 0.7f
    const val LAYER_2_Y = 0.7f
    const val LAYER_0_PERIOD_SECONDS = 120f
    const val LAYER_1_PERIOD_SECONDS = 70f
    const val LAYER_2_PERIOD_SECONDS = 90f
    const val LAYER_WEIGHT_0 = 0.5f
    const val LAYER_WEIGHT_1 = 0.25f
    const val LAYER_WEIGHT_2 = 0.25f

    const val MESH_PERIOD_SECONDS = 5f
    const val MESH_WARP_AMPLITUDE = 0.035f
    const val MESH_RIPPLE_AMPLITUDE = 0.004f
    const val COVER_FADE_SECONDS = 0.5f
    const val LYRICS_BLUR_FADE_SECONDS = 0.25f
    const val AUDIO_ROTATION_GAIN = 0.15f
    const val AUDIO_SCRIM_BRIGHTEN = 0.05f

    /** Held at the upstream default; no setting row. */
    const val RENDER_SCALE = 1.0f
    const val BLUR_SCALE = 1.0f

    /** App-side spectrum stand-in: we have a full-band level and a beat, not five FFT bands. */
    const val BASS_LEVEL_WEIGHT = 0.65f
    const val BASS_BEAT_WEIGHT = 0.35f
    const val IMAGE_PULSE_POWER_MIX = 0.1f
    const val IMAGE_PULSE_INTENSITY = 0.33f
    const val BASS_PULSE_SCALE = 1.0f

    fun scrimAlpha(isDark: Boolean): Float = if (isDark) DARK_BEHIND_LYRICS_SCRIM else LIGHT_APPEARANCE_SCRIM

    /** Cover texture size before the 4x blur downsample, preserving aspect and capping at 300 px. */
    fun coverDimensions(width: Int, height: Int): IntArray {
        val w = max(1, width)
        val h = max(1, height)
        // Only cap large covers: a tiny cover must not be upscaled to 300 px just to be blurred.
        val scale = (MAX_ARTWORK_DIMENSION.toFloat() / max(w, h)).coerceAtMost(1f)
        val targetW = max(1, (w * scale).roundToInt())
        val targetH = max(1, (h * scale).roundToInt())
        return intArrayOf(targetW, targetH)
    }

    /** The CPU blur works at a 4x smaller surface, exactly like BlurSurfaceDownsample. */
    fun blurDimensions(width: Int, height: Int): IntArray {
        val cover = coverDimensions(width, height)
        return intArrayOf(
            max(1, (cover[0] / BLUR_SURFACE_DOWNSAMPLE.toFloat()).roundToInt()),
            max(1, (cover[1] / BLUR_SURFACE_DOWNSAMPLE.toFloat()).roundToInt()),
        )
    }

    /** Renderer sigma is expressed at the 300 px scale; the small surface needs sigma / 4. */
    /** The upstream picks the sharper blur behind the lyrics page, the softer one on the page. */
    fun blurSigma(lyricsVisible: Boolean): Float =
        (if (lyricsVisible) LYRICS_BLUR_SIGMA else ORDINARY_BLUR_SIGMA) * BLUR_SCALE

    fun blurSigmaSmall(lyricsVisible: Boolean): Float = blurSigma(lyricsVisible) / BLUR_SURFACE_DOWNSAMPLE

    /** Upstream `ProcessSpectrumPower`: smootherstep over the clamped 0..1 power. */
    fun processSpectrumPower(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * v * (v * (v * 6f - 15f) + 10f)
    }

    /** Full-band level + beat stand-in for the low-bass FFT bands upstream. */
    fun bassEnergy(level: Float, beat: Float): Float =
        (level.coerceIn(0f, 1f) * BASS_LEVEL_WEIGHT +
            beat.coerceIn(0f, 1f) * BASS_BEAT_WEIGHT).coerceIn(0f, 1f)

    /**
     * `1 + 0.33 * processed² * BassPulseScale`, clamped by the 0..1 input and the 0..10 upstream
     * setting range. With audio off or scale zero this is exactly 1.0; at full power it is 1.33.
     */
    fun imageScale(level: Float, beat: Float, audioEnabled: Boolean, bassPulseScale: Float = BASS_PULSE_SCALE): Float {
        if (!audioEnabled || bassPulseScale <= 0f) return 1f
        val power = processSpectrumPower(bassEnergy(level, beat))
        val scale = bassPulseScale.coerceIn(0f, 10f)
        return (1f + IMAGE_PULSE_INTENSITY * power * power * scale).coerceAtMost(1.33f)
    }

    /** 0.5 s when the cover changes; 0.25 s when only the blur mode changes. */
    fun transitionSeconds(previousSourceKey: Int?, currentSourceKey: Int): Float =
        if (previousSourceKey != null && previousSourceKey == currentSourceKey) {
            LYRICS_BLUR_FADE_SECONDS
        } else {
            COVER_FADE_SECONDS
        }

    /** Audio only modulates rotation when the setting is on; quiet passages stay at 1.0. */
    fun audioRotationScale(audioEnabled: Boolean, level: Float): Float =
        1f + if (audioEnabled) AUDIO_ROTATION_GAIN * level.coerceIn(0f, 1f) else 0f

    fun coverFade(progress: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** Bitmap prepared once per cover, on a background thread. */
internal class PreparedAppleIosCover(val bitmap: Bitmap, val sourceKey: Int)

/**
 * CPU pre-blur, cached by the caller per cover.
 *
 * Why it cannot show texel blocks: the 300 px cover is reduced 4x before the Gaussian, blurred at
 * that small scale, then upscaled to 300 px. Every visible texel is already a weighted neighbourhood,
 * not a single source pixel. The shader's three-tap cross only hides the remaining half-texel edge;
 * it is not relied on for the blur itself. RuntimeShader child samplers do not honour filter quality,
 * which is exactly why this pipeline exists.
 */
internal object AppleIosBlur {
    fun prepare(image: ImageBitmap, lyricsVisible: Boolean, sourceKey: Int): PreparedAppleIosCover? = runCatching {
        val raw = image.asAndroidBitmap()
        val safe = if (raw.config == Bitmap.Config.HARDWARE) {
            raw.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            raw
        }
        val coverSize = AppleIosRules.coverDimensions(safe.width, safe.height)
        val cover = Bitmap.createScaledBitmap(safe, coverSize[0], coverSize[1], true)
        val smallSize = AppleIosRules.blurDimensions(safe.width, safe.height)
        val small = Bitmap.createScaledBitmap(cover, smallSize[0], smallSize[1], true)
        val blurred = gaussian(small, AppleIosRules.blurSigmaSmall(lyricsVisible))
        val upscaled = Bitmap.createScaledBitmap(blurred, coverSize[0], coverSize[1], true)
        PreparedAppleIosCover(upscaled, sourceKey)
    }.getOrNull()

    private fun gaussian(source: Bitmap, sigma: Float): Bitmap {
        val w = source.width
        val h = source.height
        val radius = ceil(3f * sigma).toInt().coerceIn(1, max(w, h))
        val kernel = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (i in -radius..radius) {
            val weight = exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = weight
            sum += weight
        }
        for (i in kernel.indices) kernel[i] /= sum

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val horizontal = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val p = pixels[y * w + sx]
                    val weight = kernel[k + radius]
                    a += ((p ushr 24) and 0xFF) * weight
                    r += ((p ushr 16) and 0xFF) * weight
                    g += ((p ushr 8) and 0xFF) * weight
                    b += (p and 0xFF) * weight
                }
                horizontal[y * w + x] = pack(a, r, g, b)
            }
        }
        val output = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0f; var r = 0f; var g = 0f; var b = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val p = horizontal[sy * w + x]
                    val weight = kernel[k + radius]
                    a += ((p ushr 24) and 0xFF) * weight
                    r += ((p ushr 16) and 0xFF) * weight
                    g += ((p ushr 8) and 0xFF) * weight
                    b += (p and 0xFF) * weight
                }
                output[y * w + x] = pack(a, r, g, b)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(output, 0, w, 0, 0, w, h)
        }
    }

    private fun pack(a: Float, r: Float, g: Float, b: Float): Int {
        val aa = a.roundToInt().coerceIn(0, 255)
        val rr = r.roundToInt().coerceIn(0, 255)
        val gg = g.roundToInt().coerceIn(0, 255)
        val bb = b.roundToInt().coerceIn(0, 255)
        return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}

/**
 * 苹果 iOS now-playing background: three slowly rotating, blurred copies of the cover, the same
 * tone treatment, 10 s mesh breathing and IGN dither as the Apache-2.0 Lyricify material.
 */
@Composable
internal fun AppleIosBackground(
    palette: DynamicBackgroundPalette,
    cover: ImageBitmap?,
    isDark: Boolean,
    lyricsVisible: Boolean = false,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    paused: Boolean = false,
    audioReactive: Boolean = true,
    staticMotion: Boolean = false,
) {
    val context = LocalContext.current
    val painter = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else runCatching {
            AppleIosPainter(context)
        }.getOrNull()
    }
    if (painter == null) {
        // Shader compilation/setup failure: silently keep the existing 云音 look.
        HyperBackground(palette, isDark, modifier, dim, paused, audioReactive, staticMotion)
        return
    }

    val prepared by produceState<PreparedAppleIosCover?>(initialValue = null, cover, lyricsVisible) {
        value = if (cover == null) null else withContext(Dispatchers.Default) {
            AppleIosBlur.prepare(cover, lyricsVisible, cover.hashCode())
        }
    }
    var current by remember { mutableStateOf<PreparedAppleIosCover?>(null) }
    var previous by remember { mutableStateOf<PreparedAppleIosCover?>(null) }
    var frameTick by remember { mutableIntStateOf(0) }
    val latestPaused by rememberUpdatedState(paused)
    val latestAudio by rememberUpdatedState(audioReactive)
    val latestStatic by rememberUpdatedState(staticMotion)
    val latestDim by rememberUpdatedState(dim)
    val animatorsOn = remember {
        runCatching { android.animation.ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }

    LaunchedEffect(prepared, painter) {
        val next = prepared ?: return@LaunchedEffect
        val prior = current
        previous = prior ?: next
        current = next
        painter.setScrim(AppleIosRules.scrimAlpha(isDark))
        painter.setDither(AppleIosRules.DITHER_STRENGTH)
        painter.setCovers(next, prior ?: next)
        val transitionSeconds = AppleIosRules.transitionSeconds(prior?.sourceKey, next.sourceKey)
        val startNs = if (prior != null) System.nanoTime() else 0L
        frameTick++
        var smoothLevel = 0f
        var smoothBeat = 0f
        var imageScale = 1f
        while (isActive) {
            var advanced = false
            withFrameNanos { ns ->
                if (latestPaused) return@withFrameNanos
                val motion = animatorsOn && !latestStatic
                if (motion) {
                    val targetLevel = if (latestAudio) AudioReactive.level.value.coerceIn(0f, 1f) else 0f
                    val targetBeat = if (latestAudio) AudioReactive.beat.value.coerceIn(0f, 1f) else 0f
                    smoothLevel += (targetLevel - smoothLevel) *
                        (if (targetLevel > smoothLevel) 0.12f else 0.045f)
                    smoothBeat += (targetBeat - smoothBeat) *
                        (if (targetBeat > smoothBeat) 0.46f else 0.12f)
                    imageScale = AppleIosRules.imageScale(smoothLevel, smoothBeat, latestAudio)
                    painter.setImageScale(imageScale)
                    painter.setTime((ns / 1_000_000_000f) % 62.831852f)
                    painter.setAudioLevel(smoothLevel)
                } else {
                    smoothLevel = 0f
                    smoothBeat = 0f
                    imageScale = 1f
                    painter.setImageScale(1f)
                    painter.setTime(0f)
                    painter.setAudioLevel(0f)
                }
                val progress = if (startNs == 0L) 1f else {
                    AppleIosRules.coverFade(
                        ((ns - startNs).coerceAtLeast(0L) / 1_000_000_000f) /
                            transitionSeconds,
                    )
                }
                painter.setCoverMix(progress)
                advanced = motion || progress < 1f
            }
            if (advanced) frameTick++
        }
    }

    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick
        if (current == null) return@Canvas
        painter.setResolution(size.width, size.height)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, painter.paint)
        }
        val darken = latestDim.coerceIn(0f, 1f)
        if (darken > 0f) drawRect(Color.Black.copy(alpha = darken))
    }
}

private class AppleIosPainter(context: android.content.Context) {
    val shader: RuntimeShader
    val paint: Paint

    init {
        val source = context.assets.open("shaders/apple_ios_background.glsl")
            .bufferedReader()
            .use { it.readText() }
        shader = RuntimeShader(source)
        shader.setFloatUniform("uScrimAlpha", AppleIosRules.DARK_BEHIND_LYRICS_SCRIM)
        shader.setFloatUniform("uDitherStrength", AppleIosRules.DITHER_STRENGTH)
        shader.setFloatUniform("uImageScale", 1f)
        paint = Paint().apply {
            this.shader = this@AppleIosPainter.shader
            isAntiAlias = false
        }
    }

    fun setResolution(width: Float, height: Float) {
        shader.setFloatUniform("uResolution", max(1f, width), max(1f, height))
    }

    fun setScrim(alpha: Float) = shader.setFloatUniform("uScrimAlpha", alpha.coerceIn(0f, 1f))
    fun setDither(strength: Float) = shader.setFloatUniform("uDitherStrength", strength)
    fun setTime(seconds: Float) = shader.setFloatUniform("uTime", seconds)
    fun setAudioLevel(level: Float) = shader.setFloatUniform("uAudioLevel", level.coerceIn(0f, 1f))
    fun setImageScale(scale: Float) = shader.setFloatUniform("uImageScale", scale.coerceAtLeast(1f))
    fun setCoverMix(progress: Float) = shader.setFloatUniform("uCoverMix", progress.coerceIn(0f, 1f))

    fun setCovers(current: PreparedAppleIosCover, previous: PreparedAppleIosCover) {
        shader.setInputShader(
            "uCoverCurrent",
            BitmapShader(current.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        shader.setInputShader(
            "uCoverPrevious",
            BitmapShader(previous.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        shader.setFloatUniform("uCoverSizeCurrent", current.bitmap.width.toFloat(), current.bitmap.height.toFloat())
        shader.setFloatUniform("uCoverSizePrevious", previous.bitmap.width.toFloat(), previous.bitmap.height.toFloat())
    }
}
