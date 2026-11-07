// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.download

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.download.DownloadPhase
import com.yunyin.music.data.download.DownloadRecord
import com.yunyin.music.data.download.TrackDownloadState
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.GlassTopBar
import com.yunyin.music.ui.components.enterOnceRise
import com.yunyin.music.ui.components.glassTopBarTopPadding
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.components.rememberGlassTopBarBackdrop
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro

// ---------------------------------------------------------------------------------------------
// Style tokens — docs/SETTINGS-STYLE.md: layered by tone and air, no shadow, no outline.
// ---------------------------------------------------------------------------------------------

/** Card inset from the screen edge. */
private val CardInset = 16.dp

/** Flat card radius; the list is held together by the card's background, not by a border. */
private val CardCorner = 14.dp

/** Section header indent: card inset + row start inset, so headers and row text share one column. */
private val TextColumn = CardInset + 12.dp

/** Row padding: 12 start / 12 end / 10 top / 10 bottom, with a 44 dp artwork inside it. */
private val RowInsets = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

/**
 * 下载中心, rebuilt as three sections instead of three tabs.
 *
 * The page answers "what is happening, what is done, what went wrong" in one scroll, because the tab
 * bar hid two of those answers behind a tap — and a failed download is exactly the thing a user opens
 * this screen to find.
 *
 * Visual rules (same as 设置, from `docs/SETTINGS-STYLE.md`): three-level type scale (section 13 sp /
 * row title 16 sp / secondary 12.5 sp), cards of `secondaryBackground` on `background` with a 14 dp
 * radius and **no shadow or outline**, 28 dp between sections, and the accent colour only on the
 * progress fill, the failure action and the destructive action.
 *
 * Motion: the progress bar is rendered straight from state (a 60 fps tween on a value that already
 * updates several times a second is pure jank); the only animations are the empty-state entrance and
 * a one-off 200 ms highlight on a row that just finished.
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
    val entries = remember(states, records, tracks) {
        val byId = LinkedHashMap<Long, CenterEntry>()
        records.forEach { record ->
            val state = states[record.track.id] ?: record.toState()
            byId[record.track.id] = CenterEntry(record.track, state, record.updatedAtMs)
        }
        states.forEach { (id, state) ->
            val track = tracks[id] ?: records.firstOrNull { it.track.id == id }?.track ?: return@forEach
            if (state.phase != DownloadPhase.IDLE) {
                val updatedAt = byId[id]?.updatedAtMs ?: 0L
                byId[id] = CenterEntry(track, state, updatedAt)
            }
        }
        byId.values.toList()
    }

    val active = entries.filter { it.state.phase.isOpen() }
    val done = entries.filter { it.state.phase == DownloadPhase.DONE }.sortedByDescending { it.updatedAtMs }
    val failed = entries.filter { it.state.phase == DownloadPhase.FAILED }
    val allEmpty = DownloadCenterRules.isEmpty(active.size, done.size, failed.size)

    // A row only flashes when *this* session watched it finish; opening the screen on an existing
    // library must not light up every completed row at once.
    val justFinished = remember { mutableStateMapOf<Long, Boolean>() }
    val knownPhases = remember { mutableMapOf<Long, DownloadPhase>() }
    LaunchedEffect(states) {
        states.forEach { (id, state) ->
            val previous = knownPhases[id]
            if (previous != null && previous != DownloadPhase.DONE && state.phase == DownloadPhase.DONE) {
                justFinished[id] = true
            }
            knownPhases[id] = state.phase
        }
    }

    var pendingDelete by remember { mutableStateOf<CenterEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    // STATUS-BAR SAFETY: the page is a full-screen overlay, so it must claim the system-bar inset
    // itself; the strip is composed outside the recorded layer so its blur samples the content.
    val topBarBackdrop = rememberGlassTopBarBackdrop()
    val topInset = glassTopBarTopPadding()

    Box(Modifier.fillMaxSize().background(AppTheme.palette.background)) {
        Box(Modifier.fillMaxSize().layerBackdrop(topBarBackdrop)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(top = topInset, bottom = 28.dp),
            ) {
                item(key = "header") {
                    DownloadHeader(
                        hasActive = active.isNotEmpty(),
                        onPauseAll = onPauseAll,
                        onResumeAll = onResumeAll,
                    )
                }

                when {
                    loading -> item(key = "loading") { DownloadSkeleton() }

                    allEmpty -> item(key = "empty") {
                        EmptyDownloads(onBrowse = onBack)
                    }

                    else -> {
                        if (active.isNotEmpty()) {
                            item(key = "section-active") {
                                SectionHeader(title = "进行中", count = active.size)
                                SectionCard {
                                    active.forEachIndexed { index, entry ->
                                        if (index > 0) RowDivider()
                                        ActiveRow(
                                            entry = entry,
                                            loader = loader,
                                            onPause = { onPause(entry.track.id) },
                                            onResume = { onResume(entry.track.id) },
                                            onCancel = { pendingDelete = entry },
                                        )
                                    }
                                }
                            }
                        }

                        if (done.isNotEmpty()) {
                            item(key = "section-done") {
                                SectionHeader(
                                    title = "已完成",
                                    count = done.size,
                                    action = "清空" to { confirmClear = true },
                                )
                                SectionCard {
                                    // Grouped by album: a download list is read as "which records did I
                                    // keep", and the album is the unit people remember.
                                    var first = true
                                    done.groupBy { it.track.albumName.ifBlank { "未知专辑" } }
                                        .forEach { (album, group) ->
                                            if (!first) RowDivider()
                                            first = false
                                            Text(
                                                text = album,
                                                modifier = Modifier.padding(
                                                    start = 12.dp,
                                                    end = 12.dp,
                                                    top = 10.dp,
                                                    bottom = 2.dp,
                                                ),
                                                fontFamily = SFPro,
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 12.5.sp,
                                                color = AppTheme.palette.secondaryLabel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            group.forEachIndexed { index, entry ->
                                                if (index > 0) RowDivider()
                                                DoneRow(
                                                    entry = entry,
                                                    loader = loader,
                                                    flash = justFinished[entry.track.id] == true,
                                                    onFlashConsumed = {
                                                        justFinished.remove(entry.track.id)
                                                    },
                                                    onDeleteRequest = { pendingDelete = entry },
                                                )
                                            }
                                        }
                                }
                            }
                        }

                        if (failed.isNotEmpty()) {
                            item(key = "section-failed") {
                                SectionHeader(title = "失败", count = failed.size)
                                SectionCard {
                                    failed.forEachIndexed { index, entry ->
                                        if (index > 0) RowDivider()
                                        FailedRow(
                                            entry = entry,
                                            loader = loader,
                                            onRetry = { onRetry(entry.track.id) },
                                            onDeleteRequest = { pendingDelete = entry },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        GlassTopBar(backdrop = topBarBackdrop, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
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
                    Icon(
                        SfIcons.ChevronLeft,
                        contentDescription = "返回",
                        tint = AppTheme.palette.label,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Deleting is destructive enough to ask first — both for one row and for the whole done list.
        val target = pendingDelete
        if (target != null) {
            com.yunyin.music.ui.components.YunYinConfirmDialog(
                onDismissRequest = { pendingDelete = null },
                title = "删除下载",
                message = "删除「${target.track.name}」的下载记录？已下载的文件不会从系统音乐库移除。",
                confirmLabel = "删除",
                onConfirm = {
                    pendingDelete = null
                    onDelete(target.track.id)
                },
            )
        }
        if (confirmClear) {
            com.yunyin.music.ui.components.YunYinConfirmDialog(
                onDismissRequest = { confirmClear = false },
                title = "清空已完成",
                message = "清空 ${done.size} 条已完成记录？已下载的文件不会从系统音乐库移除。",
                confirmLabel = "清空",
                onConfirm = {
                    confirmClear = false
                    onClearCompleted()
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Page furniture
// ---------------------------------------------------------------------------------------------

/** 32 sp title with the one bulk action the header is allowed to carry. */
@Composable
private fun DownloadHeader(
    hasActive: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = TextColumn, end = CardInset, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "下载中心",
            modifier = Modifier.weight(1f),
            fontFamily = SFPro,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            letterSpacing = (-0.6).sp,
            color = AppTheme.palette.label,
            maxLines = 1,
        )
        if (hasActive) {
            TextAction("全部暂停", onClick = onPauseAll)
        } else {
            TextAction("全部开始", onClick = onResumeAll)
        }
    }
}

/** Section label: 13 sp, SemiBold, +0.4 tracking, muted — the same voice as the settings sections. */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    action: Pair<String, () -> Unit>? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = TextColumn, end = CardInset, top = 28.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$title $count",
            modifier = Modifier.weight(1f),
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = 0.4.sp,
            color = AppTheme.palette.secondaryLabel,
        )
        if (action != null) {
            TextAction(action.first, onClick = action.second)
        }
    }
}

/** One flat card per section: background tone only, no border, no shadow. */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CardInset)
            .clip(ContinuousRoundedRectangle(CardCorner))
            .background(AppTheme.palette.secondaryBackground),
    ) {
        Column { content() }
    }
}

/** Hairline between two rows, inset to the row text, drawn in `separator @ 35%`. */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .height(1.dp)
            .background(AppTheme.palette.separator.copy(alpha = 0.35f)),
    )
}

/**
 * A 44 dp text action.
 *
 * 44 dp tall regardless of the 12.5–13 sp label inside it: the touch target is the requirement, the
 * text is only what it happens to say.
 */
@Composable
private fun TextAction(
    text: String,
    onClick: () -> Unit,
    color: Color = AppTheme.palette.secondaryLabel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(ContinuousRoundedRectangle(10.dp))
            .clickable(
                indication = rememberControlRipple(bounded = false),
                interactionSource = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = color,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

/**
 * 进行中: cover, title/artist, right-aligned percentage, a 4 dp progress track underneath, and the
 * two actions that apply while something is running.
 *
 * Tapping the row (not the actions) reveals the byte-level detail — "已下载 x / 总 y · speed" — which
 * is the question someone asks when a download looks stuck, and which does not deserve permanent
 * space on every row.
 */
@Composable
private fun ActiveRow(
    entry: CenterEntry,
    loader: ArtworkLoader,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val state = entry.state
    var expanded by remember(entry.track.id) { mutableStateOf(false) }
    val fraction = (state.progress ?: 0f).coerceIn(0f, 1f)
    val paused = state.phase == DownloadPhase.PAUSED

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = rememberControlRipple(bounded = true),
                interactionSource = null,
            ) { expanded = !expanded }
            .padding(RowInsets),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                url = entry.track.coverUrl,
                loader = loader,
                corner = 8.dp,
                requestSize = 300,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.track.name,
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    letterSpacing = (-0.1).sp,
                    color = AppTheme.palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.track.artistLine,
                    fontFamily = SFPro,
                    fontSize = 12.5.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            // Rendered from state, never animated: the value already changes several times a second.
            Text(
                text = percentLabel(state),
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }

        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppTheme.palette.tertiaryBackground),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppTheme.palette.accent),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = detailLabel(state),
                fontFamily = SFPro,
                fontSize = 12.5.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction(if (paused) "继续" else "暂停", onClick = { if (paused) onResume() else onPause() })
            Spacer(Modifier.width(4.dp))
            TextAction("取消", onClick = onCancel, color = AppTheme.palette.accent)
        }
    }
}

/**
 * 已完成: cover, title/artist, file size on the right, and a delete that only appears after a long
 * press — a completed row has nothing to do, so the destructive action must not sit there inviting
 * mis-taps.
 */
@Composable
private fun DoneRow(
    entry: CenterEntry,
    loader: ArtworkLoader,
    flash: Boolean,
    onFlashConsumed: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var revealed by remember(entry.track.id) { mutableStateOf(false) }
    val highlight = remember(entry.track.id) { Animatable(0f) }
    LaunchedEffect(flash) {
        if (!flash) return@LaunchedEffect
        if (!Motion.animatorsEnabled()) {
            highlight.snapTo(0f)
            onFlashConsumed()
            return@LaunchedEffect
        }
        highlight.snapTo(1f)
        highlight.animateTo(0f, tween(durationMillis = 200, easing = Motion.EaseOut))
        onFlashConsumed()
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    AppTheme.palette.accent.copy(alpha = 0.14f * highlight.value),
                )
                .combinedClickable(
                    indication = rememberControlRipple(bounded = true),
                    interactionSource = null,
                    onClick = { revealed = !revealed },
                    onLongClick = { revealed = true },
                )
                .padding(RowInsets),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                url = entry.track.coverUrl,
                loader = loader,
                corner = 8.dp,
                requestSize = 300,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.track.name,
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    letterSpacing = (-0.1).sp,
                    color = AppTheme.palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.track.artistLine,
                    fontFamily = SFPro,
                    fontSize = 12.5.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.state.sizeLabel(),
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
            )
        }
        if (revealed) {
            Row(
                Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction("删除", onClick = onDeleteRequest, color = AppTheme.palette.accent)
            }
        }
    }
}

/** 失败: one row, the reason underneath, and the retry that puts it back under 进行中. */
@Composable
private fun FailedRow(
    entry: CenterEntry,
    loader: ArtworkLoader,
    onRetry: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    var revealed by remember(entry.track.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    indication = rememberControlRipple(bounded = true),
                    interactionSource = null,
                    onClick = { revealed = !revealed },
                    onLongClick = { revealed = true },
                )
                .padding(RowInsets),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(
                url = entry.track.coverUrl,
                loader = loader,
                corner = 8.dp,
                requestSize = 300,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.track.name,
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    letterSpacing = (-0.1).sp,
                    color = AppTheme.palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.state.message ?: "下载失败",
                    fontFamily = SFPro,
                    fontSize = 12.5.sp,
                    color = AppTheme.palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            TextAction("重试", onClick = onRetry, color = AppTheme.palette.accent)
        }
        if (revealed) {
            Row(
                Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction("删除", onClick = onDeleteRequest, color = AppTheme.palette.accent)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Empty / loading
// ---------------------------------------------------------------------------------------------

/**
 * Nothing downloaded at all: one glyph, one sentence, one way out.
 *
 * The button closes the screen (`onBack`) rather than pretending to navigate somewhere this page has
 * no callback for — the download actions live on the track rows behind it.
 */
@Composable
private fun EmptyDownloads(onBrowse: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .enterOnceRise()
            .padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            SfIcons.SquareAndArrowDown,
            contentDescription = null,
            tint = AppTheme.palette.tertiaryLabel,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "还没有下载任务",
            fontFamily = SFPro,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            color = AppTheme.palette.label,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "在歌曲的更多菜单里选择下载，任务会出现在这里。",
            fontFamily = SFPro,
            fontSize = 12.5.sp,
            color = AppTheme.palette.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(ContinuousRoundedRectangle(12.dp))
                .background(AppTheme.palette.secondaryBackground)
                .clickable(
                    indication = rememberControlRipple(bounded = true),
                    interactionSource = null,
                    onClick = onBrowse,
                )
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "去下载歌曲",
                fontFamily = SFPro,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = AppTheme.palette.label,
            )
        }
    }
}

/** Placeholder rows while the record store is read; static on purpose (no shimmer loop). */
@Composable
private fun DownloadSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = CardInset, vertical = 8.dp)) {
        repeat(5) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).clip(ContinuousRoundedRectangle(8.dp)).background(AppTheme.palette.secondaryBackground))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(13.dp).clip(RoundedCornerShape(4.dp)).background(AppTheme.palette.secondaryBackground))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.4f).height(11.dp).clip(RoundedCornerShape(4.dp)).background(AppTheme.palette.secondaryBackground))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private data class CenterEntry(
    val track: Track,
    val state: TrackDownloadState,
    /** When the record was last written; 0 for entries known only from live state. */
    val updatedAtMs: Long = 0L,
)

/** Phases that belong under 进行中: anything the queue still owns, including a paused download. */
private fun DownloadPhase.isOpen(): Boolean =
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

private fun percentLabel(state: TrackDownloadState): String {
    if (state.phase == DownloadPhase.PAUSED) return "已暂停"
    val percent = state.progress?.let { "${(it * 100).toInt()}%" }
    return when (state.phase) {
        DownloadPhase.WAITING -> "排队中"
        DownloadPhase.RESOLVING -> "解析中"
        DownloadPhase.DOWNLOADING -> percent ?: "…"
        else -> percent ?: "…"
    }
}

/** "已下载 3.2 MB / 8.1 MB · 1.2 MB/s" — the detail line revealed by tapping an active row. */
private fun detailLabel(state: TrackDownloadState): String = buildString {
    append("已下载 ").append(formatBytes(state.downloadedBytes))
    append(" / ").append(state.sizeLabel())
    state.bytesPerSecond?.let { speed ->
        append(" · ").append(speedLabel(speed))
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.1f MB", mb)
}

private fun speedLabel(speed: Long): String =
    if (speed > 1024 * 1024) String.format("%.1f MB/s", speed / 1024.0 / 1024.0) else "${speed / 1024} KB/s"
