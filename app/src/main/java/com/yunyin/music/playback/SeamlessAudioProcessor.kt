package com.yunyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The audio half of the "seamless" track transition: tapers the head of each stream.
 *
 * MP3 and AAC start and end mid-waveform, and a stream that begins at full amplitude clicks. Ramping the
 * first few milliseconds removes that, so a track begins rather than switches on.
 *
 * ## Why there is no silence trimming here (it was tried, and removed)
 *
 * A previous version also trimmed leading/trailing silence with Media3's `SilenceSkippingAudioProcessor`.
 * That is the standard trick for speech, and it makes contiguous playback sound tighter — but it is
 * actively wrong in a music player that supports seeking, for a reason that is easy to miss:
 *
 *  - The trimmer removes silence *wherever it occurs*, including a song's **trailing fade-out**, which most
 *    tracks have a second or two of. So seeking anywhere into that tail — which is exactly what "tap near
 *    the end" does — lands in audio that the trimmer then skips. The remaining audio is consumed
 *    instantly, the item ends, and playback advances to the next song.
 *  - The position it reports is corrected for the skipped frames, so the two together read as the progress
 *    bar *jumping to the very end* on a seek that was nowhere near it.
 *
 * There is no configuration that avoids this: the trimmer does not distinguish leading from trailing
 * silence, and keeping all silence (retention 1.0) means it does nothing at all. So the trimming is gone,
 * and only the amplitude taper remains — which is safe, because it changes no frame counts and therefore
 * cannot affect the media position, and which is verified by `SeamlessAudioProcessorTest`.
 *
 * Amplitude-only processing: this produces exactly as many frames as it consumes, so it cannot disturb the
 * media position, and it reports the passed-through format unchanged. If it is handed anything other than
 * 16-bit PCM it deactivates itself (`isActive()` false, format `NOT_SET`) rather than throwing — an
 * unsupported format must degrade to "no fade", never fail playback.
 */
@UnstableApi
class SeamlessAudioProcessor(
    /**
     * Whether the taper is on, read live.
     *
     * A lambda rather than a value, so the Settings switch takes effect without the processor having to be
     * rebuilt: it is consulted at each flush, which is a fresh load or a seek — i.e. the moment a taper is
     * about to be applied anyway.
     */
    private val enabled: () -> Boolean = { true },
) : BaseAudioProcessor() {

    /** Length of the head taper, in milliseconds. Zero disables it. */
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
        // The switch is read here, so turning it off silences the taper from the next flush onward.
        fadeFrames = if (enabled()) fadeMs * inputAudioFormat.sampleRate / 1000 else 0
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
