// Clean-room QPlayer-inspired mesh flow. No QPlayer/AMLL mesh code is copied; those
// upstream mesh files are AGPL-3.0. See QpBackground.kt.
package com.yunyin.music.ui.background

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

@Composable
internal fun QpMeshBackground(
    cover: ImageBitmap?,
    trackId: Long,
    palette: DynamicBackgroundPalette,
    modifier: Modifier = Modifier,
    dim: Float = 0f,
    paused: Boolean = false,
    staticMotion: Boolean = false,
) {
    QpSurface(
        shaderAsset = "shaders/qp_mesh_background.glsl",
        mode = QpMode.Mesh,
        cover = cover,
        trackId = trackId,
        palette = palette,
        modifier = modifier,
        dim = dim,
        paused = paused,
        staticMotion = staticMotion,
    )
}
