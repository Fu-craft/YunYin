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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.yunyin.music.core.player.effects.AudioReactive
import kotlinx.coroutines.isActive
import kotlin.math.min

// Clean-room, independent implementation inspired by AMLL's background family (AGPL-3.0).
// No AMLL shader/renderer code, assets or constants were copied; see the authored AGSL in
// assets/shaders for the full provenance note.

@Composable
fun CoverWarpBackground(
    palette: DynamicBackgroundPalette,
    cover: ImageBitmap?,
    trackId: Long,
    isDark: Boolean = true,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    paused: Boolean = false,
    audioReactive: Boolean = true,
    staticMotion: Boolean = false,
) {
    val context = LocalContext.current
    val bitmap = remember(cover) {
        runCatching {
            cover?.asAndroidBitmap()?.let { source ->
                val scale = min(1f, CoverWarpRules.COVER_TEXTURE_SIZE.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
                val w = (source.width * scale).toInt().coerceAtLeast(1)
                val h = (source.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(source, w, h, true)
            }
        }.getOrNull()
    }
    val painter = remember(bitmap) {
        if (Build.VERSION.SDK_INT < 33 || bitmap == null) null else runCatching {
            val source = context.assets.open("shaders/cover_warp_background.glsl").bufferedReader().use { it.readText() }
            val shader = RuntimeShader(source)
            val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            // Bilinear sampling is essential: a 128 px texture upscaled by a warped UV must not
            // show texel blocks. BitmapShader has no public filter-quality setter on the supported
            // API surface, so the parent Paint carries isFilterBitmap = true (Skia's HIGH quality)
            // and the shader's 13-tap blur below removes any remaining texel structure.
            shader.setInputShader("uCover", bitmapShader)
            shader.setFloatUniform("uCoverSize", bitmap.width.toFloat(), bitmap.height.toFloat())
            val paint = Paint().apply {
                this.shader = shader
                isAntiAlias = true
                isFilterBitmap = true
            }
            CoverWarpPainter(shader, paint)
        }.getOrNull()
    }
    if (painter == null) {
        // Pre-API 33 / missing cover / shader compile failure: the established 云音 style.
        HyperBackground(palette, isDark, modifier, dim, paused, audioReactive, staticMotion)
        return
    }
    val seed = remember(trackId) { BackgroundStyleRules.seedForTrack(trackId) }
    val latestPalette by rememberUpdatedState(palette)
    val latestPaused by rememberUpdatedState(paused)
    val latestAudio by rememberUpdatedState(audioReactive)
    val latestStatic by rememberUpdatedState(staticMotion)
    val latestDim by rememberUpdatedState(dim)
    var frameTick by remember { mutableIntStateOf(0) }
    val animatorsOn = remember { runCatching { android.animation.ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true) }
    LaunchedEffect(painter, seed) {
        var from = latestPalette.colors
        var to = from
        var fadeStart = 0L
        var last = latestPalette
        painter.setPaletteColors(OkLabRules.transitionPalette(from, to, 1f))
        painter.setSeed(seed)
        painter.setRotation(seed.rotation)
        painter.setTime(0f)
        painter.setVolume(0f)
        frameTick++
        while (isActive) {
            var advanced = false
            withFrameNanos { ns ->
                if (latestPaused) return@withFrameNanos
                val current = latestPalette
                if (current != last) {
                    from = OkLabRules.transitionPalette(from, to, 1f)
                    to = current.colors
                    last = current
                    fadeStart = ns
                }
                val progress = if (fadeStart == 0L) 1f else ((ns - fadeStart).coerceAtLeast(0L) / 1_000_000_000f)
                val colors = OkLabRules.transitionPalette(from, to, progress)
                painter.setPaletteColors(colors)
                painter.setDim(latestDim)
                val settling = progress < 1f
                val motion = animatorsOn && !latestStatic
                if (motion) {
                    painter.setTime((ns / 1_000_000_000f) % 62.83185f)
                    val level = if (latestAudio) AudioReactive.level.value.coerceIn(0f, 1f) else 0f
                    painter.setRotation(BackgroundStyleRules.rotationFor(seed, (ns / 1_000_000_000f) % 62.83185f, level))
                    painter.setVolume(level)
                }
                painter.setSeed(seed)
                advanced = motion || settling
            }
            if (advanced) frameTick++
        }
    }
    Canvas(modifier.fillMaxSize()) {
        frameTick
        val p = painter
        val activePaint = p.paint
        p.shader.setFloatUniform("uResolution", size.width, size.height)
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, activePaint) }
    }
}

private class CoverWarpPainter(val shader: RuntimeShader, val paint: Paint) {
    fun setPaletteColors(colors: IntArray) {
        val accent = colors.getOrElse(1) { colors.firstOrNull() ?: 0xFF808080.toInt() }
        shader.setFloatUniform("uTint", (accent ushr 16 and 0xFF) / 255f, (accent ushr 8 and 0xFF) / 255f, (accent and 0xFF) / 255f)
    }
    fun setDim(value: Float) = shader.setFloatUniform("uDim", value.coerceIn(0f, 1f))
    fun setTime(value: Float) = shader.setFloatUniform("uTime", value)
    fun setVolume(value: Float) = shader.setFloatUniform("uVolume", value.coerceIn(0f, 1f))
    fun setRotation(value: Float) = shader.setFloatUniform("uRotation", value)
    fun setSeed(seed: BackgroundSeed) {
        shader.setFloatUniform("uSeedPhase", seed.coverWarpPhase)
        shader.setFloatUniform("uSeedFrequency", seed.coverWarpFrequency)
    }
}
