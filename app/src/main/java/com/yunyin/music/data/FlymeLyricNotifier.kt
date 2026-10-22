package com.yunyin.music.data

import android.app.Notification
import android.os.SystemClock
import android.util.Log
import com.yunyin.music.R

/**
 * Flyme 状态栏歌词 — attaches the lyric ticker to the **media notification itself**.
 *
 * ## Why not a separate notification (the 4.15.1–4.15.3 failures)
 *
 * Flyme's feature is a notification ticker, and a standalone ticker-only notification can "exist" without its
 * ticker ever scrolling (established-but-ignored). NeriPlayer (GPL-3.0,
 * `core/player/service/presentation/AndroidPlaybackServicePresentationPort.kt`) applies the ticker, both private
 * flags and the ticker-icon extras to the **notification the system already associates with playback** — the media
 * notification. This class is now only the state holder: the media-notification provider calls [attachTicker] while
 * building, and [currentTicker] when re-rendering.
 *
 * ## Rules kept
 *
 *  - both private flags only while playing with a lyric line; paused clears the ticker (stale line otherwise sticks);
 *  - at most one update per second (Android drops faster notification updates; intermediate lines are dropped);
 *  - ~1 s after a track change before the first line, so the previous track's text cannot stay;
 *  - ROM gate: no reflected/fallback flags → the media notification is left completely untouched.
 */
class FlymeLyricNotifier {

    /** The two private flags, reflection first, Meizu manufacturer/brand fallback (NeriPlayer's rule). */
    private val flags: Flags? by lazy { resolveFlags() }

    fun isSupported(): Boolean = flags != null

    private var throttle = FlymeLyricThrottle.State()
    private var ticker: String? = null
    private var lastTrackKey: String? = null

    /** Called (on the main thread) whenever the effective ticker changed and the media notification should refresh. */
    var onTickerChanged: (() -> Unit)? = null

    /**
     * Feeds the current lyric line. [fallback] is the track title, used as the track key and as the lead-in line.
     * With no playback or no line the ticker is cleared immediately (no rate limit on clearing).
     */
    fun update(line: String?, fallback: String?, playing: Boolean) {
        if (flags == null) return
        val now = SystemClock.elapsedRealtime()
        throttle = FlymeLyricThrottle.onTrackChanged(throttle, fallback, now)
        if (!playing) {
            setTicker(null)
            return
        }
        val text = line?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }
        if (text == null) {
            setTicker(null)
            return
        }
        if (text == ticker) return
        if (!FlymeLyricThrottle.canPost(throttle, now)) return
        throttle = FlymeLyricThrottle.markPosted(throttle, now)
        setTicker(text)
    }

    /** The ticker for the media-notification builder, or null when it must stay untouched. */
    fun currentTicker(): String? = ticker

    /**
     * Applies the Flyme ticker to the already-built media notification. Idempotent and never throws; when anything
     * is missing it simply leaves the media notification as Media3 built it.
     */
    fun attachTicker(notification: Notification) {
        val resolved = flags ?: return
        val text = ticker ?: return
        runCatching {
            notification.tickerText = text
            // The media notification already exists, so both flags are valid on every render (NeriPlayer applies
            // both unconditionally): ALWAYS keeps it scrolling, ONLY_UPDATE says "only the ticker changed".
            notification.flags = notification.flags or resolved.showTicker or resolved.updateTicker
            notification.extras.putInt("ticker_icon", R.drawable.ic_ticker_note)
            notification.extras.putBoolean("ticker_icon_switch", false)
        }.onFailure { Log.w(TAG, "Failed to attach Flyme ticker", it) }
    }

    /** Cancels a ticker posted by an older app version and clears the state. */
    fun clear() {
        setTicker(null)
        throttle = FlymeLyricThrottle.State()
        lastTrackKey = null
    }

    private fun setTicker(value: String?) {
        if (value == ticker) return
        ticker = value
        runCatching { onTickerChanged?.invoke() }
    }

    private fun resolveFlags(): Flags? {
        val reflected = runCatching {
            val cls = Class.forName("android.app.Notification")
            Flags(
                cls.getDeclaredField("FLAG_ALWAYS_SHOW_TICKER").apply { isAccessible = true }.getInt(null),
                cls.getDeclaredField("FLAG_ONLY_UPDATE_TICKER").apply { isAccessible = true }.getInt(null),
            )
        }.getOrNull()
        if (reflected != null) return reflected
        val manufacturer = runCatching { android.os.Build.MANUFACTURER }.getOrNull()
        val brand = runCatching { android.os.Build.BRAND }.getOrNull()
        val isMeizu = listOf(manufacturer, brand).any {
            it?.trim()?.equals("meizu", ignoreCase = true) == true
        }
        return if (isMeizu) {
            Flags(FLYME_FLAG_ALWAYS_SHOW_TICKER_FALLBACK, FLYME_FLAG_ONLY_UPDATE_TICKER_FALLBACK)
        } else {
            null
        }
    }

    private data class Flags(val showTicker: Int, val updateTicker: Int)

    private companion object {
        private const val TAG = "YunYin/TickerLyrics"

        /** NeriPlayer's fallbacks: AOSP never defines these flags. */
        const val FLYME_FLAG_ALWAYS_SHOW_TICKER_FALLBACK = 0x01000000
        const val FLYME_FLAG_ONLY_UPDATE_TICKER_FALLBACK = 0x02000000
    }
}
