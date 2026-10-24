// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

// Portions ported from QPlayer (Apache-2.0), © QPlayer contributors.
// Source: player-core/src/main/resources/shaders/fluid/pixi_renderer_fade.sksl
package com.yunyin.music.ui.background

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

@Composable
internal fun QpPixiBackground(
    cover: ImageBitmap?,
    trackId: Long,
    palette: DynamicBackgroundPalette,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    paused: Boolean = false,
    staticMotion: Boolean = false,
) {
    QpSurface(
        shaderAsset = "shaders/qp_pixi_background.glsl",
        mode = QpMode.Pixi,
        cover = cover,
        trackId = trackId,
        palette = palette,
        modifier = modifier,
        dim = dim,
        paused = paused,
        staticMotion = staticMotion,
    )
}
