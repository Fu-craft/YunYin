package com.yunyin.music.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.yunyin.music.core.player.effects.AudioReactive

/**
 * Renderers factory that builds the app's audio pipeline.
 *
 * Two things are installed on the audio sink, and both need the chain built by hand:
 *
 *  - [SeamlessAudioProcessor] tapers the head of each stream so tracks begin rather than switch on.
 *  - A [TeeAudioProcessor] taps the decoded audio so
 *    [com.yunyin.music.core.player.effects.AudioReactive] can measure loudness for the animated
 *    background. The tee is transparent: observing the stream does not alter playback.
 *
 * [SeamlessAudioProcessorChain] reproduces Media3's default chain ordering around them. That ordering is
 * the point: Media3's own builder inserts app processors *before* silence skipping, which would put the
 * head taper on the leading silence that gets trimmed away. The chain keeps
 * `app processors → silence skipping → Sonic`, and routes `getSkippedOutputFrameCount` /
 * `getMediaDuration` to the right members so the media position stays correct while silence is skipped.
 */
@UnstableApi
class ReactiveRenderersFactory(
    context: Context,
    private val seamless: SeamlessAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val processors = arrayOf<AudioProcessor>(
            seamless,
            TeeAudioProcessor(AudioReactive.teeSink),
        )
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(SeamlessAudioProcessorChain(processors))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
