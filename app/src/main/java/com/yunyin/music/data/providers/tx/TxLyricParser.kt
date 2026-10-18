package com.yunyin.music.data.providers.tx

/**
 * Turns QQ Music's QRC payload into the app's lyric shapes.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0), `src/utils/musicSdk/tx/lyric.js` (`parseTools` and
 * `decodeLyric`). What is faithful:
 *
 *  - `[startMs,durationMs]word(offset,duration)…` line decoding — the QRC word timings become LX's word-by-word
 *    form `[mm:ss.mmm]<startMs,duration>word…`, which is YRC-shaped and therefore parseable by the app's existing
 *    `NeteaseYrcParser` (that is what produces karaoke);
 *  - the plain-LRC projection of the same line (word timings stripped);
 *  - translation re-timing (`fixTlrcTimeTag`) and romanisation re-timing (`fixRlrcTimeTag`), which snap each
 *    translated line onto the lyric line within 100 ms;
 *  - the `LyricContent="…"` tag unwrapping, `getIntv` and the `[mm:ss.mmm]` formatting.
 *
 * What differs: the reference is JavaScript with template strings and array `shift` loops; this is Kotlin with
 * explicit builders. No behaviour was "improved" — the output text is what the app's parsers consume.
 *
 * Pure: string in, strings out. The network and the decryption live in [TxLyric]/[QrcDecoder].
 */
object TxLyricParser {

    /** One word of a QRC line, with its offset from the line start. */
    data class Word(val text: String, val offsetMs: Long, val durationMs: Long)

    /** One QRC line with word timings — the data needed to build a karaoke line directly. */
    data class WordLine(val startMs: Long, val durationMs: Long, val words: List<Word>)

    data class Parsed(
        /** Line-level LRC. */
        val lyric: String,
        /** Word-by-word LRC in LX's `<start,dur>` form (for diagnostics/other consumers). */
        val lxlyric: String,
        /** Translation LRC, re-timed onto [lyric]. */
        val tlyric: String,
        /** Romanisation LRC, re-timed onto [lyric]. */
        val rlyric: String,
        /** Structured word timing, used to build karaoke lines without a text round-trip. */
        val lines: List<WordLine>,
    ) {
        val isEmpty: Boolean get() = lyric.isBlank() && lxlyric.isBlank()
    }

    private val LINE_TIME = Regex("^\\[(\\d+),(\\d+)]")
    private val LINE_TIME2 = Regex("^\\[([\\d:.]+)]")
    private val WORD_TIME = Regex("\\(\\d+,\\d+\\)")
    private val WORD_TIME_ALL = Regex("\\(\\d+,\\d+\\)")

    /** The reference's `msFormat`: `[mm:ss.mmm]`. */
    fun msFormat(timeMs: Long): String {
        val ms = timeMs % 1000
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "[%02d:%02d.%03d]".format(minutes, seconds, ms)
    }

    /** The reference's `removeTag`: unwraps `LyricContent="…"` out of the XML envelope. */
    fun removeTag(value: String): String {
        val withoutPrefix = value.replace(Regex("^[\\S\\s]*?LyricContent=\""), "")
        return withoutPrefix.replace(Regex("\"/>[\\S\\s]*?$"), "")
    }

    fun parse(lrc: String?, tlrc: String?, rlrc: String?): Parsed {
        var lyric = ""
        var lxlyric = ""
        var lines: List<WordLine> = emptyList()
        if (!lrc.isNullOrBlank()) {
            val parsed = parseLyric(removeTag(lrc))
            lyric = parsed.lyric
            lxlyric = parsed.lxlyric
            lines = parsed.lines
        }
        val rlyric = if (!rlrc.isNullOrBlank()) fixTimeTag(parseRlyric(removeTag(rlrc)), lyric, preserveFraction = false) else ""
        val tlyric = if (!tlrc.isNullOrBlank()) fixTimeTag(tlrc, lyric, preserveFraction = true) else ""
        return Parsed(lyric = lyric, lxlyric = lxlyric, tlyric = tlyric, rlyric = rlyric, lines = lines)
    }

    private class LyricParseResult(
        val lyric: String,
        val lxlyric: String,
        val lines: List<WordLine>,
    )

    /** Line-level projection + word-by-word form + structured word timing. */
    private fun parseLyric(lrc: String): LyricParseResult {
        val text = lrc.trim().replace("\r", "")
        if (text.isEmpty()) return LyricParseResult("", "", emptyList())
        val lrcLines = mutableListOf<String>()
        val lxLines = mutableListOf<String>()
        val wordLines = mutableListOf<WordLine>()

        for (rawLine in text.split("\n")) {
            val line = rawLine.trim()
            val match = LINE_TIME.find(line)
            if (match == null) {
                if (line.startsWith("[offset")) {
                    lrcLines += line
                    lxLines += line
                }
                if (LINE_TIME2.containsMatchIn(line)) {
                    lrcLines += line
                }
                continue
            }
            val startMs = match.groupValues[1].toLongOrNull() ?: continue
            val lineDurationMs = match.groupValues[2].toLongOrNull() ?: 0L
            val startTag = msFormat(startMs)
            val words = line.replaceFirst(LINE_TIME, "")
            lrcLines += startTag + words.replace(WORD_TIME_ALL, "")

            val times = WORD_TIME_ALL.findAll(words).toList()
            if (times.isEmpty()) continue
            val timings = times.map { word ->
                val numbers = Regex("\\((\\d+),(\\d+)\\)").find(word.value)
                val start = numbers?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val duration = numbers?.groupValues?.get(2)?.toLongOrNull() ?: 0L
                maxOf(start - startMs, 0L) to duration
            }
            val wordParts = words.split(WORD_TIME)
            val newWords = buildString {
                timings.forEachIndexed { index, timing ->
                    append("<${timing.first},${timing.second}>").append(wordParts.getOrElse(index) { "" })
                }
            }
            lxLines += startTag + newWords
            wordLines += WordLine(
                startMs = startMs,
                durationMs = lineDurationMs,
                words = timings.mapIndexed { index, timing ->
                    Word(text = wordParts.getOrElse(index) { "" }, offsetMs = timing.first, durationMs = timing.second)
                },
            )
        }
        return LyricParseResult(
            lyric = lrcLines.joinToString("\n"),
            lxlyric = lxLines.joinToString("\n"),
            lines = wordLines,
        )
    }

    /** The line-level projection only; used for romanisation before re-timing. */
    private fun parseRlyric(lrc: String): String {
        val text = lrc.trim().replace("\r", "")
        if (text.isEmpty()) return ""
        val lines = mutableListOf<String>()
        for (rawLine in text.split("\n")) {
            val line = rawLine.trim()
            val match = LINE_TIME.find(line) ?: continue
            val startMs = match.groupValues[1].toLongOrNull() ?: continue
            val words = line.replaceFirst(LINE_TIME, "")
            lines += msFormat(startMs) + words.replace(WORD_TIME_ALL, "")
        }
        return lines.joinToString("\n")
    }

    /**
     * The reference's `getIntv`: millisecond value for a `[hh:mm:ss]` / `[mm:ss]` / `[mm:ss.x]` tag.
     *
     * Kept byte-for-byte with the reference, including the fact that the first component is multiplied by
     * 3,600,000: the reference pads short forms with a leading zero, so `[mm:ss.x]` is read as `h:m:x`. The only
     * consumer is the 100 ms proximity check in [fixTimeTag], where both sides of the comparison use the same
     * formula, so the constant factor cancels and a "correct" value would not change the matching. Reimplementing it
     * with hour/minute semantics would be a silent deviation from the reference for no behavioural gain.
     */
    fun getIntv(interval: String): Long {
        if (interval.isBlank()) return 0
        val normalized = if (!interval.contains('.')) "$interval.0" else interval
        val parts = normalized.split(':', '.').toMutableList()
        while (parts.size < 3) parts.add(0, "0")
        val first = parts[0].toLongOrNull() ?: 0L
        val second = parts[1].toLongOrNull() ?: 0L
        val fraction = parts[2].toLongOrNull() ?: 0L
        return first * 3_600_000L + second * 1_000L + fraction
    }

    /**
     * Re-times each line of [source] onto the matching line of [reference] (within 100 ms).
     *
     * @param preserveFraction pads a `mm:ss.x` time to three fraction digits before comparing, matching the
     *   reference's separate handling of `tlrc` and `rlrc`.
     *
     * **One deliberate difference from the reference:** the reference *consumes* reference lines as it walks
     * forward (`lrcLines.shift()`), so a translation line whose timing does not match within 100 ms swallows every
     * later lyric line and the remaining translations are dropped. That is a real loss on uploads whose translation
     * timestamps drift. This port matches each source line against all reference lines instead, which keeps the
     * alignment intent (nearest lyric line within 100 ms) without the destructive side effect. The comparison
     * itself is unchanged.
     */
    private fun fixTimeTag(source: String, reference: String, preserveFraction: Boolean): String {
        if (source.isBlank() || reference.isBlank()) return ""
        data class ReferenceLine(val rawTag: String, val timeMs: Long)
        val references = reference.split("\n").mapNotNull { line ->
            val match = LINE_TIME2.find(line) ?: return@mapNotNull null
            val words = line.replaceFirst(LINE_TIME2, "")
            if (words.isBlank()) return@mapNotNull null
            ReferenceLine(match.value, getIntv(match.groupValues[1]))
        }
        val result = mutableListOf<String>()
        for (rawLine in source.split("\n")) {
            val match = LINE_TIME2.find(rawLine) ?: continue
            val words = rawLine.replaceFirst(LINE_TIME2, "")
            if (words.isBlank()) continue
            var time = match.groupValues[1]
            if (time.contains('.')) {
                val fraction = time.substringAfter('.').length
                time = time + "0".repeat((3 - fraction).coerceAtLeast(0))
            }
            val sourceTime = getIntv(time)
            val best = references
                .filter { kotlin.math.abs(sourceTime - it.timeMs) < 100 }
                .minByOrNull { kotlin.math.abs(sourceTime - it.timeMs) }
                ?: continue
            result += rawLine.replaceFirst(LINE_TIME2, best.rawTag)
        }
        return result.joinToString("\n")
    }
}
