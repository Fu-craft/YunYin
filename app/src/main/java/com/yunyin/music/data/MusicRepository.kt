// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data

import com.yunyin.music.core.HomeFeed
import com.yunyin.music.core.ArtistInfo
import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.data.lxsource.LxResolveCacheRules
import com.yunyin.music.data.lxsource.LxSourceKey
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.lxsource.LxTrackMemory
import com.yunyin.music.data.lxsource.LxSourcePlayback
import com.yunyin.music.data.lxsource.ResolutionConsultPolicy
import com.yunyin.music.data.lxsource.ResolutionCache
import com.yunyin.music.data.net.NeteaseClient
import com.yunyin.music.data.net.LoadedPlaylistPage
import com.yunyin.music.data.net.PlaylistLoadPlan
import com.yunyin.music.data.net.PlaylistPagingRules
import com.yunyin.music.data.playlist.PlaylistTrackCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.yunyin.music.data.providers.MusicSearchPage
import com.yunyin.music.data.providers.ProviderMatcher
import com.yunyin.music.data.providers.ProviderPlayback
import com.yunyin.music.data.providers.SearchMerge
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-side facade over [NeteaseClient].
 *
 * Groups the per-endpoint calls into the shapes the screens consume, and hides the
 * fact that some sections require a signed-in account (those fail soft to empty).
 */
class MusicRepository(
    private val client: NeteaseClient,
    /**
     * Custom-source playback, or null when the feature is not wired.
     *
     * The constructor default keeps the repository testable without an Android context: a unit test constructs
     * `MusicRepository(client)` and exercises the built-in path unchanged.
     */
    private val lxPlayback: LxSourcePlayback? = null,
    /**
     * Built-in provider SDK (search on another platform) plus cross-platform playback, or null when not wired.
     *
     * Not to be confused with [lxPlayback]: this path searches *itself* (pure Kotlin, no JS) and only uses a
     * custom source for the final URL of a track that belongs to another catalogue.
     */
    private val providers: ProviderPlayback? = null,
    /** Diagnostics sink: resolution timing lines (`stage=resolve`) go to the same trail as source lookups. */
    private val log: LxSourceLog? = null,
    /**
     * Local device audio index. A local track's synthetic id resolves to its `content://` URI; null in unit tests and
     * in builds where local music is not wired.
     */
    private val localMusic: com.yunyin.music.data.local.LocalMusicStore? = null,
    /** Memory+disk cache for playlist track lists; null in unit tests. */
    private val playlistCache: PlaylistTrackCache? = null,
    /**
     * Which catalogue search prefers — `netease`, `qq`, or `both` (see `SettingsStore.musicSource`).
     *
     * A lambda rather than a value: the setting can change while the process runs, and the repository
     * is a singleton.
     */
    private val musicSource: () -> String = { "both" },
) {

    /**
     * Assembles the Discover tab.
     *
     * Sections are independent, so they are fetched concurrently and each is allowed
     * to fail on its own: a logged-out user still sees featured playlists and charts.
     */
    suspend fun loadHome(): NetResult<HomeFeed> = coroutineScope {
        val featured = async { client.personalizedPlaylists(limit = 10).listOrEmpty() }
        val daily = async { client.dailySongs().listOrEmpty() }
        val recommended = async { client.recommendedPlaylists().listOrEmpty() }
        val fresh = async { client.newSongs(limit = 10).listOrEmpty() }
        val charts = async { client.toplists().listOrEmpty() }

        val featuredPlaylists = featured.await()
        val feed = HomeFeed(
            featuredPlaylists = featuredPlaylists,
            dailySongs = daily.await(),
            recommendedPlaylists = recommended.await().ifEmpty { featuredPlaylists },
            newSongs = fresh.await(),
            charts = charts.await().take(12),
        )
        if (feed.featuredPlaylists.isEmpty() && feed.charts.isEmpty()) {
            NetResult.Err("无法连接音乐服务")
        } else {
            NetResult.Ok(feed)
        }
    }

    /**
     * Search both catalogues.
     *
     * NetEase's own results come first and keep the app's existing behaviour (artwork hydration). The built-in
     * provider SDK ([providers]) is searched **alongside** it for the same keyword and its results are appended
     * with a platform badge — they carry the other platform's id, so they are playable even when NetEase does not
     * have the song at all, or only as a fragment.
     *
     * A provider failure is not a search failure: its half becomes empty and the log records why.
     *
     * @param offset page offset in NetEase's units; the provider page is derived from it so both advance together.
     */
    suspend fun search(keyword: String, offset: Int = 0): NetResult<MusicSearchPage> = coroutineScope {
        val providerPage = (offset / SEARCH_PAGE_SIZE) + 1
        // 「仅网易云」 must not pay for a QQ request at all, and 「QQ 优先」 puts the other catalogue first.
        val source = com.yunyin.music.data.normalizeMusicSource(musicSource())
        val includeProviders = source != com.yunyin.music.data.MUSIC_SOURCE_NETEASE
        val neteaseDeferred = async { client.search(keyword, limit = SEARCH_PAGE_SIZE, offset = offset) }
        val providerDeferred = async {
            if (!includeProviders) {
                NetResult.Ok(MusicSearchPage(emptyList(), hasMore = false))
            } else {
                providers?.search(keyword, page = providerPage, limit = SEARCH_PAGE_SIZE)
                    ?: NetResult.Ok(MusicSearchPage(emptyList(), hasMore = false))
            }
        }
        when (val netease = neteaseDeferred.await()) {
            is NetResult.Err -> {
                // NetEase failing must not hide a working provider half, but if both are empty the search failed.
                val providerResult = providerDeferred.await()
                val providerTracks = (providerResult as? NetResult.Ok)?.value?.tracks.orEmpty()
                if (providerTracks.isEmpty()) {
                    netease
                } else {
                    NetResult.Ok(MusicSearchPage(providerTracks, hasMore = false, providers = listOf("tx")))
                }
            }
            is NetResult.Ok -> {
                // Search returns no artwork, so hydrate the page in a single extra call.
                val byId = client.songDetail(netease.value.map { it.id }).listOrEmpty().associateBy { it.id }
                // Preserve search ordering, backfilling anything detail omitted.
                val neteaseTracks = netease.value.map { byId[it.id] ?: it }
                val providerPageResult = providerDeferred.await()
                val providerTracks = (providerPageResult as? NetResult.Ok)?.value?.tracks.orEmpty()
                val providerHasMore = (providerPageResult as? NetResult.Ok)?.value?.hasMore ?: false
                providerTracks.forEach { providers?.remember(it) }
                NetResult.Ok(
                    MusicSearchPage(
                        // 「QQ 音乐优先」 flips the merge so the other catalogue leads; dedupe is symmetric.
                        tracks = if (source == com.yunyin.music.data.MUSIC_SOURCE_QQ) {
                            SearchMerge.merge(providerTracks, neteaseTracks)
                        } else {
                            SearchMerge.merge(neteaseTracks, providerTracks)
                        },
                        hasMore = neteaseTracks.size >= SEARCH_PAGE_SIZE || providerHasMore,
                        providers = if (providerTracks.isEmpty()) emptyList() else listOf("tx"),
                    ),
                )
            }
        }
    }

    suspend fun hotSearch(): List<String> = client.hotSearch().listOrEmpty()
    /**
     * Resolves a stream URL, or null when the track is unavailable. Trials pass through.
     *
     * ## One resolution per track, shared by both callers
     *
     * A track load resolves its URL twice: once for ExoPlayer's data source and once for the member-preview
     * check. When a custom source is enabled, two independent resolutions meant two source requests that could
     * disagree — the audio could come from the source while the trial check fell back and showed a trial notice.
     * Per `(track, quality)` single-flight plus a short cache ([LxResolveCacheRules.TTL_MS]) makes both callers
     * share one answer, so the trial state always describes the URL that is actually playing.
     *
     * ## Order: 自定义源 first, then the built-in route
     *
     * An enabled custom source exists because the built-in route can fail (region locks, VIP-only tracks,
     * removed songs), so it is asked first when one claims the 网易云 key. Every failure inside it — no source,
     * script missing, init failed, handler error, timeout, a preview clip — comes back as `null` with a
     * diagnostic block, and this falls through to the existing NetEase call. A broken source can therefore cost a
     * little latency but can never leave the player silent.
     */
    /**
     * Resolves a stream URL, or null when the track is unavailable. Trials pass through.
     *
     * ## Cache and single-flight
     *
     * One track load resolves the URL **twice** (ExoPlayer data source + trial check), so per `(track, quality)`
     * single-flight keeps both callers on one answer. The result is also cached — successful URLs for
     * [LxResolveCacheRules.SUCCESS_TTL_MS], failures for [LxResolveCacheRules.NEGATIVE_TTL_MS] — in a bounded LRU.
     * A playback error invalidates the track's entries ([invalidateResolution]), so an expired CDN URL is never
     * served twice. Re-playing or seeking back to a track therefore costs no network hop at all.
     *
     * ## NetEase first, custom source only when it can add something
     *
     * The old order asked the custom source first and only then NetEase: for a track NetEase serves directly, the
     * source's network hop + JS-engine call was pure latency on the critical path. Now:
     *  - no enabled `wy` source, a provider track whose provider the source does not declare, or a cached answer ⇒
     *    the source is **not consulted at all**;
     *  - with a `wy` source, NetEase is asked first and given [FAST_NETEASE_WINDOW_MS] to return a **full** URL.
     *    A full answer wins and the source is never called;
     *  - if NetEase answers with a trial/unavailable result, or is slower than the window, the source lookup runs
     *    concurrently with the remaining NetEase wait and the **first full URL wins** (the loser is cancelled; an
     *    in-flight HTTP request is cheap to drop). A trial is never accepted over a full URL;
     *  - when only a trial is available, the existing cross-platform toggle (QQ match) still runs after.
     */
    suspend fun streamUrl(id: Long, quality: String): NeteaseClient.SongUrl? {
        // Local device audio: the MediaStore content URI is the stream. No network, no cache, no quality tiers.
        localMusic?.uriFor(id)?.let { uri ->
            recordResolution(id, quality, "local", System.nanoTime(), "result=uri")
            return NeteaseClient.SongUrl(url = uri)
        }
        val key = LxResolveCacheRules.key(id, quality)
        cachedResolution(key, id, quality)?.let { return it }
        if (resolveLocks.size > MAX_LOCK_ENTRIES) resolveLocks.clear()
        return resolveLocks.computeIfAbsent(key) { Mutex() }.withLock {
            // Re-check inside the lock: the other caller may have filled the cache while this one waited.
            cachedResolution(key, id, quality)?.let { return@withLock it }
            val started = System.nanoTime()
            val value = resolveStreamUrl(id, quality)
            synchronized(resolutionCache) {
                resolutionCache.put(key, ResolvedBox(value), System.currentTimeMillis())
            }
            recordResolution(id, quality, "total", started, "result=${describe(value)} cached=false")
            value
        }
    }

    /**
     * Drops cached resolutions for [trackId] (all qualities). Called when the player reports an error for the track:
     * the cached URL is then assumed expired/refused and the next attempt resolves fresh.
     */
    fun invalidateResolution(trackId: Long) {
        synchronized(resolutionCache) {
            resolutionCache.removeWhere { key -> key.startsWith("$trackId:") }
        }
    }

    private suspend fun resolveStreamUrl(id: Long, quality: String): NeteaseClient.SongUrl? {
        val started = System.nanoTime()

        // 1. A track from another platform has no NetEase id; its provider is authoritative.
        val providerTrack = providers?.findProviderTrack(id)
        if (providerTrack?.providerRef != null) {
            val provider = providerTrack.providerRef.provider
            // Skip the source entirely when it does not declare this provider: the lookup cannot succeed, and the
            // skip avoids building musicInfo + writing a trace for a source that cannot answer.
            if (providers?.supports(provider) == true) {
                val url = providers.musicUrlFor(providerTrack, quality)
                if (!url.isNullOrBlank()) {
                    recordResolution(id, quality, "provider", started, "provider=$provider result=url")
                    return NeteaseClient.SongUrl(url = url)
                }
            } else {
                recordResolution(id, quality, "provider", started, "provider=$provider skip=source-unsupported")
            }
            // The source refused it or cannot serve it. Look the song up on NetEase so the track can still play.
            val fallback = neteaseMatchFor(providerTrack)?.let { client.songUrl(it.id, quality).valueOrNull() }
            recordResolution(id, quality, "provider-fallback", started, "result=${describe(fallback)}")
            return fallback
        }

        val sourceEnabled = runCatching { lxPlayback?.supports(LxSourceKey.WY.wire) == true }.getOrDefault(false)
        if (!ResolutionConsultPolicy.needsSource(sourceEnabled, netease = null)) {
            val netease = client.songUrl(id, quality).valueOrNull()
            val final = toggleIfTrial(id, quality, netease)
            recordResolution(id, quality, "netease", started, "source=disabled result=${describe(final)}")
            return final
        }

        return coroutineScope {
            val netease = async { client.songUrl(id, quality).valueOrNull() }
            // Fast window: a NetEase-served track never pays for a source round trip.
            val fast = withTimeoutOrNull(FAST_NETEASE_WINDOW_MS) { netease.await() }
            if (fast != null && isFull(fast)) {
                recordResolution(id, quality, "netease-fast", started, "result=url")
                return@coroutineScope fast
            }
            val source = async { runCatching { lxPlayback?.musicUrl(id, quality) }.getOrNull() }
            val chosen: NeteaseClient.SongUrl? = if (fast != null) {
                // NetEase already answered but it is a trial/unavailable: the source may still have the full song.
                val sourceUrl = withTimeoutOrNull(MAX_RACE_WAIT_MS) { source.await() }
                if (!sourceUrl.isNullOrBlank()) {
                    netease.cancel()
                    NeteaseClient.SongUrl(url = sourceUrl)
                } else {
                    toggleIfTrial(id, quality, fast)
                }
            } else {
                // NetEase is slower than the window: race the source against the remaining NetEase wait.
                val race = raceFull(netease, source)
                when (race.winner) {
                    RaceWinner.SOURCE -> netease.cancel()
                    RaceWinner.NETEASE -> source.cancel()
                    RaceWinner.NONE -> {
                        netease.cancel()
                        source.cancel()
                    }
                }
                race.value
            }
            val final = if (chosen != null && isFull(chosen)) chosen else toggleIfTrial(id, quality, chosen)
            recordResolution(
                id,
                quality,
                "race",
                started,
                "neteaseFast=${fast != null} sourceStarted=true result=${describe(final)}",
            )
            final
        }
    }

    private suspend fun toggleIfTrial(
        id: Long,
        quality: String,
        netease: NeteaseClient.SongUrl?,
    ): NeteaseClient.SongUrl? {
        // NetEase only has a fragment (or nothing) → try the same song on another platform before accepting it.
        // This is the answer to "会员歌曲还是只能试听": a full version elsewhere beats a 45 s preview.
        if (netease != null && !netease.isTrial && !netease.unavailable) return netease
        val track = LxTrackMemory.find(id) ?: return netease
        val otherUrl = providers?.otherSourceUrl(track, quality)
        return if (!otherUrl.isNullOrBlank()) NeteaseClient.SongUrl(url = otherUrl) else netease
    }

    /** First full URL of the two racing lookups; trials only when neither produced a full one. */
    private suspend fun raceFull(
        netease: Deferred<NeteaseClient.SongUrl?>,
        source: Deferred<String?>,
    ): RaceResult {
        val first = select<RaceEvent> {
            netease.onAwait { RaceEvent.Netease(it) }
            source.onAwait { RaceEvent.Source(it) }
        }
        return when (first) {
            is RaceEvent.Source -> {
                if (!first.url.isNullOrBlank()) {
                    RaceResult(NeteaseClient.SongUrl(url = first.url), RaceWinner.SOURCE)
                } else {
                    val neteaseValue = withTimeoutOrNull(MAX_RACE_WAIT_MS) { netease.await() }
                    RaceResult(neteaseValue, RaceWinner.NETEASE)
                }
            }
            is RaceEvent.Netease -> {
                if (isFull(first.value)) {
                    RaceResult(first.value, RaceWinner.NETEASE)
                } else {
                    val sourceUrl = withTimeoutOrNull(MAX_RACE_WAIT_MS) { source.await() }
                    if (!sourceUrl.isNullOrBlank()) {
                        RaceResult(NeteaseClient.SongUrl(url = sourceUrl), RaceWinner.SOURCE)
                    } else {
                        RaceResult(first.value, RaceWinner.NETEASE)
                    }
                }
            }
        }
    }

    private fun recordResolution(id: Long, quality: String, path: String, startedNs: Long, detail: String) {
        val elapsed = (System.nanoTime() - startedNs) / 1_000_000
        runCatching {
            log?.record(
                "解析",
                "stage=resolve track=$id quality=$quality path=$path elapsedMs=$elapsed $detail",
            )
        }
    }

    private fun cachedResolution(key: String, id: Long, quality: String): NeteaseClient.SongUrl? {
        val box = synchronized(resolutionCache) {
            resolutionCache.get(key, System.currentTimeMillis()) { candidate ->
                if (candidate.value == null) LxResolveCacheRules.NEGATIVE_TTL_MS else LxResolveCacheRules.SUCCESS_TTL_MS
            }
        } ?: return null
        recordResolution(id, quality, "cache-hit", System.nanoTime(), "result=${describe(box.value)} cached=true")
        return box.value
    }

    private fun isFull(song: NeteaseClient.SongUrl?): Boolean =
        song != null && song.playable && !song.isTrial && !song.unavailable

    private fun describe(song: NeteaseClient.SongUrl?): String = when {
        song == null -> "none"
        song.unavailable -> "unavailable"
        song.isTrial -> "trial"
        else -> "url"
    }

    private class ResolvedBox(val value: NeteaseClient.SongUrl?)

    private sealed interface RaceEvent {
        data class Netease(val value: NeteaseClient.SongUrl?) : RaceEvent
        data class Source(val url: String?) : RaceEvent
    }

    private enum class RaceWinner { NETEASE, SOURCE, NONE }

    private class RaceResult(val value: NeteaseClient.SongUrl?, val winner: RaceWinner)

    /** A NetEase track whose title/artist/album/duration match a track from another platform, if one exists. */
    private suspend fun neteaseMatchFor(track: Track): Track? {
        if (track.name.isBlank()) return null
        val candidates = client.search("${track.name} ${track.artistLine}", limit = 10).listOrEmpty()
        return ProviderMatcher.bestMatch(candidates, track)
    }

    private val resolutionCache = ResolutionCache<ResolvedBox>()

    private val resolveLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun playlist(id: Long): Playlist? = client.playlistDetail(id).valueOrNull()

    /**
     * Loads a playlist's **full** track list, progressively.
     *
     * `/playlist/track/all` used to be called once with `limit = 200`, so larger playlists were silently cut (the
     * "566 tracks only shows 200" report). Now the complete `trackIds` list is read from `/playlist/detail`, the
     * objects are fetched in [com.yunyin.music.data.net.PlaylistPagingRules.TRACK_BATCH]-sized batches, and the result
     * is rebuilt in id order. [onBatch] receives the ordered list after every batch, so the screen renders the first
     * rows immediately instead of waiting for the whole playlist.
     *
     * If the id list is unavailable, a bounded paging loop over `/playlist/track/all` is used as a fallback
     * ([PlaylistPagingRules.MAX_PAGES]/[PlaylistPagingRules.MAX_TRACKS]) with a fresh cache-bust per page. Loading
     * fewer rows than the header's `trackCount` is acceptable (deleted/unavailable entries) and logged.
     */
    /**
     * Loads a playlist cheaply and completely.
     *
     * Measured on the deployed instance (1267-track playlist): the old path spent **2.54 MB / 6.6 s** on
     * `/playlist/detail` just for `trackIds`, then ~1 MB/2.6 s per 500-id `/song/detail` batch. The new path never
     * calls the heavy detail endpoint: a **200-row first page** (0.42 MB / 2.2 s) renders immediately, then the
     * remaining pages (500 rows each, at most [PlaylistLoadPlan.MAX_CONCURRENT] in flight) are fetched and assembled
     * in offset order. Re-entering a playlist hits the memory/disk cache unless [forceRefresh].
     */
    suspend fun playlistTracksProgressive(
        id: Long,
        totalHint: Int = 0,
        forceRefresh: Boolean = false,
        onBatch: (List<Track>) -> Unit = {},
    ): List<Track> {
        val startedNs = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedNs) / 1_000_000
        var firstRowsMs = -1L
        val cache = playlistCache
        if (!forceRefresh) {
            cache?.get(id)?.let { cached ->
                firstRowsMs = elapsedMs()
                onBatch(cached)
                log?.record(
                    "解析",
                    "stage=playlist-load id=$id loaded=${cached.size} total=$totalHint cached=true " +
                        "firstRowsMs=$firstRowsMs totalMs=${elapsedMs()}",
                )
                return cached
            }
        }

        val plan = java.util.ArrayList(PlaylistLoadPlan.plan(if (totalHint > 0) totalHint else PlaylistLoadPlan.FIRST_PAGE))
        val pages = java.util.TreeMap<Int, List<Track>>()
        var requests = 0
        var bytes = 0
        var requestMs = 0L
        var failedPages = 0
        val assembleStart = System.nanoTime()

        fun record(page: LoadedPlaylistPage) {
            requests++
            bytes += page.bytes
            requestMs += page.durationMs
            pages[page.offset] = page.tracks
            val assembled = PlaylistLoadPlan.contiguous(pages, plan)
            if (assembled.isNotEmpty()) {
                if (firstRowsMs < 0L) firstRowsMs = elapsedMs()
                onBatch(assembled)
            }
        }

        suspend fun fetch(spec: PlaylistLoadPlan.Page) {
            val first = client.playlistTracksPageMeasured(id, spec.limit, spec.offset)
            when (first) {
                is NetResult.Ok -> record(first.value)
                is NetResult.Err -> {
                    // One transient retry; a second failure leaves a gap (logged, never loops).
                    when (val retry = client.playlistTracksPageMeasured(id, spec.limit, spec.offset)) {
                        is NetResult.Ok -> record(retry.value)
                        is NetResult.Err -> failedPages++
                    }
                }
            }
        }

        // Stage 1: the first page alone, so rows render before the rest is requested.
        fetch(plan[0])

        // Stage 2: the remainder.
        if (totalHint <= 0) {
            // Unknown size: sequential pages until a short page (bounded by the hard caps).
            var offset = PlaylistLoadPlan.FIRST_PAGE
            var pageIndex = 1
            while (pageIndex < PlaylistLoadPlan.MAX_PAGES) {
                val spec = PlaylistLoadPlan.Page(offset, PlaylistLoadPlan.PAGE)
                val result = client.playlistTracksPageMeasured(id, spec.limit, spec.offset)
                if (result is NetResult.Err) {
                    failedPages++
                    break
                }
                val page = (result as NetResult.Ok).value
                plan += spec
                record(page)
                pageIndex++
                if (page.tracks.size < spec.limit) break
                offset += page.tracks.size
            }
        } else {
            coroutineScope {
                plan.drop(1).chunked(PlaylistLoadPlan.MAX_CONCURRENT).forEach { window ->
                    window.map { spec -> async { fetch(spec) } }.awaitAll()
                }
            }
        }

        val assembled = PlaylistLoadPlan.contiguous(pages, plan)
        val capped = plan.size >= PlaylistLoadPlan.MAX_PAGES && totalHint > PlaylistLoadPlan.MAX_TRACKS
        if (assembled.isNotEmpty()) cache?.put(id, assembled)
        log?.record(
            "解析",
            "stage=playlist-load id=$id loaded=${assembled.size} total=$totalHint requests=$requests " +
                "bytes=$bytes requestMs=$requestMs assembleMs=${(System.nanoTime() - assembleStart) / 1_000_000} " +
                "firstRowsMs=$firstRowsMs totalMs=${elapsedMs()} failedPages=$failedPages capped=$capped",
        )
        return assembled
    }

    suspend fun playlistTracks(id: Long): List<Track> = playlistTracksProgressive(id)

    // ---------------------------------------------------------------- playlist management
    //
    // Thin wrappers over the mutation endpoints. UI callers get a NetResult and turn a failure into a specific
    // message; the session/ownership rules live next to the callers (PlaylistOwnership) so a guest never attempts a
    // write in the first place.

    suspend fun createPlaylist(name: String, description: String, isPrivate: Boolean = false): NetResult<Long> =
        client.createPlaylist(name, description, isPrivate)

    suspend fun updatePlaylist(id: Long, name: String, description: String): NetResult<Unit> =
        client.updatePlaylist(id, name, description)

    suspend fun deletePlaylist(id: Long): NetResult<Unit> = client.deletePlaylist(id)

    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>): NetResult<Unit> =
        client.addTracksToPlaylist(playlistId, trackIds)

    suspend fun removeTracksFromPlaylist(playlistId: Long, trackIds: List<Long>): NetResult<Unit> =
        client.removeTracksFromPlaylist(playlistId, trackIds)

    /** The signed-in user's playlists. Errors remain distinguishable from a genuinely empty library. */
    suspend fun userPlaylists(uid: Long): NetResult<List<Playlist>> = client.userPlaylists(uid)

    /**
     * The account's own liked songs, as a result rather than a bare list.
     *
     * A `NetResult` on purpose: the caller (the liked-songs repository) has to tell "the account has no
     * cloud likes" from "the request failed", because only the first may be counted as zero. Collapsing
     * both to an empty list is what would make an un-liked song reappear or a count understate.
     */
    suspend fun cloudLikedTracks(uid: Long): NetResult<List<Track>> {
        if (uid == 0L) return NetResult.Ok(emptyList())
        return when (val ids = client.likedSongIds(uid)) {
            is NetResult.Err -> ids
            is NetResult.Ok -> if (ids.value.isEmpty()) {
                NetResult.Ok(emptyList())
            } else {
                client.songsByIds(ids.value)
            }
        }
    }

    suspend fun artistTopSongs(artistId: Long): List<Track> = client.artistTopSongs(artistId).listOrEmpty()

    suspend fun searchSuggest(keyword: String): NetResult<com.yunyin.music.data.net.SearchSuggestions> =
        client.searchSuggest(keyword)

    suspend fun searchLyric(keyword: String): NetResult<List<Track>> = client.searchLyric(keyword)

    suspend fun playlistCategories(): NetResult<List<String>> = client.playlistCategories()

    suspend fun topPlaylists(cat: String, offset: Int): NetResult<com.yunyin.music.data.net.PlaylistSquarePage> =
        client.topPlaylists(cat, offset)

    suspend fun likeSong(id: Long, like: Boolean): NetResult<Unit> = client.likeSong(id, like)

    suspend fun subscribe(kind: String, id: Long, follow: Boolean): NetResult<Unit> =
        client.subscribe(kind, id, follow)

    suspend fun albumSubscribed(id: Long): NetResult<Boolean> = client.albumSubscribed(id)

    suspend fun personalFm(): NetResult<List<Track>> = client.personalFm()

    suspend fun hotComments(songId: Long): NetResult<List<com.yunyin.music.core.Comment>> =
        client.hotComments(songId)

    suspend fun comments(songId: Long, offset: Int, limit: Int = 20): NetResult<com.yunyin.music.core.CommentsPage> =
        client.comments(songId, offset, limit)

    /** 心动模式 queue for a song inside a playlist (requires a logged-in session on the instance). */
    suspend fun beatMode(songId: Long, startSongId: Long, playlistId: Long?): NetResult<List<Track>> =
        client.beatMode(songId, startSongId, playlistId)

    suspend fun artistDetail(artistId: Long): ArtistInfo? = client.artistDetail(artistId).valueOrNull()

    suspend fun artistSongsPage(artistId: Long, offset: Int, limit: Int, order: String = "time") =
        client.artistSongsPage(artistId, offset, limit, order).valueOrNull()

    suspend fun artistAlbumsPage(artistId: Long, offset: Int, limit: Int) =
        client.artistAlbumsPage(artistId, offset, limit).valueOrNull()

    suspend fun albumTracks(albumId: Long): List<Track> = client.albumTracks(albumId).listOrEmpty()

    /** The artist's name and cover, for the artist page's header. */
    suspend fun artistInfo(artistId: Long): ArtistInfo? =
        client.artistDetail(artistId).valueOrNull()

    /** Resolves an artist *name* to the id its page needs; null when the search finds no such artist. */
    suspend fun artistIdFor(name: String): Long? = client.artistIdByName(name)

    /**
     * The cover URL for [id].
     *
     * Search results are mapped without artwork (`/search` does not return an album object), so a
     * track the user just found and played can carry a null cover. Asking for the detail fills that
     * in, which is what makes saving the cover work from every entry point rather than only from the
     * shelves that happen to include one.
     */
    suspend fun coverUrlFor(id: Long): String? =
        client.songDetail(listOf(id)).valueOrNull()?.firstOrNull()?.coverUrl

    private companion object {
        /** Both catalogues page in this size, so one offset drives both. */
        const val SEARCH_PAGE_SIZE = 30

        /**
         * How long NetEase gets to answer with a **full** URL before the custom source is started concurrently.
         *
         * Long enough for the built-in API's normal round trip (typically 100–400 ms from this app's network), short
         * enough that a slow NetEase cannot make a source-served track wait for it. A trial/unavailable answer does not
         * need to wait for the window — it starts the source immediately.
         */
        const val FAST_NETEASE_WINDOW_MS = 450L

        /** Once one side of the race has won, the other is awaited at most this long for a better answer. */
        const val MAX_RACE_WAIT_MS = 8_000L

        /** Bound on per-key mutexes; clearing is safe (at worst one duplicate resolution). */
        const val MAX_LOCK_ENTRIES = 64

    }
}

/**
 * Unwraps a list result, treating any failure as "no items".
 *
 * The liked-list logic deliberately does *not* use this: it needs failures to stay visible (see
 * `cloudLikedTracks`). It is for the callers where "no items" is the right degradation.
 */
internal fun <T> NetResult<List<T>>.listOrEmpty(): List<T> = when (this) {
    is NetResult.Ok -> value
    is NetResult.Err -> emptyList()
}

/** Unwraps a nullable result, treating any failure as absent. */
internal fun <T> NetResult<T>.valueOrNull(): T? = (this as? NetResult.Ok)?.value
