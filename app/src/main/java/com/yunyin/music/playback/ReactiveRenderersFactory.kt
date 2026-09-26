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
 * Renderers factory that taps the decoded audio.
 *
 * Inserts a [TeeAudioProcessor] into the audio sink's processor chain so
 * [com.yunyin.music.core.player.effects.AudioReactive] can measure loudness for the animated
 * background. The tee is transparent: it observing the stream does not alter playback.
 */
@UnstableApi
class ReactiveRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val processors = arrayOf<AudioProcessor>(TeeAudioProcessor(AudioReactive.teeSink))
        return DefaultAudioSink.Builder(context)
            .setAudioProcessors(processors)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
