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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.download.DownloadPhase
import com.yunyin.music.data.download.DownloadRecord
import com.yunyin.music.data.download.TrackDownloadState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro

private enum class CenterTab(val label: String) { Active("进行中"), Done("已完成"), Failed("失败") }

/**
 * 下载中心 — the one place that owns every download, with the same three states the manager exposes.
 *
 * Visual language follows the rest of the app: page background, SF Pro, semantic palette tokens, 44 dp controls,
 * no new motion system. A row is cover + two text lines + size/state, with one primary control (pause/resume/retry)
 * and one delete control at the trailing edge. The tabs are plain text controls with an accent underline rather than
 * a second segmented-control component, so the page does not introduce a parallel control idiom.
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
    val visible = entries.filter {
        when (tab) {
            CenterTab.Active -> it.state.phase == DownloadPhase.WAITING || it.state.phase == DownloadPhase.RESOLVING ||
                it.state.phase == DownloadPhase.DOWNLOADING || it.state.phase == DownloadPhase.PAUSED
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
                text = "下载中心",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = AppTheme.palette.label,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            CenterTab.entries.forEach { entry ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = entry.label,
                        fontFamily = SFPro,
                        fontWeight = if (tab == entry) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 15.sp,
                        color = if (tab == entry) AppTheme.palette.accent else AppTheme.palette.secondaryLabel,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                indication = rememberControlRipple(bounded = false),
                                interactionSource = null,
                                onClick = { tab = entry },
                            )
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                    Box(
                        Modifier
                            .width(20.dp)
                            .height(2.dp)
                            .background(if (tab == entry) AppTheme.palette.accent else androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FooterAction("全部暂停", onPauseAll)
            FooterAction("全部继续", onResumeAll)
            FooterAction("清空已完成", onClearCompleted)
        }

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when (tab) {
                        CenterTab.Active -> "没有进行中的下载"
                        CenterTab.Done -> "还没有已完成的下载"
                        CenterTab.Failed -> "没有失败的下载"
                    },
                    fontFamily = SFPro,
                    fontSize = 14.sp,
                    color = AppTheme.palette.tertiaryLabel,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
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

@Composable
private fun FooterAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontFamily = SFPro,
        fontSize = 13.sp,
        color = AppTheme.palette.accent,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(indication = rememberControlRipple(bounded = false), interactionSource = null, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    )
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
                text = entry.track.name,
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = AppTheme.palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.track.artistLine,
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${state.sizeLabel()} · ${statusText(state)}",
                fontFamily = SFPro,
                fontSize = 11.sp,
                color = if (state.phase == DownloadPhase.FAILED) AppTheme.palette.accent else AppTheme.palette.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.phase == DownloadPhase.DOWNLOADING || state.phase == DownloadPhase.PAUSED || state.phase == DownloadPhase.WAITING) {
                val progress = state.progress ?: 0f
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                        .background(AppTheme.palette.secondaryBackground),
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                            .background(AppTheme.palette.accent),
                    )
                }
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
                DownloadPhase.PAUSED -> "继续"
                else -> "继续"
            },
            onClick = when (state.phase) {
                DownloadPhase.DOWNLOADING, DownloadPhase.WAITING, DownloadPhase.RESOLVING -> onPause
                DownloadPhase.FAILED -> onRetry
                DownloadPhase.PAUSED -> onResume
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
        DownloadPhase.PAUSED -> "已暂停$percent".let { if (percent == null) "已暂停" else it }
        DownloadPhase.DONE -> "已完成"
        DownloadPhase.FAILED -> state.message ?: "下载失败"
        DownloadPhase.IDLE -> "未下载"
    }
}
