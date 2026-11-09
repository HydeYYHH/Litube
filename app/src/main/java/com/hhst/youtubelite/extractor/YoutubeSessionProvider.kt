package com.hhst.youtubelite.extractor

import android.content.Context
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.net.URI
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.net.LinkProperties
import android.net.Proxy
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.webkit.CookieManager
import com.hhst.youtubelite.core.Constants
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Captures credentials once; consumers never merge a newer Cookie into an older response. */
class YoutubeSessionProvider(context: Context, private val http: OkHttpClient,
                             private val diagnostics: ExtractionDiagnostics? = null) {
    private val app = context.applicationContext
    private val networkGeneration = AtomicLong()
    private val nativeRecovery = AtomicLong()
    private val lock = Any()
    private val capturePermit = Semaphore(1, true)
    private val cancellation = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "youtube-session-cancellation").apply { isDaemon = true }
    }
    private var identity = ""
    @Volatile private var generation = 0L
    private var config = JsonObject(emptyMap())
    private var configAt = 0L
    private var pageIdentity = ""
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var route = ""
    private val routeLock = Any()
    private var configSource = "same-session-watch"
    private var browserConfig: BrowserConfig? = null
    private data class IdentityHint(val cookies: String, val userAgent: String, val route: Long, val value: String)
    @Volatile private var identityHint: IdentityHint? = null
    private val mediaCookies = MediaCookieIdentity()
    private data class BrowserConfig(val value: JsonObject, val scope: String, val generation: Long,
                                     val document: () -> Long, val capturedAt: Long)

    init {
        routeChanged()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = routeChanged()
            override fun onLost(network: Network) = routeChanged()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = routeChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = routeChanged()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivity.registerDefaultNetworkCallback(callback)
        } else {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        }
        ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { routeChanged() }
        }, IntentFilter(Proxy.PROXY_CHANGE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun routeChanged() = synchronized(routeLock) {
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val next = "$active:${caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}:${connectivity.defaultProxy}"
        if (route != next) {
            route = next
            networkGeneration.incrementAndGet()
        }
    }

    fun capture(videoId: String): YoutubeWebSession = captureBounded(videoId, false, Long.MAX_VALUE, { true })

    /** A known anonymous native identity can issue direct requests without a browser document. */
    internal fun captureNative(deadline: Long, current: () -> Boolean): YoutubeWebSession? {
        if (System.nanoTime() >= deadline || !current() || Thread.currentThread().isInterrupted)
            throw IOException("SESSION_CHANGED_OR_CANCELLED")
        val native = com.hhst.youtubelite.data.media.MediaSession.snapshot()
        if (native.signedIn || native.visitor.isNullOrBlank()) return null
        val domains = listOf(ORIGIN, "https://youtube.com", "https://m.youtube.com", "https://music.youtube.com")
            .associateWith { CookieManager.getInstance().getCookie(it).orEmpty() }
        if (domains.values.any { authCookies(it).isNotEmpty() }) return null
        val ua = Constants.userAgent()
        val capturedRoute = networkGeneration.get()
        val hint = scopeHint()
        synchronized(lock) {
            // Reuse an already prepared web revision so catalog and startup share MSC results.
            val now = System.currentTimeMillis()
            if (pageIdentity == hint && configAt != 0L && now - configAt < CONFIG_TTL_MS ||
                browserConfig?.let { it.scope == hint && it.generation == it.document() &&
                    now - it.capturedAt < 120_000 } == true) return null
            if (browserConfig?.takeIf { it.scope == hint }?.value?.flag("LOGGED_IN", false) == true ||
                pageIdentity == hint && config.flag("LOGGED_IN", false)) return null
        }
        val snapshot = YoutubeWebSession(digest("native:$hint:${nativeRecovery.get()}:${native.visitor}"), WebAccount.ANONYMOUS, 0, null, null, null,
            native.visitor, ua, capturedRoute, null, "native-session", JsonObject(emptyMap()),
            SessionCredentials { domains[it].orEmpty() })
        if (native != com.hhst.youtubelite.data.media.MediaSession.snapshot() || !current() ||
            ua != Constants.userAgent() || domains.any { (origin, cookie) ->
                CookieManager.getInstance().getCookie(origin).orEmpty() != cookie
            } || !isCurrent(snapshot)) throw IOException("SESSION_CHANGED_OR_CANCELLED")
        return snapshot
    }

    /** Invoked off the lock whenever a browser document supplies session configuration. */
    @Volatile var onBrowserConfiguration: (() -> Unit)? = null

    /** The configured session when no page read is needed; never touches the network. */
    fun captureCached(): YoutubeWebSession? = try {
        captureBounded("", false, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), { true }, allowPage = false)
    } catch (_: IOException) { null }

    fun captureBounded(videoId: String, fresh: Boolean, deadline: Long, current: () -> Boolean,
                       allowPage: Boolean = true): YoutubeWebSession = withPermit(deadline, current) {
        val cookies = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()
        val domainCookies = listOf(ORIGIN, "https://youtube.com", "https://m.youtube.com", "https://music.youtube.com")
            .associateWith { if (it == ORIGIN) cookies else CookieManager.getInstance().getCookie(it).orEmpty() }
        val ua = Constants.userAgent()
        val capturedRoute = networkGeneration.get()
        val nextIdentity = identityHint(cookies, ua, capturedRoute)
        var capturedGeneration: Long
        val fetchPage: Boolean
        var targetAccount: JsonObject? = null
        synchronized(lock) {
            if (nextIdentity != identity) { identity = nextIdentity; generation++; configAt = 0 }
            val browser = browserConfig?.takeIf { it.scope == identity && it.generation == it.document()
                && System.currentTimeMillis() - it.capturedAt < 120_000 }
            targetAccount = browser?.value ?: config.takeIf { pageIdentity == identity }
            if (!fresh && browser != null && (configAt == 0L || browser.capturedAt > configAt)) {
                config = browser.value; configAt = browser.capturedAt
                pageIdentity = identity; configSource = "browser-document"
            }
            if (fresh) { generation++; nativeRecovery.incrementAndGet(); configAt = 0 }
            // A cold process starts with no configuration; the persisted snapshot spares the
            // watch-page bootstrap within the same TTL window as the in-memory config.
            if (!fresh && configAt == 0L) restorePersisted(identity)
            capturedGeneration = generation
            // Innertube account/visitor/player configuration belongs to the session: a different
            // video needs a different /player request, not another page or minter. Browser identity
            // changes and explicit recovery still refresh it.
            fetchPage = fresh || configAt == 0L || System.currentTimeMillis() - configAt >= CONFIG_TTL_MS
        }
        if (fetchPage && !allowPage) throw IOException("SESSION_PAGE_REQUIRED")
        if (fetchPage) {
            val requested = targetAccount
            val index = requested?.let(::accountIndex) ?: 0
            val html = readPage("$ORIGIN/watch?v=$videoId&authuser=$index&app=desktop", ua, domainCookies, deadline) {
                current() && generation == capturedGeneration && networkGeneration.get() == capturedRoute && scopeHint() == nextIdentity
            }
            val parseStarted = System.nanoTime()
            var parsed = parseConfig(html)
            diagnostics?.event("page-parse", "WEB", "configuration", (System.nanoTime() - parseStarted) / 1_000_000, 200)
            if (requested?.flag("LOGGED_IN", false) == true && (!parsed.flag("LOGGED_IN", false)
                    || accountIndex(parsed) != index
                    || requested.text("DATASYNC_ID", "").let { it.isNotBlank() && it != parsed.text("DATASYNC_ID", "") })) {
                throw IOException("SESSION_ACCOUNT_PAGE_MISMATCH")
            }
            val js = parsed.text("PLAYER_JS_URL") ?: Regex("\"jsUrl\"\\s*:\\s*\"([^\"]+)\"")
                .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (js != null) parsed = JsonObject(parsed.toMap() +
                ("PLAYER_JS_URL" to JsonPrimitive(if (js.startsWith('/')) ORIGIN + js else js)))
            synchronized(lock) {
                if (generation != capturedGeneration || scopeHint() != nextIdentity) throw IOException("SESSION_CHANGED_OR_CANCELLED")
                if (configAt != 0L && configurationIdentity(config) != configurationIdentity(parsed)) {
                    generation++
                    capturedGeneration = generation
                }
                config = parsed; configAt = System.currentTimeMillis()
                pageIdentity = identity; configSource = "same-session-watch"
            }
            persistConfig(identity, parsed)
        }
        synchronized(lock) {
        if (pageIdentity != identity || generation != capturedGeneration || scopeHint() != nextIdentity) throw IOException("SESSION_PAGE_MISMATCH")
        val sync = config.text("DATASYNC_ID", "")
        val syncParts = sync.split("||")
        val secondary = syncParts.getOrNull(1)?.isNotBlank() == true
        val delegated = config.text("DELEGATED_SESSION_ID") ?: syncParts.firstOrNull()?.takeIf { secondary }
        val user = config.text("USER_SESSION_ID") ?: (if (secondary) syncParts[1] else syncParts.firstOrNull())?.takeIf { it.isNotBlank() }
        val visitor = config.text("VISITOR_DATA") ?: config.obj("INNERTUBE_CONTEXT").obj("client").text("visitorData")
        val loggedIn = config.flag("LOGGED_IN", cookies.contains("LOGIN_INFO=") || cookies.contains("SAPISID="))
        val account = if (!loggedIn) WebAccount.ANONYMOUS
            else if (config.flag("IS_PREMIUM", false)) WebAccount.PREMIUM
            else WebAccount.AUTHENTICATED
        val scope = digest("$identity:$generation:$visitor:$sync:${accountIndex(config)}")
        YoutubeWebSession(scope, account, accountIndex(config), delegated, user,
            sync.takeIf { it.isNotBlank() }, visitor, ua,
            capturedRoute, config.text("PLAYER_JS_URL"), if (fetchPage) configSource else "config-cache:$configSource", config,
            SessionCredentials { origin -> domainCookies[origin].orEmpty() })
        }
    }

    private fun restorePersisted(identity: String) {
        val entry = runCatching {
            appPreferenceStore().readString(PERSIST_KEY_PREFIX + identity)?.let { text ->
                org.json.JSONObject(text).let { stored ->
                    if (stored.optLong("until") <= System.currentTimeMillis()) null
                    else stored.getString("config")
                }
            }
        }.getOrNull() ?: return
        val restored = runCatching { parseJson(entry) as? JsonObject }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return
        config = restored
        configAt = System.currentTimeMillis()
        pageIdentity = identity
        configSource = "disk-cache"
    }

    private fun persistConfig(identity: String, parsed: JsonObject) {
        runCatching {
            val store = appPreferenceStore()
            store.keys().filter { it.startsWith(PERSIST_KEY_PREFIX) && it != PERSIST_KEY_PREFIX + identity }
                .forEach(store::remove)
            val encoded = parsed.toString()
            if (encoded.isNotEmpty() && encoded.length <= PERSIST_MAX_BYTES) {
                store.putString(PERSIST_KEY_PREFIX + identity, org.json.JSONObject()
                    .put("until", System.currentTimeMillis() + CONFIG_TTL_MS)
                    .put("config", encoded).toString())
            }
        }
    }

    private fun appPreferenceStore() = com.hhst.youtubelite.core.PreferenceStore.app(app)

    private fun readPage(initialUrl: String, ua: String, cookies: Map<String, String>, deadline: Long, current: () -> Boolean): String {
        var url = initialUrl
        var transientRetried = false
        var retryDeadline = Long.MAX_VALUE
        // Bound a stalled TLS/header/body read without shortening healthy body
        // transfers or weakening a caller's stricter read timeout.
        val readTimeout = http.readTimeoutMillis.toLong().takeIf { it > 0 }?.coerceAtMost(PAGE_STALL_MS) ?: PAGE_STALL_MS
        val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
            .readTimeout(readTimeout, TimeUnit.MILLISECONDS).build()
        repeat(4) {
            val requestDeadline = minOf(deadline, retryDeadline)
            val remaining = (requestDeadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0 || !current()) throw IOException("EXTRACTION_DEADLINE")
            val call = client.newCall(pageRequest(url, ua, cookies))
            call.timeout().timeout(minOf(10_000, remaining), TimeUnit.MILLISECONDS)
            val abort = cancellation.scheduleAtFixedRate({ if (System.nanoTime() >= requestDeadline || !current()) call.cancel() }, 100, 100, TimeUnit.MILLISECONDS)
            val started = System.nanoTime()
            val html = try { call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    diagnostics?.event("request", "session-page", "REDIRECT", (System.nanoTime() - started) / 1_000_000, response.code)
                    url = pageRedirect(url, response.header("Location") ?: throw IOException("PAGE_REDIRECT_MISSING"))
                    null
                } else {
                    if (!response.isSuccessful) throw PageHttpException("PAGE", response.code,
                        PageResponse.headerText(response.header("Retry-After"), System.currentTimeMillis()))
                    val source = response.body?.source() ?: throw IOException("PAGE_EMPTY")
                    if (source.request(8L * 1024 * 1024 + 1)) throw IOException("PAGE_TOO_LARGE")
                    source.readUtf8().also { diagnostics?.event("request", "session-page", "GET", (System.nanoTime() - started) / 1_000_000, response.code) }
                }
            } } catch (failure: IOException) {
                // OkHttp call timeouts surface as InterruptedIOException and handshake
                // EOFs as SSLException; both need the transient path.
                val now = System.nanoTime()
                if (!ExtractionReadRetry.isTransient(failure) || transientRetried || !current() ||
                    deadline - now < TimeUnit.MILLISECONDS.toNanos(500)) throw failure
                transientRetried = true
                retryDeadline = minOf(deadline, now + TimeUnit.MILLISECONDS.toNanos(PAGE_RETRY_MS))
                diagnostics?.event("request", "session-page", "TRANSIENT_RETRY", (System.nanoTime() - started) / 1_000_000, 0)
                null
            } finally { abort.cancel(false) }
            if (!current()) throw IOException("SESSION_CHANGED_OR_CANCELLED")
            if (html != null) return html
        }
        throw IOException("PAGE_REDIRECT_LIMIT")
    }

    private fun <T> withPermit(deadline: Long, current: () -> Boolean, block: () -> T): T {
        while (true) {
            if (System.nanoTime() >= deadline || !current() || Thread.currentThread().isInterrupted) throw IOException("SESSION_CHANGED_OR_CANCELLED")
            if (capturePermit.tryAcquire(100, TimeUnit.MILLISECONDS)) break
        }
        try { return block() } finally { capturePermit.release() }
    }

    fun isCurrent(session: YoutubeWebSession): Boolean = synchronized(lock) {
        session.networkGeneration == networkGeneration.get() && if (session.nativeBootstrap) {
            val native = com.hhst.youtubelite.data.media.MediaSession.snapshot()
            !native.signedIn && !native.visitor.isNullOrBlank() &&
                session.key == digest("native:${scopeHint()}:${nativeRecovery.get()}:${native.visitor}") &&
                listOf(ORIGIN, "https://youtube.com", "https://m.youtube.com", "https://music.youtube.com")
                    .all { authCookies(CookieManager.getInstance().getCookie(it).orEmpty()).isEmpty() }
        } else session.key == currentKey() && identity == scopeHint()
    }

    /**
     * Media bytes come from already-issued CDN URLs with no account credentials; page-config
     * refresh must not abort an in-flight read. Only a different signed-in account or network
     * route does, because those can invalidate the issued URL or its audience.
     */
    fun isMediaCurrent(session: YoutubeWebSession): Boolean =
        mediaInvalidationReason(session) == null

    /** A safe reason code, never a cookie, account identifier or network address. */
    fun mediaInvalidationReason(session: YoutubeWebSession): String? = when {
        session.networkGeneration != networkGeneration.get() -> "network_route_changed"
        !mediaCookies.matches(session.cookies(ORIGIN), CookieManager.getInstance().getCookie(ORIGIN).orEmpty()) -> "account_cookie_changed"
        else -> null
    }

    fun scopeHint(): String {
        val cookies = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()
        return identityHint(cookies, Constants.userAgent(), networkGeneration.get())
    }

    private fun identityHint(cookies: String, ua: String, route: Long): String {
        val account = com.hhst.youtubelite.data.media.MediaSession.snapshot()
        val scopedAgent = "$ua:${account.accountId}:${account.generation}"
        identityHint?.takeIf { it.cookies == cookies && it.userAgent == scopedAgent && it.route == route }?.let { return it.value }
        val auth = cookies.split(';').map { it.trim() }.filter {
            it.substringBefore('=') in setOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID", "LOGIN_INFO", "VISITOR_INFO1_LIVE")
        }.sorted().joinToString(";")
        return digest("$auth:$scopedAgent:$route").also { identityHint = IdentityHint(cookies, scopedAgent, route, it) }
    }

    /** Includes account/visitor/configuration changes even when the SID cookies are unchanged. */
    fun captureStamp(): String = synchronized(lock) { digest("${recoveryScope()}:$generation") }

    /** Refreshes themselves advance generation; concurrent consumers still share one recovery. */
    fun recoveryScope(): String = synchronized(lock) {
        val page = browserConfig?.takeIf { it.scope == scopeHint() && it.generation == it.document() && it.capturedAt > configAt }?.value ?: config
        digest("${scopeHint()}:${configurationIdentity(page)}")
    }

    private fun currentKey(): String {
        val visitor = config.text("VISITOR_DATA") ?: config.obj("INNERTUBE_CONTEXT").obj("client").text("visitorData")
        return digest("$identity:$generation:$visitor:${config.text("DATASYNC_ID", "")}:${accountIndex(config)}")
    }

    /** Origin and document generation are checked by BrowserPlayerResponses before this call. */
    fun acceptBrowserConfig(value: JsonObject, documentGeneration: Long, document: () -> Long) {
        if (storeBrowserConfig(value, documentGeneration, document)) onBrowserConfiguration?.invoke()
    }

    private fun storeBrowserConfig(value: JsonObject, documentGeneration: Long, document: () -> Long): Boolean = synchronized(lock) {
        val picked = LinkedHashMap<String, JsonElement>()
        CONFIG_FIELDS.forEach { name -> value.get(name)?.let { picked[name] = it } }
        if (JsonObject(picked).obj("INNERTUBE_CONTEXT").obj("client").isEmpty()) return@synchronized false
        picked["PLAYER_JS_URL"]?.let { current ->
            (current as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { js ->
                picked["PLAYER_JS_URL"] = JsonPrimitive(when {
                    js.startsWith("//") -> "https:$js"
                    js.startsWith('/') -> ORIGIN + js
                    else -> js
                })
            }
        }
        val filtered = JsonObject(picked)
        val hint = scopeHint()
        val old = browserConfig
        val previous = old?.takeIf { it.scope == hint }?.value ?: config
        if (hint != identity || configurationIdentity(filtered) != configurationIdentity(previous)) {
            identity = hint
            generation++
            configAt = 0
        }
        val copied = filtered
        val stored = BrowserConfig(copied, hint, documentGeneration, document, System.currentTimeMillis())
        browserConfig = if (old?.generation != documentGeneration) {
            stored.copy(capturedAt = System.currentTimeMillis())
        } else {
            stored
        }
        true
    }

    /** Metadata never crosses the captured identity boundary. */
    fun accountScope(session: YoutubeWebSession): String =
        digest("${session.account}:${session.accountIndex}:${session.dataSyncId.orEmpty()}:${authCookies(session.cookies(ORIGIN))}:${session.key}")

    companion object {
        private fun pageOrigin(url: String): String {
            val uri = runCatching { URI(url) }.getOrElse { throw IOException("PAGE_REDIRECT_INVALID") }
            if (uri.scheme != "https" || uri.host !in setOf("www.youtube.com", "youtube.com", "m.youtube.com", "music.youtube.com")
                || uri.rawUserInfo != null || uri.port !in setOf(-1, 443)) throw IOException("PAGE_REDIRECT_ORIGIN")
            return "https://${uri.host}"
        }

        internal fun pageRedirect(base: String, location: String): String =
            (base.toHttpUrlOrNull()?.resolve(location)?.toString() ?: throw IOException("PAGE_REDIRECT_INVALID"))
                .also { pageOrigin(it) }

        internal fun pageRequest(url: String, ua: String, cookies: Map<String, String>): Request = Request.Builder()
            .url(url).header("User-Agent", ua).header("Cookie", cookies[pageOrigin(url)].orEmpty()).build()

        internal fun configurationIdentity(value: JsonObject) = listOf(accountIndex(value),
            value.text("DELEGATED_SESSION_ID", ""), value.text("USER_SESSION_ID", ""),
            value.text("PLAYER_JS_URL", ""), value.obj("INNERTUBE_CONTEXT").obj("client").text("clientVersion", ""),
            value.text("DATASYNC_ID", ""), value.text("VISITOR_DATA") ?: value.obj("INNERTUBE_CONTEXT").obj("client").text("visitorData"), value.flag("LOGGED_IN", false), value.flag("IS_PREMIUM", false),
            value.obj("EXPERIMENT_FLAGS"), value.obj("EXPERIMENTS_FORCED_FLAGS"),
            value.obj("WEB_PLAYER_CONTEXT_CONFIGS").entries.map { (it.value as? JsonObject)?.text("serializedExperimentFlags", "") }.sortedBy { it.orEmpty() }).toString()
        const val ORIGIN = "https://www.youtube.com"

        /**
         * Account/visitor/player-URL/experiment config belong to the session, not one video.
         * Identity changes and explicit recovery already refresh it; this is only the upper
         * bound for an unchanged session.
         */
        private const val CONFIG_TTL_MS = 15 * 60_000L

        private const val PERSIST_KEY_PREFIX = "youtube-session:config:"
        private const val PERSIST_MAX_BYTES = 1_500_000
        private const val PAGE_STALL_MS = 6_000L
        private const val PAGE_RETRY_MS = 2_000L

        /** Account-bearing cookies only; visitor and consent cookies churn without changing the audience. */
        private val AUTH_COOKIE_NAMES = setOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID", "LOGIN_INFO")
        internal fun authCookies(cookies: String): String = cookies.splitToSequence(';').map { it.trim() }
            .filter { it.substringBefore('=') in AUTH_COOKIE_NAMES }
            .sorted().joinToString(";")
        private val CONFIG_FIELDS = setOf("INNERTUBE_CONTEXT", "INNERTUBE_API_KEY", "SESSION_INDEX",
            "DATASYNC_ID", "DELEGATED_SESSION_ID", "USER_SESSION_ID", "VISITOR_DATA", "LOGGED_IN",
            "IS_PREMIUM", "PLAYER_JS_URL", "WEB_PLAYER_CONTEXT_CONFIGS", "EXPERIMENT_FLAGS",
            "EXPERIMENTS_FORCED_FLAGS", "EVENT_ID", "_BG_CHALLENGE", "_BG_CHALLENGE_RAW", "_IS_LIVE", "_VIDEO_ID")
        internal fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray()).hexString()

        internal fun accountIndex(config: JsonObject): Int = config.get("SESSION_INDEX")
            ?.takeUnless { it is JsonNull }?.let {
                (it as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { index -> index >= 0 }
                    ?: throw IOException("SESSION_ACCOUNT_INDEX_INVALID")
            } ?: 0

        internal fun parseConfig(html: String): JsonObject {
            val merged = LinkedHashMap<String, JsonElement>()
            jsonObjects(html, Regex("ytcfg\\.set\\(\\s*\\{")).forEach { source ->
                source.entries.forEach { merged[it.key] = it.value }
            }
            val player = jsonObjects(html, Regex("ytInitialPlayerResponse\\s*=\\s*\\{")).firstOrNull()
            if (player != null) {
                merged["_IS_LIVE"] = JsonPrimitive(player.obj("videoDetails").flag("isLiveContent", false))
                player.obj("videoDetails").text("videoId")?.let { merged["_VIDEO_ID"] = JsonPrimitive(it) }
            }
            val initial = jsonObjects(html, Regex("ytInitialData\\s*=\\s*\\{")).firstOrNull()
            val logo = initial?.obj("topbar")?.obj("desktopTopbarRenderer")?.obj("logo")?.obj("topbarLogoRenderer")
            if (logo != null && (logo.obj("iconImage").text("iconType") == "YOUTUBE_PREMIUM_LOGO"
                    || logo.text("tooltip", "").contains("Premium", true))) merged["IS_PREMIUM"] = JsonPrimitive(true)
            val rawChallenge = objectPayloads(html, Regex("window\\.ytAtN\\(\\s*\\{")).firstOrNull()
            if (rawChallenge != null && rawChallenge.length <= 256 * 1024) {
                val challenge = runCatching { parseJson(rawChallenge) as JsonObject }.getOrNull()
                    ?.obj("R")?.obj("bgChallenge")
                if (challenge != null && challenge.isNotEmpty()) merged["_BG_CHALLENGE"] = challenge
                else merged["_BG_CHALLENGE_RAW"] = JsonPrimitive(rawChallenge)
            }
            return JsonObject(merged.filterKeys { it in CONFIG_FIELDS })
        }

        private fun jsonObjects(html: String, marker: Regex): Sequence<JsonObject> = objectPayloads(html, marker)
            .mapNotNull { runCatching { parseJson(it) as JsonObject }.getOrNull() }

        // Only capture the argument, without evaluating page code. BgUtils parses
        // the loose JSON challenge in its isolated, network-disabled runtime.
        private fun objectPayloads(html: String, marker: Regex): Sequence<String> = sequence {
            for (match in marker.findAll(html)) {
                val start = html.indexOf('{', match.range.first)
                var depth = 0; var quote: Char? = null; var escaped = false
                for (end in start until html.length) {
                    val c = html[end]
                    if (quote != null) {
                        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
                    } else {
                        if (c == '"' || c == '\'') quote = c
                        if (c == '{') depth++
                        if (c == '}' && --depth == 0) {
                            yield(html.substring(start, end + 1))
                            break
                        }
                    }
                }
            }
        }
    }
}
