package com.yunyin.music.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin mirror of the backdrop material shader.
 *
 * These do not "test the shader" — a JVM unit test cannot run AGSL. They pin the *arithmetic* the
 * shader is built on, which is where a tuning change (a floor of 0.5 instead of 0.05, a scrim that
 * darkens instead of lifting) would silently produce a wrong-looking backdrop: the shader and this
 * object must stay line-for-line equivalent, so a change here that violates these properties is a
 * change the shader should not have either.
 */
class BackdropMaterialRulesTest {

    private val epsilon = 1e-6f

    @Test
    fun `black floor fixes both endpoints`() {
        assertEquals(BackdropMaterialRules.BLACK_FLOOR, BackdropMaterialRules.applyBlackFloor(0f), epsilon)
        assertEquals(1f, BackdropMaterialRules.applyBlackFloor(1f), epsilon)
    }

    @Test
    fun `black floor lifts every tone below white and is monotone`() {
        var previous = -1f
        for (step in 0..100) {
            val value = step / 100f
            val mapped = BackdropMaterialRules.applyBlackFloor(value)
            if (value < 1f) {
                assertTrue("floor must lift $value (mapped $mapped)", mapped > value)
            }
            assertTrue("floor must be monotone at $value", mapped >= previous)
            previous = mapped
        }
    }

    @Test
    fun `black floor keeps the darkest tones visibly above page black`() {
        // The failure this fixes: the field collapsed into the page black and the frost edge
        // disappeared. 5 % of the range is the smallest lift that still separates them.
        assertTrue(BackdropMaterialRules.applyBlackFloor(0f) >= 0.04f)
        assertTrue(BackdropMaterialRules.applyBlackFloor(0f) <= 0.06f)
    }

    @Test
    fun `saturation leaves greys unchanged`() {
        for (step in 0..10) {
            val grey = step / 10f
            val luma = BackdropMaterialRules.luminance(grey, grey, grey)
            assertEquals(grey, BackdropMaterialRules.saturate(grey, luma), epsilon)
        }
    }

    @Test
    fun `saturation pushes channels away from the luminance`() {
        val luma = BackdropMaterialRules.luminance(0.8f, 0.2f, 0.2f)
        val red = BackdropMaterialRules.saturate(0.8f, luma)
        val green = BackdropMaterialRules.saturate(0.2f, luma)
        assertTrue("a saturated lift must increase a channel above luma", red > 0.8f)
        assertTrue("a saturated lift must push a channel below luma down", green < 0.2f)
    }

    @Test
    fun `luminance weights are Rec 709 and sum to one`() {
        val sum = BackdropMaterialRules.LUMA_R +
            BackdropMaterialRules.LUMA_G +
            BackdropMaterialRules.LUMA_B
        assertEquals(1f, sum, epsilon)
        assertEquals(1f, BackdropMaterialRules.luminance(1f, 1f, 1f), epsilon)
        assertEquals(0f, BackdropMaterialRules.luminance(0f, 0f, 0f), epsilon)
    }

    @Test
    fun `scrim is bounded and darkens bright detailed pixels more than calm ones`() {
        val dark = BackdropMaterialRules.scrimFactor(luma = 0.05f, detail = 0f)
        val brightCalm = BackdropMaterialRules.scrimFactor(luma = 0.9f, detail = 0f)
        val brightBusy = BackdropMaterialRules.scrimFactor(luma = 0.9f, detail = 1f)

        assertEquals("dark ramps must be left alone", 1f, dark, epsilon)
        assertTrue("a bright calm patch is darkened only a little", brightCalm < 1f && brightCalm > 0.9f)
        assertTrue("a bright busy patch is darkened the most", brightBusy < brightCalm)
        assertTrue(
            "the scrim can never exceed its configured maximum",
            brightBusy >= 1f - BackdropMaterialRules.SCRIM - epsilon,
        )
    }

    @Test
    fun `detail estimate rises with the luminance step and is clamped`() {
        assertEquals(0f, BackdropMaterialRules.detailOf(0.4f, 0.4f), epsilon)
        assertTrue(BackdropMaterialRules.detailOf(0.5f, 0.4f) > 0f)
        assertEquals(1f, BackdropMaterialRules.detailOf(1f, 0f), epsilon)
    }

    @Test
    fun `dither is symmetric and at most half a bit`() {
        val halfBit = 0.5f / 255f
        assertEquals(-halfBit, BackdropMaterialRules.ditherOffset(0f), epsilon)
        assertEquals(halfBit, BackdropMaterialRules.ditherOffset(1f), epsilon)
        assertEquals(0f, BackdropMaterialRules.ditherOffset(0.5f), epsilon)
        for (step in 0..100) {
            assertTrue(abs(BackdropMaterialRules.ditherOffset(step / 100f)) <= halfBit + epsilon)
        }
    }

    @Test
    fun `smoothstep endpoints and clamping`() {
        assertEquals(0f, BackdropMaterialRules.smoothstep(0.2f, 0.85f, 0.1f), epsilon)
        assertEquals(1f, BackdropMaterialRules.smoothstep(0.2f, 0.85f, 0.9f), epsilon)
        assertEquals(0.5f, BackdropMaterialRules.smoothstep(0f, 1f, 0.5f), epsilon)
    }

    private fun abs(value: Float): Float = if (value < 0f) -value else value
}
