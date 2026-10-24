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

    fun sineWarp(seed: BackgroundSeed, uvX: Float, uvY: Float, timeSeconds: Float): Pair<Float, Float> {
        val x = sin(uvY * seed.coverWarpFrequency + timeSeconds * 0.7f + seed.coverWarpPhase)
        val y = cos(uvX * seed.coverWarpFrequency * 1.37f - timeSeconds * 0.53f)
        return (x * CoverWarpRules.WARP_X) to (y * CoverWarpRules.WARP_Y)
    }
}

/**
 * Cover-warp tuning and the CPU pre-blur pipeline spec. These values are also mirrored in
 * `cover_warp_background.glsl`; the pure functions below are what the tests pin.
 */
object CoverWarpRules {
    /** Uploaded texture size. 256 px of *pre-blurred* data, so point sampling cannot show blocks. */
    const val COVER_TEXTURE_SIZE = 256

    /** Source/downsample size per pre-blur cycle. */
    const val PREBLUR_SOURCE_PX = 32

    /** Fallback source size if a device still shows structure at 32 px. */
    const val PREBLUR_FALLBACK_PX = 24

    /** Number of down(32)->up(256) cycles; each cycle is a cheap approximate box-gaussian. */
    const val PREBLUR_CYCLES = 3

    /** The in-shader blur is now a mild extra: centre + 4 axis taps. */
    const val BLUR_TAP_COUNT = 5
    const val BLUR_RADIUS_TEXELS = 2.0f

    /** AMLL parameter mapping: angle = (uTime + uVolume) * 2. */
    const val ANGLE_MULTIPLIER = 2.0f

    /** uVolume = low-frequency level * 1/10 (AMLL's lowFreqVolume / 10). */
    const val VOLUME_SCALE = 0.1f

    /** volumeEffect = uVolume * 2; sampled scale = max(0.001, 1 - volumeEffect). */
    const val VOLUME_EFFECT = 2.0f
    const val MIN_SCALE = 0.001f

    /** AMLL samples around vec2(0.2), not the visual centre. */
    const val SAMPLE_CENTRE = 0.2f

    /** Gentle seed warp after the AMLL rotation/scale. */
    const val WARP_X = 0.004f
    const val WARP_Y = 0.003f

    /** AMLL time basis: frameTime milliseconds / 10000. */
    fun textureTime(frameTimeMs: Long): Float = frameTimeMs / 10_000f

    fun volume(lowFrequencyLevel: Float): Float = lowFrequencyLevel.coerceIn(0f, 1f) * VOLUME_SCALE

    fun angle(time: Float, volume: Float): Float = (time + volume) * ANGLE_MULTIPLIER

    fun sampledScale(volume: Float): Float =
        maxOf(MIN_SCALE, 1f - volume * VOLUME_EFFECT)

    /** Vignette mask bounds, replicated for the pure test. */
    fun vignetteMask(distanceFromCentre: Float): Float {
        val t = ((distanceFromCentre - 0.8f) / (0.3f - 0.8f)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return 0.6f + (1f - smooth) * 0.4f
    }

    /** Pre-blur pass sizes in order: down to 32, then [PREBLUR_CYCLES] up/down cycles, then 256. */
    fun preblurSizes(): List<Int> {
        val sizes = ArrayList<Int>(PREBLUR_CYCLES * 2 + 2)
        sizes += PREBLUR_SOURCE_PX
        repeat(PREBLUR_CYCLES) {
            sizes += PREBLUR_SOURCE_PX
            sizes += COVER_TEXTURE_SIZE
        }
        if (sizes.last() != COVER_TEXTURE_SIZE) sizes += COVER_TEXTURE_SIZE
        return sizes
    }

    /** Rebuild only when the track, cover or palette changes; stable for the same inputs. */
    data class CacheKey(val trackId: Long, val coverHash: Int, val paletteHash: Int)

    fun cacheKey(trackId: Long, coverHash: Int, paletteHash: Int): CacheKey =
        CacheKey(trackId, coverHash, paletteHash)
}
