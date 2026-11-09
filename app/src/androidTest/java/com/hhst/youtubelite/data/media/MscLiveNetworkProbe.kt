package com.hhst.youtubelite.data.media

import android.os.SystemClock
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in read-only live-network measurements, anonymous unless explicitly enabled. One
 * active sample per probe. The supplied base keeps its pool, dispatcher, timeouts and
 * diagnostics, but HTTP caching is disabled so a logical call cannot masquerade as a
 * round trip. Reports contain endpoint categories, counts and timings only — never
 * URLs, headers, credentials, visitor/account IDs or exception messages.
 */
class MscLiveNetworkProbe(
    base: OkHttpClient,
    proxyHost: String? = null,
    proxyPort: Int = 0,
    allowSignedIn: Boolean = false,
    context: () -> RequestContext = { RequestContext() },
) {
    private class Sample(val label: String) {
        val started = now()
        var ended = started
        var firstWorker: Long? = null
        var contextNs = 0L
        var contextReads = 0
        var outcome = "success"
        var failureType: String? = null
        var failureKind: String? = null
        var failureCode: String? = null
        val profiles = linkedSetOf<String>()
        val calls = ArrayList<Trace>()
    }

    private class Exchange(val endpoint: String, val method: String) {
        val started = now()
        var sent = started
        var headers: Long? = null
        var ended: Long? = null
        var status: Int? = null
        var requestBytes = 0L
        var responseBytes = 0L
    }

    private class Trace(val endpoint: String, val method: String, val sample: Sample?) {
        val started = now()
        var ended: Long? = null
        var dnsAt: Long? = null
        var dnsNs = 0L
        var connectAt: Long? = null
        var connectNs = 0L
        var tlsAt: Long? = null
        var tlsNs = 0L
        var connectAttempts = 0
        var protocol: String? = null
        var proxyType: String? = null
        var failureType: String? = null
        var cancelled = false
        val exchanges = ArrayList<Exchange>()
        fun current() = exchanges.lastOrNull()
        inline fun edit(block: Trace.() -> Unit) { synchronized(this) { block() } }
    }

    private val active = AtomicReference<Sample?>()
    private val completed = ArrayList<Sample>()
    private val route = if (proxyHost == null) "direct" else "http_proxy"

    val http: OkHttpClient = base.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).apply {
        if (proxyHost == null) proxy(Proxy.NO_PROXY)
        else {
            require(proxyPort in 1..65535)
            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
        }
        // Preserve the production listener, including DiagnosticNetwork's body trace.
        val previous = base.eventListenerFactory
        eventListenerFactory { call ->
            val trace = Trace(endpoint(call.request().url), call.request().method, active.get())
            trace.sample?.let { synchronized(it) { it.calls.add(trace) } }
            Listener(trace, previous.create(call))
        }
        addInterceptor { chain ->
            val request = chain.request()
            check(readAllowed(request)) { "live_probe_read_endpoint_required" }
            val authorization = request.header("Authorization")
            check((authorization == null || allowSignedIn && authorization.startsWith("Bearer ")) && anonymousCookies(request.header("Cookie"))) {
                "live_probe_anonymous_identity_required"
            }
            chain.proceed(request)
        }
    }.build()

    val client = MediaClient(http) {
        val sample = active.get()
        val start = now()
        sample?.let { synchronized(it) {
            if (it.firstWorker == null) it.firstWorker = start
            it.contextReads++
        } }
        try {
            context().also {
                check(it.webSession == null && anonymousCookies(it.visitorCookie) && (allowSignedIn ||
                    !it.signedIn && it.accountId == null && it.accessToken == null && it.onBehalfOfUser == null)) {
                    "live_probe_anonymous_identity_required"
                }
                sample?.let { sample -> synchronized(sample) { sample.profiles.add(if (it.signedIn) "oauth" else "anonymous") } }
            }
        } finally { sample?.let { synchronized(it) { it.contextNs += now() - start } } }
    }

    /** Return or throw exactly what [block] does; even a failed/cancelled sample is retained. */
    suspend fun <T> sample(label: String, block: suspend () -> T): T {
        require(label.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        val sample = Sample(label)
        check(active.compareAndSet(null, sample)) { "live_probe_samples_must_not_overlap" }
        try {
            return block()
        } catch (failure: Throwable) {
            sample.outcome = if (failure is CancellationException) "cancelled" else "failure"
            sample.failureType = failure.javaClass.simpleName
            if (failure is MediaException) {
                sample.failureKind = failure.failure.kind.name
                sample.failureCode = failure.failure.code.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) }
            }
            throw failure
        } finally {
            sample.ended = now()
            active.compareAndSet(sample, null)
            synchronized(completed) { completed.add(sample) }
        }
    }

    fun report(): JSONObject = JSONObject().put("route", route).put("httpCacheEnabled", false)
        .put("workerTimingScope", "sample_start_to_first_context_entry_includes_admission_and_queue")
        .put("samples", JSONArray(synchronized(completed) { completed.toList() }.map(::sampleJson)))

    private fun sampleJson(sample: Sample): JSONObject = synchronized(sample) {
        val calls = sample.calls.map { trace -> synchronized(trace) {
            JSONObject().put("endpoint", trace.endpoint).put("method", trace.method)
                .put("elapsedMs", ms((trace.ended ?: sample.ended) - trace.started))
                .put("dnsMs", ms(trace.dnsNs)).put("connectMs", ms(trace.connectNs)).put("tlsMs", ms(trace.tlsNs))
                .put("connectAttempts", trace.connectAttempts).put("connectionReused", trace.connectAttempts == 0 && trace.protocol != null)
                .put("protocol", trace.protocol ?: JSONObject.NULL).put("proxyType", trace.proxyType ?: JSONObject.NULL)
                .put("cancelled", trace.cancelled).put("failureType", trace.failureType ?: JSONObject.NULL)
                .put("networkExchanges", trace.exchanges.size)
        } }
        val exchanges = sample.calls.flatMapIndexed { index, trace -> synchronized(trace) {
            trace.exchanges.map { exchange -> JSONObject().put("logicalCall", index)
                .put("endpoint", exchange.endpoint).put("method", exchange.method)
                .put("status", exchange.status ?: JSONObject.NULL)
                .put("elapsedMs", ms((exchange.ended ?: trace.ended ?: sample.ended) - exchange.started))
                .put("headersMs", exchange.headers?.let { ms(it - exchange.sent) } ?: JSONObject.NULL)
                .put("bodyMs", exchange.headers?.let { ms((exchange.ended ?: trace.ended ?: sample.ended) - it) } ?: JSONObject.NULL)
                .put("requestBytes", exchange.requestBytes).put("responseBytes", exchange.responseBytes)
            }
        } }
        JSONObject().put("label", sample.label).put("outcome", sample.outcome)
            .put("wallMs", ms(sample.ended - sample.started))
            .put("workerAdmissionMs", sample.firstWorker?.let { ms(it - sample.started) } ?: JSONObject.NULL)
            .put("contextMs", ms(sample.contextNs)).put("contextReads", sample.contextReads)
            .put("profiles", JSONArray(sample.profiles.toList()))
            .put("logicalCalls", calls.size).put("networkExchanges", exchanges.size)
            .put("responseBytes", exchanges.sumOf { it.getLong("responseBytes") })
            .put("failureType", sample.failureType ?: JSONObject.NULL).put("failureKind", sample.failureKind ?: JSONObject.NULL)
            .put("failureCode", sample.failureCode ?: JSONObject.NULL)
            .put("calls", JSONArray(calls)).put("http", JSONArray(exchanges))
    }

    private class Listener(val trace: Trace, val parent: EventListener) : EventListener() {
        override fun callStart(call: Call) = parent.callStart(call)
        override fun proxySelectStart(call: Call, url: HttpUrl) = parent.proxySelectStart(call, url)
        override fun proxySelectEnd(call: Call, url: HttpUrl, proxies: List<Proxy>) = parent.proxySelectEnd(call, url, proxies)
        override fun dnsStart(call: Call, domainName: String) {
            trace.edit { dnsAt = now() }; parent.dnsStart(call, domainName)
        }
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            trace.edit { dnsAt?.let { dnsNs += now() - it }; dnsAt = null }; parent.dnsEnd(call, domainName, inetAddressList)
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            trace.edit { connectAt = now(); connectAttempts++; proxyType = proxy.type().name }; parent.connectStart(call, inetSocketAddress, proxy)
        }
        override fun secureConnectStart(call: Call) {
            trace.edit { tlsAt = now() }; parent.secureConnectStart(call)
        }
        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            trace.edit { tlsAt?.let { tlsNs += now() - it }; tlsAt = null }; parent.secureConnectEnd(call, handshake)
        }
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            trace.edit { connectAt?.let { connectNs += now() - it }; connectAt = null; this.protocol = protocol?.toString() }
            parent.connectEnd(call, inetSocketAddress, proxy, protocol)
        }
        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            trace.edit { connectAt?.let { connectNs += now() - it }; connectAt = null; failureType = ioe.javaClass.simpleName }
            parent.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        }
        override fun connectionAcquired(call: Call, connection: Connection) {
            trace.edit { protocol = connection.protocol().toString(); proxyType = connection.route().proxy.type().name }
            parent.connectionAcquired(call, connection)
        }
        override fun connectionReleased(call: Call, connection: Connection) = parent.connectionReleased(call, connection)
        override fun requestHeadersStart(call: Call) {
            trace.edit { exchanges.add(Exchange(endpoint(call.request().url), call.request().method)) }; parent.requestHeadersStart(call)
        }
        override fun requestHeadersEnd(call: Call, request: Request) {
            trace.edit { current()?.sent = now() }; parent.requestHeadersEnd(call, request)
        }
        override fun requestBodyStart(call: Call) = parent.requestBodyStart(call)
        override fun requestBodyEnd(call: Call, byteCount: Long) {
            trace.edit { current()?.let { it.sent = now(); it.requestBytes = byteCount } }; parent.requestBodyEnd(call, byteCount)
        }
        override fun requestFailed(call: Call, ioe: IOException) {
            trace.edit { failureType = ioe.javaClass.simpleName; current()?.ended = now() }; parent.requestFailed(call, ioe)
        }
        override fun responseHeadersStart(call: Call) = parent.responseHeadersStart(call)
        override fun responseHeadersEnd(call: Call, response: Response) {
            trace.edit { current()?.let { it.headers = now(); it.status = response.code } }; parent.responseHeadersEnd(call, response)
        }
        override fun responseBodyStart(call: Call) = parent.responseBodyStart(call)
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            trace.edit { current()?.let { it.ended = now(); it.responseBytes = byteCount } }; parent.responseBodyEnd(call, byteCount)
        }
        override fun responseFailed(call: Call, ioe: IOException) {
            trace.edit { failureType = ioe.javaClass.simpleName; current()?.ended = now() }; parent.responseFailed(call, ioe)
        }
        override fun callEnd(call: Call) { trace.edit { ended = now() }; parent.callEnd(call) }
        override fun callFailed(call: Call, ioe: IOException) {
            trace.edit { ended = now(); failureType = ioe.javaClass.simpleName; cancelled = call.isCanceled() }; parent.callFailed(call, ioe)
        }
        override fun canceled(call: Call) { trace.edit { cancelled = true }; parent.canceled(call) }
        override fun satisfactionFailure(call: Call, response: Response) = parent.satisfactionFailure(call, response)
        override fun cacheHit(call: Call, response: Response) = parent.cacheHit(call, response)
        override fun cacheMiss(call: Call) = parent.cacheMiss(call)
        override fun cacheConditionalHit(call: Call, cachedResponse: Response) = parent.cacheConditionalHit(call, cachedResponse)
    }

    private companion object {
        fun now() = SystemClock.elapsedRealtimeNanos()
        fun ms(nanos: Long) = nanos.coerceAtLeast(0) / 1_000_000.0
        val contentPosts = setOf("browse", "search", "next", "player", "guide", "get_transcript", "get_panel",
            "navigation/resolve_url", "live_chat/get_live_chat", "live_chat/get_live_chat_replay",
            "account/accounts_list", "notification/get_notification_menu")
        val visitorCookies = setOf("VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "YSC", "__Secure-ROLLOUT_TOKEN", "__Secure-YEC", "SOCS", "CONSENT")
        fun anonymousCookies(header: String?) = header.isNullOrBlank() || header.split(';').all {
            it.substringBefore('=').trim() in visitorCookies
        }
        fun youtubeHost(url: HttpUrl) = url.host in setOf("www.youtube.com", "m.youtube.com", "youtube.com")
        fun readAllowed(request: Request): Boolean {
            val url = request.url
            if (url.scheme != "https") return false
            val path = url.encodedPath
            return when (request.method) {
                "POST" -> youtubeHost(url) && path.removePrefix("/youtubei/v1/") in contentPosts && path.startsWith("/youtubei/v1/")
                "GET", "HEAD" -> youtubeHost(url) && (path in setOf("/", "/tv", "/watch") || path.startsWith("/s/player/") && path.endsWith("/base.js")) ||
                    (url.host == "i.ytimg.com" || url.host.endsWith(".ytimg.com"))
                else -> false
            }
        }
        fun endpoint(url: HttpUrl): String {
            val path = url.encodedPath
            return when {
                youtubeHost(url) && path.startsWith("/youtubei/v1/") -> path.removePrefix("/youtubei/v1/").takeIf { it in contentPosts } ?: "rejected_content_endpoint"
                youtubeHost(url) && path == "/tv" -> "tv_html"
                youtubeHost(url) && path == "/watch" -> "watch_html"
                youtubeHost(url) && path == "/" -> "home_html"
                youtubeHost(url) && path.startsWith("/s/player/") && path.endsWith("/base.js") -> "player_script"
                url.host == "i.ytimg.com" || url.host.endsWith(".ytimg.com") -> "thumbnail"
                else -> "rejected_endpoint"
            }
        }
    }
}
