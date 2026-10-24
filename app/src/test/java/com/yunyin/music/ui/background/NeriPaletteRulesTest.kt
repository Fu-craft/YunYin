// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.ui.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure parts of the palette builder ported from NeriPlayer.
 *
 * The role-based softening itself uses Android's HSL helpers, which are not runnable on a plain JVM,
 * so those functions are covered by their pure building blocks here: luma, hue distance, the
 * lightness-span chroma maths, the shader tone-offset formula and the direct-construction path.
 */
class NeriPaletteRulesTest {

    private val epsilon = 1e-5f

    @Test
    fun `luma is Rec 709 and bounded`() {
        assertEquals(0f, DynamicBackgroundPalette.colorLuma(0xFF000000.toInt()), epsilon)
        assertEquals(1f, DynamicBackgroundPalette.colorLuma(0xFFFFFFFF.toInt()), epsilon)
        assertEquals(0.7152f, DynamicBackgroundPalette.colorLuma(0xFF00FF00.toInt()), epsilon)
        assertEquals(0.0722f, DynamicBackgroundPalette.colorLuma(0xFF0000FF.toInt()), epsilon)
        assertTrue(DynamicBackgroundPalette.colorLuma(0xFF336699.toInt()) in 0f..1f)
    }

    @Test
    fun `hue distance is symmetric and wraps`() {
        assertEquals(0f, DynamicBackgroundPalette.hueDistanceDegrees(10f, 10f), epsilon)
        assertEquals(20f, DynamicBackgroundPalette.hueDistanceDegrees(10f, 30f), epsilon)
        assertEquals(20f, DynamicBackgroundPalette.hueDistanceDegrees(30f, 10f), epsilon)
        assertEquals(0f, DynamicBackgroundPalette.hueDistanceDegrees(0f, 360f), epsilon)
        assertEquals(180f, DynamicBackgroundPalette.hueDistanceDegrees(0f, 180f), epsilon)
        assertEquals(10f, DynamicBackgroundPalette.hueDistanceDegrees(5f, 355f), epsilon)
    }

    @Test
    fun `lightness span is zero at the extremes and one at mid grey`() {
        assertEquals(0f, DynamicBackgroundPalette.hslLightnessSpan(0f), epsilon)
        assertEquals(0f, DynamicBackgroundPalette.hslLightnessSpan(1f), epsilon)
        assertEquals(1f, DynamicBackgroundPalette.hslLightnessSpan(0.5f), epsilon)
        assertEquals(0.5f, DynamicBackgroundPalette.hslLightnessSpan(0.25f), epsilon)
    }

    @Test
    fun `shader light offset follows the cover luminance and is clamped`() {
        // Dark artwork in a dark theme lifts the field; bright artwork lowers it.
        assertTrue(DynamicBackgroundPalette.shaderLightOffset(0.1f, true) <
            DynamicBackgroundPalette.shaderLightOffset(0.9f, true))
        assertTrue(DynamicBackgroundPalette.shaderLightOffset(0.9f, false) <
            DynamicBackgroundPalette.shaderLightOffset(0.1f, false))
        for (luma in 0..100) {
            val value = DynamicBackgroundPalette.shaderLightOffset(luma / 100f, true)
            assertTrue("offset for $luma was $value", value in -0.12f..0.12f)
        }
    }

    @Test
    fun `direct construction keeps five colours and sensible offsets`() {
        val colors = intArrayOf(
            0xFF334455.toInt(),
            0xFFAA4455.toInt(),
            0xFF8899AA.toInt(),
            0xFF223344.toInt(),
            0xFF556677.toInt(),
        )
        val palette = DynamicBackgroundPalette.fromColors(colors, isDark = true)
        assertEquals(5, palette.colors.size)
        assertTrue(palette.saturateOffset in 0f..1f)
        assertTrue(palette.lightOffset in -0.12f..0.12f)
        assertEquals(DynamicBackgroundPalette.colorLuma(colors.first()), palette.baseLuminance, epsilon)
        assertEquals(colors[1], palette.accent)
    }

    @Test
    fun `shader array is five rgba quads in range`() {
        val colors = intArrayOf(
            0xFF102030.toInt(),
            0xFF405060.toInt(),
            0xFF708090.toInt(),
            0xFFA0B0C0.toInt(),
            0xFFD0E0F0.toInt(),
        )
        val palette = DynamicBackgroundPalette.fromColors(colors, isDark = false)
        val shader = palette.toShaderColors()
        assertEquals(20, shader.size)
        for (value in shader) {
            assertTrue("shader channel out of range: $value", value in 0f..1f)
        }
        // Alpha of every colour is opaque.
        for (index in 0 until 5) {
            assertEquals(1f, shader[index * 4 + 3], epsilon)
        }
    }

    @Test
    fun `pickPaletteColor skips transparent and missing candidates`() {
        assertEquals(
            0xFF112233.toInt(),
            DynamicBackgroundPalette.pickPaletteColor(null, 0, 0xFF112233.toInt()),
        )
        assertEquals(
            0xFF808080.toInt(),
            DynamicBackgroundPalette.pickPaletteColor(null, 0),
        )
        assertEquals(
            0xFF445566.toInt(),
            DynamicBackgroundPalette.pickPaletteColor(0xFF445566.toInt(), 0xFF778899.toInt()),
        )
    }
}
