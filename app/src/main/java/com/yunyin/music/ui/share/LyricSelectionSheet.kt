package com.yunyin.music.ui.share

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
import androidx.compose.material3.Icon
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
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.CrossfadeContent
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.Motion
import com.yunyin.music.ui.theme.SFPro

/**
 * Lyric line picker for the share card.
 *
 * A single full-width sheet with a fixed header, a scrollable selection list and a sticky footer. Rows are 48 dp:
 * leading numbered badge (1–4) when chosen, lyric text, trailing check. Selected rows use an accent-tinted container;
 * order is song order and selection survives scrolling because it lives in the host. A CrossfadeContent separates the
 * empty and list states, and the footer's generate action names the count and is disabled at zero.
 */
@Composable
fun LyricSelectionSheet(
    lines: List<String>,
    starts: List<Int>,
    selected: List<Int>,
    currentIndex: Int,
    onToggle: (Int) -> Unit,
    onQuickDefault: () -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Bottom,
        sheetModifier = Modifier
            .fillMaxWidth()
            .fillMaxSize(0.80f)
            .navigationBarsPadding()
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background),
    ) { close ->
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择歌词", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
                    Text("最多 4 句 · 已选 ${selected.size}/4", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel)
                }
                Text(
                    "当前 4 句",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = AppTheme.palette.accent,
                    modifier = Modifier
                        .height(44.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .clickable(
                            indication = rememberControlRipple(bounded = false),
                            interactionSource = null,
                            onClick = onQuickDefault,
                        )
                        .padding(horizontal = 10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            CrossfadeContent(
                targetState = if (lines.isEmpty()) "empty" else "list",
                modifier = Modifier.fillMaxWidth().weight(1f),
                durationMillis = Motion.SmallDuration,
                label = "lyric-picker-state",
            ) { phase ->
                if (phase == "empty") {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("这首歌没有可选的歌词", fontFamily = SFPro, fontSize = 13.sp, color = AppTheme.palette.secondaryLabel)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(lines, key = { index, _ -> "line-$index" }) { index, line ->
                            val at = selected.indexOf(index)
                            val chosen = at >= 0
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                                    .background(if (chosen) AppTheme.palette.accent.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable(
                                        indication = rememberControlRipple(bounded = true),
                                        interactionSource = null,
                                    ) { onToggle(index) }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (chosen) AppTheme.palette.accent else AppTheme.palette.secondaryBackground),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (chosen) "${at + 1}" else "",
                                        fontFamily = SFPro,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = if (chosen) Color.White else AppTheme.palette.secondaryLabel,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    line,
                                    fontFamily = SFPro,
                                    fontWeight = if (index == currentIndex) FontWeight.SemiBold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    color = if (chosen) AppTheme.palette.accent else AppTheme.palette.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (chosen) {
                                    Icon(SfIcons.Checkmark, contentDescription = null, tint = AppTheme.palette.accent, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Sticky footer: count on the left, primary action on the right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已选 ${selected.size}/4", fontFamily = SFPro, fontSize = 12.sp, color = AppTheme.palette.secondaryLabel, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                        .background(if (selected.isNotEmpty()) AppTheme.palette.accent else AppTheme.palette.tertiaryBackground)
                        .clickable(
                            enabled = selected.isNotEmpty(),
                            indication = rememberControlRipple(bounded = true),
                            interactionSource = null,
                            onClick = onGenerate,
                        )
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "生成分享卡片",
                        fontFamily = SFPro,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (selected.isNotEmpty()) Color.White else AppTheme.palette.secondaryLabel,
                    )
                }
            }
        }
    }
}
