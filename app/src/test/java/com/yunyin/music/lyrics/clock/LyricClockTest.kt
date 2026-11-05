// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.lyrics.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricClockTest {

    @Test
    fun pausedAlwaysSnapsToSample() {
        assertEquals(9_000L, LyricClock.resync(9_000, 1_000, isPlaying = false))
    }

    @Test
    fun largeGapSnapsWhilePlaying() {
        // 用户 seek：偏差超过 220 ms，必须跟过去，不能继续外推旧锚点。
        assertEquals(30_000L, LyricClock.resync(30_000, 5_000, isPlaying = true))
    }

    @Test
    fun smallGapKeepsRenderedPosition() {
        // 采样只比渲染值小 80 ms：不要往回拽，继续用渲染位置。
        assertEquals(5_000L, LyricClock.resync(4_920, 5_000, isPlaying = true))
    }

    @Test
    fun predictExtrapolatesWithSpeed() {
        assertEquals(1_500L, LyricClock.predict(1_000, 500))
        assertEquals(2_000L, LyricClock.predict(1_000, 500, playbackSpeed = 2f))
    }

    @Test
    fun tinyBackwardIsClamped() {
        // 预测值比上一帧小 10 ms（<24 ms 容忍）：保持上一帧，避免抖动。
        assertEquals(1_010L, LyricClock.predict(1_000, 0, previousRenderedMs = 1_010))
    }

    @Test
    fun bigBackwardIsAccepted() {
        // 倒退超过容忍量说明确实回跳了，跟着走。
        assertEquals(1_000L, LyricClock.predict(1_000, 0, previousRenderedMs = 1_200))
    }

    @Test
    fun frameThrottleRespectsIntervalAndLowPower() {
        val interval = 16_000_000L
        assertTrue(LyricClock.shouldRender(0L, 1_000L, interval))
        assertFalse(LyricClock.shouldRender(1_000_000_000L, 1_010_000_000L, interval))
        assertTrue(LyricClock.shouldRender(1_000_000_000L, 1_020_000_000L, interval))
        // 低功耗：间隔翻倍，16 ms 的帧不再渲染。
        assertFalse(LyricClock.shouldRender(1_000_000_000L, 1_020_000_000L, interval, lowPower = true))
        assertTrue(LyricClock.shouldRender(1_000_000_000L, 1_040_000_000L, interval, lowPower = true))
    }
}