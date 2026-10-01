package com.yunyin.music.data.together

import com.yunyin.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One participant's current playback, as exchanged through the relay. */
data class TogetherPeerState(
    val uid: String,
    val name: String,
    val songId: Long,
    val positionMs: Long,
    val playing: Boolean,
    val updatedAt: Long,
    val seq: Long,
) {
    val hasSong: Boolean get() = songId > 0L
}

/** A room member as the relay reports it (identity only). */
data class TogetherMember(val uid: String, val name: String)

/** Failure modes the UI has to distinguish, rather than one opaque error. */
sealed interface TogetherResult<out T> {
    data class Ok<T>(val value: T) : TogetherResult<T>

    /** The room code does not exist (or was closed). */
    data class NoRoom(val message: String) : TogetherResult<Nothing>

    /**
     * The service refused for rate limiting.
     *
     * Its own case because it is transient and actionable — the user can simply wait — whereas a
     * generic failure reads as "it is broken". The public instance limits per address, so this is a
     * real possibility when several devices share one network.
     */
    data class RateLimited(val message: String) : TogetherResult<Nothing>

    /** Anything else: offline, bad response, service down. */
    data class Failed(val message: String) : TogetherResult<Nothing>
}

/** Applies [transform] to a success and passes failures through unchanged. */
inline fun <T, R> TogetherResult<T>.map(transform: (T) -> R): TogetherResult<R> = when (this) {
    is TogetherResult.Ok -> TogetherResult.Ok(transform(value))
    is TogetherResult.NoRoom -> this
    is TogetherResult.RateLimited -> this
    is TogetherResult.Failed -> this
}

/**
 * Transport for listen-together state.
 *
 * An interface rather than a concrete HTTP client because the *reason* this exists is a missing read
 * path, and which path supplies it is a deployment choice: the bundled relay today, a LAN socket
 * later. The sync engine above it must not care which.
 */
interface TogetherTransport {
    /** True when a transport is configured at all; the feature is hidden when it is not. */
    val configured: Boolean

    /**
     * The endpoint in use, for display.
     *
     * Worth surfacing: a transport is a *rendezvous*, so if the two members are on different endpoints
     * they sit in two rooms that both look empty and neither side has any way to tell. Showing the
     * address turns that silent failure into an obvious one.
     */
    val serverLabel: String? get() = null

    /**
     * How often the session should poll. A transport reports its own cadence because it is a property
     * of the medium: a self-hosted relay is cheap to hit every two seconds, while a free public
     * service counts each publish and poll against a rate limit.
     */
    val pollIntervalMs: Long get() = 2_000L

    /**
     * How long this transport's room codes are.
     *
     * A property of the *transport*, not a global constant, because the two kinds genuinely differ: the
     * self-hosted relay allocates codes server-side (six characters are plenty — it holds the registry),
     * while a broker-based room has no registry, so the code doubles as a topic name and needs to be long
     * enough not to be guessable by a stranger (twelve).
     *
     * The UI validates against this. Hardcoding one length disabled joining entirely on the other
     * transport — a six-character code could never satisfy a twelve-character rule, so the join button
     * stayed dead with no explanation.
     */
    val codeLength: Int get() = 12

    /** True when [code] is long enough to be a complete code for this transport. */
    fun isCompleteCode(code: String): Boolean =
        code.count { it.isLetterOrDigit() } >= codeLength

    /** Trims typed input to this transport's length, ignoring separators. */
    fun normaliseCodeInput(input: String): String =
        input.filter { it.isLetterOrDigit() }.uppercase().take(codeLength)

    suspend fun createRoom(uid: String, name: String): TogetherResult<String>

    suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>>

    suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit>

    /** Publishes this member's state and returns the peers' states in the same round trip. */
    suspend fun postState(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<List<TogetherPeerState>>

    suspend fun pollPeers(code: String, excludeUid: String): TogetherResult<List<TogetherPeerState>>

    suspend fun leave(code: String, uid: String): TogetherResult<Unit>

    suspend fun closeRoom(code: String): TogetherResult<Unit>
}

/**
 * The bundled relay, over HTTP.
 *
 * Polling rather than a socket on purpose: the payload is a handful of numbers, and an HTTP request
 * every couple of seconds is simpler to reason about (and to debug with `curl`) than a connection
 * that has to survive backgrounding. Latency is the trade — a correction lands within one poll
 * interval, the same order as a lyric line.
 */
class RelayTransport(
    private val client: OkHttpClient = defaultClient(),
) : TogetherTransport {

    /**
     * Read from BuildConfig, deliberately: the address is injected at build time so the repository
     * ships no server address, exactly as the NetEase endpoint does.
     */
    override val configured: Boolean get() = BuildConfig.TOGETHER_BASE_URL.isNotBlank()

    /**
     * Six characters: the relay allocates codes server-side and owns the registry, so the code does not
     * have to carry any unguessability of its own. (Contrast the broker transports, where the code *is*
     * the topic name and must be long enough that a stranger cannot find it.)
     */
    override val codeLength: Int get() = RELAY_CODE_LENGTH

    private val base: String get() = BuildConfig.TOGETHER_BASE_URL.trimEnd('/')

    /** Optional shared secret, matching the relay's `RELAY_TOKEN`. Blank means the relay is open. */
    private val TOKEN: String get() = BuildConfig.TOGETHER_TOKEN

    override suspend fun createRoom(uid: String, name: String): TogetherResult<String> =
        post("/room", JSONObject().put("uid", uid).put("name", name)).map { json ->
            json.optString("code")
        }.let { result ->
            // A blank code here would otherwise become a room that can never be joined.
            when (result) {
                is TogetherResult.Ok ->
                    if (result.value.isBlank()) TogetherResult.Failed("房间创建失败") else result
                else -> result
            }
        }

    override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> =
        get("/room/$code").map { json -> json.optJSONArray("members").toMembers() }

    override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> =
        post("/room/$code/join", JSONObject().put("uid", uid).put("name", name)).map { }

    override suspend fun postState(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<List<TogetherPeerState>> = post(
        "/room/$code/state",
        JSONObject()
            .put("uid", uid)
            .put("name", name)
            .put("songId", songId)
            .put("positionMs", positionMs)
            .put("playing", playing)
            .put("updatedAt", System.currentTimeMillis())
            .put("seq", seq),
    ).map { json -> json.optJSONArray("peers").toPeers() }

    override suspend fun pollPeers(
        code: String,
        excludeUid: String,
    ): TogetherResult<List<TogetherPeerState>> =
        get("/room/$code/state?exclude=$excludeUid").map { json ->
            json.optJSONArray("peers").toPeers()
        }

    override suspend fun leave(code: String, uid: String): TogetherResult<Unit> =
        post("/room/$code/leave", JSONObject().put("uid", uid)).map { }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> =
        request("DELETE", "/room/$code", null).map { }

    // ------------------------------------------------------------------ plumbing

    private suspend fun get(path: String): TogetherResult<JSONObject> = request("GET", path, null)

    private suspend fun post(path: String, body: JSONObject?): TogetherResult<JSONObject> =
        request("POST", path, body)

    /**
     * One request. Runs on IO, never throws, and maps the relay's own error shape
     * (`{"error":"no_room"}`) onto [TogetherResult.NoRoom] so the UI can say "房间不存在" instead of
     * a generic failure.
     */
    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?,
    ): TogetherResult<JSONObject> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext TogetherResult.Failed("未配置一起听服务地址")
        val builder = Request.Builder().url(base + path)
        // The relay's optional shared secret. Sent when this build was given one; the relay only
        // checks it if it was started with a token, so the two are configured together.
        TOKEN.takeIf { it.isNotBlank() }?.let { builder.header("X-Relay-Token", it) }
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.post((body?.toString() ?: "{}").toRequestBody(JSON))
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    ?: return@withContext TogetherResult.Failed("服务响应无法解析")
                when {
                    response.code == 429 -> TogetherResult.RateLimited("请求过于频繁，请稍后再试")
                    response.code == 404 || json.optString("error") == "no_room" ->
                        TogetherResult.NoRoom("房间不存在或已结束")
                    !response.isSuccessful -> TogetherResult.Failed("服务错误 HTTP ${response.code}")
                    else -> TogetherResult.Ok(json)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TogetherResult.Failed(e.message ?: "网络错误")
        }
    }

    private fun JSONArray?.toPeers(): List<TogetherPeerState> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            TogetherPeerState(
                uid = o.optString("uid"),
                name = o.optString("name"),
                songId = o.optLong("songId"),
                positionMs = o.optLong("positionMs"),
                playing = o.optBoolean("playing"),
                updatedAt = o.optLong("updatedAt"),
                seq = o.optLong("seq"),
            )
        }
    }

    private fun JSONArray?.toMembers(): List<TogetherMember> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            TogetherMember(uid = o.optString("uid"), name = o.optString("name"))
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** What `server/together-relay.js` generates. Kept in step with its CODE_LENGTH. */
        const val RELAY_CODE_LENGTH = 6

        /** Short timeouts: every call is a few hundred bytes, and a slow one only delays a correction. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
