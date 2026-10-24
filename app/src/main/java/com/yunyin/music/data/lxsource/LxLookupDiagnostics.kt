// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.lxsource

import android.os.SystemClock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * The record of one `musicUrl` lookup: what was asked, what the source did, and why the result was used or not.
 *
 * ## Why this exists
 *
 * "会员歌曲还是只能试听" has several indistinguishable causes from the outside: no source enabled, the source
 * consulted but its own API failed, the source returned a preview clip, the source returned a full URL that the
 * player still treated as a trial, or our resolution never ran. The user cannot see any of that, so the previous
 * report was necessarily a guess. This trace makes each lookup a short block in a file the user can copy from
 * Settings — with the fields that separate those cases:
 *
 *  - whether a source was consulted at all, and which (name / id / key);
 *  - the `songmid` that was sent, and whether it came from cached metadata or the id alone;
 *  - the app quality, the requested LX tier and the tier actually chosen from the source's declaration;
 *  - every `lx.request` the source made: method, URL (query stripped), HTTP status, bytes, duration and a
 *    truncated body — this is what shows "endpoint answered 200 but its `data.url` is an error string";
 *  - the handler's own result, and the preview probe's verdict;
 *  - the decision and the reason, and the total time.
 *
 * Deliberately plain text formatted as key=value lines: it is meant to be pasted into a bug report, not parsed.
 * Everything written is bounded (bodies truncated, at most [MAX_HTTP_CALLS] calls) so a hostile or chatty source
 * cannot fill the file.
 */
class LxLookupTrace(
    val trackId: Long,
    val appQuality: String,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val wallNow: () -> Long = System::currentTimeMillis,
) {

    data class HttpCall(
        val method: String,
        val url: String,
        val status: Int?,
        val bytes: Long?,
        val millis: Long,
        val bodySnippet: String?,
        val error: String?,
    )

    private val startedAt = elapsedNow()
    private val calls = Collections.synchronizedList(mutableListOf<HttpCall>())

    @Volatile
    var consulted: Boolean = false

    @Volatile
    var sourceName: String? = null

    @Volatile
    var sourceId: String? = null

    /** The LX source key asked (`wy`, `tx`, …). */
    @Volatile
    var sourceKey: String? = null

    @Volatile
    var songmid: Long? = null

    /** The platform id as text, for a provider track whose id is not a number (QQ `mid`, Kugou `hash`). */
    @Volatile
    var songIdText: String? = null

    /** `memory` when the full track was cached, `id-only` when the resolver had just the media id. */
    @Volatile
    var musicInfoSource: String? = null

    @Volatile
    var hashPresent: Boolean = false

    @Volatile
    var requestedTier: String? = null

    @Volatile
    var chosenTier: String? = null

    @Volatile
    var handlerResult: String? = null

    @Volatile
    var handlerError: String? = null

    @Volatile
    var probeVerdict: String? = null

    @Volatile
    var probeContentLength: Long? = null

    @Volatile
    var decision: String = "not_consulted"

    @Volatile
    var reason: String? = null

    fun recordHttpCall(call: HttpCall) {
        if (calls.size >= MAX_HTTP_CALLS) return
        calls += call
    }

    fun elapsedMs(): Long = elapsedNow() - startedAt

    /** One block, formatted for a human pasting it into a report. */
    fun format(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wallNow()))
        return buildString {
            appendLine("[$timestamp] trackId=$trackId quality=$appQuality")
            appendLine(
                "  consulted=$consulted source=${sourceName ?: "-"} id=${sourceId ?: "-"} " +
                    "key=${sourceKey ?: LxSourceKey.WY.wire}",
            )
            appendLine(
                "  musicInfo: songmid=${songmid ?: "-"} providerId=${songIdText ?: "-"} " +
                    "from=${musicInfoSource ?: "-"} hash=" + if (hashPresent) "present" else "absent",
            )
            appendLine("  quality: requested=${requestedTier ?: "-"} chosen=${chosenTier ?: "-"}")
            synchronized(calls) {
                if (calls.isEmpty()) {
                    appendLine("  http: none")
                } else {
                    calls.forEachIndexed { index, call ->
                        appendLine(
                            "  http[${index + 1}]: ${call.method} ${call.url} status=${call.status ?: "-"} " +
                                "bytes=${call.bytes ?: "-"} in=${call.millis}ms",
                        )
                        call.bodySnippet?.takeIf { it.isNotBlank() }?.let { appendLine("             body=$it") }
                        call.error?.takeIf { it.isNotBlank() }?.let { appendLine("             error=$it") }
                    }
                }
            }
            handlerResult?.takeIf { it.isNotBlank() }?.let { appendLine("  handler returned: $it") }
            handlerError?.takeIf { it.isNotBlank() }?.let { appendLine("  handler error: $it") }
            if (probeVerdict != null) {
                appendLine("  probe: verdict=$probeVerdict contentLength=${probeContentLength ?: "-"}")
            }
            appendLine("  decision: $decision${reason?.let { " —— $it" } ?: ""}")
            appendLine("  total: ${elapsedMs()}ms")
        }
    }

    private companion object {
        const val MAX_HTTP_CALLS = 4
    }
}

/** Truncates text for the trace; whitespace is collapsed so a JSON body stays one readable line. */
internal fun traceSnippet(text: String?, limit: Int = 320): String? {
    val collapsed = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (collapsed.isEmpty()) return null
    return if (collapsed.length <= limit) collapsed else collapsed.take(limit) + "…"
}

/** URL without its query, for a log line: a source's query carries API keys and signatures. */
internal fun traceRedactUrl(url: String): String = url.trim().substringBefore('?').take(160)

/**
 * The preview-clip heuristic: does a URL's byte size look like a full track or a short trial fragment?
 *
 * The aggregator source returns a NetEase CDN URL either way, and for a VIP track that URL is often a 20–35 s
 * preview (`0.3 MB`) while the same source returns `10–12 MB` for a free full track. A URL alone cannot say
 * which it is, but its length can: a track of N seconds cannot be a full song at less than
 * [MIN_BYTES_PER_SECOND] (~64 kbit/s), and [PREVIEW_TO_EXPECTED_RATIO] leaves generous headroom below that.
 *
 * The rule is conservative in both directions: an unknown length or a very short track returns [Verdict.UNKNOWN]
 * so the URL is used, because refusing a playable URL is worse than playing a clip — this only catches the clear
 * case, and the trace records what was decided.
 */
internal object LxPreviewProbe {

    enum class Verdict { FULL, PREVIEW_CLIP, UNKNOWN }

    /** 64 kbit/s: below any real music stream this app asks for. */
    const val MIN_BYTES_PER_SECOND = 8_000L

    /** A clip that is less than this fraction of the plausible minimum is a preview, not a short encode. */
    const val PREVIEW_TO_EXPECTED_RATIO = 0.5

    /** Under 30 s the expected-size math is too noisy to judge, and a short track is not a trial. */
    const val MIN_JUDGEABLE_DURATION_MS = 30_000L

    fun classify(contentLength: Long?, trackDurationMs: Long): Verdict {
        if (contentLength == null || contentLength <= 0L) return Verdict.UNKNOWN
        if (trackDurationMs < MIN_JUDGEABLE_DURATION_MS) return Verdict.UNKNOWN
        val expectedMinBytes = (trackDurationMs / 1000.0 * MIN_BYTES_PER_SECOND).toLong()
        return when {
            contentLength < (expectedMinBytes * PREVIEW_TO_EXPECTED_RATIO).toLong() -> Verdict.PREVIEW_CLIP
            contentLength >= expectedMinBytes -> Verdict.FULL
            else -> Verdict.UNKNOWN
        }
    }

    /**
     * Asks the URL how big it is.
     *
     * `HEAD` first; a CDN that refuses it is asked for a one-byte range, whose `Content-Range` carries the total.
     * Any failure is [Verdict.UNKNOWN] with a null length — the probe is advisory and must never block playback.
     */
    suspend fun inspect(url: String, trackDurationMs: Long, client: okhttp3.OkHttpClient, timeoutMs: Long = 2_500L): Pair<Verdict, Long?> {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return Verdict.UNKNOWN to null
        }
        val length = probeLength(url, client, timeoutMs)
        return classify(length, trackDurationMs) to length
    }

    private suspend fun probeLength(url: String, client: okhttp3.OkHttpClient, timeoutMs: Long): Long? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // HEAD is cheapest and what most CDNs answer.
            runCatching {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", PROBE_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    val length = response.header("Content-Length")?.toLongOrNull()
                    if (response.isSuccessful && length != null && length > 0L) return@withContext length
                }
            }
            // Fall back to a one-byte range: `Content-Range: bytes 0-0/1234567`.
            runCatching {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", PROBE_USER_AGENT)
                    .build()
                client.newCall(request).execute().use { response ->
                    val total = response.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.toLongOrNull()
                    if (total != null && total > 0L) return@withContext total
                }
            }
            null
        }
    }

    private const val PROBE_USER_AGENT = "YunYin/1.0"
}

/**
 * Short-lived reuse of a resolved stream URL.
 *
 * One track load resolves the URL **twice** in this app: once for ExoPlayer's data source and once for the
 * trial-preview check. With a custom source enabled both went to the source independently, which can produce two
 * different answers (one full, one fallback) and a trial notice that disagrees with the audio actually playing.
 * Coalescing per `(track, quality)` makes both callers share one answer — and one source request.
 *
 * ## Why the TTL is now longer than a few seconds
 *
 * A signed CDN URL stays valid for many minutes, and the expensive part is the custom-source round trip (a network
 * hop *plus* a JS-engine call). Four seconds only covered the double-resolution of one load; a re-play or a seek
 * back to the same track paid the full price again. A successful resolution is therefore reused for
 * [SUCCESS_TTL_MS] (bounded LRU in [ResolutionCache]), while a failure is remembered only briefly so a transient
 * source error is retried quickly. A playback error invalidates the entry immediately (see `MusicRepository`), so a
 * URL that did expire is never served twice.
 */
internal object LxResolveCacheRules {
    /** Legacy short window: retained for the double-resolution guarantee inside one track load. */
    const val TTL_MS = 4_000L

    /** Successful URLs are reused for three minutes; a playback error invalidates earlier. */
    const val SUCCESS_TTL_MS = 180_000L

    /** Negative results are cached briefly, so a transient source failure is retried soon. */
    const val NEGATIVE_TTL_MS = 5_000L

    /** Bounded so a long listening session cannot grow the cache without limit. */
    const val MAX_ENTRIES = 32

    fun key(trackId: Long, quality: String): String = "$trackId:$quality"

    fun isFresh(ageMs: Long): Boolean = ageMs in 0..TTL_MS
}

/**
 * Small bounded LRU with per-value freshness decisions, for resolved stream URLs.
 *
 * Pure so the eviction and expiry rules are unit-tested without Android or a network. Entries are keyed by
 * `trackId:quality`; the value's timestamp decides whether it is still fresh, and the least-recently-used entry is
 * dropped when the bound is exceeded.
 */
internal class ResolutionCache<V>(private val maxEntries: Int = LxResolveCacheRules.MAX_ENTRIES) {

    class Entry<V>(val value: V, val atMs: Long)

    private val entries = object : LinkedHashMap<String, Entry<V>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<V>>?): Boolean =
            size > maxEntries
    }

    fun get(key: String, nowMs: Long, ttlFor: (V) -> Long): V? {
        val entry = entries[key] ?: return null
        val age = nowMs - entry.atMs
        if (age !in 0..ttlFor(entry.value)) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: V, nowMs: Long) {
        entries[key] = Entry(value, nowMs)
    }

    fun removeWhere(predicate: (String) -> Boolean) {
        entries.keys.removeAll(predicate)
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    fun clear() = entries.clear()

    fun size(): Int = entries.size
}
