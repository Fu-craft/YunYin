// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.lyrics.composable.lyrics

/**
 * 连续跟随器：让列表以临界阻尼追随一个**连续目标位置**，而不是"发一次指令、滚到某一项"。
 *
 * 原来的做法是离散制——决策器说"去第 N 行"，动画执行，双方各自判断"到没到"。两套判定
 * 差几像素就会打架，表现为换行滑到位后又被轻推一下。连续跟随没有"到达"这个概念：每一帧
 * 都朝目标推进一点，目标本身随播放位置连续移动，所以不存在第二段运动。
 *
 * 半隐式欧拉积分（位置先由速度更新，速度再由弹簧力更新），临界阻尼（[DAMPING] = 1）时不过冲。
 */
internal class LyricScrollFollower(
    startOffset: Float = 0f,
) {
    var offset: Float = startOffset
        private set

    private var velocity: Float = 0f

    /** 清零并停在 [offset]。拖动开始、切歌、seek 时用来放弃当前惯性。 */
    fun reset(offset: Float = 0f) {
        this.offset = offset
        velocity = 0f
    }

    /**
     * 推进一帧，返回新的偏移。
     *
     * @param targetOffset 这一帧该到的位置（由播放时间连续算出）
     * @param deltaSeconds 距上一帧的秒数，调用方从 `withFrameNanos` 的时间差算
     */
    fun step(targetOffset: Float, deltaSeconds: Float): Float {
        if (deltaSeconds <= 0f) return offset
        // 大步长会让显式积分发散；真实帧间隔远小于这个上限，超出的部分只可能是掉帧或
        // 首帧，夹住才能保证数值稳定。
        val dt = deltaSeconds.coerceAtMost(MAX_STEP_SECONDS)

        val displacement = targetOffset - offset
        val spring = displacement * STIFFNESS
        val damper = -velocity * (2f * DAMPING * kotlin.math.sqrt(STIFFNESS))

        velocity += (spring + damper) * dt
        offset += velocity * dt

        // 静止判定：避免长期在亚像素级抖动（每帧都改 offset 会一直触发重组）。
        if (kotlin.math.abs(targetOffset - offset) < REST_EPSILON_PX &&
            kotlin.math.abs(velocity) < REST_EPSILON_PX
        ) {
            offset = targetOffset
            velocity = 0f
        }
        return offset
    }

    internal companion object {
        /** 弹簧刚度；越大越跟手，越小越柔和。 */
        const val STIFFNESS = 400f

        /** 1.0 = 临界阻尼，不过冲。 */
        const val DAMPING = 1f

        /** 单帧最大积分步长（秒）。 */
        const val MAX_STEP_SECONDS = 1f / 30f

        /** 亚像素静止阈值。 */
        const val REST_EPSILON_PX = 0.5f
    }
}