package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/**
 * Picks the line to show in a one-line status-bar ticker.
 *
 * Extracted and kept pure because the interesting part is the *selection*, not the string: Flyme's
 * ticker renders a single line of text, so what matters is which line is current at a given moment
 * and what its text is — and getting that wrong shows a stale or wrong lyric in the status bar, which
 * is much harder to debug on-device than here.
 */
object LyricTicker {

    /**
     * The most recently started line at [timeMs], or null before the first line begins.
     *
     * Deliberately a "latest line whose start has passed" rule rather than a `[start, end)` range
     * test. A line's end is extended to cover its accompaniment vocals, so ends overlap the next
     * line's start; a range test would keep showing the older line while the next has already begun.
     * This is the same rule the on-screen renderer uses, so the two never disagree about which line
     * is current.
     *
     * Returning null during the lead-in is intentional: there is no lyric yet, and the caller shows
     * the song title instead of inventing one.
     */
    fun lineAt(lines: List<ISyncedLine>, timeMs: Long): ISyncedLine? {
        var current: ISyncedLine? = null
        for (line in lines) {
            if (line.start <= timeMs) current = line else break
        }
        return current
    }

    /**
     * The line's own text, without its translation.
     *
     * The translation is dropped on purpose: the ticker is a single line in the status bar, so a
     * two-line string (`content\ntranslation`, which is what the clipboard copy uses) would be
     * truncated mid-way and read as a glitch.
     */
    fun contentOf(line: ISyncedLine?): String? {
        val raw: String = when (line) {
            is KaraokeLine -> line.syllables.joinToString("") { it.content }
            is SyncedLine -> line.content
            else -> return null
        }
        return raw.trim().takeIf { it.isNotEmpty() }
    }
}
