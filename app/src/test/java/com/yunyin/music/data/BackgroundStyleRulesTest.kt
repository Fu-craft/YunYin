package com.yunyin.music.data

import com.yunyin.music.ui.background.BackgroundStyle
import com.yunyin.music.ui.background.BackgroundStyleRules
import com.yunyin.music.ui.background.OkLabRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the style dispatch, per-track seed determinism, OkLab and palette-transition rules. */
class BackgroundStyleRulesTest {

    @Test
    fun `missing or unknown style falls back to yunyin`() {
        assertEquals(BackgroundStyle.Yunyin, BackgroundStyle.fromKey(null))
        assertEquals(BackgroundStyle.Yunyin, BackgroundStyle.fromKey(""))
        assertEquals(BackgroundStyle.Yunyin, BackgroundStyle.fromKey("unknown"))
        assertEquals(BackgroundStyle.CoverWarp, BackgroundStyle.fromKey("cover_warp"))
        assertEquals(BackgroundStyle.OkLabFlow, BackgroundStyle.fromKey("oklab_flow"))
    }

    @Test
    fun `new styles compose a static frame while yunyin keeps historical off behaviour`() {
        assertTrue(BackgroundStyleRules.shouldCompose(BackgroundStyle.Yunyin, true))
        assertEquals(false, BackgroundStyleRules.shouldCompose(BackgroundStyle.Yunyin, false))
        assertTrue(BackgroundStyleRules.shouldCompose(BackgroundStyle.CoverWarp, false))
        assertTrue(BackgroundStyleRules.shouldCompose(BackgroundStyle.OkLabFlow, false))
        assertTrue(BackgroundStyleRules.shouldRunMotion(true, true, false))
        assertEquals(false, BackgroundStyleRules.shouldRunMotion(false, true, false))
        assertEquals(false, BackgroundStyleRules.shouldRunMotion(true, false, false))
        assertEquals(false, BackgroundStyleRules.shouldRunMotion(true, true, true))
    }

    @Test
    fun `seeds are deterministic per track and differ across tracks`() {
        val a = BackgroundStyleRules.seedForTrack(42L)
        val b = BackgroundStyleRules.seedForTrack(42L)
        val c = BackgroundStyleRules.seedForTrack(43L)
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertTrue(a.flowFrequency in 4.5f..5.5f)
        assertTrue(a.flowAmplitude in 22f..29f)
        assertTrue(a.flowSpeed in -0.85f..0.85f)
        assertTrue(a.flowTilt > -0.3f && a.flowTilt < 0.3f)
    }

    @Test
    fun `oklab round trip is close and white black are known values`() {
        val white = OkLabRules.fromSrgb(0xFFFFFFFF.toInt())
        assertEquals(1f, white.l, 0.002f)
        assertEquals(0f, white.a, 0.002f)
        assertEquals(0f, white.b, 0.002f)
        val black = OkLabRules.fromSrgb(0xFF000000.toInt())
        assertEquals(0f, black.l, 0.001f)
        for (rgb in listOf(0xFF3366CC.toInt(), 0xFFCC6633.toInt(), 0xFF22AA88.toInt())) {
            val round = OkLabRules.toSrgb(OkLabRules.fromSrgb(rgb))
            val dr = kotlin.math.abs((rgb shr 16 and 0xFF) - (round shr 16 and 0xFF))
            val dg = kotlin.math.abs((rgb shr 8 and 0xFF) - (round shr 8 and 0xFF))
            val db = kotlin.math.abs((rgb and 0xFF) - (round and 0xFF))
            assertTrue(dr <= 2 && dg <= 2 && db <= 2)
        }
    }

    @Test
    fun `preblur plan downsamples to 32 and upscales to a preblurred 256 texture`() {
        val rules = com.yunyin.music.ui.background.CoverWarpRules
        assertEquals(256, rules.COVER_TEXTURE_SIZE)
        assertEquals(32, rules.PREBLUR_SOURCE_PX)
        assertEquals(24, rules.PREBLUR_FALLBACK_PX)
        assertEquals(3, rules.PREBLUR_CYCLES)
        assertEquals(5, rules.BLUR_TAP_COUNT)
        assertEquals(2.0f, rules.BLUR_RADIUS_TEXELS, 0.001f)
        val plan = rules.preblurSizes()
        assertEquals(32, plan.first())
        assertEquals(256, plan.last())
        assertEquals(32, plan[0])
        assertEquals(32, plan[1])
        assertEquals(256, plan[2])
        val key = rules.cacheKey(trackId = 42L, coverHash = 7, paletteHash = 9)
        assertEquals(key, rules.cacheKey(42L, 7, 9))
        assertNotEquals(key, rules.cacheKey(43L, 7, 9))
        assertNotEquals(key, rules.cacheKey(42L, 8, 9))
        assertNotEquals(key, rules.cacheKey(42L, 7, 10))
    }

    @Test
    fun `AMLL parameter mapping is encoded exactly`() {
        val rules = com.yunyin.music.ui.background.CoverWarpRules
        assertEquals(0.1f, rules.volume(1f), 0.0001f)
        assertEquals(0f, rules.volume(-1f), 0.0001f)
        assertEquals(1f, rules.textureTime(10_000L), 0.0001f)
        assertEquals(2.2f, rules.angle(1f, 0.1f), 0.0001f) // (time + volume) * 2
        assertEquals(1f, rules.sampledScale(0f), 0.0001f)
        assertEquals(0.8f, rules.sampledScale(0.1f), 0.0001f) // 1 - volume*2
        assertEquals(0.001f, rules.sampledScale(1f), 0.0001f) // clamp
        assertEquals(0.2f, rules.SAMPLE_CENTRE, 0.0001f)
        assertTrue(rules.vignetteMask(0f) in 0.59f..0.61f)
        assertTrue(rules.vignetteMask(0.8f) in 0.99f..1.001f)
        assertTrue(rules.vignetteMask(0.5f) in 0.70f..0.80f)
    }

    @Test
    fun `palette transition is deterministic and hits both endpoints`() {
        val from = intArrayOf(0xFF101820.toInt(), 0xFF336699.toInt())
        val to = intArrayOf(0xFFDDAA22.toInt(), 0xFF80E0C0.toInt())
        assertEquals(from.toList(), OkLabRules.transitionPalette(from, to, 0f).toList())
        assertEquals(to.toList(), OkLabRules.transitionPalette(from, to, 1f).toList())
        val mid = OkLabRules.transitionPalette(from, to, 0.5f)
        assertEquals(mid.toList(), OkLabRules.transitionPalette(from, to, 0.5f).toList())
        assertNotEquals(from.toList(), mid.toList())
        assertNotEquals(to.toList(), mid.toList())
    }
}
