// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.ui.background.BackgroundStyle
import com.yunyin.music.ui.background.BackgroundStyleRules
import com.yunyin.music.ui.background.QpRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Pins the QPlayer style keys, constants, gates and the Apple iOS migration. */
class QpRulesTest {

    @Test
    fun `style keys round trip and apple_ios migrates to yunyin`() {
        assertEquals(BackgroundStyle.QpPixi, BackgroundStyle.fromKey("qp_pixi"))
        assertEquals(BackgroundStyle.QpMesh, BackgroundStyle.fromKey("qp_mesh"))
        assertEquals(BackgroundStyle.QpClassic, BackgroundStyle.fromKey("qp_classic"))
        assertEquals(BackgroundStyle.Yunyin, BackgroundStyle.fromKey("apple_ios"))
        assertEquals(BackgroundStyle.OkLabFlow, BackgroundStyle.fromKey("oklab_flow"))
        assertEquals(BackgroundStyle.Yunyin, BackgroundStyle.default)
        assertEquals("qp_pixi", BackgroundStyle.QpPixi.storageKey)
        assertEquals("qp_mesh", BackgroundStyle.QpMesh.storageKey)
        assertEquals("qp_classic", BackgroundStyle.QpClassic.storageKey)
    }

    @Test
    fun `shared QPlayer texture and blur constants match`() {
        assertEquals(32, QpRules.SOURCE_TEX_SIZE)
        assertEquals(256, QpRules.TEXTURE_TEX_SIZE)
        assertEquals(2, QpRules.BLUR_RADIUS)
        assertEquals(4, QpRules.BLUR_PASSES)
        assertEquals(0.6f, QpRules.FADE_SECONDS, 0f)
        assertEquals(72f, QpRules.CHROMA_CAP, 0f)
        assertEquals(108f, QpRules.LUMA_CAP, 0f)
        assertEquals(60, BackgroundStyleRules.FRAME_RATE_LIMIT)
        assertEquals(16_666_666L, BackgroundStyleRules.FRAME_INTERVAL_NS)
        assertEquals(0.2f, QpRules.MESH_ROTATION_SPEED, 0f)
        assertEquals(0.3f, QpRules.MESH_CENTER, 0f)
        assertEquals(256, QpRules.MESH_TEXTURE_SIZE)
    }

    @Test
    fun `Pixi sprite geometry and colour caps match QPlayer`() {
        assertEquals(1.41421356f, QpRules.PIXI_MAIN_SCALE, 0f)
        assertEquals(0.8f, QpRules.PIXI_MID_SCALE, 0f)
        assertEquals(0.5f, QpRules.PIXI_INNER_SCALE, 0f)
        assertEquals(0.25f, QpRules.PIXI_CORE_SCALE, 0f)
        assertEquals(0.115f, QpRules.PIXI_FEATHER_FRACTION, 0f)
        assertEquals(0.28f, QpRules.PIXI_CHROMA_CAP, 0f)
        assertEquals(0.42f, QpRules.PIXI_LUMA_CAP, 0f)
        assertEquals(0.25f, QpRules.PIXI_TRAVEL_FRACTION, 0f)
    }

    @Test
    fun `Classic warp weights and rotation match QPlayer`() {
        assertEquals(0.10f, QpRules.CLASSIC_WARP_WEIGHT_1, 0f)
        assertEquals(0.06f, QpRules.CLASSIC_WARP_WEIGHT_2, 0f)
        assertEquals(0.18f, QpRules.CLASSIC_ROTATION_SPEED, 0f)
        assertEquals(0.005f, QpRules.CLASSIC_UV_MIN, 0f)
        assertEquals(0.995f, QpRules.CLASSIC_UV_MAX, 0f)
        assertEquals(0.4f, QpRules.CLASSIC_VIGNETTE_GAIN, 0f)
    }

    @Test
    fun `angles are deterministic and in one turn`() {
        val a = QpRules.angles(42L)
        val b = QpRules.angles(42L)
        assertArrayEquals(a, b, 0f)
        assertEquals(4, a.size)
        assertTrue(a.all { it >= 0f && it < PI.toFloat() * 2f })
        assertTrue(QpRules.bend(42L) == 1f || QpRules.bend(42L) == -1f)
    }

    @Test
    fun `static and audio gates and fallback decision`() {
        assertTrue(QpRules.shouldAnimate(true, true, false))
        assertFalse(QpRules.shouldAnimate(false, true, false))
        assertFalse(QpRules.shouldAnimate(true, false, false))
        assertFalse(QpRules.shouldAnimate(true, true, true))
        assertEquals(0f, QpRules.audioTerm(), 0f)
        assertTrue(QpRules.shouldFallback(false))
        assertFalse(QpRules.shouldFallback(true))
    }

    @Test
    fun `QPlayer styles compose a static frame when dynamic is off`() {
        assertTrue(BackgroundStyleRules.shouldCompose(BackgroundStyle.QpPixi, false))
        assertTrue(BackgroundStyleRules.shouldCompose(BackgroundStyle.QpMesh, false))
        assertTrue(BackgroundStyleRules.shouldCompose(BackgroundStyle.QpClassic, false))
        assertTrue(BackgroundStyleRules.shouldCompose(BackgroundStyle.OkLabFlow, false))
        assertFalse(BackgroundStyleRules.shouldCompose(BackgroundStyle.Yunyin, false))
    }
}
