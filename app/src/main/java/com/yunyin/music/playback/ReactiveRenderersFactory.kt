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
 * Two processors are installed on the audio sink:
 *
 *  - [SeamlessAudioProcessor] tapers the head of each stream so tracks begin rather than switch on.
 *  - A [TeeAudioProcessor] taps the decoded audio so
 *    [com.yunyin.music.core.player.effects.AudioReactive] can measure loudness for the animated
 *    background. The tee is transparent: observing the stream does not alter playback.
 *
 * `setAudioProcessors` is used rather than a hand-built chain. Media3 wraps them in its default chain,
 * which appends an *inactive* silence skipper and Sonic — and inert processors cost nothing. An earlier
 * version replaced the chain to reorder around that skipper so silence trimming could be enabled; the
 * trimming has since been removed (it broke seeking; see [SeamlessAudioProcessor]), so the default chain is
 * correct and the custom one is gone.
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
            .setAudioProcessors(processors)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
