package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The displacement rule of the live container warp.
 *
 * The shader itself cannot run on the JVM, so the properties that decide whether it reads as
 * geometry rather than a glitch are pinned here: the envelope is zero at both ends (start and end
 * are exact frames) and peaks at the midpoint; the radial dolly is 1 at both ends and reaches its
 * configured peak at the midpoint; every displacement scales by the single amplitude knob and is
 * proportional to the envelope; and the duration is long enough to be read.
 */
class TransitionWarpRulesTest {

    private val epsilon = 1e-5f

    @Test
    fun `envelope is zero at both ends and one at the midpoint`() {
        assertEquals(0f, TransitionWarpRules.envelope(0f), epsilon)
        assertEquals(0f, TransitionWarpRules.envelope(1f), epsilon)
        assertEquals(1f, TransitionWarpRules.envelope(0.5f), epsilon)
    }

    @Test
    fun `envelope stays inside zero to one`() {
        for (step in 0..200) {
            val value = TransitionWarpRules.envelope(step / 200f)
            assertTrue("envelope at $step was $value", value in 0f..1f)
        }
    }

    @Test
    fun `envelope rises then falls monotonically and symmetrically`() {
        var previous = 0f
        for (step in 0..100) {
            val value = TransitionWarpRules.envelope(step / 200f)
            assertTrue("envelope must rise in the first half", value >= previous)
            previous = value
        }
        previous = 1f
        for (step in 100..200) {
            val value = TransitionWarpRules.envelope(step / 200f)
            assertTrue("envelope must fall in the second half", value <= previous)
            previous = value
        }
        for (step in 0..100) {
            val p = step / 200f
            assertEquals(
                TransitionWarpRules.envelope(p),
                TransitionWarpRules.envelope(1f - p),
                epsilon,
            )
        }
    }

    @Test
    fun `radial scale is one at the ends and its peak at the midpoint`() {
        assertEquals(1f, TransitionWarpRules.radialScale(0f), epsilon)
        assertEquals(1f, TransitionWarpRules.radialScale(1f), epsilon)
        assertEquals(
            TransitionWarpRules.PEAK_RADIAL_SCALE,
            TransitionWarpRules.radialScale(0.5f),
            epsilon,
        )
    }

    @Test
    fun `radial scale is bounded and follows the envelope`() {
        val min = TransitionWarpRules.PEAK_RADIAL_SCALE - (1f - TransitionWarpRules.PEAK_RADIAL_SCALE) *
            (TransitionWarpRules.AMPLITUDE_SCALE - 1f)
        var previous = 1f
        for (step in 0..100) {
            val scale = TransitionWarpRules.radialScale(step / 200f)
            assertTrue("radial scale at $step was $scale", scale >= min - epsilon && scale <= 1f)
            assertTrue("radial scale must fall in the first half", scale <= previous + epsilon)
            previous = scale
        }
    }

    @Test
    fun `amplitude factor is zero at both ends and scales with the knob`() {
        assertEquals(0f, TransitionWarpRules.amplitudeFactor(0f), epsilon)
        assertEquals(0f, TransitionWarpRules.amplitudeFactor(1f), epsilon)
        assertEquals(TransitionWarpRules.AMPLITUDE_SCALE, TransitionWarpRules.amplitudeFactor(0.5f), epsilon)
        assertEquals(
            TransitionWarpRules.AMPLITUDE_SCALE * 2f,
            TransitionWarpRules.displacementDp(baseDp = 2f, progress = 0.5f),
            epsilon,
        )
        assertEquals(0f, TransitionWarpRules.displacementDp(baseDp = 80f, progress = 0f), epsilon)
    }

    @Test
    fun `peak amplitudes are strong enough to be perceived and finite`() {
        // 60 dp-equivalent peel or ripple would have been the old, imperceptible tuning; the new
        // design must exceed it on every axis, while staying in a sane range for a phone screen.
        assertTrue(TransitionWarpRules.PEEL_DP >= 60f)
        assertTrue(TransitionWarpRules.RIPPLE_DP >= 25f)
        assertTrue(TransitionWarpRules.DRAG_DP >= 30f)
        assertTrue(TransitionWarpRules.AMPLITUDE_SCALE > 0f)
        assertTrue(TransitionWarpRules.PEAK_RADIAL_SCALE in 0.85f..0.95f)
    }

    @Test
    fun `warp is long enough to read`() {
        assertTrue(TransitionWarpRules.DURATION_MS in 680L..760L)
    }

    @Test
    fun `direction leans the way the toggle goes`() {
        assertEquals(1f, TransitionWarpRules.direction(targetShowLyrics = true), 0f)
        assertEquals(-1f, TransitionWarpRules.direction(targetShowLyrics = false), 0f)
    }

    @Test
    fun `progress clamps and phase advances monotonically`() {
        assertEquals(0f, TransitionWarpRules.progress(1_000L, 1_000L), epsilon)
        assertEquals(
            1f,
            TransitionWarpRules.progress(1_000L, 1_000L + TransitionWarpRules.DURATION_MS),
            epsilon,
        )
        assertEquals(1f, TransitionWarpRules.progress(1_000L, 99_999L), epsilon)
        assertEquals(1f, TransitionWarpRules.progress(-1L, 5_000L), epsilon)

        assertEquals(0f, TransitionWarpRules.wavePhase(0f), epsilon)
        var previous = -1f
        for (step in 0..100) {
            val phase = TransitionWarpRules.wavePhase(step / 100f)
            assertTrue("phase must advance", phase >= previous)
            previous = phase
        }
        assertTrue(previous > 6f)
        assertEquals(
            TransitionWarpRules.smoothstep(0.2f, 0.8f, 0.5f),
            TransitionWarpRules.smoothstep(0.2f, 0.8f, 0.5f),
            0f,
        )
    }
}
