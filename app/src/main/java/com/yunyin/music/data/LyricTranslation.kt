package com.yunyin.music.data

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.copy
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import kotlin.math.abs

/**
 * Attaches a translation track to parsed lyrics.
 *
 * NetEase returns translations in a separate `tlyric` document whose timestamps do **not**
 * always equal the lyric line starts — measured on real tracks, one song matched 2 of 60
 * lines by exact timestamp but 55 within 300ms. Matching by equality therefore silently
 * drops most translations (this is why English songs appeared untranslated), so lines are
 * matched to the nearest translation within [DEFAULT_TOLERANCE_MS] instead.
 *
 * Existing translations are preserved; this only fills gaps, which matters when the base
 * lyrics came from AMLL (often no translation) and the translation from NetEase.
 */
internal object LyricTranslation {

    /** Largest gap still treated as the same line, in milliseconds. */
    const val DEFAULT_TOLERANCE_MS = 500

    fun merge(
        lyrics: SyncedLyrics,
        rawTranslation: String?,
        toleranceMs: Int = DEFAULT_TOLERANCE_MS,
    ): SyncedLyrics {
        if (rawTranslation.isNullOrBlank()) return lyrics
        val entries = parse(rawTranslation)
        if (entries.isEmpty()) return lyrics

        val starts = entries.keys.sorted()
        val merged = lyrics.lines.map { line ->
            if (line.translation != null) return@map line
            val match = nearestWithin(starts, line.start, toleranceMs)
                ?.let { entries[it] }
                ?.takeIf { it.isNotBlank() }
                ?: return@map line

            when (line) {
                is KaraokeLine -> line.copy(translation = match)
                is SyncedLine -> line.copy(translation = match)
                else -> line
            }
        }
        return lyrics.copy(lines = merged)
    }

    /**
     * Parses a translation payload into timestamp → text.
     *
     * Two shapes arrive here, and both must work:
     *
     *  - **LRC** — `[mm:ss.xx]text`. Handles multiple timestamps on one line (a repeated chorus line is
     *    emitted as `[00:12.00][01:40.00]text`) and the 1/2/3-digit fractional-second variants.
     *  - **YRC** — `[lineStart,lineDuration](wordStart,wordDuration,?)word…`. This is the shape of
     *    NetEase's `ytlrc` (the translation aligned to `yrc`), and it is what the repository prefers
     *    for karaoke songs precisely because its timings line up exactly with the lyric lines.
     *
     * The YRC branch was missing, and the consequence was silent: the LRC pattern matched nothing in a
     * `ytlrc` payload, so this returned an empty map, [merge] did nothing, and **every karaoke song
     * lost its translation** — despite the repository having deliberately selected the better-aligned
     * source for it. Measured on the repository's own sample: `parse(ytlrc)` returned 0 entries.
     */
    fun parse(raw: String): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        val lrcTag = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        // `[start,duration]` at the head of a YRC line; `%d,%d` cannot be confused with `mm:ss`.
        val yrcHead = Regex("""^\[(\d+),(\d+)]""")
        val yrcWord = Regex("""\(\s*\d+\s*,\s*\d+\s*(?:,\s*-?\d+\s*)?\)""")

        raw.lineSequence().forEach { line ->
            val head = yrcHead.find(line.trim())
            if (head != null) {
                val start = head.groupValues[1].toIntOrNull() ?: return@forEach
                // Strip every `(wordStart,wordDuration[,flags])` group, leaving just the text.
                val text = yrcWord.replace(line.substring(head.range.last + 1), "").trim()
                if (text.isNotEmpty()) out[start] = text
                return@forEach
            }

            val tags = lrcTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            // Everything after the last tag is the text (tags may be stacked).
            val text = line.substringAfterLast(']').trim()
            if (text.isEmpty()) return@forEach
            tags.forEach { tag ->
                val minutes = tag.groupValues[1].toIntOrNull() ?: return@forEach
                val seconds = tag.groupValues[2].toIntOrNull() ?: return@forEach
                val frac = tag.groupValues[3]
                val millis = when (frac.length) {
                    0 -> 0
                    1 -> frac.toInt() * 100
                    2 -> frac.toInt() * 10
                    else -> frac.take(3).toInt()
                }
                out[minutes * 60_000 + seconds * 1_000 + millis] = text
            }
        }
        return out
    }

    /** Binary search for the closest value in [sorted] within [tolerance]. */
    fun nearestWithin(sorted: List<Int>, target: Int, tolerance: Int): Int? {
        if (sorted.isEmpty()) return null
        var low = 0
        var high = sorted.size - 1
        while (low < high) {
            val mid = (low + high) / 2
            if (sorted[mid] < target) low = mid + 1 else high = mid
        }
        val best = listOfNotNull(sorted.getOrNull(low), sorted.getOrNull(low - 1))
            .minByOrNull { abs(it - target) } ?: return null
        return best.takeIf { abs(it - target) <= tolerance }
    }

    /** Reads a line's translation without a type test at each call site. */
    private val com.mocharealm.accompanist.lyrics.core.model.ISyncedLine.translation: String?
        get() = when (this) {
            is KaraokeLine -> translation
            is SyncedLine -> translation
            else -> null
        }
}
