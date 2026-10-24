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
 * Kugou (酷狗音乐) lyrics: hash search + KRC decryption + word timing, with plain-LRC fallback.
 *
 * ## Provenance
 *
 * Ported from **lyswhut/lx-music-mobile** (GPL-3.0):
 *  - `src/utils/musicSdk/kg/lyric.js` — the lyric search (`lyrics.kugou.com/search`, headers `KG-RC`/`KG-THash`/
 *    `KuGou2012-9020-ExpandSearchManager`), the format choice (`krctype == 1 && contenttype != 1` → krc), the
 *    download call and `parseLyric` (line-header conversion, `<offset,dur,?>` word tags, `[language:]` translations);
 *  - `src/utils/musicSdk/kg/util.js` — the KRC decryption: base64 → drop 4 bytes → XOR with the 16-byte key
 *    `40 47 61 77 5e 32 74 47 51 36 31 2d ce d2 6e 69` → zlib inflate;
 *  - `src/utils/musicSdk/kg/musicSearch.js` is **not** used: the app has no Kugou track identity, so the mobile
 *    search endpoint supplies the `hash` that the lyric endpoints require.
 *
 * What is faithful: the key bytes and their order, the 4-byte prefix, the container order, the request fields and
 * headers, the tag→timestamp conversion and the translation block. What is rewritten: Kotlin/OkHttp, structured
 * word timings instead of the reference's string round-trip.
 *
 * ## Failure discipline
 *
 * Null on any failure (no candidate, non-KRC without LRC, decode/JSON error); never throws out of [fetch].
 */
class KgLyric(private val client: OkHttpClient = defaultClient()) {

    /** Finds a Kugou candidate by keyword; the returned track carries the `hash` needed by the lyric search. */
    suspend fun search(keyword: String): List<Track> {
        if (keyword.isBlank()) return emptyList()
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json" +
            "&keyword=${enc(keyword)}&page=1&pagesize=5&showtype=1"
        return runCatching {
            val text = get(url, emptyMap()) ?: return@runCatching emptyList()
            val info = JSONObject(text).optJSONObject("data")?.optJSONArray("info")
                ?: return@runCatching emptyList()
            (0 until info.length()).mapNotNull { index ->
                val item = info.optJSONObject(index) ?: return@mapNotNull null
                val hash = item.optString("hash").trim()
                if (hash.isBlank()) return@mapNotNull null
                val name = item.optString("songname").ifBlank { return@mapNotNull null }
                val durationSec = item.optInt("duration")
                val singer = item.optString("singername")
                Track(
                    id = ProviderTrackIds.idFor(PROVIDER, hash),
                    name = name,
                    artists = singer.replace('、', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() },
                    albumName = item.optString("album_name"),
                    albumId = 0L,
                    coverUrl = null,
                    durationMs = durationSec * 1000L,
                    providerRef = ProviderRef(
                        provider = PROVIDER,
                        providerId = hash,
                        songId = hash,
                        intervalSeconds = durationSec,
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Fetches KRC (or LRC) lyrics for a Kugou hash; null when nothing decodable came back. */
    suspend fun fetch(hash: String, keyword: String, durationMs: Long): ExtraLyricPayload? {
        val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=${enc(keyword)}&hash=${enc(hash)}&timelength=${durationMs.coerceAtLeast(0L)}&lrctxt=1"
        return runCatching {
            val searchJson = JSONObject(get(searchUrl, LYRIC_HEADERS) ?: return@runCatching null)
            val candidates = searchJson.optJSONArray("candidates") ?: return@runCatching null
            val candidate = candidates.optJSONObject(0) ?: return@runCatching null
            val id = candidate.optString("id").ifBlank { return@runCatching null }
            val accessKey = candidate.optString("accesskey")
            val isKrc = candidate.optInt("krctype") == 1 && candidate.optInt("contenttype") != 1
            val format = if (isKrc) "krc" else "lrc"
            val downloadUrl = "http://lyrics.kugou.com/download?ver=1&client=pc" +
                "&id=${enc(id)}&accesskey=${enc(accessKey)}&fmt=$format&charset=utf8"
            val download = JSONObject(get(downloadUrl, LYRIC_HEADERS) ?: return@runCatching null)
            val content = download.optString("content").ifBlank { return@runCatching null }
            val actualFormat = download.optString("fmt").ifBlank { format }
            if (actualFormat == "krc") {
                val krc = decodeKrc(content) ?: return@runCatching null
                parseKrc(krc)
            } else {
                val lrc = runCatching { String(java.util.Base64.getDecoder().decode(content), Charsets.UTF_8) }
                    .getOrNull() ?: return@runCatching null
                parsePlainLrc(lrc)
            }
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    private fun get(url: String, headers: Map<String, String>): String? = runCatching {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.getOrNull()

    companion object {
        const val PROVIDER = "kg"
        private const val UA = "KuGou2012-9020-ExpandSearchManager"
        private val LYRIC_HEADERS = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to UA,
        )

        /** The exact 16 bytes from lx's `kg/util.js`. */
        private val KrcKey = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

        private fun enc(text: String): String = java.net.URLEncoder.encode(text, "UTF-8")

        /** base64 → drop 4-byte prefix → XOR the 16-byte key → zlib inflate. Null on any mismatch. */
        fun decodeKrc(base64Content: String): String? {
            val decoded = runCatching { java.util.Base64.getDecoder().decode(base64Content.trim()) }.getOrNull()
                ?: return null
            if (decoded.size <= 4) return null
            val body = ByteArray(decoded.size - 4)
            for (i in body.indices) {
                body[i] = (decoded[i + 4].toInt() xor KrcKey[i % KrcKey.size].toInt()).toByte()
            }
            return runCatching {
                val inflater = Inflater()
                inflater.setInput(body)
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0) break
                    out.write(buffer, 0, n)
                }
                inflater.end()
                String(out.toByteArray(), Charsets.UTF_8).takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        private val headerLine = Regex("^\\[(\\d+),(\\d+)\\](.*)$")
        private val languageBlock = Regex("\\[language:([A-Za-z0-9+/=\\\\]+)\\]")
        private val krcWord = Regex("<(\\d+),(\\d+),\\d+>")
        private val lrcLine = Regex("^\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\](.*)$")

        /** Parses a decoded KRC payload into line + word timings (synthetic-vector testable). */
        fun parseKrc(krc: String): ExtraLyricPayload {
            var text = krc.replace("\r", "")
            text = text.replace(Regex("^.*\\[id:\\$\\w+\\]\\n"), "")
            var translationLines: List<String>? = null
            val language = languageBlock.find(text)
            if (language != null) {
                text = text.replace(language.value, "").replace("\n\n", "\n")
                translationLines = runCatching {
                    val json = JSONObject(
                        String(
                            java.util.Base64.getDecoder().decode(language.groupValues[1]),
                            Charsets.UTF_8,
                        ),
                    )
                    val content = json.optJSONArray("content") ?: return@runCatching null
                    var trans: List<String>? = null
                    for (i in 0 until content.length()) {
                        val item = content.optJSONObject(i) ?: continue
                        if (item.optInt("type") != 1) continue
                        val arr = item.optJSONArray("lyricContent") ?: continue
                        trans = (0 until arr.length()).map { lineIndex ->
                            val line = arr.optJSONArray(lineIndex)
                            if (line == null) {
                                arr.optString(lineIndex)
                            } else {
                                (0 until line.length()).joinToString("") { line.optString(it) }
                            }
                        }
                    }
                    trans
                }.getOrNull()
            }

            data class Entry(val startMs: Long, val durationMs: Long, val plain: String, val words: List<ExtraWord>)

            val entries = mutableListOf<Entry>()
            for (raw in text.split('\n')) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val header = headerLine.find(line)
                if (header != null) {
                    val start = header.groupValues[1].toLongOrNull() ?: continue
                    val duration = header.groupValues[2].toLongOrNull() ?: 0L
                    val body = header.groupValues[3]
                    val tagMatches = krcWord.findAll(body).toList()
                    val words = mutableListOf<ExtraWord>()
                    tagMatches.forEachIndexed { index, tag ->
                        val wordStart = tag.groupValues[1].toLongOrNull() ?: return@forEachIndexed
                        val wordDuration = tag.groupValues[2].toLongOrNull() ?: 0L
                        val textEnd = if (index + 1 < tagMatches.size) tagMatches[index + 1].range.first else body.length
                        words += ExtraWord(
                            text = body.substring(tag.range.last + 1, textEnd).trim(),
                            offsetMs = wordStart - start,
                            durationMs = wordDuration,
                        )
                    }
                    entries += Entry(start, duration, krcWord.replace(body, "").trim(), words)
                    continue
                }
                val lrc = lrcLine.find(line)
                if (lrc != null) {
                    val minutes = lrc.groupValues[1].toLongOrNull() ?: continue
                    val seconds = lrc.groupValues[2].toLongOrNull() ?: 0L
                    val fraction = lrc.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                    entries += Entry(
                        minutes * 60_000 + seconds * 1000 + fraction,
                        0L,
                        lrc.groupValues[4].trim(),
                        emptyList(),
                    )
                }
            }
            if (entries.isEmpty()) return ExtraLyricPayload(PROVIDER, null, null, emptyList())
            val translationLrc = translationLines
                ?.mapIndexed { index, line -> "[${timeTag(entries.getOrNull(index)?.startMs ?: 0L)}]$line" }
                ?.joinToString("\n")
                ?.takeIf { it.isNotBlank() }
            val plain = entries.joinToString("\n") { "[${timeTag(it.startMs)}]${it.plain}" }.ifBlank { null }
            val lines = entries.mapNotNull { entry ->
                if (entry.words.isEmpty()) return@mapNotNull null
                val end = entry.startMs + entry.words.maxOf { it.offsetMs + it.durationMs }
                ExtraWordLine(
                    startMs = entry.startMs,
                    durationMs = maxOf(entry.durationMs, end - entry.startMs),
                    words = entry.words,
                )
            }
            return ExtraLyricPayload(PROVIDER, plain, translationLrc, lines)
        }

        /** Plain-LRC fallback (the `fmt=lrc` download is base64 of a normal LRC document). */
        fun parsePlainLrc(lrc: String): ExtraLyricPayload {
            val entries = lrc.replace("\r", "").split('\n').mapNotNull { raw ->
                val match = lrcLine.find(raw.trim()) ?: return@mapNotNull null
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                (minutes * 60_000 + seconds * 1000 + fraction) to match.groupValues[4].trim()
            }
            if (entries.isEmpty()) return ExtraLyricPayload(PROVIDER, null, null, emptyList())
            val plain = entries.joinToString("\n") { (time, line) -> "[${timeTag(time)}]$line" }
            return ExtraLyricPayload(PROVIDER, plain, null, emptyList())
        }

        private fun parseClockToMs(text: String): Long? {
            val match = lrcLine.find("[$text]") ?: return null
            val minutes = match.groupValues[1].toLongOrNull() ?: return null
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            return minutes * 60_000 + seconds * 1000 + fraction
        }

        private fun timeTag(ms: Long): String {
            val clamped = ms.coerceAtLeast(0L)
            return "%02d:%02d.%03d".format(clamped / 60_000, (clamped % 60_000) / 1000, clamped % 1000)
        }
    }
}
