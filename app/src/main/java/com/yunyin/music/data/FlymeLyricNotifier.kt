package com.yunyin.music.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yunyin.music.R
import com.yunyin.music.data.lxsource.LxSourceLog

/**
 * Flyme 状态栏歌词, implemented literally from the official 适配说明.
 *
 *  - a **standalone** notification (not the media notification), its **own channel**, always the **same id**;
 *  - `PRIORITY_MAX`, a 60 dp-class small icon (Flyme renders it left of the ticker), `setTicker(line)`;
 *  - `FLAG_NO_CLEAR` always (mandatory resident notification), both private flags + ticker-icon extras only while
 *    a lyric line is active;
 *  - pause/stop/disable: `setTicker(null)`, clear both flags, post once more, then drop the entry;
 *  - private flags reflected once; absent + non-Meizu ⇒ feature unsupported; Meizu manufacturer/brand fallback.
 *
 * The media notification is not touched at all.
 */
class FlymeLyricNotifier(
    private val context: Context,
    private val log: LxSourceLog? = null,
) {

    private val flags: Flags? by lazy {
        val resolved = resolveFlags()
        record(
            "supported=${resolved != null} showFlag=${resolved?.showTicker ?: 0} " +
                "updateFlag=${resolved?.updateTicker ?: 0} manufacturer=${safeManufacturer()} " +
                "brand=${safeBrand()} sdk=${Build.VERSION.SDK_INT}",
        )
        resolved
    }

    fun isSupported(): Boolean = flags != null

    private var throttle = FlymeLyricThrottle.State()
    private var activeLine: String? = null
    private var posted = false

    /** Feeds the current lyric line; [fallback] is the track title and the track-change key. */
    fun update(line: String?, fallback: String?, playing: Boolean) {
        val resolved = flags ?: return
        val now = SystemClock.elapsedRealtime()
        throttle = FlymeLyricThrottle.onTrackChanged(throttle, fallback, now)
        if (!playing) {
            clearTicker(resolved)
            return
        }
        val text = line?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }
        if (text == null) {
            clearTicker(resolved)
            return
        }
        if (text == activeLine) return
        if (!FlymeLyricThrottle.canPost(throttle, now)) return
        throttle = FlymeLyricThrottle.markPosted(throttle, now)
        post(resolved, text)
    }

    /** Stops the lyric: cleared ticker/flags notice, then removes the entry. */
    fun clear() {
        throttle = FlymeLyricThrottle.State()
        activeLine = null
        flags?.let { clearTicker(it) }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        posted = false
    }

    private fun post(resolved: Flags, text: String) {
        runCatching {
            ensureChannel()
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setPriority(Notification.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_lyric_ticker_60)
                .setTicker(text)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setOngoing(true)
            if (activeLine == null) builder.setContentTitle(context.getString(R.string.app_name))
            val notification = builder.build()
            notification.flags = FlymeLyricNotificationRules.activeFlags(resolved.showTicker, resolved.updateTicker)
            notification.extras.putBoolean("ticker_icon_switch", false)
            notification.extras.putInt("ticker_icon", R.drawable.ic_ticker_note)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            activeLine = text
            posted = true
            record("post len=${text.length} id=$NOTIFICATION_ID flags=0x${notification.flags.toString(16)}")
        }.onFailure { Log.w(TAG, "Failed to post Flyme lyric", it) }
    }

    private fun clearTicker(resolved: Flags) {
        if (!posted && activeLine == null) return
        activeLine = null
        runCatching {
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setPriority(Notification.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_lyric_ticker_60)
                .setTicker(null)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .build()
            notification.flags = FlymeLyricNotificationRules.clearedFlags(resolved.showTicker, resolved.updateTicker)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            record("clear")
        }.onFailure { Log.w(TAG, "Failed to clear Flyme lyric", it) }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        posted = false
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ticker_channel_name),
               // Flyme wants HIGH; sound/vibration/badge off so it never pops per line.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
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
        return if (FlymeLyricNotificationRules.isFlymeManufacturer(safeManufacturer(), safeBrand())) {
            Flags(
                FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
                FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
            )
        } else {
            null
        }
    }

    private fun safeManufacturer(): String? = runCatching { Build.MANUFACTURER }.getOrNull()
    private fun safeBrand(): String? = runCatching { Build.BRAND }.getOrNull()

    private fun record(detail: String) {
        runCatching { log?.record("解析", "stage=flyme-lyric $detail") }
    }

    private data class Flags(val showTicker: Int, val updateTicker: Int)

    private companion object {
        const val TAG = "YunYin/TickerLyrics"
        const val NOTIFICATION_ID = FlymeLyricNotificationRules.NOTIFICATION_ID
        const val CHANNEL_ID = FlymeLyricNotificationRules.CHANNEL_ID
    }
}
