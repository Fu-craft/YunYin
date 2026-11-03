// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.widget

import com.yunyin.music.playback.PlaybackUiState
import java.util.concurrent.atomic.AtomicReference

/**
 * What a home-screen widget needs to know, and nothing else.
 *
 * [PlaybackUiState] carries the queue, the repeat mode and a dozen other fields the widget cannot draw;
 * this is the projection of it that fits in a `RemoteViews` update, so the provider never holds a
 * reference to the player (and therefore never keeps the playback process alive by accident).
 */
data class WidgetState(
    /** Empty when nothing is loaded. */
    val title: String,
    val artist: String,
    val coverUrl: String?,
    /** `isPlaying`, i.e. audio is actually coming out — not merely "asked to play". */
    val playing: Boolean,
    /** 0..100, or 0 when the duration is unknown. */
    val progressPct: Int,
    val hasTrack: Boolean,
) {
    companion object {
        val empty = WidgetState(
            title = "",
            artist = "",
            coverUrl = null,
            playing = false,
            progressPct = 0,
            hasTrack = false,
        )

        fun from(state: PlaybackUiState): WidgetState {
            val track = state.current
            val duration = state.durationMs
            val pct = if (duration > 0L) {
                ((state.positionMs.coerceAtLeast(0L) * 100L) / duration).toInt().coerceIn(0, 100)
            } else {
                0
            }
            return WidgetState(
                title = track?.name.orEmpty(),
                artist = track?.artistLine.orEmpty(),
                coverUrl = track?.coverUrl,
                playing = state.isPlaying,
                progressPct = pct,
                hasTrack = track != null,
            )
        }
    }
}

/**
 * The last state the app published, readable by the widget provider.
 *
 * A widget broadcast can arrive when no Activity is alive, and the provider must not bind a
 * `MediaController` (or touch the playback service) just to draw a label. The app writes here whenever
 * playback changes; the provider reads it. Process death clears it, which correctly degrades to the
 * "未在播放" layout — the widget showing a stale track forever would be worse.
 */
object WidgetStateStore {
    private val state = AtomicReference(WidgetState.empty)

    val current: WidgetState
        get() = state.get()

    fun publish(value: WidgetState) {
        state.set(value)
    }
}
