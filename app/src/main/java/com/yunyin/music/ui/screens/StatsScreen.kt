// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.data.stats.PlaybackStatsRecorder
import com.yunyin.music.data.stats.StatsBucket
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

private enum class StatsRange(val label: String, val days: Int) {
    Week("本周", 7),
    Month("本月", 30),
    Year("本年", 365),
    All("总共", Int.MAX_VALUE),
}

/**
 * 统计页。
 *
 * 布局参考用户给的样式：一张卡回答"我听了多久"，用分段控件切换时间范围，柱状图看分布，
 * 下面是次数 / 天数两个数字和最爱的一首。刻意不做成榜单页——榜单在 [PlaybackStatsScreen] 里，
 * 这一页只讲"量"。
 */
@Composable
fun StatsScreen(
    recorder: PlaybackStatsRecorder,
    onBack: () -> Unit,
    /** 取封面；为空时退回占位图标。 */
    coverUrlFor: (suspend (Long) -> String?)? = null,
    /** 有了 loader 才能真正把封面画出来。 */
    loader: com.yunyin.music.data.ArtworkLoader? = null,
    modifier: Modifier = Modifier,
) {
    val snapshot = recorder.snapshot
    var range by remember { mutableStateOf(StatsRange.Week) }

    val buckets: List<StatsBucket> = remember(snapshot, range) {
        val all = snapshot.days.sortedBy { it.key }
        if (range.days >= all.size) all else all.takeLast(range.days)
    }
    val totalMs = buckets.sumOf { it.totalMs }
    val plays = buckets.sumOf { it.plays }
    val activeDays = buckets.count { it.totalMs > 0 }
    val peak = (buckets.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L)
    val topSongs = remember(snapshot) { snapshot.songs.sortedByDescending { it.totalMs }.take(5) }
    var covers by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(topSongs, coverUrlFor) {
        val provider = coverUrlFor ?: return@LaunchedEffect
        covers = topSongs.mapNotNull { song ->
            runCatching { provider(song.id) }.getOrNull()?.let { song.id to it }
        }.toMap()
    }

    LazyColumn(
        modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
    ) {
        item("title") {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "统计",
                    fontFamily = SFPro,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                    color = AppTheme.palette.label,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "返回",
                    fontFamily = SFPro,
                    fontSize = 15.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
                )
            }
        }

        item("listening") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(AppTheme.palette.secondaryBackground)
                    .padding(18.dp),
            ) {
                Text(
                    text = "听歌统计",
                    fontFamily = SFPro,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.palette.label,
                )
                Spacer(Modifier.height(14.dp))
                RangeSegments(selected = range, onSelect = { range = it })
                Spacer(Modifier.height(16.dp))

                if (buckets.isEmpty()) {
                    Text(
                        text = "还没有听歌记录。听几首歌之后这里会出现统计。",
                        fontFamily = SFPro,
                        fontSize = 14.sp,
                        color = AppTheme.palette.secondaryLabel,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    return@Column
                }

                Text(
                    text = rangeLabel(range, buckets),
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDuration(totalMs),
                    fontFamily = SFPro,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = AppTheme.palette.label,
                )

                Spacer(Modifier.height(18.dp))
                DayBars(buckets = buckets, peak = peak)
                Spacer(Modifier.height(18.dp))

                Row(Modifier.fillMaxWidth()) {
                    StatCell(label = "听歌次数", value = plays.toString(), modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(38.dp)
                            .background(AppTheme.palette.separator.copy(alpha = 0.35f)),
                    )
                    StatCell(
                        label = "陪伴天数",
                        value = activeDays.toString(),
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                }

                if (topSongs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AppTheme.palette.separator.copy(alpha = 0.3f)),
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth()) {
                    topSongs.forEach { song ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (loader != null) {
                            com.yunyin.music.ui.components.Artwork(
                                url = covers[song.id],
                                loader = loader,
                                corner = 10.dp,
                                requestSize = 128,
                                modifier = Modifier.width(44.dp).height(44.dp),
                            )
                        } else {
                            Box(
                                Modifier
                                    .width(44.dp)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(AppTheme.palette.tertiaryBackground),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = SfIcons.MusicNote,
                                    contentDescription = null,
                                    tint = AppTheme.palette.tertiaryLabel,
                                    modifier = Modifier.width(20.dp).height(20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                fontFamily = SFPro,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = AppTheme.palette.label,
                            )
                            Text(
                                text = song.artist.ifBlank { "未知歌手" },
                                fontFamily = SFPro,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = AppTheme.palette.secondaryLabel,
                            )
                        }
                        Text(
                            text = formatDuration(song.totalMs),
                            fontFamily = SFPro,
                            fontSize = 14.sp,
                            color = AppTheme.palette.secondaryLabel,
                        )
                    }
                    }
                    }
                }
            }
        }
    }
}

/** 本周 / 本月 / 本年 / 总共 —— 圆角胶囊轨道里的一颗滑块。 */
@Composable
private fun RangeSegments(selected: StatsRange, onSelect: (StatsRange) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(AppTheme.palette.tertiaryBackground)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        StatsRange.entries.forEach { range ->
            val active = range == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (active) AppTheme.palette.secondaryBackground else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(range) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = range.label,
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) AppTheme.palette.label else AppTheme.palette.secondaryLabel,
                )
            }
        }
    }
}

/** 按天柱状图：只有最高的一根用强调色，其余用低对比，避免整排抢眼。 */
@Composable
private fun DayBars(buckets: List<StatsBucket>, peak: Long) {
    val shown = buckets.takeLast(14)
    Row(
        Modifier.fillMaxWidth().height(96.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        shown.forEach { bucket ->
            val fraction = (bucket.totalMs.toFloat() / peak).coerceIn(0f, 1f)
            val isPeak = bucket.totalMs == peak && bucket.totalMs > 0
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((72 * fraction).dp.coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isPeak) AppTheme.palette.accent
                            else AppTheme.palette.accent.copy(alpha = 0.28f),
                        ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = bucket.key.takeLast(2).trimStart('0'),
                    fontFamily = SFPro,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    color = AppTheme.palette.tertiaryLabel,
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.secondaryLabel,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontFamily = SFPro,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.palette.label,
        )
    }
}

/** 「2026年11月1日–7日」这样的区间文案；只有一天时只显示那一天。 */
private fun rangeLabel(range: StatsRange, buckets: List<StatsBucket>): String {
    val first = buckets.firstOrNull()?.key ?: return range.label
    val last = buckets.lastOrNull()?.key ?: return range.label
    return if (first == last) first else "$first – $last"
}
