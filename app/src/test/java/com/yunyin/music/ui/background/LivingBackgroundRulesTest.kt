package com.yunyin.music.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sampling rules for the living background.
 *
 * The composable cannot be unit-tested (it needs a Compose host and a GPU), but everything that
 * decides *where the copies are* is a pure function here. These tests pin the properties that make
 * the motion safe: bounded, periodic, deterministic, never covering less than the screen, and — for
 * the breathing pulse — quiet most of the time and bounded at the peak.
 */
class LivingBackgroundRulesTest {

    private val times = longArrayOf(0L, 1_000L, 17_000L, 60_000L, 91_000L, 118_000L, 600_000L)

    @Test
    fun `rotation stays inside a full turn for every copy and time`() {
        for (index in 0 until CoverOrbitRules.COPY_COUNT) {
            for (time in times) {
                val degrees = CoverOrbitRules.rotationDegrees(index, time)
                assertTrue("copy $index at $time produced $degrees", degrees >= 0f)
                assertTrue("copy $index at $time produced $degrees", degrees < 360f)
            }
        }
    }

    @Test
    fun `copies turn in both directions and start at distinct angles`() {
        val firstStart = CoverOrbitRules.rotationDegrees(0, 0L)
        val secondStart = CoverOrbitRules.rotationDegrees(1, 0L)
        val thirdStart = CoverOrbitRules.rotationDegrees(2, 0L)
        assertTrue("copies must not start stacked", firstStart != secondStart && secondStart != thirdStart)

        // A small time step, kept below any wrap: copy 0 advances, copy 1 retrogrades.
        val step = 5_000L
        val firstMoved = CoverOrbitRules.rotationDegrees(0, step)
        assertTrue("copy 0 must turn forward", firstMoved > firstStart)
        val secondMoved = CoverOrbitRules.rotationDegrees(1, step)
        assertTrue("copy 1 must turn the other way", secondMoved < secondStart)
    }

    @Test
    fun `orbit offsets stay inside the screen fractions`() {
        for (index in 0 until CoverOrbitRules.COPY_COUNT) {
            for (time in times) {
                assertTrue(abs(CoverOrbitRules.orbitX(index, time)) <= 1f)
                assertTrue(abs(CoverOrbitRules.orbitY(index, time)) <= 1f)
            }
        }
    }

    @Test
    fun `orbit moves over time rather than sitting at the origin`() {
        var maxX = 0f
        var maxY = 0f
        for (step in 0..240) {
            val time = step * 1_000L
            maxX = maxOf(maxX, abs(CoverOrbitRules.orbitX(0, time)))
            maxY = maxOf(maxY, abs(CoverOrbitRules.orbitY(0, time)))
        }
        assertTrue("the first copy must actually drift horizontally", maxX > 0.01f)
        assertTrue("the first copy must actually drift vertically", maxY > 0.01f)
    }

    @Test
    fun `negative time is handled without NaN`() {
        for (index in 0 until CoverOrbitRules.COPY_COUNT) {
            val rotation = CoverOrbitRules.rotationDegrees(index, -12_345L)
            val x = CoverOrbitRules.orbitX(index, -12_345L)
            val y = CoverOrbitRules.orbitY(index, -12_345L)
            assertTrue(!rotation.isNaN() && !x.isNaN() && !y.isNaN())
            assertTrue(abs(x) <= 1f && abs(y) <= 1f)
        }
    }

    @Test
    fun `copy scales always cover a rotated screen and stay bounded`() {
        // A square rotated by 45° still fills the screen only at ≥ √2; the copies must stay above
        // that at every sampled time, or a corner of the backdrop would show through.
        val minimumCover = 1.4143f
        for (index in 0 until CoverOrbitRules.COPY_COUNT) {
            for (time in times) {
                val scale = CoverOrbitRules.scale(index, time)
                assertTrue("copy $index at $time scale $scale", scale > minimumCover)
                assertTrue("copy $index at $time scale $scale", scale < 1.9f)
            }
        }
    }

    @Test
    fun `copy opacities are subtle and nonzero`() {
        for (index in 0 until CoverOrbitRules.COPY_COUNT) {
            val opacity = CoverOrbitRules.opacity(index)
            assertTrue("opacity must be visible but subtle", opacity > 0f && opacity <= 0.3f)
        }
    }

    @Test
    fun `out of range indices clamp instead of throwing`() {
        assertEquals(
            CoverOrbitRules.rotationDegrees(0, 5_000L),
            CoverOrbitRules.rotationDegrees(-7, 5_000L),
            0f,
        )
        assertEquals(
            CoverOrbitRules.rotationDegrees(CoverOrbitRules.COPY_COUNT - 1, 5_000L),
            CoverOrbitRules.rotationDegrees(99, 5_000L),
            0f,
        )
    }

    @Test
    fun `crossfade is instantly complete when there is no start time`() {
        assertEquals(1f, CoverCrossfadeRules.progress(-1L, 0L), 0f)
        assertEquals(1f, CoverCrossfadeRules.progress(-1L, 10_000L), 0f)
    }

    @Test
    fun `crossfade eases from zero to one and stays there`() {
        assertEquals(0f, CoverCrossfadeRules.progress(1_000L, 1_000L), 0f)
        assertEquals(1f, CoverCrossfadeRules.progress(1_000L, 1_000L + CoverCrossfadeRules.DURATION_MS), 0f)
        assertEquals(1f, CoverCrossfadeRules.progress(1_000L, 1_000_000L), 0f)

        var previous = -1f
        for (step in 0..20) {
            val now = 1_000L + CoverCrossfadeRules.DURATION_MS * step / 20L
            val progress = CoverCrossfadeRules.progress(1_000L, now)
            assertTrue("crossfade must not go backwards", progress >= previous)
            previous = progress
        }
    }

    @Test
    fun `breathing never leaves its amplitude band`() {
        assertEquals(1f, BreathingRules.scale(0L) - BreathingRules.AMPLITUDE * BreathingRules.wave(0L), 1e-5f)
        for (step in 0..1_000) {
            val time = step * 7L
            val wave = BreathingRules.wave(time)
            assertTrue("wave at $time was $wave", wave in 0f..1f)
            val scale = BreathingRules.scale(time)
            assertTrue("scale at $time was $scale", scale >= 1f && scale <= 1f + BreathingRules.AMPLITUDE + 1e-5f)
        }
    }

    @Test
    fun `breathing is quiet most of the cycle and peaks briefly`() {
        val samples = 300
        var loud = 0
        var quiet = 0
        var peak = 0f
        for (step in 0 until samples) {
            // One primary period (1 / 1.15 s) in milliseconds.
            val time = (step * (1_000L / 1.15f / samples)).toLong()
            val wave = BreathingRules.wave(time)
            peak = maxOf(peak, wave)
            if (wave > 0.5f) loud++
            if (wave < 0.1f) quiet++
        }
        assertTrue("the pulse must peak above 0.8, was $peak", peak > 0.8f)
        assertTrue("the pulse must be quiet for most of the cycle", quiet > samples / 2)
        assertTrue("the pulse must not be loud for a large share of the cycle", loud < samples / 4)
    }

    @Test
    fun `rules are deterministic`() {
        for (index in 0 until CoverOrbitRules.COPY_COUNT) {
            for (time in times) {
                assertEquals(
                    CoverOrbitRules.rotationDegrees(index, time),
                    CoverOrbitRules.rotationDegrees(index, time),
                    0f,
                )
                assertEquals(CoverOrbitRules.orbitX(index, time), CoverOrbitRules.orbitX(index, time), 0f)
                assertEquals(CoverOrbitRules.scale(index, time), CoverOrbitRules.scale(index, time), 0f)
            }
        }
        assertEquals(BreathingRules.wave(12_345L), BreathingRules.wave(12_345L), 0f)
    }

    private fun abs(value: Float): Float = if (value < 0f) -value else value
}
