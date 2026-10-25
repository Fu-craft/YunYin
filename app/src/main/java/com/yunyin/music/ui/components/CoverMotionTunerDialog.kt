// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.CoverMotion
import com.yunyin.music.ui.theme.SFPro
import kotlinx.coroutines.delay

/**
 * 「封面动效手感」 — tune the pause/resume spring by watching it, not by guessing.
 *
 * The preview animates a placeholder card through exactly the specs the player uses
 * ([CoverMotion.pauseSpec] / [CoverMotion.resumeSpec]), then holds at each end so the settle is
 * visible. Every slider change restarts the loop and is pushed to [CoverMotion], so the value the
 * user lands on is the value the player will use.
 */
@Composable
fun CoverMotionTunerDialog(
    onDismiss: () -> Unit,
) {
    var params by remember { mutableStateOf(CoverMotion.params.value) }
    val preview = remember { Animatable(1f) }

    // Restart the loop whenever a value changes: the point is to feel the new curve immediately.
    LaunchedEffect(params) {
        CoverMotion.set(params)
        while (true) {
            preview.animateTo(1f, animationSpec = CoverMotion.pauseSpec(params))
            preview.snapTo(1f)
            delay(220)
            preview.animateTo(params.pausedScale, animationSpec = CoverMotion.pauseSpec(params))
            delay(420)
            preview.animateTo(1f, animationSpec = CoverMotion.resumeSpec(params))
            delay(320)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("封面动效手感", fontFamily = SFPro, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(120.dp)
                            .graphicsLayer {
                                scaleX = preview.value
                                scaleY = preview.value
                            }
                            .background(
                                AppTheme.palette.secondaryBackground,
                                RoundedCornerShape(12.dp),
                            ),
                    )
                }
                TunerSlider(
                    label = "暂停阻尼",
                    value = params.pauseDamping,
                    range = CoverMotion.MIN_DAMPING..CoverMotion.MAX_DAMPING,
                ) { params = params.copy(pauseDamping = it) }
                TunerSlider(
                    label = "暂停刚度",
                    value = params.pauseStiffness,
                    range = CoverMotion.MIN_STIFFNESS..CoverMotion.MAX_STIFFNESS,
                ) { params = params.copy(pauseStiffness = it) }
                TunerSlider(
                    label = "恢复阻尼",
                    value = params.resumeDamping,
                    range = CoverMotion.MIN_DAMPING..CoverMotion.MAX_DAMPING,
                ) { params = params.copy(resumeDamping = it) }
                TunerSlider(
                    label = "目标缩放",
                    value = params.pausedScale,
                    range = CoverMotion.MIN_PAUSED_SCALE..CoverMotion.MAX_PAUSED_SCALE,
                ) { params = params.copy(pausedScale = it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CoverMotion.set(params)
                onDismiss()
            }) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = {
                params = CoverMotion.Params()
                CoverMotion.set(params)
            }) { Text("恢复默认") }
        },
    )
}

@Composable
private fun TunerSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = SFPro, fontSize = 13.sp)
            Text(formatValue(value), fontFamily = SFPro, fontSize = 13.sp)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

private fun formatValue(value: Float): String =
    if (value >= 100f) value.toInt().toString() else String.format(java.util.Locale.US, "%.2f", value)
