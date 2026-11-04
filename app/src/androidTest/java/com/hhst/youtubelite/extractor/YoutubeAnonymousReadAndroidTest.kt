package com.hhst.youtubelite.extractor

import android.os.Bundle
import android.webkit.CookieManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.Constants
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Anonymous native route with an empty credential handle; never signs the device's account out. */
class YoutubeAnonymousReadAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ExtractionTestActivity::class.java)

    @Test fun anonymousPageAndMediaAreReadWithoutAccountHeaders() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ids = InstrumentationRegistry.getArguments().getString("videoIds").orEmpty()
            .split(',').mapNotNull(VideoId::parse)
        assumeTrue("Supply -e videoIds for anonymous network acceptance", ids.isNotEmpty())
        val app = instrumentation.targetContext
        val cookieSnapshot = CookieManager.getInstance().getCookie(YoutubeSessionProvider.ORIGIN)
        val diagnostics = ExtractionDiagnostics()
        val policy = YoutubeMediaRequests({ true }, diagnostics)
        val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                assertTrue("Anonymous request contained account credentials",
                    chain.request().header("Cookie").isNullOrEmpty() &&
                        chain.request().header("Authorization").isNullOrEmpty())
                chain.proceed(chain.request())
            }.addInterceptor(policy.interceptor()).build()
        val records = mutableListOf<Map<String, Any>>()
        try {
            for (id in ids) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                val ua = Constants.userAgent()
                var pageUrl = "${YoutubeSessionProvider.ORIGIN}/watch?v=$id&app=desktop"
                var page: String? = null
                for (attempt in 0..3) {
                    if (page != null) break
                    client.newCall(YoutubeSessionProvider.pageRequest(pageUrl, ua, emptyMap())).execute().use { response ->
                        if (response.code in setOf(301, 302, 303, 307, 308)) {
                            pageUrl = YoutubeSessionProvider.pageRedirect(pageUrl, requireNotNull(response.header("Location")))
                        } else {
                            assertEquals(200, response.code)
                            val source = requireNotNull(response.body).source()
                            require(!source.request(8L * 1024 * 1024 + 1))
                            page = source.readUtf8()
                        }
                    }
                }
                val html = requireNotNull(page) { "Anonymous page exceeded redirect limit" }
                val config0 = YoutubeSessionProvider.parseConfig(html)
                assertFalse("Page did not remain anonymous", config0.flag("LOGGED_IN", false))
                var js = config0.text("PLAYER_JS_URL") ?: Regex("\"jsUrl\"\\s*:\\s*\"([^\"]+)\"")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/") ?: error("Anonymous player missing")
                if (js.startsWith('/')) js = YoutubeSessionProvider.ORIGIN + js
                val config = kotlinx.serialization.json.JsonObject(config0.toMap() + ("PLAYER_JS_URL" to kotlinx.serialization.json.JsonPrimitive(js)))
                val visitor = config.text("VISITOR_DATA") ?:
                    config.obj("INNERTUBE_CONTEXT").obj("client").text("visitorData")
                val session = YoutubeWebSession("anonymous-acceptance-$id", WebAccount.ANONYMOUS,
                    0, null, null, null, visitor, ua, 0, js, "credential-free-watch", config, { "" })
                val transport = object : ExtractionTransport {
                    override fun get(context: ExtractionContext, url: String, headers: Map<String, List<String>>): PageResponse {
                        val builder = okhttp3.Request.Builder().url(url)
                        headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                        return client.newCall(builder.get().build()).execute().use {
                            PageResponse(it.code, it.headers.toMultimap(), it.body?.string().orEmpty(), it.request.url.toString())
                        }
                    }
                    override fun post(context: ExtractionContext, url: String, headers: Map<String, List<String>>, body: ByteArray): PageResponse {
                        val builder = okhttp3.Request.Builder().url(url)
                        headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                        return client.newCall(builder.post(body.toRequestBody(null)).build()).execute().use {
                            PageResponse(it.code, it.headers.toMultimap(), it.body?.string().orEmpty(), it.request.url.toString())
                        }
                    }
                }
                val context = ExtractionContext(session, transport,
                    false, true, true, "", deadline, { true }, diagnostics)
                val solver: SignatureSolver = GlobalContext.get().get<AndroidChallengeSolver>()
                val minter: PoTokenMinter = GlobalContext.get().get<PoTokenProvider>()
                val result = MscPlayback(solver, minter)
                    .resolve(id, context, demand = null, fresh = false, deferred = emptySet())
                assertTrue("No anonymous media candidates", result.stream.formats.isNotEmpty() || result.stream.manifests.isNotEmpty())
                data class Selection(val url: String, val plan: MediaRequestPlan, val protocol: String)
                val selected = listOfNotNull(
                    result.stream.formats.firstOrNull { it.audioOnly }?.let { Selection(it.url, it.requestPlan!!, "HTTPS") },
                    result.stream.formats.firstOrNull { !it.audioOnly }?.let { Selection(it.url, it.requestPlan!!, "HTTPS") }) +
                    result.stream.manifests.distinctBy { it.protocol }.map { Selection(it.url, it.requestPlan!!, it.protocol.name) }
                for (candidate in selected) {
                    val manifest = candidate.protocol != "HTTPS"
                    val started = System.nanoTime()
                    client.newCall(policy.build(candidate.url, candidate.plan, 0, if (manifest) -1 else 16 * 1024L))
                        .execute().use { response ->
                            assertTrue("Anonymous media HTTP ${response.code}", response.isSuccessful)
                            val bytes = requireNotNull(response.body).source().readByteArray(if (manifest) 1024 else 16 * 1024L)
                            val record = mapOf("videoId" to id, "session" to "ANONYMOUS",
                                "profile" to candidate.plan.client.name, "protocol" to candidate.protocol,
                                "httpStatus" to response.code, "firstReadBytes" to bytes.size,
                                "firstReadMs" to (System.nanoTime() - started) / 1_000_000)
                            records += record
                            instrumentation.sendStatus(2, Bundle().apply { putString("youtubeAnonymousRead", com.hhst.youtubelite.core.TestJson.any(record)) })
                        }
                }
            }
        } finally {
            assertTrue("Device account cookies changed", cookieSnapshot ==
                CookieManager.getInstance().getCookie(YoutubeSessionProvider.ORIGIN))
            File(app.filesDir, "youtube-anonymous-read.json").writeText(com.hhst.youtubelite.core.TestJson.any(records))
            File(app.filesDir, "youtube-anonymous-diagnostics.txt").writeText(diagnostics.export())
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
