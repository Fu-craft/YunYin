// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yunyin.music.aura.AppleMusicArtistLogoManager
import com.yunyin.music.aura.AuraColorSource
import com.yunyin.music.aura.AuraViewModel
import com.yunyin.music.aura.prewarmArtworkColor
import com.yunyin.music.aura.CatalogBackground
import com.yunyin.music.aura.CatalogDetailPage
import com.yunyin.music.aura.MusicCollection
import com.yunyin.music.aura.NowPlayingConfig
import com.yunyin.music.aura.Song
import com.yunyin.music.core.AlbumInfo
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.ArtistUiState

/**
 * 歌手页 —— **AuraPlayer 的页面原样渲染**。
 *
 * 页面本体是 [CatalogDetailPage]（`tools/aura-wip` 里那份 `CatalogDetails.kt` 的原件，逐行未改），
 * 这里只做数据适配：把 [ArtistUiState] 装成它的 `MusicCollection` + `AuraViewModel`，
 * 再把它的播放 / 打开专辑回调转到我们的回调上。
 */
@Composable
fun ArtistScreen(
    state: ArtistUiState,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onTrackClick: (Track, Int) -> Unit,
    onPlayAll: () -> Unit,
    modifier: Modifier = Modifier,
    onShufflePlay: () -> Unit = {},
    followed: Boolean = false,
    onToggleFollow: (() -> Unit)? = null,
    onAllTrackClick: (Track, Int) -> Unit = { _, _ -> },
    onLoadMoreSongs: () -> Unit = {},
    onLoadMoreAlbums: () -> Unit = {},
    onAlbumClick: (AlbumInfo) -> Unit = {},
    nowPlayingId: Long? = null,
    isPlaying: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 取色走 UI 已经缓存的封面位图，不再单独下一次网络（第一次之后都是内存命中）。
    remember(loader) {
        AuraColorSource.loadBitmap = { url, size -> loader.load(url, size) }
        true
    }

    // 歌手图先用 AuraPlayer 的来源（Apple Music 艺术家图），拿不到才回落到网易云封面。
    var artistArtwork by remember(state.id, state.name) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.id, state.name) {
        artistArtwork = runCatching {
            AppleMusicArtistLogoManager.resolveArtistMedia(
                context = context,
                artistName = state.name,
                needLogo = false,
                needArtwork = true,
                needMotionVideo = false,
            ).artworkUrl
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val artworkUrl = artistArtwork?.takeIf { it.isNotBlank() } ?: state.coverUrl.orEmpty()

    LaunchedEffect(artworkUrl) { prewarmArtworkColor(artworkUrl) }

    val tracks: List<Track> = remember(state.hotSongs, state.allSongs) {
        if (state.hotSongs.isNotEmpty()) state.hotSongs else state.allSongs
    }
    val songs: List<Song> = remember(tracks) { tracks.map { it.toAuraSong() } }
    val current: Song? = remember(songs, nowPlayingId) { songs.firstOrNull { it.id == nowPlayingId } }

    val collection = remember(state.id, state.name, artworkUrl, state.musicSize, songs) {
        MusicCollection(
            key = "artist:${state.id}",
            title = state.name,
            subtitle = if (state.musicSize > 0) "${state.musicSize} 首" else "",
            songs = songs,
            artwork = artworkUrl,
        )
    }

    val model = remember(songs, current) {
        AuraViewModel(
            songs = songs,
            sortedSongs = songs,
            current = current,
            onPlayCollectionAt = { list, index ->
                val source = tracks.getOrNull(index)
                if (source != null) onTrackClick(source, index) else Unit
            },
            onPlaySongNow = { song ->
                val index = tracks.indexOfFirst { it.id == song.id }
                if (index >= 0) onTrackClick(tracks[index], index)
            },
            onOpenAlbum = { song ->
                val album = state.albums.firstOrNull { it.id == song.albumId }
                if (album != null) onAlbumClick(album) else Unit
            },
            onLoadMoreAlbumPages = onLoadMoreAlbums,
            onLoadMoreSongPages = onLoadMoreSongs,
        )
    }

    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        // 他页面自己的背景：从封面主色渐到深底（CatalogBackground → CatalogNowPlayingBackground）。
        CatalogBackground(
            collection = collection,
            nowPlayingConfig = NowPlayingConfig(),
            immersiveAlbum = true,
        )
        CatalogDetailPage(
            model = model,
            padding = PaddingValues(0.dp),
            collection = collection,
            cloud = null,
            destinationId = state.id,
            nowPlayingConfig = NowPlayingConfig(),
        )
    }
}

/** 我们的 [Track] → AuraPlayer 的 `Song`。 */
private fun Track.toAuraSong(): Song = Song(
    id = id,
    title = name,
    artist = artistLine,
    album = albumName,
    albumId = albumId,
    artistId = artistIds.firstOrNull() ?: 0L,
    duration = durationMs,
    artwork = coverUrl.orEmpty(),
)
