// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yunyin.music.core.Playlist
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.theme.AppTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Playlist showcases, ported from CurrentMusicX (`feature/library`).
 *
 * Two alternative presentations for a shelf of playlists, both borrowed from that project's
 * "playlist depth ribbon" and "record shelf":
 *
 * - **Ribbon** — sleeves ride a 3D figure-eight path (`sin t`, `cos t · 0.28`, `sin 2t · 0.62`) and are
 *   projected to the screen with a simple perspective divide, so the loop passes behind itself. This is
 *   the reference's `DepthRibbonPath`; the arc-length resampling is dropped because our shelf is static
 *   (no drag), and without dragging the only thing it bought was constant-speed motion.
 * - **Shelf** — CD cases standing at a lean on a shelf. The lean and the slot spacing are the
 *   reference's measured numbers (`x = w(0.12 + i·0.069)`, width `0.435w`, edge rise `0.132w`), which
 *   is what makes a row of squares look like cases in a rack instead of icons in a grid.
 *
 * Both are opt-in (`SettingsStore.playlistShowcase`), default off: they are a stylistic flourish and
 * the flat shelf remains the readable default.
 */
enum class PlaylistShowcase(val key: String, val label: String) {
    Off("off", "关闭"),
    Ribbon("ribbon", "立体缎带"),
    Shelf("shelf", "CD 唱片架"),
    ;

    companion object {
        fun of(value: String?): PlaylistShowcase =
            entries.firstOrNull { it.key == value } ?: Off

        fun next(value: String?): PlaylistShowcase {
            val current = of(value)
            return entries[(current.ordinal + 1) % entries.size]
        }
    }
}

/** The reference's ribbon path, sampled into 2D screen positions with a perspective divide. */
private fun ribbonPoint(index: Int, count: Int, radiusPx: Float): Triple<Float, Float, Float> {
    val t = (index.toFloat() / count) * 2f * PI.toFloat()
    val x = sin(t)
    val y = cos(t) * 0.28f
    val z = sin(2 * t) * 0.62f
    // Camera at z = -2.2 looking at the origin; the divide is what produces the depth cue.
    val depth = (z + 2.2f).coerceAtLeast(0.2f)
    return Triple(x * radiusPx / depth * 1.6f, y * radiusPx / depth * 1.6f, depth)
}

@Composable
fun PlaylistRibbon(
    playlists: List<Playlist>,
    loader: ArtworkLoader,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return
    val size = 92.dp
    val count = playlists.size.coerceAtMost(18)
    Box(
        modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(0.dp)),
    ) {
        val centerX = 210f
        val centerY = 96f
        // Draw far-to-near so a sleeve that passes "behind" is correctly occluded by nearer ones.
        playlists.take(count)
            .mapIndexed { index, playlist -> index to playlist }
            .sortedByDescending { (index, _) -> ribbonPoint(index, count, 150f).third }
            .forEach { (index, playlist) ->
                val (x, y, depth) = ribbonPoint(index, count, 150f)
                val scale = (1f / depth).coerceIn(0.45f, 1.15f)
                val alpha = ((1.25f - depth) * 1.4f).coerceIn(0.25f, 1f)
                Box(
                    Modifier
                        .offset(x = (centerX + x).dp * 0.55f, y = (centerY + y).dp * 0.55f)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        },
                ) {
                    Artwork(
                        url = playlist.coverUrl,
                        loader = loader,
                        corner = 12.dp,
                        modifier = Modifier
                            .size(size)
                            .clickable { onPlaylistClick(playlist) },
                    )
                }
            }
    }
}

@Composable
fun PlaylistShelf(
    playlists: List<Playlist>,
    loader: ArtworkLoader,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return
    val widthDp = 92.dp
    val lean = 7f
    Box(
        modifier
            .fillMaxWidth()
            .height(184.dp)
            .background(AppTheme.palette.secondaryBackground),
    ) {
        playlists.take(8).forEachIndexed { index, playlist ->
            // The reference's slot pitch is 0.069 W with a 0.12 W leading margin; we mirror it in dp so
            // the row reads as cases in a rack rather than a centred carousel.
            val pitch = widthDp * 0.069f * 6.4f
            Box(
                Modifier
                    .offset(x = 16.dp + pitch * index)
                    .graphicsLayer {
                        rotationZ = -lean
                        rotationY = 10f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                        shadowElevation = 12f
                    },
            ) {
                Artwork(
                    url = playlist.coverUrl,
                    loader = loader,
                    corner = 6.dp,
                    modifier = Modifier
                        .size(widthDp * 1.24f, widthDp * 1.28f)
                        .clickable { onPlaylistClick(playlist) },
                )
            }
        }
    }
}
