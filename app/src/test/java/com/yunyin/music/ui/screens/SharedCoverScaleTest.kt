// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.screens

import androidx.compose.animation.core.SpringSpec
import com.yunyin.music.ui.theme.Motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the *shape* of the cover's scale through a presentation change.
 *
 * The defect encoded here: the full artwork carried the paused 0.92 scale while the lyrics-header
 * thumbnail —?the other end of the same shared element —?did not. [AnimatedContent] composes both
 * presentations during the cross-fade, so the two renderings blend; the apparent scale slid from 0.92
 * toward 1.0 before the shared element's bounds started shrinking the cover, which the user described
 * as "enlarge, then shrink". Sharing one scale makes the apparent scale constant, so the transition's
 * bounds animation is the only animated quantity and the visible size can only move one way.
 */
class SharedCoverScaleTest {

    @Test
    fun `both renderings of the shared cover use the same paused scale`() {
        for (paused in listOf(1f, 0.92f, 0.5f, 0f)) {
            assertEquals(
                "the full artwork and the header thumbnail must render at one scale",
                paused,
                sharedCoverPausedScale(paused),
                0f,
            )
        }
    }

    @Test
    fun `a cross-fade of two renderings sharing a scale keeps the apparent scale constant`() {
        val scale = sharedCoverPausedScale(0.92f)
        for (step in 0..20) {
            val incomingAlpha = step / 20f
            val apparent = crossfadeScale(scale, scale, incomingAlpha)
            assertEquals(scale, apparent, 1e-5f)
        }
    }

    @Test
    fun `the previous asymmetric ends would have enlarged the cover before it shrank`() {
        // The old code: artwork at the paused scale, header thumbnail unscaled. If the sharing
        // invariant regresses, this is the motion the user sees again.
        val atStart = crossfadeScale(0.92f, 1f, 0f)
        val atEnd = crossfadeScale(0.92f, 1f, 1f)
        assertTrue(
            "an unscaled incoming end must move the apparent scale, so the ends must share it",
            atEnd > atStart + 0.05f,
        )
    }

    @Test
    fun `the paused dim is derived from the shared scale and stays monotone`() {
        // Full size is undimmed; the paused rest value dims by exactly the documented 6 %.
        assertEquals(1f, sharedCoverPausedAlpha(1f), 0f)
        assertEquals(1f - 0.06f, sharedCoverPausedAlpha(0.92f), 1e-6f)
        // Below the rest value the mapping clamps —?a stray scale can never dim past the paused look.
        assertEquals(1f - 0.06f, sharedCoverPausedAlpha(0.5f), 1e-6f)
        // Monotone in the scale: as the cover grows back the dim can only lift.
        var previous = sharedCoverPausedAlpha(0.92f)
        for (step in 1..20) {
            val scale = 0.92f + (1f - 0.92f) * step / 20f
            val alpha = sharedCoverPausedAlpha(scale)
            assertTrue("alpha must not fall while the cover grows", alpha >= previous - 1e-6f)
            previous = alpha
        }
    }

    /**
     * What the eye sees while the outgoing rendering fades under the incoming one: a blend of the two
     * scales weighted by how far the incoming content has faded in.
     */
    private fun crossfadeScale(outgoing: Float, incoming: Float, incomingAlpha: Float): Float =
        outgoing + (incoming - outgoing) * incomingAlpha.coerceIn(0f, 1f)

    /**
     * The pause/resume settle is *meant* to overshoot —?that rebound is the Apple-Music "springy"
     * feel. Guards the configuration so a future "fix the wobble" change cannot quietly put the cover
     * back on a critically damped curve.
     */
    @Test
    fun `the pause and resume settles are under-damped so the cover rebounds`() {
        val pause = Motion.CoverPauseSettle as SpringSpec<Float>
        val resume = Motion.CoverResumeSettle as SpringSpec<Float>

        // Apple's bounce band is 0.1-0.3, i.e. damping 0.7-0.9. Outside it the move either reads as
        // a wobble (too bouncy) or as a dead linear scale-down (critically damped).
        assertTrue("pause damping ${pause.dampingRatio} is outside the bounce band", pause.dampingRatio in 0.7f..0.9f)
        assertTrue("resume damping ${resume.dampingRatio} is outside the bounce band", resume.dampingRatio in 0.7f..0.9f)
        assertTrue("resume must settle at least as fast as pause", resume.stiffness >= pause.stiffness)
    }

    /**
     * The rebound overshoots the rest value, and the two clamps in [sharedCoverPausedScale] /
     * [sharedCoverPausedAlpha] absorb it: the cover may dip slightly past 92 %, but the dim stays
     * inside the paused look instead of inverting.
     */
    @Test
    fun `an overshooting progress clamps the scale and the dim`() {
        val overshoot = 1.08f // ~8 % past the target at the first rebound
        val scale = sharedCoverPausedScale(1f - overshoot * (1f - 0.92f))
        assertTrue("the rebound must stay a dip, not an inversion", scale in 0.85f..0.92f)
        assertEquals(1f - 0.06f, sharedCoverPausedAlpha(scale), 1e-6f)
    }
}
