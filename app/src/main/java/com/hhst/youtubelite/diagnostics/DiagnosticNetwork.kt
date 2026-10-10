package com.hhst.youtubelite.diagnostics

import android.os.SystemClock
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** OkHttp phases plus actual consumer reads, without headers, URLs or response payloads. */
object DiagnosticNetwork {
    data class Policy(val acceptedStatuses: Set<Int> = emptySet())
    private val active = ConcurrentHashMap<Call, Trace>()
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .eventListenerFactory { call -> Trace(call).also { active[call] = it }.listener }
        .addInterceptor(Interceptor { chain ->
            val trace = active[chain.call()] ?: return@Interceptor chain.proceed(chain.request())
            val response = try { chain.proceed(chain.request()) } catch (failure: IOException) {
                trace.finish("FAILURE", failure); throw failure
            }
            val body = response.body ?: run { trace.finish("SUCCESS"); return@Interceptor response }
            val wrapped = object : ResponseBody() {
                private val stream = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        trace.beginRead(body.contentLength())
                        val count = try { super.read(sink, byteCount) } catch (failure: IOException) {
                            trace.finish(if (chain.call().isCanceled()) "CANCELLED" else "FAILURE", failure)
                            throw failure
                        } finally { trace.endRead() }
                        trace.read(count)
                        return count
                    }
                    override fun close() {
                        try { super.close() } finally {
                            trace.finish(if (trace.complete || body.contentLength() >= 0 && trace.bytes >= body.contentLength()) "SUCCESS" else "CANCELLED")
                        }
                    }
                }.buffer()
                override fun source() = stream
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
            }
            response.newBuilder().body(wrapped).build()
        })

    fun pending(context: DiagnosticContext?): List<Map<String, Any?>> = active.values
        .filter { context == null || it.context.traceId == context.traceId }.take(32).map { it.state() }
    fun tag(request: Request, context: DiagnosticContext) =
        request.newBuilder().tag(DiagnosticContext::class.java, context).build()
    fun sample() { active.values.forEach { it.sample() } }

    private class Trace(private val call: Call) {
        val context = (call.request().tag(DiagnosticContext::class.java) ?: DiagnosticContext())
            .child(UUID.randomUUID().toString())
        private val started = SystemClock.elapsedRealtime()
        private val finished = AtomicBoolean()
        private val resource = DiagnosticRedaction.resource(call.request().url.toString())
        @Volatile var bytes = 0L; private set
        @Volatile var complete = false; private set
        @Volatile private var phase = "created"
        @Volatile private var lastProgress = started
        @Volatile private var lastSample = started
        @Volatile private var status: Int? = null
        @Volatile private var expected: Long? = null
        @Volatile private var responseFields = emptyMap<String, Any?>()
        private var connectAt: Long? = null
        private var dnsAt: Long? = null
        private var tlsAt: Long? = null
        private var requestSentAt: Long? = null
        private var headersAt: Long? = null
        @Volatile private var consumerReading = false
        private val watchdog = DiagnosticProgressWatchdog(30_000, { state, duration ->
            AppLog.event(AppLog.Category.DOWNLOADER, "transfer_progress.$state", state() + mapOf("reason" to "no_byte_progress", "duration_ms" to duration),
                critical = state == "stalled", context = context)
        })
        fun beginRead(length: Long) {
            consumerReading = true
            expected = length.takeIf { it >= 0 }
        }
        fun endRead() { consumerReading = false }
        fun sample() {
            if (!finished.get()) AppLog.snapshot(AppLog.Category.NETWORK, context, state())
            // A pending consumer read is actual transfer work, including LAN/offline fixtures.
            // Business network waits have no pending read; cancellation closes the active call.
            if (context.taskId != null) watchdog.sample(SystemClock.elapsedRealtime(), bytes, consumerReading && !finished.get() && !call.isCanceled())
        }
        fun state(): Map<String, Any?> = resource + responseFields + mapOf("request_id" to context.requestId,
            "operation_id" to context.operationId, "phase" to phase, "http_status" to status,
            "bytes_read" to bytes, "expected_bytes" to expected, "last_progress_ms" to lastProgress.takeIf { bytes > 0 },
            "duration_ms" to (SystemClock.elapsedRealtime() - started))
        private fun phase(name: String, fields: Map<String, Any?> = emptyMap()) {
            phase = name
            AppLog.detail(AppLog.Category.NETWORK, "request.$name", state() + fields, context)
        }
        fun read(count: Long) {
            if (count < 0) { complete = true; finish("SUCCESS"); return }
            if (count == 0L) return
            val first = bytes == 0L
            bytes += count; lastProgress = SystemClock.elapsedRealtime()
            if (first) {
                val delay = headersAt?.let { lastProgress - it }
                phase("first_bytes", mapOf("first_byte_delay_ms" to delay))
                if (delay != null && delay >= 3_000) AppLog.event(AppLog.Category.NETWORK, "request.slow_first_byte",
                    state() + mapOf("first_byte_delay_ms" to delay), context = context, level = DiagnosticLevel.WARN)
            }
            if (lastProgress - lastSample >= 5_000) {
                lastSample = lastProgress
                AppLog.snapshot(AppLog.Category.NETWORK, context, state())
            }
        }
        fun finish(outcome: String, failure: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) return
            val fields = state() + mapOf("outcome" to outcome, "cancelled" to call.isCanceled())
            if (outcome == "FAILURE" && !call.isCanceled() || (status ?: 0) >= 400 && status !in call.request().tag(Policy::class.java)?.acceptedStatuses.orEmpty())
                AppLog.event(AppLog.Category.NETWORK, "request.failed", fields, failure, true, context)
            else AppLog.detail(AppLog.Category.NETWORK, "request.end", fields, context)
            active.remove(call)
            watchdog.reset(if (outcome == "SUCCESS") "completed" else "cancelled")
            AppLog.endContext(context)
        }
        val listener = @Suppress("UNUSED_PARAMETER") object : EventListener() {
            override fun callStart(call: Call) {
                if (active.size > 256) active.entries.firstOrNull { it.key != call }?.let { active.remove(it.key) }
                phase("start", mapOf("method" to call.request().method,
                    "range" to call.request().header("Range")?.takeIf { it.matches(Regex("bytes=\\d+-\\d*")) }))
            }
            override fun dnsStart(call: Call, domainName: String) { dnsAt = SystemClock.elapsedRealtime(); phase("dns_start") }
            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) = phase("dns_end",
                mapOf("address_count" to inetAddressList.size, "dns_ms" to dnsAt?.let { SystemClock.elapsedRealtime() - it }))
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                connectAt = SystemClock.elapsedRealtime(); phase("connect_start", mapOf("proxy_type" to proxy.type().name))
            }
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
                val duration = connectAt?.let { SystemClock.elapsedRealtime() - it }
                phase("connect_end", mapOf("protocol" to protocol?.toString(), "connect_ms" to duration))
                if (duration != null && duration >= 3_000) AppLog.event(AppLog.Category.NETWORK, "request.slow_connect",
                    state() + mapOf("connect_ms" to duration), context = context, level = DiagnosticLevel.WARN)
            }
            override fun secureConnectStart(call: Call) { tlsAt = SystemClock.elapsedRealtime(); phase("tls_start") }
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = phase("tls_end",
                mapOf("tls_version" to handshake?.tlsVersion?.javaName, "tls_ms" to tlsAt?.let { SystemClock.elapsedRealtime() - it }))
            override fun requestHeadersEnd(call: Call, request: Request) { requestSentAt = SystemClock.elapsedRealtime(); phase("request_sent") }
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
                AppLog.event(AppLog.Category.NETWORK, "request.connection_attempt_failed", state() +
                    mapOf("proxy_type" to proxy.type().name, "connect_ms" to connectAt?.let { SystemClock.elapsedRealtime() - it }),
                    ioe, context = context, level = DiagnosticLevel.WARN)
            }
            override fun responseHeadersEnd(call: Call, response: Response) {
                status = response.code
                headersAt = SystemClock.elapsedRealtime()
                expected = response.body?.contentLength()?.takeIf { it >= 0 }
                responseFields = mapOf("content_type" to response.header("Content-Type")?.substringBefore(';')?.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) },
                    "content_range" to response.header("Content-Range")?.takeIf { it.matches(Regex("bytes (\\d+-\\d+/[\\d*]+|\\*/\\d+)")) })
                val wait = requestSentAt?.let { SystemClock.elapsedRealtime() - it }
                phase("headers", mapOf("headers_wait_ms" to wait))
                if (wait != null && wait >= 3_000)
                    AppLog.event(AppLog.Category.NETWORK, "request.slow_headers", state(), context = context, level = DiagnosticLevel.WARN)
            }
            override fun responseBodyStart(call: Call) = phase("body_start")
            override fun responseBodyEnd(call: Call, byteCount: Long) = phase("body_transport_end", mapOf("transport_bytes" to byteCount))
            override fun callFailed(call: Call, ioe: IOException) = finish(if (call.isCanceled()) "CANCELLED" else "FAILURE", ioe)
            override fun callEnd(call: Call) { if (call.request().method == "HEAD" || status in setOf(204, 304)) finish("SUCCESS") }
        }
    }
}

