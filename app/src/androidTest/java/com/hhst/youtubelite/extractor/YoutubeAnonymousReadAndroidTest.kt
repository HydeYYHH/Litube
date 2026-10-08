package com.hhst.youtubelite.extractor

import android.os.Bundle
import android.webkit.CookieManager
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.hhst.youtubelite.core.Constants
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.schabi.newpipe.extractor.services.youtube.streams.*

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
                val config = YoutubeSessionProvider.parseConfig(html)
                assertFalse("Page did not remain anonymous", config.getBoolean("LOGGED_IN", false))
                var js = config.getString("PLAYER_JS_URL") ?: Regex("\"jsUrl\"\\s*:\\s*\"([^\"]+)\"")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/") ?: error("Anonymous player missing")
                if (js.startsWith('/')) js = YoutubeSessionProvider.ORIGIN + js
                config["PLAYER_JS_URL"] = js
                val visitor = config.getString("VISITOR_DATA") ?:
                    config.getObject("INNERTUBE_CONTEXT").getObject("client").getString("visitorData")
                val session = YoutubeSession("anonymous-acceptance-$id", YoutubeSession.Account.ANONYMOUS,
                    0, null, null, null, visitor, ua, 0, js, "credential-free-watch", config, { "" })
                val context = ExtractionContext(session, HttpDownloader(client),
                    GlobalContext.get().get<AndroidChallengeSolver>(), GlobalContext.get().get<PoTokenProvider>(),
                    false, true, deadline, { true }, diagnostics)
                val result = YoutubeStreamEngine().extract(id, context)
                assertTrue("No anonymous media candidates", result.candidates.isNotEmpty() || result.manifests.isNotEmpty())
                val selected = listOfNotNull(result.candidates.firstOrNull { it.audioOnly },
                    result.candidates.firstOrNull { !it.audioOnly }) + result.manifests.distinctBy { it.key.protocol }
                for (candidate in selected) {
                    val manifest = candidate in result.manifests
                    val started = System.nanoTime()
                    client.newCall(policy.build(candidate.url, candidate.requestPlan, 0, if (manifest) -1 else 16 * 1024L))
                        .execute().use { response ->
                            assertTrue("Anonymous media HTTP ${response.code}", response.isSuccessful)
                            val bytes = requireNotNull(response.body).source().readByteArray(if (manifest) 1024 else 16 * 1024L)
                            val record = mapOf("videoId" to id, "session" to "ANONYMOUS",
                                "profile" to candidate.requestPlan.profile.name, "protocol" to candidate.key.protocol.name,
                                "httpStatus" to response.code, "firstReadBytes" to bytes.size,
                                "firstReadMs" to (System.nanoTime() - started) / 1_000_000)
                            records += record
                            instrumentation.sendStatus(2, Bundle().apply { putString("youtubeAnonymousRead", Gson().toJson(record)) })
                        }
                }
            }
        } finally {
            assertTrue("Device account cookies changed", cookieSnapshot ==
                CookieManager.getInstance().getCookie(YoutubeSessionProvider.ORIGIN))
            File(app.filesDir, "youtube-anonymous-read.json").writeText(Gson().toJson(records))
            File(app.filesDir, "youtube-anonymous-diagnostics.txt").writeText(diagnostics.export())
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
