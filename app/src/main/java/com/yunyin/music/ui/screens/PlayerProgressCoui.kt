// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.itos.coui.slider.CouiSliderColors
import com.itos.coui.slider.Slider

/**
 * 播放进度条的 **COUI(凝光)实现**。
 *
 * 与 [PlayerProgressBar] 并存,由 [com.yunyin.music.ui.style.UiStyle] 选择。
 *
 * 两个它必须保住的性质(和 miuix 版一致):
 *  1. **拖拽时进度条不能被播放位置拽回去** —— 拖动期间显示的是手指位置,`positionProvider` 暂停采样;
 *  2. **只在松手时 seek 一次** —— `onValueChange` 只更新草稿,`onValueChangeFinished` 才提交,
 *     否则一次拖动会给播放器发几十次 seek。
 *
 * 采样仍然逐帧(`withFrameNanos`),因为播放器每 250 ms 才发布一次位置,直接用它会让滑块每秒跳 4 下。
 */
@Composable
internal fun CouiPlayerProgressBar(
    /** Interpolated playback position in ms; safe to poll once per frame. */
    positionProvider: () -> Long,
    durationMs: Long,
    /** Fraction 0..1. Called once on release. */
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val position by rememberUpdatedState(positionProvider)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var frameFraction by remember { mutableFloatStateOf(0f) }

    // 逐帧跟随:只在未拖动时更新,拖动时由手指接管。
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                if (!dragging) {
                    frameFraction = if (durationMs > 0L) {
                        (position() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    Slider(
        value = if (dragging) dragFraction else frameFraction,
        onValueChange = { raw ->
            dragging = true
            dragFraction = raw.coerceIn(0f, 1f)
        },
        onValueChangeFinished = {
            dragging = false
            onSeek(dragFraction)
        },
        valueRange = 0f..1f,
        // 已播色用白色而不是强调色:这一条压在模糊封面上,红色会在深色封面里发闷,白色与两侧时间
        // 标签同色,读起来是一条完整的"播放线"。
        colors = CouiSliderColors(
            activeTrack = PlayerTrackActive,
            inactiveTrack = PlayerTrackInactive,
            thumb = Color.White,
            activeTick = PlayerTrackActive,
            inactiveTick = PlayerTrackInactive,
            thumbShadow = Color.Black.copy(alpha = 0.35f),
        ),
        // 铺满整行。Slider 自己不撑宽,只有给了 fillMaxWidth 才画满,否则只按内容宽度画一小段。
        modifier = modifier.fillMaxWidth(),
    )
}

/** 已播段:白 95%,在任意封面底色上都清楚。 */
private val PlayerTrackActive = Color.White.copy(alpha = 0.95f)

/** 未播段:白 25%,存在感够低,不挡住封面。 */
private val PlayerTrackInactive = Color.White.copy(alpha = 0.25f)
