package com.yunyin.music.data

import android.content.Context
import com.yunyin.music.core.LyricBundle
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.net.NeteaseClient
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
) {

    private val cacheDir = File(context.cacheDir, "lyrics").apply { mkdirs() }
    private val memory = LinkedHashMap<Long, SyncedLyrics>(MEMORY_CACHE)

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
     * Resolves the lyrics for [trackId].
     *
     * Delivery is deliberately **two-phase**. The first usable document is handed to [onFirstDoc]
     * the moment it exists (NetEase measured ~0.4s), and only then is a word-by-word upgrade
     * awaited. Waiting for that upgrade *before* showing anything is what made loading feel slow:
     * measured over real tracks (`tools/measure_lyrics_wait.py`), the median time-to-lyrics was
     * 2.40s because the load blocked on the upgrade window even when lyrics were already in hand —
     * and most songs only have line-level lyrics on NetEase, so most songs paid it.
     *
     * @param onFirstDoc invoked once with a provisional document, before the upgrade is awaited, so
     *   the caller can paint immediately. Pass null when only the final result matters (prefetch).
     * @param onUpgraded invoked later from a background thread if a word-by-word document lands
     *   after [onFirstDoc] already delivered a line-level one. Callers must marshal to their own
     *   thread. This exists so karaoke appears without the user having to replay the song.
     */
    suspend fun lyricsFor(
        trackId: Long,
        onFirstDoc: ((SyncedLyrics) -> Unit)? = null,
        onUpgraded: ((SyncedLyrics) -> Unit)? = null,
    ): SyncedLyrics? {
        val cached = cachedLyrics(trackId)

        // Only a word-by-word document is final, so only it may short-circuit the load. A cached
        // *line-level* document must not: a song that showed plain lyrics once — because the
        // karaoke source was slow that time — would otherwise show plain lyrics forever, since
        // nothing would ever look for the better document again.
        if (cached != null && isWordByWord(cached)) return cached

        // Both sources start at once and run in [scope], so an early return does not cancel them.
        // That matters for caching, not just tidiness: cancelling a still-running AMLL fetch meant
        // a slow-but-successful mirror was re-raced on *every* play (measured settle up to 9.9s).
        val neteaseDeferred = scope.async { NeteaseSide(fetchBundle(trackId)) }
        val amllDeferred = scope.async { fetchAmllTtml(trackId) }

        // Plain lyrics already on hand: hand them over now and upgrade in the background rather
        // than making the caller wait on fetches that may fail.
        if (cached != null) {
            onFirstDoc?.invoke(cached)
            // `afterLineLevel = true`: something is already on screen, so the late delivery should
            // only improve on it, never replace it with the same thing.
            deliverLateDocument(trackId, amllDeferred, neteaseDeferred, onUpgraded, afterLineLevel = true)
            return cached
        }

        val first = withTimeoutOrNull(FIRST_DOCUMENT_WAIT_MS) {
            awaitFirstDocument(neteaseDeferred, amllDeferred)
        }
        if (first == null) {
            // Nothing usable arrived within the window. Crucially, this must **not** simply return:
            // the fetch is still running on [scope], and abandoning it here is what made some songs
            // load lyrics "never" — the caller was told there were none, nothing was cached, and
            // nothing retried the track (the view model only starts a load when the *track id*
            // changes, and its manual retry is not wired to any UI). Handing the in-flight fetch to
            // the background upgrader turns "shows nothing, forever" into "shows lyrics a moment
            // later".
            //
            // `afterLineLevel = false`: nothing was shown, so a late line-level document is welcome.
            deliverLateDocument(trackId, amllDeferred, neteaseDeferred, onUpgraded, afterLineLevel = false)
            return null
        }

        // The NetEase payload supplies both the alignment reference and the translation, so it is
        // part of the result rather than an optimisation — and it is the fast source (0.4s).
        val netease = withTimeoutOrNull(BUNDLE_WAIT_MS) { neteaseDeferred.await() }
            ?: NeteaseSide(null)

        // Same reasoning as above: a document that cannot be resolved is not the end of the story,
        // because the other source may still produce a usable one.
        val resolved = resolve(first, netease)
        if (resolved == null) {
            deliverLateDocument(trackId, amllDeferred, neteaseDeferred, onUpgraded, afterLineLevel = false)
            return null
        }
        storeIfTrustworthy(trackId, resolved)

        if (isWordByWord(resolved.lyrics)) return resolved.lyrics

        // Line-level for now: show it, then try to upgrade. Awaiting the upgrade costs the caller
        // nothing visible, because [onFirstDoc] has already delivered something to display.
        onFirstDoc?.invoke(resolved.lyrics)

        val amll = withTimeoutOrNull(UPGRADE_WAIT_MS) { amllDeferred.await() }
        upgradeFrom(amll, netease)?.let { upgraded ->
            storeIfTrustworthy(trackId, upgraded)
            return upgraded.lyrics
        }

        // The window expired without a karaoke document. The fetch is still running on [scope], so
        // let it finish and hand the better document over. Without this the user would have to
        // replay the song to get karaoke — which is exactly the reported symptom.
        deliverLateDocument(trackId, amllDeferred, neteaseDeferred, onUpgraded, afterLineLevel = true)
        return resolved.lyrics
    }

    /**
     * Delivers a lyric document that arrived **after** the caller was told to stop waiting.
     *
     * Runs on [scope] (IO) and is the safety net for every early return above: the fetches are
     * started on this scope precisely so they outlive the caller's patience, and this is what turns
     * that into something the user sees. Without it, a slow source meant the track was reported as
     * having no lyrics at all — and since the view model only starts a load when the *track id*
     * changes, the failure lasted until the user skipped away and back.
     *
     * **It awaits the AMLL document specifically, not a race between the two sources.** An earlier
     * version re-ran [awaitFirstDocument] here, which was a mistake with a very visible result: when
     * the NetEase fetch had already completed, racing returned that *line-level* document immediately,
     * so this delivered plain lyrics and returned — while the in-flight karaoke document, the whole
     * reason for waiting, was never delivered. Songs that should have word-by-word lyrics showed none.
     * Awaiting the one source that can still improve the result is the point of this call.
     *
     * Unlike the word-by-word-only upgrade it replaced, a line-level document is still delivered when
     * no karaoke exists: when the screen is otherwise empty, plain lyrics are the difference between a
     * readable song and nothing.
     *
     * @param onDelivered invoked off the main thread; callers marshal to their own dispatcher.
     * @param afterLineLevel true when the caller has already shown line-level lyrics for this track, so
     *        only a word-by-word document is worth delivering. False when nothing was shown.
     */
    private fun deliverLateDocument(
        trackId: Long,
        amllDeferred: Deferred<SyncedLyrics?>,
        neteaseDeferred: Deferred<NeteaseSide>,
        onDelivered: ((SyncedLyrics) -> Unit)?,
        afterLineLevel: Boolean,
    ) {
        if (onDelivered == null) return
        scope.launch {
            val amll = runCatching { amllDeferred.await() }.getOrNull()
            val netease = runCatching { neteaseDeferred.await() }.getOrNull() ?: NeteaseSide(null)

            // The better document, if there is one.
            val upgraded = upgradeFrom(amll, netease)
            if (upgraded != null) {
                storeIfTrustworthy(trackId, upgraded)
                onDelivered(upgraded.lyrics)
                return@launch
            }

            // No karaoke document. Only fall back to the line-level one if the caller showed nothing —
            // otherwise this would replace what the user is reading with the same thing.
            if (afterLineLevel) return@launch
            val fallback = netease.doc?.takeIf { it.lines.isNotEmpty() }
                ?.let { resolve(Candidate(it, fromAmll = false), netease) }
            if (fallback != null) {
                storeIfTrustworthy(trackId, fallback)
                onDelivered(fallback.lyrics)
            }
        }
    }

    /**
     * The word-by-word version of [amllDoc], if it is one and it resolves.
     *
     * Deliberately has **no line-count guard**. An earlier version rejected the upgrade when the
     * two documents differed in line count by more than 20%, which threw away the karaoke document
     * for any song where the sources disagree on structure — measured on a real track with 107
     * NetEase lines against 75 AMLL lines. That was also inconsistent: the *first-document* path
     * accepted the very same document unchecked, so whether karaoke appeared depended on network
     * timing. Showing plain lyrics when karaoke exists is strictly worse than a re-layout, and the
     * view re-anchors its reading position from the clock, so a structure change is harmless.
     */
    private fun upgradeFrom(amllDoc: SyncedLyrics?, netease: NeteaseSide): Resolved? {
        if (amllDoc == null || !isWordByWord(amllDoc)) return null
        return resolve(Candidate(amllDoc, fromAmll = true), netease)
    }

    /** A lyric document plus which source it came from. */
    private class Candidate(val lyrics: SyncedLyrics, val fromAmll: Boolean)

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

    /** A resolved document, plus whether its timing was trustworthy enough to cache. */
    private class Resolved(val lyrics: SyncedLyrics, val trustworthy: Boolean)

    /**
     * Takes whichever of the two sources produces a usable document first.
     *
     * Only the *first* document is awaited here. Holding out for a better one is [lyricsFor]'s
     * upgrade phase, which runs after the first document has already been handed to the caller —
     * that separation is the whole point of the two-phase design.
     *
     * The race is in [raceFirstOf], which returns as soon as one side answers. The obvious
     * `coroutineScope { launch { … }; launch { … }; receive() }` form looks equivalent but waits for
     * both producers, which made the load as slow as the slowest source — see that function's note.
     */
    private suspend fun awaitFirstDocument(
        netease: Deferred<NeteaseSide>,
        amll: Deferred<SyncedLyrics?>,
    ): Candidate? = raceFirstOf(
        first = { netease.await().doc?.takeIf { it.lines.isNotEmpty() }?.let { Candidate(it, fromAmll = false) } },
        second = { amll.await()?.takeIf { it.lines.isNotEmpty() }?.let { Candidate(it, fromAmll = true) } },
    )

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
        return Resolved(merged, trustworthy)
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

    /**
     * Caches a resolved document, unless its timing is suspect.
     *
     * An AMLL document with no reference is left unshifted, which can be seconds off; returning it
     * is still better than showing nothing, but caching it would pin the wrong timing for the whole
     * session.
     */
    private fun storeIfTrustworthy(trackId: Long, resolved: Resolved) {
        if (resolved.trustworthy) store(trackId, resolved.lyrics)
    }

    /**
     * Reads or fetches the NetEase payload.
     *
     * Runs on [scope] (IO), so the disk write never touches the main thread. A persisted bundle
     * answers instantly, which is what lets a previously-seen track resolve with no network at all.
     */
    private suspend fun fetchBundle(trackId: Long): LyricBundle? {
        readCachedBundle(trackId)?.let { return it }
        val result = runCatching { client.lyric(trackId) }.getOrNull()
        val bundle = (result as? NetResult.Ok)?.value ?: return null
        writeCachedBundle(trackId, bundle)
        return bundle
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

    private fun store(trackId: Long, lyrics: SyncedLyrics) {
        synchronized(memory) {
            memory[trackId] = lyrics
            while (memory.size > MEMORY_CACHE) {
                val oldest = memory.keys.firstOrNull() ?: break
                memory.remove(oldest)
            }
        }
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

        /**
         * How long to wait for ANY usable lyric document before giving up.
         *
         * Measured: NetEase answers in ~0.4s on every track tested, so this is only reached when
         * that fails and a mirror is being waited out.
         */
        const val FIRST_DOCUMENT_WAIT_MS = 6000L

        /**
         * How long to wait for a **word-by-word upgrade** after a line-level document is already
         * being displayed.
         *
         * This is off the critical path by construction: the caller has already been handed the
         * line-level lyrics, so the only cost of this window is that a page-swap happens late. It
         * is sized generously because a healthy AMLL mirror answered in 0.6–2.2s when measured.
         */
        const val UPGRADE_WAIT_MS = 3000L

        /**
         * How long to wait for the NetEase payload that supplies alignment and translation.
         *
         * This is not merely an optimisation bound: the payload carries the time reference that
         * an AMLL document must be shifted by, so returning without it is what produced "lyrics
         * start before the intro". NetEase measured 0.4s across every track tested, so this limit
         * is only a guard against an unreachable API — it is deliberately far above the observed
         * latency rather than near it.
         */
        const val BUNDLE_WAIT_MS = 6000L

        /** Ceiling for a single AMLL mirror request. */
        const val MIRROR_CALL_TIMEOUT_MS = 4500L

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
