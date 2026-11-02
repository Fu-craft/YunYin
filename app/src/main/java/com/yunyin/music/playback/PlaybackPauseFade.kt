// SPDX-License-Identifier: AGPL-3.0-only
// Ported from CurrentMusicX (GPL-3.0-only), core/media/PlaybackPauseFade.kt, which itself credits
// Folia's playbackFade.ts / usePlaybackTransportController.ts (AGPL). 云音 is AGPL-3.0-only and GPLv3
// §13 permits the combination; the combined work stays AGPL.
//
// One deliberate difference from the original: the gain is *not* written to `ExoPlayer.volume`. This
// app's crossfade already owns that property (it writes 0→1 on both decks during an overlap), so a
// second writer would fight it. The envelope is published into a [TransportGain] that the audio
// processor multiplies into the samples instead — same audible result, no shared-state race.

package com.yunyin.music.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The transport gain the audio processor multiplies into every sample.
 *
 * `@Volatile` because it is written on the main thread (the envelope) and read on the audio thread.
 */
class TransportGain {
    @Volatile
    var value: Float = 1f
}

/**
 * Main-thread transport envelope.
 *
 * The gain **multiplies** rather than replaces the deck/crossfade volume: pausing ramps it to 0 and a
 * resume ramps it back to 1 over [DURATION_MS].
 */
class PlaybackPauseFade(
    private val scope: CoroutineScope,
    private val applyGain: (Float) -> Unit,
    private val pausePlayer: () -> Unit,
    private val changed: () -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var pausedIntent = false
        private set
    var gain = 1f
        private set

    private var settled = true
    private var revision = 0L
    private var ramp: Job? = null

    val fadingOut get() = pausedIntent && !settled

    /**
     * Notes that playback is (or is about to be) paused.
     *
     * [audible] = true runs the 200 ms fade-out and then pauses; false is the "already silent" case
     * (the pause came from the notification or the lock screen, so the audio has stopped before we
     * were told) and just parks the gain at 0 so the next resume fades in.
     */
    fun pause(audible: Boolean) {
        if (pausedIntent) return
        pausedIntent = true
        settled = false
        changed()
        if (!audible) {
            finishPause()
            return
        }
        animate(0f) {
            delay(15)
            finishPause()
        }
    }

    /** Ramps back to unity; the next audible frames start from silence. */
    fun resume() {
        if (!pausedIntent) return
        pausedIntent = false
        settled = true
        changed()
        animate(1f)
    }

    /** End-of-track, focus loss or a seek cannot let an old pause start another song. */
    fun finishPause() {
        if (!fadingOut) return
        revision++
        ramp?.cancel()
        ramp = null
        settled = true
        setGain(0f)
        pausePlayer()
        changed()
        // Keep the paused pipeline muted; only an explicit resume/new source restores its gain.
    }

    /** Call after stopping the old source, before loading a different source or releasing players. */
    fun reset() {
        revision++
        ramp?.cancel()
        ramp = null
        pausedIntent = false
        settled = true
        setGain(1f)
        changed()
    }

    private fun setGain(value: Float) {
        gain = value
        applyGain(value)
    }

    private fun animate(target: Float, complete: suspend () -> Unit = {}) {
        val token = ++revision
        ramp?.cancel()
        val from = gain
        val start = nowMs()
        ramp = scope.launch {
            while (token == revision) {
                val progress = ((nowMs() - start).toFloat() / DURATION_MS).coerceIn(0f, 1f)
                setGain(from + (target - from) * progress)
                if (progress >= 1f) break
                delay(minOf(16L, (DURATION_MS - (nowMs() - start)).coerceAtLeast(1)))
            }
            if (token == revision) complete()
        }
    }

    companion object {
        const val DURATION_MS = 200L
    }
}
