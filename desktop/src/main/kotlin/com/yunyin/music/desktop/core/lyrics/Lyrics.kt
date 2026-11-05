package com.yunyin.music.desktop.core.lyrics

/**
 * 桌面端的歌词模型与解析。
 *
 * 与 Android 端分开维护：那边的解析建立在 `mocharealm.accompanist` 上（Android 构件），
 * 桌面用不了。这里只保留真正需要的东西——时间轴 + 原文 + 译文，以及主副行的判定规则。
 */

/** 一行歌词。[startMs] 是这一行开始的时间。 */
data class LyricLine(
    val startMs: Long,
    val content: String,
    val translation: String? = null,
)

/**
 * 解析 LRC。
 *
 * 支持一行多个时间戳（`[00:12.00][01:20.00]词`）与 1/2/3 位小数：
 * 1 位 = ×100 ms、2 位 = ×10 ms、3 位 = 毫秒。
 */
fun parseLrc(raw: String): List<LyricLine> {
    val out = mutableListOf<LyricLine>()
    val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    for (line in raw.lineSequence()) {
        val stamps = tag.findAll(line).toList()
        if (stamps.isEmpty()) continue
        val text = line.substring(stamps.last().range.last + 1).trim()
        if (text.isEmpty()) continue
        for (stamp in stamps) {
            val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
            val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
            val fraction = stamp.groupValues[3]
            val millis = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.padEnd(3, '0').take(3).toLong()
            }
            out += LyricLine(startMs = minutes * 60_000 + seconds * 1_000 + millis, content = text)
        }
    }
    return out.sortedBy { it.startMs }
}

/**
 * 解析 YRC（网易逐字歌词）。
 *
 * 行首 `[start,duration]`，之后是若干 `(wordStart,wordDuration,flags)词`。这里只要行级时间与整行文本，
 * 逐字时间留给以后的卡拉 OK 效果。
 */
fun parseYrc(raw: String): List<LyricLine> {
    val head = Regex("""^\[(\d+),(\d+)]""")
    val word = Regex("""\(\d+,\d+,-?\d+\)""")
    return raw.lineSequence().mapNotNull { line ->
        val match = head.find(line.trim()) ?: return@mapNotNull null
        val text = word.replace(line.trim().substring(match.range.last + 1), "").trim()
        if (text.isEmpty()) return@mapNotNull null
        LyricLine(startMs = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null, content = text)
    }.sortedBy { it.startMs }.toList()
}

/** 一份歌词文档：[lines] 是原文，[translation] 是另一条时间轴上的译文。 */
data class LyricDocument(
    val lines: List<LyricLine>,
    val translation: List<LyricLine> = emptyList(),
)

/**
 * 把译文挂到原文上。
 *
 * 一对一、按顺序匹配：每条译文只能被消费一次，且必须落在 [toleranceMs] 内。
 * 早期的“最近邻”匹配没有唯一性约束，双语歌里两条原文会抢同一条译文，表现为译文整体错位一行。
 */
fun mergeTranslation(
    lines: List<LyricLine>,
    translation: List<LyricLine>,
    toleranceMs: Long = 500L,
): List<LyricLine> {
    if (translation.isEmpty()) return lines
    var cursor = 0
    return lines.map { line ->
        while (cursor < translation.size && translation[cursor].startMs < line.startMs - toleranceMs) cursor++
        if (cursor >= translation.size) return@map line
        val candidate = translation[cursor]
        if (kotlin.math.abs(candidate.startMs - line.startMs) > toleranceMs) return@map line
        cursor++
        line.copy(translation = candidate.content)
    }
}

/**
 * 主行是否应该换成译文。
 *
 * 判据只看字符集：原文以汉字为主、译文以拉丁字母为主时交换。纯中文、纯英文、以及中英同行的歌词都不触发。
 */
fun shouldSwapMain(content: String, translation: String): Boolean =
    cjkRatio(content) >= 0.5f && latinRatio(content) < 0.1f &&
        latinRatio(translation) >= 0.5f && cjkRatio(translation) < 0.1f

/** 按 [shouldSwapMain] 调整主副行，返回交换后的行。 */
fun applyMainLanguage(lines: List<LyricLine>): List<LyricLine> = lines.map { line ->
    val translation = line.translation
    if (translation != null && shouldSwapMain(line.content, translation)) {
        line.copy(content = translation, translation = line.content)
    } else {
        line
    }
}

private fun cjkRatio(text: String): Float = ratio(text) { it.code in 0x4E00..0x9FFF }

private fun latinRatio(text: String): Float = ratio(text) { it.isLetter() && it.code < 0x250 }

private fun ratio(text: String, predicate: (Char) -> Boolean): Float {
    val letters = text.count { it.isLetterOrDigit() }
    if (letters == 0) return 0f
    return text.count(predicate).toFloat() / letters
}