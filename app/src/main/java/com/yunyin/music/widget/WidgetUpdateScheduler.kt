// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock

/**
 * The single gate every widget refresh goes through.
 *
 * A media app republishes playback state several times a second (position ticks, buffering, isPlaying
 * flaps), and each `updateAppWidget` re-inflates layouts in the launcher process. Pushing those straight
 * through is what makes widgets famous for battery complaints, so:
 *
 *  - updates are throttled to [THROTTLE_MS] unless a caller forces one (track change, play/pause);
 *  - nothing here is periodic — `updatePeriodMillis` is 0 in the provider info, and there is no timer;
 *  - a no-widget home screen costs one `getAppWidgetIds` call and returns.
 */
object WidgetUpdateScheduler {

    /** 2 updates a second is the most the launcher can show anyway; the throttle is what saves the wakeups. */
    private const val THROTTLE_MS = 500L

    private var lastRequestAt = 0L

    /**
     * Publishes [state] and refreshes every placed widget.
     *
     * [forced] bypasses the throttle — use it for a track change or a play/pause, where the widget would
     * otherwise stay visibly wrong for up to half a second.
     */
    fun publish(context: Context, state: WidgetState, forced: Boolean = false) {
        WidgetStateStore.publish(state)
        request(context, forced)
    }

    /** Refreshes the widgets from the last published state. */
    fun request(context: Context, forced: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!forced && now - lastRequestAt < THROTTLE_MS) return
        lastRequestAt = now

        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(appContext, NowPlayingWidgetProvider::class.java))
        if (ids.isEmpty()) return
        NowPlayingWidgetProvider.refresh(appContext, manager, ids)
    }
}
