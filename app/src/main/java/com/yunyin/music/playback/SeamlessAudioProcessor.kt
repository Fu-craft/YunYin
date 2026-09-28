package com.yunyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The audio half of the "seamless" track transition.
 *
 * Two independent artifacts make one track run into the next sound like a splice rather than a
 * continuation, and each is handled by a different part of this design:
 *
 *  1. **Leading/trailing silence and encoder padding.** MP3 and AAC carry encoder delay and padding, and
 *     uploaders routinely leave seconds of dead air at the head. That silence is what you hear as a gap.
 *     This is handed to Media3's [SilenceSkippingAudioProcessor], switched on through
 *     `ExoPlayer.setSkipSilenceEnabled` — *not* reimplemented here, and that distinction is the whole
 *     reason this feature is safe in this app (see below).
 *  2. **An abrupt onset.** Even with the silence trimmed, the first sample lands at full amplitude, which
 *     can click or pop. [SeamlessAudioProcessor] tapers the first few milliseconds so the track begins
 *     rather than switches on.
 *
 * ## Why the silence trimming must be Media3's, not ours
 *
 * This app's karaoke lyrics and progress bar are driven from the player's reported position. A processor
 * that drops samples *shortens the stream*, so a naive implementation would slide the audio out of sync
 * with the lyric timeline — the exact bug class this app has spent a lot of effort eliminating, and one
 * that would show up as every word landing late after the first length of silence.
 *
 * `DefaultAudioSink` avoids it: it asks the chain for `getSkippedOutputFrameCount()` and adds that
 * duration back into the position it reports (`applySkipping`), so the media position stays on the
 * original timeline and skipping is invisible to anything reading it. [SeamlessAudioProcessorChain]
 * therefore delegates that count to the real [SilenceSkippingAudioProcessor] and keeps it in the chain,
 * rather than trimming on its own. The fade processor changes amplitude only — never length — so it needs
 * no correction at all.
 *
 * The app's own [com.yunyin.music.playback.ReactiveRenderersFactory] replaces the sink's chain to add this
 * fade and the analysis tee; the chain built here reproduces Media3's default ordering
 * (`user processors → silence skipping → Sonic`) so that nothing about speed/pitch handling changes.
 */

/**
 * Tapers the head of each stream.
 *
 * Amplitude-only processing: it produces exactly as many frames as it consumes, so it cannot disturb the
 * media position, and it reports the passed-through format unchanged. If it is handed anything other than
 * 16-bit PCM it deactivates itself (`isActive()` false, format `NOT_SET`) rather than throwing — an
 * unsupported format must degrade to "no fade", never fail playback.
 *
 * The fade is deliberately short ([DEFAULT_FADE_MS]): long enough to remove an onset click, short enough
 * that it is not heard as a fade-in. It is applied on `flush`, and a flush happens on every load and seek,
 * so "the first frames of the stream" means the first frames you actually hear.
 */
@UnstableApi
class SeamlessAudioProcessor : BaseAudioProcessor() {

    /** Length of the head taper, in milliseconds. */
    private var fadeMs: Int = DEFAULT_FADE_MS

    /** Frames the taper spans, derived from the input sample rate on flush. */
    private var fadeFrames = 0

    /** Frames emitted since the last flush; drives the fade's position. */
    private var framesEmitted = 0L

    /** Sets the head taper length. Call before playback; the value applies from the next flush. */
    fun setFadeDurationMs(ms: Int) {
        fadeMs = ms.coerceIn(0, MAX_FADE_MS)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.sampleRate == Format.NO_VALUE) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // A flush is a fresh load or a seek. In both cases the next frames are ones the listener has not
        // heard, so the taper restarts: seeking into a track should fade in exactly as loading it does.
        fadeFrames = fadeMs * inputAudioFormat.sampleRate / 1000
        framesEmitted = 0
    }

    override fun onReset() {
        framesEmitted = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val channels = inputAudioFormat.channelCount
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val source = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val target = out.order(ByteOrder.nativeOrder())

        if (fadeFrames <= 0 || framesEmitted >= fadeFrames) {
            // Past the taper: straight copy, and no per-sample work at all. This is the path taken for
            // essentially the whole of every track, so it must not pay for the fade.
            target.put(source)
        } else {
            // The gain is per *frame*, not per sample, or the channels would fade independently and the
            // stereo image would shift during the taper.
            var channel = 0
            var gain = nextFrameGain()
            val scaled = gain < 1f
            while (source.remaining() >= 2) {
                val sample = source.short
                target.putShort(if (scaled) (sample.toFloat() * gain).roundToInt().toShort() else sample)
                channel++
                if (channel == channels) {
                    channel = 0
                    gain = nextFrameGain()
                }
            }
        }

        target.flip()
        // The buffer is consumed: Media3's processors advance the input to its limit, and the pipeline
        // relies on that to know the data was taken.
        inputBuffer.position(inputBuffer.limit())
    }

    /**
     * Gain for the next frame, advancing the counter.
     *
     * Ramps from one step above silence to unity across [fadeFrames]. It starts at a non-zero step rather
     * than 0 so the very first frame is not a hard edge of its own.
     */
    private fun nextFrameGain(): Float {
        if (framesEmitted >= fadeFrames) return 1f
        val gain = (framesEmitted + 1).toFloat() / fadeFrames
        framesEmitted++
        return gain
    }

    private companion object {
        /** Head taper length. Long enough to remove a click, short enough not to be heard as a fade. */
        const val DEFAULT_FADE_MS = 30

        /** Upper bound, so a bad caller cannot turn the taper into an audible fade-in. */
        const val MAX_FADE_MS = 400
    }
}

/**
 * The sink's processor chain: the app's own processors, then silence trimming, then speed/pitch.
 *
 * Mirrors `DefaultAudioSink.DefaultAudioProcessorChain` — same ordering, same delegation — but takes the
 * processors the app supplies instead of a fixed set. It has to exist at all because
 * `DefaultAudioSink.Builder.setAudioProcessors(...)` wraps the given processors in a *default* chain that
 * puts them **before** silence skipping, which would leave this app's head taper sitting on the leading
 * silence that the skipper then removes — the fade would be thrown away. Building the chain here pins the
 * order.
 *
 * The [getSkippedOutputFrameCount] and [getMediaDuration] delegations are not incidental: the sink
 * consults them to keep the media position on the original timeline. Skipping silence without reporting
 * the skipped frames would desync the lyrics (see the file note above), so this is the part that makes
 * trimming safe rather than merely correct-sounding.
 */
@UnstableApi
internal class SeamlessAudioProcessorChain(
    userProcessors: Array<AudioProcessor>,
    private val silenceSkipping: SilenceSkippingAudioProcessor = SilenceSkippingAudioProcessor(),
    private val sonic: SonicAudioProcessor = SonicAudioProcessor(),
) : AudioProcessorChain {

    private val processors: Array<AudioProcessor> =
        userProcessors + arrayOf(silenceSkipping, sonic)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames
}
