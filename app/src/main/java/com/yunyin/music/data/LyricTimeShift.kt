package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlin.math.abs

/**
 * Aligns lyrics that were timed to a different audio edit than the one being streamed.
 *
 * Word-by-word lyrics (AMLL TTML) and the NetEase lyric can describe **different edits** of
 * the same song. Measured example: for one track the AMLL document begins at 927ms while the
 * NetEase lyric begins at 5790ms — a ~4.9s difference. Because the app prefers AMLL, that
 * track's lyrics ran seconds ahead of the audio, so lines began before the intro had ended.
 * Other tracks agreed to within tens of milliseconds, which is why the symptom came and went
 * with the song.
 *
 * The fix is a global time shift: the two documents describe the same performance, just
 * offset, so moving every timestamp by the difference re-aligns the word timing to the audio
 * we actually play while preserving all syllable-level detail.
 */
internal object LyricTimeShift {

    /**
     * Below this disagreement the two sources are considered the same edit and nothing is
     * shifted, so ordinary sub-second timing differences are left alone.
     */
    const val ALIGNMENT_TOLERANCE_MS = 1500

    /**
     * Credit/metadata detection now lives in [LyricMetadata], which is shared with the code that
     * strips those lines before display. Keeping one rule in one place matters: when the anchor
     * logic and the display logic disagreed, the timing was correct while the lyrics still showed
     * a credit line as the first lyric.
     */
    fun isCreditLine(text: String): Boolean = LyricMetadata.isCreditLine(text)

    /**
     * Picks the first line of [lines] that can serve as an alignment anchor.
     *
     * Returns the first timestamped line that is not a credit line. Falls back to the very first
     * timestamped line when every line looks like a credit (so a document that is *only* credits
     * still yields something), and to null when there are no usable timestamps at all.
     */
    fun firstAnchorStartMs(lines: List<Pair<Int, String?>>): Int? {
        if (lines.isEmpty()) return null
        val firstNonCredit = lines.firstOrNull { (_, text) -> !isCreditLine(text.orEmpty()) }
        return (firstNonCredit ?: lines.first()).first
    }

    /**
     * Whether a loaded document's timing is trustworthy enough to cache.
     *
     * A NetEase document *is* the reference, so it never needs an external one. An AMLL document
     * does: it carries no hint of which edit it was timed to, so without a reference it can run
     * seconds ahead of the audio — which is exactly the "lyrics start before the intro" symptom.
     * Returning such a document is still acceptable (better than showing nothing), but caching it
     * would pin the wrong timing for the rest of the session. This is the check that stops that.
     *
     * [referenceResolved] separates "NetEase answered and simply has no lyric for this track" (a
     * genuinely absent reference, so there is nothing better to wait for) from "the reference
     * could not be fetched", which is a gap worth retrying on the next load.
     */
    fun isAlignmentTrustworthy(
        referenceMs: Int?,
        fromAmll: Boolean,
        referenceResolved: Boolean,
    ): Boolean = referenceMs != null || !fromAmll || referenceResolved

    /**
     * Aligns [lyrics] using explicit anchors on both sides.
     *
     * Preferred over [alignTo] when the chosen document may itself open with credits: [alignTo]
     * assumes the document's *first* line is comparable to the reference, which is not true for a
     * NetEase lyric that starts with "作词 : …". Here the caller supplies each side's first *sung*
     * line, so unlike lines are never compared against each other.
     */
    fun alignByAnchors(
        lyrics: SyncedLyrics,
        currentAnchorMs: Int?,
        referenceAnchorMs: Int?,
        toleranceMs: Int = ALIGNMENT_TOLERANCE_MS,
    ): SyncedLyrics {
        val current = currentAnchorMs ?: return lyrics
        val reference = referenceAnchorMs ?: return lyrics
        val delta = reference - current
        if (abs(delta) <= toleranceMs) return lyrics
        return shift(lyrics, delta)
    }

    /**
     * Shifts [lyrics] in time so that their first line matches [referenceFirstLineStartMs].
     *
     * Returns the lyrics unchanged when the sources already agree, or when either side has no
     * usable timestamp. Never shifts by a partial amount: alignment is a single global offset.
     */
    fun alignTo(
        lyrics: SyncedLyrics,
        referenceFirstLineStartMs: Int?,
        toleranceMs: Int = ALIGNMENT_TOLERANCE_MS,
    ): SyncedLyrics {
        val current = lyrics.lines.firstOrNull()?.start ?: return lyrics
        val reference = referenceFirstLineStartMs ?: return lyrics

        val delta = reference - current
        if (abs(delta) <= toleranceMs) return lyrics

        return shift(lyrics, delta)
    }

    /** Moves every timestamp in [lyrics] forward by [deltaMs] (negative moves it earlier). */
    fun shift(lyrics: SyncedLyrics, deltaMs: Int): SyncedLyrics {
        if (deltaMs == 0) return lyrics

        val shifted = lyrics.lines.mapNotNull { line ->
            when (line) {
                is KaraokeLine.MainKaraokeLine -> {
                    val syllables = line.syllables.shift(deltaMs)
                    if (syllables.isEmpty()) return@mapNotNull null
                    line.copy(
                        // Clamped so a large negative shift cannot produce negative times.
                        start = (line.start + deltaMs).coerceAtLeast(0),
                        end = (line.end + deltaMs).coerceAtLeast(0),
                        syllables = syllables,
                        accompanimentLines = line.accompanimentLines?.mapNotNull { acc ->
                            val accSyllables = acc.syllables.shift(deltaMs)
                            if (accSyllables.isEmpty()) {
                                null
                            } else {
                                acc.copy(
                                    start = (acc.start + deltaMs).coerceAtLeast(0),
                                    end = (acc.end + deltaMs).coerceAtLeast(0),
                                    syllables = accSyllables,
                                )
                            }
                        },
                    )
                }

                is KaraokeLine.AccompanimentKaraokeLine -> {
                    val syllables = line.syllables.shift(deltaMs)
                    if (syllables.isEmpty()) {
                        null
                    } else {
                        line.copy(
                            start = (line.start + deltaMs).coerceAtLeast(0),
                            end = (line.end + deltaMs).coerceAtLeast(0),
                            syllables = syllables,
                        )
                    }
                }

                is SyncedLine -> {
                    val start = (line.start + deltaMs).coerceAtLeast(0)
                    val end = (line.end + deltaMs).coerceAtLeast(0)
                    // A line shifted entirely before zero would have nothing to show.
                    if (end <= 0) null else line.copy(start = start, end = end)
                }

                else -> line
            }
        }

        return lyrics.copy(lines = shifted.sortedBy { it.start })
    }

    private fun List<KaraokeSyllable>.shift(deltaMs: Int): List<KaraokeSyllable> =
        mapNotNull { syllable ->
            val start = (syllable.start + deltaMs).coerceAtLeast(0)
            val end = (syllable.end + deltaMs).coerceAtLeast(0)
            if (end <= start) null else syllable.copy(start = start, end = end)
        }
}
