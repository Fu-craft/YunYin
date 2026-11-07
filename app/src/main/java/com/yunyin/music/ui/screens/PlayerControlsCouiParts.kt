// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import com.yunyin.music.ui.AudioQuality

/**
 * 播放器控制区的 **COUI(凝光)零件**。
 *
 * 为什么不去用 COUI 的 `ChipTab` / `Group`:那两个组件需要 `CouiBackdrop`(库自己的背景采样源),
 * 而播放器背景已经有一套玻璃背板(`io.github.kyant0.backdrop`)在采样同一块画面。挂两套采样源
 * 会在同一帧里重复绘制背板,代价与闪烁风险都不值得。
 *
 * 所以这里只用 COUI 的**主题令牌**——`CouiTheme.colorScheme` 的强调色/文字色、`CouiTextKind` 的字号
 * 与字重、圆角胶囊——在播放器自己的背景上呈现出同一套视觉语言,而不引入第二套材质管线。
 */

/** 播放时间 / 总时长:COUI 的 `Progress` 字级(14 sp, medium)。 */
@Composable
internal fun CouiTimeLabel(text: String, modifier: Modifier = Modifier) {
    CouiText(
        text = text,
        kind = CouiTextKind.Progress,
        // 读取 coui 的配色,但保持播放器上的高对比:白 90%,在模糊封面上始终可读。
        color = Color.White.copy(alpha = 0.9f),
        modifier = modifier,
    )
}

/**
 * 音质胶囊:COUI 的强调色底 + 强调色文字,胶囊圆角。
 *
 * 保留原来的信息量(图标 + 档位名),只换成凝光的配色:底色用强调色的 18% 透明层,
 * 而不是经典版的白色 18% —— 这样它在深色封面上是"一点红",和设置页里的强调色是同一个来源。
 */
@Composable
internal fun CouiQualityChip(
    quality: AudioQuality,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = CouiTheme.colorScheme.accent
    Row(
        modifier
            .background(accent.copy(alpha = 0.18f), RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = quality.icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        CouiText(
            text = quality.label,
            kind = CouiTextKind.PreferenceValue,
            color = accent,
        )
    }
}

/** 控制区里的小字号标签(如"歌词/循环/队列"的文字版,留作凝光版备用)。 */
@Composable
internal fun CouiControlLabel(text: String, active: Boolean, modifier: Modifier = Modifier) {
    CouiText(
        text = text,
        kind = CouiTextKind.PreferenceValue,
        color = if (active) {
            CouiTheme.colorScheme.accent
        } else {
            Color.White.copy(alpha = 0.75f)
        },
        modifier = modifier,
    )
}

/** 未使用但保留:把 dp 换算成 COUI 的胶囊圆角时保持一致。 */
internal val CouiPillRadius = 999.dp

/** 供未来接入 COUI 滑块刻度时使用的字号,避免每次写魔法数。 */
internal val CouiStepLabelSize = 12.sp
