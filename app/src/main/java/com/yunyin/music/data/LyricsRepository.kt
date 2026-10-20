package com.yunyin.music.data

import android.content.Context
import com.yunyin.music.core.LyricBundle
import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Track
import com.yunyin.music.data.net.NeteaseClient
import com.yunyin.music.data.lxsource.LxSourceLog
import com.yunyin.music.data.providers.ProviderLyricDocument
import com.yunyin.music.data.providers.ProviderLyrics
import com.yunyin.music.data.providers.ProviderMatcher
import com.yunyin.music.data.providers.extra.ExtraLyricDocument
import com.yunyin.music.data.providers.extra.ExtraLyricPayload
import com.yunyin.music.data.providers.extra.ExtraLyricsProvider
import com.yunyin.music.data.providers.tx.TxLyric
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser
import com.mocharealm.accompanist.lyrics.core.parser.NeteaseYrcParser
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Loads word-by-word lyrics.
 *
 * Sources:
 *  1. **AMLL TTML DB** — community Apple-Music-style TTML: true syllable timing, duet
 *     alignment, background vocals and translations.
 *  2. **NetEase `/lyric/new`** — `yrc` (word-by-word) then `lrc` (line-level).
 *
 * Both are fetched **concurrently** and *raced*: the first usable document is returned
 * immediately, so the load is bounded by the fastest healthy source rather than the slowest. A
 * word-by-word document that arrives later **upgrades** the result in the background instead of
 * being waited for up front — waiting for it is what made loading feel slow (see [lyricsFor]).
 * The NetEase payload is then used to align the chosen document and to supply a translation.
 *
 * Parsing uses the *specific* parser, not [AutoParser]: auto detection runs
 * `LyricifySyllableParser`/`EnhancedLrcParser` before `NeteaseYrcParser`, and a YRC payload
 * whose syllable text is Latin (`Hello(1734,400,0)`) satisfies the Lyricify detector — so
 * auto detection can claim a YRC document and silently drop its syllable timing.
 */
class LyricsRepository(
    context: Context,
    private val client: NeteaseClient,
    /**
     * Lyrics for tracks that came from another platform, or null when that feature is not wired.
     *
     * A provider track has no NetEase id, so the NetEase path below cannot serve it. This dependency is optional so
     * the existing path is unchanged for every NetEase track and for tests.
     */
    private val providerLyrics: ProviderLyrics? = null,
    /** Kuwo/Kugou fallback for tracks whose NetEase/QQ/AMLL sources have no lyrics; null disables it. */
    private val extraLyrics: ExtraLyricsProvider? = null,
    /** Shared diagnostics sink for the pin decisions (`stage=lyric pinned=… reason=…`). */
    private val log: LxSourceLog? = null,
) {

    private val cacheDir = File(context.cacheDir, "lyrics").apply { mkdirs() }
    private val memory = LinkedHashMap<Long, SyncedLyrics>(MEMORY_CACHE)

    /**
     * Which source produced each cached document.
     *
     * Used as the tie-break when a line-level cached document is carried into a probe: its source
     * decides whether it outranks another line-level candidate.
     */
    private val cachedSources = LinkedHashMap<Long, LyricSource>()

    /** Whether the last NetEase lyric payload said the track genuinely has no lyrics. */
    @Volatile
    private var lastFetchWasNolyric = false

    /**
     * Owns the in-flight fetches.
     *
     * They must outlive a single [lyricsFor] call: an early return (the common case now) would
     * otherwise cancel a still-running AMLL fetch, so its result would never be cached and the
     * mirrors would be re-raced on every subsequent play.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        // Caps a *single* mirror. The mirrors are raced (see [fetchAmllTtml]), so this bounds the
        // worst case instead of adding to it: measured, one AMLL host never responded and was
        // killed only by the old 12s read timeout, which every load that had no fast mirror hit.
        .callTimeout(MIRROR_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val ttmlParser = TTMLParser()
    private val autoParser = AutoParser()

    /** Which mirror served the last AMLL fetch; surfaced in Settings for diagnostics. */
    @Volatile
    var lastMirror: String? = null
        private set

    /** Index of the mirror that worked this session, tried first on later loads. */
    @Volatile
    private var preferredMirror: Int = -1

    /**
     * Resolves the lyrics for [trackId] by probing the applicable sources and painting the winner
     * **once**.
     *
     * The panel stays in its loading state until this returns, so the user never sees a weak
     * document replaced by a better one mid-song. A word-by-word document short-circuits the probe;
     * otherwise the best line-level document is painted at the [LyricProbeRules.PROBE_CAP_MS] cap.
     * See [probeAndPaint] for the decision loop and [LyricProbeRules] for the pure winner rule.
     */
    suspend fun lyricsFor(
        trackId: Long,
        /** The playing track; needed for the Kuwo/Kugou title+artist search and provider fallback. */
        track: Track? = null,
    ): SyncedLyrics? {
        // A word-by-word document from an earlier probe is already the best a new probe could pick;
        // returning it immediately is the one case where waiting buys nothing.
        val cached = cachedLyrics(trackId)?.takeIf { LyricProbeRules.isPaintable(it.lines.size) }
        if (cached != null && isWordByWord(cached)) return cached

        // A track from another platform has no NetEase id, so the NetEase/AMLL lanes cannot serve it:
        // its own platform and the title+artist fallbacks are the applicable sources.
        providerLyrics?.let { provider ->
            if (provider.supports(trackId)) {
                return providerProbe(trackId, provider, cached)
            }
        }

        // "Probe, then paint once": all applicable sources start in parallel, a word-by-word document
        // short-circuits the wait, and nothing is shown until the winner is decided. The deferreds
        // live on [scope] so a probe that stops waiting leaves the fetch alive to fill its cache.
        val neteaseDeferred = scope.async { NeteaseSide(fetchBundle(trackId)) }
        val amllDeferred = scope.async { fetchAmllTtml(trackId) }
        return probeAndPaint(
            trackId = trackId,
            cachedLineLevel = cached,
            lanes = listOf(
                { laneNetease(neteaseDeferred) },
                { laneAmll(neteaseDeferred, amllDeferred) },
                { laneExtras(track) },
            ),
        )
    }


    /**
     * Runs every lane in parallel, waits at most [LyricProbeRules.PROBE_CAP_MS] for a winner, and
     * paints it once.
     *
     * The loop breaks early the moment a **word-by-word** document arrives: it is the best a probe
     * can produce, so waiting longer only delays the first paint. If no word-by-word document
     * arrives, the probe keeps collecting line-level documents until the cap, then paints the one
     * with the most lines — the longest readable result, not whichever answered first.
     *
     * A lane that throws or returns null is simply a lane with no document; the other lanes and the
     * cap are unaffected. This is the only strategy: there is no later upgrade, no callback that
     * can replace what was painted, and no pin to block a second source.
     */
    private suspend fun probeAndPaint(
        trackId: Long,
        cachedLineLevel: SyncedLyrics?,
        lanes: List<suspend () -> Resolved?>,
    ): SyncedLyrics? {
        val startedNs = System.nanoTime()
        val arriving = Channel<Resolved>(Channel.UNLIMITED)
        val collected = mutableListOf<Resolved>()
        cachedLineLevel?.takeIf { LyricProbeRules.isPaintable(it.lines.size) }?.let {
            collected += Resolved(it, trustworthy = true, sourceOf(trackId))
        }
        // Lanes run on [scope], not as children of this call: when the probe decides, cancelling a
        // lane must cancel the *waiting* — never make the panel wait for a lane whose blocking HTTP
        // call happens to be un-cancellable. That is what keeps the 2.5 s cap real rather than a
        // best-effort.
        val jobs = lanes.map { lane ->
            scope.launch {
                runCatching { lane() }.getOrNull()?.let { arriving.send(it) }
            }
        }
        try {
            val deadlineNs = startedNs + LyricProbeRules.PROBE_CAP_MS * 1_000_000L
            while (true) {
                val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) break
                val candidate = withTimeoutOrNull(remainingMs) { arriving.receiveCatching().getOrNull() }
                    ?: break
                collected += candidate
                // A word-by-word document is the best a probe can produce: paint it now and stop
                // waiting, which is what keeps the common case well under a second.
                if (isWordByWord(candidate.lyrics)) break
            }
        } finally {
            jobs.forEach { it.cancel() }
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        val winner = LyricProbeRules.winner(collected.map { it.probeCandidate() })
            ?.let { best -> collected.firstOrNull { it.probeCandidate() == best } }
        traceProbe(trackId, winner, collected, elapsedMs)
        if (winner == null) return null
        if (winner.trustworthy) store(trackId, winner.lyrics, winner.source)
        return winner.lyrics
    }

    /** One lane's probe metadata, in the shape the pure winner rule consumes. */
    private fun Resolved.probeCandidate(): LyricProbeCandidate {
        val karaoke = lyrics.lines.filterIsInstance<KaraokeLine>()
        return LyricProbeCandidate(
            source = source,
            wordCount = karaoke.sumOf { it.syllables.size },
            lineCount = lyrics.lines.size,
        )
    }

    /** Records the probe outcome: winner, counts, elapsed, and each lane's verdict. */
    private fun traceProbe(trackId: Long, winner: Resolved?, candidates: List<Resolved>, elapsedMs: Long) {
        runCatching {
            val sources = candidates.joinToString(",") { candidate ->
                "${candidate.source.tag}:${if (isWordByWord(candidate.lyrics)) "word" else "line"}"
            }
            val counts = winner?.probeCandidate()
            log?.recordDiagnostic(
                "stage=lyric probe winner=${winner?.source?.tag ?: "none"} " +
                    "words=${counts?.wordCount ?: 0} lines=${counts?.lineCount ?: 0} " +
                    "elapsedMs=$elapsedMs sources=[$sources] trackId=$trackId",
            )
        }
    }

    /** NetEase's own payload: `yrc` renders as karaoke, `lrc` as line-level. */
    private suspend fun laneNetease(netease: Deferred<NeteaseSide>): Resolved? {
        val side = runCatching { netease.await() }.getOrNull() ?: return null
        val document = side.doc?.takeIf { LyricProbeRules.isPaintable(it.lines.size) } ?: return null
        return resolve(Candidate(document, LyricSource.Netease), side)
    }

    /** AMLL TTML, aligned/translated against the NetEase side (the app's timeline reference). */
    private suspend fun laneAmll(
        netease: Deferred<NeteaseSide>,
        amll: Deferred<SyncedLyrics?>,
    ): Resolved? {
        val side = runCatching { netease.await() }.getOrNull() ?: NeteaseSide(null)
        val document = runCatching { amll.await() }.getOrNull()
            ?.takeIf { LyricProbeRules.isPaintable(it.lines.size) } ?: return null
        return resolve(Candidate(document, LyricSource.Amll), side)
    }

    /** Kuwo then Kugou — the slow lanes; they carry their own matching and time budgets. */
    private suspend fun laneExtras(track: Track?): Resolved? {
        val extra = extraFallback(track) ?: return null
        return Resolved(extra.document, trustworthy = true, extra.source)
    }

    /** Provider-track probe: the platform's own QRC lyrics, the NetEase match, and Kuwo/Kugou. */
    private suspend fun providerProbe(
        trackId: Long,
        provider: ProviderLyrics,
        cachedLineLevel: SyncedLyrics?,
    ): SyncedLyrics? {
        val track = provider.trackFor(trackId)
        return probeAndPaint(
            trackId = trackId,
            cachedLineLevel = cachedLineLevel,
            lanes = listOf(
                { laneProviderPlatform(trackId, provider) },
                { laneProviderNetease(track) },
                { laneExtras(track) },
            ),
        )
    }

    /** The provider platform's QRC document (word-timed when the platform has it). */
    private suspend fun laneProviderPlatform(trackId: Long, provider: ProviderLyrics): Resolved? {
        val payload = withTimeoutOrNull(PROVIDER_LYRIC_WAIT_MS) { provider.fetch(trackId) } ?: return null
        val document = toProviderDocument(payload)?.takeIf { LyricProbeRules.isPaintable(it.lines.size) }
            ?: return null
        return Resolved(document, trustworthy = true, LyricSource.Tx)
    }

    /** NetEase's document for the same recording, matched by title/artist/duration. */
    private suspend fun laneProviderNetease(track: Track?): Resolved? {
        val neteaseId = withTimeoutOrNull(PROVIDER_LYRIC_WAIT_MS) { neteaseIdFor(track) } ?: return null
        val side = NeteaseSide(fetchBundle(neteaseId))
        val document = side.doc?.takeIf { LyricProbeRules.isPaintable(it.lines.size) } ?: return null
        return resolve(Candidate(document, LyricSource.Netease), side)
    }

    /** A lyric document plus which source it came from. */
    private class Candidate(val lyrics: SyncedLyrics, val source: LyricSource) {
        /** True when the document came from the AMLL TTML DB. */
        val fromAmll: Boolean get() = source == LyricSource.Amll
    }

    /** The NetEase side of the load: its raw payload, plus the pieces derived from it. */
    private inner class NeteaseSide(val bundle: LyricBundle?) {
        /** Parsed once, on first use: both the alignment reference and the candidate need it. */
        val doc: SyncedLyrics? by lazy { bundle?.let { parseNetease(it) } }

        /**
         * Start of the first real lyric line, used as the alignment reference.
         *
         * Taken from the **stripped** document, so a leading credit line can never be mistaken for
         * the first lyric — the mistake that shifted AMLL documents by up to 15.5s.
         */
        val anchorMs: Int? by lazy {
            doc?.let { stripMetadata(it).lines.firstOrNull()?.start }
        }

        val translation: String? get() = bundle?.wordTranslation ?: bundle?.translation
    }

    /** A resolved document, whether its timing was trustworthy, and which source produced it. */
    private class Resolved(
        val lyrics: SyncedLyrics,
        val trustworthy: Boolean,
        val source: LyricSource,
    )

    /** A document from the Kuwo/Kugou fallback, with which of the two supplied it. */
    private class ExtraDoc(val document: SyncedLyrics, val source: LyricSource)

    /**
     * Aligns and enriches [candidate] against the NetEase side.
     *
     * Only an **AMLL** document is shifted: the NetEase document defines the timeline we trust (it
     * is where the reference comes from), so shifting it would be circular.
     *
     * The alignment anchor on both sides is the first *sung* line, not the first line. Measured on
     * real tracks, NetEase documents commonly open with the credits ("出品：网易音乐人x青云LAB",
     * "作词 : …"), which have nothing to do with when singing starts — comparing a credit against
     * AMLL's first lyric produced a measured ~15.5s bogus offset, which is the "lyrics start before
     * the intro" symptom in its most common form.
     */
    /**
     * Aligns, strips metadata and enriches [candidate] against the NetEase side.
     *
     * Every displayed document passes through here, so this is where the leading **credit lines are
     * removed**. They carry their own timestamps (measured: `[00:00.03] 出品：…`), so leaving them
     * in meant the first "lyric" on screen was a credit line, highlighted the moment playback began
     * — the "lyrics start before the intro" bug. Stripping them was previously only done for the
     * *alignment anchor*, not for what was shown, which is why the timing looked right while the
     * lyrics still began too early.
     *
     * Only an **AMLL** document is time-shifted: the NetEase document defines the timeline we trust
     * (it is where the reference comes from), so shifting it would be circular.
     */
    private fun resolve(candidate: Candidate, netease: NeteaseSide): Resolved? {
        // Strip credits before aligning: the anchor is then simply the first remaining line, and
        // the document that gets displayed has no metadata at its head.
        val stripped = stripMetadata(candidate.lyrics)

        // Fold same-start lines into translations on the **NetEase** side only.
        //
        // NetEase's YRC carries a line's translation as a second line with an identical start, and
        // without this the translation was drawn as a lyric row and given the word-by-word fill. TTML
        // is excluded on purpose: it has agents, so a duet genuinely has two voices at one moment and
        // folding those would delete a real lyric — see [LyricLineFolding].
        val simultaneousFolded =
            if (candidate.fromAmll) stripped else LyricLineFolding.foldSimultaneousTranslations(stripped)

        // Then the other way a translation hides in the lyric track: some uploads interleave it as
        // alternating lines instead of using a translation field at all (measured on `Purple Whisper`).
        // See [LyricScriptFolding] for why this needs two measurements and what it cannot distinguish.
        //
        // Gated on the payload having **no** translation field. When one exists the translation is
        // already expressed properly, so a genuinely bilingual song — which also looks "mixed script" —
        // is never at risk of being folded by mistake.
        val folded = if (candidate.fromAmll || netease.translation != null) {
            simultaneousFolded
        } else {
            LyricScriptFolding.foldInterleavedTranslation(simultaneousFolded)
        }

        val aligned = if (candidate.fromAmll) {
            LyricTimeShift.alignByAnchors(
                lyrics = folded,
                currentAnchorMs = folded.lines.firstOrNull()?.start,
                referenceAnchorMs = netease.anchorMs,
            )
        } else {
            folded
        }

        // Prefer the YRC-aligned translation: it lines up exactly with the lyric lines, whereas the
        // LRC-aligned one needs the fuzzy (500ms) matching in LyricTranslation.
        val merged = LyricTranslation.merge(aligned, netease.translation)
        if (merged.lines.isEmpty()) return null

        val trustworthy = LyricTimeShift.isAlignmentTrustworthy(
            referenceMs = netease.anchorMs,
            fromAmll = candidate.fromAmll,
            referenceResolved = netease.bundle != null,
        )
        return Resolved(merged, trustworthy, candidate.source)
    }

    /**
     * Drops a document's leading credit/metadata lines.
     *
     * Each remaining line keeps its own timestamp, so the intro stays genuinely empty until singing
     * begins — which is the point: the credit line that used to be highlighted at t≈0 is gone.
     */
    private fun stripMetadata(lyrics: SyncedLyrics): SyncedLyrics {
        val kept = LyricMetadata.stripLeading(lyrics.lines) { lineText(it) }
        if (kept.size == lyrics.lines.size) return lyrics
        return lyrics.copy(lines = kept)
    }

    /** The source that produced the cached document, or NetEase when it is unknown. */
    private fun sourceOf(trackId: Long): LyricSource =
        synchronized(cachedSources) { cachedSources[trackId] } ?: LyricSource.Netease

    /**
     * Reads or fetches the NetEase payload.
     *
     * Runs on [scope] (IO), so the disk write never touches the main thread. A persisted bundle
     * answers instantly, which is what lets a previously-seen track resolve with no network at all.
     */
    private suspend fun fetchBundle(trackId: Long): LyricBundle? {
        readCachedBundle(trackId)?.let { cached ->
            lastFetchWasNolyric = cached.nolyric
            return cached
        }
        val result = runCatching { client.lyric(trackId) }.getOrNull()
        val bundle = (result as? NetResult.Ok)?.value ?: return null
        lastFetchWasNolyric = bundle.nolyric
        writeCachedBundle(trackId, bundle)
        return bundle
    }

    /** Empty-state text for the current track: lyricless is a fact, "not found" is not. */
    fun lyricEmptyMessage(): String = com.yunyin.music.data.net.LyricEndpointRules.emptyMessage(lastFetchWasNolyric)

    /**
     * Kuwo/Kugou fallback: only consulted when the main sources produced nothing usable.
     *
     * The match is title+artist based (the app has no Kuwo/Kugou ids for a NetEase track), bounded by the provider's
     * own 6s/source and 10s/total budget. Returns null when there is no playing track to search for, no related
     * candidate, or no decodable document — the existing empty state then remains the honest answer.
     */
    private suspend fun extraFallback(track: Track?): ExtraDoc? {
        val provider = extraLyrics ?: return null
        val target = track ?: return null
        val payload = withTimeoutOrNull(EXTRA_LYRIC_WAIT_MS) { provider.fetch(target) } ?: return null
        val document = extraPayloadToDocument(payload) ?: return null
        return ExtraDoc(document, extraSource(payload.provider))
    }

    /** Maps a Kuwo/Kugou payload tag to its [LyricSource] for the pin rule and the log. */
    private fun extraSource(provider: String): LyricSource =
        if (provider == ExtraLyricsProvider.KG) LyricSource.Kugou else LyricSource.Kuwo

    private fun extraPayloadToDocument(payload: ExtraLyricPayload): SyncedLyrics? {
        val base = ExtraLyricDocument.build(payload)
            ?: payload.plain?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { autoParser.parse(raw) }.getOrNull() }
                ?.takeIf { it.lines.isNotEmpty() }
            ?: return null
        val stripped = stripMetadata(base)
        val merged = LyricTranslation.merge(stripped, payload.translation)
        return merged.takeIf { it.lines.isNotEmpty() }
    }

    /** Parses a provider lyric payload into the app's lyric model. Null when nothing usable came back. */
    private fun toProviderDocument(payload: TxLyric.Parsed): SyncedLyrics? {
        // Karaoke first: QRC word timings are converted straight into the app's karaoke model
        // (see `ProviderLyricDocument` for why the word track is not parsed from text).
        val base = ProviderLyricDocument.build(payload)
            ?: payload.plain?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { autoParser.parse(raw) }.getOrNull() }
                ?.takeIf { it.lines.isNotEmpty() }
            ?: return null
        val stripped = stripMetadata(base)
        val merged = LyricTranslation.merge(stripped, payload.translation)
        return merged.takeIf { it.lines.isNotEmpty() }
    }

    /** The NetEase id of the same recording, for a provider track whose own lyric lookup failed. */
    private suspend fun neteaseIdFor(track: Track?): Long? {
        if (track == null || track.name.isBlank()) return null
        val candidates = client.search("${track.name} ${track.artistLine}", limit = 10).listOrEmpty()
        return ProviderMatcher.bestMatch(candidates, track)?.id
    }

    // ---------------------------------------------------------------- bundle persistence

    /**
     * Raw NetEase payloads persisted per track.
     *
     * Stored as the *raw* bundle rather than the resolved lyrics: the resolution (alignment,
     * translation merge) is derived data that depends on both sources, whereas the bundle is the
     * source of truth for the NetEase side. With the AMLL TTML already on disk, a cold start can
     * therefore resolve lyrics for a previously-seen track with no network at all.
     */
    private fun bundleFile(trackId: Long) = File(cacheDir, "$trackId.json")

    private fun readCachedBundle(trackId: Long): LyricBundle? = runCatching {
        val file = bundleFile(trackId)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        LyricBundle(
            id = trackId,
            lrc = json.optStringOrNull("lrc"),
            translation = json.optStringOrNull("translation"),
            yrc = json.optStringOrNull("yrc"),
            romanized = json.optStringOrNull("romanized"),
            wordTranslation = json.optStringOrNull("wordTranslation"),
            wordRomanized = json.optStringOrNull("wordRomanized"),
        ).takeIf { it.yrc != null || it.lrc != null }
    }.getOrNull()

    private fun writeCachedBundle(trackId: Long, bundle: LyricBundle) {
        runCatching {
            val json = JSONObject().apply {
                bundle.lrc?.let { put("lrc", it) }
                bundle.yrc?.let { put("yrc", it) }
                bundle.translation?.let { put("translation", it) }
                bundle.romanized?.let { put("romanized", it) }
                bundle.wordTranslation?.let { put("wordTranslation", it) }
                bundle.wordRomanized?.let { put("wordRomanized", it) }
            }
            bundleFile(trackId).writeText(json.toString())
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        optString(name).takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    /** Warms the cache for an upcoming track, so skipping ahead feels instant. */
    suspend fun prefetch(trackId: Long) {
        // Only a word-by-word result is worth skipping for, matching [lyricsFor]: if all that is
        // cached is line-level, prefetching is exactly the chance to look for the karaoke document
        // before the user gets there.
        if (isWordByWord(cachedLyrics(trackId))) return
        runCatching { lyricsFor(trackId) }
    }

    private fun cachedLyrics(trackId: Long): SyncedLyrics? = synchronized(memory) { memory[trackId] }

    private fun store(trackId: Long, lyrics: SyncedLyrics, source: LyricSource) {
        // A fragment is never worth caching: caching it would make the next load enter the probe
        // with nothing to gain, and the paint-once rule exists precisely so fragments never become
        // the visible document.
        if (!LyricProbeRules.isPaintable(lyrics.lines.size)) return
        synchronized(memory) {
            memory[trackId] = lyrics
            while (memory.size > MEMORY_CACHE) {
                val oldest = memory.keys.firstOrNull() ?: break
                memory.remove(oldest)
            }
        }
        synchronized(cachedSources) { cachedSources[trackId] = source }
    }

    /** True when the lyrics carry syllable-level timing (i.e. render as karaoke). */
    fun isWordByWord(lyrics: SyncedLyrics?): Boolean =
        lyrics?.lines?.any { it is KaraokeLine } == true

    /** Drops all cached lyrics (used by the Settings entry). */
    fun clearCache() {
        synchronized(memory) { memory.clear() }
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
        preferredMirror = -1
        lastMirror = null
    }

    // ---------------------------------------------------------------- AMLL

    /**
     * Fetches TTML from the AMLL DB.
     *
     * All mirrors are queried **concurrently** and the first usable response wins. Asking
     * them in sequence would mean waiting out every unreachable host — and jsDelivr is
     * frequently unreachable from mainland China, which is exactly the case where AMLL
     * matters most (songs NetEase has no YRC for, e.g. 海阔天空).
     */
    private suspend fun fetchAmllTtml(trackId: Long): SyncedLyrics? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$trackId.ttml")
        if (file.exists()) {
            runCatching { file.readText() }.getOrNull()?.let { cached ->
                parseTtml(cached)?.let { return@withContext it }
            }
        }

        // The mirror that worked last time goes first so the common case is one request.
        val ordered = if (preferredMirror in MIRRORS.indices) {
            listOf(MIRRORS[preferredMirror]) + MIRRORS.filterIndexed { i, _ -> i != preferredMirror }
        } else {
            MIRRORS
        }

        coroutineScope {
            val results = Channel<Pair<Int, String?>>(ordered.size)
            val jobs = ordered.mapIndexed { index, mirror ->
                launch { results.send(index to download(mirror.format(trackId))) }
            }
            try {
                repeat(ordered.size) {
                    val (index, body) = results.receive()
                    if (body == null || !body.contains(TTML_MARKER)) return@repeat
                    val parsed = parseTtml(body) ?: return@repeat
                    // Record the winning mirror by its position in the canonical list.
                    preferredMirror = MIRRORS.indexOf(ordered[index])
                    lastMirror = hostOf(ordered[index])
                    runCatching { file.writeText(body) }
                    return@coroutineScope parsed
                }
                null
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
    }

    private fun hostOf(mirror: String): String = mirror.substringAfter("//").substringBefore('/')

    private fun parseTtml(body: String): SyncedLyrics? = runCatching {
        if (!ttmlParser.canParse(body)) null else ttmlParser.parse(body)
    }.getOrNull()?.takeIf { it.lines.isNotEmpty() }

    private fun download(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", "YunYin/1.0").build()
        http.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- NetEase

    /**
     * Parses the NetEase payload, preferring the word-by-word track.
     *
     * Tries `yrc` and then `lrc` rather than `yrc ?: lrc`: if a `yrc` field is present but fails to
     * parse (or parses to nothing usable) the old `?:` form returned null and the perfectly good
     * `lrc` alongside it was never looked at — which loses the lyrics entirely, or silently drops
     * the song from word-by-word to nothing at all.
     *
     * **`yrc` is parsed with the YRC parser rather than `AutoParser`.** Both detectors accept a YRC
     * payload — verified by running them on a Latin-script sample, where both `canParse` calls return
     * true — and `AutoParser` orders `LyricifySyllableParser` before `NeteaseYrcParser`. On that
     * sample the two happened to produce identical syllable timings, so this is not the cause of any
     * observed missing-lyrics bug; it is a correctness measure, because relying on a detector order is
     * not a guarantee about which parser actually claims the document. `lrc` keeps using
     * [autoParser], since its variants genuinely need detection.
     */
    private fun parseNetease(bundle: LyricBundle): SyncedLyrics? {
        bundle.yrc?.let { raw ->
            runCatching { NeteaseYrcParser.parse(raw) }.getOrNull()
                ?.takeIf { it.lines.isNotEmpty() }
                ?.let { return it }
        }
        for (raw in listOfNotNull(bundle.lrc)) {
            runCatching { autoParser.parse(raw) }.getOrNull()
                ?.takeIf { it.lines.isNotEmpty() }
                ?.let { return it }
        }
        return null
    }

    /**
     * The plain text of a line, for credit-line detection.
     *
     * [ISyncedLine][com.mocharealm.accompanist.lyrics.core.model.ISyncedLine] carries no text
     * itself: a karaoke line exposes it as syllables and a plain line as `content`.
     */
    private fun lineText(line: com.mocharealm.accompanist.lyrics.core.model.ISyncedLine): String =
        when (line) {
            is KaraokeLine -> line.syllables.joinToString("") { it.content }
            is com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine -> line.content
            else -> ""
        }

    private companion object {
        const val MEMORY_CACHE = 24
        const val TTML_MARKER = "http://www.w3.org/ns/ttml"

        /** Ceiling for a single AMLL mirror request. */
        const val MIRROR_CALL_TIMEOUT_MS = 4500L

        /**
         * How long the provider lyric path may take, per step (provider fetch, NetEase fallback match).
         *
         * The provider endpoint answered in well under a second when measured; this is a guard against an
         * unreachable endpoint, not a latency budget. Two steps are bounded, so a broken provider costs at most ~2×
         * this before the screen shows "no lyrics".
         */
        const val PROVIDER_LYRIC_WAIT_MS = 8000L

        /** Kuwo/Kugou fallback budget (provider itself caps each source at 6s, total 10s). */
        const val EXTRA_LYRIC_WAIT_MS = 10_000L

        /**
         * AMLL DB mirrors. `%d` is the NetEase song id. jsDelivr is fastest when
         * reachable; the raw/ghproxy entries cover networks where it is blocked.
         */
        val MIRRORS = listOf(
            "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/%d.ttml",
            "https://fastly.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/%d.ttml",
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/%d.ttml",
            "https://ghproxy.net/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/%d.ttml",
            "https://gcore.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/%d.ttml",
        )
    }
}

/** `String.format` with the mirror template's `%d` placeholder. */
private fun String.format(trackId: Long): String = String.format(this, trackId)
