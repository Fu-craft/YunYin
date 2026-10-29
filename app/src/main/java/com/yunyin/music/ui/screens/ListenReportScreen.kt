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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.net.ListenReportApi
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

/**
 * 听歌足迹 — the cloud listening report.
 *
 * Both feeds require a signed-in session, and the report payload has no documented shape, so the page
 * renders only what [ListenReportApi.Report] could extract (total time + song rows) and shows the
 * server's own message otherwise. That keeps it honest: an empty month says "本月没有数据", a signed
 * out account says "需要登录", and neither pretends to be a real zero.
 */
@Composable
fun ListenReportScreen(
    loadTotal: suspend () -> NetResult<Long?>,
    loadReport: suspend (period: String) -> NetResult<ListenReportApi.Report>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var totalSeconds by remember { mutableStateOf<Long?>(null) }
    var period by remember { mutableStateOf("month") }
    var report by remember { mutableStateOf<ListenReportApi.Report?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = runCatching { loadTotal() }.getOrNull()) {
            is NetResult.Ok -> totalSeconds = result.value
            is NetResult.Err -> error = result.message
            null -> Unit
        }
    }

    LaunchedEffect(period) {
        loading = true
        error = null
        when (val result = runCatching { loadReport(period) }.getOrNull()) {
            is NetResult.Ok -> report = result.value
            is NetResult.Err -> {
                report = null
                error = result.message
            }
            null -> report = null
        }
        loading = false
    }

    Column(
        modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .padding(top = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "听歌足迹",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = AppTheme.palette.label,
            )
            Box(Modifier.fillMaxWidth()) {
                Text(
                    text = "返回",
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clickable(onClick = onBack)
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    fontFamily = SFPro,
                    fontSize = 15.sp,
                    color = AppTheme.palette.accent,
                )
            }
        }

        // Total listening time, when the instance exposes it.
        totalSeconds?.let { seconds ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = "总收听时长",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
                Text(
                    text = formatHours(seconds),
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 30.sp,
                    color = AppTheme.palette.label,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("week" to "本周", "month" to "本月", "year" to "年度").forEach { (key, label) ->
                PeriodChip(label = label, selected = period == key) { period = key }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.palette.accent)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), fontFamily = SFPro, color = AppTheme.palette.secondaryLabel)
            }
            else -> {
                val rows = report?.topSongs.orEmpty()
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("这个周期还没有数据", fontFamily = SFPro, color = AppTheme.palette.secondaryLabel)
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    fontFamily = SFPro,
                                    fontSize = 14.sp,
                                    color = AppTheme.palette.secondaryLabel,
                                    modifier = Modifier.width(28.dp),
                                )
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = row.name,
                                        fontFamily = SFPro,
                                        fontSize = 15.sp,
                                        color = AppTheme.palette.label,
                                    )
                                    if (row.artist.isNotBlank()) {
                                        Text(
                                            text = row.artist,
                                            fontFamily = SFPro,
                                            fontSize = 12.sp,
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
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) AppTheme.palette.accent.copy(alpha = 0.18f)
                else AppTheme.palette.secondaryBackground,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            fontFamily = SFPro,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) AppTheme.palette.accent else AppTheme.palette.label,
        )
    }
}

/** `7325` → `2 小时 2 分`; anything under an hour reads as minutes only. */
private fun formatHours(seconds: Long): String {
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours 小时 $minutes 分" else "$minutes 分"
}
