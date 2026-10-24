// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.yunyin.music.core.Track
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.launch

/**
 * "Up next" queue presented as an iOS sheet.
 *
 * Shows the whole queue with the playing row highlighted in the accent color; tapping a
 * row jumps to it. Sized to leave the player visible above, which is the standard iOS
 * sheet proportion.
 *
 * The grabber is a real handle: the header follows the finger 1:1 and dismisses on the player's own
 * rule (> 120 dp of travel, or a fling faster than 0.11 px/ms), otherwise it springs back. This is
 * what the drawn grabber implied all along; it used to be decoration.
 */
@Composable
fun QueueSheet(
    queue: List<Track>,
    currentIndex: Int,
    loader: ArtworkLoader,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Read only from `graphicsLayer` below, so following the finger never recomposes the sheet.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    val density = LocalDensity.current

    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        sheetModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.72f)
            .graphicsLayer { translationY = dragOffset }
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .clickable(
                enabled = false,
                indication = null,
                interactionSource = null,
            ) { },
    ) { close ->
        // The grabber + title are the handle: large enough to grab (≈60 dp) and away from the rows, so
        // a vertical drag there can never be confused with scrolling the queue.
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
                            // Left at its dragged offset: the sheet's exit animation then starts from
                            // where the finger let go instead of jumping back first.
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
            // Grabber, as on a native sheet — now the handle it looks like.
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        .background(AppTheme.palette.tertiaryLabel),
                )
            }
            Text(
                text = "播放队列",
                fontFamily = SFPro,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = AppTheme.palette.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(queue, key = { _, track -> track.id }) { index, track ->
                val isCurrent = index == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Songs get inserted, skipped and reordered while the queue is open; without
                        // this the rows teleport. The app's item-appear duration for fades, Compose's
                        // default spring for placement (no new numbers to justify).
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
                    }
                }
            }
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
    /** Travel past which a release dismisses instead of springing back. */
    const val DistanceDp = 120f

    /** Release speed (px/ms) past which a flick dismisses even below [DistanceDp]. */
    const val VelocityPxPerMs = 0.11f

    fun shouldDismiss(offsetPx: Float, distancePx: Float, velocityPxPerMs: Float): Boolean =
        offsetPx > distancePx || velocityPxPerMs > VelocityPxPerMs
}
