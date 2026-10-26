// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.QueueEditRules
import com.yunyin.music.ui.components.formatDuration
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 播放队列, now editable:
 *  - drag the trailing handle to reorder; the row follows the finger and commits on release;
 *  - trailing ⋯ removes a non-current row with a 撤销 snackbar;
 *  - header 清空 asks for confirmation and keeps the current item playing.
 *
 * Rows use the track id as their stable key, so a move/remove cannot make the list jump.
 */
@Composable
fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    loader: ArtworkLoader,
    shuffle: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (Int, Int) -> Boolean,
    onRemove: (Int) -> Boolean,
    onInsert: (Int, Track) -> Boolean,
    onClear: () -> Boolean,
    onDismiss: () -> Unit,
) {
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingRemove by remember { mutableStateOf<Int?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val totalMs = queue.sumOf { it.durationMs }
    val title = "队列 ${queue.size} 首 · ${formatDuration(totalMs)}"

    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        sheetModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f)
            .graphicsLayer { translationY = dragOffset }
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .clickable(
                enabled = false,
                indication = null,
                interactionSource = null,
            ) { },
    ) { close ->
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                            },
                            onDragStopped = { velocity ->
                                val distancePx = with(density) {
                                    QueueSheetDismissRules.DistanceDp.dp.toPx()
                                }
                                if (QueueSheetDismissRules.shouldDismiss(
                                        offsetPx = dragOffset,
                                        distancePx = distancePx,
                                        velocityPxPerMs = velocity / 1000f,
                                    )
                                ) {
                                    close()
                                } else {
                                    dragScope.launch {
                                        Animatable(dragOffset).animateTo(0f, Motion.SpringSettle) {
                                            dragOffset = value
                                        }
                                    }
                                }
                            },
                        ),
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width = 36.dp, height = 5.dp)
                                .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                                .background(AppTheme.palette.tertiaryLabel),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "播放队列",
                                fontFamily = SFPro,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = AppTheme.palette.label,
                            )
                            Text(
                                title,
                                fontFamily = SFPro,
                                fontSize = 12.sp,
                                color = AppTheme.palette.secondaryLabel,
                            )
                        }
                        if (queue.size > 1) {
                            Box(
                                Modifier
                                    .height(44.dp)
                                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                                    .clickable(
                                        indication = rememberControlRipple(bounded = false),
                                        interactionSource = null,
                                    ) { showClearConfirm = true }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "清空",
                                    fontFamily = SFPro,
                                    fontSize = 13.sp,
                                    color = AppTheme.palette.accent,
                                )
                            }
                        }
                    }
                    if (shuffle) {
                        Text(
                            QueueEditRules.shuffleHint(true),
                            fontFamily = SFPro,
                            fontSize = 12.sp,
                            color = AppTheme.palette.secondaryLabel,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                }

                LazyColumn(
                    contentPadding = PaddingValues(bottom = 96.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(queue, key = { _, track -> track.id }) { index, track ->
                        val isCurrent = index == currentIndex
                        var dragY by remember(track.id) { mutableFloatStateOf(0f) }
                        var dragging by remember(track.id) { mutableStateOf(false) }
                        val rowHeightPx = with(density) { 58.dp.toPx() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = dragY }
                                .animateItem(
                                    fadeInSpec = tween(Motion.ItemAppearDuration, easing = Motion.EaseOut),
                                    fadeOutSpec = tween(Motion.ItemAppearDuration, easing = Motion.EaseOut),
                                )
                                .clickable { close { onSelect(index) } }
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(
                                url = track.coverUrl,
                                loader = loader,
                                corner = 8.dp,
                                requestSize = 200,
                                modifier = Modifier.size(42.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.name,
                                    fontFamily = SFPro,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                    color = if (isCurrent) AppTheme.palette.accent else AppTheme.palette.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.artistLine,
                                    fontFamily = SFPro,
                                    fontSize = 12.sp,
                                    color = AppTheme.palette.secondaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isCurrent) {
                                Icon(
                                    SfIcons.SpeakerWave,
                                    contentDescription = "正在播放",
                                    tint = AppTheme.palette.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clickable(
                                            indication = rememberControlRipple(bounded = false),
                                            interactionSource = null,
                                        ) { pendingRemove = index },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        SfIcons.Ellipsis,
                                        contentDescription = "从队列移除",
                                        tint = AppTheme.palette.secondaryLabel,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .pointerInput(queue.size, index) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragging = true },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                dragY += delta.y
                                            },
                                            onDragEnd = {
                                                val target = (index + (dragY / rowHeightPx).roundToInt())
                                                    .coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                                                if (target != index) onMove(index, target)
                                                dragging = false
                                                dragY = 0f
                                            },
                                            onDragCancel = {
                                                dragging = false
                                                dragY = 0f
                                            },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    SfIcons.ListNumber,
                                    contentDescription = "拖动排序",
                                    tint = AppTheme.palette.tertiaryLabel,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp),
            )
        }

        val removeIndex = pendingRemove
        if (removeIndex != null) {
            val track = queue.getOrNull(removeIndex)
            if (track != null) {
                AlertDialog(
                    onDismissRequest = { pendingRemove = null },
                    title = { Text("从队列移除", fontFamily = SFPro, fontWeight = FontWeight.SemiBold) },
                    text = { Text("移除「${track.name}」？", fontFamily = SFPro) },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingRemove = null
                            if (onRemove(removeIndex)) {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "已从队列移除",
                                        actionLabel = "撤销",
                                        withDismissAction = false,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onInsert(removeIndex, track)
                                    }
                                }
                            }
                        }) { Text("移除", color = AppTheme.palette.accent) }
                    },
                    dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } },
                )
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                title = { Text("清空队列", fontFamily = SFPro, fontWeight = FontWeight.SemiBold) },
                text = { Text("保留当前歌曲，清空队列中的其他歌曲？", fontFamily = SFPro) },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirm = false
                        onClear()
                    }) { Text("清空", color = AppTheme.palette.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                },
            )
        }
    }
}

/**
 * Dismiss rule for the queue sheet's drag handle.
 *
 * The same rule the player's drag-to-dismiss uses (plan 001): > 120 dp of travel **or** a release
 * faster than 0.11 px/ms. Pure and unit-tested so the threshold cannot drift from the player's.
 */
internal object QueueSheetDismissRules {
    const val DistanceDp = 120f
    const val VelocityPxPerMs = 0.11f

    fun shouldDismiss(offsetPx: Float, distancePx: Float, velocityPxPerMs: Float): Boolean =
        offsetPx > distancePx || velocityPxPerMs > VelocityPxPerMs
}
