// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.lyrics.composable.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricScrollFollowerTest {

    private fun run(follower: LyricScrollFollower, target: Float, frames: Int) {
        repeat(frames) { follower.step(target, 1f / 60f) }
    }

    @Test
    fun convergesToTheTarget() {
        val follower = LyricScrollFollower()
        run(follower, target = 100f, frames = 120)
        assertEquals(100f, follower.offset, 0.5f)
    }

    @Test
    fun neverOvershootsWithCriticalDamping() {
        val follower = LyricScrollFollower()
        var max = 0f
        repeat(120) {
            max = maxOf(max, follower.step(100f, 1f / 60f))
        }
        // 临界阻尼不应越过目标；允许亚像素的积分误差。
        assertTrue("overshoot to $max", max <= 100.5f)
    }

    @Test
    fun movesMonotonicallyTowardTheTarget() {
        val follower = LyricScrollFollower()
        var previous = follower.offset
        repeat(60) {
            val next = follower.step(80f, 1f / 60f)
            assertTrue("went backwards: $previous -> $next", next >= previous - 0.01f)
            previous = next
        }
    }

    @Test
    fun followsAMovingTargetWithoutStalling() {
        val follower = LyricScrollFollower()
        var target = 0f
        repeat(60) {
            target += 2f              // 目标每帧移动：模拟连续换行
            follower.step(target, 1f / 60f)
        }
        // 目标匀速移动时，临界阻尼弹簧的稳态滞后约 2v/ω（ω = sqrt(STIFFNESS)）；这里只要求
        // 它跟得上，而不是零滞后——零滞后需要速度前馈，属于另一层。`n        assertTrue("lag ${target - follower.offset}", target - follower.offset < 20f)
    }

    @Test
    fun resetDropsTheMomentum() {
        val follower = LyricScrollFollower()
        run(follower, target = 100f, frames = 3)
        follower.reset(offset = 0f)
        assertEquals(0f, follower.offset, 0.001f)
        // 重置后不应再带着旧速度冲过去。
        assertTrue(follower.step(0f, 1f / 60f) <= 0.01f)
    }

    @Test
    fun hugeFrameGapIsClamped() {
        val follower = LyricScrollFollower()
        val afterStall = follower.step(500f, deltaSeconds = 2f)
        // 一步两秒若不被夹住，显式积分会直接飞出去。
        assertTrue("diverged to $afterStall", afterStall < 500f)
    }
}