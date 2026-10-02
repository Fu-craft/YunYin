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
    private val candidates: List<Broker> = defaultCandidates(),
    private val onConnectionChanged: (Boolean) -> Unit = {},
) : TogetherTransport {

    /**
     * One broker to try, and how to reach it.
     *
     * The list is ordered and **both members iterate the same list in the same order**, which is what
     * keeps them aligned: the first address that accepts a connection is the one used. (A transport is a
     * rendezvous, so two devices picking different addresses would sit in two empty rooms that look
     * identical.) [label] is what the room sheet shows, so a mismatch is visible rather than silent.
     */
    data class Broker(
        val label: String,
        val kind: Kind,
        val host: String = "",
        val port: Int = 0,
        val tls: Boolean = false,
        val url: String = "",
    ) {
        enum class Kind { WEB_SOCKET, TCP }

        companion object {
            /** `wss://…:8084/mqtt` — reachable on networks that block the MQTT ports outright. */
            fun webSocket(url: String) = Broker(label = url, kind = Kind.WEB_SOCKET, url = url)

            fun tcp(host: String, port: Int, tls: Boolean) =
                Broker(label = "$host:$port${if (tls) " (TLS)" else ""}", kind = Kind.TCP,
                    host = host, port = port, tls = tls)
        }
    }

    /** Always available: public brokers are reachable without registration or configuration. */
    override val configured: Boolean get() = candidates.isNotEmpty()

    /** A broker room has no registry, so the code doubles as a topic name and must not be guessable. */
    override val codeLength: Int get() = TogetherCode.LENGTH

    /** The address actually in use, for display and for diagnosing a mismatch between the two sides. */
    override val serverLabel: String? get() = chosen?.label

    private var chosen: Broker? = null
    private var client: MqttClient? = null

    /**
     * Used only for WebSocket connections.
     *
     * `readTimeout(0)` because these connections are meant to stay open for the whole session, and
     * `pingInterval` so a silently dropped link is noticed rather than sitting there looking healthy —
     * the WebSocket-level equivalent of the MQTT keep-alive, for the case where the path dies without a
     * FIN.
     */
    private val wsClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

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
        client?.close()
        client = null
        chosen = null
        synchronized(lock) {
            peersByUid.clear()
            heardAt.clear()
        }
        return TogetherResult.Ok(Unit)
    }

    override suspend fun closeRoom(code: String): TogetherResult<Unit> = leave(code, selfUid)

    // ------------------------------------------------------------------ plumbing

    /**
     * Brings up a connection and an **acknowledged** subscription, trying each broker in turn.
     *
     * Three things are being handled here, each of which was a separate reported failure:
     *
     *  1. **A broker that is unreachable.** A blocked port or a filtered domain is a property of the
     *     network, not a reason to give up — the next candidate gets a turn, and in practice
     *     `broker.hivemq.com` and `broker.emqx.io` are rarely blocked at the same time.
     *  2. **Connecting is asynchronous**, so publishing straight after `connect()` reported 尚未连接.
     *  3. **A subscription is not in effect until its SUBACK arrives**, so publishing before that sends
     *     state into a room we cannot hear — which presents as "the room contains only me".
     */
    private suspend fun ensureSubscribed(code: String, uid: String): TogetherResult<Unit> {
        selfUid = uid
        if (roomCode == code && client?.isConnected == true && subscribedRoom == code) {
            return TogetherResult.Ok(Unit)
        }
        if (roomCode != code) {
            synchronized(lock) {
                peersByUid.clear()
                heardAt.clear()
            }
            roomCode = code
            subscribedRoom = null
        }

        // Already connected to the working address: nothing to re-establish.
        val live = client
        if (live != null && live.isConnected) {
            val filter = "$TOPIC_ROOT/$code/+"
            if (subscribedRoom == code) return TogetherResult.Ok(Unit)
            live.subscribe(filter)
            if (live.awaitSubscribed(filter, SUBSCRIBE_WAIT_MS)) {
                subscribedRoom = code
                return TogetherResult.Ok(Unit)
            }
        }

        val filter = "$TOPIC_ROOT/$code/+"
        val failures = mutableListOf<String>()
        for (broker in candidates) {
            val candidate = MqttClient(
                // The pipe is the only thing that differs between endpoints; the protocol above it is
                // identical, which is why MQTT-over-WebSocket needs no separate implementation.
                openStream = when (broker.kind) {
                    Broker.Kind.WEB_SOCKET -> { { WebSocketMqttStream(broker.url, wsClient) } }
                    Broker.Kind.TCP -> { { TcpMqttStream(broker.host, broker.port, broker.tls) } }
                },
                scope = scope,
                onMessage = { topic, payload -> onIncoming(topic, payload) },
                onConnectionChanged = onConnectionChanged,
            )
            candidate.connect()
            if (!candidate.awaitConnected(CONNECT_WAIT_MS)) {
                candidate.close()
                failures += broker.label
                continue
            }
            candidate.subscribe(filter)
            if (!candidate.awaitSubscribed(filter, SUBSCRIBE_WAIT_MS)) {
                candidate.close()
                failures += "${broker.label}(订阅未确认)"
                continue
            }
            // Success: adopt it, and drop whatever we were using before so one broker is in effect.
            live?.close()
            client = candidate
            chosen = broker
            subscribedRoom = code
            return TogetherResult.Ok(Unit)
        }
        return TogetherResult.Failed("无法连接消息服务器（已尝试：${failures.joinToString("、")}）")
    }

    /** The room whose subscription the broker has confirmed, so the wait happens once, not per publish. */
    private var subscribedRoom: String? = null

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
        val active = client
        if (active == null || !active.isConnected) return TogetherResult.Failed("尚未连接")
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
        active.publish("$TOPIC_ROOT/$code/$uid", payload, retain = true)
        return TogetherResult.Ok(Unit)
    }

    private fun clearRetained(code: String, uid: String) {
        client?.publish("$TOPIC_ROOT/$code/$uid", "", retain = true)
    }

    private companion object {
        /** Namespaced and versioned, so this cannot collide with another app's topics on a public broker. */
        const val TOPIC_ROOT = "yunyin/together/v1"

        /** Longer than the 20s heartbeat, so a single missed beat does not make the peer vanish. */
        const val MEMBER_TTL_MS = 75_000L

        /** How long to wait for the broker before telling the user it could not be reached. */
        const val CONNECT_WAIT_MS = 12_000L

        /**
         * How long to wait for the subscription to be acknowledged.
         *
         * Generous because the client re-sends an unacknowledged SUBSCRIBE every couple of seconds, and
         * a public broker can take a moment — but still finite, so an uncooperative broker surfaces as an
         * error instead of a room that silently never fills.
         */
        const val SUBSCRIBE_WAIT_MS = 20_000L

        /**
         * The endpoints to try, **in a fixed order that both members iterate identically**.
         *
         * Trying more than one is the point: a blocked port or a filtered domain is a property of the
         * network, and the two members are usually on *different* networks — so if each stopped at its
         * first-choice address, the two could easily end up on different brokers and sit in two rooms
         * that are both empty. Same list, same order, first address that accepts both wins.
         *
         * **WebSocket first, and that ordering is measured rather than assumed.** Plain MQTT ports are
         * the ones carriers filter: on the user's phone network both 1883 *and* 8883 were refused, while
         * `wss://…:8084/mqtt` connected immediately (and carried connect/subscribe/publish/retain
         * correctly — verified end to end). Port 8084-over-TLS is not recognisable as MQTT, so it is
         * left alone. The TCP entries stay as fallbacks for networks that do allow them.
         *
         * `test.mosquitto.org` is deliberately absent: it fails certificate verification, so a client
         * would have to weaken TLS to use it.
         *
         * `together.mqtt.host` (with optional `port`/`tls`) replaces this list entirely, for a
         * self-hosted broker.
         */
        fun defaultCandidates(): List<Broker> {
            val configuredHost = BuildConfig.TOGETHER_MQTT_HOST.trim()
            if (configuredHost.isNotEmpty()) {
                val port = BuildConfig.TOGETHER_MQTT_PORT.trim().toIntOrNull() ?: 8883
                return listOf(Broker.tcp(configuredHost, port, tlsFor(port)))
            }
            return listOf(
                Broker.webSocket("wss://broker.emqx.io:8084/mqtt"),
                Broker.webSocket("wss://broker.hivemq.com:8884/mqtt"),
                Broker.tcp("broker.hivemq.com", 8883, tls = true),
                Broker.tcp("broker.emqx.io", 8883, tls = true),
            )
        }

        /**
         * TLS unless explicitly disabled.
         *
         * `together.mqtt.tls=false` (or `0`) is the only way to turn it off, for a network that allows
         * plain 1883 and prefers it; the conventional TLS port implies it regardless.
         */
        fun tlsFor(port: Int): Boolean {
            val configured = BuildConfig.TOGETHER_MQTT_TLS.trim()
            return when {
                configured.equals("false", ignoreCase = true) || configured == "0" -> false
                port == 8883 -> true
                else -> true
            }
        }
    }
}
