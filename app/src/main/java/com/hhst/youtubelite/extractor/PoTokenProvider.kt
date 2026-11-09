package com.hhst.youtubelite.extractor

import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.net.URI
import android.app.ActivityManager
import com.hhst.youtubelite.core.WebViewTimerOccupancy
import com.hhst.youtubelite.core.WebViewTimerOwner
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The BotGuard markers are interpreter inputs, not page configuration. */
internal fun JsonObject.stripChallengeMarkers(): JsonObject =
    JsonObject(filterKeys { it != "_BG_CHALLENGE" && it != "_BG_CHALLENGE_RAW" })

/** BgUtils adapter. Transport uses the immutable extraction context. */
class PoTokenProvider(
    context: Context,
    private val timers: WebViewTimerOccupancy = WebViewTimerOccupancy.NOOP,
    private val heavy: HeavyJsGate = HeavyJsGate(),
) : PoTokenMinter {
    private val app = context.applicationContext
    private val lock = Any()
    private var runtime: HiddenJavascriptRuntime? = null
    private var scope = ""
    private var expires = 0L
    private var refreshAt = 0L
    private var lastUsed = 0L
    private var initializationStage = "START"
    private val lowRam = app.getSystemService(ActivityManager::class.java).isLowRamDevice
    private val reaper = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "youtube-po-idle").apply { isDaemon = true }
    }
    private val tokens = LinkedHashMap<String, PoTokenMinter.Token>(128, .75f, true)

    init { reaper.scheduleWithFixedDelay({ synchronized(lock) {
        // Minter is bound to the integrity token's lifetime (refreshAt); keeping it a few
        // minutes spares a ~1 s BotGuard init for the next video.
        if (runtime != null && System.currentTimeMillis() - lastUsed >= MINTER_IDLE_MS) releaseMinter()
    } }, 60, 60, TimeUnit.SECONDS) }

    override fun mint(request: PoTokenMinter.TokenRequest, context: ExtractionContext): PoTokenMinter.Token = synchronized(lock) {
        if (!request.client.webTokens || request.scope != context.session.scope() || request.identifier.isBlank()) throw IOException("PO_UNSUPPORTED_CONTEXT")
        context.check()
        tokens[request.cacheKey()]?.takeIf { System.currentTimeMillis() + 30_000 < it.expiresAtMillis }?.let {
            context.diagnostics.event("token-cache", request.client.name, "HIT:${request.purpose}:${request.binding}", 0, 0)
            return it
        }
        try {
            if (runtime == null || scope != request.scope || System.currentTimeMillis() >= refreshAt) {
                heavy.run(context) { initializeMinter(context, request.scope) }
            }
            val result = runtime!!.evaluate("WebPo.mint(${JsonPrimitive(request.identifier)})", 1_000, context)
            if (result.isBlank()) throw IOException("PO_EMPTY_TOKEN")
            context.check()
            PoTokenMinter.Token(result, expires).also {
                tokens[request.cacheKey()] = it
                while (tokens.size > 256) tokens.remove(tokens.keys.first())
                context.diagnostics.event("token", request.client.name, "${request.purpose}:${request.binding}:${request.protocol}", 0, 0)
                lastUsed = System.currentTimeMillis()
            }
        } catch (failure: Throwable) {
            val known = setOf("PO_NO_CHALLENGE", "PO_INTERPRETER_ORIGIN", "PO_ACCOUNT_PAGE_MISMATCH",
                "PO_INITIALIZATION_TIMEOUT", "PO_EMPTY_TOKEN", "PO_INVALID_INTEGRITY", "JS_TIMEOUT", "JS_EVALUATION_Error",
                "JS_EVALUATION_TypeError", "JS_EVALUATION_ReferenceError", "JS_EVALUATION_RangeError",
                "JS_EVALUATION_SyntaxError", "JS_RUNTIME_FAILURE")
            val code = generateSequence(failure) { it.cause }.mapNotNull { it.message?.takeIf(known::contains) }.firstOrNull()
                ?: failure.javaClass.simpleName
            val status = (failure as? PageHttpException)?.status ?: 0
            context.diagnostics.event("token-init", request.client.name, "$initializationStage:$code", 0, status)
            clear()
            throw if (failure is IOException) failure else IOException("PO_INITIALIZATION", failure)
        }
    }

    private fun initializeMinter(originalContext: ExtractionContext, newScope: String) {
        val context = originalContext.withTimeLimit(15_000)
        clear()
        val start = System.nanoTime()
        fun remaining(): Long = (15_000 - (System.nanoTime() - start) / 1_000_000).coerceAtMost(context.remainingMillis()).also {
            if (it <= 0) throw IOException("PO_INITIALIZATION_TIMEOUT")
        }
        fun loadedRuntime(): HiddenJavascriptRuntime {
            return runtime ?: HiddenJavascriptRuntime(app, timers, WebViewTimerOwner.POTOKEN, context.session.userAgent).also {
                runtime = it
                val bundle = app.assets.open("potoken/bgutils.js").bufferedReader().use { input -> input.readText() }
                it.evaluate(bundle + "\n'loaded'", remaining(), context)
            }
        }
        // kotlinx config objects are immutable, so challenge markers are stripped at use sites.
        fun pairedChallenge(config: JsonObject): JsonObject? {
            if (config.containsKey("_BG_CHALLENGE")) {
                return config["_BG_CHALLENGE"] as? JsonObject
            }
            val raw = config["_BG_CHALLENGE_RAW"]?.asText ?: return null
            val parsed = loadedRuntime().evaluate("WebPo.challenge(${JsonPrimitive(raw)})", remaining(), context)
            return parseJson(parsed) as? JsonObject
        }
        initializationStage = "CHALLENGE"
        var config = context.session.configuration()
        var challenge = pairedChallenge(config)
        var challengeSource = "SESSION_PAGE_PAIR"
        if (challenge == null) {
            val page = context.get(YoutubeSessionProvider.ORIGIN + "/?app=desktop&authuser=" + context.session.accountIndex, mapOf(
                "User-Agent" to listOf(context.session.userAgent),
                "Cookie" to listOf(context.session.cookies(YoutubeSessionProvider.ORIGIN))))
            if (page.responseCode == 429 || page.responseCode == 402) requireResponse(page, "PO_PAGE")
            if (page.responseCode == 200) {
                val paired = YoutubeSessionProvider.parseConfig(page.responseBody)
                if (context.session.account != WebAccount.ANONYMOUS
                    && (!paired.flag("LOGGED_IN", false) || YoutubeSessionProvider.accountIndex(paired) != context.session.accountIndex
                        || context.session.dataSyncId?.let { it != paired.text("DATASYNC_ID") } == true)) throw IOException("PO_ACCOUNT_PAGE_MISMATCH")
                pairedChallenge(paired)?.let {
                    config = paired
                    challenge = it
                    challengeSource = "HOMEPAGE_PAIR"
                }
            }
        }
        if (challenge == null) {
            val headers = InnertubeAuth.headers(context.session, StreamClient.WEB, YoutubeSessionProvider.ORIGIN, System.currentTimeMillis() / 1000)
            val inner = config.obj("INNERTUBE_CONTEXT")
            val body = "{\"context\":$inner,\"engagementType\":\"ENGAGEMENT_TYPE_UNBOUND\"}"
            val response = context.post("${YoutubeSessionProvider.ORIGIN}/youtubei/v1/att/get?prettyPrint=false", headers, body.toByteArray())
            requireResponse(response, "PO_ATTESTATION")
            challenge = (parseJson(response.responseBody) as JsonObject)["bgChallenge"] as? JsonObject
            challengeSource = "ATTESTATION_FALLBACK"
        }
        context.diagnostics.event("token-challenge", "WEB", challengeSource, 0, 200)
        remaining()
        val interpreter = challenge?.objOrNull("interpreterUrl")?.get("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")?.asText ?: throw IOException("PO_NO_CHALLENGE")
        val url = if (interpreter.startsWith("//")) "https:$interpreter" else interpreter
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host !in setOf("www.youtube.com", "www.google.com", "www.gstatic.com")) throw IOException("PO_INTERPRETER_ORIGIN")
        initializationStage = "INTERPRETER"
        val source = context.get(url, mapOf("User-Agent" to listOf(context.session.userAgent)))
        requireResponse(source, "PO_INTERPRETER")
        initializationStage = "RUNTIME_LOAD"
        loadedRuntime().evaluate("globalThis.yt={config_:${config.stripChallengeMarkers()}};\n" + source.responseBody + "\n'loaded'", remaining(), context)
        initializationStage = "SNAPSHOT"
        val snapshot = runtime!!.evaluate("WebPo.snapshot(${challenge?.toString() ?: "null"})", remaining(), context)
        val headers = mapOf("User-Agent" to listOf(context.session.userAgent), "Content-Type" to listOf("application/json+protobuf"),
            "x-goog-api-key" to listOf("AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"), "x-user-agent" to listOf("grpc-web-javascript/0.1"))
        initializationStage = "INTEGRITY"
        val response = context.post("${YoutubeSessionProvider.ORIGIN}/api/jnn/v1/GenerateIT", headers,
            JsonArray(listOf("O43z0dpjhgX20SCx4KAo", snapshot).map(::JsonPrimitive)).toString().toByteArray())
        requireResponse(response, "PO_INTEGRITY")
        val integrity = parsePoIntegrity(response.responseBody)
        initializationStage = "MINTER"
        runtime!!.evaluate("WebPo.initialize(${Json.encodeToString(integrity)})", remaining(), context)
        scope = newScope
        expires = System.currentTimeMillis() + integrity.estimatedTtlSecs * 1000
        refreshAt = expires - maxOf(30_000, integrity.mintRefreshThreshold * 1000)
        context.diagnostics.event("token-init", "WEB", "bgutils-4.0.3:initialized", (System.nanoTime() - start) / 1_000_000, 200)
    }

    private fun requireResponse(response: PageResponse, stage: String) {
        if (response.responseCode != 200) {
            val retry = PageResponse.headerText(response.header("Retry-After"), System.currentTimeMillis())
            throw PageHttpException(stage, response.responseCode, retry)
        }
    }

    override fun invalidate(scope: String) = synchronized(lock) { if (this.scope == scope) clear() }
    override fun finished(context: ExtractionContext) = synchronized(lock) {
        if (lowRam && scope == context.session.scope()) releaseMinter()
    }
    override fun invalidateAll() = synchronized(lock) { clear() }
    private fun releaseMinter() { runtime?.close(); runtime = null; scope = ""; expires = 0; refreshAt = 0 }
    private fun clear() { tokens.clear(); releaseMinter() }

    private companion object { const val MINTER_IDLE_MS = 5 * 60_000L }

}

/** GenerateIT refresh threshold/fallback are optional; a two-item reply is valid. */
@kotlinx.serialization.Serializable
internal data class PoIntegrity(
    val integrityToken: String,
    val estimatedTtlSecs: Long,
    val mintRefreshThreshold: Long,
)

internal fun parsePoIntegrity(body: String): PoIntegrity {
    try {
        val data = parseJson(body) as JsonArray
        fun item(index: Int) = data.getOrNull(index)?.takeUnless { it is JsonNull }
        val token = item(0)?.asText?.takeIf { it.isNotBlank() } ?: throw IOException("PO_INVALID_INTEGRITY")
        val ttl = item(1)?.asLongNum?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }
            ?: throw IOException("PO_INVALID_INTEGRITY")
        return PoIntegrity(token, ttl, item(2)?.asLongNum?.coerceAtLeast(0) ?: 0)
    } catch (failure: RuntimeException) { throw IOException("PO_INVALID_INTEGRITY", failure) }
}
