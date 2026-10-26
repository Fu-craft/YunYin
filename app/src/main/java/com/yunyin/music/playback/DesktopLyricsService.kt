// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.yunyin.music.AppContainer
import com.yunyin.music.MainActivity
import com.yunyin.music.data.DesktopLyricFrame
import com.yunyin.music.data.DesktopLyricsState
import com.yunyin.music.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Floating desktop lyrics overlay. It never takes focus and ignores touches outside its bounds. */
class DesktopLyricsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lyricView: TextView? = null
    private var translationView: TextView? = null
    private var collectionJob: Job? = null
    private var lastPlayingAt = 0L
    private var locked = false
    private var notificationShown = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        lastPlayingAt = SystemClock.elapsedRealtime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限才能显示桌面歌词", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureNotification()
        if (root == null) {
            if (!addOverlay()) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        root?.let { view ->
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(Motion.SmallDuration.toLong()).start()
        }
        startCollection()
        return START_STICKY
    }

    private fun ensureNotification() {
        if (notificationShown) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "桌面歌词",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "显示在其他应用上方的歌词" },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DesktopLyricsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("桌面歌词")
            .setContentText("歌词显示在其他应用上方")
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "关闭", stop)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        notificationShown = true
    }

    private fun addOverlay(): Boolean {
        val wm = windowManager ?: return false
        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels - 32 * metrics.density).toInt()
            .coerceAtMost((640 * metrics.density).toInt())
            .coerceAtLeast((240 * metrics.density).toInt())
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(185, 12, 12, 16))
            }
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val lock = control("锁定") { toggleLock() }
        val close = control("关闭") { stopFromOverlay() }
        strip.addView(lock)
        strip.addView(close)
        val lyric = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            maxLines = 2
        }
        val translation = TextView(this).apply {
            setTextColor(Color.argb(210, 255, 255, 255))
            textSize = 13f
            typeface = Typeface.DEFAULT
            maxLines = 2
            visibility = View.GONE
        }
        container.addView(strip)
        container.addView(lyric)
        container.addView(translation)
        lyricView = lyric
        translationView = translation
        val layout = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels * 0.16f).toInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        container.setOnTouchListener { _, event ->
            if (locked) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = startX + (event.rawX - downX).toInt()
                    layout.y = startY + (event.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(container, layout) }
                    true
                }
                else -> false
            }
        }
        return runCatching {
            wm.addView(container, layout)
            root = container
            params = layout
            true
        }.getOrElse {
            Toast.makeText(this, "无法创建桌面歌词浮窗", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun control(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(Color.argb(210, 255, 255, 255))
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        minWidth = dp(44)
        minHeight = dp(44)
        setOnClickListener { onClick() }
    }

    private fun toggleLock() {
        locked = !locked
        root?.alpha = if (locked) 0.78f else 1f
    }

    private fun startCollection() {
        if (collectionJob != null) return
        collectionJob = scope.launch {
            DesktopLyricsState.frame.collect { frame ->
                if (frame.isPlaying) lastPlayingAt = SystemClock.elapsedRealtime()
                render(frame)
                if (!frame.isPlaying &&
                    com.yunyin.music.data.DesktopLyricsRules.shouldStop(
                        playing = frame.isPlaying,
                        elapsedSincePlayingMs = SystemClock.elapsedRealtime() - lastPlayingAt,
                    )
                ) {
                    stopFromOverlay()
                }
            }
        }
    }

    private fun render(frame: DesktopLyricFrame) {
        val lyric = lyricView ?: return
        val translation = translationView ?: return
        if (frame.text.isBlank()) {
            lyric.text = frame.title.ifBlank { "桌面歌词" }
            translation.visibility = View.GONE
            return
        }
        if (frame.words.isNotEmpty()) {
            val builder = SpannableStringBuilder()
            frame.words.forEach { word ->
                val start = builder.length
                builder.append(word.text)
                val color = if (frame.positionMs >= word.start) Color.WHITE else Color.argb(150, 255, 255, 255)
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    builder.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            lyric.setText(builder, TextView.BufferType.SPANNABLE)
        } else {
            lyric.text = frame.text
        }
        val t = frame.translation
        translation.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!t.isNullOrBlank()) translation.text = t
    }

    private fun stopFromOverlay() {
        runCatching { AppContainer.from(this).settings.desktopLyricsEnabled = false }
        sendBroadcast(Intent(ACTION_CLOSED).setPackage(packageName))
        stopSelf()
    }

    override fun onDestroy() {
        collectionJob?.cancel()
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
        windowManager = null
        scope.cancel()
        DesktopLyricsState.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "com.yunyin.music.action.DESKTOP_LYRICS_START"
        const val ACTION_STOP = "com.yunyin.music.action.DESKTOP_LYRICS_STOP"
        const val ACTION_CLOSED = "com.yunyin.music.action.DESKTOP_LYRICS_CLOSED"
        private const val CHANNEL = "desktop_lyrics"
        private const val NOTIFICATION_ID = 9103
    }
}
