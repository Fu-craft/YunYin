package com.yunyin.music.data

/**
 * Rate/timing gate for the Flyme status-bar ticker.
 *
 * Flyme's recipe (适配说明, and Moriafly's write-up) is explicit: reposting too fast makes the ticker stutter, and a
 * post immediately after a track change can leave the *previous* track's line on screen. So:
 *  - at most one post per second; intermediate lyric lines are dropped, not queued (a queued burst would replay old
 *    lines long after they were relevant);
 *  - after the "track key" changes, nothing is posted for [TRACK_CHANGE_DELAY_MS].
 *
 * Pure (time injected) so both rules are unit-tested; the notifier only owns the SystemClock and the notification.
 */
object FlymeLyricThrottle {

    const val MIN_INTERVAL_MS = 1_000L
    const val TRACK_CHANGE_DELAY_MS = 1_000L

    data class State(
        val lastPostMs: Long = Long.MIN_VALUE,
        val trackKey: String? = null,
        val earliestPostMs: Long = 0L,
    )

    /** Resets the delay whenever the track key (title) changes. */
    fun onTrackChanged(state: State, trackKey: String?, nowMs: Long): State =
        if (trackKey == state.trackKey) {
            state
        } else {
            state.copy(trackKey = trackKey, earliestPostMs = nowMs + TRACK_CHANGE_DELAY_MS)
        }

    fun canPost(state: State, nowMs: Long): Boolean {
        if (nowMs < state.earliestPostMs) return false
        if (state.lastPostMs == Long.MIN_VALUE) return true
        return nowMs - state.lastPostMs >= MIN_INTERVAL_MS
    }

    fun markPosted(state: State, nowMs: Long): State = state.copy(lastPostMs = nowMs)
}
