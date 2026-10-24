package com.yunyin.music.ui.background

import kotlin.math.PI

/**
 * The now-playing background family. 云音 is the existing HyperBackground; 色阶流动 is a
 * clean-room, independent implementation inspired by AMLL's background *ideas* only (AGPL-3.0
 * reference; no code, shader source, assets or constants copied).
 */
enum class BackgroundStyle(
    val storageKey: String,
    val label: String,
    val description: String,
) {
    Yunyin("yunyin", "云音（默认）", "五色流体，随音频轻轻呼吸"),
    OkLabFlow("oklab", "色阶流动", "封面取四色，在 OkLab 中流动混合"),
    ;

    companion object {
        val default: BackgroundStyle = Yunyin
        fun fromKey(key: String?): BackgroundStyle = when (key) {
            "oklab", "oklab_flow" -> OkLabFlow // "oklab_flow" is the v4.32.0–v4.32.2 legacy key
            else -> default // "yunyin", and any removed/unrecognised value (incl. cover styles)
        }
    }
}

/** Per-track composition params; deterministic in the track id, so a song always looks the same. */
data class BackgroundSeed(
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
            flowFrequency = range(4.5f, 5.5f),
            flowAmplitude = range(22f, 29f),
            flowSpeed = range(0.65f, 0.85f) * if (random.nextBoolean()) 1f else -1f,
            flowTilt = (-5f + (random.nextFloat() - 0.5f) * 12f) * (PI.toFloat() / 180f),
            angleJitter = (random.nextFloat() - 0.5f) * 0.3f,
        )
    }

}

