// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.lyrics.composable.lyrics

import androidx.compose.animation.core.DeferredTargetAnimation
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ApproachLayoutModifierNode
import androidx.compose.ui.layout.ApproachMeasureScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round

/**
 * 让每一行歌词自己在弹簧里移动到新位置，而不是动画整个列表的滚动偏移。
 *
 * 这是「换行丝滑」的关键：滚动只负责把行摆到目标槽位（`snap`，瞬时完成），位移的平滑
 * 由每行独立的 placement 弹簧提供。因此不存在「动画 + 到位判定」两套机制相互竞争，
 * 也就没有滑到位之后被再推一下的现象。
 *
 * 机制取自 Accompanist-Lyrics 上游（`utils/modifier/SpringPlacementModifier.kt`）。
 *
 * @param lookaheadScope 外层 [LookaheadScope]，用于拿到目标位置
 * @param itemKey 行标识；变化时重置动画并回到首帧语义
 * @param isManualScrolling 用户正在拖动：直接落位，不跟随
 * @param stiffness 弹簧刚度，越大越跟手
 */
internal class SpringPlacementModifierNode(
    var lookaheadScope: LookaheadScope,
    var itemKey: Any,
    var isManualScrolling: Boolean,
    var stiffness: Float,
) : ApproachLayoutModifierNode, Modifier.Node() {

    private var offsetAnimation = DeferredTargetAnimation(IntOffset.VectorConverter)

    private var isFirstFrame = true

    override fun isMeasurementApproachInProgress(lookaheadSize: IntSize): Boolean = false

    override fun Placeable.PlacementScope.isPlacementApproachInProgress(
        lookaheadCoordinates: LayoutCoordinates,
    ): Boolean {
        val target = with(lookaheadScope) {
            lookaheadScopeCoordinates.localLookaheadPositionOf(lookaheadCoordinates).round()
        }
        val spec: FiniteAnimationSpec<IntOffset> = if (isFirstFrame || isManualScrolling) {
            snap()
        } else {
            spring(dampingRatio = 0.95f, stiffness = stiffness)
        }
        offsetAnimation.updateTarget(target, coroutineScope, spec)
        return !offsetAnimation.isIdle
    }

    override fun ApproachMeasureScope.approachMeasure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            val coordinates = coordinates
            if (coordinates != null) {
                val target = with(lookaheadScope) {
                    lookaheadScopeCoordinates.localLookaheadPositionOf(coordinates).round()
                }
                val spec: FiniteAnimationSpec<IntOffset> = if (isFirstFrame || isManualScrolling) {
                    snap()
                } else {
                    spring(dampingRatio = 0.95f, stiffness = stiffness)
                }
                val animatedOffset = offsetAnimation.updateTarget(target, coroutineScope, spec)
                isFirstFrame = false

                val placementOffset = with(lookaheadScope) {
                    lookaheadScopeCoordinates.localPositionOf(coordinates, Offset.Zero).round()
                }
                val delta = animatedOffset - placementOffset
                placeable.place(delta.x, delta.y)
            } else {
                placeable.place(0, 0)
            }
        }
    }

    fun updateState(
        newScope: LookaheadScope,
        newKey: Any,
        newIsManualScrolling: Boolean,
        newStiffness: Float,
    ) {
        lookaheadScope = newScope
        isManualScrolling = newIsManualScrolling
        stiffness = newStiffness
        if (itemKey != newKey) {
            itemKey = newKey
            offsetAnimation = DeferredTargetAnimation(IntOffset.VectorConverter)
            isFirstFrame = true
        }
    }
}

internal data class SpringPlacementNodeElement(
    val lookaheadScope: LookaheadScope,
    val itemKey: Any,
    val isManualScrolling: Boolean,
    val stiffness: Float,
) : ModifierNodeElement<SpringPlacementModifierNode>() {

    override fun update(node: SpringPlacementModifierNode) {
        node.updateState(lookaheadScope, itemKey, isManualScrolling, stiffness)
    }

    override fun create(): SpringPlacementModifierNode =
        SpringPlacementModifierNode(lookaheadScope, itemKey, isManualScrolling, stiffness)
}

/** 给一行歌词挂上独立的 placement 弹簧。 */
internal fun Modifier.springPlacement(
    lookaheadScope: LookaheadScope,
    itemKey: Any,
    isManualScrolling: Boolean,
    stiffness: Float = 300f,
): Modifier = this.then(
    SpringPlacementNodeElement(lookaheadScope, itemKey, isManualScrolling, stiffness),
)
