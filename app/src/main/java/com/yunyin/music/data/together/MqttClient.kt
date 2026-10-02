package com.yunyin.music.data.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.OutputStream

/**
 * A minimal MQTT 3.1.1 client — just enough for the listen-together room, and nothing more.
 *
 * ## Why hand-rolled
 *
 * The feature needs exactly four things: connect anonymously, subscribe to one topic, publish small
 * payloads, and keep the connection alive. That is a few hundred lines of a well-specified protocol,
 * where a library would add a dependency and an API surface far larger than the need; this project
 * keeps its dependency list short on purpose.
 *
 * Deliberately **not** implemented: QoS 1/2 (state is re-sent periodically, so a lost message costs one
 * interval, not correctness), authentication, wildcards, last-will, session resumption. Each is a real
 * feature this use does not need.
 *
 * ## The pipe is pluggable
 *
 * Packets are written to and read from an [MqttStream], which is a plain TCP/TLS socket or a WebSocket.
 * That split exists because the *network* decides which one is reachable: a carrier that blocks 1883
 * and 8883 usually still permits `wss://…:8084`, and the protocol above this line does not care.
 *
 * ## Threading, and the one rule that matters
 *
 * **Exactly one thread reads the stream** (the connection loop). MQTT has no framing other than the
 * stream itself, so a second reader — even one that only "just checks for a SUBACK" — would consume
 * bytes the main loop needs and corrupt every subsequent packet. Everything that needs to observe the
 * stream does so through state that the reader loop writes:
 *
 *  - incoming messages       -> [onMessage]
 *  - subscription acks       -> [subscribed]
 *  - connection up/down      -> [connected]
 *
 * ## Why the subscription is tracked, not fired and forgotten
 *
 * A public broker intermittently accepts a SUBSCRIBE and never sends the SUBACK. The client then looks
 * connected, keeps publishing happily, and **receives nothing** — which presents as "the room contains
 * only me". So subscribing is a supervised activity: unacknowledged topics are re-sent until confirmed,
 * and [awaitSubscribed] lets a caller wait for the real answer before it starts publishing.
 */
class MqttClient(
    /** Opens a fresh byte pipe for one connection attempt. */
    private val openStream: () -> MqttStream,
    private val scope: CoroutineScope,
    private val clientIdPrefix: String = "yunyin",
    private val onMessage: (topic: String, payload: String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
) {

    private var stream: MqttStream? = null
    private var output: OutputStream? = null

    /** Serialises writes: a half-written packet followed by another is unparseable to the broker. */
    private val writeLock = Any()

    private var connectionLoop: Job? = null
    private var pinger: Job? = null
    private var supervisor: Job? = null

    /** Observes the connection so callers can wait instead of guessing. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Topics the broker has acknowledged. Together with [desiredTopics] this drives re-subscription. */
    private val desiredTopics = linkedSetOf<String>()
    private val subscribed = MutableStateFlow<Set<String>>(emptySet())

    /** Packet id -> topic, so a SUBACK (which carries only the id) can be attributed. */
    private val pendingSubscribes = mutableMapOf<Int, String>()
    private var nextPacketId = 0
    private val stateLock = Any()

    val isConnected: Boolean get() = _connected.value

    /** The most recent connection failure, for a caller that wants to explain *why*. */
    var lastError: String? = null
        private set

    // ------------------------------------------------------------------ lifecycle

    /** Connects and stays connected, reconnecting with a backoff until [close]. Safe to call twice. */
    fun connect() {
        if (connectionLoop != null) return
        connectionLoop = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                try {
                    openOnce()
                    attempt = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message
                    attempt++
                } finally {
                    teardown()
                }
                if (!isActive) break
                // Back off, but never longer than 30s: a room should recover on its own.
                delay((1_000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L))
            }
        }
    }

    fun close() {
        connectionLoop?.cancel()
        connectionLoop = null
        sendControlPacket(DISCONNECT)
        teardown()
    }

    /**
     * Suspends until the connection is up, or [timeoutMs] elapses.
     *
     * A false means the endpoint was not reachable within the window — on a mobile network quite
     * possibly a blocked port, so the caller should say so.
     */
    suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { connected.first { it } } != null

    /**
     * Suspends until [topic] is acknowledged by the broker.
     *
     * This is what a caller waits on before publishing: publishing on an unacknowledged subscription
     * means the peer's messages never arrive while ours go out, so the room would look one-sided.
     */
    suspend fun awaitSubscribed(topic: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { subscribed.first { topic in it } } != null

    fun subscribe(topic: String) {
        synchronized(stateLock) { desiredTopics += topic }
        startSupervisor()
    }

    /** Publishes a payload, optionally **retained**. */
    fun publish(topic: String, payload: String, retain: Boolean) {
        if (!isConnected) return
        runCatching { sendPublish(topic, payload.toByteArray(Charsets.UTF_8), retain) }
    }

    // ------------------------------------------------------------------ connection

    /** Opens a pipe, handshakes, then reads it until it ends (throwing so we reconnect). */
    private suspend fun openOnce() {
        val opened = openStream()
        if (!opened.awaitReady(STREAM_READY_MS)) {
            opened.close()
            throw IllegalStateException("stream did not become ready")
        }
        stream = opened
        output = opened.output.buffered()
        val input = DataInputStream(opened.input.buffered())

        sendConnect(output!!)
        val connackType = input.readUnsignedByte()
        val connackLen = readRemainingLength(input)
        val connack = ByteArray(connackLen).also { input.readFully(it) }
        if (connackType != CONNACK || connack.size < 2 || connack.last() != 0.toByte()) {
            throw IllegalStateException("broker refused the connection (type=$connackType)")
        }

        lastError = null
        _connected.value = true
        onConnectionChanged(true)

        // A fresh session has no acknowledgements; the supervisor re-sends every desired topic.
        synchronized(stateLock) {
            subscribed.value = emptySet()
            pendingSubscribes.clear()
        }
        startSupervisor()
        startPinger()

        // The single reader loop. Returns (throwing) when the stream ends, so the outer loop reconnects.
        while (true) {
            val header = input.readUnsignedByte()
            val type = header shr 4
            val remaining = readRemainingLength(input)
            val body = ByteArray(remaining).also { input.readFully(it) }
            when (type) {
                PUBLISH -> {
                    // QoS 0 only, so the fixed header's low bits are just the retain flag.
                    val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                    val topic = String(body, 2, topicLength, Charsets.UTF_8)
                    val payload = String(body, 2 + topicLength, body.size - 2 - topicLength, Charsets.UTF_8)
                    onMessage(topic, payload)
                }
                SUBACK -> onSubAck(body)
                // PINGRESP, PUBACK... nothing to do for QoS 0.
                else -> Unit
            }
        }
    }

    /**
     * Records a SUBACK.
     *
     * The body is the packet id followed by one granted-QoS byte per requested filter. A `0x80` grant
     * means the broker *refused* that subscription — treating it as success is how a client ends up
     * publishing into a room it can never hear.
     */
    private fun onSubAck(body: ByteArray) {
        if (body.size < 3) return
        val granted = body[2].toInt() and 0xFF
        if (granted > 2) return
        val id = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        synchronized(stateLock) {
            val topic = pendingSubscribes.remove(id)
            if (topic != null) subscribed.value = subscribed.value + topic
        }
    }

    private fun teardown() {
        pinger?.cancel()
        pinger = null
        supervisor?.cancel()
        supervisor = null
        runCatching { stream?.close() }
        stream = null
        output = null
        _connected.value = false
        synchronized(stateLock) {
            subscribed.value = emptySet()
            pendingSubscribes.clear()
        }
        onConnectionChanged(false)
    }

    /**
     * Re-sends any desired-but-unacknowledged subscription until it is confirmed.
     *
     * The retry is the point: a public broker sometimes drops a SUBSCRIBE silently, and without this
     * the client would stay deaf for the whole session while looking perfectly healthy.
     */
    private fun startSupervisor() {
        if (supervisor?.isActive == true) return
        supervisor = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!isConnected) {
                    delay(SUPERVISE_INTERVAL_MS)
                    continue
                }
                val pending = synchronized(stateLock) {
                    desiredTopics.filterNot { it in subscribed.value }
                }
                pending.forEach { topic ->
                    val id = synchronized(stateLock) {
                        val next = ++nextPacketId
                        pendingSubscribes[next] = topic
                        next
                    }
                    runCatching { sendSubscribe(topic, id) }
                }
                delay(SUPERVISE_INTERVAL_MS)
            }
        }
    }

    /**
     * Sends PINGREQ on its own timer.
     *
     * Not driven by the read loop: a quiet room sends nothing for long stretches, and the broker drops a
     * client that never pings — precisely when the connection looks healthy.
     */
    private fun startPinger() {
        pinger?.cancel()
        pinger = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEPALIVE_MS / 2)
                if (isConnected) sendControlPacket(PINGREQ)
            }
        }
    }

    // ------------------------------------------------------------------ packets

    private fun sendConnect(out: OutputStream) {
        val clientId = "$clientIdPrefix-${java.util.UUID.randomUUID()}".take(23)
        val keepAliveSeconds = (KEEPALIVE_MS / 1000).toInt()
        val variable = byteArrayOf(
            0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(),
            0x04,                       // protocol level 4 = MQTT 3.1.1
            0x02,                       // connect flags: clean session
            // Keep-alive is a **big-endian** 16-bit field: high byte first. Writing the seconds into the
            // first byte would ask for `seconds * 256`, and the broker would drop us for silence.
            ((keepAliveSeconds shr 8) and 0xFF).toByte(),
            (keepAliveSeconds and 0xFF).toByte(),
        )
        val payload = byteArrayOf((clientId.length shr 8).toByte(), clientId.length.toByte()) +
            clientId.toByteArray(Charsets.UTF_8)
        writePacket(out, CONNECT, variable + payload)
    }

    private fun sendSubscribe(topic: String, packetId: Int) {
        val body = byteArrayOf((packetId shr 8).toByte(), packetId.toByte()) +
            byteArrayOf((topic.length shr 8).toByte(), topic.length.toByte()) +
            topic.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00)          // requested QoS 0
        writePacket(out(), SUBSCRIBE, body, firstBits = 0x02)   // the low bits are reserved, must be 2
    }

    private fun sendPublish(topic: String, payload: ByteArray, retain: Boolean) {
        val body = byteArrayOf((topic.length shr 8).toByte(), topic.length.toByte()) +
            topic.toByteArray(Charsets.UTF_8) + payload
        writePacket(out(), PUBLISH, body, firstBits = if (retain) 0x01 else 0x00)
    }

    private fun out(): OutputStream = output ?: throw IllegalStateException("not connected")

    /** Sends a two-byte packet (PINGREQ / DISCONNECT): fixed header plus a zero remaining-length. */
    private fun sendControlPacket(type: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            runCatching {
                out.write(type shl 4)
                out.write(0)
                out.flush()
            }
        }
    }

    private fun writePacket(out: OutputStream, type: Int, body: ByteArray, firstBits: Int = 0) {
        synchronized(writeLock) {
            out.write((type shl 4) or firstBits)
            writeRemainingLength(out, body.size)
            out.write(body)
            out.flush()
        }
    }

    /** MQTT's variable-length remaining-length field: 7 bits per byte, high bit means "continues". */
    private fun readRemainingLength(input: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        var encoded: Int
        do {
            encoded = input.readUnsignedByte()
            value += (encoded and 0x7F) * multiplier
            multiplier *= 128
        } while (encoded and 0x80 != 0 && multiplier <= 128 * 128 * 128)
        return value
    }

    private fun writeRemainingLength(out: OutputStream, length: Int) {
        var value = length
        do {
            var encoded = value % 128
            value /= 128
            if (value > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (value > 0)
    }

    private companion object {
        const val CONNECT = 1
        const val CONNACK = 2
        const val PUBLISH = 3
        const val SUBSCRIBE = 8
        const val SUBACK = 9
        const val PINGREQ = 12
        const val DISCONNECT = 14

        const val KEEPALIVE_MS = 30_000L

        /** How long to wait for a WebSocket handshake (or a socket) before trying the next endpoint. */
        const val STREAM_READY_MS = 12_000L

        /** How often an unacknowledged subscription is re-sent. */
        const val SUPERVISE_INTERVAL_MS = 2_500L
    }
}
