// SPDX-License-Identifier: AGPL-3.0-only
// Ported from CurrentMusicX (GPL-3.0-only), core/media/PlaybackEqualizer.kt.
// Logic unchanged: attach only to this app's own audio session (never session 0 / the global mix),
// quantise to the platform's millibel grid, and degrade to "unavailable" instead of throwing.

package com.yunyin.music.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import kotlin.math.roundToInt

data class EqualizerRuntime(
    val supported: Boolean,
    val sessionId: Int = 0,
    val active: Boolean = false,
    val error: String? = null,
)

/** Own playback session only. Never attaches to the global output mix (session 0). Main-thread owned. */
class PlaybackEqualizer(private val log: (String) -> Unit = {}) : java.io.Closeable {
    private val supported = runCatching {
        AudioEffect.queryEffects().orEmpty().any { it.type == AudioEffect.EFFECT_TYPE_EQUALIZER }
    }.getOrDefault(false)

    @Volatile
    var runtime: EqualizerRuntime = EqualizerRuntime(supported)
        private set

    private var effect: Equalizer? = null
    private var sessionId = 0
    private var desired = EqualizerSettings()
    private var applied = emptyList<Int>()

    fun configure(settings: EqualizerSettings) {
        desired = settings.normalized()
        if (!desired.enabled) {
            releaseEffect()
            runtime = EqualizerRuntime(supported, sessionId)
            return
        }
        if (runtime.error != null) return
        if (effect == null && sessionId > 0) attach(sessionId) else apply()
    }

    fun attach(id: Int) {
        if (id <= 0) {
            close()
            return
        }
        if (id == sessionId && effect != null) {
            apply()
            return
        }
        releaseEffect()
        sessionId = id
        if (!supported) {
            runtime = EqualizerRuntime(false)
            return
        }
        if (!desired.enabled) {
            runtime = EqualizerRuntime(true, id)
            return
        }
        try {
            effect = Equalizer(0, id)
            apply()
        } catch (e: Exception) {
            unavailable(e)
        }
    }

    private fun apply() {
        val native = effect ?: return
        try {
            check(native.hasControl())
            val range = native.bandLevelRange
            val values = List(native.numberOfBands.toInt()) { band ->
                // System engines commonly quantize millibel parameters to whole dB.
                ((desired.gainAt(native.getCenterFreq(band.toShort()) / 1000f) / 100.0).roundToInt() * 100)
                    .coerceIn(range[0].toInt(), range[1].toInt())
            }
            values.forEachIndexed { band, gain ->
                if (applied.getOrNull(band) != gain) native.setBandLevel(band.toShort(), gain.toShort())
            }
            applied = values
            if (native.enabled != desired.enabled) check(native.setEnabled(desired.enabled) == AudioEffect.SUCCESS)
            runtime = EqualizerRuntime(supported = true, sessionId = sessionId, active = native.enabled)
        } catch (e: Exception) {
            unavailable(e)
        }
    }

    private fun unavailable(e: Exception) {
        releaseEffect()
        runtime = EqualizerRuntime(supported, sessionId, error = "均衡器暂不可用，请关闭后重新开启")
        log("Equalizer unavailable: ${e.javaClass.simpleName}")
    }

    private fun releaseEffect() {
        runCatching { effect?.release() }
        effect = null
        applied = emptyList()
    }

    internal fun actualLevels(): List<Pair<Float, Int>> = effect?.let { native ->
        List(native.numberOfBands.toInt()) { band ->
            native.getCenterFreq(band.toShort()) / 1000f to native.getBandLevel(band.toShort()).toInt()
        }
    }.orEmpty()

    override fun close() {
        releaseEffect()
        sessionId = 0
        runtime = EqualizerRuntime(supported)
    }
}
