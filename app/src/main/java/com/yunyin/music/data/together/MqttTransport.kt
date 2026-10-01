package com.yunyin.music.data.together

import com.yunyin.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/**
 * Listen-together over MQTT — the default transport, and the one without a request budget.
 *
 * ## Why MQTT rather than the HTTP service
 *
 * The first transport published and read over HTTP. That worked, but the public instance's allowance
 * is **per source address and shared**, so two people behind one home router (or one developer running
 * tests) spend the same quota — it surfaced in the app as HTTP 429. MQTT has none of that shape: one
 * long-lived connection per device, the broker *pushes* on it, and receiving costs nothing per message.
 *
 * ## How a room is laid out
 *
 * There is no room registry; the code names a topic subtree, and **each member gets its own retained
 * topic**:
 *
 *   members publish (retained)   `<base>/<code>/<uid>`   this member's current state
 *   members subscribe            `<base>/<code>/+`       everyone else's
 *
 * Retained is the important part. The broker keeps the last message on a topic, so someone joining an
 * existing room receives the other member's state **immediately** instead of waiting for the next
 * heartbeat — and because the slot is per member, two members cannot overwrite each other's state
 * (which a single shared topic would do).
 *
 * ## Failure it does not hide
 *
 * A retained message outlives the device. Leaving therefore publishes an **empty** retained payload to
 * clear the slot; otherwise a departed member would appear present to the next person who joins.
 */
class MqttTransport(
    private val scope: CoroutineScope,
    private val host: String = defaultHost(),
    private val port: Int = defaultPort(),
    private val onConnectionChanged: (Boolean) -> Unit = {},
) : TogetherTransport {

    /** Always available: public brokers are reachable without registration or configuration. */
    override val configured: Boolean get() = host.isNotBlank()

    /**
     * How often the member re-publishes its own state.
     *
     * Slow, because the broker pushes changes the instant they happen and a local action publishes at
     * once (`TogetherSession.noteUserAction`). The heartbeat carries the drifting position and keeps the
     * retained slot fresh; 20s of drift between two devices is tens of milliseconds, well inside the 2s
     * correction tolerance.
     */
    override val pollIntervalMs: Long get() = 20_000L

    private val lock = Any()
    private val peersByUid = mutableMapOf<String, TogetherPeerState>()
    private val heardAt = mutableMapOf<String, Long>()

    private var selfUid: String = ""
    private var roomCode: String = ""

    private val client = MqttClient(
        host = host,
        port = port,
        scope = scope,
        onMessage = { topic, payload -> onIncoming(topic, payload) },
        onConnectionChanged = onConnectionChanged,
    )

    override suspend fun createRoom(uid: String, name: String): TogetherResult<String> {
        // Local by construction: the code names a topic subtree, so there is nothing to create.
        selfUid = uid
        return TogetherResult.Ok(TogetherCode.fresh())
    }

    override suspend fun roomInfo(code: String): TogetherResult<List<TogetherMember>> =
        // Nothing to look up; a topic subtree simply exists. An empty list means "go ahead", which is
        // how the join flow reads it.
        TogetherResult.Ok(emptyList())

    override suspend fun join(code: String, uid: String, name: String): TogetherResult<Unit> {
        val ready = ensureSubscribed(code, uid)
        if (ready is TogetherResult.Failed) return ready
        // Announce presence at once so the host sees someone arrive before any music is chosen.
        return publish(code, uid, name, songId = 0L, positionMs = 0L, playing = false, seq = 0L)
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
        val ready = ensureSubscribed(code, uid)
        // A dropped connection is transient, so the caller's loop should retry; but if the broker has
        // never been reachable, the message names it so the reason is diagnosable rather than a
        // generic failure.
        if (ready is TogetherResult.Failed) return ready
        val published = publish(code, uid, name, songId, positionMs, playing, seq)
        if (published is TogetherResult.Failed) return published
        // No fetch: whatever the peer said has already been pushed to us.
        return TogetherResult.Ok(snapshot())
    }

    override suspend fun pollPeers(
        code: String,
        excludeUid: String,
    ): TogetherResult<List<TogetherPeerState>> = TogetherResult.Ok(snapshot())

    override suspend fun leave(code: String, uid: String): TogetherResult<Unit> {
        // Clear our retained slot: otherwise a device that left keeps looking present to anyone who
        // joins later, because a retained message outlives its publisher.
        clearRetained(code, uid)
        client.close()
        synchronized(lock) {
            peersByUid.clear()
            heardAt.clear()
        }
        return TogetherResult.Ok(Unit)
    }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> = leave(code, selfUid)

    // ------------------------------------------------------------------ plumbing

    /**
     * Brings the subscription up, **waiting for the connection**.
     *
     * Connecting is asynchronous, so publishing straight after `connect()` always failed with
     * 尚未连接 — which is what the join used to report. Waiting here is the fix; the timeout is long
     * enough for a slow network and short enough that a wrong broker does not hang the UI.
     */
    private suspend fun ensureSubscribed(code: String, uid: String): TogetherResult<Unit> {
        selfUid = uid
        if (roomCode == code && client.isConnected) return TogetherResult.Ok(Unit)
        if (roomCode != code) {
            synchronized(lock) {
                peersByUid.clear()
                heardAt.clear()
            }
            roomCode = code
        }
        client.connect()
        if (!client.awaitConnected(CONNECT_WAIT_MS)) {
            // Named explicitly: on a mobile network an unreachable broker on port 1883 is most likely a
            // blocked port, and "无法连接 broker.hivemq.com:1883" says far more than "尚未连接".
            return TogetherResult.Failed("无法连接消息服务器 $host:$port")
        }
        // The wildcard covers every member's slot under this room, including our own — incoming
        // messages from ourselves are filtered in onIncoming.
        client.subscribe("$TOPIC_ROOT/$code/+")
        return TogetherResult.Ok(Unit)
    }

    private fun onIncoming(topic: String, payload: String) {
        // Our own slot; the broker echoes it back to us and it is not a peer.
        if (topic.endsWith("/$selfUid")) return
        if (payload.isBlank()) {
            // An empty retained payload is a *tombstone*: that member cleared its slot by leaving.
            val uid = topic.substringAfterLast('/')
            synchronized(lock) {
                peersByUid.remove(uid)
                heardAt.remove(uid)
            }
            return
        }
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val uid = json.optString("uid").takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            peersByUid[uid] = TogetherPeerState(
                uid = uid,
                name = json.optString("name"),
                songId = json.optLong("songId"),
                positionMs = json.optLong("positionMs"),
                playing = json.optBoolean("playing"),
                updatedAt = json.optLong("updatedAt"),
                seq = json.optLong("seq"),
            )
            heardAt[uid] = System.currentTimeMillis()
        }
    }

    private fun snapshot(): List<TogetherPeerState> = synchronized(lock) {
        // Liveness is "how long since I last heard from them", measured on our own clock, so the two
        // devices never have to agree on the time.
        val now = System.currentTimeMillis()
        heardAt.filterValues { now - it > MEMBER_TTL_MS }.keys.forEach { uid ->
            peersByUid.remove(uid)
            heardAt.remove(uid)
        }
        peersByUid.values.toList()
    }

    private fun publish(
        code: String,
        uid: String,
        name: String,
        songId: Long,
        positionMs: Long,
        playing: Boolean,
        seq: Long,
    ): TogetherResult<Unit> {
        if (!client.isConnected) return TogetherResult.Failed("尚未连接")
        val payload = JSONObject()
            .put("uid", uid)
            .put("name", name)
            .put("songId", songId)
            .put("positionMs", positionMs)
            .put("playing", playing)
            .put("updatedAt", System.currentTimeMillis())
            .put("seq", seq)
            .toString()
        // Retained, so a later joiner sees this member's state at once.
        client.publish("$TOPIC_ROOT/$code/$uid", payload, retain = true)
        return TogetherResult.Ok(Unit)
    }

    private fun clearRetained(code: String, uid: String) {
        client.publish("$TOPIC_ROOT/$code/$uid", "", retain = true)
    }

    private companion object {
        /** Namespaced and versioned, so this cannot collide with another app's topics on a public broker. */
        const val TOPIC_ROOT = "yunyin/together/v1"

        /** Longer than the 20s heartbeat, so a single missed beat does not make the peer vanish. */
        const val MEMBER_TTL_MS = 75_000L

        /** How long to wait for the broker before telling the user it could not be reached. */
        const val CONNECT_WAIT_MS = 12_000L

        /**
         * A public broker that accepts anonymous connections and is widely reachable. Overridable via
         * `together.mqtt.host` / `together.mqtt.port`, so a self-hosted broker (or a different public
         * one) needs no code change.
         */
        fun defaultHost(): String =
            BuildConfig.TOGETHER_MQTT_HOST.ifBlank { "broker.hivemq.com" }

        fun defaultPort(): Int = BuildConfig.TOGETHER_MQTT_PORT.ifBlank { "1883" }.toIntOrNull() ?: 1883
    }
}
