package com.yunyin.music.playback

/**
 * When to warm the next track's stream URL, and which track.
 *
 * A transition resolves its URL on the critical path, which is what the user feels as "the next song takes a moment
 * to start". Resolving the *next* queue item while the current one plays moves that cost off the transition, and the
 * real transition then hits [com.yunyin.music.data.MusicRepository]'s resolution cache.
 *
 * The policy is deliberately conservative:
 *  - only the queue's next id, in queue order (no lookahead across an unknown queue, no wrap-around guessing);
 *  - never the currently playing id, and only one prefetch in flight;
 *  - the caller's prefetch job is independent of the player's live lookup — it is started on the same scope but is
 *    never passed to `cancelPending`, so a prefetch can neither cancel nor be cancelled by a live lookup. The
 *    supersede rule only concerns the *current* track's lookup.
 */
object PlaybackPrefetchPolicy {

    /** How long after a transition the prefetch starts: long enough not to compete with the current track's start. */
    const val PREFETCH_DELAY_MS = 8_000L

    /** The next id in queue order, or null at the end of the queue. */
    fun nextTrackId(queueIds: List<Long>, currentIndex: Int): Long? {
        if (queueIds.isEmpty()) return null
        val current = queueIds.getOrNull(currentIndex)
        return queueIds.getOrNull(currentIndex + 1)?.takeIf { it != current }
    }

    /** One prefetch at a time, never for the live track. */
    fun shouldStart(currentTrackId: Long?, nextTrackId: Long?, inFlightPrefetchTrackId: Long?): Boolean =
        nextTrackId != null &&
            nextTrackId != currentTrackId &&
            nextTrackId != inFlightPrefetchTrackId
}
