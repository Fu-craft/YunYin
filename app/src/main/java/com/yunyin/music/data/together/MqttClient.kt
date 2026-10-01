package com.yunyin.music.data.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A minimal MQTT 3.1.1 client — just enough for the listen-together room, and nothing more.
 *
 * ## Why hand-rolled
 *
 * The features needs exactly four things: connect anonymously, subscribe to one topic, publish small
 * payloads, and keep the connection alive. That is a few hundred lines of a forty-year-old protocol,
 * where a library would add a dependency and an API surface far larger than the need. This project
 * already keeps its dependency list short on purpose.
 *
 * What it deliberately does **not** implement: QoS 1/2 (state is re-sent periodically, so a lost
 * message costs one interval, not correctness), authentication, TLS, wildcards, last-will, session
 * resumption. Each is a real feature that this use does not need; adding them unwatched would be the
 * actual risk.
 *
 * ## Threading
 *
 * [publish] may be called from any thread and is serialised by a lock — MQTT packets must not
 * interleave on the wire. The reader runs in [scope] on IO.
 *
 * @param onMessage invoked for every PUBLISH received on a subscribed topic.
 * @param onConnectionChanged reports connect/disconnect, so the caller can surface state.
 */
class MqttClient(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val clientIdPrefix: String = "yunyin",
    private val onMessage: (topic: String, payload: String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit = {},
) {

    private var socket: Socket? = null
    private var output: OutputStream? = null

    /** Serialises writes: a half-written packet followed by another is unparseable to the broker. */
    private val writeLock = Any()

    /** Topic filters to (re)subscribe to, remembered so a reconnect restores them. */
    private val subscriptions = linkedSetOf<String>()
    private val subscriptionsLock = Any()

    private var connectionLoop: Job? = null

    /**
     * Connection state, as observable data.
     *
     * Needed because connecting is **asynchronous**: `connect()` starts a coroutine and returns
     * immediately, so a caller that publishes right after it would always find itself "not connected".
     * That was a real bug — joining a room reported 尚未连接 every time, because the join published
     * before the socket was up. `awaitConnected` is how a caller waits for the real answer.
     */
    private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
    val connected: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected

    val isConnected: Boolean get() = _connected.value

    /**
     * Suspends until the connection is up, or [timeoutMs] elapses.
     *
     * @return true when connected. A false means the broker was not reachable within the window —
     *   which on a mobile network is quite possibly a blocked port rather than a fault, so the caller
     *   should say so rather than reporting a generic failure.
     */
    suspend fun awaitConnected(timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            connected.first { it }
        } != null

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
                    // Any failure lands here: the broker refused, the network dropped, or a read failed.
                    lastError = e.message
                    _connected.value = false
                    onConnectionChanged(false)
                    attempt++
                } finally {
                    pinger?.cancel()
                    pinger = null
                    runCatching { socket?.close() }
                    socket = null
                    output = null
                    _connected.value = false
                }
                if (!isActive) break
                // Back off, but never longer than 30s: a room should recover on its own.
                val waitMs = (1_000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L)
                delay(waitMs)
            }
        }
    }

    /** The most recent connection failure, for a caller that wants to explain *why*. */
    var lastError: String? = null
        private set

    fun close() {
        connectionLoop?.cancel()
        connectionLoop = null
        sendControlPacket(DISCONNECT)
        runCatching { socket?.close() }
        socket = null
        output = null
        _connected.value = false
        onConnectionChanged(false)
    }

    fun subscribe(topic: String) {
        synchronized(subscriptionsLock) { subscriptions += topic }
        // If already connected, subscribe now; otherwise openOnce will do it for every remembered topic.
        if (isConnected) runCatching { sendSubscribe(topic) }
    }

    /**
     * Publishes a payload, optionally **retained**.
     *
     * Retained is what makes a room feel instant to join: the broker keeps the last message on the
     * topic, so a subscriber receives it the moment it subscribes instead of waiting for the next
     * heartbeat. That is the difference between "the new member sees the current song at once" and
     * "the new member sees nothing for up to a heartbeat".
     */
    fun publish(topic: String, payload: String, retain: Boolean) {
        if (!isConnected) return
        runCatching { sendPublish(topic, payload.toByteArray(Charsets.UTF_8), retain) }
    }

    // ------------------------------------------------------------------ connection

    /** Opens a socket, sends CONNECT, waits for CONNACK, then drains the stream until it ends. */
    private fun openOnce() {
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        val input = DataInputStream(newSocket.getInputStream().buffered())
        val out = newSocket.getOutputStream().buffered()

        socket = newSocket
        output = out

        sendConnect(out)
        val connackType = input.readUnsignedByte()
        val connackLen = input.readUnsignedByte()
        val connack = ByteArray(connackLen).also { input.readFully(it) }
        if (connackType != CONNACK || connack.size < 2 || connack.last() != 0.toByte()) {
            throw IllegalStateException("broker refused the connection (type=$connackType)")
        }

        // Fresh connection: restore every subscription.
        synchronized(subscriptionsLock) { subscriptions.toList() }.forEach { sendSubscribe(it) }
        startPinger()
        lastError = null
        _connected.value = true
        onConnectionChanged(true)

        // Reader loop; returns (throwing) when the stream ends so the outer loop reconnects.
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
                // PINGRESP, SUBACK, PUBACK... nothing to do for QoS 0.
                else -> Unit
            }
        }
    }

    /**
     * Sends PINGREQ on its own timer.
     *
     * It must not be driven by the read loop: a quiet room sends nothing for long stretches, and the
     * broker drops a client that never pings — precisely when the connection looks healthy. Also
     * restarted on every reconnect, so a dropped link's pinger cannot outlive it.
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

    private var pinger: Job? = null

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

    // ------------------------------------------------------------------ packets

    private fun sendConnect(out: OutputStream) {
        val clientId = "${clientIdPrefix}-${java.util.UUID.randomUUID()}".take(23)
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

    private fun sendSubscribe(topic: String) {
        packetId++
        val body = byteArrayOf((packetId shr 8).toByte(), packetId.toByte()) +
            byteArrayOf((topic.length shr 8).toByte(), topic.length.toByte()) +
            topic.toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x00)          // requested QoS 0
        writePacket(out(), SUBSCRIBE, body, firstBytes = 0x02)  // low bits are reserved, must be 2
    }

    private fun sendPublish(topic: String, payload: ByteArray, retain: Boolean) {
        val body = byteArrayOf((topic.length shr 8).toByte(), topic.length.toByte()) +
            topic.toByteArray(Charsets.UTF_8) + payload
        writePacket(out(), PUBLISH, body, firstBytes = if (retain) 0x01 else 0x00)
    }

    private fun out(): OutputStream = output ?: throw IllegalStateException("not connected")

    private var packetId = 0

    /** Sends a two-byte packet (PINGREQ / DISCONNECT): fixed header plus a zero remaining-length. */
    private fun sendControlPacket(type: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            out.write(type shl 4)
            out.write(0)
            out.flush()
        }
    }

    private fun writePacket(out: OutputStream, type: Int, body: ByteArray, firstBytes: Int = 0) {
        synchronized(writeLock) {
            out.write((type shl 4) or firstBytes)
            writeRemainingLength(out, body.size)
            out.write(body)
            out.flush()
        }
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
        const val PINGREQ = 12
        const val DISCONNECT = 14

        const val CONNECT_TIMEOUT_MS = 8_000
        const val KEEPALIVE_MS = 30_000L
    }
}
