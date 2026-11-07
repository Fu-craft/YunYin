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
import androidx.compose.ui.Modifier
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
        modifier = modifier,
    )
}
