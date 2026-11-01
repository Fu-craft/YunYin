// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import coil3.request.allowHardware
import coil3.toBitmap
import androidx.compose.foundation.shape.CircleShape
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
    songsIn: List<com.yunyin.music.ui.components.shelf.ShelfSong>,
    pickIndex: Int = -1,
    /** Current song name, artists and slot, mirrored from the renderer. */
    onCurrent: (String, String, Int) -> Unit = { _, _, _ -> },
    onActivate: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (songsIn.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val renderer = remember(context) {
        com.yunyin.music.ui.components.shelf.RecordShelfView(context).apply {
            bottomInset = 0f
            setRunning(true)
        }
    }
    SideEffect { renderer.configure(songsIn) }
    // Sampling, not the callback: the header must never depend on a callback that fires only when
    // the selected slot changes.
    LaunchedEffect(renderer) {
        while (true) {
            kotlinx.coroutines.delay(100)
            onCurrent(renderer.currentName, renderer.currentArtists, renderer.currentIndex)
        }
    }
    LaunchedEffect(pickIndex) {
        if (pickIndex in songsIn.indices) renderer.pick(pickIndex)
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { renderer },
        update = { it.onActivate = { index -> onActivate(index) } },
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
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val visible = tracks.take(60)
    // Background colour is sampled from the first cover, like the reference: a fixed dark plate made
    // the scene look pasted on rather than lit by the album art.
    val context = androidx.compose.ui.platform.LocalContext.current
    var sceneColor by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.graphics.Color(0xFF0B0B0D)) }
    var pickRequest by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var current by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var songTitle by remember { androidx.compose.runtime.mutableStateOf(title) }
    var songArtists by remember { androidx.compose.runtime.mutableStateOf(visible.size.toString() + " 首") }
    // Sliding onto a record starts it, without a second tap. A short dwell keeps a flick from
    // firing a burst of tracks on the way past.
    androidx.compose.runtime.LaunchedEffect(current) {
        if (current in visible.indices) {
            kotlinx.coroutines.delay(1800)
            onTrackClick(current)
        }
    }
    androidx.compose.runtime.LaunchedEffect(visible.firstOrNull()?.coverUrl) {
        val url = visible.firstOrNull()?.coverUrl ?: return@LaunchedEffect
        sceneColor = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val request = coil3.request.ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = coil3.SingletonImageLoader.get(context).execute(request)
                val image = (result as? coil3.request.SuccessResult)?.image
                    ?: return@runCatching androidx.compose.ui.graphics.Color(0xFF0B0B0D)
                @Suppress("NAME_SHADOWING") val bitmap: android.graphics.Bitmap = image.toBitmap()
                val palette = androidx.palette.graphics.Palette.Builder(bitmap).generate()
                androidx.compose.ui.graphics.Color(palette.getDarkMutedColor(-0xF4F4F3))
            }.getOrDefault(androidx.compose.ui.graphics.Color(0xFF0B0B0D))
        }
    }
    val scene: androidx.compose.ui.graphics.Color by androidx.compose.animation.animateColorAsState(
        targetValue = sceneColor,
        animationSpec = androidx.compose.animation.core.tween(600),
        label = "scene-color",
    )
    // Recorded layer behind the controls: liquid glass needs a backdrop to refract, otherwise the
    // buttons render as flat grey circles.
    val sceneBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop { drawRect(androidx.compose.ui.graphics.Color.Black); drawContent() }
    val covers = visible.map { Playlist(id = it.id, name = it.name, coverUrl = it.coverUrl) }
    val pick: (Playlist) -> Unit = { playlist ->
        onTrackClick(tracks.indexOfFirst { it.id == playlist.id })
    }
    Box(
        modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(scene, androidx.compose.ui.graphics.Color(0xFF06060A)),
                ),
            ),
    ) {
        if (tracks.isEmpty()) {
            androidx.compose.material3.CircularProgressIndicator(
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
        }
        if (style == PlaylistShowcase.Ribbon) {
        // The reference ribbon itself (DepthRibbonView + DepthRibbonPath), not a look-alike.
        com.yunyin.music.ui.components.shelf.PlaylistDepthRibbon(
            songs = tracks.map { track ->
                com.yunyin.music.ui.components.shelf.ShelfSong(
                    id = track.id,
                    name = track.name,
                    artists = track.artists.joinToString("/"),
                    album = "",
                    cover = track.coverUrl.orEmpty(),
                )
            },
            canPlay = true,
            onPlay = { index -> onTrackClick(index) },
            onPrepare = { { } },
            onClose = onBack,
        )
        } else {
            PlaylistShelf(
                songsIn = visible.map { track ->
                    com.yunyin.music.ui.components.shelf.ShelfSong(
                        id = track.id,
                        name = track.name,
                        artists = track.artists.joinToString("/"),
                        album = "",
                        cover = track.coverUrl.orEmpty(),
                    )
                },
                pickIndex = pickRequest,
                onCurrent = { name, artists, index ->
                    songTitle = name.ifBlank { title }
                    songArtists = artists.ifBlank { visible.size.toString() + " 首" }
                    if (index >= 0) current = index
                },
                onActivate = { index -> if (index in visible.indices) onTrackClick(index) },
                modifier = Modifier.fillMaxSize().padding(top = 120.dp, bottom = 120.dp),
            )
        }

        // Shelf only: the ported ribbon draws its own title and transport, and our pill was
        // covering them (which is why playback looked dead there).
        if (style == PlaylistShowcase.Shelf) {
        // Round controls over the scene: back left, search right. Translucent white on the
        // cover-derived backdrop, which is the same material language as the rest of the app.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShowcaseCircleButton(onClick = onBack) {
                Icon(
                    imageVector = com.yunyin.music.ui.icons.SfIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            ShowcaseCircleButton(onClick = onSearch) {
                Icon(
                    imageVector = com.yunyin.music.ui.icons.SfIcons.MagnifyingGlass,
                    contentDescription = "搜索歌单",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
        }
        // Header: back + title + subtitle, as in the reference's top row.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).padding(horizontal = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = songTitle,
                    fontFamily = SFPro,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    textAlign = TextAlign.Center,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = visible.getOrNull(current)?.artists?.joinToString("/") ?: "${tracks.size} 首",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
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
                .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f))
                .clickable {
                    // One index for both: previously the shelf slid to one record while playback
                    // started a different one, because each side rolled its own random.
                    val index = visible.indices.randomOrNull()
                    if (index != null) {
                        pickRequest = index
                        onTrackClick(index)
                    } else {
                        onShufflePick()
                    }
                }
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = com.yunyin.music.ui.icons.SfIcons.Shuffle,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(0xFF111111),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "帮我挑一首",
                    fontFamily = SFPro,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color(0xFF111111),
                    textAlign = TextAlign.Center,
                )
            }
        }
        }
    }
}

@Composable
private fun ShowcaseCircleButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop { drawRect(Color.Black); drawContent() }
    Box(
        Modifier
            .size(44.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { CircleShape },
                effects = { lens(12f.dp.toPx(), 24f.dp.toPx()) },
                onDrawSurface = { drawRect(Color.White.copy(alpha = 0.16f)) },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
