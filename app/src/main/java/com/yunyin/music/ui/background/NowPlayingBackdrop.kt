// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.background

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale

/*
 * Now-playing backdrop layers ported from NeriPlayer (GPL-3.0).
 *
 * Source paths in NeriPlayer-master:
 *   - app/src/main/java/moe/ouom/neriplayer/ui/playback/visual/NowPlayingBackdropLayers.kt
 *     (layer order: blurred cover → darken scrim → dynamic backdrop at 0.80 alpha)
 *   - app/src/main/java/moe/ouom/neriplayer/ui/playback/visual/NowPlayingBlurPolicy.kt
 *     (cover-blur availability and gating)
 *   - app/src/main/java/moe/ouom/neriplayer/ui/playback/visual/AppPlaybackVisualOwner.kt
 *     (AccentBackdropSurface: flat accent colour + vignette/white mask behind everything)
 *
 * Copyright (C) 2025-2025 NeriPlayer developers, https://github.com/cwuom/NeriPlayer
 *
 * Both NeriPlayer and YunYin are licensed under the GNU General Public License v3.0. This file is an
 * adaptation: our artwork is already decoded to an [ImageBitmap], so NeriPlayer's Coil loading and
 * RenderScript pre-blur are replaced by a remembered low-resolution downscale drawn with bilinear
 * filtering (the blurred-cover look without a per-frame blur pass); the flat accent surface is
 * replaced by a two-stop palette gradient. The three now-playing background settings and their
 * gating rules are ported with the layers.
 */

/**
 * Pure gating rules for the now-playing background layers.
 *
 * These decide whether each layer may *do work*, not merely whether it is visible: a disabled
 * dynamic background must not request frames at all, and an audio-reactive background with the
 * reactive setting off must not read the audio taps. Kept free of Compose so a unit test can pin the
 * truth table.
 */
internal object NowPlayingBackgroundRules {

    /**
     * How far the cover is downscaled before it becomes the background.
     *
     * NeriPlayer pre-blurs the cover bitmap; we get the same soft look by drawing a very small bitmap
     * stretched to the screen with bilinear filtering. 32 px keeps large structures and costs one
     * tiny upload, and unlike a `Modifier.blur` it adds no per-frame GPU pass.
     */
    const val COVER_DOWNSCALE_PX = 32

    /** Opacity of the dynamic shader when it sits over the blurred cover (NeriPlayer: 0.80). */
    const val DYNAMIC_ALPHA = 0.80f

    /** Vignette strength at the top of the static palette backdrop, mirroring the accent surface. */
    const val PALETTE_VIGNETTE_ALPHA = 0.22f

    /**
     * Whether the dynamic shader layer should run at all.
     *
     * False means the layer is not composed: no shader draw, no frame pump, no GPU cost. The
     * background then shows the blurred cover if that is enabled, otherwise the static palette
     * gradient.
     */
    fun shouldRunDynamic(
        dynamicEnabled: Boolean,
        animatorsEnabled: Boolean,
        powerSaveMode: Boolean,
    ): Boolean = dynamicEnabled && animatorsEnabled && !powerSaveMode

    /**
     * Whether the shader may consume the audio taps.
     *
     * Turning audio reactivity off leaves the time-based drift running; it only stops the beat/level
     * uniforms so the fields stop pulsing and drifting with the music.
     */
    fun shouldUseAudioReactive(
        audioReactiveEnabled: Boolean,
        animatorsEnabled: Boolean,
        powerSaveMode: Boolean,
    ): Boolean = audioReactiveEnabled && animatorsEnabled && !powerSaveMode

    /** Whether the cover-blur layer is drawn. Static, so it is only gated by its own setting. */
    fun shouldShowCoverBlur(coverBlurEnabled: Boolean): Boolean = coverBlurEnabled

    /** Black scrim alpha applied over the blurred cover so white text keeps its contrast. */
    fun coverDarken(dim: Float): Float = dim.coerceIn(0f, 1f)
}

/**
 * The current cover as the now-playing background: the artwork's own colours, heavily softened.
 *
 * This is the "模糊封面背景" layer. It is static — no clock, no animation — so when the dynamic
 * background is also off the page costs nothing beyond that single image draw on redraw.
 */
@Composable
internal fun CoverBlurBackground(
    cover: ImageBitmap?,
    dim: Float,
    modifier: Modifier = Modifier,
) {
    val lowResCover = remember(cover) {
        cover?.let { bitmap ->
            runCatching {
                Bitmap.createScaledBitmap(
                    bitmap.asAndroidBitmap(),
                    NowPlayingBackgroundRules.COVER_DOWNSCALE_PX,
                    NowPlayingBackgroundRules.COVER_DOWNSCALE_PX,
                    true,
                ).asImageBitmap()
            }.getOrDefault(bitmap)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (lowResCover != null) {
            Image(
                bitmap = lowResCover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val darken = NowPlayingBackgroundRules.coverDarken(dim)
        if (darken > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = darken)))
        }
    }
}

/**
 * The static palette backdrop: a two-stop gradient from the cover palette plus NeriPlayer's
 * top vignette. Always cheap, always opaque — it is what keeps the page from ever being black when
 * both background layers are off.
 */
@Composable
internal fun StaticPaletteBackdrop(
    palette: DynamicBackgroundPalette,
    dim: Float,
    modifier: Modifier = Modifier,
) {
    val primary = Color(palette.colors.first())
    val secondary = Color(palette.colors.getOrElse(2) { palette.colors.first() })
    val darken = dim.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(secondary, primary)))
            .drawWithContent {
                drawContent()
                drawVignette()
                if (darken > 0f) {
                    drawRect(Color.Black.copy(alpha = darken))
                }
            },
    )
}

/** NeriPlayer's accent-surface vignette: dark at the top, clear towards the content. */
private fun DrawScope.drawVignette() {
    val alpha = NowPlayingBackgroundRules.PALETTE_VIGNETTE_ALPHA
    drawRect(
        Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
        ),
    )
}
