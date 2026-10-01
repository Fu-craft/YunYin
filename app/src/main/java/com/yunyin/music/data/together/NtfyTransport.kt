package com.yunyin.music.data.together

import com.yunyin.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Listen-together over a public publish/subscribe service, so the app needs **no server of its own**.
 *
 * ## Why this exists
 *
 * NetEase's own together-listen cannot be reused: its room runs over an Agora RTC channel and its HTTP
 * API is write-only for playback state (measured — reporting returns `result: true`, while
 * `sync/playlist/get` comes back empty and `anotherDeviceInfo` stays null). So a read path has to come
 * from somewhere, and "somewhere" is either a server the user runs or a service that already exists.
 * This is the latter: [ntfy.sh] is a free HTTP pub/sub service, so two devices can exchange a few
 * numbers with nothing deployed.
 *
 * ## Reading is a long-lived subscription, not polling
 *
 * The first version polled: a publish *and* a fetch every three seconds, per device. That met the
 * service's rate limit in real use and surfaced as **HTTP 429** in the app. The service's own model is
 * a long-lived stream, so that is what this uses: one `GET /{topic}/json` connection per device, kept
 * open, delivering each message as it arrives. Receiving therefore costs ~one request per session
 * instead of one every few seconds, and a peer's action arrives immediately rather than at the next
 * tick.
 *
 * Only *publishing* remains periodic — and it can be slow, because the stream already delivers
 * changes the instant they happen:
 *
 *   publish  POST {base}/{topic}            body = the state as JSON
 *   read     GET  {base}/{topic}/json       long-lived NDJSON stream
 *
 * ## What this costs
 *
 * - **The topic is public.** Anyone who knows the code can read the messages: a song id, a position, a
 *   play flag and a nickname — no account, no cookie, no title. The code is therefore treated as a
 *   secret, and is twelve characters from an unambiguous alphabet so it cannot be guessed.
 * - **It depends on someone else's uptime and quota.** [baseUrl] is configurable
 *   (`together.ntfy.url`) so a self-hosted instance or a mirror can be substituted without touching
 *   code, and the bundled relay remains available for anyone who would rather run their own.
 */
class NtfyTransport(
    private val scope: CoroutineScope,
    private val baseUrl: String = defaultBaseUrl(),
    private val client: OkHttpClient = defaultClient(),
    /** Injectable so liveness is judged by elapsed time, not by the service's clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) : TogetherTransport {

    /** No setup is required: the public instance is the default. */
    override val configured: Boolean get() = baseUrl.isNotBlank()

    /** The room code *is* the topic name, so it must be long enough not to be guessed. */
    override val codeLength: Int get() = TogetherCode.LENGTH

    /**
     * The publish heartbeat: slow on purpose.
     *
     * A *change* does not wait for this — the subscription stream delivers it the moment it happens,
     * and a local action re-publishes immediately (`TogetherSession.noteUserAction` wakes the loop).
     * So this only has to carry the slowly-drifting position and prove the member is still here.
     *
     * It used to be 5s, and that was still too much: the public instance's allowance is **per source
     * address and shared**, so everyone behind one home/office NAT draws on the same quota. Combined
     * with the fact that both members publish, a fast heartbeat spends the whole budget on a feature
     * whose updates are mostly already delivered by the stream. Every 30s cuts the request rate by
     * another ten times, and drift between two devices over 30s is a few tens of milliseconds — far
     * inside the 2s tolerance that triggers a correction.
     */
    override val pollIntervalMs: Long get() = 30_000L

    private val base: String get() = baseUrl.trimEnd('/')

    /** Latest state per peer, written by the stream thread and read by the session thread. */
    private val lock = Any()
    private val peersByUid = mutableMapOf<String, TogetherPeerState>()
    private val heardAt = mutableMapOf<String, Long>()

    private var selfUid: String = ""
    private var subscription: Job? = null
    private var streamCall: Call? = null

    override suspend fun createRoom(uid: String, name: String): TogetherResult<String> {
        // Local by construction: there is no room to create on the service, only a topic to agree on.
        beginSession(uid)
        return TogetherResult.Ok(TogetherCode.fresh())
    }

    override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> =
        // Nothing to look up: a topic exists as soon as someone publishes to it. An empty list is the
        // honest answer, and the join flow treats it as "go ahead".
        TogetherResult.Ok(emptyList())

    override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> {
        beginSession(uid)
        // Announce presence immediately so the host sees someone arrive before any music is chosen.
        return publish(code, uid, name, songId = 0L, positionMs = 0L, playing = false, seq = 0L).map { }
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
        if (selfUid != uid) beginSession(uid)
        val published = publish(code, uid, name, songId, positionMs, playing, seq)
        if (published is TogetherResult.Failed) return published
        // No fetch here: the stream has already been delivering whatever the peer said.
        return TogetherResult.Ok(snapshot())
    }

    /**
     * The peers seen so far.
     *
     * No network call: the subscription keeps this current, so this is a read of accumulated state.
     * Members who have gone quiet are dropped here — liveness is measured by elapsed time since we last
     * heard from them, which is a question about *our* clock, so no clock synchronisation is involved.
     */
    override suspend fun pollPeers(
        code: String,
        excludeUid: String,
    ): TogetherResult<List<TogetherPeerState>> = TogetherResult.Ok(snapshot())

    override suspend fun leave(code: String, uid: String): TogetherResult<Unit> {
        // A tombstone rather than silence: the peer drops this member immediately instead of waiting
        // for it to go stale, and it must not be treated as "the peer chose this song".
        val result = publish(code, uid, "", songId = 0L, positionMs = 0L, playing = false, seq = 0L)
        endSession()
        return result.map { }
    }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> {
        val result = publish(code, selfUid, "", songId = 0L, positionMs = 0L, playing = false, seq = 0L)
        endSession()
        return result.map { }
    }

    // ------------------------------------------------------------------ the stream

    /** Resets the peer table and starts (or restarts) the subscription for [uid]. */
    private fun beginSession(uid: String) {
        selfUid = uid
        synchronized(lock) {
            peersByUid.clear()
            heardAt.clear()
        }
        startSubscription()
    }

    private fun endSession() {
        subscription?.cancel()
        subscription = null
        // Cancelling the coroutine does not close a blocking socket read, so the Call is cancelled too.
        streamCall?.cancel()
        streamCall = null
        synchronized(lock) {
            peersByUid.clear()
            heardAt.clear()
        }
    }

    private fun startSubscription() {
        subscription?.cancel()
        subscription = scope.launch(Dispatchers.IO) {
            // `since=30m` on the first connect replays recent history, so joining a room that already
            // has a track in it finds that track; afterwards the stream is live.
            val request = Request.Builder()
                .url("$base/${topic(currentTopic)}/json?since=$INITIAL_WINDOW")
                .header("Accept", "application/x-ndjson")
                .get()
                .build()
            while (true) {
                try {
                    val call = client.newCall(request)
                    streamCall = call
                    call.execute().use { response ->
                        val source = response.body?.source() ?: return@launch
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            consume(line)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A stream can drop (network change, service restart). Reconnect rather than going
                    // deaf for the rest of the session.
                    lastStreamError = e.message
                }
                if (!isActive) break
                kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private var currentTopic: String = ""

    private var lastStreamError: String? = null

    /** One line of the NDJSON stream: either a message or an event to ignore (open/keepalive). */
    private fun consume(line: String) {
        if (line.isBlank()) return
        val event = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (event.optString("event") != "message") return
        val payload = runCatching { JSONObject(event.optString("message")) }.getOrNull() ?: return
        val uid = payload.optString("uid")
        if (uid.isBlank() || uid == selfUid) return
        synchronized(lock) {
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

    private fun snapshot(): List<TogetherPeerState> = synchronized(lock) {
        // A member who stops publishing is gone; "how long since I last heard from them" is measured on
        // our own clock, so no clock agreement is needed.
        val now = clock()
        heardAt.filterValues { now - it > MEMBER_TTL_MS }.keys.forEach { uid ->
            peersByUid.remove(uid)
            heardAt.remove(uid)
        }
        peersByUid.values.toList()
    }

    // ------------------------------------------------------------------ publishing

    private suspend fun publish(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<JSONObject> = withContext(Dispatchers.IO) {
        currentTopic = code
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
                when {
                    // Named separately: this is the one failure the user can act on, and it must not read
                    // as "network error".
                    response.code == 429 -> TogetherResult.RateLimited("服务限流，请稍后再试")
                    !response.isSuccessful -> TogetherResult.Failed("发送失败 HTTP ${response.code}")
                    else -> TogetherResult.Ok(payload)
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

        /** First connect looks back this far, so a room joined a moment ago still finds the host. */
        const val INITIAL_WINDOW = "30m"

        /**
         * How long a member may be silent before it is treated as having left.
         *
         * Comfortably more than the 30s heartbeat, so an ordinary missed beat does not make the other
         * person appear to vanish, but short enough that closing the app shows up within about a minute.
         */
        const val MEMBER_TTL_MS = 75_000L

        /** Wait before reopening a dropped stream. */
        const val RECONNECT_DELAY_MS = 2_000L

        fun topic(code: String): String = TogetherCode.topic(code)

        fun defaultBaseUrl(): String =
            BuildConfig.TOGETHER_NTFY_URL.ifBlank { "https://ntfy.sh" }

        /**
         * The read timeout is `0` on purpose: the subscription connection is meant to stay open, and any
         * finite read timeout would tear it down mid-song.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
