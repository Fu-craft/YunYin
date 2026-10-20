package com.yunyin.music.data.providers.extra

import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.providers.ProviderMatcher
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The fallback lyric sources beyond NetEase and QQ Music: Kuwo then Kugou.
 *
 * ## Why search, not ids
 *
 * Kuwo and Kugou are keyed by their own ids/hashes and the app's tracks are NetEase tracks, so each source is asked
 * by `title + artist` and the result is run through [ProviderMatcher] — the same name/artist/±5s relation guards used
 * for QQ, including the "never accept a cover by a different artist" rule. The resolved candidate is cached per track,
 * so repeat loads only fetch the lyric document.
 *
 * ## Bounds and precedence
 *
 * Kuwo is tried first (it exposes word timings for most tracks we probed), then Kugou. Each source has a
 * [SOURCE_TIMEOUT_MS] cap and the whole attempt has a [TOTAL_BUDGET_MS] deadline, so a slow provider cannot hang the
 * lyric panel. `requireWords` skips a plain-only answer when the caller is looking for a karaoke upgrade.
 *
 * ## Diagnostics
 *
 * Every attempt records `stage=lyric provider=… match=… verdict=…` (plus line/word counts) to the shared log, which is
 * what makes a "still no lyrics" report diagnosable.
 */
class ExtraLyricsProvider(
    private val kw: KwLyric = KwLyric(),
    private val kg: KgLyric = KgLyric(),
    private val log: LxSourceLog? = null,
) {

    private val kwCandidates = object : LinkedHashMap<Long, Track>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Track>?): Boolean = size > 16
    }

    private val kgCandidates = object : LinkedHashMap<Long, Track>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Track>?): Boolean = size > 16
    }

    /** The first usable document from Kuwo, then Kugou; null means neither source matched. */
    suspend fun fetch(track: Track, requireWords: Boolean = false): ExtraLyricPayload? {
        if (track.name.isBlank() || track.artists.isEmpty()) return null
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS
        fetchKw(track, deadline)
            ?.takeIf { !requireWords || it.hasWords }
            ?.let { return it }
        fetchKg(track, deadline)
            ?.takeIf { !requireWords || it.hasWords }
            ?.let { return it }
        return null
    }

    private suspend fun fetchKw(track: Track, deadline: Long): ExtraLyricPayload? {
        val remaining = minOf(SOURCE_TIMEOUT_MS, deadline - System.currentTimeMillis())
        if (remaining <= 0L) return null
        return withTimeoutOrNull(remaining) {
            val candidate = kwCandidates[track.id] ?: run {
                val keyword = "${track.name} ${track.artistLine}"
                val results = kw.search(keyword)
                val match = ProviderMatcher.bestMatch(results, track)
                if (match == null) {
                    trace(track, KW, "match=none candidates=${results.size} verdict=skip")
                } else {
                    kwCandidates[track.id] = match
                    trace(track, KW, "match=ok candidates=${results.size}")
                }
                match
            } ?: return@withTimeoutOrNull null
            val songId = candidate.providerRef?.songId?.toLongOrNull() ?: return@withTimeoutOrNull null
            val payload = kw.fetch(songId)
            trace(
                track,
                KW,
                "match=ok songId=$songId verdict=${if (payload == null) "empty" else "ok"} " +
                    "lines=${payload?.lines?.size ?: 0} words=${payload?.lines?.sumOf { it.words.size } ?: 0}",
            )
            payload
        }
    }

    private suspend fun fetchKg(track: Track, deadline: Long): ExtraLyricPayload? {
        val remaining = minOf(SOURCE_TIMEOUT_MS, deadline - System.currentTimeMillis())
        if (remaining <= 0L) return null
        return withTimeoutOrNull(remaining) {
            val candidate = kgCandidates[track.id] ?: run {
                val keyword = "${track.name} ${track.artistLine}"
                val results = kg.search(keyword)
                val match = ProviderMatcher.bestMatch(results, track)
                if (match == null) {
                    trace(track, KG, "match=none candidates=${results.size} verdict=skip")
                } else {
                    kgCandidates[track.id] = match
                    trace(track, KG, "match=ok candidates=${results.size}")
                }
                match
            } ?: return@withTimeoutOrNull null
            val hash = candidate.providerRef?.providerId ?: return@withTimeoutOrNull null
            val payload = kg.fetch(hash, "${candidate.name} ${candidate.artistLine}", candidate.durationMs)
            trace(
                track,
                KG,
                "match=ok hash=$hash verdict=${if (payload == null) "empty" else "ok"} " +
                    "lines=${payload?.lines?.size ?: 0} words=${payload?.lines?.sumOf { it.words.size } ?: 0}",
            )
            payload
        }
    }

    private fun trace(track: Track, provider: String, detail: String) {
        runCatching {
            log?.record(
                "解析",
                "stage=lyric provider=$provider trackId=${track.id} title=${track.name.take(40)} $detail",
            )
        }
    }

    companion object {
        const val KW = "kw"
        const val KG = "kg"

        /** Per-source cap; both providers answer within a few seconds on healthy networks. */
        const val SOURCE_TIMEOUT_MS = 6_000L

        /** Whole fallback attempt, so the panel cannot wait on Kuwo + Kugou serially beyond this. */
        const val TOTAL_BUDGET_MS = 10_000L
    }
}
