// Adapted from NewTube (MIT), 5d0f3ff71ae5f48640b617212c97269a6bb0bbfc.
// Copyright (c) 2020-present yuliskov. See assets/licenses/newtube-MIT.txt.
package com.hhst.youtubelite.cast.protocol

import android.os.SystemClock
import android.net.Network
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
 * One CASTV2 socket per receiver: TLS to port 8009, length-prefixed frames, virtual
 * connections (`CONNECT`/`CLOSE`) and the PING/PONG heartbeat; app-level logic lives in
 * [CastV2Session].
 *
 * Threading: sends serialize on one writer executor (any-thread safe); inbound messages
 * dispatch on the reader thread — listeners must not block and must post to main for UI.
 *
 * Lifecycle: exactly one terminal callback per channel (`onError` or `onClosed`);
 * messages arriving after it are dropped silently.
 */
class CastV2Channel(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
    private val authenticator: Authenticator = Authenticator { socket, input, output ->
        CastDeviceAuth.authenticate(socket, input, output)
    },
    private val network: Network? = null,
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

    /** Single writer thread gives free write ordering: a send queued after [open] runs after the handshake. */
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CastV2Writer").apply { isDaemon = true }
    }

    /** A blocked write must not also block the deadline that aborts its socket. */
    private val closeWorker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CastV2Close").apply { isDaemon = true }
    }
    private val transportLock = Any()
    private val callbackLock = Any()
    private val localCloseFinished = AtomicBoolean()

    @Volatile
    private var closeDeadline: ScheduledFuture<*>? = null

    private val connectedDestinations = ConcurrentHashMap.newKeySet<String>()

    /** Terminal-state latch: set by exactly one of [fail]/[close]; gates callbacks + sends. */
    private val terminated = AtomicBoolean()

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var input: InputStream? = null

    @Volatile
    private var output: OutputStream? = null

    @Volatile
    private var reader: Thread? = null

    @Volatile
    private var heartbeat: ScheduledFuture<*>? = null

    @Volatile
    private var opened = false

    /** Last time ANY frame arrived; heartbeat PONGs and app messages both count as liveness. */
    @Volatile
    private var lastInboundMs = 0L

    fun isOpen(): Boolean = opened && !terminated.get()

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
            val accepted = synchronized(transportLock) {
                if (terminated.get()) false else {
                    this.socket = socket
                    true
                }
            }
            if (!accepted) {
                socket.close()
                return
            }
            network?.bindSocket(socket)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            diagnostics.phase("channel_tls_started")
            socket.startHandshake()
            val inStream = BufferedInputStream(socket.inputStream)
            val outStream = BufferedOutputStream(socket.outputStream)
            synchronized(transportLock) {
                if (terminated.get()) return
                input = inStream
                output = outStream
            }
            diagnostics.phase("device_auth_started")
            authenticator.authenticate(socket, inStream, outStream)
            diagnostics.phase("device_auth_succeeded")
            synchronized(transportLock) {
                // Auth can finish after a local close aborted the transport;
                // never restart its reader or heartbeat.
                if (terminated.get()) return
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
            }

            diagnostics.phase("channel_opened")
            dispatch(callback = listener::onOpened)
        } catch (e: Exception) {
            diagnostics.error("channel_connect_or_auth_failed", e)
            fail("Connect to $host:$port failed: $e")
        }
    }

    /** Provisional TLS only. DeviceAuth binds this certificate before onOpened or media writes. */
    private fun provisionalSocketFactory(): SSLSocketFactory {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }.socketFactory
    }

    /** Open a virtual connection to a destination: required once for [RECEIVER_ID] before any
     *  receiver command and once per launched app transportId before media traffic. Idempotent. */
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

    private fun readLoop() {
        try {
            val inStream = input
            while (inStream != null && !terminated.get()) {
                val message = CastMessageCodec.readFramed(inStream)
                if (message == null) {
                    fail("Receiver closed the connection")
                    return
                }
                if (terminated.get()) return
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

    /** PONG replies because some receivers probe the sender; an inbound PONG only counts as traffic. */
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

    /**
     * Close locally: best-effort CLOSE to connected destinations, then kill socket and threads;
     * a separate deadline aborts a blocked write (including the CLOSE itself). Idempotent;
     * `onClosed` fires once unless `onError` already did.
     */
    fun close() {
        if (!terminated.compareAndSet(false, true)) return
        closeDeadline = closeWorker.schedule({ finishLocalClose() }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
        execute(::doClose)
    }

    /** Writer thread only. */
    private fun doClose() {
        if (localCloseFinished.get()) return
        val out = output
        if (out != null) {
            for (destination in connectedDestinations) {
                if (localCloseFinished.get()) break
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
        finishLocalClose()
    }

    /** Writer or deadline thread; only one finishes the local terminal event. */
    private fun finishLocalClose() {
        if (!localCloseFinished.compareAndSet(false, true)) return
        teardown()
        dispatch(terminal = true, callback = listener::onClosed)
    }

    /** Any thread. Terminal error path; exactly-once with [close] via [terminated]. */
    private fun fail(reason: String) {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        diagnostics.error("channel_failed", null)
        dispatch(terminal = true) { listener.onError(reason) }
        teardown()
    }

    private fun teardown() {
        val (transport, readerThread) = synchronized(transportLock) {
            opened = false
            heartbeat?.cancel(false)
            heartbeat = null
            val current = socket to reader
            socket = null
            reader = null
            input = null
            output = null
            current
        }
        transport?.let {
            try {
                it.close() // also unblocks the reader's readFramed()
            } catch (e: Exception) {
                // ignore
            }
        }
        if (readerThread != null && readerThread != Thread.currentThread()) {
            readerThread.interrupt()
        }
        writer.shutdown() // lets an in-flight task (possibly this one) finish, accepts no more
        closeDeadline?.cancel(false)
        closeWorker.shutdown()
    }

    /** Queue on the writer. Rejection after shutdown means the channel has already terminated. */
    private fun execute(task: () -> Unit) {
        try {
            writer.execute { task() }
        } catch (e: RejectedExecutionException) {
        }
    }

    /** Serialize terminal delivery after any active callback; drop late active callbacks. */
    private fun dispatch(terminal: Boolean = false, callback: () -> Unit) {
        synchronized(callbackLock) {
            if (!terminal && terminated.get()) return
            try {
                callback()
            } catch (e: Exception) {
                diagnostics.error("channel_listener_failed", e)
            }
        }
    }

    companion object {
        /** Sender id on every outbound message; receivers address replies to it. */
        const val SENDER_ID = "sender-0"

        /** The platform receiver - the always-on destination for receiver-namespace commands. */
        const val RECEIVER_ID = "receiver-0"

        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"

        /** Cast receivers answer within one LAN round trip; a longer timeout only delays the picker for an unreachable address. */
        private const val CONNECT_TIMEOUT_MS = 7_000

        /** Allow a courtesy frame on a healthy LAN without waiting behind a blocked writer. */
        private const val CLOSE_GRACE_MS = 500L

        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** No inbound traffic for this long = the receiver is gone (3 missed heartbeat rounds). */
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L
    }
}
