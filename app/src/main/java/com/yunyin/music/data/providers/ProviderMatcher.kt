package com.yunyin.music.data.providers

import com.yunyin.music.core.Track
import java.util.Locale

/**
 * Cross-platform song matching — "is this search result the same recording as that track?".
 *
 * ## Provenance and intent
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0), `src/utils/musicSdk/index.js` (`findMusic` / `searchMusic`)
 * and the helpers it uses (`getIntv`, `filterStr`, singer normalisation). LX uses this to retry a NetEase-gated
 * song on another platform; the same idea is what makes complaint 1 work here: find the QQ version of a NetEase
 * VIP track and play that instead.
 *
 * The reference algorithm has a long fallthrough list of "looser" comparisons. Reproduced here as an ordered set
 * of tiers, but with one deliberate guard the reference gets from its earlier passes: a candidate must have a
 * **related name** (one contains the other, normalised) and a **matching duration** (±5 s) before any tier can
 * accept it. Without that, a search for a common word would match a different song and the app would silently play
 * the wrong audio — worse than falling back to the trial.
 */
internal object ProviderMatcher {

    /** LX compares track lengths with this tolerance; different masters differ by a second or two. */
    const val INTERVAL_TOLERANCE_SECONDS = 5

    /**
     * The best candidate for [target] in [candidates], or null when none is related enough.
     *
     * Tiers are evaluated in LX's preference order; the first candidate accepted by a tier wins.
     */
    fun bestMatch(candidates: List<Track>, target: Track): Track? {
        if (candidates.isEmpty()) return null
        val targetName = normalize(target.name)
        val targetSinger = normalize(singerKey(target.artistLine))
        val targetAlbum = normalize(target.albumName)
        val targetInterval = target.providerRef?.intervalSeconds?.takeIf { it > 0 }
            ?: (target.durationMs / 1000).toInt().takeIf { it > 0 }

        // **Voice identity guard.** The weakest reference tiers match a name (or a singer/interval) alone, and
        // applying them to a raw search page can return a *cover* of the same title — measured, cover uploads sit
        // next to the original in QQ search. Playing that would be "the song plays but it is not the artist's voice",
        // which is worse than keeping the trial. So a candidate must relate on **both** name and artist (when the
        // target has an artist) and be within the duration window; the tiers below only rank the survivors.
        val related = candidates.filter { candidate ->
            val name = normalize(candidate.name)
            val singer = normalize(singerKey(candidate.artistLine))
            val interval = candidate.providerRef?.intervalSeconds ?: (candidate.durationMs / 1000).toInt()
            isNameRelated(name, targetName) &&
                isIntervalRelated(interval, targetInterval) &&
                isSingerRelated(singer, targetSinger)
        }
        if (related.isEmpty()) return null

        fun pick(predicate: (String, String, String, String, Int) -> Boolean): Track? =
            related.firstOrNull { candidate ->
                val name = normalize(candidate.name)
                val singer = normalize(singerKey(candidate.artistLine))
                val album = normalize(candidate.albumName)
                val interval = candidate.providerRef?.intervalSeconds ?: (candidate.durationMs / 1000).toInt()
                predicate(name, singer, album, targetName, interval)
            }

        // Tier order mirrors findMusic: exact combination first, single-attribute matches last.
        pick { name, singer, _, tName, interval ->
            name == tName && singer == targetSinger && interval == targetInterval
        }?.let { return it }
        pick { name, singer, album, tName, _ ->
            name == tName && singer == targetSinger && album == targetAlbum
        }?.let { return it }
        pick { name, singer, _, tName, _ -> name == tName && singer == targetSinger }?.let { return it }
        pick { name, _, _, tName, interval -> name == tName && interval == targetInterval }?.let { return it }
        pick { _, singer, _, _, interval -> singer == targetSinger && interval == targetInterval }?.let { return it }
        return pick { name, _, _, tName, _ -> name == tName || name.contains(tName) || tName.contains(name) }
    }

    /** LX's `filterStr`: lowercase, and punctuation/whitespace/brackets removed. */
    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[\\s'.,，&\"、()（）`~\\-<>|/\\]\\[!！《》〈〉「」『』]"), "")

    /** Splits multiple artists (LX treats `、&;/,,` as separators), sorts, and rejoins with `、`. */
    fun singerKey(singers: String): String {
        val parts = singers.split('、', '/', '&', ';', ',', '；', '，', '|').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size <= 1) parts.firstOrNull().orEmpty() else parts.sorted().joinToString("、")
    }

    private fun isNameRelated(candidate: String, target: String): Boolean {
        if (candidate.isEmpty() || target.isEmpty()) return false
        return candidate == target || candidate.contains(target) || target.contains(candidate)
    }

    private fun isIntervalRelated(candidateSeconds: Int, targetSeconds: Int?): Boolean {
        if (targetSeconds == null) return true
        return kotlin.math.abs(candidateSeconds - targetSeconds) <= INTERVAL_TOLERANCE_SECONDS
    }

    /**
     * Singer guard: an empty target singer imposes no constraint, otherwise one side must contain the other
     * (normalised) — so "周杰伦" matches "周杰伦、林俊杰" but never a cover credited to someone else.
     */
    private fun isSingerRelated(candidate: String, target: String): Boolean {
        if (target.isEmpty()) return true
        if (candidate.isEmpty()) return false
        return candidate == target || candidate.contains(target) || target.contains(candidate)
    }
}

/** Human labels for provider keys, used by the search badge and the diagnostic log. */
object ProviderLabels {
    fun of(provider: String): String = when (provider) {
        "tx" -> "QQ音乐"
        "kw" -> "酷我音乐"
        "kg" -> "酷狗音乐"
        "wy" -> "网易云"
        "mg" -> "咪咕音乐"
        "local" -> "本地"
        else -> provider.ifBlank { "其他" }
    }
}
