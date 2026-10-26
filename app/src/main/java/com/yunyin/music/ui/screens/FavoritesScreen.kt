// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.AppContainer
import com.yunyin.music.core.FavoriteAlbum
import com.yunyin.music.core.FavoriteArtist
import com.yunyin.music.core.FavoritePage
import com.yunyin.music.core.FavoritePlaylist
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.FavoritesRules
import com.yunyin.music.data.FavoritesRules.Segment
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.PagingAutoLoader
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import kotlinx.coroutines.launch

private data class FavoritePageState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val appendError: Boolean = false,
)

private data class PendingUnsubscribe(val segment: Segment, val id: Long, val name: String)

/**
 * 我的收藏: subscribed artists / albums / playlists. The three lists share the existing
 * [PagingAutoLoader] guards, one glass top bar and the same 44 dp segmented control idiom as
 * 评论 / 本地音乐 / 下载中心.
 */
@Composable
fun FavoritesScreen(
    signedIn: Boolean,
    onBack: () -> Unit,
    onArtistClick: (FavoriteArtist) -> Unit,
    onAlbumClick: (FavoriteAlbum) -> Unit,
    onPlaylistClick: (FavoritePlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember { AppContainer.from(context) }
    val scope = rememberCoroutineScope()
    var segment by remember { mutableStateOf(Segment.Artists) }
    var artistState by remember { mutableStateOf(FavoritePageState<FavoriteArtist>()) }
    var albumState by remember { mutableStateOf(FavoritePageState<FavoriteAlbum>()) }
    var playlistState by remember { mutableStateOf(FavoritePageState<FavoritePlaylist>()) }
    var pending by remember { mutableStateOf<PendingUnsubscribe?>(null) }
    val listState = rememberLazyListState()
    val backdrop = rememberGlassTopBarBackdrop()

    fun loadArtists(reset: Boolean) {
        if (!signedIn || artistState.loading) return
        if (!reset && !FavoritesRules.shouldRequest(artistState.loaded, artistState.loading, artistState.hasMore, artistState.appendError)) return
        artistState = artistState.copy(loading = true, error = null, appendError = false)
        scope.launch {
            when (val result = container.music.favoriteArtists(if (reset) 0 else artistState.items.size)) {
                is NetResult.Ok -> artistState = applyPage(artistState, result.value, reset)
                is NetResult.Err -> artistState = artistState.copy(
                    loading = false,
                    loaded = true,
                    error = if (reset) result.message else artistState.error,
                    appendError = !reset,
                )
            }
        }
    }

    fun loadAlbums(reset: Boolean) {
        if (!signedIn || albumState.loading) return
        if (!reset && !FavoritesRules.shouldRequest(albumState.loaded, albumState.loading, albumState.hasMore, albumState.appendError)) return
        albumState = albumState.copy(loading = true, error = null, appendError = false)
        scope.launch {
            when (val result = container.music.favoriteAlbums(if (reset) 0 else albumState.items.size)) {
                is NetResult.Ok -> albumState = applyPage(albumState, result.value, reset)
                is NetResult.Err -> albumState = albumState.copy(
                    loading = false,
                    loaded = true,
                    error = if (reset) result.message else albumState.error,
                    appendError = !reset,
                )
            }
        }
    }

    fun loadPlaylists(reset: Boolean) {
        if (!signedIn || playlistState.loading) return
        if (!reset && !FavoritesRules.shouldRequest(playlistState.loaded, playlistState.loading, playlistState.hasMore, playlistState.appendError)) return
        playlistState = playlistState.copy(loading = true, error = null, appendError = false)
        scope.launch {
            when (val result = container.music.favoritePlaylists(if (reset) 0 else playlistState.items.size)) {
                is NetResult.Ok -> playlistState = applyPage(playlistState, result.value, reset)
                is NetResult.Err -> playlistState = playlistState.copy(
                    loading = false,
                    loaded = true,
                    error = if (reset) result.message else playlistState.error,
                    appendError = !reset,
                )
            }
        }
    }

    LaunchedEffect(segment, signedIn) {
        listState.scrollToItem(0)
        if (!signedIn) return@LaunchedEffect
        when (segment) {
            Segment.Artists -> if (!artistState.loaded) loadArtists(true)
            Segment.Albums -> if (!albumState.loaded) loadAlbums(true)
            Segment.Playlists -> if (!playlistState.loaded) loadPlaylists(true)
        }
    }

    val count = when (segment) {
        Segment.Artists -> artistState.items.size
        Segment.Albums -> albumState.items.size
        Segment.Playlists -> playlistState.items.size
    }
    val loading = when (segment) {
        Segment.Artists -> artistState.loading
        Segment.Albums -> albumState.loading
        Segment.Playlists -> playlistState.loading
    }
    val loaded = when (segment) {
        Segment.Artists -> artistState.loaded
        Segment.Albums -> albumState.loaded
        Segment.Playlists -> playlistState.loaded
    }
    val error = when (segment) {
        Segment.Artists -> artistState.error
        Segment.Albums -> albumState.error
        Segment.Playlists -> playlistState.error
    }
    val hasMore = when (segment) {
        Segment.Artists -> artistState.hasMore
        Segment.Albums -> albumState.hasMore
        Segment.Playlists -> playlistState.hasMore
    }
    val appendError = when (segment) {
        Segment.Artists -> artistState.appendError
        Segment.Albums -> albumState.appendError
        Segment.Playlists -> playlistState.appendError
    }

    BackHandler { onBack() }

    Box(modifier.fillMaxSize().background(AppTheme.palette.background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = glassTopBarTopPadding(), bottom = 32.dp),
            ) {
                item("segments") {
                    FavoriteSegments(segment) { segment = it }
                }
                when {
                    !signedIn -> item("empty") { FavoriteEmpty(FavoritesRules.emptyText(segment, false)) }
                    loading && count == 0 -> items(6) { FavoriteSkeleton() }
                    error != null && count == 0 -> item("error") {
                        FavoriteError(error) { when (segment) {
                            Segment.Artists -> loadArtists(true)
                            Segment.Albums -> loadAlbums(true)
                            Segment.Playlists -> loadPlaylists(true)
                        } }
                    }
                    count == 0 && loaded -> item("empty") {
                        FavoriteEmpty(FavoritesRules.emptyText(segment, true))
                    }
                    else -> {
                        when (segment) {
                            Segment.Artists -> items(artistState.items, key = { "artist-${it.id}" }) { artist ->
                                FavoriteRow(
                                    coverUrl = artist.coverUrl,
                                    title = artist.name,
                                    subtitle = "${artist.songCount} 首 · ${artist.albumCount} 张专辑",
                                    onClick = { onArtistClick(artist) },
                                    onLongClick = { pending = PendingUnsubscribe(segment, artist.id, artist.name) },
                                )
                            }
                            Segment.Albums -> items(albumState.items, key = { "album-${it.id}" }) { album ->
                                FavoriteRow(
                                    coverUrl = album.coverUrl,
                                    title = album.name,
                                    subtitle = listOf(album.artistLine, album.year.takeIf { it > 0 }?.toString().orEmpty())
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    onClick = { onAlbumClick(album) },
                                    onLongClick = { pending = PendingUnsubscribe(segment, album.id, album.name) },
                                )
                            }
                            Segment.Playlists -> items(playlistState.items, key = { "playlist-${it.id}" }) { playlist ->
                                FavoriteRow(
                                    coverUrl = playlist.coverUrl,
                                    title = playlist.name,
                                    subtitle = "${playlist.creatorName} · ${playlist.trackCount} 首",
                                    onClick = { onPlaylistClick(playlist) },
                                    onLongClick = { pending = PendingUnsubscribe(segment, playlist.id, playlist.name) },
                                )
                            }
                        }
                        if (loading) item("loading-more") { FavoriteLoadingMore() }
                        if (appendError) item("append-error") {
                            FavoriteError("加载下一页失败") {
                                when (segment) {
                                    Segment.Artists -> loadArtists(false)
                                    Segment.Albums -> loadAlbums(false)
                                    Segment.Playlists -> loadPlaylists(false)
                                }
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(backdrop = backdrop, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(
                        indication = rememberControlRipple(bounded = false),
                        interactionSource = null,
                        onClick = onBack,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SfIcons.ChevronLeft, contentDescription = "返回", tint = AppTheme.palette.accent, modifier = Modifier.size(22.dp))
                }
                Text(
                    "我的收藏",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = AppTheme.palette.label,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        val removal = pending
        if (removal != null) {
            AlertDialog(
                onDismissRequest = { pending = null },
                title = { Text("取消收藏", fontFamily = SFPro, fontWeight = FontWeight.SemiBold) },
                text = { Text("不再收藏「${removal.name}」？", fontFamily = SFPro) },
                confirmButton = {
                    TextButton(onClick = {
                        pending = null
                        scope.launch {
                            val kind = when (removal.segment) {
                                Segment.Artists -> "artist"
                                Segment.Albums -> "album"
                                Segment.Playlists -> "playlist"
                            }
                            when (val result = container.music.subscribe(kind, removal.id, false)) {
                                is NetResult.Ok -> {
                                    when (removal.segment) {
                                        Segment.Artists -> artistState = artistState.copy(items = artistState.items.filterNot { it.id == removal.id })
                                        Segment.Albums -> albumState = albumState.copy(items = albumState.items.filterNot { it.id == removal.id })
                                        Segment.Playlists -> playlistState = playlistState.copy(items = playlistState.items.filterNot { it.id == removal.id })
                                    }
                                }
                                is NetResult.Err -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("取消收藏", color = AppTheme.palette.accent) }
                },
                dismissButton = { TextButton(onClick = { pending = null }) { Text("保留") } },
            )
        }
    }

    if (signedIn) {
        val total = listState.layoutInfo.totalItemsCount
        PagingAutoLoader(
            lastVisibleItemIndex = { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 },
            totalItemCount = { listState.layoutInfo.totalItemsCount },
            hasMore = hasMore,
            loadingMore = loading,
            appendError = appendError,
            hasItems = count > 0,
            onLoadMore = {
                when (segment) {
                    Segment.Artists -> loadArtists(false)
                    Segment.Albums -> loadAlbums(false)
                    Segment.Playlists -> loadPlaylists(false)
                }
            },
        )
    }
}

private fun <T> applyPage(state: FavoritePageState<T>, page: FavoritePage<T>, reset: Boolean): FavoritePageState<T> {
    val items = if (reset) page.items else state.items + page.items
    return state.copy(items = items, loading = false, loaded = true, hasMore = page.more, error = null, appendError = false)
}

@Composable
private fun FavoriteSegments(selected: Segment, onSelect: (Segment) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.control))
            .background(AppTheme.palette.secondaryBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment.entries.forEach { segment ->
            val active = segment == selected
            Box(
                Modifier.weight(1f).fillMaxSize().clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .background(if (active) AppTheme.palette.accent.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(indication = rememberControlRipple(bounded = false), interactionSource = null) { onSelect(segment) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    segment.label,
                    fontFamily = SFPro,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                    color = if (active) AppTheme.palette.accent else AppTheme.palette.secondaryLabel,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    coverUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(68.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(url = coverUrl, loader = AppContainer.from(androidx.compose.ui.platform.LocalContext.current).artwork, corner = 8.dp, requestSize = 200, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = AppTheme.palette.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(SfIcons.ChevronRight, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FavoriteEmpty(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(SfIcons.Heart, contentDescription = null, tint = AppTheme.palette.tertiaryLabel, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun FavoriteError(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.height(44.dp).clip(ContinuousRoundedRectangle(AppleShapes.control)).clickable(indication = rememberControlRipple(bounded = false), interactionSource = null, onClick = onRetry).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SfIcons.ArrowClockwise, contentDescription = null, tint = AppTheme.palette.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("重试", fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.accent)
        }
    }
}

@Composable
private fun FavoriteSkeleton() {
    Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(ContinuousRoundedRectangle(8.dp)).background(AppTheme.palette.secondaryBackground))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(ContinuousRoundedRectangle(4.dp)).background(AppTheme.palette.secondaryBackground))
            Box(Modifier.fillMaxWidth(0.35f).height(10.dp).clip(ContinuousRoundedRectangle(4.dp)).background(AppTheme.palette.secondaryBackground))
        }
    }
}

@Composable
private fun FavoriteLoadingMore() {
    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
        Text("加载中…", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
    }
}
