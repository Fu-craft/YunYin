// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.background

import android.graphics.Paint
import android.graphics.RuntimeShader
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.yunyin.music.core.player.effects.AudioReactive
import kotlinx.coroutines.isActive

// Clean-room, independent implementation inspired by AMLL's background family (AGPL-3.0).
// No AMLL shader/renderer code, assets or constants were copied; see the authored AGSL in
// assets/shaders for the full provenance note.

@Composable
fun OkLabFlowBackground(
    palette: DynamicBackgroundPalette,
    trackId: Long,
    isDark: Boolean = true,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    paused: Boolean = false,
    audioReactive: Boolean = true,
    staticMotion: Boolean = false,
) {
    val context = LocalContext.current
    val painter = remember {
        if (Build.VERSION.SDK_INT < 33) null else runCatching {
            val source = context.assets.open("shaders/oklab_flow_background.glsl").bufferedReader().use { it.readText() }
            val shader = RuntimeShader(source)
            OkLabFlowPainter(shader, Paint().apply { this.shader = shader; isAntiAlias = false })
        }.getOrNull()
    }
    if (painter == null) {
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
        painter.setColors(OkLabRules.transitionPalette(from, to, 1f))
        painter.setSeed(seed)
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
                painter.setColors(OkLabRules.transitionPalette(from, to, progress))
                painter.setDim(latestDim)
                val settling = progress < 1f
                val motion = animatorsOn && !latestStatic
                if (motion) {
                    val time = (ns / 1_000_000_000f) % 62.83185f
                    painter.setTime(time)
                    painter.setVolume(if (latestAudio) AudioReactive.level.value.coerceIn(0f, 1f) * 0.08f else 0f)
                }
                painter.setSeed(seed)
                advanced = motion || settling
            }
            if (advanced) frameTick++
        }
    }
    Canvas(modifier.fillMaxSize()) {
        frameTick
        painter.shader.setFloatUniform("uResolution", size.width, size.height)
        drawIntoCanvas { canvas -> canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, painter.paint) }
    }
}

private class OkLabFlowPainter(val shader: RuntimeShader, val paint: Paint) {
    fun setColors(colors: IntArray) {
        val values = OkLabRules.toShaderArray(colors)
        fun color(index: Int): FloatArray {
            val offset = index * 3
            if (offset + 2 >= values.size) return floatArrayOf(0.1f, 0.1f, 0.1f)
            return floatArrayOf(values[offset], values[offset + 1], values[offset + 2])
        }
        shader.setFloatUniform("uColor0", color(0))
        shader.setFloatUniform("uColor1", color(1))
        shader.setFloatUniform("uColor2", color(2))
        shader.setFloatUniform("uColor3", color(3))
    }
    fun setDim(value: Float) = shader.setFloatUniform("uDim", value.coerceIn(0f, 1f))
    fun setTime(value: Float) = shader.setFloatUniform("uTime", value)
    fun setVolume(value: Float) = shader.setFloatUniform("uVolume", value.coerceIn(0f, 0.2f))
    fun setSeed(seed: BackgroundSeed) {
        shader.setFloatUniform("uFlowFrequency", seed.flowFrequency)
        shader.setFloatUniform("uFlowAmplitude", seed.flowAmplitude)
        shader.setFloatUniform("uFlowSpeed", seed.flowSpeed)
        shader.setFloatUniform("uFlowTilt", seed.flowTilt)
        shader.setFloatUniform("uAngleJitter", seed.angleJitter)
    }
}
