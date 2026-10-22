package com.yunyin.music.data

import android.app.Notification

/**
 * Constants/arithmetic for the doc-literal Flyme lyric notification.
 *
 * The implementation is deliberately tiny: every lyric line is one `notify(sameId, …)`. The only suppression is an
 * identical line re-delivered; a *different* line is never dropped — if Android's ~1 s notification rate limit is
 * still in effect, the newest line is coalesced and posted at the next allowed instant (latest-wins).
 */
object FlymeLyricNotificationRules {

    const val NOTIFICATION_ID = 2001
    const val CHANNEL_ID = "flyme_status_bar_lyrics"

    const val FLAG_ALWAYS_SHOW_TICKER = 0x01000000
    const val FLAG_ONLY_UPDATE_TICKER = 0x02000000

    /** Android's notification update rate limit; only used to coalesce, never to drop a different line. */
    const val MIN_POST_INTERVAL_MS = 1_000L

    /** An unchanged line must not be reposted. */
    fun isSameText(previous: String?, next: String?): Boolean =
        next != null && previous == next

    /** Flags for a lyric post: resident + both Flyme flags. */
    fun activeFlags(showTicker: Int, updateTicker: Int): Int =
        Notification.FLAG_NO_CLEAR or showTicker or updateTicker

    /** Flags for the stop post: resident only, both Flyme flags removed. */
    fun clearedFlags(showTicker: Int, updateTicker: Int): Int =
        Notification.FLAG_NO_CLEAR and (showTicker or updateTicker).inv()

    /** 0 when a post may happen now; otherwise how long the newest line must wait (latest-wins). */
    fun nextPostDelayMs(nowMs: Long, lastPostMs: Long): Long {
        if (lastPostMs <= 0L) return 0L
        return (lastPostMs + MIN_POST_INTERVAL_MS - nowMs).coerceAtLeast(0L)
    }

    val isFlymeManufacturer: (String?, String?) -> Boolean = { manufacturer, brand ->
        listOf(manufacturer, brand).any { it?.trim()?.equals("meizu", ignoreCase = true) == true }
    }
}
