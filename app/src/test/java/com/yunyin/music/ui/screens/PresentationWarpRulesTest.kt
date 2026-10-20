package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The displacement rule of the approximate mesh warp.
 *
 * The shader itself cannot run on the JVM, so the properties that decide whether the effect reads
 * as a warp rather than a glitch are pinned here: the envelope is zero at both ends (start and end
 * are exact frames), never exceeds its amplitude, and rises/falls smoothly; the snapshot's alpha is
 * fully opaque through the first half and reaches zero at the end (no empty border can be seen); and
 * a cancel always fades the overlay out completely.
 */
class PresentationWarpRulesTest {

    private val epsilon = 1e-5f

    @Test
    fun `envelope is zero at both ends and one at the midpoint`() {
        assertEquals(0f, PresentationWarpRules.envelope(0f), epsilon)
        assertEquals(0f, PresentationWarpRules.envelope(1f), epsilon)
        assertEquals(1f, PresentationWarpRules.envelope(0.5f), epsilon)
    }

    @Test
    fun `envelope stays inside zero to one`() {
        for (step in 0..200) {
            val value = PresentationWarpRules.envelope(step / 200f)
            assertTrue("envelope at $step was $value", value in 0f..1f)
        }
    }

    @Test
    fun `envelope rises then falls monotonically and symmetrically`() {
        var previous = 0f
        for (step in 0..100) {
            val value = PresentationWarpRules.envelope(step / 200f)
            assertTrue("envelope must rise in the first half", value >= previous)
            previous = value
        }
        previous = 1f
        for (step in 100..200) {
            val value = PresentationWarpRules.envelope(step / 200f)
            assertTrue("envelope must fall in the second half", value <= previous)
            previous = value
        }
        for (step in 0..100) {
            val p = step / 200f
            assertEquals(
                PresentationWarpRules.envelope(p),
                PresentationWarpRules.envelope(1f - p),
                epsilon,
            )
        }
    }

    @Test
    fun `overlay is fully gone at both ends`() {
        assertEquals(0f, PresentationWarpRules.overlayAlpha(0f), epsilon)
        assertEquals(0f, PresentationWarpRules.overlayAlpha(1f), epsilon)
    }

    @Test
    fun `overlay holds full opacity through the first half and fades in the second`() {
        // The ramp exists to hide the one-or-two-frame gap between the frozen frame and the live
        // screen; after it, the overlay must be fully opaque until the midpoint.
        val rampEnd = PresentationWarpRules.FADE_IN_MS.toFloat() / PresentationWarpRules.DURATION_MS
        for (step in 0..50) {
            val p = step / 100f
            val alpha = PresentationWarpRules.overlayAlpha(p)
            if (p >= rampEnd) {
                assertTrue("alpha at ${step}% was $alpha", alpha > 0.99f)
            } else {
                assertTrue("ramp alpha at ${step}% was $alpha", alpha in 0f..1f)
            }
        }
        var previous = 1f
        for (step in 50..100) {
            val alpha = PresentationWarpRules.overlayAlpha(step / 100f)
            assertTrue("overlay must only fade out in the second half", alpha <= previous + epsilon)
            previous = alpha
        }
    }

    @Test
    fun `progress is clamped to the warp duration`() {
        assertEquals(0f, PresentationWarpRules.progress(1_000L, 1_000L), epsilon)
        assertEquals(1f, PresentationWarpRules.progress(1_000L, 1_000L + PresentationWarpRules.DURATION_MS), epsilon)
        assertEquals(1f, PresentationWarpRules.progress(1_000L, 99_999L), epsilon)
        assertEquals(1f, PresentationWarpRules.progress(-1L, 5_000L), epsilon)
    }

    @Test
    fun `cancel progress and alpha are well ordered`() {
        assertEquals(0f, PresentationWarpRules.cancelProgress(-1L, 9_999L), epsilon)
        assertEquals(0f, PresentationWarpRules.cancelProgress(5_000L, 5_000L), epsilon)
        assertEquals(1f, PresentationWarpRules.cancelProgress(5_000L, 5_000L + PresentationWarpRules.CANCEL_FADE_MS), epsilon)
        assertEquals(1f, PresentationWarpRules.cancelProgress(5_000L, 999_999L), epsilon)

        assertEquals(1f, PresentationWarpRules.cancelAlpha(0f), epsilon)
        assertEquals(0f, PresentationWarpRules.cancelAlpha(1f), epsilon)
        for (step in 0..20) {
            val alpha = PresentationWarpRules.cancelAlpha(step / 20f)
            assertTrue("cancel alpha out of range at $step", alpha in 0f..1f)
        }
    }

    @Test
    fun `ripple phase advances monotonically and is bounded to one and a half waves`() {
        var previous = -1f
        for (step in 0..100) {
            val phase = PresentationWarpRules.wavePhase(step / 100f)
            assertTrue("phase must advance", phase >= previous)
            previous = phase
        }
        assertEquals(0f, PresentationWarpRules.wavePhase(0f), epsilon)
        assertTrue(PresentationWarpRules.wavePhase(1f) > 9f)
    }

    @Test
    fun `smoothstep is clamped and symmetric`() {
        assertEquals(0f, PresentationWarpRules.smoothstep(0.2f, 0.8f, 0f), epsilon)
        assertEquals(1f, PresentationWarpRules.smoothstep(0.2f, 0.8f, 1f), epsilon)
        assertEquals(0.5f, PresentationWarpRules.smoothstep(0f, 1f, 0.5f), epsilon)
    }

    @Test
    fun `amplitude constants stay in the visible-but-gentle range`() {
        assertTrue(PresentationWarpRules.PEEL_DP in 8f..40f)
        assertTrue(PresentationWarpRules.RIPPLE_DP in 4f..24f)
        assertTrue(PresentationWarpRules.EDGE_DRAG in 0.01f..0.10f)
        assertTrue(PresentationWarpRules.DURATION_MS == 560L)
        assertTrue(PresentationWarpRules.OUTGOING_FADE_MS < PresentationWarpRules.DURATION_MS)
        assertTrue(PresentationWarpRules.CANCEL_FADE_MS < PresentationWarpRules.DURATION_MS)
    }
}
