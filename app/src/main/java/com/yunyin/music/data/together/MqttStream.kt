package com.yunyin.music.data.together

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * The byte pipe MQTT packets travel over.
 *
 * The protocol is identical whichever pipe it is on — which is exactly why this is an interface. A
 * public broker is reachable over plain TCP (1883/8883) *or*, on a network that blocks those ports,
 * over **WebSocket** (`wss://…:8084/mqtt`), which is indistinguishable from ordinary TLS traffic to a
 * carrier. That is not hypothetical: measured on the user's phone network, 1883 **and** 8883 are both
 * blocked while 8084-over-wss connects fine.
 *
 * Keeping this separate means [MqttClient]'s packet handling — connect, subscribe, retain, keep-alive —
 * is written once and works on both.
 */
interface MqttStream {
    val input: InputStream
    val output: OutputStream

    /** Suspends until the pipe is usable, or [timeoutMs] elapses. */
    suspend fun awaitReady(timeoutMs: Long): Boolean

    fun close()
}

/** Plain TCP, optionally wrapped in TLS. */
class TcpMqttStream(host: String, port: Int, tls: Boolean) : MqttStream {

    private val socket: Socket

    init {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // TLS wraps the connected socket; `autoClose = true` so closing the wrapper closes the raw one.
        //
        // The intermediate typed values matter: `SSLSocketFactory.getDefault()` is declared to return
        // the *base* `SocketFactory`, so the SSL-specific overload and `startHandshake` are only
        // reachable through an explicit cast. The handshake is worth forcing — it turns a bad
        // certificate or a non-TLS port into an immediate, reportable failure instead of a mysterious
        // read timeout later.
        socket = if (tls) {
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            val ssl = factory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
            ssl.startHandshake()
            ssl
        } else {
            raw
        }
    }

    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    /** A connected socket is immediately writable — the TLS handshake above already completed. */
    override suspend fun awaitReady(timeoutMs: Long): Boolean = true

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
    }
}

/**
 * MQTT over WebSocket, adapted to the same byte-pipe shape.
 *
 * WebSocket is message-framed while MQTT is a byte stream, so this does two small translations:
 * incoming binary frames are queued and re-exposed as a blocking [InputStream], and writes are sent as
 * binary frames. A packet may be split across frames or several packets may share one, so the reader
 * concatenates rather than assuming a frame boundary means a packet boundary.
 */
class WebSocketMqttStream(
    url: String,
    private val client: OkHttpClient,
) : MqttStream {

    /** Received frames, in order. `null` marks end-of-stream so the reader can stop. */
    private val frames = LinkedBlockingQueue<ByteArray?>()
    private val opened = CompletableDeferred<Boolean>()

    private val webSocket: WebSocket = client.newWebSocket(
        Request.Builder()
            .url(url)
            // Required: these brokers accept MQTT on this endpoint only when the subprotocol is
            // requested. (Verified against broker.emqx.io — without it the handshake is refused.)
            .header("Sec-WebSocket-Protocol", "mqtt")
            .build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                frames.put(bytes.toByteArray())
            }

            /** MQTT is binary; a text frame would be a broker misbehaving, so it is ignored. */
            override fun onMessage(webSocket: WebSocket, text: String) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                frames.put(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                frames.put(null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Unblock both an awaiting connect and an awaiting read — otherwise a failed handshake
                // would hang until the caller's own timeout in each place.
                opened.complete(false)
                frames.put(null)
            }
        },
    )

    override val input: InputStream = object : InputStream() {
        private var current: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
            while (true) {
                val buffered = current
                if (buffered != null && offset < buffered.size) {
                    return buffered[offset++].toInt() and 0xFF
                }
                // Need the next frame; `null` (or the queue being drained after close) means EOF.
                val next = frames.take() ?: return -1
                current = next
                offset = 0
            }
        }

        override fun available(): Int = (current?.size ?: 0) - offset
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            webSocket.send(ByteString.of(b.toByte()))
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            // `ByteString.of(array, offset, count)` is deprecated; the extension reads better and is
            // what the library now points at.
            webSocket.send(b.toByteString(off, len))
        }

        /** Writes are already flushed per frame by the WebSocket implementation. */
        override fun flush() = Unit
    }

    override suspend fun awaitReady(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { opened.await() } ?: false

    override fun close() {
        runCatching { webSocket.close(NORMAL_CLOSURE, null) }
        // Make sure a blocked reader is released even if the close callback never fires.
        frames.offer(null)
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
