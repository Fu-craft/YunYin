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
 * The shipping post and the 测试状态栏歌词 button share [buildNotification] (one code path), so “the test scrolls”
 * proves playback scrolls too.
 *
 * **No `setSilent`/`setOnlyAlertOnce`:** device evidence collected via the in-app test shows Flyme skips the ticker
 * animation for silent notifications — variant A (silent=false) scrolled while the shipping silent post did not.
 * Audibility is prevented by the already-silent channel (no sound, no vibration, no badge) and `setDefaults(0)`.
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
        resolved
    }

    fun isSupported(): Boolean = flags != null

    /** Used by the player's gate so skipped renders are still visible in the copied log. */
    fun logSkip(reason: String) = record("skip reason=$reason")

    private var throttle = FlymeLyricThrottle.State()
    private var activeLine: String? = null
    private var posted = false

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

    fun clear() {
        throttle = FlymeLyricThrottle.State()
        activeLine = null
        flags?.let { clearTicker(it) }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        posted = false
    }

    /** Settings → 播放 → 「测试状态栏歌词」: one shipping notification, same builder. */
    fun runSelfTest() {
        val resolved = flags ?: Flags(
            FlymeLyricNotificationRules.FLAG_ALWAYS_SHOW_TICKER,
            FlymeLyricNotificationRules.FLAG_ONLY_UPDATE_TICKER,
        )
        runCatching { post(resolved, "测试歌词 · 云音", test = true) }
    }

    private fun post(resolved: Flags, text: String, test: Boolean = false) {
        runCatching {
            ensureChannel()
            val notification = buildNotification(resolved, text)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            activeLine = text
            posted = true
            val prefix = if (test) "test post" else "post"
            record(
                "$prefix len=${text.length} id=$NOTIFICATION_ID flags=0x${notification.flags.toString(16)} " +
                    "silent=false priority=${Notification.PRIORITY_MAX} channel=${FlymeLyricNotificationRules.CHANNEL_ID}",
            )
        }.onFailure { Log.w(TAG, "Failed to post Flyme lyric", it) }
    }

    /** The one builder used by playback and the self-test. */
    private fun buildNotification(resolved: Flags, text: String): Notification {
        val builder = NotificationCompat.Builder(context, FlymeLyricNotificationRules.CHANNEL_ID)
            .setPriority(Notification.PRIORITY_MAX)
            .setSmallIcon(R.drawable.ic_lyric_ticker_60)
            .setTicker(text)
            .setDefaults(0)
            .setShowWhen(false)
            .setOngoing(true)
        if (activeLine == null) builder.setContentTitle(context.getString(R.string.app_name))
        val notification = builder.build()
        notification.flags = FlymeLyricNotificationRules.activeFlags(resolved.showTicker, resolved.updateTicker)
        notification.extras.putBoolean("ticker_icon_switch", false)
        notification.extras.putInt("ticker_icon", R.drawable.ic_ticker_note)
        return notification
    }

    private fun clearTicker(resolved: Flags) {
        if (!posted && activeLine == null) return
        activeLine = null
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
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            record("clear")
        }.onFailure { Log.w(TAG, "Failed to clear Flyme lyric", it) }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
        posted = false
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
        const val NOTIFICATION_ID = FlymeLyricNotificationRules.NOTIFICATION_ID
    }
}
