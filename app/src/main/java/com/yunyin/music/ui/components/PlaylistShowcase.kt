// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

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
 * CD 唱片架 — the reference's real shape, not a leaning row.
 *
 * Looking at the reference screenshots: the covers are **cards standing in perspective**, receding
 * into the distance along a diagonal, each turned away from the viewer (`rotationY`) and tilted
 * (`rotationZ`), with the nearest card large and sharp and the far ones small and blurred. A flat row
 * with a small Z-rotation — what the previous version did — cannot look like this, because the
 * defining cue is the perspective turn plus the depth blur.
 *
 * Built with `graphicsLayer { rotationY / rotationZ / cameraDistance }`: `cameraDistance` is the
 * lens-to-plane distance, so shrinking it (relative to the card) is what produces the strong
 * foreshortening the reference has.
 */
@Composable
fun PlaylistShelf(
    playlists: List<Playlist>,
    loader: ArtworkLoader,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return
    // The real renderer, ported verbatim from CurrentMusicX (custom View, 37 KB): frame clock,
    // quad paths, shadow plane, AGSL lighting, depth-of-field. Wrapped rather than reimplemented —
    // that is the only way the shelf actually looks like theirs.
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            // coil3 ships no network fetcher by default: `coil-compose` alone can only decode local
            // files, so every sleeve silently failed and the shelf drew empty cases.
            coil3.SingletonImageLoader.setSafe { ctx ->
                coil3.ImageLoader.Builder(ctx)
                    .components {
                        add(
                            coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                                callFactory = {
                                    okhttp3.OkHttpClient.Builder()
                                        .addInterceptor { chain ->
                                            chain.proceed(
                                                chain.request().newBuilder()
                                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                                                    .header("Referer", "https://music.163.com/")
                                                    .build(),
                                            )
                                        }
                                        .build()
                                },
                            ),
                        )
                    }
                    .build()
            }
            com.yunyin.music.ui.components.shelf.RecordShelfView(context).apply {
                topInset = 0f
                topInset = 0f
                bottomInset = 0f
                // Without this the shelf never leaves its intro state, so nothing ever slides out.
                setRunning(true)
            }
        },
        update = { view ->
            view.onActivate = { index -> playlists.getOrNull(index)?.let(onPlaylistClick) }
            val songs = playlists.map { playlist ->
                com.yunyin.music.ui.components.shelf.ShelfSong(
                    id = playlist.id,
                    name = playlist.name,
                    artists = "",
                    album = "",
                    cover = playlist.coverUrl.orEmpty(),
                )
            }
            if (view.tag != playlists.size to playlists.firstOrNull()?.id) {
                view.tag = playlists.size to playlists.firstOrNull()?.id
                view.configure(songs)
            }
        },
    )
}

/** 立体缎带 — the reference's loop, screen-scaled, draggable. */
@Composable
fun PlaylistRibbon(
    playlists: List<Playlist>,
    loader: ArtworkLoader,
    onPlaylistClick: (Playlist) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) return
    val count = playlists.size.coerceAtMost(20)
    var phase by androidx.compose.runtime.mutableFloatStateOf(0f)
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, delta ->
                    phase -= delta / 120f
                }
            },
    ) {
        val shortSide = minOf(maxWidth, maxHeight)
        val coverSize = shortSide * 0.36f
        val radiusDp = shortSide.value * 1.4f
        val centerX = maxWidth.value * 0.5f
        val centerY = maxHeight.value * 0.52f
        playlists.take(count)
            .mapIndexed { index, playlist -> index to playlist }
            .sortedByDescending { (index, _) -> ribbonDepth(index, count, phase) }
            .forEach { (index, playlist) ->
                val (x, y, depth) = ribbonPoint(index, count, radiusDp, phase)
                val scale = (1.7f / depth).coerceIn(0.5f, 1.45f)
                Box(
                    Modifier.graphicsLayer {
                        translationX = with(density) { (centerX + x).dp.toPx() }
                        translationY = with(density) { (centerY + y).dp.toPx() }
                        scaleX = scale
                        scaleY = scale
                        alpha = ((1.9f - depth) * 0.8f).coerceIn(0.28f, 1f)
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

private fun ribbonDepth(index: Int, count: Int, phase: Float): Float {
    val t = ((index + phase) / count) * 2f * kotlin.math.PI.toFloat()
    return (kotlin.math.sin(2 * t) * 0.62f + 2.2f).coerceAtLeast(0.35f)
}

private fun ribbonPoint(index: Int, count: Int, radius: Float, phase: Float): Triple<Float, Float, Float> {
    val t = ((index + phase) / count) * 2f * kotlin.math.PI.toFloat()
    val x = kotlin.math.sin(t)
    val y = kotlin.math.cos(t) * 0.28f
    val depth = ribbonDepth(index, count, phase)
    return Triple(x * radius / depth, y * radius / depth, depth)
}

/**
 * Full-screen showcase: title header, the scene, and the reference's 「帮我挑一首」 action.
 *
 * The reference's page is *only* the scene plus chrome — no track list underneath — so this replaces
 * the playlist page rather than decorating it.
 */
@Composable
fun PlaylistShowcaseScreen(
    style: PlaylistShowcase,
    title: String,
    tracks: List<Track>,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (index: Int) -> Unit,
    onShufflePick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val covers = tracks.map { Playlist(id = it.id, name = it.name, coverUrl = it.coverUrl) }
    val pick: (Playlist) -> Unit = { playlist ->
        onTrackClick(tracks.indexOfFirst { it.id == playlist.id })
    }
    Box(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0B0B0D))) {
        if (style == PlaylistShowcase.Ribbon) {
            PlaylistRibbon(covers, loader, pick, Modifier.fillMaxSize().padding(top = 120.dp, bottom = 120.dp))
        } else {
            PlaylistShelf(covers, loader, pick, Modifier.fillMaxSize().padding(top = 120.dp, bottom = 120.dp))
        }

        // Header: back + title + subtitle, as in the reference's top row.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = com.yunyin.music.ui.icons.SfIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontFamily = SFPro,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = AppTheme.palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${tracks.size} 首",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                )
            }
        }

        // Footer: the reference's shuffle action, in a pill.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                .background(AppTheme.palette.secondaryBackground)
                .clickable(onClick = onShufflePick)
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = com.yunyin.music.ui.icons.SfIcons.Shuffle,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "帮我挑一首",
                    fontFamily = SFPro,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.palette.label,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
