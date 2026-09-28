package com.yunyin.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The head taper used by the seamless transition.
 *
 * Two properties matter and both are easy to get subtly wrong:
 *
 *  1. **The frame count is preserved exactly.** The app's karaoke lyrics and progress bar read the
 *     player's position, so a processor that changes the stream length would desync them. Silence
 *     trimming is handled by Media3 precisely because it corrects the reported position; this taper
 *     must not introduce a second, uncorrected length change.
 *  2. **The gain is per frame, not per sample.** Applying it per sample would fade the left and right
 *     channels at different rates and shift the stereo image during the taper.
 */
class SeamlessAudioProcessorTest {

    private val stereo16 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    /** A processor configured and flushed for 16-bit stereo, i.e. ready to take input. */
    private fun processor(): SeamlessAudioProcessor =
        SeamlessAudioProcessor().apply {
            configure(stereo16)
            flush()
        }

    private fun bufferOf(shorts: ShortArray): ByteBuffer =
        ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.nativeOrder()).apply {
            shorts.forEach { putShort(it) }
            flip()
        }

    private fun readShorts(buffer: ByteBuffer): ShortArray {
        val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
        val out = ShortArray(copy.remaining() / 2)
        for (i in out.indices) out[i] = copy.short
        return out
    }

    @Test
    fun `preserves the frame count exactly`() {
        val processor = processor()
        // A quarter second of stereo: far more than the taper, so both the faded and the copied path run.
        val frames = 11_025
        val input = bufferOf(ShortArray(frames * 2) { 1_000 })

        processor.queueInput(input)
        val output = readShorts(processor.getOutput())

        assertEquals("the taper must not add or drop a single frame", frames * 2, output.size)
    }

    @Test
    fun `fades the head in and leaves the body untouched`() {
        val processor = processor()
        val frames = 4_410
        val input = bufferOf(ShortArray(frames * 2) { 10_000 })
        processor.queueInput(input)
        val out = readShorts(processor.getOutput())

        // The very first frame is attenuated...
        assertTrue("the first sample is not tapered", out[0] < 10_000)
        assertTrue("the first sample must stay positive", out[0] > 0)
        // ...the gain rises...
        assertTrue("the taper does not rise", out[2] > out[0])
        // ...and by the end of the stream every sample is passed through bit-exact.
        val lastFrame = (frames - 1) * 2
        assertEquals(10_000.toShort(), out[lastFrame])
        assertEquals(10_000.toShort(), out[lastFrame + 1])
    }

    @Test
    fun `applies the same gain to every channel of a frame`() {
        val processor = processor()
        // Left and right identical, so any per-channel gain difference shows up as a mismatch.
        val frames = 2_205
        val input = bufferOf(ShortArray(frames * 2) { 20_000 })
        processor.queueInput(input)
        val out = readShorts(processor.getOutput())

        for (frame in 0 until frames) {
            assertEquals(
                "channel gains diverged at frame $frame, which shifts the stereo image",
                out[frame * 2],
                out[frame * 2 + 1],
            )
        }
    }

    @Test
    fun `restarts the taper on flush, so a seek fades in as a load does`() {
        val processor = processor()
        val frames = 2_205
        // Prime past the taper.
        processor.queueInput(bufferOf(ShortArray(frames * 2) { 10_000 }))
        processor.getOutput()

        // A flush models a fresh load or a seek: after it, the next frames are new to the listener.
        processor.flush()
        processor.queueInput(bufferOf(ShortArray(frames * 2) { 10_000 }))
        val out = readShorts(processor.getOutput())

        assertTrue("the taper did not restart after a flush", out[0] < 10_000)
        assertEquals("the tail of the post-flush stream is still exact", 10_000.toShort(),
            out[(frames - 1) * 2])
    }

    @Test
    fun `deactivates itself on a format it cannot handle rather than throwing`() {
        val processor = SeamlessAudioProcessor()
        val float32 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)

        // Must not throw: an unsupported format has to degrade to "no fade", never fail playback.
        processor.configure(float32)
        assertFalse("should stand down rather than fail the pipeline", processor.isActive)

        // And a format it does handle keeps it active.
        processor.configure(stereo16)
        assertTrue("should be active for 16-bit PCM", processor.isActive)
    }

    @Test
    fun `is a no-op before any flush`() {
        // onConfigure without flush: fadeFrames is still 0, so nothing should be attenuated. This is the
        // state the pipeline passes through while reconfiguring, and attenuating there would fade a
        // stream that was never meant to be faded.
        val processor = SeamlessAudioProcessor()
        processor.configure(stereo16)
        val input = bufferOf(ShortArray(64) { 5_000 })
        processor.queueInput(input)
        val out = readShorts(processor.getOutput())
        assertEquals(5_000.toShort(), out[0])
    }
}
