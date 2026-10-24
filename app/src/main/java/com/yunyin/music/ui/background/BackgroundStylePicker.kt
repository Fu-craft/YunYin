// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.background

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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import com.yunyin.music.ui.components.AnimatedSheet
import com.yunyin.music.ui.components.SheetPlacement
import com.yunyin.music.ui.components.rememberControlRipple
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.SFPro

/** Small picker for the now-playing background family; one 56 dp row per style. */
@Composable
fun BackgroundStylePicker(
    selected: BackgroundStyle,
    onSelect: (BackgroundStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedSheet(
        onDismissed = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        placement = SheetPlacement.Bottom,
        sheetModifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.sheet))
            .background(AppTheme.palette.background)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) { close ->
        Text("背景样式", fontFamily = SFPro, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppTheme.palette.label)
        Text(
            "只改变正在播放页的动态背景；模糊封面背景不受影响。",
            fontFamily = SFPro,
            fontSize = 13.sp,
            color = AppTheme.palette.secondaryLabel,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        Column {
            BackgroundStyle.entries.forEach { style ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(ContinuousRoundedRectangle(AppleShapes.control))
                        .clickable(
                            indication = rememberControlRipple(bounded = true),
                            interactionSource = null,
                        ) {
                            onSelect(style)
                            close()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(style.label, fontFamily = SFPro, fontSize = 16.sp, color = AppTheme.palette.label)
                        Text(
                            style.description,
                            fontFamily = SFPro,
                            fontSize = 13.sp,
                            color = AppTheme.palette.secondaryLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (style == selected) {
                        Icon(
                            SfIcons.Checkmark,
                            contentDescription = "当前样式",
                            tint = AppTheme.palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            Box(
                Modifier
                    .height(44.dp)
                    .clip(ContinuousRoundedRectangle(AppleShapes.control))
                    .clickable(
                        indication = rememberControlRipple(bounded = true),
                        interactionSource = null,
                    ) { close() }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("完成", fontFamily = SFPro, fontSize = 14.sp, color = AppTheme.palette.accent)
            }
        }
    }
}
