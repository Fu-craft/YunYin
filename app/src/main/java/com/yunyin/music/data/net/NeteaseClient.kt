package com.yunyin.music.data.net

import com.yunyin.music.core.Account
import com.yunyin.music.core.ArtistInfo
import com.yunyin.music.core.ChartInfo
import com.yunyin.music.core.LyricBundle
import com.yunyin.music.core.NetResult
import com.yunyin.music.core.Playlist
import com.yunyin.music.core.Track
import com.yunyin.music.core.map
import com.yunyin.music.data.SettingsStore
import com.yunyin.music.data.playlist.PlaylistRequestRules
import com.yunyin.music.data.playlist.PlaylistWrites
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp wrapper over a NeteaseCloudMusicApi (Binaryify) deployment.
 *
 * Design notes:
 *  - Responses are parsed with `org.json`, which ships with Android; the payloads
 *    are shallow and irregular, so hand-written mapping is clearer than a
 *    serialization model and avoids one more dependency.
 *  - Every call funnels through [request], which attaches the persisted cookie and
 *    converts failures into [NetResult.Err] so the UI never sees an exception.
 */
class NeteaseClient(
    private val settings: SettingsStore,
    /** Optional diagnostics trail (lxsource log) for lyric-fetch evidence. */
    private val diag: com.yunyin.music.data.lxsource.LxSourceLog? = null,
) {

    /**
     * Reads a string field, treating JSON null as absent.
     *
     * `org.json`'s `optString` returns the literal text `"null"` for a JSON null (it only falls
     * back to the default when the key is missing), so raw reads leak the word "null" into the
     * UI. Everything showing user-visible text goes through here.
     */
    private fun JSONObject.text(name: String): String? =
        optString(name).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    /** Same, for a nested object that may itself be absent. */
    private fun JSONObject?.nestedText(name: String): String? = this?.text(name)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---------------------------------------------------------------- transport

    private suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
    ): NetResult<JSONObject> = withContext(Dispatchers.IO) {
        // No endpoint configured for this build. Fail with an actionable message rather than
        // building a relative URL and issuing a meaningless request.
        if (!SettingsStore.hasBaseUrl) {
            return@withContext NetResult.Err(
                "未配置接口地址：请在 local.properties 设置 api.base.url 后重新构建",
            )
        }
        try {
            val url = buildString {
                append(SettingsStore.BASE_URL)
                if (!path.startsWith("/")) append('/')
                append(path)
                // The endpoint is fixed; there is no per-user proxy or client-IP override.
                val q = query.filterValues { it.isNotEmpty() }
                if (q.isNotEmpty()) {
                    append('?')
                    append(q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" })
                }
            }
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            settings.cookie.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            if (form != null) {
                val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
                builder.post(body)
            } else {
                builder.get()
            }

            val startedNs = System.nanoTime()
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                lastHttpBytes = text.toByteArray(Charsets.UTF_8).size
                lastHttpMs = (System.nanoTime() - startedNs) / 1_000_000

                // NetEase reports problems with a JSON body even on 4xx/5xx — notably risk
                // control, which comes back as HTTP 400 with code 10004 and a human
                // readable `message`. Parse the body first so that text is what the user
                // sees, instead of a bare "HTTP 400".
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    return@withContext NetResult.Err(
                        if (response.isSuccessful) "响应无法解析" else "HTTP ${response.code}",
                    )
                }

                val code = json.optInt("code", if (response.isSuccessful) 200 else response.code)
                val message = extractMessage(json)

                if (!response.isSuccessful || code !in 200..299) {
                    return@withContext NetResult.Err(message ?: "HTTP ${response.code}")
                }
                // A 200 response can still carry a failure code.
                if (json.optBoolean("success", true).not()) {
                    return@withContext NetResult.Err(message ?: "请求失败")
                }
                NetResult.Ok(json)
            }
        } catch (e: CancellationException) {
            // Structured cancellation is control flow, not a network failure. Re-throwing lets a
            // cancelled search/refresh stop before it can publish stale state to its caller.
            throw e
        } catch (e: IOException) {
            NetResult.Err(e.message ?: "网络错误")
        } catch (e: Exception) {
            NetResult.Err(e.message ?: "解析错误")
        }
    }

    /**
     * Pulls the human-readable reason out of a NetEase error body.
     *
     * These nest differently per endpoint: `message` at the top level, `msg` as a string,
     * or `msg` as an object (the proxy/tunnel errors). Known codes are mapped to an
     * actionable sentence because the raw text (e.g. "当前登录存在安全风险") does not tell
     * the user what to do about it.
     */
    private fun extractMessage(json: JSONObject): String? {
        val raw = when {
            json.optString("message").isNotBlank() -> json.optString("message")
            json.opt("msg") is String -> json.optString("msg")
            json.optJSONObject("msg") != null -> json.optJSONObject("msg")?.optString("message")
            json.optString("data").isNotBlank() -> json.optString("data")
            else -> null
        }
        val code = json.optInt("code", -1)
        return explain(code, raw)
    }

    /** Adds an actionable hint to the codes users actually hit. */
    private fun explain(code: Int, raw: String?): String? = when {
        code == 10004 || raw?.contains("安全风险") == true ->
            "网易风控拦截：${raw ?: "请稍后再试"}。可在「设置」填写你自己的 MUSIC_U Cookie 或配置代理。"
        code == 503 || raw?.contains("验证码") == true -> "验证码错误或已过期"
        code == 502 -> "接口异常：${raw ?: "请检查代理设置"}"
        code == 404 -> "接口不存在"
        else -> raw
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    // ---------------------------------------------------------------- manual cookie

    /**
     * Signs in by replaying a `MUSIC_U` cookie copied from a browser.
     *
     * The docs call this out explicitly ("可以直接从浏览器中获取cookie值, 只需要其中key为
     * MUSIC_U的数据即可"). It is the reliable route when the login endpoints are
     * risk-controlled, and it carries the account's own VIP entitlement — which is what
     * actually unlocks member-only tracks.
     */
    suspend fun loginWithCookie(rawCookie: String): NetResult<Account> {
        val cleaned = normalizeCookie(rawCookie)
        if (cleaned.isBlank()) return NetResult.Err("Cookie 为空")

        val previous = settings.cookie
        settings.cookie = cleaned
        val account = (loginStatus() as? NetResult.Ok)?.value
        return if (account != null) {
            NetResult.Ok(account)
        } else {
            // Revert so a bad paste does not silently break the session.
            settings.cookie = previous
            NetResult.Err("Cookie 无效或已过期（需要包含 MUSIC_U）")
        }
    }

    /**
     * Accepts either a bare `MUSIC_U=...` pair or a full copied cookie string, keeping
     * only well-formed `name=value` pairs.
     */
    private fun normalizeCookie(raw: String): String =
        raw.trim()
            .removePrefix("Cookie:")
            .split(';', '\n')
            .map { it.trim() }
            .filter { pair ->
                val name = pair.substringBefore('=', "").trim()
                name.isNotEmpty() &&
                    pair.contains('=') &&
                    // Drop Set-Cookie attributes if the whole header was pasted.
                    name.lowercase() !in setOf("max-age", "expires", "path", "domain", "secure", "httponly", "samesite")
            }
            .joinToString("; ")

    /** Signs out locally. The caller re-registers a guest session afterwards. */
    fun clearSession() = settings.clearSession()

    // ---------------------------------------------------------------- login

    /** Register a throwaway device account so playable URLs work before sign-in. */
    suspend fun registerAnonymous(): NetResult<Account> =
        request("/register/anonimous", form = emptyMap()).let { result ->
            when (result) {
                is NetResult.Err -> result
                is NetResult.Ok -> {
                    val json = result.value
                    val cookie = json.optString("cookie")
                    if (cookie.isNotBlank()) settings.cookie = cookie
                    val id = json.optLong("userId", 0L)
                    val account = Account(
                        userId = id,
                        nickname = "游客",
                        avatarUrl = null,
                        isAnonymous = true,
                    )
                    settings.account = account
                    NetResult.Ok(account)
                }
            }
        }


    suspend fun loginStatus(): NetResult<Account?> = request("/login/status").let { result ->
        when (result) {
            is NetResult.Err -> result
            is NetResult.Ok -> {
                val profile = result.value.optJSONObject("data")
                    ?.optJSONObject("profile")
                    ?: result.value.optJSONObject("data")?.optJSONObject("account")
                if (profile == null) {
                    NetResult.Ok(null)
                } else {
                    val account = Account(
                        userId = profile.optLong("userId"),
                        nickname = profile.text("nickname").orEmpty(),
                        avatarUrl = profile.text("avatarUrl"),
                    )
                    settings.account = account
                    NetResult.Ok(account)
                }
            }
        }
    }

    // ---------------------------------------------------------------- catalog

    suspend fun search(keyword: String, limit: Int = 30, offset: Int = 0): NetResult<List<Track>> =
        request(
            "/search",
            query = mapOf("keywords" to keyword, "limit" to "$limit", "offset" to "$offset", "type" to "1"),
        ).map { json ->
            json.optJSONObject("result")?.optJSONArray("songs")?.mapSearchTracks().orEmpty()
        }

    suspend fun hotSearch(): NetResult<List<String>> = request("/search/hot").map { json ->
        val hots = json.optJSONObject("result")?.optJSONArray("hots") ?: return@map emptyList()
        (0 until hots.length()).mapNotNull { hots.optJSONObject(it)?.optString("first")?.ifBlank { null } }
    }

    /** Enriches search hits with real cover art (search results only carry `picId`). */
    suspend fun songDetail(ids: List<Long>): NetResult<List<Track>> {
        if (ids.isEmpty()) return NetResult.Ok(emptyList())
        return request("/song/detail", query = mapOf("ids" to ids.joinToString(","))).map { json ->
            json.optJSONArray("songs")?.mapDetailTracks().orEmpty()
        }
    }

    /**
     * Result of resolving a stream URL.
     *
     * `url` may be a **trial fragment** for member-only tracks (`fee=1`): the service then
     * returns `freeTrialInfo` describing the playable window and no full track. That is
     * an entitlement limit, not a quality one — lowering `level` does not lift it, only an
     * account with VIP does. `code` distinguishes "no copyright / unavailable" (404).
     */
    data class SongUrl(
        val url: String?,
        val trialEndMs: Long? = null,
        val bitrate: Int = 0,
        val code: Int = 200,
    ) {
        val playable: Boolean get() = !url.isNullOrBlank()
        val isTrial: Boolean get() = trialEndMs != null
        val unavailable: Boolean get() = code == 404 || !playable
    }

    suspend fun songUrl(id: Long, level: String): NetResult<SongUrl> = request(
        "/song/url/v1",
        query = mapOf("id" to "$id", "level" to level),
    ).map { json ->
        val d = json.optJSONArray("data")?.optJSONObject(0) ?: JSONObject()
        SongUrl(
            url = d.optString("url").ifBlank { null },
            // `freeTrialInfo.end` is seconds from the trial start.
            trialEndMs = d.optJSONObject("freeTrialInfo")?.optLong("end")?.takeIf { it > 0 }?.times(1000),
            bitrate = d.optInt("br"),
            code = d.optInt("code", json.optInt("code", 200)),
        )
    }

    /** Availability probe: tells "member-only" apart from "no copyright / offline". */
    suspend fun checkMusic(id: Long): NetResult<Boolean> = request(
        "/check/music",
        query = mapOf("id" to "$id", "timestamp" to System.currentTimeMillis().toString()),
        form = emptyMap(),
    ).map { it.optBoolean("success", false) }

    suspend fun lyric(id: Long): NetResult<LyricBundle> {
        val now = System.currentTimeMillis()
        val firstResult = request(
            "/lyric/new",
            query = RequestCacheBust.withCacheBust(mapOf("id" to "$id"), now),
        )
        val firstJson = (firstResult as? NetResult.Ok)?.value
        val first = firstJson?.let { parseLyric(id, it) }
        diag?.record("歌词", buildString {
            append("stage=lyric id=").append(id)
            append(" endpoint=/lyric/new")
            append(" code=").append(firstJson?.optInt("code", -1) ?: -1)
            append(" nolyric=").append(firstJson?.optBoolean("nolyric") ?: false)
            append(" uncollected=").append(firstJson?.optBoolean("uncollected") ?: false)
            append(" sgc=").append(firstJson?.optBoolean("sgc") ?: false)
            append(" lrcLen=").append(first?.lrc?.length ?: 0)
            append(" yrcLen=").append(first?.yrc?.length ?: 0)
            append(" tlyricLen=").append(first?.translation?.length ?: 0)
        })
        if (first != null && !LyricEndpointRules.needsLegacyFallback(first.nolyric, first.lrc, first.yrc)) {
            return NetResult.Ok(first)
        }
        // Legacy endpoint: it is not a superset on this deployment, but it still answers with clean line-level LRC
        // for tracks whose "new" payload is empty or only the mixed word-format blob.
        val legacyResult = request(
            "/lyric",
            query = RequestCacheBust.withCacheBust(mapOf("id" to "$id"), System.currentTimeMillis()),
        )
        val legacyJson = (legacyResult as? NetResult.Ok)?.value
        val legacy = legacyJson?.let { parseLyric(id, it) }
        diag?.record("歌词", buildString {
            append("stage=lyric id=").append(id)
            append(" endpoint=/lyric")
            append(" code=").append(legacyJson?.optInt("code", -1) ?: -1)
            append(" nolyric=").append(legacyJson?.optBoolean("nolyric") ?: false)
            append(" uncollected=").append(legacyJson?.optBoolean("uncollected") ?: false)
            append(" sgc=").append(legacyJson?.optBoolean("sgc") ?: false)
            append(" lrcLen=").append(legacy?.lrc?.length ?: 0)
            append(" yrcLen=").append(legacy?.yrc?.length ?: 0)
            append(" tlyricLen=").append(legacy?.translation?.length ?: 0)
        })
        val legacyUsable = legacy != null && LyricEndpointRules.hasLines(legacy.lrc, legacy.yrc)
        val chosen = if (legacyUsable) legacy else first ?: legacy
            ?: return (legacyResult as? NetResult.Err ?: firstResult as? NetResult.Err ?: NetResult.Err("歌词获取失败"))
        diag?.record(
            "歌词",
            "stage=lyric id=$id chosen=${if (legacyUsable) "/lyric" else "/lyric/new"} " +
                "result=${if (LyricEndpointRules.hasLines(chosen.lrc, chosen.yrc)) "lines" else "nolyric"}",
        )
        return NetResult.Ok(chosen)
    }

    private fun parseLyric(id: Long, json: JSONObject): LyricBundle {
        fun track(name: String) = json.optJSONObject(name)?.optString("lyric")?.ifBlank { null }
        val lrc = track("lrc")
        val yrc = track("yrc")
        return LyricBundle(
            id = id,
            lrc = lrc,
            translation = track("tlyric"),
            yrc = yrc,
            romanized = track("romalrc"),
            wordTranslation = track("ytlrc") ?: track("ytlyric"),
            wordRomanized = track("yromalrc"),
            nolyric = json.optBoolean("nolyric") || !LyricEndpointRules.hasLines(lrc, yrc),
        )
    }

    suspend fun personalizedPlaylists(limit: Int = 12): NetResult<List<Playlist>> = request(
        "/personalized",
        query = mapOf("limit" to "$limit"),
    ).map { json ->
        val result = json.optJSONArray("result") ?: return@map emptyList()
        (0 until result.length()).mapNotNull { i ->
            val o = result.optJSONObject(i) ?: return@mapNotNull null
            Playlist(
                id = o.optLong("id"),
                name = o.text("name").orEmpty(),
                coverUrl = o.optString("picUrl").ifBlank { null },
                trackCount = o.optInt("trackCount"),
                playCount = o.optLong("playCount"),
            )
        }
    }

    suspend fun recommendedPlaylists(): NetResult<List<Playlist>> = request("/recommend/resource").map { json ->
        val result = json.optJSONArray("recommend") ?: return@map emptyList()
        (0 until result.length()).mapNotNull { i ->
            val o = result.optJSONObject(i) ?: return@mapNotNull null
            Playlist(
                id = o.optLong("id"),
                name = o.text("name").orEmpty(),
                coverUrl = o.optString("picUrl").ifBlank { null },
                trackCount = o.optInt("trackCount"),
                playCount = o.optLong("playCount"),
                description = o.optString("copywriter").ifBlank { null },
            )
        }
    }

    suspend fun dailySongs(): NetResult<List<Track>> = request("/recommend/songs").map { json ->
        json.optJSONObject("data")?.optJSONArray("dailySongs")?.mapDetailTracks().orEmpty()
    }

    suspend fun newSongs(limit: Int = 12): NetResult<List<Track>> = request(
        "/personalized/newsong",
        query = mapOf("limit" to "$limit"),
    ).map { json ->
        val result = json.optJSONArray("result") ?: return@map emptyList()
        val tracks = mutableListOf<Track>()
        // `/personalized/newsong` wraps the track in a `song` object with older field names.
        val ids = mutableListOf<Long>()
        for (i in 0 until result.length()) {
            val o = result.optJSONObject(i) ?: continue
            val song = o.optJSONObject("song") ?: continue
            val id = song.optLong("id")
            if (id == 0L) continue
            ids += id
            tracks += Track(
                id = id,
                name = song.text("name").orEmpty(),
                artists = song.optJSONArray("artists")?.mapNames().orEmpty(),
                albumName = song.optJSONObject("album")?.optString("name").orEmpty(),
                albumId = song.optJSONObject("album")?.optLong("id") ?: 0L,
                coverUrl = o.optString("picUrl").ifBlank { null },
                durationMs = song.optLong("duration"),
                fee = song.optInt("fee"),
            )
        }
        tracks
    }

    suspend fun playlistDetail(id: Long): NetResult<Playlist> = request(
        "/playlist/detail",
        // Timestamp: the API caches this endpoint; a rename/create would otherwise be invisible for a while.
        query = PlaylistRequestRules.withCacheBust(mapOf("id" to "$id"), System.currentTimeMillis()),
    ).map { json ->
        val p = json.optJSONObject("playlist") ?: JSONObject()
        Playlist(
            id = p.optLong("id"),
            name = p.optString("name"),
            coverUrl = p.optString("coverImgUrl").ifBlank { null },
            trackCount = p.optInt("trackCount"),
            playCount = p.optLong("playCount"),
            description = p.optString("description").ifBlank { null },
            creatorUserId = p.optJSONObject("creator")?.optLong("userId") ?: 0L,
        )
    }

    /**
     * The playlist's **complete ordered id list** from `/playlist/detail` (`playlist.trackIds`).
     *
     * Probe on a 1267-track playlist: `trackIds` had all 1267 ids while the embedded `tracks[]` array stopped at
     * 1000. This is the authoritative list the batched loader follows.
     */
    suspend fun playlistTrackIds(id: Long): NetResult<List<Long>> = request(
        "/playlist/detail",
        query = PlaylistRequestRules.withCacheBust(mapOf("id" to "$id"), System.currentTimeMillis()),
    ).map { json ->
        val ids = json.optJSONObject("playlist")?.optJSONArray("trackIds") ?: return@map emptyList()
        (0 until ids.length()).mapNotNull { i -> ids.optJSONObject(i)?.optLong("id")?.takeIf { it > 0L } }
    }

    /** Batched track objects for the playlist loader (500 ids per `/song/detail` call in the deployed instance). */
    suspend fun songDetails(ids: List<Long>): NetResult<List<Track>> = songsByIds(ids)

    suspend fun playlistTracks(id: Long, limit: Int = 1000, offset: Int = 0): NetResult<List<Track>> = request(
        "/playlist/track/all",
        query = PlaylistRequestRules.withCacheBust(
            mapOf("id" to "$id", "limit" to "$limit", "offset" to "$offset"),
            System.currentTimeMillis(),
        ),
    ).map { json -> json.optJSONArray("songs")?.mapDetailTracks().orEmpty() }

    /** Last request's transfer size/time (advisory: parallel requests may overwrite each other). */
    @Volatile
    private var lastHttpBytes: Int = 0

    @Volatile
    private var lastHttpMs: Long = 0L

    /** A playlist page plus the measured transfer stats, for the loading breakdown log. */
    suspend fun playlistTracksPageMeasured(id: Long, limit: Int, offset: Int): NetResult<LoadedPlaylistPage> {
        val started = System.nanoTime()
        val result = request(
            "/playlist/track/all",
            query = PlaylistRequestRules.withCacheBust(
                mapOf("id" to "$id", "limit" to "$limit", "offset" to "$offset"),
                System.currentTimeMillis(),
            ),
        )
        val totalMs = (System.nanoTime() - started) / 1_000_000
        return when (result) {
            is NetResult.Ok -> NetResult.Ok(
                LoadedPlaylistPage(
                    offset = offset,
                    tracks = result.value.optJSONArray("songs")?.mapDetailTracks().orEmpty(),
                    bytes = lastHttpBytes,
                    durationMs = if (lastHttpMs > 0L) lastHttpMs else totalMs,
                ),
            )
            is NetResult.Err -> result
        }
    }

    /**
     * 心动模式: the personalised "heartbeat" queue for [songId], inside [playlistId] when one is known.
     *
     * The deployed instance's route is login-gated (the raw probe lives in [BeatModeRules]): a guest is rejected
     * locally without a round trip, and a 301/401 from the server is reported as the two specific login messages
     * rather than a generic error. `pid` is omitted when no playlist id is known — the server's own answer is
     * then what the user sees. Cache-busted because this deployment caches module results.
     *
     * The recommendation payload is used **only as an ordered id source**: its song objects do not use
     * `/song/detail`'s field names, so mapping them directly produced id-only rows (playable, with lyrics, but
     * no title/artist/cover). The ids are therefore hydrated through the authoritative batched
     * `/song/detail?ids=` path and rebuilt in the original order; rows the detail endpoint cannot describe are
     * dropped, and a queue is never installed from blank rows.
     */
    suspend fun beatMode(songId: Long, startSongId: Long, playlistId: Long?): NetResult<List<Track>> {
        if (settings.cookie.isBlank()) return NetResult.Err(BeatModeRules.GUEST_MESSAGE)
        val query = buildMap {
            put("id", "$songId")
            put("sid", "$startSongId")
            playlistId?.takeIf { it > 0L }?.let { put("pid", "$it") }
        }
        val payload = when (
            val result = request(
                "/playmode/intelligence/list",
                query = RequestCacheBust.withCacheBust(query, System.currentTimeMillis()),
            )
        ) {
            is NetResult.Ok -> result.value
            is NetResult.Err ->
                return NetResult.Err(BeatModeRules.loginFailureMessage(result.message) ?: result.message)
        }

        // Step 1: ordered ids only (see the doc above for why the payload's own objects are not trusted).
        val ids = BeatModeRules.songIds(payload)
        android.util.Log.i("YunYin/BeatMode", "beat payload ids=${ids.size} first=${ids.take(5)}")
        if (ids.isEmpty()) return NetResult.Err(BeatModeRules.EMPTY_MESSAGE)

        // Step 2: authoritative metadata by id, in batches; a failed batch fails the hydration instead of
        // silently dropping a whole page of songs.
        val hydrated = when (val detail = songDetailsBatched(ids)) {
            is NetResult.Ok -> detail.value
            is NetResult.Err -> {
                android.util.Log.i(
                    "YunYin/BeatMode",
                    "hydration request failed for ${ids.size} ids: ${detail.message}",
                )
                return NetResult.Err(BeatModeRules.HYDRATION_MESSAGE)
            }
        }
        return when (val hydration = BeatModeRules.hydrate(ids, hydrated.associateBy { it.id })) {
            is BeatModeRules.Hydration.Failed -> {
                android.util.Log.i(
                    "YunYin/BeatMode",
                    "hydration produced no displayable rows for ${ids.size} ids: ${ids.take(5)}",
                )
                NetResult.Err(BeatModeRules.HYDRATION_MESSAGE)
            }
            is BeatModeRules.Hydration.Ready -> {
                if (hydration.droppedIds.isNotEmpty()) {
                    android.util.Log.i(
                        "YunYin/BeatMode",
                        "dropped ${hydration.droppedIds.size} ids (missing or id-only): " +
                            hydration.droppedIds.take(8),
                    )
                }
                NetResult.Ok(hydration.tracks)
            }
        }
    }

    /**
     * Resolves [ids] to full tracks through `/song/detail?ids=` in [PlaylistPagingRules.TRACK_BATCH] batches.
     *
     * Cache-busted for the same reason as every other read on this deployment. The detail response order is not
     * trusted: callers rebuild by id order from the returned tracks.
     */
    private suspend fun songDetailsBatched(ids: List<Long>): NetResult<List<Track>> {
        val out = ArrayList<Track>(ids.size)
        PlaylistPagingRules.chunks(ids).forEach { chunk ->
            val result = request(
                "/song/detail",
                query = RequestCacheBust.withCacheBust(
                    mapOf("ids" to chunk.joinToString(",")),
                    System.currentTimeMillis(),
                ),
            )
            when (result) {
                is NetResult.Ok -> out += result.value.optJSONArray("songs")?.mapDetailTracks().orEmpty()
                is NetResult.Err -> return result
            }
        }
        return NetResult.Ok(out)
    }

    /** Reports one qualified play to the signed-in account. Silent, fire-and-forget. */
    suspend fun scrobble(songId: Long, sourceId: Long, seconds: Int): NetResult<Unit> {
        val result = request(
            "/scrobble",
            query = PlaylistRequestRules.withCacheBust(
                mapOf("id" to "$songId", "sourceid" to "$sourceId", "time" to "$seconds"),
                System.currentTimeMillis(),
            ),
        )
        return when (result) {
            is NetResult.Err -> result
            is NetResult.Ok -> if (result.value.optInt("code") == 200) NetResult.Ok(Unit) else NetResult.Err(
                result.value.optString("message").ifBlank {
                    result.value.optString("msg").ifBlank { "code=${result.value.optInt("code")}" }
                },
            )
        }
    }

    /** Hot comments for a song (read-only, no login). Cache-busted because the instance caches. */
    suspend fun hotComments(songId: Long): NetResult<List<com.yunyin.music.core.Comment>> = request(
        "/comment/hot",
        query = PlaylistRequestRules.withCacheBust(
            mapOf("id" to "$songId", "type" to "0"),
            System.currentTimeMillis(),
        ),
    ).map { json -> parseComments(json.optJSONArray("hotComments")) }

    /** One page of all comments for a song. */
    suspend fun comments(songId: Long, offset: Int, limit: Int = 20): NetResult<com.yunyin.music.core.CommentsPage> =
        request(
            "/comment/music",
            query = PlaylistRequestRules.withCacheBust(
                mapOf("id" to "$songId", "limit" to "$limit", "offset" to "$offset"),
                System.currentTimeMillis(),
            ),
        ).map { json ->
            com.yunyin.music.core.CommentsPage(
                comments = parseComments(json.optJSONArray("comments")),
                more = json.optBoolean("more"),
                total = json.optInt("total"),
            )
        }

    private fun parseComments(array: org.json.JSONArray?): List<com.yunyin.music.core.Comment> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val user = item.optJSONObject("user")
            val id = item.optLong("commentId").takeIf { it > 0L } ?: item.optLong("id")
            if (id <= 0L) return@mapNotNull null
            val content = item.optString("content")
            if (content.isBlank()) return@mapNotNull null
            com.yunyin.music.core.Comment(
                id = id,
                nickname = user?.optString("nickname").orEmpty().ifBlank { "网易云用户" },
                avatarUrl = user?.optString("avatarUrl")?.ifBlank { null },
                content = content,
                likedCount = item.optInt("likedCount"),
                timeMs = item.optLong("time"),
            )
        }
    }

    // ---------------------------------------------------------------- playlist writes
    //
    // These are the NeteaseCloudMusicApi mutation endpoints. They take form fields and require the logged-in
    // MUSIC_U cookie (the app stores it in [SettingsStore.cookie]); without a real session the server answers 301
    // ("need login"), which is surfaced as a specific message instead of being collapsed into a generic error.

    private fun loginRequiredMessage(): String? =
        if (settings.cookie.isBlank()) "未登录，请先登录" else null

    /** Maps NetEase's need-login responses to the two user-facing cases the UI distinguishes. */
    private fun loginAware(result: NetResult<JSONObject>): NetResult<JSONObject> = when (result) {
        is NetResult.Ok -> result
        is NetResult.Err ->
            if (result.message.contains("登录") || result.message.contains("301") || result.message.contains("401")) {
                NetResult.Err("登录已过期，请重新登录")
            } else {
                result
            }
    }

    suspend fun createPlaylist(
        name: String,
        description: String = "",
        isPrivate: Boolean = false,
    ): NetResult<Long> {
        loginRequiredMessage()?.let { return NetResult.Err(it) }
        val invalid = PlaylistWrites.validateName(name) ?: PlaylistWrites.validateDescription(description)
        if (invalid != null) return NetResult.Err(invalid)
        return loginAware(
            request("/playlist/create", form = PlaylistWrites.createForm(name, description, isPrivate)),
        ).map { json ->
            val newId = json.optJSONObject("playlist")?.optLong("id")?.takeIf { it > 0L }
                ?: json.optLong("id").takeIf { it > 0L }
                ?: json.optLong("playlistId").takeIf { it > 0L }
                ?: 0L
            // A "created but not shown" report needs the id we were given, so it goes to the diagnostics trail.
            android.util.Log.i("YunYin/Playlist", "create name=$name -> id=$newId code=${json.optInt("code")}")
            newId
        }
    }

    suspend fun updatePlaylist(playlistId: Long, name: String, description: String): NetResult<Unit> {
        loginRequiredMessage()?.let { return NetResult.Err(it) }
        val invalid = PlaylistWrites.validateName(name) ?: PlaylistWrites.validateDescription(description)
        if (invalid != null) return NetResult.Err(invalid)
        return loginAware(
            request("/playlist/update", form = PlaylistWrites.updateForm(playlistId, name, description)),
        ).map { }
    }

    suspend fun deletePlaylist(playlistId: Long): NetResult<Unit> {
        loginRequiredMessage()?.let { return NetResult.Err(it) }
        return loginAware(request("/playlist/delete", form = PlaylistWrites.deleteForm(playlistId))).map { }
    }

    /** Adds tracks; the API dedupes by itself, so a second add is not an error to the user. */
    suspend fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>): NetResult<Unit> {
        loginRequiredMessage()?.let { return NetResult.Err(it) }
        if (trackIds.isEmpty()) return NetResult.Err("没有可添加的歌曲")
        return loginAware(
            request("/playlist/tracks", form = PlaylistWrites.tracksForm(playlistId, "add", trackIds)),
        ).map { }
    }

    suspend fun removeTracksFromPlaylist(playlistId: Long, trackIds: List<Long>): NetResult<Unit> {
        loginRequiredMessage()?.let { return NetResult.Err(it) }
        if (trackIds.isEmpty()) return NetResult.Err("没有可移除的歌曲")
        return loginAware(
            request("/playlist/tracks", form = PlaylistWrites.tracksForm(playlistId, "del", trackIds)),
        ).map { }
    }

    suspend fun toplists(): NetResult<List<ChartInfo>> = request("/toplist").map { json ->
        val list = json.optJSONArray("list") ?: return@map emptyList()
        (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            ChartInfo(
                id = o.optLong("id"),
                name = o.text("name").orEmpty(),
                coverUrl = o.text("coverImgUrl"),
                updateFrequency = o.optString("updateFrequency").ifBlank { null },
            )
        }
    }

    suspend fun artistTopSongs(artistId: Long): NetResult<List<Track>> = request(
        "/artist/top/song",
        query = RequestCacheBust.withCacheBust(mapOf("id" to "$artistId"), System.currentTimeMillis()),
    ).map { json -> json.optJSONArray("songs")?.mapDetailTracks().orEmpty() }

    /**
     * Resolves an artist name to an id (artist search). Cache-busted like every result endpoint.
     */
    suspend fun artistIdByName(name: String): Long? {
        val result = request(
            "/search",
            query = RequestCacheBust.withCacheBust(
                mapOf("keywords" to name, "type" to "100"),
                System.currentTimeMillis(),
            ),
        ).map { json ->
            json.optJSONObject("result")?.optJSONArray("artists")?.optJSONObject(0)?.optLong("id")
                ?.takeIf { it != 0L }
        }
        return (result as? NetResult.Ok)?.value
    }

    /** Artist profile from the deployed instance (`/artist/detail`), replacing the hot-songs-only direct call. */
    suspend fun artistDetail(artistId: Long): NetResult<ArtistInfo> = request(
        "/artist/detail",
        query = RequestCacheBust.withCacheBust(mapOf("id" to "$artistId"), System.currentTimeMillis()),
    ).map { json -> ArtistPayloads.parseArtist(json, artistId) }

    /** All songs, paged (`/artist/songs`); [more]/[total] drive lazy paging on the artist screen. */
    suspend fun artistSongsPage(
        artistId: Long,
        offset: Int,
        limit: Int,
        order: String = "time",
    ): NetResult<com.yunyin.music.core.ArtistSongsPage> = request(
        "/artist/songs",
        query = RequestCacheBust.withCacheBust(
            mapOf("id" to "$artistId", "offset" to "$offset", "limit" to "$limit", "order" to order),
            System.currentTimeMillis(),
        ),
    ).map { json ->
        com.yunyin.music.core.ArtistSongsPage(
            tracks = json.optJSONArray("songs")?.mapDetailTracks().orEmpty(),
            more = json.optBoolean("more"),
            total = json.optInt("total"),
        )
    }

    /** Albums, paged (`/artist/album`); accepts both `hotAlbums` and `albums` shapes. */
    suspend fun artistAlbumsPage(
        artistId: Long,
        offset: Int,
        limit: Int,
    ): NetResult<com.yunyin.music.core.ArtistAlbumsPage> = request(
        "/artist/album",
        query = RequestCacheBust.withCacheBust(
            mapOf("id" to "$artistId", "offset" to "$offset", "limit" to "$limit"),
            System.currentTimeMillis(),
        ),
    ).map { json -> ArtistPayloads.parseAlbumsPage(json) }

    /** Songs of one album, so an album tap can open the existing collection page. */
    suspend fun albumTracks(albumId: Long): NetResult<List<Track>> = request(
        "/album",
        query = RequestCacheBust.withCacheBust(mapOf("id" to "$albumId"), System.currentTimeMillis()),
    ).map { json -> json.optJSONArray("songs")?.mapDetailTracks().orEmpty() }

    // ---------------------------------------------------------------- user library

    /**
     * The signed-in user's playlists.
     *
     * Includes the "我喜欢的音乐" special playlist (NetEase flags it via `specialType`),
     * so the liked-songs list needs no separate request. Requires a real login — an
     * anonymous session gets an empty list rather than an error.
     */
    suspend fun userPlaylists(uid: Long, limit: Int = 100, offset: Int = 0): NetResult<List<Playlist>> = request(
        "/user/playlist",
        // The list that must reflect a just-created playlist: without `timestamp` the API answers from its cache and
        // the new playlist is missing until that cache expires (the "创建了但不显示" report).
        query = PlaylistRequestRules.withCacheBust(
            mapOf("uid" to "$uid", "limit" to "$limit", "offset" to "$offset"),
            System.currentTimeMillis(),
        ),
    ).map { json ->
        val list = json.optJSONArray("playlist") ?: return@map emptyList()
        val mapped = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            if (o.optLong("id") == 0L) return@mapNotNull null
            Playlist(
                id = o.optLong("id"),
                name = o.text("name").orEmpty(),
                coverUrl = o.text("coverImgUrl"),
                trackCount = o.optInt("trackCount"),
                playCount = o.optLong("playCount"),
                description = o.text("description"),
                isLikedSongs = o.optInt("specialType") == SPECIAL_TYPE_LIKED,
                creatorUserId = o.optJSONObject("creator")?.optLong("userId") ?: 0L,
            )
        }
        // Diagnostics: the cache-busted refetch result (ids + count) next to the create line above.
        android.util.Log.i(
            "YunYin/Playlist",
            "userPlaylists uid=$uid count=${mapped.size} ids=${mapped.take(8).joinToString(",") { it.id.toString() }}",
        )
        mapped
    }

    /** Ids of every song the user has liked. */
    suspend fun likedSongIds(uid: Long): NetResult<List<Long>> = request(
        "/likelist",
        query = mapOf("uid" to "$uid", "timestamp" to System.currentTimeMillis().toString()),
    ).map { json ->
        val ids = json.optJSONArray("ids") ?: return@map emptyList()
        (0 until ids.length()).mapNotNull { ids.optLong(it).takeIf { id -> id != 0L } }
    }

    /** Resolves liked ids into full tracks, fetching in chunks of [SONG_DETAIL_BATCH]. */
    suspend fun songsByIds(ids: List<Long>): NetResult<List<Track>> {
        if (ids.isEmpty()) return NetResult.Ok(emptyList())
        val out = mutableListOf<Track>()
        ids.chunked(SONG_DETAIL_BATCH).forEach { chunk ->
            when (val r = songDetail(chunk)) {
                is NetResult.Ok -> out += r.value
                is NetResult.Err -> Unit
            }
        }
        return NetResult.Ok(out)
    }

    /**
     * Likes or unlikes a song.
     *
     * Requires a real (non-anonymous) session: a guest gets `code=301` ("need login") from the
     * service, which is surfaced as a message rather than treated as a network failure.
     *
     * `/like` is a mutating endpoint, so a `timestamp` is included — the same reason the login calls
     * need one. Without it the API server's two-minute URL cache can swallow the request and the
     * toggle silently does nothing.
     */
    suspend fun likeSong(id: Long, like: Boolean): NetResult<Unit> = request(
        "/like",
        query = mapOf(
            "id" to "$id",
            "like" to like.toString(),
            "timestamp" to System.currentTimeMillis().toString(),
        ),
    ).map { }

    /** Whether [id] is in the signed-in user's liked songs. */
    suspend fun isLiked(uid: Long, id: Long): NetResult<Boolean> =
        likedSongIds(uid).map { it.contains(id) }

    // ---------------------------------------------------------------- mapping

    /** `/search` uses compact field names: `artists`, `album.picId`, `duration`. */
    private fun JSONArray.mapSearchTracks(): List<Track> = (0 until length()).mapNotNull { i ->
        val o = optJSONObject(i) ?: return@mapNotNull null
        Track(
            id = o.optLong("id"),
            name = o.text("name").orEmpty(),
            artists = o.optJSONArray("artists")?.mapNames().orEmpty(),
            albumName = o.optJSONObject("album")?.optString("name").orEmpty(),
            albumId = o.optJSONObject("album")?.optLong("id") ?: 0L,
            coverUrl = null,
            durationMs = o.optLong("duration"),
            fee = o.optInt("fee"),
        )
    }

    /** `/song/detail` and friends use `ar` / `al` / `dt`. */
    private fun JSONArray.mapDetailTracks(): List<Track> = (0 until length()).mapNotNull { i ->
        val o = optJSONObject(i) ?: return@mapNotNull null
        val album = o.optJSONObject("al")
        // Cover URL from `song/detail` is 130px; upgrade to a 500px variant.
        val cover = album?.optString("picUrl")?.ifBlank { null }
        Track(
            id = o.optLong("id"),
            name = o.text("name").orEmpty(),
            artists = o.optJSONArray("ar")?.mapNames().orEmpty(),
            albumName = album?.text("name").orEmpty(),
            albumId = album?.optLong("id") ?: 0L,
            coverUrl = cover,
            durationMs = o.optLong("dt"),
            fee = o.optInt("fee"),
        )
    }

    private fun JSONArray.mapNames(): List<String> = (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.optString("name")?.ifBlank { null }
    }

    /**
     * A long at [index], or null when the entry is absent, null, or not a number.
     *
     * `JSONArray.optLong` answers 0 for "missing", which is indistinguishable from a real 0 — and a song
     * id of 0 is meaningless, so mapping it to null keeps the caller honest about what it received.
     */
    private fun JSONArray.optLongOrNull(index: Int): Long? {
        if (index < 0 || index >= length() || isNull(index)) return null
        return optLong(index).takeIf { it != 0L }
            ?: optString(index).toLongOrNull()
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

        /** NetEase marks the built-in "我喜欢的音乐" playlist with this `specialType`. */
        private const val SPECIAL_TYPE_LIKED = 5

        /** Cap for batched `/song/detail` lookups. */
        private const val SONG_DETAIL_BATCH = 100
    }
}
