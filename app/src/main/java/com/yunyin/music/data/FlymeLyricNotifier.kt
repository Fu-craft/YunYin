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
            "init sdk=${Build.VERSION.SDK_INT} manufacturer=${safeManufacturer()} brand=${safeBrand()} " +
                "model=${runCatching { Build.MODEL }.getOrNull()} " +
                "reflectedShow=${resolved?.showTicker ?: "none"} " +
                "reflectedUpdate=${resolved?.updateTicker ?: "none"} supported=${resolved != null}",
        )
        record(
            "supported=${resolved != null} showFlag=${resolved?.showTicker ?: 0} " +
                "updateFlag=${resolved?.updateTicker ?: 0} manufacturer=${safeManufacturer()} " +
                "brand=${safeBrand()} sdk=${Build.VERSION.SDK_INT}",
        )
        resolved
    }

    fun isSupported(): Boolean = flags != null

    /** Used by the player's gate so skipped renders are still visible in the copied log. */
    fun logSkip(reason: String) = record("skip reason=$reason")

    private var throttle = FlymeLyricThrottle.State()
    private var activeLine: String? = null
    private var posted = false

    /** Feeds the current lyric line; [fallback] is the track title and the track-change key. */
    fun update(line: String?, fallback: String?, playing: Boolean) {
        val resolved = flags ?: run { record("skip reason=unsupported"); return }
        val now = SystemClock.elapsedRealtime()
        throttle = FlymeLyricThrottle.onTrackChanged(throttle, fallback, now)
        if (!playing) {
            record("skip reason=not-playing")
            clearTicker(resolved)
            return
        }
        val text = line?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }
        if (text == null) {
            record("skip reason=no-line")
            clearTicker(resolved)
            return
        }
        if (text == activeLine) return
        if (!FlymeLyricThrottle.canPost(throttle, now)) {
            record("skip reason=throttled")
            return
        }
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

    private fun ensureChannel(channelId: String = CHANNEL_ID, importance: Int = NotificationManager.IMPORTANCE_HIGH) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(channelId, context.getString(R.string.ticker_channel_name), importance).apply {
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

    /** Writes to both sinks: the general diagnostic line and the append-only copy the Settings sheet reads. */
    private fun record(detail: String) {
        runCatching { log?.record("解析", "stage=flyme-lyric $detail") }
        runCatching {
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            log?.recordDiagnostic("[$timestamp] stage=flyme-lyric $detail")
        }
    }

    /** Settings → 播放 → 测试状态栏歌词: four recipes, one every 2.5 s, each variant logged. */
    fun runSelfTest(onDone: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val resolved = flags ?: Flags(
            FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
            FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
        )
        record("test start supported=${flags != null}")
        var index = 0
        val step = object : Runnable {
            override fun run() {
                if (index >= FlymeLyricNotificationRules.TestVariant.entries.size) {
                    record("test done")
                    runCatching { onDone() }
                    return
                }
                val variant = FlymeLyricNotificationRules.TestVariant.entries[index]
                index++
                record("test variant=${variant.label}")
                runCatching { postTestVariant(resolved, variant, "测试歌词 · 云音") }
                handler.postDelayed(this, 2_500L)
            }
        }
        handler.post(step)
    }

    private fun postTestVariant(resolved: Flags, variant: FlymeLyricNotificationRules.TestVariant, text: String) {
        if (variant.minImportance) ensureChannel(MIN_CHANNEL_ID, NotificationManager.IMPORTANCE_MIN) else ensureChannel()
        val builder = NotificationCompat.Builder(context, if (variant.minImportance) MIN_CHANNEL_ID else CHANNEL_ID)
            .setPriority(Notification.PRIORITY_MAX)
            .setSmallIcon(if (variant.label.startsWith("C")) R.mipmap.ic_launcher else R.drawable.ic_lyric_ticker_60)
            .setTicker(text)
            .setOnlyAlertOnce(variant.onlyAlertOnce)
            .setShowWhen(false)
            .setOngoing(true)
            .setContentTitle(context.getString(R.string.app_name))
        if (variant.silent) builder.setSilent(true)
        val notification = builder.build()
        notification.flags = FlymeLyricNotificationRules.activeFlags(resolved.showTicker, resolved.updateTicker)
        notification.extras.putBoolean("ticker_icon_switch", false)
        notification.extras.putInt("ticker_icon", R.drawable.ic_ticker_note)
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        record(
            "test post id=$NOTIFICATION_ID flags=0x${notification.flags.toString(16)} silent=${variant.silent} " +
                "priority=${Notification.PRIORITY_MAX} channel=${if (variant.minImportance) MIN_CHANNEL_ID else CHANNEL_ID}",
        )
    }

    private data class Flags(val showTicker: Int, val updateTicker: Int)

    private companion object {
        const val TAG = "YunYin/TickerLyrics"
        const val NOTIFICATION_ID = FlymeLyricNotificationRules.NOTIFICATION_ID
        const val CHANNEL_ID = FlymeLyricNotificationRules.CHANNEL_ID
        const val MIN_CHANNEL_ID = "flyme_status_bar_lyrics_min"
    }
}
