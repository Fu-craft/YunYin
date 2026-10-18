package com.yunyin.music.data.providers

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.yunyin.music.data.providers.tx.TxLyric

/**
 * Builds the app's lyric model straight from QRC word timings.
 *
 * ## Why not a text round-trip
 *
 * The obvious route would be to feed the provider's word-by-word string into one of the app's parsers. Verified
 * against a live QQ payload, that does not work: LX's word form is `[mm:ss.mmm]<startMs,duration>word`, while the
 * app's `NeteaseYrcParser` expects NetEase's `(start,duration,0)` tuples and its `LyricifySyllableParser` expects
 * `word(start,duration)` — both returned **0 karaoke lines** on the real payload, so the song would have fallen back
 * to plain lyrics even though perfect word timing was available.
 *
 * Building [KaraokeLine]s from the structured timings avoids inventing a conversion format, keeps the word
 * boundaries exactly as QRC reports them, and is pure (no Android), so it is unit-testable with synthetic lines.
 *
 * The caller still runs the result through the existing credit-line stripping and translation merge, so a provider
 * document renders under the same rules as a NetEase one.
 */
internal object ProviderLyricDocument {

    /** A karaoke document from QRC word lines, or null when the payload has no word timing. */
    fun build(parsed: TxLyric.Parsed): SyncedLyrics? {
        if (parsed.lines.isEmpty()) return null
        val lyricLines = parsed.lines.mapNotNull { line ->
            val syllables = line.words
                .filter { it.durationMs > 0L || it.text.isNotBlank() }
                .map { word ->
                    KaraokeSyllable(
                        content = word.text,
                        start = (line.startMs + word.offsetMs).toInt(),
                        end = (line.startMs + word.offsetMs + word.durationMs).toInt(),
                        phonetic = null,
                    )
                }
            if (syllables.isEmpty()) return@mapNotNull null
            val end = maxOf(
                line.startMs + line.durationMs,
                syllables.maxOf { it.end.toLong() },
            )
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
        if (lyricLines.isEmpty()) return null
        return SyncedLyrics(lines = lyricLines)
    }
}
