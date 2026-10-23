package com.yunyin.music.ui.screens

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.yunyin.music.data.stats.GroupStats
import com.yunyin.music.data.stats.PlaybackStatsRecorder
import com.yunyin.music.data.stats.PlaybackStatsRules
import com.yunyin.music.data.stats.PlaybackStatsSnapshot
import com.yunyin.music.data.stats.SongStats
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.GlassTopBar
import com.kyant.backdrop.backdrops.layerBackdrop
import com.yunyin.music.ui.components.EmptyState
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

private enum class StatsTab(val label: String) { Songs("歌曲"), Artists("艺人"), Albums("专辑") }

/**
 * 播放统计 — local-only listening statistics.
 *
 * Hierarchy follows the question the page answers: totals first, then the last-seven-days shape, then the ranked
 * lists. The chart is drawn with plain Compose boxes (no chart dependency); bars are normalised by the pure
 * [PlaybackStatsRules]. The three Top lists share one segmented control, the same control idiom used by 评论 and
 * 本地音乐, and every interactive control is a 44 dp target.
 */
@Composable
fun PlaybackStatsScreen(
    recorder: PlaybackStatsRecorder,
    onBack: () -> Unit,
    /** Writes the page's progress markers into the same diagnostics log the copy-log action reads. */
    diag: (String) -> Unit = {},
) {
    // Kill switch: besides the hidden 资料库 entry, no back door may compose the stats body. If something still
    // navigates here, pop immediately and paint only the page background; never touch the recorder.
    if (!com.yunyin.music.data.stats.StatsFeature.enabled) {
        LaunchedEffect(Unit) { onBack() }
        Box(Modifier.fillMaxSize().background(AppTheme.palette.background))
        return
    }

    // The store is defensive, but the screen must also survive a recorder/state failure instead of taking the
    // app down: read through runCatching and surface a retry / clear action.
    var snapshot by remember { mutableStateOf<PlaybackStatsSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(StatsTab.Songs) }
    LaunchedEffect(recorder) {
        diag("stage=stats open")
        diag("stage=stats load-start")
        val first = runCatching {
            withTimeout(2_000L) {
                recorder.loadNow()
                recorder.state.value
            }
        }
        first.onSuccess { loaded ->
            snapshot = loaded
            loadError = null
            diag(
                "stage=stats load-done songs=${loaded.songs.size} artists=${loaded.artists.size} " +
                    "albums=${loaded.albums.size} days=${loaded.days.size}",
            )
        }.onFailure {
            val timeout = it is kotlinx.coroutines.TimeoutCancellationException
            loadError = if (timeout) "加载统计超时" else "读取统计失败：${it.message ?: it.javaClass.simpleName}"
            diag("stage=stats error ${loadError}")
        }
        // Collect rather than a hand-written while(true): the flow owns the loop, so a slow/blocked source
        // cannot spin a recomposition path.
        runCatching { recorder.state.collect { value -> snapshot = value; loadError = null } }
            .onFailure {
                loadError = it.message ?: it.javaClass.simpleName
                diag("stage=stats error ${loadError}")
            }
    }
    LaunchedEffect(snapshot?.loaded) {
        if (snapshot?.loaded == true) diag("stage=stats render")
    }

    // Full-window opaque background first: the page previously spent the status-bar area on transparency,
    // letting the home wallpaper show through. The frosted bar now owns the inset.
    val topBarBackdrop = rememberGlassTopBarBackdrop()
    val topInset = glassTopBarTopPadding()
    Box(Modifier.fillMaxSize().background(AppTheme.palette.background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            Column(Modifier.fillMaxSize().padding(top = topInset).navigationBarsPadding()) {

        val errorNow = loadError
        val current = snapshot
        when {
            errorNow != null -> StatsLoadError(
                message = errorNow,
                onRetry = { loadError = null },
                onClear = { runCatching { recorder.clear() }; loadError = null; snapshot = null },
            )
            current == null || !current.loaded -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
            }
            else -> {
                val empty = current.totals.plays == 0 && current.totals.totalMs == 0L
                CrossfadeContent(
                    targetState = if (empty) "empty" else "stats",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    durationMillis = Motion.CrossfadeDuration,
                    label = "stats-phase",
                ) { phase ->
                    if (phase == "empty") {
                        EmptyState(
                            text = "还没有播放记录",
                            icon = SfIcons.ChartBar,
                            iconSize = 52.dp,
                        )
                    } else {
                        StatsContent(snapshot = current, tab = tab, onSelectTab = { tab = it })
                    }
                }
            }
        }
            }
            GlassTopBar(backdrop = topBarBackdrop, modifier = Modifier.align(Alignment.TopCenter)) {
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
                        "播放统计",
                        fontFamily = SFPro,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = AppTheme.palette.label,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsLoadError(message: String, onRetry: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("播放统计读取失败", fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppTheme.palette.label)
        Spacer(Modifier.height(6.dp))
        Text(message, fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.height(44.dp).clip(ContinuousRoundedRectangle(AppleShapes.pill)).background(AppTheme.palette.accent)
                    .clickable(indication = rememberControlRipple(bounded = true), interactionSource = null, onClick = onRetry)
                    .padding(horizontal = 16.dp), contentAlignment = Alignment.Center,
            ) { Text("重试", fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White) }
            Box(
                Modifier.height(44.dp).clip(ContinuousRoundedRectangle(AppleShapes.pill)).background(AppTheme.palette.secondaryBackground)
                    .clickable(indication = rememberControlRipple(bounded = true), interactionSource = null, onClick = onClear)
                    .padding(horizontal = 16.dp), contentAlignment = Alignment.Center,
            ) { Text("清空统计数据", fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.secondaryLabel) }
        }
    }
}

@Composable
private fun StatsContent(snapshot: PlaybackStatsSnapshot, tab: StatsTab, onSelectTab: (StatsTab) -> Unit) {
    val fractions = PlaybackStatsRules.barFractions(snapshot.days)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item("overview") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverviewCard("总时长", PlaybackStatsRules.formatDuration(snapshot.totals.totalMs), Modifier.weight(1f))
                OverviewCard("总次数", "${snapshot.totals.plays}", Modifier.weight(1f))
                OverviewCard("活跃天数", "${snapshot.totals.activeDays}", Modifier.weight(1f))
            }
        }
        item("chart") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.card))
                    .background(AppTheme.palette.secondaryBackground)
                    .padding(16.dp),
            ) {
                Text("最近 7 天", fontFamily = SFPro, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppTheme.palette.label)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    snapshot.days.forEachIndexed { index, day ->
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((fractions.getOrElse(index) { 0f } * 90f).dp.coerceAtLeast(2.dp))
                                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                                    .background(if (index == snapshot.days.lastIndex) AppTheme.palette.accent else AppTheme.palette.tertiaryLabel.copy(alpha = 0.5f)),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(day.label, fontFamily = SFPro, fontSize = 10.sp, color = AppTheme.palette.tertiaryLabel)
                        }
                    }
                }
            }
        }
        item("segments") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .height(44.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(AppTheme.palette.secondaryBackground)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatsTab.entries.forEach { option ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                            .background(if (option == tab) AppTheme.palette.tertiaryBackground else Color.Transparent)
                            .clickable(
                                indication = rememberControlRipple(bounded = true),
                                interactionSource = null,
                            ) { onSelectTab(option) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.label,
                            fontFamily = SFPro,
                            fontWeight = if (option == tab) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 13.sp,
                            color = if (option == tab) AppTheme.palette.label else AppTheme.palette.secondaryLabel,
                        )
                    }
                }
            }
        }
        when (tab) {
            StatsTab.Songs -> itemsIndexed(snapshot.songs.take(PlaybackStatsRules.MAX_ROWS), key = { _, item -> "s-${item.id}" }) { index, song ->
                SongStatsRow(index, song)
            }
            StatsTab.Artists -> itemsIndexed(snapshot.artists.take(PlaybackStatsRules.MAX_ROWS), key = { _, item -> "a-${item.key}" }) { index, group ->
                GroupStatsRow(index, group)
            }
            StatsTab.Albums -> itemsIndexed(snapshot.albums.take(PlaybackStatsRules.MAX_ROWS), key = { _, item -> "b-${item.key}" }) { index, group ->
                GroupStatsRow(index, group)
            }
        }
    }
}

@Composable
private fun OverviewCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.card))
            .background(AppTheme.palette.secondaryBackground)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(value, fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = AppTheme.palette.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(label, fontFamily = SFPro, fontSize = 11.sp, color = AppTheme.palette.secondaryLabel)
    }
}

@Composable
private fun SongStatsRow(index: Int, song: SongStats) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.tertiaryLabel, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, fontFamily = SFPro, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = AppTheme.palette.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artist} · ${song.album}", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "${song.plays} 次 · ${PlaybackStatsRules.formatDuration(song.totalMs)}",
            fontFamily = SFPro,
            fontSize = 11.sp,
            color = AppTheme.palette.tertiaryLabel,
            maxLines = 1,
        )
    }
}

@Composable
private fun GroupStatsRow(index: Int, group: GroupStats) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.tertiaryLabel, modifier = Modifier.width(28.dp))
        Text(
            group.key,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            color = AppTheme.palette.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${group.plays} 次 · ${PlaybackStatsRules.formatDuration(group.totalMs)}",
            fontFamily = SFPro,
            fontSize = 11.sp,
            color = AppTheme.palette.tertiaryLabel,
            maxLines = 1,
        )
    }
}
