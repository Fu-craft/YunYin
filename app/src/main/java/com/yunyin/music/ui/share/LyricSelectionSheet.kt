package com.yunyin.music.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.yunyin.music.data.share.LyricSelectionRules
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.SFPro

/**
 * Lyric line picker for the share card.
 *
 * The card needs a deliberate selection, not an implicit window: tapping lines toggles them (1–4, in song order), the
 * current line starts selected via the "当前 4 句" default, and the primary action names the count. Rows are 44 dp and
 * the selected state uses the accent tint + checkmark, so the choice is legible at a glance.
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
            .heightIn(max = 620.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .padding(20.dp),
    ) { close ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("选择歌词", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
                Text(
                    "选择 1–4 句 · 已选 ${selected.size}/4",
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
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
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
            itemsIndexed(lines) { index, line ->
                val chosen = selected.contains(index)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .background(if (chosen) AppTheme.palette.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable(
                            indication = rememberControlRipple(bounded = true),
                            interactionSource = null,
                        ) { onToggle(index) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                        Text(
                            "${selected.indexOf(index) + 1}",
                            fontFamily = SFPro,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = AppTheme.palette.accent,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            Text(
                "取消",
                fontFamily = SFPro,
                fontSize = 14.sp,
                color = AppTheme.palette.secondaryLabel,
                modifier = Modifier
                    .height(44.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .clickable(
                        indication = rememberControlRipple(bounded = false),
                        interactionSource = null,
                    ) { close() }
                    .padding(horizontal = 14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .height(44.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .background(
                        if (selected.isNotEmpty()) AppTheme.palette.accent
                        else AppTheme.palette.tertiaryBackground,
                    )
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
                    "生成分享卡 (${selected.size})",
                    fontFamily = SFPro,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = if (selected.isNotEmpty()) Color.White else AppTheme.palette.secondaryLabel,
                )
            }
        }
    }
}
