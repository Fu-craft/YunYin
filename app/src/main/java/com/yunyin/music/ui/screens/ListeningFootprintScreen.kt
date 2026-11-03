// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.data.stats.PlaybackStatsStore
import com.yunyin.music.data.stats.StatsBucket
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import kotlin.math.roundToLong

/**
 * 听歌足迹：按时间看自己听歌的分布。
 *
 * 与另外两页分工（见 docs/WIDGET-AND-FOOTPRINT.md）：[PlaybackStatsScreen] 是累计榜，
 * [ListenReportScreen] 是网易年度报告，本页只回答「最近这些天我怎么听的」。
 *
 * 数据全部来自本机 [PlaybackStatsStore]，离线可用；不求网易接口，所以任何一天都能打开。
 */
@Composable
fun ListeningFootprintScreen(
    store: PlaybackStatsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = store.snapshot()

    val days = remember(snapshot) { snapshot.days.takeLast(42) }
    val peakMs = remember(days) { (days.maxOfOrNull { it.totalMs } ?: 0L).coerceAtLeast(1L) }

    LazyColumn(
        modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "返回",
                    fontFamily = SFPro,
                    fontSize = 15.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier.clickableRow(onBack),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "听歌足迹",
                    fontFamily = SFPro,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                    color = AppTheme.palette.label,
                )
            }
        }

        item("totals") {
            Column(Modifier.padding(bottom = 14.dp)) {
                Text(
                    text = formatDuration(snapshot.totals.totalMs),
                    fontFamily = SFPro,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.palette.accent,
                )
                Text(
                    text = "${snapshot.totals.plays} 次播放 · ${snapshot.totals.activeDays} 天有听歌",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
            }
        }

        if (days.isEmpty()) {
            item("empty") {
                Text(
                    text = "还没有听歌记录。听几首歌之后，这里会出现你的足迹。",
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.secondaryLabel,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            item("heatmap") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppTheme.palette.secondaryBackground)
                        .padding(14.dp),
                ) {
                    Text(
                        text = "最近 ${days.size} 天",
                        fontFamily = SFPro,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                        color = AppTheme.palette.secondaryLabel,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 七天一行，颜色深浅 = 当天听歌时长相对峰值
                    days.chunked(7).forEach { week ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            week.forEach { day ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(
                                            AppTheme.palette.accent.copy(
                                                alpha = levelAlpha(day.totalMs, peakMs),
                                            ),
                                        ),
                                )
                            }
                            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "颜色越深，那天听歌越久",
                        fontFamily = SFPro,
                        fontSize = 11.sp,
                        color = AppTheme.palette.tertiaryLabel,
                    )
                }
            }

            item("recent-header") {
                Text(
                    text = "每日明细",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                    color = AppTheme.palette.secondaryLabel,
                    modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 7.dp),
                )
            }

            items(days.reversed(), key = { it.key }) { day ->
                DayRow(day = day, peakMs = peakMs)
            }
        }
    }
}

@Composable
private fun DayRow(day: StatsBucket, peakMs: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppTheme.palette.secondaryBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = day.label.ifBlank { day.key },
                fontFamily = SFPro,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.1).sp,
                color = AppTheme.palette.label,
            )
            Text(
                text = "${day.plays} 次播放",
                fontFamily = SFPro,
                fontSize = 12.5.sp,
                letterSpacing = 0.1.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
        Text(
            text = formatDuration(day.totalMs),
            fontFamily = SFPro,
            fontSize = 14.sp,
            color = AppTheme.palette.secondaryLabel,
        )
    }
}

/** 五档透明度：无记录 → 0，其余按峰值分四档。 */
private fun levelAlpha(value: Long, peak: Long): Float = when {
    value <= 0L -> 0.06f
    else -> (0.18f + 0.62f * (value.toFloat() / peak)).coerceIn(0.18f, 0.82f)
}

/** 小时 + 分钟；不足一分钟显示「<1 分」。 */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "—"
    val minutes = (ms / 60_000.0).roundToLong()
    if (minutes < 1) return "<1 分"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours} 小时 ${rest} 分" else "${rest} 分"
}

/** 小工具：给文字加点击。 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
