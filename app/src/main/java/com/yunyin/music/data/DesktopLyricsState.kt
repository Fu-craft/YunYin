// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.yunyin.music.core.Track
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DesktopLyricWord(val text: String, val start: Int, val end: Int)

data class DesktopLyricFrame(
    val trackId: Long = 0L,
    val title: String = "",
    val text: String = "",
    val translation: String? = null,
    val words: List<DesktopLyricWord> = emptyList(),
    val lineStart: Int = 0,
    val lineEnd: Int = 0,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
)

/** Process-local lyric frame consumed by the overlay service. Updated at the player's 4 Hz tick. */
object DesktopLyricsState {
    private val _frame = MutableStateFlow(DesktopLyricFrame())
    val frame: StateFlow<DesktopLyricFrame> = _frame

    fun publish(
        lyrics: SyncedLyrics?,
        track: Track?,
        positionMs: Long,
        isPlaying: Boolean,
        offsetMs: Long,
    ) {
        if (track == null) {
            _frame.value = DesktopLyricFrame()
            return
        }
        val clock = (positionMs - offsetMs).coerceAtLeast(0L).toInt()
        val lines = lyrics?.lines.orEmpty()
        val line = lines.lastOrNull { it.start <= clock } ?: lines.firstOrNull()
        if (line == null) {
            _frame.value = DesktopLyricFrame(trackId = track.id, title = track.name, isPlaying = isPlaying)
            return
        }
        val text = when (line) {
            is KaraokeLine -> line.syllables.joinToString("") { it.content }
            is SyncedLine -> line.content
            else -> ""
        }
        val translation = when (line) {
            is KaraokeLine -> line.translation
            is SyncedLine -> line.translation
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        val words = (line as? KaraokeLine)?.syllables.orEmpty().map {
            DesktopLyricWord(it.content, it.start, it.end)
        }
        _frame.value = DesktopLyricFrame(
            trackId = track.id,
            title = track.name,
            text = text,
            translation = translation,
            words = words,
            lineStart = line.start,
            lineEnd = line.end,
            positionMs = clock.toLong(),
            isPlaying = isPlaying,
        )
    }

    fun clear() {
        _frame.value = DesktopLyricFrame()
    }
}
