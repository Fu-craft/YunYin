// SPDX-License-Identifier: AGPL-3.0-or-later
package com.yunyin.music.aura

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Fetches and caches Apple Music square motion artwork (`motionDetailSquare`) as local MP4 files.
 * Inspired by HyperChanger's MotionArt/MotionCover pipeline, adapted for Aura-Player.
 */
object AppleMusicMotionCoverManager {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val STOREFRONTS = listOf("cn", "us")
    private const val MAX_CANDIDATES = 3
    private const val MAX_SIDE = 1080
    private const val MAX_BYTES = 40L * 1024 * 1024
    private const val DURATION_SLACK_MS = 3000L
    private const val CACHE_BUDGET_BYTES = 512L * 1024 * 1024
    private const val NONE_TTL_MS = 7L * 24 * 3600 * 1000
    private const val FAIL_TTL_MS = 30L * 60 * 1000
    private const val DIR_NAME = "am_motion_cache"
    private const val INDEX_NAME = "index.txt"

    private val ALBUM_URL_REGEX = Regex("/album/(?:[^/?]+/)?(\\d+)\\?i=(\\d+)")
    private val FEAT_REGEX = Regex("\\s*[(\\[（【](feat\\.?|ft\\.|with)\\s[^)\\]）】]*[)\\]）】]", RegexOption.IGNORE_CASE)
    private val EDITION_REGEX = Regex("\\s+-\\s+(single|ep)$", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT_REGEX = Regex("\\s*(/|、|,|，|&| feat\\.? | ft\\. | x )\\s*", RegexOption.IGNORE_CASE)

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val indexMutex = Mutex()
    private val lookupMutex = Mutex()
    private val indexCache = ConcurrentHashMap<String, IndexEntry>()
    @Volatile
    private var indexLoaded = false

    internal data class IndexEntry(val state: String, val timestampMs: Long)
    internal data class SongCandidate(val songId: String, val albumId: String)
    internal data class AlbumMotionInfo(val albumTitle: String, val masterUrl: String?)
    internal data class MotionArtMatch(val albumId: String, val albumTitle: String, val masterUrl: String)
    internal data class ByteRange(val offset: Long, val length: Long)
    internal data class MediaByteRanges(val fileUrl: String, val ranges: List<ByteRange>)
    internal class TransientError(message: String) : IOException(message)

    fun peekCachedFile(context: Context, song: Song): File? {
        if (song.title.isBlank()) return null
        val key = albumCacheKey(song.title, song.artist, song.album, song.albumArtist)
        val appContext = context.applicationContext
        ensureIndexLoadedSync(appContext)
        val entry = indexCache[key] ?: return null
        if (!entry.state.startsWith("v:")) return null
        val file = File(cacheDir(appContext), entry.state.removePrefix("v:"))
        return if (file.isFile && file.length() > 0L) file else null
    }

    suspend fun resolve(context: Context, song: Song, activePlayingPath: String? = null): File? = withContext(Dispatchers.IO) {
        if (song.title.isBlank()) return@withContext null
        val appContext = context.applicationContext
        val key = albumCacheKey(song.title, song.artist, song.album, song.albumArtist)
        val dir = cacheDir(appContext)
        ensureIndexLoaded(appContext)

        val existing = indexCache[key]
        val now = System.currentTimeMillis()
        if (existing != null) {
            when {
                existing.state.startsWith("v:") -> {
                    val f = File(dir, existing.state.removePrefix("v:"))
                    if (f.isFile && f.length() > 0L) {
                        f.setLastModified(now)
                        return@withContext f
                    }
                }
                existing.state == "none" && now - existing.timestampMs < NONE_TTL_MS -> return@withContext null
                existing.state == "fail" && now - existing.timestampMs < FAIL_TTL_MS -> return@withContext null
            }
        }

        lookupMutex.withLock {
            ensureIndexLoaded(appContext)
            val recheck = indexCache[key]
            val recheckNow = System.currentTimeMillis()
            if (recheck != null) {
                when {
                    recheck.state.startsWith("v:") -> {
                        val f = File(dir, recheck.state.removePrefix("v:"))
                        if (f.isFile && f.length() > 0L) {
                            f.setLastModified(recheckNow)
                            return@withLock f
                        }
                    }
                    recheck.state == "none" && recheckNow - recheck.timestampMs < NONE_TTL_MS -> return@withLock null
                    recheck.state == "fail" && recheckNow - recheck.timestampMs < FAIL_TTL_MS -> return@withLock null
                }
            }

            try {
                val found = findMotionArt(
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    albumArtist = song.albumArtist,
                    durationMs = song.duration,
                )
                if (found == null) {
                    putIndexEntry(appContext, key, "none")
                    return@withLock null
                }
                val fileName = "${found.albumId}.mp4"
                val outFile = File(dir, fileName)
                if (!outFile.isFile || outFile.length() == 0L) {
                    val ok = downloadMotionMp4(found.masterUrl, outFile)
                    if (!ok) {
                        putIndexEntry(appContext, key, "none")
                        return@withLock null
                    }
                    trimCache(dir, activePlayingPath)
                }
                outFile.setLastModified(System.currentTimeMillis())
                putIndexEntry(appContext, key, "v:$fileName")
                outFile
            } catch (_: TransientError) {
                putIndexEntry(appContext, key, "fail")
                null
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                putIndexEntry(appContext, key, "fail")
                null
            }
        }
    }

    internal suspend fun findMotionArt(
        title: String,
        artist: String,
        album: String,
        albumArtist: String = "",
        durationMs: Long,
        fetcher: suspend (String) -> String? = { url -> fetchText(url) },
    ): MotionArtMatch? {
        val searchArtist = firstArtist(artist.ifBlank { albumArtist })
        val term = "${stripFeat(title)} $searchArtist".trim()
        if (term.isBlank()) return null

        var anyAnswer = false
        for (cc in STOREFRONTS) {
            currentCoroutineContext().ensureActive()
            val searchUrl = "https://music.apple.com/$cc/search?term=${encodeParam(term)}"
            val page = fetcher(searchUrl) ?: continue
            anyAnswer = true
            val searchSections = extractSectionsJson(page) ?: continue
            val songs = parseSongCandidates(searchSections, title, artist.ifBlank { albumArtist })
            if (songs.isEmpty()) continue

            val candidates = songs.take(MAX_CANDIDATES)
            val albumPages = coroutineScope {
                candidates.map { candidate ->
                    async { fetcher("https://music.apple.com/$cc/album/${candidate.albumId}") }
                }.awaitAll()
            }

            var fallback: MotionArtMatch? = null
            val wantAlbumNorm = norm(stripEdition(album))
            for (k in candidates.indices) {
                val candidate = candidates[k]
                val albumPage = albumPages[k] ?: continue
                val sections = extractSectionsJson(albumPage) ?: continue
                val storeMs = extractTrackDurationMs(sections, candidate.songId)
                if (durationMs > 0L && storeMs > 0L && abs(storeMs - durationMs) > DURATION_SLACK_MS) {
                    continue
                }
                val info = extractAlbumHeaderMotion(sections) ?: continue
                val storeAlbumNorm = norm(stripEdition(info.albumTitle))
                val sameAlbum = wantAlbumNorm.isNotEmpty() && storeAlbumNorm == wantAlbumNorm
                if (sameAlbum || wantAlbumNorm.isEmpty()) {
                    return info.masterUrl?.let { MotionArtMatch(candidate.albumId, info.albumTitle, it) }
                }
                if (fallback == null && info.masterUrl != null) {
                    fallback = MotionArtMatch(candidate.albumId, info.albumTitle, info.masterUrl)
                }
            }
            if (fallback != null) return fallback
        }
        if (!anyAnswer) throw TransientError("no storefront answered")
        return null
    }

    internal fun albumCacheKey(
        title: String,
        artist: String,
        album: String,
        albumArtist: String = "",
    ): String {
        val effectiveArtist = firstArtist(albumArtist.ifBlank { artist })
        val normArtist = norm(effectiveArtist)
        val normAlbum = norm(album)
        return if (normAlbum.isNotEmpty() && normArtist.isNotEmpty()) {
            "a:$normArtist|$normAlbum"
        } else {
            "s:$normArtist|${norm(stripFeat(title))}"
        }
    }

    internal fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val out = StringBuilder(n.length)
        var i = 0
        while (i < n.length) {
            val cp = n.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    internal fun stripFeat(s: String?): String =
        if (s.isNullOrEmpty()) "" else FEAT_REGEX.replace(s, "")

    internal fun stripEdition(s: String?): String =
        if (s.isNullOrEmpty()) "" else EDITION_REGEX.replace(s, "")

    internal fun firstArtist(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val parts = ARTIST_SPLIT_REGEX.split(s.trim())
        return parts.firstOrNull { it.isNotBlank() }?.trim() ?: s.trim()
    }

    internal fun extractSectionsJson(html: String): JSONArray? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        if (start <= 0) return null
        val end = html.indexOf("</script>", start)
        if (end < 0) return null
        return runCatching {
            val root = JSONObject(html.substring(start, end))
            root.getJSONArray("data").getJSONObject(0).getJSONObject("data").getJSONArray("sections")
        }.getOrNull()
    }

    internal fun parseSongCandidates(sections: JSONArray, title: String, artist: String): List<SongCandidate> {
        val out = mutableListOf<SongCandidate>()
        val seenSongIds = HashSet<String>()
        val wantTitle = norm(stripFeat(title))
        val wantArtist = norm(firstArtist(artist))
        if (wantTitle.isEmpty()) return out

        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val items = sec.optJSONArray("items") ?: continue
            for (k in 0 until items.length()) {
                val item = items.optJSONObject(k) ?: continue
                val cd = item.optJSONObject("contentDescriptor") ?: continue
                if (cd.optString("kind") != "song") continue
                if (wantTitle != norm(stripFeat(item.optString("title", "")))) continue
                val by = subtitleArtist(item)
                val normBy = norm(by)
                if (wantArtist.isNotEmpty() && normBy.isNotEmpty() &&
                    !normBy.contains(wantArtist) && !wantArtist.contains(normBy)
                ) {
                    continue
                }
                val match = ALBUM_URL_REGEX.find(cd.optString("url", "")) ?: continue
                val albumId = match.groupValues[1]
                val songId = match.groupValues[2]
                if (seenSongIds.add(songId)) {
                    out += SongCandidate(songId = songId, albumId = albumId)
                }
            }
        }
        return out
    }

    private fun subtitleArtist(item: JSONObject): String {
        val links = item.optJSONArray("subtitleLinks")
        if (links != null && links.length() > 0) {
            val first = links.optJSONObject(0)
            if (first != null) return first.optString("title", "")
        }
        val sub = item.optString("subtitle", "")
        val dot = sub.indexOf('·')
        return if (dot >= 0) sub.substring(dot + 1).trim() else ""
    }

    internal fun extractAlbumHeaderMotion(sections: JSONArray): AlbumMotionInfo? {
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            if (!sec.optString("id").startsWith("album-detail-header")) continue
            val header = sec.optJSONArray("items")?.optJSONObject(0) ?: return null
            val albumTitle = header.optString("title", "")
            val master = header.optJSONObject("videoArtwork")
                ?.optJSONObject("dictionary")
                ?.optJSONObject("motionDetailSquare")
                ?.optString("video", "")
                ?.takeIf { it.startsWith("https://") }
            return AlbumMotionInfo(albumTitle = albumTitle, masterUrl = master)
        }
        return null
    }

    internal fun extractTrackDurationMs(sections: JSONArray, songId: String): Long {
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            if (!sec.optString("id").startsWith("track-list")) continue
            val items = sec.optJSONArray("items") ?: continue
            for (k in 0 until items.length()) {
                val item = items.optJSONObject(k) ?: continue
                val ids = item.optJSONObject("contentDescriptor")?.optJSONObject("identifiers") ?: continue
                if (songId == ids.optString("storeAdamID")) {
                    return item.optLong("duration", 0L)
                }
            }
        }
        return 0L
    }

    internal fun pickSdrH264Variant(masterUrl: String, m3u8: String): String? {
        var best: String? = null
        var bestSide = 0
        var bestBw = Long.MAX_VALUE
        val lines = m3u8.lines()
        for (i in 0 until lines.size - 1) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val codecs = playlistAttr(line, "CODECS") ?: continue
            val range = playlistAttr(line, "VIDEO-RANGE")
            val res = playlistAttr(line, "RESOLUTION") ?: continue
            val bw = playlistAttr(line, "BANDWIDTH")?.toLongOrNull() ?: Long.MAX_VALUE
            if (!codecs.startsWith("avc1") || range != "SDR") continue
            val wh = res.split('x')
            if (wh.size != 2) continue
            val w = wh[0].toIntOrNull() ?: 0
            val h = wh[1].toIntOrNull() ?: 0
            val side = max(w, h)
            if (w <= 0 || h <= 0 || side > MAX_SIDE) continue
            if (side > bestSide || (side == bestSide && bw < bestBw)) {
                val resolved = resolveUrl(masterUrl, lines[i + 1].trim()) ?: continue
                best = resolved
                bestSide = side
                bestBw = bw
            }
        }
        return best
    }

    internal fun parseMediaByteRanges(mediaUrl: String, playlist: String): MediaByteRanges? {
        var fileUrl: String? = null
        var nextOffset = 0L
        var pendingRange: String? = null
        val out = mutableListOf<ByteRange>()

        for (raw in playlist.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = resolveUrl(mediaUrl, playlistAttr(line, "URI")) ?: return null
                    val range = parseRangeSpec(playlistAttr(line, "BYTERANGE"), 0L) ?: return null
                    fileUrl = uri
                    out += range
                    nextOffset = range.offset + range.length
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = line.removePrefix("#EXT-X-BYTERANGE:").trim()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val uri = resolveUrl(mediaUrl, line) ?: return null
                    val range = pendingRange?.let { parseRangeSpec(it, nextOffset) } ?: return null
                    pendingRange = null
                    if (fileUrl == null) fileUrl = uri
                    if (fileUrl != uri) return null
                    out += range
                    nextOffset = range.offset + range.length
                }
            }
        }
        val resolvedFile = fileUrl ?: return null
        return if (out.isEmpty()) null else MediaByteRanges(resolvedFile, out)
    }

    private fun parseRangeSpec(spec: String?, nextOffset: Long): ByteRange? {
        if (spec == null) return null
        val clean = spec.replace("\"", "").trim()
        val at = clean.indexOf('@')
        val len = (if (at < 0) clean else clean.substring(0, at)).trim().toLongOrNull() ?: return null
        val off = if (at < 0) nextOffset else clean.substring(at + 1).trim().toLongOrNull() ?: return null
        return if (len > 0L && off >= 0L) ByteRange(offset = off, length = len) else null
    }

    private fun playlistAttr(line: String, name: String): String? {
        val pattern = Regex("(?:^|[:,])${Regex.escape(name)}=(\"[^\"]*\"|[^,]*)")
        val match = pattern.find(line) ?: return null
        val raw = match.groupValues[1]
        return if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private fun resolveUrl(base: String, ref: String?): String? {
        if (ref.isNullOrBlank()) return null
        return runCatching { URL(URL(base), ref).toString() }.getOrNull()
    }

    private fun encodeParam(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8").replace("+", "%20") }.getOrDefault(value)

    private fun fetchText(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    internal fun downloadMotionMp4(masterUrl: String, outFile: File): Boolean {
        val m3u = fetchText(masterUrl) ?: throw TransientError("master")
        val variantUrl = pickSdrH264Variant(masterUrl, m3u) ?: return false
        val mediaPlaylist = fetchText(variantUrl) ?: throw TransientError("media playlist")
        val byteRanges = parseMediaByteRanges(variantUrl, mediaPlaylist) ?: return false

        val fragFile = File("${outFile.path}.frag")
        val tmpFile = File("${outFile.path}.tmp")
        return try {
            var downloadedBytes = 0L
            FileOutputStream(fragFile).use { os ->
                var i = 0
                while (i < byteRanges.ranges.size) {
                    val from = byteRanges.ranges[i].offset
                    var to = from + byteRanges.ranges[i].length
                    var j = i + 1
                    while (j < byteRanges.ranges.size && byteRanges.ranges[j].offset == to) {
                        to += byteRanges.ranges[j].length
                        j++
                    }
                    downloadedBytes += fetchByteRange(
                        uri = byteRanges.fileUrl,
                        from = from,
                        to = to,
                        os = os,
                        budgetRemaining = MAX_BYTES - downloadedBytes,
                    )
                    i = j
                }
            }
            remuxZeroBasedMp4(fragFile, tmpFile)
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) throw IOException("rename failed")
            true
        } catch (t: TransientError) {
            throw t
        } catch (_: Throwable) {
            false
        } finally {
            fragFile.delete()
            tmpFile.delete()
        }
    }

    private fun fetchByteRange(
        uri: String,
        from: Long,
        to: Long,
        os: FileOutputStream,
        budgetRemaining: Long,
    ): Long {
        val want = to - from
        if (want > budgetRemaining) throw IOException("exceeds byte budget")
        val req = Request.Builder()
            .url(uri)
            .header("User-Agent", UA)
            .header("Range", "bytes=$from-${to - 1}")
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (resp.code != 206 && !(resp.code == 200 && from == 0L)) {
                    throw TransientError("range answered ${resp.code}")
                }
                val stream = resp.body?.byteStream() ?: throw TransientError("empty body")
                val buf = ByteArray(64 * 1024)
                var got = 0L
                stream.use { input ->
                    while (got < want) {
                        val read = input.read(buf, 0, minOf(buf.size.toLong(), want - got).toInt())
                        if (read <= 0) break
                        os.write(buf, 0, read)
                        got += read
                    }
                }
                if (got != want) throw TransientError("short read $got/$want")
                return got
            }
        } catch (e: SocketException) {
            throw TransientError(e.message ?: "socket")
        } catch (e: SocketTimeoutException) {
            throw TransientError(e.message ?: "timeout")
        } catch (e: UnknownHostException) {
            throw TransientError(e.message ?: "dns")
        }
    }

    /**
     * Copies H.264 samples from Apple's fragmented MP4 into a plain MP4 whose timestamps start at 0
     * so seamless video looping lands directly back on the first frame.
     */
    private fun remuxZeroBasedMp4(src: File, dst: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(src.path)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("video/avc")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) throw IOException("no H.264 track")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()
            started = true
            val cap = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                4 * 1024 * 1024
            }
            val buffer = ByteBuffer.allocate(max(cap, 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var origin = -1L
            var samples = 0
            while (true) {
                buffer.clear()
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                val t = extractor.sampleTime
                if (origin < 0L) origin = t
                val flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, n, max(0L, t - origin), flags)
                muxer.writeSampleData(outTrack, buffer, info)
                samples++
                extractor.advance()
            }
            if (samples == 0) throw IOException("no samples")
        } finally {
            extractor.release()
            if (muxer != null) {
                runCatching { if (started) muxer.stop() }
                runCatching { muxer.release() }
            }
        }
    }

    internal fun cacheDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!isDirectory) mkdirs() }

    private fun ensureIndexLoadedSync(context: Context) {
        if (indexLoaded) return
        synchronized(this) {
            if (indexLoaded) return
            val indexFile = File(cacheDir(context), INDEX_NAME)
            if (indexFile.isFile) {
                runCatching {
                    indexFile.readLines().forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size == 3) {
                            val ts = parts[2].toLongOrNull() ?: 0L
                            indexCache[parts[0]] = IndexEntry(parts[1], ts)
                        }
                    }
                }
            }
            indexLoaded = true
        }
    }

    private suspend fun ensureIndexLoaded(context: Context) {
        if (indexLoaded) return
        indexMutex.withLock {
            ensureIndexLoadedSync(context)
        }
    }

    private suspend fun putIndexEntry(context: Context, key: String, state: String) {
        indexMutex.withLock {
            ensureIndexLoadedSync(context)
            indexCache[key] = IndexEntry(state, System.currentTimeMillis())
            if (indexCache.size > 2000) {
                val oldestKey = indexCache.entries.minByOrNull { it.value.timestampMs }?.key
                if (oldestKey != null) indexCache.remove(oldestKey)
            }
            val lines = indexCache.entries.map { "${it.key}\t${it.value.state}\t${it.value.timestampMs}" }
            val dir = cacheDir(context)
            val tmp = File(dir, "$INDEX_NAME.tmp")
            val dst = File(dir, INDEX_NAME)
            runCatching {
                tmp.writeText(lines.joinToString("\n"))
                if (!tmp.renameTo(dst)) {
                    dst.writeText(lines.joinToString("\n"))
                    tmp.delete()
                }
            }
        }
    }

    internal fun trimCache(dir: File, activePlayingPath: String?) {
        val files = dir.listFiles { _, name -> name.endsWith(".mp4") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_BUDGET_BYTES) return
        files.sortBy { it.lastModified() }
        for (file in files) {
            if (total <= CACHE_BUDGET_BYTES) break
            if (activePlayingPath != null && file.absolutePath == activePlayingPath) continue
            total -= file.length()
            file.delete()
        }
    }
}
