// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors
//
// 移植自 AuraPlayer（AGPL-3.0-or-later）的列表单元：
//   ui/CatalogDetails.kt  ArtistContentsPage 的行/网格结构
//   ui/LibraryPages.kt    SongRow（ONE 风格分支）、CollectionTile
// 只把数据边界换成了我们的 Track/Album，视觉参数（尺寸、字重、字号、圆角、透明度）保持原值。

package com.yunyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.core.Track
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/** 列表封面圆角：AuraPlayer 的 `ListArtworkCornerRadius`。 */
private val ListArtworkCornerRadius = 12.dp

/**
 * AuraPlayer 的歌曲行（`SongRow` 的 ONE 风格分支）。
 *
 * 与原版的差别只在于数据：`model.current?.key == song.key` 换成了调用方算好的 [isCurrent]。
 */
@Composable
fun AuraSongRow(
    track: Track,
    loader: ArtworkLoader,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkSize: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val palette = AppTheme.palette
    val rowBg = if (isCurrent) palette.label.copy(alpha = 0.08f) else Color.Transparent
    val titleColor = if (isCurrent) palette.accent else palette.label
    val subColor = palette.secondaryLabel

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.coverUrl,
            loader = loader,
            corner = ListArtworkCornerRadius,
            requestSize = 160,
            modifier = Modifier.size(artworkSize),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = SFPro,
                fontSize = 13.sp,
                color = subColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * AuraPlayer 的 `CollectionTile`：方形封面 + 标题 + 副标题，整格可点。
 *
 * 原版用 `AsyncImage` 与 `MiuixTheme.textStyles.body2`，这里对应 `Artwork` 与 SF Pro 13sp。
 */
@Composable
fun AuraCollectionTile(
    title: String,
    subtitle: String,
    artUrl: String,
    loader: ArtworkLoader,
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
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Artwork(
                url = artUrl,
                loader = loader,
                corner = if (circular) 999.dp else 16.dp,
                requestSize = 480,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
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
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = palette.label.copy(alpha = 0.66f),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * AuraPlayer 的分段 tab（`ArtistContentsPage` 的 `albums` 开关在界面上的形态）：
 * 一条胶囊轨道，选中项填充、其余透明。
 */
@Composable
fun AuraSegmentedTabs(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = AppTheme.palette
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = selected == index
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) palette.label.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) palette.label else palette.secondaryLabel,
                )
            }
        }
    }
}
