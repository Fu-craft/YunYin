// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers

import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxSourcePlayback
import com.yunyin.music.data.providers.tx.TxSearch
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Stream-URL resolution for provider tracks, and the cross-platform retry for NetEase-gated songs.
 *
 * ## The two jobs
 *
 * 1. **A provider track is played by its own catalogue.** Its [com.yunyin.music.core.ProviderRef] names the
 *    platform (`tx`) and that platform's id; [musicUrlFor] hands the source that platform's `musicInfo` and asks
 *    for a URL. There is no NetEase id involved, so this cannot go through the NetEase API.
 * 2. **A NetEase track that only streams a fragment gets a second chance elsewhere.** [otherSourceUrl] searches
 *    the other platform for the same recording (see [ProviderMatcher]) and resolves *that* track's URL. This is
 *    LX's "toggle source" behaviour and the answer to "会员歌曲还是只能试听": when the custom source's backend can
 *    serve the other platform's version, the song plays in full instead of as a 45 s fragment.
 *
 * ## Precedence
 *
 * - A track with a [com.yunyin.music.core.ProviderRef] is resolved **only** on its provider; on failure the caller
 *   may fall back to looking the song up on NetEase, but the provider is never bypassed silently.
 * - A NetEase track is resolved through the existing order (enabled `wy` custom source → NetEase API) and only if
 *   that yields a trial/unavailable result is [otherSourceUrl] attempted.
 *
 * ## Never hang, never lie
 *
 * Every branch is bounded by the source's own 12 s lookup ceiling and the search's 15 s call timeout, and every
 * outcome (match found / no match / URL refused) is written as a `provider=` block to the same diagnostics file
 * the Settings copy row reads. A failure falls through to the built-in path instead of failing playback.
 */
class ProviderPlayback(
    private val sources: LxSourcePlayback,
    private val txSearch: TxSearch,
    private val store: ProviderTrackStore,
    private val log: LxSourceLog? = null,
) {

    /** Bounded cache of cross-source matches, including misses, so a track load does not search every time. */
    private val matchCache = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Track?>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Track?>?): Boolean = size > 64
        },
    )

    /** The persisted full track for a provider id, or null when [id] is a NetEase id. */
    fun findProviderTrack(id: Long): Track? = store.find(id)

    /**
     * Whether an enabled custom source declares [provider].
     *
     * Called before resolving a provider track: a source that cannot answer for that platform would otherwise pay a
     * JS-engine call and a diagnostic-block write just to fail.
     */
    fun supports(provider: String): Boolean = sources.supports(provider)

    /**
     * Searches the other platform for [keyword], remembering every result.
     *
     * A provider outage must not fail the user's search: an error becomes an empty page and a log line, and the
     * NetEase half of the results is still shown.
     */
    suspend fun search(keyword: String, page: Int, limit: Int): NetResult<MusicSearchPage> {
        if (!sources.supports(TxSearch.PROVIDER)) return NetResult.Ok(MusicSearchPage(emptyList(), false))
        return when (val result = txSearch.search(keyword, page, limit)) {
            is NetResult.Err -> {
                trace("provider=${TxSearch.PROVIDER}", "stage=search reason=${result.message} query=$keyword")
                NetResult.Ok(MusicSearchPage(emptyList(), false))
            }
            is NetResult.Ok -> {
                result.value.tracks.forEach { store.remember(it) }
                NetResult.Ok(
                    MusicSearchPage(
                        tracks = result.value.tracks,
                        hasMore = result.value.page < result.value.allPage,
                        providers = listOf(TxSearch.PROVIDER),
                    ),
                )
            }
        }
    }

    fun remember(track: Track) {
        if (track.providerRef != null) store.remember(track)
    }

    /** The URL for a provider track, or null when no enabled source can serve its platform. */
    suspend fun musicUrlFor(track: Track, appQuality: String): String? {
        val ref = track.providerRef ?: return null
        if (!sources.supports(ref.provider)) {
            trace(
                "provider=${ref.provider} trackId=${track.id}",
                "stage=skip reason=没有启用支持 ${ProviderLabels.of(ref.provider)}（${ref.provider}）的自定义源",
            )
            return null
        }
        val musicInfo = ProviderMusicInfo.forTrack(track)
        val url = sources.musicUrlForKey(
            key = ref.provider,
            musicInfo = musicInfo,
            appQuality = appQuality,
            providerIdText = "${ref.provider}:${ref.providerId}",
            trackDurationMs = track.durationMs,
            outcomeKey = track.id,
            musicInfoSource = "provider-search",
        )
        if (url != null) store.remember(track)
        // Which identity was used, and what the result was: the previous block named the provider id only, so a
        // user report of "wrong song" could not be tied to the `musicInfo` that was actually sent.
        trace(
            "provider=${ref.provider} trackId=${track.id}",
            "stage=url provider=${ref.provider} providerId=${ref.providerId} " +
                "songId=${ref.songId ?: "-"} mediaMid=${ref.mediaMid ?: "-"} " +
                "name=${track.name} singer=${track.artistLine} interval=${track.durationMs / 1000}s " +
                "quality=$appQuality result=${if (url != null) "ok" else "failed"}" +
                (if (url != null) "" else " fallback=builtin"),
        )
        return url
    }

    /**
     * Tries to play a NetEase track from another platform when NetEase only streams a fragment.
     *
     * Returns null when no other platform is configured, no match is found, or the source refuses the URL — the
     * caller then keeps whatever the NetEase path produced.
     */
    suspend fun otherSourceUrl(track: Track, appQuality: String): String? {
        val provider = TxSearch.PROVIDER
        if (!sources.supports(provider)) return null
        val cached = matchCache[track.id]
        val match = if (matchCache.containsKey(track.id)) {
            cached
        } else {
            val found = searchOtherSource(track)
            matchCache[track.id] = found
            found
        }
        if (match == null) {
            trace(
                "provider=$provider neteaseId=${track.id}",
                "stage=match reason=没有找到同一首歌 query=${track.name} ${track.artistLine} " +
                    "interval=${track.durationMs / 1000}s",
            )
            return null
        }
        val url = musicUrlFor(match, appQuality)
        trace(
            "provider=$provider neteaseId=${track.id}",
            "stage=toggle neteaseId=${track.id} matched=${match.providerRef?.providerId} " +
                "name=${match.name} singer=${match.artistLine} interval=${match.durationMs / 1000}s " +
                "result=${if (url != null) "ok" else "failed"}",
        )
        return url
    }

    /** Exposed for tests and for a future "try another platform" UI action. */
    suspend fun searchOtherSource(track: Track): Track? {
        val query = "${track.name} ${track.artistLine}".trim()
        if (query.isEmpty()) return null
        return when (val page = txSearch.search(query, page = 1, limit = TxSearch.DEFAULT_LIMIT)) {
            is NetResult.Err -> {
                trace("provider=${TxSearch.PROVIDER} neteaseId=${track.id}", "stage=search reason=${page.message}")
                null
            }
            is NetResult.Ok -> ProviderMatcher.bestMatch(page.value.tracks, track)
        }
    }

    private fun trace(header: String, detail: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching { log?.recordDiagnostic("[$timestamp] $header\n  $detail") }
    }
}
