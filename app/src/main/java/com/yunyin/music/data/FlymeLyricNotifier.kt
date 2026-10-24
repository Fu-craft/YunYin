// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yunyin.music.R
import com.yunyin.music.data.lxsource.LxSourceLog

/**
 * Flyme 状态栏歌词 — a literal implementation of the doc's `setLyric(String text)`.
 *
 * One function per doc step: [flags] (reflect once) → [buildNotification] → [NotificationManagerCompat.notify] with the
 * **same** id on every line. There is no establish-state, no track-change delay and no line-dropping throttle: every
 * different lyric line results in one notify. The only coalescing is Android's own ~1 s notification rate limit,
 * handled latest-wins in [schedule] (the newest line is posted at the next allowed instant; the last line always
 * lands).
 *
 * No `setSilent`/`setOnlyAlertOnce`: Flyme skips the ticker for silent notifications (device-proven). Noise is
 * prevented by the channel (no sound/vibration/badge) and `setDefaults(0)`.
 */
class FlymeLyricNotifier(
    private val context: Context,
    private val log: LxSourceLog? = null,
) {

    private val flags: Flags? by lazy {
        val resolved = resolveFlags()
        record(
            "init sdk=${Build.VERSION.SDK_INT} manufacturer=${safeManufacturer()} brand=${safeBrand()} " +
                "model=${runCatching { Build.MODEL }.getOrNull()} " +
                "reflectedShow=${resolved?.showTicker ?: 0} reflectedUpdate=${resolved?.updateTicker ?: 0} " +
                "supported=${resolved != null} id=${FlymeLyricNotificationRules.NOTIFICATION_ID}",
        )
        resolved
    }

    fun isSupported(): Boolean = flags != null

    fun logSkip(reason: String) = record("skip reason=$reason")

    private val handler = Handler(Looper.getMainLooper())
    private var activeLine: String? = null
    private var pendingLine: String? = null
    private var lastPostMs = 0L
    private var updateIndex = 0

    private val flushPending = Runnable {
        val pending = pendingLine ?: return@Runnable
        pendingLine = null
        val resolved = flags ?: return@Runnable
        runCatching { post(pending, resolved) }
    }

    /** Called by the player on every position poll; identical lines are skipped, different ones are posted. */
    fun update(line: String?, fallback: String?, playing: Boolean) {
        updateIndex++
        val resolved = flags ?: run { record("skip reason=unsupported"); return }
        val text = if (!playing) null else line?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }
        if (text == null) {
            pendingLine = null
            handler.removeCallbacks(flushPending)
            clearTicker(resolved)
            record("update idx=$updateIndex len=0 same=false posted=false id=${FlymeLyricNotificationRules.NOTIFICATION_ID}")
            return
        }
        val same = FlymeLyricNotificationRules.isSameText(activeLine, text)
        if (same) {
            record("update idx=$updateIndex len=${text.length} same=true posted=false id=${FlymeLyricNotificationRules.NOTIFICATION_ID}")
            return
        }
        schedule(text, resolved)
    }

    /** Latest-wins coalescing: post now, or schedule the newest line at the next allowed instant. */
    private fun schedule(text: String, resolved: Flags) {
        val now = SystemClock.elapsedRealtime()
        val delay = FlymeLyricNotificationRules.nextPostDelayMs(now, lastPostMs)
        if (delay <= 0L) {
            runCatching { post(text, resolved) }
            return
        }
        pendingLine = text
        handler.removeCallbacks(flushPending)
        handler.postDelayed(flushPending, delay)
        record(
            "update idx=$updateIndex len=${text.length} same=false posted=false " +
                "id=${FlymeLyricNotificationRules.NOTIFICATION_ID} coalescedMs=$delay",
        )
    }

    /** Settings → 播放 → 「测试状态栏歌词」: one shipping notification, same builder. */
    fun runSelfTest() {
        val resolved = flags ?: Flags(
            FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
            FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
        )
        runCatching { post("测试歌词 · 云音", resolved, test = true) }
    }

    private fun post(text: String, resolved: Flags, test: Boolean = false) {
        ensureChannel()
        val notification = buildNotification(resolved, text)
        NotificationManagerCompat.from(context).notify(FlymeLyricNotificationRules.NOTIFICATION_ID, notification)
        activeLine = text
        lastPostMs = SystemClock.elapsedRealtime()
        record(
            (if (test) "test post" else "post") +
                " len=${text.length} id=${FlymeLyricNotificationRules.NOTIFICATION_ID} " +
                "flags=0x${notification.flags.toString(16)} silent=false priority=${Notification.PRIORITY_MAX} " +
                "channel=${FlymeLyricNotificationRules.CHANNEL_ID}",
        )
    }

    /** Doc step 2: resident, PRIORITY_MAX, 60 dp small icon, ticker line, ticker-icon extras, both flags. */
    private fun buildNotification(resolved: Flags, text: String): Notification {
        val builder = NotificationCompat.Builder(context, FlymeLyricNotificationRules.CHANNEL_ID)
            .setPriority(Notification.PRIORITY_MAX)
            .setSmallIcon(R.drawable.ic_lyric_ticker_60)
            .setTicker(text)
            .setDefaults(0)
            .setShowWhen(false)
            .setOngoing(true)
            // Every line carries content: a content-less notification is ticked only once by Flyme, which
            // is exactly why the 2nd..Nth lyric line never scrolled (the test post, which had no
            // activeLine yet, did carry a title and did scroll). The ticker still drives the status bar.
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
        val notification = builder.build()
        notification.flags = FlymeLyricNotificationRules.activeFlags(resolved.showTicker, resolved.updateTicker)
        notification.extras.putBoolean("ticker_icon_switch", false)
        notification.extras.putInt("ticker_icon", R.drawable.ic_ticker_note)
        return notification
    }

    /** Doc: stop = `setTicker(null)` + clear both flags + notify, then drop the entry. */
    fun clear() {
        pendingLine = null
        handler.removeCallbacks(flushPending)
        flags?.let { clearTicker(it) }
    }

    private fun clearTicker(resolved: Flags) {
        activeLine = null
        lastPostMs = 0L
        runCatching {
            val notification = NotificationCompat.Builder(context, FlymeLyricNotificationRules.CHANNEL_ID)
                .setPriority(Notification.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_lyric_ticker_60)
                .setTicker(null)
                .setDefaults(0)
                .setShowWhen(false)
                .setOngoing(true)
                .build()
            notification.flags = FlymeLyricNotificationRules.clearedFlags(resolved.showTicker, resolved.updateTicker)
            NotificationManagerCompat.from(context).notify(FlymeLyricNotificationRules.NOTIFICATION_ID, notification)
            record("clear id=${FlymeLyricNotificationRules.NOTIFICATION_ID}")
        }.onFailure { Log.w(TAG, "Failed to clear Flyme lyric", it) }
        runCatching { NotificationManagerCompat.from(context).cancel(FlymeLyricNotificationRules.NOTIFICATION_ID) }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = FlymeLyricNotificationRules.CHANNEL_ID
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                context.getString(R.string.ticker_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    /** Doc step 1: reflect the two private fields once per process; Meizu name is the hidden-API fallback. */
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
        runCatching {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            log?.recordDiagnostic("[$timestamp] stage=flyme-lyric $detail")
        }
    }

    private data class Flags(val showTicker: Int, val updateTicker: Int)

    private companion object {
        const val TAG = "YunYin/TickerLyrics"
    }
}
