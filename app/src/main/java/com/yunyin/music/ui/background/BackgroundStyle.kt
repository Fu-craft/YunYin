package com.yunyin.music.ui.background

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The now-playing background family. 云音 is the existing HyperBackground; the other two are
 * clean-room, independent implementations inspired by AMLL's background *ideas* only (AGPL-3.0
 * reference; no code, shader source, assets or constants copied).
 */
enum class BackgroundStyle(
    val storageKey: String,
    val label: String,
    val description: String,
) {
    Yunyin("yunyin", "云音（默认）", "五色流体，随音频轻轻呼吸"),
    CoverWarp("cover_warp", "封面流动", "封面本身被缓慢扭曲、缩放"),
    OkLabFlow("oklab_flow", "色阶流动", "封面取四色，在 OkLab 中流动混合"),
    ;

    companion object {
        val default: BackgroundStyle = Yunyin
        fun fromKey(key: String?): BackgroundStyle =
            entries.firstOrNull { it.storageKey == key } ?: default
    }
}

/** Per-track composition params; deterministic in the track id, so a song always looks the same. */
data class BackgroundSeed(
    val rotation: Float,
    val coverWarpPhase: Float,
    val coverWarpFrequency: Float,
    val flowFrequency: Float,
    val flowAmplitude: Float,
    val flowSpeed: Float,
    val flowTilt: Float,
    val angleJitter: Float,
)

/** Pure decisions for style dispatch, motion/audio gating and seeded composition. */
object BackgroundStyleRules {

    /** 云音 keeps its historical behaviour when 动态背景 is off; the new styles still paint a static frame. */
    fun shouldCompose(style: BackgroundStyle, dynamicEnabled: Boolean): Boolean =
        dynamicEnabled || style != BackgroundStyle.Yunyin

    fun shouldRunMotion(dynamicEnabled: Boolean, animatorsOn: Boolean, powerSave: Boolean): Boolean =
        dynamicEnabled && animatorsOn && !powerSave

    fun shouldUseAudio(audioSetting: Boolean, animatorsOn: Boolean, powerSave: Boolean): Boolean =
        audioSetting && animatorsOn && !powerSave

    private fun mix(value: Long): Long {
        var z = value + 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    fun seedForTrack(trackId: Long): BackgroundSeed {
        val random = java.util.Random(mix(trackId))
        fun range(min: Float, max: Float) = min + random.nextFloat() * (max - min)
        return BackgroundSeed(
            rotation = random.nextFloat() * (2f * PI.toFloat()),
            coverWarpPhase = random.nextFloat() * (2f * PI.toFloat()),
            coverWarpFrequency = range(1.15f, 1.85f),
            flowFrequency = range(4.5f, 5.5f),
            flowAmplitude = range(22f, 29f),
            flowSpeed = range(0.65f, 0.85f) * if (random.nextBoolean()) 1f else -1f,
            flowTilt = (-5f + (random.nextFloat() - 0.5f) * 12f) * (PI.toFloat() / 180f),
            angleJitter = (random.nextFloat() - 0.5f) * 0.3f,
        )
    }

    /** CPU-side, deterministic seed usage for the cover shader's rotation axis. */
    fun rotationFor(seed: BackgroundSeed, timeSeconds: Float, volume: Float): Float =
        seed.rotation + timeSeconds * CoverWarpRules.ROTATION_RATE + volume * CoverWarpRules.VOLUME_ROTATION

    fun sineWarp(seed: BackgroundSeed, uvX: Float, uvY: Float, timeSeconds: Float): Pair<Float, Float> {
        val x = sin(uvY * seed.coverWarpFrequency + timeSeconds * 0.7f + seed.coverWarpPhase)
        val y = cos(uvX * seed.coverWarpFrequency * 1.37f - timeSeconds * 0.53f)
        return (x * CoverWarpRules.WARP_X) to (y * CoverWarpRules.WARP_Y)
    }
}

/**
 * Tuning exposed for tests and shared by the Kotlin/GLSL sides. The shader keeps the same
 * constants (AGSL cannot import Kotlin); changing one requires changing both and the tests
 * below will keep the numbers honest.
 */
object CoverWarpRules {
    /** Texture upload size. 128 px leaves enough texels for a 13-tap blur to hide the grid. */
    const val COVER_TEXTURE_SIZE = 128

    /** Fixed 13-tap gaussian-ish blur: centre + 4 axis + 4 diagonal + 4 outer taps. */
    const val BLUR_TAP_COUNT = 13

    /** Outer tap offset in texels; the effective blur radius is ~2.6 texels at 128 px. */
    const val BLUR_RADIUS_TEXELS = 2.6f

    /** Slow rotation: 0.012 rad/s instead of the old 0.035, so the cover drifts rather than spins. */
    const val ROTATION_RATE = 0.012f

    /** Audio can add at most 0.6 rad of rotation; it no longer whips the texture around. */
    const val VOLUME_ROTATION = 0.6f

    /** Gentle volume zoom: 1.0 → 0.65 at full level, not the old 1.0 → 0.55 at a tiny volume. */
    const val VOLUME_ZOOM = 0.35f

    /** Gentle sine warp amplitudes, in UV units. */
    const val WARP_X = 0.006f
    const val WARP_Y = 0.004f

    fun zoom(volume: Float): Float =
        (1f - volume.coerceIn(0f, 1f) * VOLUME_ZOOM).coerceIn(0.65f, 1f)
}
