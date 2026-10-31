// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Current showcase choice, published by the host so the playlist page can read it. */
val LocalPlaylistShowcase = androidx.compose.runtime.staticCompositionLocalOf { "off" }

enum class PlaylistShowcase(val key: String, val label: String) {
    Off("off", "关闭"),
    Ribbon("ribbon", "立体缎带"),
    Shelf("shelf", "CD 唱片架"),
    ;

    companion object {
        fun of(value: String?): PlaylistShowcase = entries.firstOrNull { it.key == value } ?: Off
        fun next(value: String?): PlaylistShowcase = entries[(of(value).ordinal + 1) % entries.size]
    }
}

/**
 * Ribbon geometry, ported from CurrentMusicX `DepthRibbonGeometry`.
 *
 * Sample: `(sin t, cos t · 0.28, sin 2t · 0.62)` — an ellipse whose z crosses zero twice, which is
 * what makes the loop pass behind itself. Positions are scaled by a radius and projected with a
 * perspective divide.
 *
 * The earlier version used a fixed radius and small covers, which packed the sleeves into a knot in
 * the middle of the frame. The radius now follows the frame, as the reference's does.
 */
private fun ribbonPosition(index: Int, count: Int, radius: Float, phase: Float): Triple<Float, Float, Float> {
    val t = ((index + phase) / count) * 2f * PI.toFloat()
    val x = sin(t)
    val y = cos(t) * 0.28f
    val z = sin(2 * t) * 0.62f
    val depth = (z + 2.2f).coerceAtLeast(0.35f)
    return Triple(x * radius / depth, y * radius / depth, depth)
}

@Composable
fun PlaylistRibbon(
    playlists: List<Playlist>,
    loader: ArtworkLoader,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return
    val count = playlists.size.coerceAtMost(20)
    var phase by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, delta -> phase -= delta / 120f }
            },
    ) {
        val shortSide = minOf(maxWidth, maxHeight)
        val coverSize = shortSide * 0.34f
        val radiusPx = with(density) { shortSide.toPx() } * 1.4f
        val radiusDp = with(density) { radiusPx.toDp() }.value
        val centerX = maxWidth.value * 0.5f
        val centerY = maxHeight.value * 0.5f
        // px→dp for the projected offsets: the projection output is in the same units as the radius.
        val unit = radiusDp / radiusPx
        playlists.take(count)
            .mapIndexed { index, playlist -> index to playlist }
            .sortedByDescending { (index, _) -> ribbonPosition(index, count, radiusDp, phase).third }
            .forEach { (index, playlist) ->
                val (x, y, depth) = ribbonPosition(index, count, radiusDp, phase)
                val scale = (1.7f / depth).coerceIn(0.55f, 1.4f)
                Box(
                    Modifier.graphicsLayer {
                        translationX = with(density) { ((centerX + x * unit)).dp.toPx() }
                        translationY = with(density) { ((centerY + y * unit)).dp.toPx() }
                        scaleX = scale
                        scaleY = scale
                        alpha = ((1.9f - depth) * 0.8f).coerceIn(0.3f, 1f)
                    },
                ) {
                    Artwork(
                        url = playlist.coverUrl,
                        loader = loader,
                        corner = 14.dp,
                        modifier = Modifier
                            .size(coverSize)
                            .clickable { onPlaylistClick(playlist) },
                    )
                }
            }
    }
}

/**
 * CD shelf, ported from CurrentMusicX `RecordShelfGeometry`.
 *
 * Its measured ratios are kept and expressed against the frame width `W`: case width `0.435 W`,
 * pitch `0.069 W`, leading margin `0.12 W`, and each further case rises `0.046 W`. The rise is what
 * makes the row read as cases leaning in a rack; without it the covers sit in a straight line and
 * look like a scaled-up carousel.
 */
@Composable
fun PlaylistShelf(
    playlists: List<Playlist>,
    loader: ArtworkLoader,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entry by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 680),
        label = "shelf-entry",
    )
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(AppTheme.palette.background),
    ) {
        val w = maxWidth
        val caseW = w * 0.435f
        val caseH = w * 0.45f
        val pitch = w * 0.069f
        val lead = w * 0.12f
        val rise = w * 0.046f
        val top = maxHeight * 0.34f
        val slide = with(density) { (w * 1.1f).toPx() } * (1f - entry)
        playlists.take(14).forEachIndexed { index, playlist ->
            val x = with(density) { (lead + pitch * index).toPx() }
            val y = with(density) { (top - rise * index).toPx() }
            Box(
                Modifier.graphicsLayer {
                    translationX = x + slide
                    translationY = y
                    rotationZ = -9f
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 1f)
                    shadowElevation = 16f
                },
            ) {
                Artwork(
                    url = playlist.coverUrl,
                    loader = loader,
                    corner = 4.dp,
                    modifier = Modifier
                        .size(caseW, caseH)
                        .clickable { onPlaylistClick(playlist) },
                )
            }
        }
    }
}

@Composable
fun PlaylistShowcaseScreen(
    style: PlaylistShowcase,
    title: String,
    tracks: List<Track>,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val covers = tracks.map { Playlist(id = it.id, name = it.name, coverUrl = it.coverUrl) }
    Box(modifier.fillMaxSize().background(AppTheme.palette.background)) {
        val pick: (Playlist) -> Unit = { playlist ->
            onTrackClick(tracks.indexOfFirst { it.id == playlist.id })
        }
        if (style == PlaylistShowcase.Ribbon) {
            PlaylistRibbon(
                playlists = covers,
                loader = loader,
                onPlaylistClick = pick,
                modifier = Modifier.fillMaxSize().padding(top = 72.dp),
            )
        } else {
            PlaylistShelf(
                playlists = covers,
                loader = loader,
                onPlaylistClick = pick,
                modifier = Modifier.fillMaxSize().padding(top = 72.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = com.yunyin.music.ui.icons.SfIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = AppTheme.palette.label,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                fontFamily = SFPro,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
