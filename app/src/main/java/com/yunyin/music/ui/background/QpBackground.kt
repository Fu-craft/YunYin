// Portions ported from QPlayer (Apache-2.0), © QPlayer contributors.
// Source: player-core/src/main/java/dev/t1m3/qplayer/lyric/skia/FluidBackground.java
// and player-core/src/main/resources/shaders/fluid/*.sksl. Android Compose/AGSL adaptation.

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** QPlayer's documented fluid parameters, kept free of Android types. */
internal object QpRules {
    const val SOURCE_TEX_SIZE = 32
    const val TEXTURE_TEX_SIZE = 256
    const val BLUR_RADIUS = 2
    const val BLUR_PASSES = 4
    const val FADE_SECONDS = 0.6f
    const val FRAME_RATE_LIMIT = BackgroundStyleRules.FRAME_RATE_LIMIT

    // Pixi Renderer
    const val PIXI_MAIN_SCALE = 1.41421356f
    const val PIXI_MID_SCALE = 0.8f
    const val PIXI_INNER_SCALE = 0.5f
    const val PIXI_CORE_SCALE = 0.25f
    const val PIXI_FEATHER_FRACTION = 0.115f
    const val PIXI_MAIN_SPIN = 0.06f
    const val PIXI_MID_SPIN = -0.12f
    const val PIXI_INNER_SPIN = 0.06f
    const val PIXI_CORE_SPIN = -0.08f
    const val PIXI_TRAVEL_SPEED = 0.045f
    const val PIXI_TRAVEL_FRACTION = 0.25f
    const val PIXI_DRIFT_SPEED = 0.27f
    const val PIXI_DRIFT_FRACTION = 0.025f
    const val PIXI_CHROMA_CAP = 0.28f
    const val PIXI_LUMA_CAP = 0.42f

    // Classic
    const val CLASSIC_WARP_WEIGHT_1 = 0.10f
    const val CLASSIC_WARP_WEIGHT_2 = 0.06f
    const val CLASSIC_WARP_SPEED_1 = 0.42f
    const val CLASSIC_WARP_SPEED_2 = 0.37f
    const val CLASSIC_WARP_FREQ_1 = 3.7f
    const val CLASSIC_WARP_FREQ_2 = 3.1f
    const val CLASSIC_WARP_SPEED_3 = 0.29f
    const val CLASSIC_WARP_SPEED_4 = 0.51f
    const val CLASSIC_WARP_FREQ_3 = 2.3f
    const val CLASSIC_WARP_FREQ_4 = 4.4f
    const val CLASSIC_ROTATION_SPEED = 0.18f
    const val CLASSIC_UV_MIN = 0.005f
    const val CLASSIC_UV_MAX = 0.995f
    const val CLASSIC_VIGNETTE_BASE = 0.6f
    const val CLASSIC_VIGNETTE_GAIN = 0.4f

    // Shared colour treatment for Classic / Mesh (QPlayer amllAdjust).
    const val ADJUST_CONTRAST = 0.4f
    const val ADJUST_SATURATION = 3.0f
    const val ADJUST_CONTRAST_2 = 1.7f
    const val ADJUST_BRIGHTNESS = 0.75f
    const val CHROMA_CAP = 72f
    const val LUMA_CAP = 108f

    fun angles(trackId: Long): FloatArray {
        val random = Random(trackId xor 0x9E3779B97F4A7C15uL.toLong())
        return FloatArray(4) { random.nextFloat() * (PI.toFloat() * 2f) }
    }

    fun bend(trackId: Long): Float = if ((trackId ushr 1) % 2L == 0L) 1f else -1f

    fun shouldAnimate(dynamicEnabled: Boolean, animatorsEnabled: Boolean, powerSave: Boolean): Boolean =
        dynamicEnabled && animatorsEnabled && !powerSave

    /** QPlayer's styles have no spectrum input, so the audio gate contributes nothing. */
    fun audioTerm(): Float = 0f

    fun shouldFallback(shaderReady: Boolean): Boolean = !shaderReady
}

internal class QpPreparedCover(
    val bitmap: Bitmap,
    val angles: FloatArray,
    val sourceKey: Int,
)

internal object QpCover {
    fun prepare(image: ImageBitmap, adjusted: Boolean, trackId: Long): QpPreparedCover? = runCatching {
        val raw = image.asAndroidBitmap()
        val safe = if (raw.config == Bitmap.Config.HARDWARE) {
            raw.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            raw
        }
        val square = Bitmap.createScaledBitmap(safe, QpRules.SOURCE_TEX_SIZE, QpRules.SOURCE_TEX_SIZE, true)
        val pixels = IntArray(QpRules.SOURCE_TEX_SIZE * QpRules.SOURCE_TEX_SIZE)
        square.getPixels(pixels, 0, QpRules.SOURCE_TEX_SIZE, 0, 0, QpRules.SOURCE_TEX_SIZE, QpRules.SOURCE_TEX_SIZE)
        if (adjusted) {
            for (i in pixels.indices) pixels[i] = adjust(pixels[i])
        }
        boxBlur(pixels, QpRules.SOURCE_TEX_SIZE, QpRules.SOURCE_TEX_SIZE)
        val small = Bitmap.createBitmap(QpRules.SOURCE_TEX_SIZE, QpRules.SOURCE_TEX_SIZE, Bitmap.Config.ARGB_8888)
        small.setPixels(pixels, 0, QpRules.SOURCE_TEX_SIZE, 0, 0, QpRules.SOURCE_TEX_SIZE, QpRules.SOURCE_TEX_SIZE)
        val texture = Bitmap.createScaledBitmap(
            small,
            QpRules.TEXTURE_TEX_SIZE,
            QpRules.TEXTURE_TEX_SIZE,
            true,
        )
        QpPreparedCover(texture, QpRules.angles(trackId), trackId.toInt())
    }.getOrNull()

    private fun adjust(pixel: Int): Int {
        var r = ((pixel ushr 16) and 0xFF).toFloat()
        var g = ((pixel ushr 8) and 0xFF).toFloat()
        var b = (pixel and 0xFF).toFloat()
        r = (r - 128f) * QpRules.ADJUST_CONTRAST + 128f
        g = (g - 128f) * QpRules.ADJUST_CONTRAST + 128f
        b = (b - 128f) * QpRules.ADJUST_CONTRAST + 128f
        val gray = r * 0.3f + g * 0.59f + b * 0.11f
        r = gray * -2f + r * 3f
        g = gray * -2f + g * 3f
        b = gray * -2f + b * 3f
        r = ((r - 128f) * QpRules.ADJUST_CONTRAST_2 + 128f) * QpRules.ADJUST_BRIGHTNESS
        g = ((g - 128f) * QpRules.ADJUST_CONTRAST_2 + 128f) * QpRules.ADJUST_BRIGHTNESS
        b = ((b - 128f) * QpRules.ADJUST_CONTRAST_2 + 128f) * QpRules.ADJUST_BRIGHTNESS
        var y = r * 0.3f + g * 0.59f + b * 0.11f
        var cr = r - y; var cg = g - y; var cb = b - y
        val chroma = max(abs(cr), max(abs(cg), abs(cb)))
        if (chroma > QpRules.CHROMA_CAP) {
            val k = QpRules.CHROMA_CAP / chroma
            r = y + cr * k; g = y + cg * k; b = y + cb * k
        }
        if (y > QpRules.LUMA_CAP) {
            val k = QpRules.LUMA_CAP / y
            r *= k; g *= k; b *= k
        }
        return (0xFF shl 24) or
            (r.roundToInt().coerceIn(0, 255) shl 16) or
            (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int) {
        val radius = QpRules.BLUR_RADIUS
        repeat(QpRules.BLUR_PASSES) {
            val tmp = IntArray(pixels.size)
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val p = pixels[y * w + (x + k).coerceIn(0, w - 1)]
                    a += (p ushr 24) and 0xFF; r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF; b += p and 0xFF
                }
                val d = 2 * radius + 1
                tmp[y * w + x] = (a / d shl 24) or (r / d shl 16) or (g / d shl 8) or (b / d)
            }
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val p = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    a += (p ushr 24) and 0xFF; r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF; b += p and 0xFF
                }
                val d = 2 * radius + 1
                pixels[y * w + x] = (a / d shl 24) or (r / d shl 16) or (g / d shl 8) or (b / d)
            }
        }
    }
}

internal enum class QpMode { Pixi, Mesh, Classic }

@Composable
internal fun QpSurface(
    shaderAsset: String,
    mode: QpMode,
    cover: ImageBitmap?,
    trackId: Long,
    palette: DynamicBackgroundPalette,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    paused: Boolean = false,
    staticMotion: Boolean = false,
) {
    val context = LocalContext.current
    val shaderReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val painter = remember(shaderAsset) {
        if (!shaderReady) null else runCatching { QpPainter(context, shaderAsset, mode) }.getOrNull()
    }
    if (painter == null) {
        HyperBackground(palette, true, modifier, dim, paused, false, staticMotion)
        return
    }
    val prepared by produceState<QpPreparedCover?>(null, cover, trackId) {
        value = if (cover == null) null else withContext(Dispatchers.Default) {
            QpCover.prepare(cover, adjusted = mode != QpMode.Pixi, trackId = trackId)
        }
    }
    var current by remember { mutableStateOf<QpPreparedCover?>(null) }
    var previous by remember { mutableStateOf<QpPreparedCover?>(null) }
    var frameTick by remember { mutableIntStateOf(0) }
    val latestPaused by rememberUpdatedState(paused)
    val latestStatic by rememberUpdatedState(staticMotion)
    val latestDim by rememberUpdatedState(dim)
    val animatorsOn = remember {
        runCatching { android.animation.ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
    val bend = remember(trackId) { QpRules.bend(trackId) }

    LaunchedEffect(prepared, painter) {
        val next = prepared ?: return@LaunchedEffect
        val prior = current
        previous = prior ?: next
        current = next
        painter.setCovers(next, prior ?: next)
        painter.setAngles(next.angles)
        painter.setAnglesPrevious(prior?.angles ?: next.angles)
        painter.setBend(bend)
        val fadeStartNs = if (prior != null && !staticMotion) System.nanoTime() else 0L
        val startNs = System.nanoTime()
        frameTick++
        var nextFrameNs = 0L
        while (isActive) {
            var advanced = false
            withFrameNanos { ns ->
                if (latestPaused) return@withFrameNanos
                if (nextFrameNs != 0L && ns < nextFrameNs) return@withFrameNanos
                nextFrameNs = if (ns - nextFrameNs > BackgroundStyleRules.FRAME_INTERVAL_NS * 2L) {
                    ns + BackgroundStyleRules.FRAME_INTERVAL_NS
                } else {
                    nextFrameNs + BackgroundStyleRules.FRAME_INTERVAL_NS
                }
                val motion = animatorsOn && !latestStatic
                if (motion) {
                    painter.setTime(((ns - startNs) / 1_000_000_000f) % 3600f)
                } else {
                    painter.setTime(0f)
                }
                val fade = if (fadeStartNs == 0L) 1f else {
                    ((ns - fadeStartNs).coerceAtLeast(0L) / 1_000_000_000f / QpRules.FADE_SECONDS)
                        .coerceIn(0f, 1f)
                }
                painter.setFade(fade)
                advanced = motion || fade < 1f
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

private class QpPainter(context: android.content.Context, asset: String, private val mode: QpMode) {
    val shader: RuntimeShader
    val paint: Paint

    init {
        val source = context.assets.open(asset).bufferedReader().use { it.readText() }
        shader = RuntimeShader(source)
        shader.setFloatUniform("fade", 1f)
        if (mode == QpMode.Pixi) shader.setFloatUniform("bend", 1f)
        paint = Paint().apply {
            this.shader = this@QpPainter.shader
            isAntiAlias = false
        }
    }

    fun setResolution(width: Float, height: Float) {
        shader.setFloatUniform("resolution", max(1f, width), max(1f, height))
    }

    fun setTime(seconds: Float) = shader.setFloatUniform("time", seconds)
    fun setBend(value: Float) {
        if (mode == QpMode.Pixi) shader.setFloatUniform("bend", value)
    }
    fun setFade(value: Float) = shader.setFloatUniform("fade", value.coerceIn(0f, 1f))
    fun setAngles(values: FloatArray) {
        if (mode == QpMode.Pixi) shader.setFloatUniform("angles", values)
    }

    fun setAnglesPrevious(values: FloatArray) {
        if (mode == QpMode.Pixi) shader.setFloatUniform("anglesPrev", values)
    }

    fun setCovers(current: QpPreparedCover, previous: QpPreparedCover) {
        shader.setInputShader("cover", BitmapShader(current.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setInputShader("coverPrev", BitmapShader(previous.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }
}
