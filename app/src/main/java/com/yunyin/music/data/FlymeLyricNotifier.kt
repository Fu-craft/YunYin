package com.yunyin.music.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yunyin.music.MainActivity
import com.yunyin.music.R

/**
 * Flyme 状态栏歌词 — shows the current lyric line in the status bar on Flyme-family ROMs.
 *
 * ## How Flyme's feature actually works
 *
 * It is a modification of the standard notification ticker: Flyme looks for a **resident**
 * notification carrying a `tickerText` plus two private flags, and animates that text in the status
 * bar. From Flyme's own 适配说明 (open.flyme.cn/doc id 239):
 *
 *  - the notification must be permanent — `Notification.FLAG_NO_CLEAR`;
 *  - the lyric goes in `setTicker(...)`;
 *  - **both** `FLAG_ALWAYS_SHOW_TICKER` and `FLAG_ONLY_UPDATE_TICKER` must be set;
 *  - the same notification id must be reused for every update;
 *  - `ticker_icon_switch = false` plus `ticker_icon` control the little leading icon, which is where
 *    play/pause state is shown;
 *  - to stop showing lyrics, clear the ticker and both flags.
 *
 * Both flags are `@hide` fields that only exist on ROMs that ported the feature, so support is
 * detected by reflection — that is the documented method, and it is also the gate that keeps a
 * permanent notification off phones that would show it as dead weight.
 *
 * This posts its **own** notification rather than modifying Media3's transport notification. Flyme
 * only asks for a resident notification with a ticker; it does not have to be the media one, and
 * owning it means the update cadence is ours — the lyric line changes far more often than the
 * transport state does.
 */
class FlymeLyricNotifier(private val context: Context) {

    /**
     * The two private flags, or null on a ROM without the feature.
     *
     * Resolved once: reflection is not free and the answer cannot change while the process lives.
     * Failure is the normal case on most devices, so it is not logged as an error.
     */
    private val flags: Flags? by lazy {
        runCatching {
            val cls = Class.forName("android.app.Notification")
            val show = cls.getDeclaredField("FLAG_ALWAYS_SHOW_TICKER").apply { isAccessible = true }
                .getInt(null)
            val update = cls.getDeclaredField("FLAG_ONLY_UPDATE_TICKER").apply { isAccessible = true }
                .getInt(null)
            Flags(show, update)
        }.getOrNull()
    }

    /** Whether this ROM ported Flyme's status-bar lyrics. */
    fun isSupported(): Boolean = flags != null

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    /**
     * Posts or updates the resident lyric notification.
     *
     * @param line the lyric line, or null when there is nothing to show (no lyrics yet, or the
     *        lead-in). A null line still keeps the notification resident with [fallback] as the
     *        ticker, so the entry does not blink in and out of the shade between songs.
     * @param fallback shown while there is no lyric line — the track's own title.
     */
    fun update(line: String?, fallback: String?, playing: Boolean) {
        val resolved = flags ?: return
        val text = line ?: fallback
        if (text.isNullOrBlank()) {
            clear()
            return
        }

        runCatching {
            ensureChannel()

            val icon = if (playing) R.drawable.ic_ticker_play else R.drawable.ic_ticker_pause
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(line ?: "")
                .setTicker(text)
                .setContentIntent(openAppIntent())
                // Every line change reposts this notification, so it must never make a sound or
                // vibrate; the channel is silent too, and this is the belt to that braces.
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .build()

            // Must be permanent, or Flyme drops the ticker and the system may clear it.
            notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
            // The two flags that make Flyme render it as a status-bar lyric. Both are required; with
            // only one set the text is treated as an ordinary ticker and animates once.
            notification.flags = notification.flags or resolved.showTicker or resolved.updateTicker

            // The leading icon is the play/pause indicator. `ticker_icon_switch = false` tells Flyme
            // to use the notification's own small icon rather than swapping in its default.
            notification.extras.putBoolean("ticker_icon_switch", false)
            notification.extras.putInt("ticker_icon", icon)

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(TAG, "Failed to post status-bar lyric", error)
        }
    }

    /** Removes the lyric notification (feature disabled, or nothing to show). */
    fun clear() {
        if (flags == null) return
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel() {
        val existing = manager?.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ticker_channel_name),
                // LOW: it must be visible and silent. Default importance would buzz on every line.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private class Flags(val showTicker: Int, val updateTicker: Int)

    private companion object {
        const val TAG = "YunYin/TickerLyrics"
        const val CHANNEL_ID = "status_bar_lyrics"

        /**
         * Fixed id, reused for every update as Flyme requires.
         *
         * Chosen above Media3's own transport notification (1001) so the two never collide — that
         * would make each update overwrite the other's notification.
         */
        const val NOTIFICATION_ID = 2001
    }
}
