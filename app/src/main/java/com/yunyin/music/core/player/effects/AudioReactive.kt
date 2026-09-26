package com.yunyin.music.core.player.effects

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Derives a level and a beat impulse from the audio being played.
 *
 * The PCM is tapped straight out of ExoPlayer's audio pipeline (see
 * [com.yunyin.music.playback.ReactiveRenderersFactory]) rather than captured from the device, so
 * no recording permission is involved and the analyser only ever sees our own output.
 *
 * Pipeline, mirroring NeriPlayer's `AudioReactive`:
 *  1. RMS of the tapped buffer, scaled by the current volume, clamped to 0..1.
 *  2. Two exponential moving averages — a fast one (attack) and a slow one (floor).
 *  3. A beat is the fast average exceeding the slow one by a multiple of an adaptive noise
 *     floor, rate-limited by a minimum gap.
 *  4. The level is exposed perceptually (`sqrt`) with a small lift on a beat.
 *
 * Consumers smooth these further; the values here are the raw, immediate readings.
 */
object AudioReactive {

    private val _level = MutableStateFlow(0f)

    /** Perceptual loudness, 0..1. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _beat = MutableStateFlow(0f)

    /** Beat impulse that decays to zero, 0..1. */
    val beat: StateFlow<Float> = _beat.asStateFlow()

    /** When false the analyser ignores buffers and reports silence. */
    @Volatile
    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                _level.value = 0f
                _beat.value = 0f
            }
        }

    // Analysis state, only touched from the audio thread.
    private var emaFast = 0.0
    private var emaSlow = 0.0
    private var noiseEma = 0.0
    private var lastBeatNs = 0L
    private var lastBeatUpdateNs = 0L

    private var sampleRate = 44_100
    private var channelCount = 2
    private var encoding = C.ENCODING_PCM_16BIT

    /** The sink handed to the audio pipeline. */
    val teeSink: TeeAudioProcessor.AudioBufferSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channels: Int, audioEncoding: Int) {
            sampleRate = sampleRateHz.coerceAtLeast(1)
            channelCount = channels.coerceAtLeast(1)
            encoding = audioEncoding
            emaFast = 0.0
            emaSlow = 0.0
            noiseEma = 0.0
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!enabled) return
            val rms = readRms(buffer) ?: return
            analyse(rms)
        }
    }

    // ------------------------------------------------------------------ analysis

    private fun analyse(loudness: Double) {
        val nowNs = System.nanoTime()

        // Asymmetric envelope: quick attack, slow release.
        emaFast = ALPHA_FAST * loudness + (1 - ALPHA_FAST) * emaFast
        emaSlow = ALPHA_SLOW * loudness + (1 - ALPHA_SLOW) * emaSlow

        val delta = (emaFast - emaSlow).coerceAtLeast(0.0)
        noiseEma = 0.02 * delta + 0.98 * noiseEma
        val threshold = 3.0 * (noiseEma + EPSILON)

        val isBeat = delta > threshold && nowNs - lastBeatNs > MIN_BEAT_GAP_NS
        if (isBeat) {
            lastBeatNs = nowNs
            lastBeatUpdateNs = nowNs
            _beat.value = 1f
        } else {
            decayBeat(nowNs)
        }

        val perceptual = kotlin.math.sqrt(loudness).toFloat()
        _level.value = if (isBeat) {
            maxOf(perceptual, minOf(1f, perceptual + 0.08f))
        } else {
            perceptual
        }
    }

    /** Beat decays by [BEAT_DECAY_PER_REFERENCE] per 16.67 ms of elapsed time. */
    private fun decayBeat(nowNs: Long) {
        if (lastBeatUpdateNs == 0L) return
        val elapsed = nowNs - lastBeatUpdateNs
        if (elapsed <= 0L) return
        val references = elapsed.toDouble() / BEAT_DECAY_REFERENCE_NS
        lastBeatUpdateNs = nowNs
        _beat.value = (_beat.value * Math.pow(BEAT_DECAY_PER_REFERENCE, references)).toFloat()
    }

    /**
     * RMS of [buffer] across all channels, 0..1.
     *
     * Returns null for encodings that are not linear PCM.
     */
    private fun readRms(buffer: ByteBuffer): Double? {
        val bytes = buffer.remaining()
        if (bytes <= 0) return null
        val samples = buffer.duplicate().order(ByteOrder.nativeOrder())

        var sum = 0.0
        var count = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val n = bytes / 2
                for (i in 0 until n) {
                    val v = samples.short.toDouble() / Short.MAX_VALUE
                    sum += v * v
                    count++
                }
            }

            C.ENCODING_PCM_8BIT -> {
                for (i in 0 until bytes) {
                    val v = ((samples.get().toInt() and 0xFF) - 128) / 128.0
                    sum += v * v
                    count++
                }
            }

            C.ENCODING_PCM_24BIT -> {
                val n = bytes / 3
                for (i in 0 until n) {
                    val b0 = samples.get().toInt() and 0xFF
                    val b1 = samples.get().toInt() and 0xFF
                    val b2 = samples.get().toInt() and 0xFF
                    var v = (b2 shl 16) or (b1 shl 8) or b0
                    if (v and 0x800000 != 0) v = v or -0x1000000
                    val d = v / 8_388_608.0
                    sum += d * d
                    count++
                }
            }

            C.ENCODING_PCM_32BIT -> {
                val n = bytes / 4
                for (i in 0 until n) {
                    val v = samples.int.toDouble() / Int.MAX_VALUE
                    sum += v * v
                    count++
                }
            }

            C.ENCODING_PCM_FLOAT -> {
                val n = bytes / 4
                for (i in 0 until n) {
                    val v = samples.float.toDouble().coerceIn(-1.0, 1.0)
                    sum += v * v
                    count++
                }
            }

            else -> return null
        }

        if (count == 0) return null
        return kotlin.math.sqrt(sum / count).coerceIn(0.0, 1.0)
    }

    private const val ALPHA_FAST = 0.5
    private const val ALPHA_SLOW = 0.05
    private const val EPSILON = 1.0E-6
    private const val MIN_BEAT_GAP_NS = 120_000_000L
    private const val BEAT_DECAY_REFERENCE_NS = 16_666_667L
    private const val BEAT_DECAY_PER_REFERENCE = 0.90
}
