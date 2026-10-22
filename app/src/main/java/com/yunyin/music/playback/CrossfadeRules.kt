package com.yunyin.music.playback

import androidx.media3.common.Player

/**
 * 歌曲无缝衔接: non-overlapping tail/head fade at queue boundaries.
 *
 * ## What the old feature did
 *
 * Only [SeamlessAudioProcessor]'s **head taper** (~50 ms) on each stream. With one ExoPlayer and streamed HTTP
 * tracks there is still a load gap at every boundary, and 50 ms is below the threshold of "I can hear a transition",
 * so the switch appeared to do nothing.
 *
 * ## What this is (and is not)
 *
 * This is a volume envelope on the single running player: the outgoing track fades to silence over [FADE_MS]
 * before its end, and the incoming one ramps up from silence over the same span. ExoPlayer still loads the next
 * item itself, so this does **not** overlap two tracks (true crossfade needs two decoders); it removes the abrupt
 * boundary and masks the start of the load gap. The honest claim is "fade-out / fade-in", not gapless overlap.
 *
 * Pure so the eligibility and gain curve are unit-testable; the player only applies the returned gain.
 */
object CrossfadeRules {

    /** 1.5 s is long enough to hear, short enough not to feel like a volume bug. */
    const val FADE_MS = 1_500L

    /** Very short tracks (jingles) get a shorter fade so the whole song is not a fade. */
    const val MAX_FADE_FRACTION = 3

    /** Whether a boundary follows this item (queue order, or queue loop when repeat-all is active). */
    fun hasNextItem(index: Int, itemCount: Int, repeatMode: Int): Boolean {
        if (itemCount <= 1) return false
        if (index < itemCount - 1) return true
        return repeatMode == Player.REPEAT_MODE_ALL
    }

    /** The player volume for the current position; 1 when the feature is off or no boundary follows. */
    fun gain(
        positionMs: Long,
        durationMs: Long,
        enabled: Boolean,
        hasNext: Boolean,
    ): Float {
        if (!enabled || !hasNext || durationMs <= 0L) return 1f
        val fade = minOf(FADE_MS, durationMs / MAX_FADE_FRACTION).coerceAtLeast(1L)
        val position = positionMs.coerceIn(0L, durationMs)
        val remaining = durationMs - position
        return when {
            position < fade -> (position.toFloat() / fade).coerceIn(0.02f, 1f)
            remaining < fade -> (remaining.toFloat() / fade).coerceIn(0f, 1f)
            else -> 1f
        }
    }
}
