// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol

import android.os.SystemClock
import com.hhst.youtubelite.diagnostics.CastDiagnosticTrace
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject

/**
 * One CASTV2 socket to one receiver: TLS to port 8009, length-prefixed [CastMessage] frames
 * both ways, plus the two transport-level namespaces every session needs - virtual connections
 * (`CONNECT`/`CLOSE`) and the PING/PONG heartbeat. Everything app-level (receiver
 * control, media) lives above this in [CastV2Session].
 *
 * **Threading.** [open] and every send are serialized on a single writer executor,
 * so callers may invoke any public method from any thread (including main - no network ever runs
 * on the caller). Inbound messages are decoded on a dedicated reader thread and dispatched to the
 * [Listener] *on that reader thread* - listeners must not block and must post to main
 * themselves if they touch UI.
 *
 * **Lifecycle.** Exactly one terminal callback fires per channel: `onError` (socket
 * died, heartbeat timeout, handshake failure) or `onClosed` (local [close]). All
 * dispatch paths are guarded so close-during-callback races collapse into that single terminal
 * event; late messages after the terminal state are dropped silently.
 */
class CastV2Channel(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
    private val authenticator: Authenticator = Authenticator { socket, input, output ->
        CastDeviceAuth.authenticate(socket, input, output)
    },
) {

    /** DeviceAuth over the provisional TLS socket, before any application traffic. */
    fun interface Authenticator {
        fun authenticate(socket: SSLSocket, input: InputStream, output: OutputStream)
    }

    interface Listener {
        /** TLS is up and the reader/heartbeat are running (writer thread). Send CONNECT etc. from here. */
        fun onOpened()

        /** A non-heartbeat message arrived (reader thread - do not block, post to main for UI). */
        fun onMessage(message: CastMessage)

        /** The channel died: connect/handshake failure, socket error, heartbeat timeout. Terminal. */
        fun onError(reason: String)

        /** Local [close] finished. Terminal; not fired after [onError]. */
        fun onClosed()
    }

    @Volatile
    private var diagnostics = CastDiagnosticTrace()

    fun setDiagnostics(diagnostics: CastDiagnosticTrace) {
        this.diagnostics = diagnostics
    }

    /**
     * Single thread for connect + all writes: gives us free write synchronization AND guarantees
     * ordering (a send queued right after [open] runs strictly after the handshake).
     */
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CastV2Writer").apply { isDaemon = true }
    }

    /** Destinations that received CONNECT; CLOSE goes to each of them on teardown. */
    private val connectedDestinations = ConcurrentHashMap.newKeySet<String>()

    /** Terminal-state latch: set by exactly one of [fail]/[doClose]; gates all callbacks + sends. */
    private val terminated = AtomicBoolean()

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var reader: Thread? = null

    /** Writer thread only. */
    private var heartbeat: ScheduledFuture<*>? = null

    @Volatile
    private var opened = false

    /** Last time ANY frame arrived; heartbeat PONGs and app messages both count as liveness. */
    @Volatile
    private var lastInboundMs = 0L

    /** TLS connected and not yet terminated. */
    fun isOpen(): Boolean = opened && !terminated.get()

    // ---------------------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------------------

    /** Connect asynchronously; `onOpened` or `onError` follows. Call once per instance. */
    fun open() {
        execute(::doOpen)
    }

    private fun doOpen() {
        if (terminated.get()) {
            return
        }
        try {
            diagnostics.phase("channel_connect_started")
            val socket = provisionalSocketFactory().createSocket() as SSLSocket
            this.socket = socket
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            diagnostics.phase("channel_tls_started")
            socket.startHandshake()
            this.socket = socket
            val inStream = BufferedInputStream(socket.inputStream)
            val outStream = BufferedOutputStream(socket.outputStream)
            input = inStream
            output = outStream
            diagnostics.phase("device_auth_started")
            authenticator.authenticate(socket, inStream, outStream)
            diagnostics.phase("device_auth_succeeded")
            socket.soTimeout = 0
            lastInboundMs = SystemClock.elapsedRealtime()
            opened = true

            val reader = Thread(::readLoop, "CastV2Reader").apply { isDaemon = true }
            this.reader = reader
            reader.start()

            heartbeat = writer.scheduleWithFixedDelay(
                { heartbeatTick() },
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )

            diagnostics.phase("channel_opened")
            if (!terminated.get()) {
                dispatch(listener::onOpened)
            }
        } catch (e: Exception) {
            diagnostics.error("channel_connect_or_auth_failed", e)
            fail("Connect to $host:$port failed: $e")
        }
    }

    /** Provisional TLS only. DeviceAuth binds this certificate before onOpened or media writes. */
    private fun provisionalSocketFactory(): SSLSocketFactory {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Authentication occurs over this socket before any application traffic.
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Authentication occurs over this socket before any application traffic.
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }.socketFactory
    }

    // ---------------------------------------------------------------------------------
    // Send
    // ---------------------------------------------------------------------------------

    /**
     * Open a virtual connection to a destination. Required once for [RECEIVER_ID] before any
     * receiver command and once per launched app transportId before media traffic. Idempotent.
     */
    fun connect(destinationId: String?) {
        if (destinationId != null && connectedDestinations.add(destinationId)) {
            send(SENDER_ID, destinationId, NS_CONNECTION, "{\"type\":\"CONNECT\"}")
        }
    }

    /** Queue one message. Safe from any thread; silently dropped once the channel is terminated. */
    fun send(sourceId: String, destinationId: String, namespace: String, jsonPayload: String) {
        execute { doSend(sourceId, destinationId, namespace, jsonPayload) }
    }

    /** Writer thread only. */
    private fun doSend(sourceId: String, destinationId: String, namespace: String, jsonPayload: String) {
        val out = output
        if (terminated.get() || out == null) {
            return
        }
        try {
            CastMessageCodec.writeFramed(out, CastMessage.utf8(sourceId, destinationId, namespace, jsonPayload))
            out.flush()
        } catch (e: Exception) {
            diagnostics.error("channel_send_failed", e)
            fail("Send failed: $e")
        }
    }

    // ---------------------------------------------------------------------------------
    // Reader + heartbeat
    // ---------------------------------------------------------------------------------

    private fun readLoop() {
        try {
            val inStream = input
            while (inStream != null && !terminated.get()) {
                val message = CastMessageCodec.readFramed(inStream)
                if (message == null) {
                    fail("Receiver closed the connection")
                    return
                }
                lastInboundMs = SystemClock.elapsedRealtime()
                if (NS_HEARTBEAT == message.namespace) {
                    handleHeartbeat(message)
                    continue
                }
                dispatch { listener.onMessage(message) }
            }
        } catch (e: Exception) {
            // Socket teardown from close() lands here as an exception - only report real deaths.
            if (!terminated.get()) {
                diagnostics.error("channel_read_failed", e)
                fail("Read failed: $e")
            }
        }
    }

    /** Reply to an inbound PING with PONG. Some receivers probe the sender. An inbound PONG only counts as traffic. */
    private fun handleHeartbeat(message: CastMessage) {
        try {
            val payload = JSONObject(message.payloadUtf8 ?: "{}")
            if ("PING" == payload.optString("type")) {
                send(SENDER_ID, message.sourceId, NS_HEARTBEAT, "{\"type\":\"PONG\"}")
            }
        } catch (e: Exception) {
            diagnostics.error("heartbeat_malformed", e)
        }
    }

    /** Writer thread, every 5s: liveness check first, then an outbound PING. */
    private fun heartbeatTick() {
        if (terminated.get()) {
            return
        }
        if (SystemClock.elapsedRealtime() - lastInboundMs > HEARTBEAT_TIMEOUT_MS) {
            diagnostics.error("heartbeat_timeout", null)
            fail("Heartbeat timeout (no traffic for ${HEARTBEAT_TIMEOUT_MS}ms)")
            return
        }
        doSend(SENDER_ID, RECEIVER_ID, NS_HEARTBEAT, "{\"type\":\"PING\"}")
    }

    // ---------------------------------------------------------------------------------
    // Teardown
    // ---------------------------------------------------------------------------------

    /**
     * Close locally: best-effort CLOSE to every connected destination, then kill the socket and
     * both threads. Idempotent; `onClosed` fires once (unless `onError` already did).
     */
    fun close() {
        execute(::doClose)
    }

    /** Writer thread only. */
    private fun doClose() {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        val out = output
        if (out != null) {
            for (destination in connectedDestinations) {
                try {
                    CastMessageCodec.writeFramed(
                        out,
                        CastMessage.utf8(SENDER_ID, destination, NS_CONNECTION, "{\"type\":\"CLOSE\"}"),
                    )
                } catch (e: Exception) {
                    break // socket already dead; the CLOSEs are courtesy only
                }
            }
            try {
                out.flush()
            } catch (e: Exception) {
                // ignore
            }
        }
        teardown()
        dispatch(listener::onClosed)
    }

    /** Any thread. Terminal error path; exactly-once with [doClose] via [terminated]. */
    private fun fail(reason: String) {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        diagnostics.error("channel_failed", null)
        dispatch { listener.onError(reason) }
        teardown()
    }

    private fun teardown() {
        heartbeat?.cancel(false)
        socket?.let {
            try {
                it.close() // also unblocks the reader's readFramed()
            } catch (e: Exception) {
                // ignore
            }
        }
        val readerThread = reader
        if (readerThread != null && readerThread != Thread.currentThread()) {
            readerThread.interrupt()
        }
        writer.shutdown() // lets an in-flight task (possibly this one) finish, accepts no more
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /** Queue on the writer. Rejection after shutdown means the channel has already terminated. */
    private fun execute(task: () -> Unit) {
        try {
            writer.execute { task() }
        } catch (e: RejectedExecutionException) {
            // already terminated
        }
    }

    /** Listener exceptions must never kill the reader/writer threads. */
    private fun dispatch(callback: () -> Unit) {
        try {
            callback()
        } catch (e: Exception) {
            diagnostics.error("channel_listener_failed", e)
        }
    }

    companion object {
        /** Sender id on every outbound message; receivers address replies to it. */
        const val SENDER_ID = "sender-0"

        /** The platform receiver - the always-on destination for receiver-namespace commands. */
        const val RECEIVER_ID = "receiver-0"

        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"

        /**
         * Cast receivers answer within one LAN round trip. A longer timeout only delays the picker
         * for an unreachable address.
         */
        private const val CONNECT_TIMEOUT_MS = 7_000

        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** No inbound traffic for this long = the receiver is gone (3 missed heartbeat rounds). */
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L
    }
}
