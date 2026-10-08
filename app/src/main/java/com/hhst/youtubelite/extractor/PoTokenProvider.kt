package com.hhst.youtubelite.extractor

import android.content.Context
import com.grack.nanojson.JsonWriter
import com.grack.nanojson.JsonObject as NanoJsonObject
import com.google.gson.JsonObject
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.streams.YoutubeSession
import org.schabi.newpipe.extractor.services.youtube.streams.StreamHttpException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.net.URI
import android.app.ActivityManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hhst.youtubelite.core.WebViewTimerOccupancy
import com.hhst.youtubelite.core.WebViewTimerOwner
import org.schabi.newpipe.extractor.services.youtube.streams.ClientProfile
import org.schabi.newpipe.extractor.services.youtube.streams.ExtractionContext
import org.schabi.newpipe.extractor.services.youtube.streams.InnertubeAuth
import org.schabi.newpipe.extractor.services.youtube.streams.PoTokenProvider as CorePoTokenProvider
import java.io.IOException

/** BgUtils thin host; all transport goes through the immutable extraction context. */
class PoTokenProvider(
    context: Context,
    private val timers: WebViewTimerOccupancy = WebViewTimerOccupancy.NOOP,
    private val heavy: HeavyJsGate = HeavyJsGate(),
) : CorePoTokenProvider {
    private val app = context.applicationContext
    private val lock = Any()
    private val gson = Gson()
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
    private val tokens = LinkedHashMap<String, CorePoTokenProvider.Token>(128, .75f, true)

    init { reaper.scheduleWithFixedDelay({ synchronized(lock) {
        // The minter is bound to the integrity token's own lifetime (refreshAt), so keeping it for a
        // few minutes spares a ~1 s BotGuard initialization when the next video needs a token.
        if (runtime != null && System.currentTimeMillis() - lastUsed >= MINTER_IDLE_MS) releaseMinter()
    } }, 60, 60, TimeUnit.SECONDS) }

    override fun get(request: CorePoTokenProvider.TokenRequest, context: ExtractionContext): CorePoTokenProvider.Token = synchronized(lock) {
        if (!request.profile.webTokens() || request.scope != context.session.scope() || request.identifier.isBlank()) throw IOException("PO_UNSUPPORTED_CONTEXT")
        context.check()
        tokens[request.cacheKey()]?.takeIf { System.currentTimeMillis() + 30_000 < it.expiresAtMillis }?.let {
            context.diagnostics.event("token-cache", request.profile.name, "HIT:${request.purpose}:${request.binding}", 0, 0)
            return it
        }
        try {
            if (runtime == null || scope != request.scope || System.currentTimeMillis() >= refreshAt) {
                heavy.run(context) { initializeMinter(context, request.scope) }
            }
            val result = runtime!!.evaluate("WebPo.mint(${gson.toJson(request.identifier)})", 1_000, context)
            if (result.isBlank()) throw IOException("PO_EMPTY_TOKEN")
            context.check()
            CorePoTokenProvider.Token(result, expires).also {
                tokens[request.cacheKey()] = it
                while (tokens.size > 256) tokens.remove(tokens.keys.first())
                context.diagnostics.event("token", request.profile.name, "${request.purpose}:${request.binding}:${request.protocol}", 0, 0)
                lastUsed = System.currentTimeMillis()
            }
        } catch (failure: Throwable) {
            val known = setOf("PO_NO_CHALLENGE", "PO_INTERPRETER_ORIGIN", "PO_ACCOUNT_PAGE_MISMATCH",
                "PO_INITIALIZATION_TIMEOUT", "PO_EMPTY_TOKEN", "PO_INVALID_INTEGRITY", "JS_TIMEOUT", "JS_EVALUATION_Error",
                "JS_EVALUATION_TypeError", "JS_EVALUATION_ReferenceError", "JS_EVALUATION_RangeError",
                "JS_EVALUATION_SyntaxError", "JS_RUNTIME_FAILURE")
            val code = generateSequence(failure) { it.cause }.mapNotNull { it.message?.takeIf(known::contains) }.firstOrNull()
                ?: failure.javaClass.simpleName
            val status = (failure as? StreamHttpException)?.status ?: 0
            context.diagnostics.event("token-init", request.profile.name, "$initializationStage:$code", 0, status)
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
        fun pairedChallenge(config: NanoJsonObject): JsonObject? {
            config.remove("_BG_CHALLENGE")?.let {
                return JsonParser.parseString(JsonWriter.string(it)).asJsonObject
            }
            val raw = config.remove("_BG_CHALLENGE_RAW") as? String ?: return null
            val parsed = loadedRuntime().evaluate("WebPo.challenge(${gson.toJson(raw)})", remaining(), context)
            return JsonParser.parseString(parsed).takeIf { it.isJsonObject }?.asJsonObject
        }
        initializationStage = "CHALLENGE"
        var config = context.session.configuration()
        var challenge = pairedChallenge(config)
        var challengeSource = "SESSION_PAGE_PAIR"
        if (challenge == null) {
            val page = context.get(YoutubeSessionProvider.ORIGIN + "/?app=desktop&authuser=" + context.session.accountIndex, mapOf(
                "User-Agent" to listOf(context.session.userAgent),
                "Cookie" to listOf(context.session.cookies(YoutubeSessionProvider.ORIGIN))))
            if (page.responseCode() == 429 || page.responseCode() == 402) requireResponse(page, "PO_PAGE")
            if (page.responseCode() == 200) {
                val paired = YoutubeSessionProvider.parseConfig(page.responseBody())
                if (context.session.account != YoutubeSession.Account.ANONYMOUS
                    && (!paired.getBoolean("LOGGED_IN", false) || YoutubeSessionProvider.accountIndex(paired) != context.session.accountIndex
                        || context.session.dataSyncId?.let { it != paired.getString("DATASYNC_ID") } == true)) throw IOException("PO_ACCOUNT_PAGE_MISMATCH")
                pairedChallenge(paired)?.let {
                    config = paired
                    challenge = it
                    challengeSource = "HOMEPAGE_PAIR"
                }
            }
        }
        if (challenge == null) {
            val headers = InnertubeAuth.headers(context.session, ClientProfile.WEB, YoutubeSessionProvider.ORIGIN, System.currentTimeMillis() / 1000)
            val inner = config.getObject("INNERTUBE_CONTEXT")
            val body = "{\"context\":${JsonWriter.string(inner)},\"engagementType\":\"ENGAGEMENT_TYPE_UNBOUND\"}"
            val response = context.post("${YoutubeSessionProvider.ORIGIN}/youtubei/v1/att/get?prettyPrint=false", headers, body.toByteArray())
            requireResponse(response, "PO_ATTESTATION")
            challenge = JsonParser.parseString(response.responseBody()).asJsonObject.getAsJsonObject("bgChallenge")
            challengeSource = "ATTESTATION_FALLBACK"
        }
        context.diagnostics.event("token-challenge", "WEB", challengeSource, 0, 200)
        remaining()
        val interpreter = challenge?.getAsJsonObject("interpreterUrl")?.get("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue")?.asString ?: throw IOException("PO_NO_CHALLENGE")
        val url = if (interpreter.startsWith("//")) "https:$interpreter" else interpreter
        val uri = URI(url)
        if (uri.scheme != "https" || uri.host !in setOf("www.youtube.com", "www.google.com", "www.gstatic.com")) throw IOException("PO_INTERPRETER_ORIGIN")
        initializationStage = "INTERPRETER"
        val source = context.get(url, mapOf("User-Agent" to listOf(context.session.userAgent)))
        requireResponse(source, "PO_INTERPRETER")
        initializationStage = "RUNTIME_LOAD"
        loadedRuntime().evaluate("globalThis.yt={config_:${JsonWriter.string(config)}};\n" + source.responseBody() + "\n'loaded'", remaining(), context)
        initializationStage = "SNAPSHOT"
        val snapshot = runtime!!.evaluate("WebPo.snapshot(${gson.toJson(challenge)})", remaining(), context)
        val headers = mapOf("User-Agent" to listOf(context.session.userAgent), "Content-Type" to listOf("application/json+protobuf"),
            "x-goog-api-key" to listOf("AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"), "x-user-agent" to listOf("grpc-web-javascript/0.1"))
        initializationStage = "INTEGRITY"
        val response = context.post("${YoutubeSessionProvider.ORIGIN}/api/jnn/v1/GenerateIT", headers, gson.toJson(listOf("O43z0dpjhgX20SCx4KAo", snapshot)).toByteArray())
        requireResponse(response, "PO_INTEGRITY")
        val integrity = parsePoIntegrity(response.responseBody())
        initializationStage = "MINTER"
        runtime!!.evaluate("WebPo.initialize(${gson.toJson(integrity)})", remaining(), context)
        scope = newScope
        expires = System.currentTimeMillis() + integrity.estimatedTtlSecs * 1000
        refreshAt = expires - maxOf(30_000, integrity.mintRefreshThreshold * 1000)
        context.diagnostics.event("token-init", "WEB", "bgutils-4.0.3:initialized", (System.nanoTime() - start) / 1_000_000, 200)
    }

    private fun requireResponse(response: Response, stage: String) {
        if (response.responseCode() != 200) {
            val retry = response.responseHeaders().entries.firstOrNull { it.key.equals("Retry-After", true) }
                ?.value?.firstOrNull()?.let { StreamHttpException.parseRetryAfter(it, System.currentTimeMillis()) } ?: 0
            throw StreamHttpException(stage, response.responseCode(), retry)
        }
    }

    override fun invalidate(scope: String) = synchronized(lock) { if (this.scope == scope) clear() }
    override fun finished(context: ExtractionContext) = synchronized(lock) {
        if (lowRam && scope == context.session.scope()) releaseMinter()
    }
    fun invalidateAll() = synchronized(lock) { clear() }
    private fun releaseMinter() { runtime?.close(); runtime = null; scope = ""; expires = 0; refreshAt = 0 }
    private fun clear() { tokens.clear(); releaseMinter() }

    private companion object { const val MINTER_IDLE_MS = 5 * 60_000L }

}

/** GenerateIT's refresh threshold and fallback are optional; a two-item reply is valid. */
internal data class PoIntegrity(
    val integrityToken: String,
    val estimatedTtlSecs: Long,
    val mintRefreshThreshold: Long,
)

internal fun parsePoIntegrity(body: String): PoIntegrity {
    try {
        val data = JsonParser.parseString(body).asJsonArray
        fun item(index: Int) = if (index < data.size()) data[index].takeUnless { it.isJsonNull } else null
        val token = item(0)?.asString?.takeIf { it.isNotBlank() } ?: throw IOException("PO_INVALID_INTEGRITY")
        val ttl = item(1)?.asLong?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }
            ?: throw IOException("PO_INVALID_INTEGRITY")
        return PoIntegrity(token, ttl, item(2)?.asLong?.coerceAtLeast(0) ?: 0)
    } catch (failure: RuntimeException) { throw IOException("PO_INVALID_INTEGRITY", failure) }
}
