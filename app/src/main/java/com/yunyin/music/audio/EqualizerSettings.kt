// SPDX-License-Identifier: AGPL-3.0-only
// Ported from CurrentMusicX (GPL-3.0-only), data/settings/EqualizerSettings.kt.
// Same bands, same presets, same millibel quantisation; the DataStore repository is replaced by the
// app's SharedPreferences-backed SettingsStore, which is the only storage this project has.

package com.yunyin.music.audio

import kotlin.math.ln
import kotlin.math.roundToInt

enum class EqualizerPreset(val label: String, val gains: List<Int>) {
    FLAT("平直", listOf(0, 0, 0, 0, 0)),
    BASS("低音", listOf(600, 400, 0, -100, -200)),
    VOCAL("人声", listOf(-200, -100, 300, 400, 100)),
    ROCK("摇滚", listOf(500, 200, -200, 300, 500)),
    POP("流行", listOf(-100, 200, 400, 200, -100)),
    JAZZ("爵士", listOf(300, 100, -100, 200, 400)),
    CUSTOM("自定义", emptyList()),
}

/**
 * The user's equaliser curve, in millibels, on the five bands the presets are written for.
 *
 * Device band layouts vary, so the stored curve is *interpolated* onto whatever the platform effect
 * reports ([gainAt]) instead of being pushed band-by-band by index.
 */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    val gains: List<Int> = EqualizerPreset.FLAT.gains,
) {
    fun normalized(): EqualizerSettings = copy(
        gains = FREQUENCIES.indices.map { index ->
            ((gains.getOrElse(index) { 0 }.coerceIn(-1200, 1200) / 100.0).roundToInt() * 100)
        },
    )

    fun withBand(index: Int, gain: Int): EqualizerSettings {
        require(index in FREQUENCIES.indices)
        return normalized().copy(
            preset = EqualizerPreset.CUSTOM,
            gains = normalized().gains.toMutableList().also { it[index] = gain },
        ).normalized()
    }

    /** Interpolate our stored curve on a logarithmic frequency axis. */
    fun gainAt(frequencyHz: Float): Int {
        val values = normalized().gains
        if (!frequencyHz.isFinite() || frequencyHz <= FREQUENCIES.first()) return values.first()
        if (frequencyHz >= FREQUENCIES.last()) return values.last()
        val upper = FREQUENCIES.indexOfFirst { it >= frequencyHz }
        val lower = upper - 1
        val fraction = ln(frequencyHz / FREQUENCIES[lower]) / ln(FREQUENCIES[upper].toFloat() / FREQUENCIES[lower])
        return (values[lower] + (values[upper] - values[lower]) * fraction).roundToInt()
    }

    companion object {
        val FREQUENCIES = listOf(60, 230, 910, 3600, 14000)
    }
}
