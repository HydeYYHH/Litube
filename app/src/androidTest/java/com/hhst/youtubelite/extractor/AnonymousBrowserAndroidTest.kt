package com.hhst.youtubelite.extractor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.data.media.MediaClient
import com.hhst.youtubelite.data.media.MediaHomeRepository
import com.hhst.youtubelite.data.media.MediaSession
import com.hhst.youtubelite.debug.reference.PageReferenceActivity
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.browsePage
import com.liskovsoft.youtubeapi.videoinfo.V2.homeQuery
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class AnonymousBrowserAndroidTest {
    @Test fun actualBrowserViewingComparesHomeProfiles() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("network") == "1" && arguments.getString("anonymousReporting") == "1")
        assumeTrue(!MediaSession.privateBrowsing())
        val previousMode = MediaSession.anonymousMode()
        val records = JSONArray()
        val scenario = ActivityScenario.launch<PageReferenceActivity>(
            Intent(instrumentation.targetContext, PageReferenceActivity::class.java).apply {
                data = Uri.parse(HOME)
            })
        lateinit var host: PageReferenceActivity
        scenario.onActivity { host = it }
        fun web(): WebView = requireNotNull(findWeb(host.window.decorView))
        val probe = WebProbe(::web)
        fun record(event: String, value: JSONObject) {
            value.put("event", event)
            records.put(value)
            instrumentation.sendStatus(2, Bundle().apply { putString("anonymousBrowser", value.toString()) })
        }
        fun waitPage(videoId: String? = null, staleDocument: String? = null) {
            val deadline = SystemClock.elapsedRealtime() + 40_000
            val expected = videoId?.let(JSONObject::quote) ?: "null"
            val stale = staleDocument?.let(JSONObject::quote) ?: "null"
            while (SystemClock.elapsedRealtime() < deadline) {
                val ready = probe.evaluate("""
                    const expected=$expected, url=new URL(location.href);
                    const correct=expected ? url.searchParams.get('v')===expected : url.pathname==='/';
                    return {ready:correct && (!$stale || window.__comparisonDocument!==$stale) &&
                        document.readyState==='complete' && !!window.ytcfg?.get('INNERTUBE_CONTEXT')};
                """.trimIndent())
                if (ready.optBoolean("ready")) return
                Thread.sleep(200)
            }
            throw AssertionError("Browser configuration was not ready")
        }
        fun navigate(url: String, videoId: String? = null) {
            val marker = System.nanoTime().toString()
            probe.evaluate("window.__comparisonDocument=${JSONObject.quote(marker)}; return {};")
            instrumentation.runOnMainSync { web().loadUrl(url) }
            waitPage(videoId, marker)
        }
        suspend fun compare(event: String) {
            waitPage()
            val identity = probe.evaluate("return {visitor:ytcfg.get('VISITOR_DATA') || ytcfg.get('INNERTUBE_CONTEXT').client.visitorData, signedIn:!!ytcfg.get('LOGGED_IN'), client:ytcfg.get('INNERTUBE_CONTEXT').client.clientName};")
            assumeTrue("The browser must be anonymous", !identity.optBoolean("signedIn"))
            val cookie = CookieManager.getInstance().getCookie(HOME).orEmpty()
            val visitor = identity.optString("visitor").takeIf { it.isNotBlank() }
            record("${event}_identity", JSONObject().put("client", identity.optString("client"))
                .put("visitorHash", visitor?.let(::hash))
                .put("visitorIdHash", visitor?.let(::visitorIdHash))
                .put("cookieNames", JSONArray(cookie.split(';').map { it.trim().substringBefore('=') })))
            probe.evaluate(FETCH_HOME)
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var result = JSONObject()
            while (SystemClock.elapsedRealtime() < deadline) {
                result = probe.evaluate("return window.__homeComparison || {};")
                if (result.has("http") || result.has("error")) break
                Thread.sleep(200)
            }
            assertTrue("Browser home request did not complete", result.has("http"))
            record("${event}_mweb", result)
            GlobalContext.get().get<MediaClient>().read { native, http ->
                fun sample(name: String, context: RequestContext): Int {
                    val query = homeQuery(context = context)
                    val response = http.execute(HttpRequest("POST", query.url, query.headers, query.body))
                    val page = browsePage(response.body)
                    record("${event}_$name", JSONObject().put("http", response.status)
                        .put("items", page.items.size).put("messages", JSONArray(page.messages))
                        .put("hasContinuation", page.nextKey != null))
                    return page.items.size
                }
                val browser = RequestContext(visitor = visitor, visitorCookie = cookie)
                val count = sample("tv_same_identity", browser)
                if (count > 0) {
                    record("${event}_native_identity", JSONObject().put("signedIn", native.signedIn)
                        .put("visitorIdHash", native.visitor?.let(::visitorIdHash)))
                    sample("tv_native_with_browser_visitor", native.copy(visitor = visitor))
                    sample("tv_browser_with_native_visitor", browser.copy(visitor = native.visitor))
                    val allowed = setOf("VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "YSC", "__Secure-ROLLOUT_TOKEN", "__Secure-YEC")
                    val fields = cookie.split(';').map(String::trim)
                    val restricted = fields.filter { it.substringBefore('=') in allowed }.joinToString("; ")
                    sample("tv_restricted_cookies", browser.copy(visitorCookie = restricted))
                    val consent = "SOCS=CAISEwgDEgk5NTA1MzE4OTQaAmVuIAEaBgiA7frSBg"
                    sample("tv_restricted_with_consent", browser.copy(visitorCookie = "$restricted; $consent"))
                    sample("tv_full_with_consent", browser.copy(visitorCookie = "$cookie; $consent"))
                    sample("tv_without_cookies", browser.copy(visitorCookie = null))
                    for (field in fields) {
                        val name = field.substringBefore('=')
                        sample("tv_without_$name", browser.copy(visitorCookie = fields.filterNot { it == field }.joinToString("; ")))
                    }
                }
            }
            val native = MediaHomeRepository(GlobalContext.get().get()).loadHome("All", null)
            record("${event}_native_home", JSONObject().put("items", native.items.size)
                .put("messages", JSONArray(native.messages)).put("hasContinuation", native.nextKey != null))
        }
        try {
            MediaSession.setAnonymousMode(true)
            compare("before")
            val videos = if (arguments.getString("compareOnly") == "1") emptyList()
                else arguments.getString("videos", "GjjK6yD5I9I,FEZS601QSVE,aqz-KE-bpKQ").orEmpty().split(',')
            for (video in videos.mapNotNull(VideoId::parse)) {
                navigate("https://m.youtube.com/watch?v=$video&hl=en&persist_app=1&app=m", video)
                assumeTrue("The browser must be anonymous", !probe.evaluate("return {signedIn:!!ytcfg.get('LOGGED_IN')};").optBoolean("signedIn"))
                val deadline = SystemClock.elapsedRealtime() + 100_000
                var state = JSONObject()
                while (SystemClock.elapsedRealtime() < deadline) {
                    state = probe.evaluate(PLAYBACK)
                    if (state.optDouble("time") >= 20 || state.optBoolean("ended")) break
                    Thread.sleep(500)
                }
                record("browser_playback", state.put("video", video))
                assertTrue("Browser playback did not reach 20 seconds or its actual end",
                    state.optDouble("time") >= 20 || state.optBoolean("ended") && state.optDouble("time") > 0)
                probe.evaluate("document.querySelector('video')?.pause(); return {};")
            }
            if (arguments.getString("compareOnly") != "1") {
                navigate(HOME)
                compare("after")
            }
        } finally {
            File(instrumentation.targetContext.filesDir, "anonymous-browser-comparison.json").writeText(records.toString(2))
            scenario.close()
            MediaSession.setAnonymousMode(previousMode)
        }
    }

    private fun findWeb(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findWeb(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun visitorIdHash(value: String): String? = runCatching {
        val decoded = android.util.Base64.decode(java.net.URLDecoder.decode(value, "UTF-8"),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        if (decoded.size < 2 || decoded[0].toInt() != 10) null
        else hash(decoded.copyOfRange(2, 2 + (decoded[1].toInt() and 255)).toString(Charsets.UTF_8))
    }.getOrNull()

    private companion object {
        const val HOME = "https://m.youtube.com/?hl=en&persist_app=1&app=m"
        val FETCH_HOME = """
            window.__homeComparison = null;
            const count = root => {
                const ids = new Set(), renderers = {}, messages = [];
                const visit = v => {
                    if (!v || typeof v !== 'object') return;
                    for (const [key, child] of Object.entries(v)) {
                        if (key === 'videoId' && typeof child === 'string') ids.add(child);
                        if (/Renderer${'$'}|ViewModel${'$'}/.test(key)) renderers[key] = (renderers[key] || 0) + 1;
                        if (key === 'feedNudgeRenderer') messages.push(child.title?.runs?.map(x=>x.text).join(''));
                        visit(child);
                    }
                };
                visit(root);
                return {videoIds:ids.size, renderers, messages};
            };
            fetch('/youtubei/v1/browse?prettyPrint=false', {
                method:'POST', credentials:'include', headers:{'Content-Type':'application/json'},
                body:JSON.stringify({context:ytcfg.get('INNERTUBE_CONTEXT'),browseId:'FEwhat_to_watch'})
            }).then(async r => window.__homeComparison = {http:r.status,...count(await r.json())})
              .catch(e => window.__homeComparison = {error:e.name});
            return {};
        """.trimIndent()
        val PLAYBACK = """
            const video = document.querySelector('video');
            if (!video) return {hasVideo:false};
            video.muted = true;
            if (video.paused && !video.ended) video.play().catch(()=>{});
            const reports = performance.getEntriesByType('resource').filter(r => /\/api\/stats\/(playback|watchtime)/.test(r.name));
            return {hasVideo:true,url:location.pathname+location.search,time:video.currentTime,duration:Number.isFinite(video.duration)?video.duration:null,
                ended:video.ended,paused:video.paused,ready:video.readyState,
                error:video.error?.code || 0,playbackReports:reports.filter(r=>r.name.includes('/playback?')).length,
                watchTimeReports:reports.filter(r=>r.name.includes('/watchtime?')).length};
        """.trimIndent()
    }
}
