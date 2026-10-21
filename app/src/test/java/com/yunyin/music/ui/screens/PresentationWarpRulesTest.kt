package com.yunyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The displacement rule of the snapshot-overlay warp.
 *
 * The shader cannot run on the JVM, so the properties that decide whether the effect reads as a warp
 * rather than a glitch are pinned here: the envelope is zero at both ends (start and end are exact
 * frames) and peaks at the midpoint; the radial dolly is 1 at both ends and reaches its configured
 * peak at the midpoint; the amplitudes are large enough to be perceived; the snapshot's alpha holds
 * through the first half and reaches zero at the end (no border can be exposed); and the overlay's
 * duration is long enough to be read while the live cross-fade keeps its own 560 ms fallback.
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
    fun `radial dolly is one at the ends and its peak at the midpoint`() {
        assertEquals(1f, PresentationWarpRules.radialScale(0f), epsilon)
        assertEquals(1f, PresentationWarpRules.radialScale(1f), epsilon)
        assertEquals(
            PresentationWarpRules.PEAK_RADIAL_SCALE,
            PresentationWarpRules.radialScale(0.5f),
            epsilon,
        )
        assertTrue(PresentationWarpRules.PEAK_RADIAL_SCALE in 0.85f..0.95f)
    }

    @Test
    fun `amplitude factor is zero at both ends and scales with the knob`() {
        assertEquals(0f, PresentationWarpRules.amplitudeFactor(0f), epsilon)
        assertEquals(0f, PresentationWarpRules.amplitudeFactor(1f), epsilon)
        assertEquals(PresentationWarpRules.AMPLITUDE_SCALE, PresentationWarpRules.amplitudeFactor(0.5f), epsilon)
        assertEquals(
            PresentationWarpRules.AMPLITUDE_SCALE * 2f,
            PresentationWarpRules.displacementDp(baseDp = 2f, progress = 0.5f),
            epsilon,
        )
        assertEquals(0f, PresentationWarpRules.displacementDp(baseDp = 72f, progress = 0f), epsilon)
    }

    @Test
    fun `amplitudes are strong enough to be perceived and finite`() {
        // The old snapshot tuning (22 dp peel / 12 dp ripple) was imperceptible; this design must be
        // clearly above it on every axis while staying inside a sane range for a phone screen.
        assertTrue(PresentationWarpRules.PEEL_DP >= 60f)
        assertTrue(PresentationWarpRules.RIPPLE_DP >= 25f)
        assertTrue(PresentationWarpRules.DRAG_DP >= 30f)
        assertTrue(PresentationWarpRules.AMPLITUDE_SCALE > 0f)
    }

    @Test
    fun `warp is long enough to read while the fallback cross-fade stays 560`() {
        assertTrue(PresentationWarpRules.DURATION_MS in 680L..760L)
        assertTrue(PresentationWarpRules.DURATION_MS > 560L)
        assertTrue(PresentationWarpRules.OUTGOING_FADE_MS < PresentationWarpRules.DURATION_MS)
        assertTrue(PresentationWarpRules.CANCEL_FADE_MS < PresentationWarpRules.DURATION_MS)
    }

    @Test
    fun `direction leans the way the toggle goes`() {
        assertEquals(1f, PresentationWarpRules.direction(targetShowLyrics = true), 0f)
        assertEquals(-1f, PresentationWarpRules.direction(targetShowLyrics = false), 0f)
    }

    @Test
    fun `snapshot alpha holds through the first half and reaches zero at the end`() {
        assertEquals(0f, PresentationWarpRules.overlayAlpha(0f), epsilon)
        assertEquals(0f, PresentationWarpRules.overlayAlpha(1f), epsilon)
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
    fun `progress clamps, phase advances and cancel fades fully`() {
        assertEquals(0f, PresentationWarpRules.progress(1_000L, 1_000L), epsilon)
        assertEquals(
            1f,
            PresentationWarpRules.progress(1_000L, 1_000L + PresentationWarpRules.DURATION_MS),
            epsilon,
        )
        assertEquals(1f, PresentationWarpRules.progress(1_000L, 99_999L), epsilon)
        assertEquals(1f, PresentationWarpRules.progress(-1L, 5_000L), epsilon)

        assertEquals(0f, PresentationWarpRules.wavePhase(0f), epsilon)
        var previous = -1f
        for (step in 0..100) {
            val phase = PresentationWarpRules.wavePhase(step / 100f)
            assertTrue("phase must advance", phase >= previous)
            previous = phase
        }
        assertTrue(previous > 6f)

        assertEquals(0f, PresentationWarpRules.cancelProgress(-1L, 9_999L), epsilon)
        assertEquals(1f, PresentationWarpRules.cancelProgress(5_000L, 5_000L + PresentationWarpRules.CANCEL_FADE_MS), epsilon)
        assertEquals(1f, PresentationWarpRules.cancelAlpha(0f), epsilon)
        assertEquals(0f, PresentationWarpRules.cancelAlpha(1f), epsilon)
    }
}
