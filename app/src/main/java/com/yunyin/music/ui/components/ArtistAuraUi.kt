// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
//
// 以下三个组件逐行移植自 AuraPlayer（AGPL-3.0-or-later）：
//   ui/LibraryPages.kt  Artwork / SongRow（ONE 风格分支）/ CollectionTile
// 仅替换了数据边界（Song → Track）与两处运行时依赖：
//   MiuixTheme.colorScheme.* → 本项目 AppTheme.palette.*
//   coil SingletonImageLoader 的内存键优化 → 直接 AsyncImage
// 视觉参数（尺寸 / 字号 / 字重 / 圆角 / 透明度 / 结构）保持原值。

package com.yunyin.music.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.yunyin.music.core.Track
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/** AuraPlayer `LibraryPages.kt` 的 `ListArtworkCornerRadius`。 */
private val ListArtworkCornerRadius = 12.dp

/**
 * AuraPlayer 的 `Artwork`：渐变底 + 「♫」占位 + coil 裁切填充。
 *
 * 原版会从 `SingletonImageLoader` 里取同名曲封的内存键来避免闪烁，这里直接用 [AsyncImage]；
 * 其余（渐变配色、占位符、圆角/CircleShape 分支）与原版一致。
 */
@Composable
fun AuraArtwork(
    uri: String,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
    cornerRadius: Dp = 16.dp,
) {
    val palette = AppTheme.palette
    Box(
        modifier
            .clip(if (circular) CircleShape else RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    listOf(
                        palette.label.copy(alpha = 0.10f),
                        palette.label.copy(alpha = 0.04f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text("♫", fontSize = 28.sp, color = palette.label.copy(alpha = 0.7f))
        if (uri.isNotBlank()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 3:45 —— AuraPlayer 的 `durationText`。 */
internal fun auraDurationText(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * AuraPlayer `SongRow` 的 ONE 分支，逐行对应：
 * 44dp 封面（圆角 12）→ 标题 15.5sp（当前曲目 Bold + accent）→ `artist · album` 13sp →
 * 右侧时长 12sp + 40dp 的 ⋯ 按钮。
 */
@Composable
fun AuraSongRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkSize: Dp = 44.dp,
    onMore: (() -> Unit)? = null,
) {
    val palette = AppTheme.palette
    val rowBg = if (isCurrent) palette.label.copy(alpha = 0.08f) else Color.Transparent
    val titleColor = if (isCurrent) palette.accent else palette.label
    val subColor = palette.secondaryLabel

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuraArtwork(
            uri = track.coverUrl.orEmpty(),
            modifier = Modifier.size(artworkSize),
            cornerRadius = ListArtworkCornerRadius,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = track.name.ifBlank { "未命名歌曲" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = SFPro,
                fontSize = 15.5.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = titleColor,
            )
            Text(
                text = listOf(track.artistLine, track.albumName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = auraDurationText(track.durationMs),
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = subColor,
            )
            IconButton(onClick = { onMore?.invoke() }, modifier = Modifier.size(40.dp)) {
                Image(
                    imageVector = SfIcons.Ellipsis,
                    contentDescription = "更多",
                    modifier = Modifier.size(20.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        if (isCurrent) palette.accent else subColor,
                    ),
                )
            }
        }
    }
}

/** AuraPlayer 的 `CollectionTile`：方形封面 + 标题 + 副标题。 */
@Composable
fun AuraCollectionTile(
    title: String,
    subtitle: String,
    artUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
) {
    val palette = AppTheme.palette
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(2.dp),
    ) {
        AuraArtwork(
            uri = artUrl,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            circular = circular,
            cornerRadius = 16.dp,
        )
        Text(
            text = title,
            color = palette.label,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = subtitle,
            fontFamily = SFPro,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = palette.label.copy(alpha = 0.66f),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
