package com.yunyin.music.data.together

import com.yunyin.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Listen-together over a public publish/subscribe service, so the app needs **no server of its own**.
 *
 * ## Why this exists
 *
 * NetEase's own together-listen cannot be reused: its room runs over an Agora RTC channel and its
 * HTTP API is write-only for playback state (measured — reporting returns `result: true`, while
 * `sync/playlist/get` comes back empty and `anotherDeviceInfo` stays null). So a read path has to
 * come from somewhere, and "somewhere" is either a server the user runs or a service that already
 * exists. This is the latter: [ntfy.sh] is a free HTTP pub/sub service, so two devices can exchange
 * a few numbers with nothing deployed.
 *
 * ## How the conversation works
 *
 * There is no room registry. The "room code" *is* the topic name, so creating a room is a local
 * operation (generate a code) and joining is merely publishing to the same topic. Each side
 * publishes its own state and reads the other's:
 *
 *   publish  POST {base}/{topic}                body = the state as JSON
 *   read     GET  {base}/{topic}/json?poll=1&since={last event id}
 *
 * The cursor is a *message id*, not a timestamp: the service hands back messages that arrived after
 * it, so the two devices' clocks never have to agree (and on a phone in a pocket, they reliably do
 * not).
 *
 * ## What this costs
 *
 * - **The topic is public.** Anyone who knows the code can read the messages. What travels is a song
 *   id, a position, a play flag and a nickname — no account, no cookie, no title — but the code is
 *   therefore treated as a secret: twelve characters from an unambiguous alphabet, which is not
 *   guessable and is not meant to be shouted around.
 * - **It depends on someone else's uptime.** `ntfy.sh` is a free public instance. [baseUrl] is
 *   configurable (`together.ntfy.url`) so a self-hosted instance or a mirror can be substituted
 *   without touching code, and the bundled relay remains available for anyone who would rather run
 *   their own.
 */
class NtfyTransport(
    private val baseUrl: String = defaultBaseUrl(),
    private val client: OkHttpClient = defaultClient(),
    /** Injectable so freshness (not the service's clock) can be the expiry rule. */
    private val clock: () -> Long = System::currentTimeMillis,
) : TogetherTransport {

    /** No setup is required: the public instance is the default. */
    override val configured: Boolean get() = baseUrl.isNotBlank()

    /**
     * Three seconds rather than two: ntfy counts a publish and a poll as two requests, and the free
     * instance rate-limits per address. 40 requests a minute sits comfortably inside that.
     */
    override val pollIntervalMs: Long get() = 3_000L

    private val base: String get() = baseUrl.trimEnd('/')

    /** Latest state per peer, plus when this device last heard it — used to expire silent members. */
    private val peersByUid = mutableMapOf<String, TogetherPeerState>()
    private val heardAt = mutableMapOf<String, Long>()

    /** Cursor for the next poll: the id of the newest message this device has already read. */
    private var lastEventId: String? = null

    private var selfUid: String = ""

    override suspend fun createRoom(uid: String, name: String): TogetherResult<String> {
        // Local by construction: there is no room to create on the service, only a topic to agree on.
        selfUid = uid
        lastEventId = null
        peersByUid.clear()
        heardAt.clear()
        return TogetherResult.Ok(newCode())
    }

    override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> =
        // Nothing to look up: a topic exists as soon as someone publishes to it. An empty list is the
        // honest answer, and the join flow treats it as "go ahead".
        TogetherResult.Ok(emptyList())

    override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> {
        selfUid = uid
        lastEventId = null
        peersByUid.clear()
        heardAt.clear()
        // Announce presence immediately so the host sees someone arrive before any music is chosen.
        return publish(code, uid, name, songId = 0L, positionMs = 0L, playing = false, seq = 0L)
            .map { }
    }

    override suspend fun postState(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<List<TogetherPeerState>> {
        selfUid = uid
        val published = publish(code, uid, name, songId, positionMs, playing, seq)
        if (published is TogetherResult.Failed) return published
        return pollPeers(code, uid)
    }

    override suspend fun pollPeers(
        code: String,
        excludeUid: String,
    ): TogetherResult<List<TogetherPeerState>> = withContext(Dispatchers.IO) {
        val cursor = lastEventId?.let { "since=$it" } ?: "since=$INITIAL_WINDOW"
        val request = Request.Builder().url("$base/${topic(code)}/json?poll=1&$cursor").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext TogetherResult.Failed("服务错误 HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                // Newline-delimited JSON: one object per line, plus keepalive/open events to ignore.
                body.lineSequence().forEach { line ->
                    if (line.isBlank()) return@forEach
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    if (event.optString("event") != "message") return@forEach
                    event.optString("id").takeIf { it.isNotBlank() }?.let { lastEventId = it }
                    val payload = runCatching { JSONObject(event.optString("message")) }.getOrNull()
                        ?: return@forEach
                    val uid = payload.optString("uid")
                    if (uid.isBlank() || uid == selfUid) return@forEach
                    peersByUid[uid] = TogetherPeerState(
                        uid = uid,
                        name = payload.optString("name"),
                        songId = payload.optLong("songId"),
                        positionMs = payload.optLong("positionMs"),
                        playing = payload.optBoolean("playing"),
                        updatedAt = payload.optLong("updatedAt"),
                        seq = payload.optLong("seq"),
                    )
                    heardAt[uid] = clock()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext TogetherResult.Failed(e.message ?: "网络错误")
        }
        // A member who stops publishing is gone. Measured locally rather than from the service's
        // clock: the only question is "how long since I last heard from them".
        val now = clock()
        val expired = heardAt.filterValues { now - it > MEMBER_TTL_MS }.keys
        expired.forEach { uid ->
            peersByUid.remove(uid)
            heardAt.remove(uid)
        }
        TogetherResult.Ok(peersByUid.values.toList())
    }

    override suspend fun leave(code: String, uid: String): TogetherResult<Unit> {
        // A tombstone rather than silence: the peer drops this member immediately instead of waiting
        // for it to go stale, and it must not be treated as "the peer chose this song".
        val result = publish(code, uid, "", songId = 0L, positionMs = 0L, playing = false, seq = 0L)
        return result.map { }
    }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> {
        val result = publish(code, selfUid, "", songId = 0L, positionMs = 0L, playing = false, seq = 0L)
        return result.map { }
    }

    private suspend fun publish(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<JSONObject> = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("uid", uid)
            .put("name", name)
            .put("songId", songId)
            .put("positionMs", positionMs)
            .put("playing", playing)
            .put("updatedAt", clock())
            .put("seq", seq)
        val request = Request.Builder()
            .url("$base/${topic(code)}")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    TogetherResult.Failed("发送失败 HTTP ${response.code}")
                } else {
                    TogetherResult.Ok(payload)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            TogetherResult.Failed(e.message ?: "网络错误")
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** First poll looks back this far, so a room joined a moment ago still finds the host. */
        const val INITIAL_WINDOW = "30m"

        /** How long a member may be silent before it is treated as having left. */
        const val MEMBER_TTL_MS = 15_000L

        fun topic(code: String): String = TogetherCode.topic(code)

        fun newCode(): String = TogetherCode.fresh()

        fun defaultBaseUrl(): String =
            BuildConfig.TOGETHER_NTFY_URL.ifBlank { "https://ntfy.sh" }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
