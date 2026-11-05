// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.lyrics.clock

import kotlin.math.abs

/**
 * 歌词时钟：把播放器给出的「采样位置」外推成每帧的连续位置。
 *
 * 播放器的 position 是几十到几百毫秒才更新一次的采样值，直接拿它画歌词会一顿一顿的；
 * 而简单的插值（向采样值缓动）又会持续滞后，跳行时能看出来。这里用的是**锚点外推**：
 * 每次收到新的采样就重设锚点（位置 + 当时的系统时钟），帧回调里用锚点 + 流逝时间 × 速度
 * 预测当前位置。预测值只 forward，不会无故倒退，除非偏差超过 [BACKWARD_TOLERANCE_MS]。
 *
 * 机制取自 NeriPlayer 的 `LyricPlaybackPosition.kt`（同为开源项目）：
 * - 重同步阈值 220 ms：采样与渲染值偏差到这么大才认为用户 seek 了；
 * - 倒退容忍 24 ms：小于这个量的倒退还保持上一帧，避免抖动；
 * - 低功耗时把刷新间隔降到 1/2，仍用同一套外推。
 */
object LyricClock {

    /** 采样与渲染值差到这个量才跳变（视为 seek）。 */
    const val RESYNC_THRESHOLD_MS = 220L

    /** 允许保留的倒退量。 */
    const val BACKWARD_TOLERANCE_MS = 24L

    /**
     * 收到新采样时，决定从哪里继续外推。
     *
     * 暂停时直接对齐采样（暂停不该有外推）；播放中只在偏差超过阈值时对齐，
     * 否则沿用已渲染的位置，避免每次采样都把进度往回拽。
     */
    fun resync(externalPositionMs: Long, renderedPositionMs: Long, isPlaying: Boolean): Long {
        if (!isPlaying) return externalPositionMs
        if (abs(externalPositionMs - renderedPositionMs) >= RESYNC_THRESHOLD_MS) return externalPositionMs
        return if (externalPositionMs > renderedPositionMs) externalPositionMs else renderedPositionMs
    }

    /**
     * 由锚点外推当前帧的位置。
     *
     * @param anchorPositionMs 锚点位置
     * @param elapsedMs 距锚点建立已过去的毫秒（单调时钟）
     * @param playbackSpeed 播放速度，1.0 为常速
     * @param previousRenderedMs 上一帧渲染的位置
     */
    fun predict(
        anchorPositionMs: Long,
        elapsedMs: Long,
        playbackSpeed: Float = 1f,
        previousRenderedMs: Long = anchorPositionMs,
    ): Long {
        val delta = (elapsedMs.coerceAtLeast(0L) * playbackSpeed.coerceAtLeast(0f)).toLong()
        val predicted = anchorPositionMs + delta
        val backward = previousRenderedMs - predicted
        return if (backward in 1..BACKWARD_TOLERANCE_MS) previousRenderedMs else predicted
    }

    /**
     * 是否该在这一帧重新渲染。
     *
     * 低功耗模式下把刷新间隔翻倍，仍与普通模式共用同一时间轴，所以两种模式切换时不会跳。
     */
    fun shouldRender(
        lastRenderedNanos: Long,
        frameNanos: Long,
        baseIntervalNanos: Long,
        lowPower: Boolean = false,
    ): Boolean {
        val interval = if (lowPower) baseIntervalNanos * 2 else baseIntervalNanos
        if (lastRenderedNanos == 0L) return true
        return frameNanos - lastRenderedNanos >= interval
    }
}