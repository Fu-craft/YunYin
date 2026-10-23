package com.yunyin.music.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.download.DownloadPhase
import com.yunyin.music.data.download.DownloadRecord
import com.yunyin.music.data.download.TrackDownloadState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro

private enum class CenterTab(val label: String) { Active("进行中"), Done("已完成"), Failed("失败") }

/**
 * 下载中心, redesigned around what the user is asking at a glance.
 *
 * Structure: a summary card (in-progress / done / failed + aggregate progress and remaining size), then the same quiet
 * track + filled selected segment used by 评论 and 本地音乐, then rows with a clear hierarchy (song name; artist / size;
 * a thin progress capsule; a state colour). Bulk actions are three 44 dp secondary pills under the summary instead of
 * a row of tiny text buttons; per-row pause/resume/retry/delete remain 44 dp icon targets. Empty/loading use the shared
 * illustration-space pattern, and motion is limited to [CrossfadeContent] + existing `Motion` tokens.
 */
@Composable
fun DownloadCenterScreen(
    states: Map<Long, TrackDownloadState>,
    records: List<DownloadRecord>,
    tracks: Map<Long, Track>,
    loader: ArtworkLoader,
    onBack: () -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearCompleted: () -> Unit,
    loading: Boolean = false,
) {
    var tab by remember { mutableStateOf(CenterTab.Active) }

    val entries = remember(states, records, tracks) {
        val byId = LinkedHashMap<Long, CenterEntry>()
        records.forEach { record ->
            val state = states[record.track.id] ?: record.toState()
            byId[record.track.id] = CenterEntry(record.track, state)
        }
        states.forEach { (id, state) ->
            val track = tracks[id] ?: records.firstOrNull { it.track.id == id }?.track ?: return@forEach
            if (state.phase != DownloadPhase.IDLE) byId[id] = CenterEntry(track, state)
        }
        byId.values.toList()
    }
    val activeCount = entries.count { it.state.phase.isActive() }
    val doneCount = entries.count { it.state.phase == DownloadPhase.DONE }
    val failedCount = entries.count { it.state.phase == DownloadPhase.FAILED }
    val visible = entries.filter {
        when (tab) {
            CenterTab.Active -> it.state.phase.isActive()
            CenterTab.Done -> it.state.phase == DownloadPhase.DONE
            CenterTab.Failed -> it.state.phase == DownloadPhase.FAILED
        }
    }.sortedByDescending { it.state.trackId }

    Column(Modifier.fillMaxSize().background(AppTheme.palette.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                "下载中心",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = AppTheme.palette.label,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        SummaryCard(activeCount, doneCount, failedCount, entries)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryPill("全部暂停", onPauseAll)
            SecondaryPill("全部继续", onResumeAll)
            SecondaryPill("清空已完成", onClearCompleted)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .height(44.dp)
                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                .background(AppTheme.palette.secondaryBackground)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CenterTab.entries.forEach { option ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        .background(if (tab == option) AppTheme.palette.tertiaryBackground else Color.Transparent)
                        .clickable(
                            indication = rememberControlRipple(bounded = true),
                            interactionSource = null,
                        ) { tab = option },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.label,
                        fontFamily = SFPro,
                        fontWeight = if (tab == option) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 13.sp,
                        color = if (tab == option) AppTheme.palette.label else AppTheme.palette.secondaryLabel,
                    )
                }
            }
        }

        CrossfadeContent(
            targetState = when {
                loading -> "loading"
                visible.isEmpty() -> "empty"
                else -> "list"
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            durationMillis = Motion.CrossfadeDuration,
            label = "download-center-phase",
        ) { phase ->
            when (phase) {
                "loading" -> DownloadSkeleton()
                "empty" -> EmptyState(
                    text = when (tab) {
                        CenterTab.Active -> "没有进行中的下载"
                        CenterTab.Done -> "还没有已完成的下载"
                        CenterTab.Failed -> "没有失败的下载"
                    },
                    icon = SfIcons.ArrowDownCircle,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.track.id }) { entry ->
                        DownloadRow(
                            entry = entry,
                            loader = loader,
                            onPause = { onPause(entry.track.id) },
                            onResume = { onResume(entry.track.id) },
                            onRetry = { onRetry(entry.track.id) },
                            onDelete = { onDelete(entry.track.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(active: Int, done: Int, failed: Int, entries: List<CenterEntry>) {
    val totalBytes = entries.sumOf { it.state.totalBytes ?: 0L }
    val downloaded = entries.sumOf { it.state.downloadedBytes }
    val progress = if (totalBytes > 0) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.card))
            .background(AppTheme.palette.secondaryBackground)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryStat("进行中", "$active", AppTheme.palette.accent, Modifier.weight(1f))
            SummaryStat("已完成", "$done", AppTheme.palette.secondaryLabel, Modifier.weight(1f))
            SummaryStat("失败", "$failed", if (failed > 0) AppTheme.palette.accent else AppTheme.palette.secondaryLabel, Modifier.weight(1f))
        }
        if (active > 0) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(AppTheme.palette.tertiaryBackground),
            ) {
                Box(
                    Modifier.fillMaxWidth(progress).height(3.dp).clip(CircleShape).background(AppTheme.palette.accent),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "${(progress * 100).toInt()}% · 剩余 ${remainingLabel(totalBytes - downloaded)}",
                fontFamily = SFPro,
                fontSize = 11.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = color)
        Text(label, fontFamily = SFPro, fontSize = 11.sp, color = AppTheme.palette.secondaryLabel)
    }
}

@Composable
private fun SecondaryPill(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel)
    }
}

@Composable
private fun DownloadSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        repeat(5) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(ContinuousRoundedRectangle(AppleShapes.control)).background(AppTheme.palette.secondaryBackground))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(13.dp).clip(ContinuousRoundedRectangle(AppleShapes.control)).background(AppTheme.palette.secondaryBackground))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.4f).height(11.dp).clip(ContinuousRoundedRectangle(AppleShapes.control)).background(AppTheme.palette.secondaryBackground))
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    entry: CenterEntry,
    loader: ArtworkLoader,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val state = entry.state
    val statusColor = when (state.phase) {
        DownloadPhase.DOWNLOADING, DownloadPhase.RESOLVING, DownloadPhase.WAITING, DownloadPhase.FAILED -> AppTheme.palette.accent
        DownloadPhase.DONE -> AppTheme.palette.secondaryLabel
        else -> AppTheme.palette.tertiaryLabel
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = entry.track.coverUrl,
            loader = loader,
            corner = 10.dp,
            requestSize = 300,
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.track.name,
                fontFamily = SFPro,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.track.artistLine} · ${state.sizeLabel()}",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(AppTheme.palette.tertiaryBackground),
                ) {
                    if (state.phase.isActive()) {
                        Box(
                            Modifier
                                .fillMaxWidth((state.progress ?: 0f).coerceIn(0f, 1f))
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(AppTheme.palette.accent),
                        )
                    } else if (state.phase == DownloadPhase.DONE) {
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(AppTheme.palette.secondaryLabel))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText(state),
                    fontFamily = SFPro,
                    fontSize = 11.sp,
                    color = statusColor,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        RowControl(
            icon = when (state.phase) {
                DownloadPhase.DOWNLOADING, DownloadPhase.WAITING, DownloadPhase.RESOLVING -> SfIcons.Pause
                DownloadPhase.FAILED -> SfIcons.ArrowClockwise
                else -> SfIcons.Play
            },
            description = when (state.phase) {
                DownloadPhase.DOWNLOADING, DownloadPhase.WAITING, DownloadPhase.RESOLVING -> "暂停"
                DownloadPhase.FAILED -> "重试"
                else -> "继续"
            },
            onClick = when (state.phase) {
                DownloadPhase.DOWNLOADING, DownloadPhase.WAITING, DownloadPhase.RESOLVING -> onPause
                DownloadPhase.FAILED -> onRetry
                else -> onResume
            },
        )
        RowControl(SfIcons.Trash, "删除", onDelete)
    }
}

@Composable
private fun RowControl(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(
            indication = rememberControlRipple(bounded = false),
            interactionSource = null,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = AppTheme.palette.secondaryLabel, modifier = Modifier.size(19.dp))
    }
}

private data class CenterEntry(val track: Track, val state: TrackDownloadState)

private fun DownloadPhase.isActive(): Boolean =
    this == DownloadPhase.WAITING || this == DownloadPhase.RESOLVING ||
        this == DownloadPhase.DOWNLOADING || this == DownloadPhase.PAUSED

private fun DownloadRecord.toState(): TrackDownloadState = TrackDownloadState(
    trackId = track.id,
    phase = phase,
    message = message,
    fileName = fileName,
    uri = uri,
    downloadedBytes = downloadedBytes,
    totalBytes = totalBytes,
    progress = totalBytes?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) },
)

private fun TrackDownloadState.sizeLabel(): String {
    val total = totalBytes ?: return ""
    return String.format("%.1f MB", total / 1024.0 / 1024.0)
}

private fun remainingLabel(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    return String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun statusText(state: TrackDownloadState): String {
    val percent = state.progress?.let { "${(it * 100).toInt()}%" }
    return when (state.phase) {
        DownloadPhase.WAITING -> "排队中"
        DownloadPhase.RESOLVING -> "解析地址"
        DownloadPhase.DOWNLOADING -> buildString {
            append(percent ?: "下载中")
            state.bytesPerSecond?.let { speed ->
                append(" · ")
                append(if (speed > 1024 * 1024) String.format("%.1f MB/s", speed / 1024.0 / 1024.0) else "${speed / 1024} KB/s")
            }
        }
        DownloadPhase.PAUSED -> "已暂停$percent"
        DownloadPhase.DONE -> "已完成"
        DownloadPhase.FAILED -> state.message ?: "下载失败"
        DownloadPhase.IDLE -> "未下载"
    }
}
