// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.AlbumInfo
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.ArtistUiState
import com.yunyin.music.ui.components.AuraCollectionTile
import com.yunyin.music.ui.components.AuraSongRow
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 歌手页 —— 版式取自 AuraPlayer 的艺术家页
 * （`CatalogDetails.kt` 的 artist hero + `ArtistContentsPage` 的列表）：
 *
 * ```
 * 全幅头图（底部渐隐）
 *   返回                    ← 左上
 * 歌手名          28sp 白色粗体，居中
 * (ⓘ)   (▶ 84dp)   (★)      ← 三颗圆形按钮
 * ┌ 最新发行 ─────────────┐  ← 玻璃卡片：封面 64dp / 日期 / 专辑名 / 曲目数
 * 热门歌曲 ›
 * 列表行…
 * 专辑（两列网格）
 * ```
 *
 * 与原版的差别只在数据来源：`AuraViewModel` / `MusicCollection` 换成本项目的 [ArtistUiState]。
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
    val palette = AppTheme.palette
    val listState = rememberLazyListState()
    val latestAlbum = remember(state.albums) { state.albums.firstOrNull() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(palette.background),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // ---------------------------------------------------------------- hero
        item("hero") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // AuraPlayer：height = (宽 / 0.95).coerceIn(360, 500) × 1.125
                val heroHeight = (maxWidth / 0.95f).coerceIn(360.dp, 500.dp) * (1.25f * 0.90f)

                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    val heroBackdrop = rememberLayerBackdrop { drawContent() }
                    Box(Modifier.fillMaxSize().layerBackdrop(heroBackdrop)) {
                    com.yunyin.music.ui.components.AuraArtwork(
                        uri = state.coverUrl.orEmpty(),
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 0.dp,
                    )
                    }
                    // 头图下缘的纹理模糊（他 473-493 行的 progressiveTextureBlur 位置）：
                    // 用 miuix 0.9.4 的 textureBlur 采样同一图层，再用渐变把模糊限制在下缘。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .textureBlur(
                                backdrop = heroBackdrop,
                                shape = androidx.compose.ui.graphics.RectangleShape,
                                blurRadius = 3.8f,
                            )
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.52f to Color.Transparent,
                                        0.92f to Color.White,
                                        1f to Color.White,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                    // 底部渐隐：先用 DstIn 把图片本身淡出（他 339-461 行的画法），
                    // 再叠一层到页面底色的过渡（他的 heroMixTint / surface 三段）。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Color.Black,
                                        0.58f to Color.Black,
                                        0.88f to Color.Black.copy(alpha = 0.32f),
                                        1f to Color.Transparent,
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.46f to Color.Transparent,
                                    0.72f to palette.background.copy(alpha = 0.34f),
                                    0.96f to palette.background.copy(alpha = 0.74f),
                                    1f to palette.background.copy(alpha = 0.92f),
                                ),
                            ),
                    )

                    // 返回
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 12.dp, top = 8.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = SfIcons.ChevronLeft,
                            contentDescription = "返回",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = state.name.ifBlank { "歌手" },
                            fontFamily = SFPro,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            color = palette.label,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(
                            Modifier.padding(top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            // 他：IconButton(42dp, CircleShape, heroCardFill) { Text("i") }
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .clickable { },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "i",
                                    fontFamily = SFPro,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.label,
                                )
                            }
                            // 他：IconButton(60dp, accent) { Image(ic_player_play, 32dp) }
                            Box(
                                Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(palette.accent)
                                    .clickable(onClick = onPlayAll),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(com.yunyin.music.R.drawable.aura_ic_play),
                                    contentDescription = "播放",
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black),
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                            // 他：IconButton(42dp) { Image(ic_player_favorite / _on, 24dp) }
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .clickable { onToggleFollow?.invoke() },
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(
                                        if (followed) com.yunyin.music.R.drawable.aura_ic_favorite_on
                                        else com.yunyin.music.R.drawable.aura_ic_favorite,
                                    ),
                                    contentDescription = "收藏艺术家",
                                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(palette.label),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------- 最新发行（玻璃卡片）
        if (latestAlbum != null) {
            item("latest") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(palette.label.copy(alpha = 0.06f))
                        .clickable { onAlbumClick(latestAlbum) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.yunyin.music.ui.components.AuraArtwork(
                        uri = latestAlbum.coverUrl.orEmpty(),
                        modifier = Modifier.size(64.dp),
                        cornerRadius = 12.dp,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            text = formatReleaseDate(latestAlbum.publishTime),
                            fontFamily = SFPro,
                            fontSize = 12.sp,
                            color = palette.secondaryLabel,
                        )
                        Text(
                            text = latestAlbum.name,
                            fontFamily = SFPro,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            text = if (latestAlbum.trackCount > 0) "${latestAlbum.trackCount} 首歌曲" else "专辑",
                            fontFamily = SFPro,
                            fontSize = 12.sp,
                            color = palette.secondaryLabel,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Icon(
                        imageVector = SfIcons.Plus,
                        contentDescription = "添加到播放列表",
                        tint = palette.label,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // ------------------------------------------------------------ 热门歌曲
        val songs = if (state.hotSongs.isNotEmpty()) state.hotSongs else state.allSongs
        item("hot-header") {
            SectionHeader(title = "热门歌曲", showChevron = true)
        }
        if (songs.isEmpty()) {
            item("hot-empty") {
                Text(
                    text = if (state.loading) "加载中…" else "暂无歌曲",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = palette.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        } else {
            items(songs.size) { index ->
                val track = songs[index]
                AuraSongRow(
                    track = track,
                    isCurrent = track.id == nowPlayingId,
                    onClick = { onTrackClick(track, index) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        // ---------------------------------------------------------------- 专辑
        if (state.albums.isNotEmpty()) {
            item("albums-header") { SectionHeader(title = "专辑", showChevron = false) }
            items(state.albums.chunked(2).size) { rowIndex ->
                val pair = state.albums.chunked(2)[rowIndex]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    pair.forEach { album ->
                        AuraCollectionTile(
                            title = album.name,
                            subtitle = if (album.trackCount > 0) "${album.trackCount} 首" else "",
                            artUrl = album.coverUrl.orEmpty(),
                            onClick = { onAlbumClick(album) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        item("bottom") { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
}

@Composable
private fun HeroCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    container: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(size * 0.42f),
        )
    }
}

@Composable
private fun SectionHeader(title: String, showChevron: Boolean) {
    val palette = AppTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontFamily = SFPro,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = palette.label,
        )
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = SfIcons.ChevronRight,
                contentDescription = null,
                tint = palette.secondaryLabel,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 2026年11月8日 —— AuraPlayer 的发行日期写法。 */
private fun formatReleaseDate(epochMs: Long): String {
    if (epochMs <= 0L) return "最新发行"
    return SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(epochMs))
}
