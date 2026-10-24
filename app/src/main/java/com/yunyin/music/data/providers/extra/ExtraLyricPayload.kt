// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers.extra

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable

/** One timed word from Kuwo's `<start,dur>` form or Kugou's KRC tags. */
data class ExtraWord(
    val text: String,
    /** Offset from [ExtraWordLine.startMs]. */
    val offsetMs: Long,
    val durationMs: Long,
)

/** One lyric line with optional word timing. Empty [words] means a line-level line. */
data class ExtraWordLine(
    val startMs: Long,
    val durationMs: Long,
    val words: List<ExtraWord>,
)

/**
 * A lyric payload from a non-QQ provider (Kuwo/Kugou).
 *
 * [plain] is always an LRC string when any line text exists, so a caller without word support still gets readable
 * lyrics; [lines] carries the word timing when the provider supplied it. [translation] is LRC text re-timed onto the
 * same lines, suitable for the app's existing `LyricTranslation.merge`.
 */
data class ExtraLyricPayload(
    val provider: String,
    val plain: String?,
    val translation: String?,
    val lines: List<ExtraWordLine>,
) {
    val hasWords: Boolean get() = lines.any { it.words.isNotEmpty() }

    val isEmpty: Boolean get() = plain.isNullOrBlank() && lines.isEmpty()
}

/**
 * Builds the app's karaoke model straight from provider word timings — the same reasoning as
 * [com.yunyin.music.data.providers.ProviderLyricDocument]: a text round-trip would have to invent a conversion
 * format, while the structured timings are exactly what the renderer needs.
 */
internal object ExtraLyricDocument {

    fun build(payload: ExtraLyricPayload): SyncedLyrics? {
        val karaoke = payload.lines.mapNotNull { line ->
            if (line.words.isEmpty()) return@mapNotNull null
            val syllables = line.words
                .filter { it.text.isNotBlank() }
                .map { word ->
                    KaraokeSyllable(
                        content = word.text,
                        start = (line.startMs + word.offsetMs).toInt(),
                        end = (line.startMs + word.offsetMs + word.durationMs).toInt(),
                        phonetic = null,
                    )
                }
            if (syllables.isEmpty()) return@mapNotNull null
            val end = maxOf(line.startMs + line.durationMs, syllables.maxOf { it.end.toLong() })
            KaraokeLine.MainKaraokeLine(
                syllables = syllables,
                translation = null,
                alignment = KaraokeAlignment.Start,
                start = line.startMs.toInt(),
                end = end.toInt(),
                phonetic = null,
                accompanimentLines = emptyList(),
            )
        }
        if (karaoke.isEmpty()) return null
        return SyncedLyrics(lines = karaoke)
    }

    /** Formats [ms] as an LRC timestamp, for the plain fallback and translation merge. */
    fun timeTag(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val minutes = clamped / 60_000
        val seconds = (clamped % 60_000) / 1000
        val millis = clamped % 1000
        return "%02d:%02d.%03d".format(minutes, seconds, millis)
    }
}
