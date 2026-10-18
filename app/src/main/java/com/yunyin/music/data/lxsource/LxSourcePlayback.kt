package com.yunyin.music.data.lxsource

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves a stream URL through an enabled custom source, for any source key the source declares.
 *
 * ## One long-lived runtime per (source, key)
 *
 * Creating a QuickJS context and executing a 500 KB source takes hundreds of milliseconds, far too long per
 * song. So the first lookup opens a runtime, runs the script, waits for `inited`, and keeps the engine for the
 * next lookup. Each runtime is serialised by its own [Mutex]: a QuickJS context is single-threaded, and two
 * concurrent lookups on one engine would interleave evaluations. The slot is keyed by source **and** key because
 * one source can declare several platforms (`tx`, `wy`, `kw`), each with its own descriptor.
 *
 * ## The caller decides which catalogue
 *
 * [musicUrl] resolves a NetEase track through the `wy` declaration (the original behaviour). [musicUrlForKey] is
 * the general form used by [com.yunyin.music.data.providers.ProviderPlayback] for a track that came from another
 * platform: the caller passes that platform's `musicInfo` object and the source key, and gets back the URL for
 * that platform's song.
 *
 * ## Failure is never silence
 *
 * Every failure path returns `null` — no enabled source, script missing, initialisation failed, handler rejected,
 * lookup timeout, a preview clip — after writing a diagnostic block (see [LxLookupTrace]) to the lxsource log.
 * The caller then falls back on its own path, so a broken source can never leave the player without audio.
 *
 * ## Cancellation
 *
 * [cancelPending] is called when the player moves to another track, so the previous song's lookup is interrupted
 * instead of completing into a URL nobody will use. A cancelled lookup also drops its runtime: the engine was
 * mid-evaluation when it was interrupted, and recreating it is cheaper than trusting it.
 */
class LxSourcePlayback(
    private val manager: LxSourceManager,
    private val log: LxSourceLog,
) {

    private class Slot(
        val source: LxImportedSource,
        val descriptor: LxSourceDescriptor,
        val runtime: LxJsRuntime,
        val mutex: Mutex = Mutex(),
    )

    private class CachedSource(val revision: Long, val source: LxImportedSource?)

    private val slots = ConcurrentHashMap<String, Slot>()

    /**
     * The key of the lookup currently running, if any.
     *
     * ## Why the caller's job is not cancelled any more
     *
     * This used to hold the caller's `Job`, and `cancelPending()` cancelled it when the player changed tracks. That
     * was wrong by construction: the caller is `PlaybackService`'s `runBlocking` on ExoPlayer's loading thread, so
     * cancelling it throws `CancellationException` out of the resolving data source and surfaces to the user as a
     * bare **"Source error"**. Worse, `onMediaItemTransition` can fire *after* the new item's load has started, so a
     * lookup for the track that is about to play could be cancelled by the transition that announced it — the
     * "some tracks play, some show source error" symptom.
     *
     * A lookup is now marked superseded instead: it finishes on its own (bounded by `timeoutMs`), and its result is
     * discarded if a *different* track became current. A lookup for the **same** track is never touched.
     */
    private val activeKey = AtomicReference<Long?>(null)

    /** Keys whose result must be discarded because the player moved to another track. */
    private val supersededKeys: MutableSet<Long> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** Per-key "which enabled source answers for this key", invalidated by [LxSourceManager.revision]. */
    private val sourceCache = ConcurrentHashMap<String, CachedSource>()

    /** The last few per-track answers, for the on-screen trial notice ("音源未取到完整链接"). */
    private val lastOutcome = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<Long, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > 16
        },
    )

    /** A dedicated client for the advisory preview probe: HEAD/Range requests with short timeouts. */
    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    private val closedRevision = AtomicLong(Long.MIN_VALUE)
    /** Why the last lookup for [trackId] did not produce a usable URL, or null when it did (or was not consulted). */
    fun lastOutcome(trackId: Long): String? = lastOutcome[trackId]

    /** The enabled source claiming `wy`, or `null` when no custom source should be consulted. */
    fun enabledNeteaseSource(): LxImportedSource? = enabledSourceForKey(LxSourceKey.WY.wire)

    /** The enabled source declaring [key] (`wy`, `tx`, `kw`, …), or null. */
    fun enabledSourceForKey(key: String): LxImportedSource? {
        val revision = manager.revision()
        val cached = sourceCache[key]
        if (cached != null && cached.revision == revision) return cached.source
        val match = manager.list().firstOrNull { source -> source.enabled && source.keys.any { it.wire == key } }
        sourceCache[key] = CachedSource(revision, match)
        return match
    }

    /** Whether an enabled source can be asked for [key] right now. */
    fun supports(key: String): Boolean = enabledSourceForKey(key) != null

    /**
     * The URL the enabled source resolves for a NetEase [trackId], or `null` to use the normal path.
     *
     * @param appQuality the app's own level (`standard|higher|exhigh|lossless|hires`), mapped to an LX tier here
     *   so the rest of the app never has to know the LX vocabulary.
     */
    suspend fun musicUrl(
        trackId: Long,
        appQuality: String,
        timeoutMs: Long = LOOKUP_TIMEOUT_MS,
    ): String? {
        val source = enabledNeteaseSource() ?: return null
        val track = LxTrackMemory.find(trackId)
        val musicInfo = LxMusicInfo.musicInfo(trackId, track)
        return musicUrlForKey(
            key = LxSourceKey.WY.wire,
            source = source,
            musicInfo = musicInfo,
            appQuality = appQuality,
            providerIdText = null,
            trackDurationMs = track?.durationMs ?: 0L,
            outcomeKey = trackId,
            musicInfoSource = if (track != null) "memory" else "id-only",
            timeoutMs = timeoutMs,
        )
    }

    /**
     * The URL a source resolves for a platform track, e.g. a QQ Music song found by [TxSearch].
     *
     * @param musicInfo the platform's `musicInfo` object (see `ProviderMusicInfo`); it is passed through verbatim.
     * @param providerIdText the platform id, recorded in the diagnostic trail (the numeric [outcomeKey] is only a
     *   label).
     */
    suspend fun musicUrlForKey(
        key: String,
        source: LxImportedSource? = null,
        musicInfo: JSONObject,
        appQuality: String,
        providerIdText: String? = null,
        trackDurationMs: Long = 0L,
        outcomeKey: Long = 0L,
        musicInfoSource: String = "provider-search",
        timeoutMs: Long = LOOKUP_TIMEOUT_MS,
    ): String? {
        val resolvedSource = source ?: enabledSourceForKey(key) ?: return null
        val source = resolvedSource
        val trace = LxLookupTrace(trackId = outcomeKey, appQuality = appQuality).apply {
            consulted = true
            sourceName = source.header.name
            sourceKey = key
            sourceId = source.id
            songIdText = providerIdText
            this.musicInfoSource = musicInfoSource
            hashPresent = musicInfo.has("hash")
        }
        val script = manager.readScript(source)
        if (script.isNullOrBlank()) {
            trace.decision = "fallback"
            trace.reason = "脚本文件缺失"
            lastOutcome[outcomeKey] = "自定义源脚本缺失"
            log.recordDiagnostic(trace.format())
            return null
        }

        val job = currentCoroutineContext()[Job]
        activeKey.set(outcomeKey)
        val defaultFallbackLabel = if (key == LxSourceKey.WY.wire) "回退网易云" else "回退内置解析"
        return try {
            val url = withTimeoutOrNull(timeoutMs) {
                lookup(key, source, script, musicInfo, appQuality, trackDurationMs, trace)
            }
            if (supersededKeys.remove(outcomeKey)) {
                // The player moved on while this was running. Discard — but do not throw: the caller is a data
                // source, and an exception there is what the user saw as "Source error".
                trace.decision = "superseded"
                trace.reason = "播放已切换到其他曲目，结果丢弃"
                log.recordDiagnostic(trace.format())
                Log.d(TAG, "lookup key=$key track=$outcomeKey superseded")
                return null
            }
            if (url == null) {
                trace.decision = "fallback"
                if (trace.reason.isNullOrBlank()) trace.reason = "未取得可用的 http(s) 链接"
                lastOutcome[outcomeKey] = "自定义源未取到完整链接"
                if (key != LxSourceKey.WY.wire) trace.reason = "$defaultFallbackLabel：${trace.reason}"
            } else {
                trace.decision = "used_source"
                lastOutcome.remove(outcomeKey)
            }
            log.recordDiagnostic(trace.format())
            Log.d(TAG, "lookup key=$key track=$outcomeKey decision=${trace.decision}")
            url
        } catch (e: CancellationException) {
            // Only a genuine cancellation of the caller reaches here (the app going away); our own supersede path
            // never throws.
            trace.decision = "cancelled"
            trace.reason = "调用方已取消"
            log.recordDiagnostic(trace.format())
            closeSlotsFor(source.id)
            throw e
        } catch (e: Throwable) {
            closeSlotsFor(source.id)
            trace.decision = "fallback"
            trace.reason = "异常：${e.javaClass.simpleName}: ${e.message}"
            lastOutcome[outcomeKey] = "自定义源解析异常"
            log.recordDiagnostic(trace.format())
            null
        } finally {
            activeKey.compareAndSet(outcomeKey, null)
        }
    }

    /**
     * Marks the in-flight lookup as no longer wanted, when the player moved to a different track.
     *
     * @param currentTrackId the item the player is on now; a lookup for that same track is left alone (a transition
     *   callback can arrive after the new item's load has already started).
     */
    fun cancelPending(reason: String, currentTrackId: Long? = null) {
        val key = activeKey.get() ?: return
        if (!LxSupersedeRules.shouldSupersede(key, currentTrackId)) return
        Log.d(TAG, "supersede lookup track=$key reason=$reason current=$currentTrackId")
        supersededKeys += key
    }

    /** Releases every engine. The app does this when it is being torn down; lookups then recreate lazily. */
    fun close() {
        slots.values.forEach { runCatching { it.runtime.close() } }
        slots.clear()
        sourceCache.clear()
        supersededKeys.clear()
        activeKey.set(null)
        closedRevision.set(Long.MIN_VALUE)
    }

    private suspend fun lookup(
        key: String,
        source: LxImportedSource,
        script: String,
        musicInfo: JSONObject,
        appQuality: String,
        trackDurationMs: Long,
        trace: LxLookupTrace,
    ): String? {
        val slot = slotFor(key, source, script, trace) ?: return null
        return slot.mutex.withLock {
            val desired = LxMusicInfo.desiredTier(appQuality)
            val quality = LxMusicInfo.chooseQuality(desired, slot.descriptor.qualities)
            trace.requestedTier = desired
            trace.chosenTier = quality
            val payload = JSONObject()
                .put("source", key)
                .put("action", "musicUrl")
                .put(
                    "info",
                    JSONObject()
                        .put("type", quality)
                        .put("musicInfo", musicInfo),
                )
                .toString()
            val raw = slot.runtime.request("request", payload, trace = trace)
            trace.handlerResult = traceSnippet(raw)
            val url = parseLxMusicUrl(raw)
            if (url == null) {
                // A handler that resolved something unusable is a source bug; recreate on the next attempt so a
                // transient bad state cannot stick.
                trace.reason = "源没有返回 http(s) 链接（见 handler returned 字段）"
                closeSlotsFor(source.id)
                return@withLock null
            }
            // A CDN URL can be a full track or a 20–35 s preview; the size is the only signal available before
            // playback. Treating a preview as full is exactly the "会员歌曲还是只能试听" report.
            val (verdict, length) = LxPreviewProbe.inspect(url, trackDurationMs, probeClient)
            trace.probeVerdict = verdict.name
            trace.probeContentLength = length
            if (verdict == LxPreviewProbe.Verdict.PREVIEW_CLIP) {
                trace.reason = "源返回的是试听片段（${length ?: "?"} 字节，曲目 ${trackDurationMs}ms）"
                return@withLock null
            }
            url
        }
    }

    private suspend fun slotFor(
        key: String,
        source: LxImportedSource,
        script: String,
        trace: LxLookupTrace,
    ): Slot? {
        val slotId = "${source.id}:$key"
        slots[slotId]?.let { return it }
        val runtime = LxJsRuntime(log)
        val inited = runtime.open(script, source.header, INIT_TIMEOUT_MS)
        if (inited is LxInitedValidator.Result.Error) {
            runtime.close()
            trace.reason = "音源初始化失败：${inited.message}"
            log.record(
                "音源初始化失败，回退内置解析",
                "source=${source.header.name}\n${inited.message}\n${inited.detail.orEmpty()}",
            )
            return null
        }
        val descriptors = (inited as LxInitedValidator.Result.Ok).sources
        val descriptor = descriptors.firstOrNull { it.key.wire == key }
        if (descriptor == null) {
            runtime.close()
            trace.reason = "音源不再声明 $key"
            log.record("音源不再支持 $key", "source=${source.header.name} → 回退内置解析")
            return null
        }
        val slot = Slot(source = source, descriptor = descriptor, runtime = runtime)
        val existing = slots.putIfAbsent(slotId, slot)
        if (existing != null) {
            // Another caller won the race; keep one engine.
            runtime.close()
            return existing
        }
        Log.d(TAG, "opened runtime for ${source.header.name} (${source.id}) key=$key")
        return slot
    }

    private fun closeSlotsFor(sourceId: String) {
        slots.keys.filter { it.startsWith("$sourceId:") }.forEach { slotId ->
            slots.remove(slotId)?.let { runCatching { it.runtime.close() } }
        }
    }

    private companion object {
        const val TAG = "YunYin/LxSource"

        /** Init happens once per source (then cached); the same 8 s budget as import. */
        const val INIT_TIMEOUT_MS = 8_000L

        /**
         * How long a lookup may hold ExoPlayer's loading thread.
         *
         * Shorter than the runtime's own 30 s dispatch ceiling on purpose: the loading thread is what makes the
         * next track start, and a source that takes longer than this is better abandoned in favour of NetEase.
         */
        const val LOOKUP_TIMEOUT_MS = 12_000L
    }
}
