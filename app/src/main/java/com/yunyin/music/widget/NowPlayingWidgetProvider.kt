// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Bundle
import android.util.LruCache
import android.view.KeyEvent
import android.widget.RemoteViews
import coil3.request.allowHardware
import coil3.toBitmap
import com.yunyin.music.MainActivity
import com.yunyin.music.R
import java.util.concurrent.Executors

/**
 * 桌面小组件 — now playing, with transport controls, in three shapes.
 *
 * ## Why RemoteViews
 *
 * The rest of the UI is Compose, but a widget cannot host a Compose composition without shipping Glance
 * (a second rendering model, a second theme system, another dependency). Three XML layouts plus this
 * provider is the whole cost, and it keeps the widget's look under direct control.
 *
 * ## Where the data comes from
 *
 * Not from the player. A widget broadcast can arrive with no Activity alive, and binding a
 * `MediaController` — or worse, starting the playback service — just to draw a title would wake a process
 * the user did not ask for. The app publishes a [WidgetState] into [WidgetStateStore] whenever playback
 * changes; this provider only reads it, which is why it can render instantly and cheaply.
 *
 * ## Transport
 *
 * The buttons dispatch **media keys** through `AudioManager`. That routes to whichever media session
 * currently holds the focus — ours, whenever the widget is showing its own track — without this process
 * needing a player handle, a token, or a service binding.
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // A resize can cross a layout threshold (4×2 ↔ 4×1 ↔ 2×2), so the shape is re-decided here.
        refresh(context, manager, intArrayOf(appWidgetId))
    }

    override fun onEnabled(context: Context) {
        // Nothing to register: refreshes are pushed by the app, never polled. Paint the initial frame.
        WidgetUpdateScheduler.request(context, forced = true)
    }

    override fun onDisabled(context: Context) {
        // Symmetry for onEnabled — there is no listener to unregister (updatePeriodMillis is 0 and no
        // receiver is registered dynamically), so this only clears the cached cover.
        coverCache.evictAll()
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ACTION_NEXT -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            ACTION_PREV -> sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> {
                super.onReceive(context, intent)
                return
            }
        }
        // The media key is asynchronous: the session will report back and the app will publish a new
        // state. Refresh once anyway so an optimistic icon flip is not needed and nothing looks stuck.
        WidgetUpdateScheduler.request(context, forced = true)
    }

    private fun sendMediaKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.yunyin.music.widget.TOGGLE"
        const val ACTION_NEXT = "com.yunyin.music.widget.NEXT"
        const val ACTION_PREV = "com.yunyin.music.widget.PREV"

        /** Extra read by [MainActivity] to open the player page instead of the last tab. */
        const val EXTRA_OPEN_PLAYER = "com.yunyin.music.widget.OPEN_PLAYER"

        private const val RC_TOGGLE = 1
        private const val RC_NEXT = 2
        private const val RC_PREV = 3
        private const val RC_OPEN = 4

        /** Covers are small and few; a 4-entry cache stops every throttled refresh re-decoding the same art. */
        private val coverCache = LruCache<String, Bitmap>(6)

        private val io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "widget-update").apply { isDaemon = true }
        }

        fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (ids.isEmpty()) return
            val state = WidgetStateStore.current
            val appContext = context.applicationContext
            io.execute {
                runCatching {
                    val cover = state.coverUrl?.let { loadCover(appContext, it) }
                    ids.forEach { id ->
                        val views = buildViews(appContext, manager, id, state, cover)
                        manager.updateAppWidget(id, views)
                    }
                }
            }
        }

        /**
         * Picks the shape that fits the host's current cell size.
         *
         * One provider rather than three: the launcher's picker would otherwise list three identical
         * entries, and this way a resize is a re-layout instead of a delete-and-re-add.
         */
        private fun layoutFor(manager: AppWidgetManager, id: Int): Int {
            val options = manager.getAppWidgetOptions(id)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return when {
                minHeight in 1..100 -> R.layout.widget_now_playing_4x1
                minWidth in 1..180 -> R.layout.widget_now_playing_2x2
                else -> R.layout.widget_now_playing_4x2
            }
        }

        private fun buildViews(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            state: WidgetState,
            cover: Bitmap?,
        ): RemoteViews {
            val layout = layoutFor(manager, id)
            val views = RemoteViews(context.packageName, layout)
            val title = if (state.hasTrack) state.title else context.getString(R.string.widget_idle)
            val artist = if (state.hasTrack) state.artist else ""

            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_artist, artist)

            if (cover != null) {
                views.setImageViewBitmap(R.id.widget_cover, cover)
            } else {
                views.setImageViewResource(R.id.widget_cover, R.drawable.ic_ticker_note_white)
            }

            views.setImageViewResource(
                R.id.widget_play,
                if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setContentDescription(
                R.id.widget_play,
                context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play),
            )
            views.setOnClickPendingIntent(R.id.widget_play, pending(context, ACTION_TOGGLE, RC_TOGGLE))

            if (layout == R.layout.widget_now_playing_4x2) {
                views.setOnClickPendingIntent(R.id.widget_prev, pending(context, ACTION_PREV, RC_PREV))
                views.setOnClickPendingIntent(R.id.widget_next, pending(context, ACTION_NEXT, RC_NEXT))
                // Discrete only: `setProgressBar(..., false)` never animates, and the app throttles how
                // often this value is pushed, so the widget cannot turn into a per-frame repaint.
                views.setProgressBar(R.id.widget_progress, 100, state.progressPct, false)
            }

            // Cover and text open the player page, not merely the app.
            val open = openIntent(context)
            views.setOnClickPendingIntent(R.id.widget_cover, open)
            views.setOnClickPendingIntent(R.id.widget_title, open)
            views.setOnClickPendingIntent(R.id.widget_artist, open)
            return views
        }

        private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, NowPlayingWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_PLAYER, true)
            }
            return PendingIntent.getActivity(
                context,
                RC_OPEN,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Coil, synchronously on the update thread — the cache means this is rare. */
        private fun loadCover(context: Context, url: String): Bitmap? {
            coverCache.get(url)?.let { return it }
            val bitmap = runCatching {
                val request = coil3.request.ImageRequest.Builder(context)
                    .data(url)
                    .size(192)
                    .allowHardware(false)
                    .build()
                // `execute` is suspending; we are already on the widget's IO thread, so the block is
                // confined to it rather than borrowing a dispatcher for one decode.
                val result = kotlinx.coroutines.runBlocking {
                    coil3.SingletonImageLoader.get(context).execute(request)
                }
                (result as? coil3.request.SuccessResult)?.image?.toBitmap()
            }.getOrNull() ?: return null
            coverCache.put(url, bitmap)
            return bitmap
        }
    }
}
