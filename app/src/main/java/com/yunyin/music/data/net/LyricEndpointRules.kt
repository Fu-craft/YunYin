package com.yunyin.music.data.net

/**
 * When to fall back from `/lyric/new` to the legacy `/lyric` endpoint.
 *
 * Live probe against the deployed API (id=186016):
 *  - `/lyric`      → clean line-level LRC (3 629 bytes, `code=200`)
 *  - `/lyric/new`  → 4 707 bytes whose `lrc` is actually a **mixed YRC/JSON** payload and whose `yrc` field is
 *                    **absent** on this deployment
 *
 * So "new" is not a superset here: a track whose yrc is missing and whose lrc is the mixed form (or empty) is better
 * served by legacy `/lyric`, which returns the plain LRC the parser understands. A genuinely lyricless track is
 * reported as such instead of being indistinguishable from a failure.
 */
object LyricEndpointRules {

    /** The `/lyric/new` lrc field contains per-word JSON objects like `{"t":0,"c":[...]}`. */
    fun isMixedWordFormat(lrc: String?): Boolean =
        lrc != null && (lrc.contains("{\"t\":") || lrc.contains("\"c\":["))

    fun hasLines(lrc: String?, yrc: String?): Boolean =
        !lrc.isNullOrBlank() || !yrc.isNullOrBlank()

    /**
     * Legacy fallback is needed when the new endpoint says nolyric, when it has neither a usable lrc nor yrc, or
     * when the only lrc it returned is the mixed word-format data the line parser cannot consume.
     */
    fun needsLegacyFallback(nolyric: Boolean, lrc: String?, yrc: String?): Boolean {
        if (nolyric) return true
        if (!yrc.isNullOrBlank()) return false
        return lrc.isNullOrBlank() || isMixedWordFormat(lrc)
    }

    /** Honest empty-state text: lyricless is a fact; anything else is "not found (yet)". */
    fun emptyMessage(nolyric: Boolean): String =
        if (nolyric) "该歌曲没有歌词" else "暂无歌词"
}
