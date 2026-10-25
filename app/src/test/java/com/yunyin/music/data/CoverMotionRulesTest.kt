// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import androidx.compose.animation.core.SpringSpec
import com.yunyin.music.ui.theme.CoverMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins 「封面动效手感」 defaults and the clamps that keep stored values usable. */
class CoverMotionRulesTest {

    @Test
    fun `defaults are the soft pair, not the hard snap`() {
        val p = CoverMotion.Params()
        assertEquals(1.00f, p.pauseDamping, 1e-6f)
        assertEquals(220f, p.pauseStiffness, 1e-3f)
        assertEquals(0.92f, p.resumeDamping, 1e-6f)
        assertEquals(0.94f, p.pausedScale, 1e-6f)
        assertTrue("Apple's pause is critically damped: no wobble", p.pauseDamping >= 0.99f)
        assertTrue("pause must feel soft, not snapped", p.pauseStiffness <= 260f)
    }

    @Test
    fun `out of range values are clamped on read`() {
        val wild = CoverMotion.Params(
            pauseDamping = 5f,
            pauseStiffness = 99999f,
            resumeDamping = -1f,
            resumeStiffness = 1f,
            pausedScale = 0.1f,
        ).sanitized()
        assertEquals(CoverMotion.MAX_DAMPING, wild.pauseDamping, 1e-6f)
        assertEquals(CoverMotion.MAX_STIFFNESS, wild.pauseStiffness, 1e-3f)
        assertEquals(CoverMotion.MIN_DAMPING, wild.resumeDamping, 1e-6f)
        assertEquals(CoverMotion.MIN_STIFFNESS, wild.resumeStiffness, 1e-3f)
        assertEquals(CoverMotion.MIN_PAUSED_SCALE, wild.pausedScale, 1e-6f)
    }

    @Test
    fun `specs are built from the current params`() {
        val soft = CoverMotion.Params(pauseDamping = 0.9f, pauseStiffness = 300f)
        val pause = CoverMotion.pauseSpec(soft) as SpringSpec<Float>
        assertEquals(0.9f, pause.dampingRatio, 1e-6f)
        assertEquals(300f, pause.stiffness, 1e-3f)
        val resume = CoverMotion.resumeSpec(soft) as SpringSpec<Float>
        assertEquals(soft.resumeDamping, resume.dampingRatio, 1e-6f)
    }

    @Test
    fun `pause spec and defaults survive a round trip through set`() {
        CoverMotion.set(CoverMotion.Params(pauseDamping = 0.7f, pausedScale = 0.9f))
        assertEquals(0.7f, CoverMotion.params.value.pauseDamping, 1e-6f)
        assertEquals(0.9f, CoverMotion.params.value.pausedScale, 1e-6f)
        CoverMotion.set(CoverMotion.Params())
        assertEquals(CoverMotion.DEFAULT_PAUSE_DAMPING, CoverMotion.params.value.pauseDamping, 1e-6f)
    }
}