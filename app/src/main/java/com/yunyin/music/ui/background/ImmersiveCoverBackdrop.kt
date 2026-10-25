// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
// Backdrop composition ideas from NeriPlayer (GPL-3.0), © NeriPlayer contributors.

package com.yunyin.music.ui.background

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
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.isActive

/**
 * The opt-in immersive cover. One RuntimeShader pass samples a full-bleed cropped cover with a
 * subtle liquid ripple; the bottom alpha gradient reveals the normal page background for the
 * information block. Shader construction failure is silent: the existing backdrop remains.
 */
@Composable
internal fun ImmersiveCoverBackdrop(
    cover: ImageBitmap?,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val painter = remember {
        runCatching {
            val source = context.assets.open("shaders/immersive_cover.glsl")
                .bufferedReader().use { it.readText() }
            val shader = RuntimeShader(source)
            shader.setFloatUniform("time", 0f)
            shader.setFloatUniform("coverAspect", 1f)
            shader.setFloatUniform("coverSize", 1f, 1f)
            shader.setFloatUniform("resolution", 1f, 1f)
            val paint = Paint().apply {
                this.shader = shader
                isAntiAlias = true
            }
            shader to paint
        }.getOrNull()
    } ?: return
    val bitmap = remember(cover) { cover?.asAndroidBitmap() } ?: return
    val (shader, paint) = painter
    val latestAnimate by rememberUpdatedState(animate)
    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(shader, bitmap) {
        runCatching {
            shader.setInputShader(
                "cover",
                BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
            )
            shader.setFloatUniform("coverSize", bitmap.width.toFloat(), bitmap.height.toFloat())
            shader.setFloatUniform(
                "coverAspect",
                bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat(),
            )
        }
        val startNs = System.nanoTime()
        var nextFrameNs = 0L
        while (isActive) {
            withFrameNanos { ns ->
                if (!latestAnimate) return@withFrameNanos
                if (nextFrameNs != 0L && ns < nextFrameNs) return@withFrameNanos
                nextFrameNs = if (ns - nextFrameNs > ImmersiveCoverRules.FRAME_INTERVAL_NS * 2L) {
                    ns + ImmersiveCoverRules.FRAME_INTERVAL_NS
                } else {
                    nextFrameNs + ImmersiveCoverRules.FRAME_INTERVAL_NS
                }
                time = ((ns - startNs) / 1_000_000_000f) % 3600f
            }
            if (!latestAnimate) {
                // Keep the static frame; the next frame tick is cheap and this loop still
                // listens for the toggle so re-enabling resumes without recreating the shader.
                kotlinx.coroutines.delay(120L)
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        runCatching {
            shader.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
            shader.setFloatUniform("time", time)
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
    }
}
