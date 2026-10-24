// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 云音 / YunYin contributors

package com.yunyin.music.data.providers.extra

import com.yunyin.music.core.ProviderRef
import com.yunyin.music.core.Track
import com.yunyin.music.data.providers.ProviderTrackIds
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater

/**
 * Kuwo (酷我音乐) lyrics: search + encrypted lyric container + word timing.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0):
 *  - `src/utils/musicSdk/kw/lyric.js` — the lyric request, `parseLrc`/`sortLrcArr`/`transformLrc` and the
 *    `lrcTools` karaoke parser;
 *  - `src/utils/musicSdk/kw/decodeLyric.js` — the `TP=content` container, zlib inflate, base64 + `yeelion` XOR;
 *  - `src/utils/musicSdk/kw/musicSearch.js` — the search endpoint and result fields.
 *
 * What is faithful: both endpoints and every query field, the container decoding order, the `kuwo:` octal offset
 * divisor arithmetic (`(a+b)/(2*offset)`, `(a-b)/(2*offset2)+start`), the overlapping-word clamp and the duplicate
 * timestamp → translation rule. What is rewritten: Kotlin/OkHttp instead of the reference's RN fetch, and the
 * karaoke lines are kept as structured timings instead of being stringified and re-parsed.
 *
 * ## Failure discipline
 *
 * Every network/decode/parse failure returns null; nothing throws out of [fetch]. The caller (the extra-provider
 * orchestrator) bounds each source with a timeout, so a slow Kuwo cannot hang the lyric panel.
 */
class KwLyric(private val client: OkHttpClient = defaultClient()) {

    /** Searches Kuwo by `title + artist`; results are plain candidates for the shared matcher. */
    suspend fun search(keyword: String): List<Track> {
        if (keyword.isBlank()) return emptyList()
        val url = "http://search.kuwo.cn/r.s?client=kt&all=${enc(keyword)}&pn=0&rn=8&uid=794762570" +
            "&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0" +
            "&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        return runCatching {
            val text = get(url) ?: return@runCatching emptyList()
            val json = JSONObject(text)
            val list = json.optJSONArray("abslist") ?: return@runCatching emptyList()
            (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val mid = item.optString("MUSICRID").replace("MUSIC_", "").trim()
                if (mid.isBlank()) return@mapNotNull null
                val name = item.optString("SONGNAME").ifBlank { return@mapNotNull null }
                val duration = item.optString("DURATION").toIntOrNull() ?: 0
                Track(
                    id = ProviderTrackIds.idFor(PROVIDER, mid),
                    name = name,
                    artists = item.optString("ARTIST").replace('&', '、')
                        .split('、').map { it.trim() }.filter { it.isNotEmpty() },
                    albumName = item.optString("ALBUM"),
                    albumId = 0L,
                    coverUrl = null,
                    durationMs = duration * 1000L,
                    providerRef = ProviderRef(
                        provider = PROVIDER,
                        providerId = mid,
                        songId = mid,
                        intervalSeconds = duration,
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Fetches and decodes the lyrics for a Kuwo numeric song id; null when Kuwo has nothing decodable. */
    suspend fun fetch(songId: Long): ExtraLyricPayload? {
        val url = "http://mlyric.kuwo.cn/mobi.s?f=web&type=lyric&lrcx=1&rid=$songId&encode=utf8"
        return runCatching {
            val raw = getBytes(url) ?: return@runCatching null
            val text = decodeContainer(raw) ?: return@runCatching null
            parse(text)
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    private fun getBytes(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.bytes()
        }
    }.getOrNull()

    companion object {
        const val PROVIDER = "kw"
        private const val UA = "Mozilla/5.0 (Linux; Android 13)"
        private val XorKey = "yeelion".toByteArray(Charsets.ISO_8859_1)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

        private fun enc(text: String): String =
            java.net.URLEncoder.encode(text, "UTF-8")

        /**
         * `TP=content\r\n...\r\n\r\n<zlib>` → base64 text → XOR `yeelion` → UTF-8 LRC.
         *
         * The reference compares the first 10 bytes case-insensitively with `tp=content`; anything else is a
         * different payload (often an error page) and is rejected rather than parsed.
         */
        fun decodeContainer(raw: ByteArray): String? {
            val head = String(raw, 0, minOf(10, raw.size), Charsets.ISO_8859_1)
            if (!head.equals("tp=content", ignoreCase = true)) return null
            val marker = indexOf(raw, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)) ?: return null
            val inflated = inflate(raw, marker + 4) ?: return null
            val base64 = String(inflated, Charsets.ISO_8859_1).trim()
            val decoded = runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
            val out = ByteArray(decoded.size)
            for (i in decoded.indices) out[i] = (decoded[i].toInt() xor XorKey[i % XorKey.size].toInt()).toByte()
            return String(out, Charsets.UTF_8).takeIf { it.isNotBlank() }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
            if (needle.isEmpty() || haystack.size < needle.size) return null
            outer@ for (i in 0..(haystack.size - needle.size)) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return null
        }

        private fun inflate(raw: ByteArray, offset: Int): ByteArray? = runCatching {
            val inflater = Inflater()
            inflater.setInput(raw, offset, raw.size - offset)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) break
                out.write(buffer, 0, n)
            }
            inflater.end()
            out.toByteArray()
        }.getOrNull()

        private val wordLine = Regex("^\\[(\\d{1,2}:[\\d:.]+)\\]\\s*(.*)$")
        private val wordTimeAll = Regex("<(-?\\d+),(-?\\d+)(?:,-?\\d+)?>")
        private val tagLine = Regex("^\\[(ver|ti|ar|al|offset|by|kuwo):\\s*(.*?)\\]$")
        private val lyricxTag = Regex("^<-?\\d+,-?\\d+>")

        /** Parses the decoded Kuwo lyric text (synthetic-vector testable; no Android/network). */
        fun parse(text: String): ExtraLyricPayload {
            var offset = 1
            var offset2 = 1
            var isOk = true
            val metadata = mutableListOf<String>()

            data class Entry(
                val timeMs: Long,
                val plainText: String,
                val words: List<ExtraWord>,
            )

            val entries = mutableListOf<Entry>()
            val rawLines = text.split("\r\n", "\r", "\n")
            for (rawLine in rawLines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val wm = wordLine.find(line)
                if (wm != null) {
                    val timeMs = parseTime(wm.groupValues[1]) ?: continue
                    val body = wm.groupValues[2]
                    val tagMatches = wordTimeAll.findAll(body).toList()
                    val words = mutableListOf<ExtraWord>()
                    if (tagMatches.isNotEmpty()) {
                        var previousStart = -1L
                        var previousEnd = -1L
                        tagMatches.forEachIndexed { index, match ->
                            val a = match.groupValues[1].toLongOrNull() ?: return@forEachIndexed
                            val b = match.groupValues[2].toLongOrNull() ?: return@forEachIndexed
                            var start = kotlin.math.abs((a + b) / (2L * offset))
                            var end = kotlin.math.abs((a - b) / (2L * offset2)) + start
                            if (words.isNotEmpty() && start < previousEnd) {
                                val last = words.removeAt(words.lastIndex)
                                val fixedEnd = maxOf(start, last.offsetMs)
                                words += last.copy(durationMs = fixedEnd - last.offsetMs)
                                previousEnd = fixedEnd
                            }
                            val textEnd = if (index + 1 < tagMatches.size) tagMatches[index + 1].range.first else body.length
                            val wordText = body.substring(match.range.last + 1, textEnd).trim()
                            words += ExtraWord(
                                text = wordText,
                                offsetMs = start,
                                durationMs = (end - start).coerceAtLeast(0L),
                            )
                            previousStart = start
                            previousEnd = end
                        }
                    }
                    val plain = wordTimeAll.replace(body, "").trim()
                    entries += Entry(timeMs, plain, words)
                    continue
                }
                val tm = tagLine.find(line) ?: continue
                if (tm.groupValues[1] == "kuwo") {
                    val content = tm.groupValues[2].substringBefore(']')
                    val value = content.toIntOrNull(8)
                    if (value == null) {
                        isOk = false
                    } else {
                        offset = value / 10
                        offset2 = value % 10
                        if (offset == 0 || offset2 == 0) isOk = false
                    }
                } else if (line.startsWith("[")) {
                    metadata += line
                }
            }
            if (!isOk || entries.isEmpty()) {
                return ExtraLyricPayload(PROVIDER, null, null, emptyList())
            }

            // Duplicate timestamps are Kuwo's translation lines: the reference retimes the earlier line onto the
            // previous main timestamp and keeps the later one as the main line (lyric.js sortLrcArr).
            val seen = HashSet<Long>()
            val main = mutableListOf<Entry>()
            val translation = mutableListOf<Entry>()
            val isLyricx = entries.any { lyricxTag.containsMatchIn(it.plainText) || it.words.isNotEmpty() }
            for (entry in entries) {
                if (!seen.add(entry.timeMs)) {
                    if (main.size < 2) continue
                    val popped = main.removeAt(main.lastIndex)
                    val retimed = popped.copy(timeMs = main.lastOrNull()?.timeMs ?: popped.timeMs)
                    translation += retimed
                    main += entry
                } else {
                    main += entry
                }
            }
            // The reference rejects plain lyrics that are more than 30% duplicated timestamps but not karaoke.
            if (!isLyricx && translation.size > main.size * 0.3 && main.size - translation.size > 6) {
                return ExtraLyricPayload(PROVIDER, null, null, emptyList())
            }

            val plainLrc = buildString {
                metadata.forEach { appendLine(it) }
                main.forEach { appendLine("[${formatTime(it.timeMs)}]${it.plainText}") }
            }.trim().ifBlank { null }
            val translationLrc = if (translation.isEmpty()) {
                null
            } else {
                translation.joinToString("\n") { "[${formatTime(it.timeMs)}]${it.plainText}" }
            }
            val lines = main.mapNotNull { entry ->
                if (entry.words.isEmpty()) return@mapNotNull null
                val end = entry.timeMs + entry.words.maxOf { it.offsetMs + it.durationMs }
                ExtraWordLine(
                    startMs = entry.timeMs,
                    durationMs = (end - entry.timeMs).coerceAtLeast(0L),
                    words = entry.words,
                )
            }
            return ExtraLyricPayload(PROVIDER, plainLrc, translationLrc, lines)
        }

        private fun parseTime(tag: String): Long? {
            val parts = tag.split(':')
            if (parts.size != 2) return null
            val minutes = parts[0].toLongOrNull() ?: return null
            val seconds = parts[1].toDoubleOrNull() ?: return null
            return minutes * 60_000 + (seconds * 1000).toLong()
        }

        private fun formatTime(ms: Long): String {
            val clamped = ms.coerceAtLeast(0L)
            return "%02d:%02d.%03d".format(clamped / 60_000, (clamped % 60_000) / 1000, clamped % 1000)
        }
    }
}
