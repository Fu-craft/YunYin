package com.yunyin.music.ui.download

import androidx.compose.runtime.compositionLocalOf
import com.yunyin.music.core.Track
import com.yunyin.music.data.download.TrackDownloadState

/**
 * Download state + actions for the player surface.
 *
 * Passed through a [compositionLocalOf] instead of being threaded through every player composable: the player screen
 * already takes ~25 parameters, and a download is orthogonal to how the chrome is laid out. It also keeps the frozen
 * chrome untouched — the download control is rendered by the player screen itself, next to the existing heart.
 */
class DownloadController(
    val states: Map<Long, TrackDownloadState>,
    val onDownload: (Track) -> Unit,
    val onCancel: (Long) -> Unit,
) {
    fun stateOf(track: Track?): TrackDownloadState? = track?.let { states[it.id] }

    /** A tap starts / retries a download, or cancels one that is already running. */
    fun toggle(track: Track) {
        val state = states[track.id]
        if (state?.isActive == true) onCancel(track.id) else onDownload(track)
    }
}

val LocalDownloadController = compositionLocalOf<DownloadController?> { null }
