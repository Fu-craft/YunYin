// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yunyin.music.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 媒体音量条：左侧静音、中间细进度条、右侧增音。
 *
 * 照搬 AuraPlayer 的 `OneUiPlayerVolumeSlider`：两侧各一个 30dp 圆角按钮（点一次步进一格），
 * 中间是一条按 `AudioManager.STREAM_MUSIC` 比例显示的细条。拖动时停止轮询，避免与手指抢值；
 * 松手后继续每 300ms 同步一次，这样用音量键调整也能反映到界面上。
 *
 * 为什么不直接听 `VolumeProviderCompat`：我们播放走 MediaSession，音量归系统管，
 * 读 `AudioManager` 才是唯一真实的来源。
 */
@Composable
fun VolumeSliderRow(
    modifier: Modifier = Modifier,
    accentColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.26f),
) {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    if (manager == null) return

    val max = remember(manager) {
        manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var value by remember {
        mutableFloatStateOf(manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat())
    }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(manager, dragging) {
        if (dragging) return@LaunchedEffect
        while (true) {
            value = manager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
            delay(300)
        }
    }

    val fixed = remember(manager) { manager.isVolumeFixed }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        VolumeStepButton(
            iconRes = R.drawable.aura_ic_volume_muted,
            description = "减小音量",
            enabled = !fixed,
            onClick = {
                val next = (manager.getStreamVolume(AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(0)
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                value = next / max.toFloat()
            },
        )
        Spacer(Modifier.width(8.dp))
        GlowProgressBar(
            value = value,
            accentColor = accentColor,
            trackColor = trackColor,
            modifier = Modifier.weight(1f).height(24.dp),
            enabled = !fixed,
            label = "媒体音量",
            onChange = { next ->
                dragging = true
                value = next
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, (next * max).roundToInt(), 0)
            },
            onFinished = { dragging = false },
        )
        Spacer(Modifier.width(8.dp))
        VolumeStepButton(
            iconRes = R.drawable.aura_ic_volume_full,
            description = "增大音量",
            enabled = !fixed,
            onClick = {
                val next = (manager.getStreamVolume(AudioManager.STREAM_MUSIC) + 1).coerceAtMost(max)
                manager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                value = next / max.toFloat()
            },
        )
    }
}

@Composable
private fun VolumeStepButton(
    iconRes: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = description,
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = if (enabled) 0.9f else 0.4f)),
            modifier = Modifier.size(16.dp),
        )
    }
}
