// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yunyin.music.ui.theme.Motion

/**
 * Entrance for a state that has nothing to animate around it — an empty shelf, a failed page.
 *
 * 160 ms fade plus an 8 dp rise, played **once** when the state first appears. The reason this is one
 * modifier instead of an `AnimatedVisibility` per screen: an empty state is a single block of text, so
 * it should arrive the same way everywhere, and a screen that has no content should not be the one
 * place where a hard cut is left in.
 *
 * Deliberately not a staggered/list animation: this is the "there is nothing here" surface, and the
 * one row that carries it moves as a whole.
 *
 * Honours the system "remove animations" setting by starting at its final value.
 */
@Composable
fun Modifier.enterOnceRise(
    durationMs: Int = 160,
    rise: Dp = 8.dp,
): Modifier {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!Motion.animatorsEnabled()) {
            appear.snapTo(1f)
            return@LaunchedEffect
        }
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = Motion.duration(durationMs), easing = Motion.EaseOut),
        )
    }
    return this.graphicsLayer {
        alpha = appear.value
        translationY = (1f - appear.value) * rise.toPx()
    }
}
