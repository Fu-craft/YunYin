package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine

/**
 * Turns a lyric line into the text to put on the clipboard.
 *
 * Extracted and unit-tested because the interesting part is not the string but the *decisions*: what
 * a line's text actually is differs by line type, and there are several empty-ish cases that should
 * copy nothing rather than a blank line.
 */
object LyricClipboard {

    /**
     * The copyable text for [line], or null when there is nothing worth copying.
     *
     * Syllables are concatenated with no separator on purpose: in karaoke lyrics a syllable's
     * content already carries its own spacing, so joining with spaces (or a newline) would insert
     * gaps the lyric does not have.
     *
     * A translation, when present, goes on a second line — copying a line of lyrics without its
     * translation would be losing half of what is on screen.
     */
    fun textFor(line: ISyncedLine): String? {
        val content = when (line) {
            is KaraokeLine -> line.syllables.joinToString("") { it.content }
            is SyncedLine -> line.content
            else -> null
        }?.trim()

        if (content.isNullOrEmpty()) return null

        val translation = when (line) {
            is KaraokeLine -> line.translation
            is SyncedLine -> line.translation
            else -> null
        }?.trim()

        return if (translation.isNullOrEmpty()) content else "$content\n$translation"
    }
}
