package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.net.PageScript
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class WatchHistoryAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var view: WebView? = null
    private val probe get() = WebProbe { view!! }

    private fun inject() {
        for (name in listOf("core.js", "player-hook.js")) {
            val source = instrumentation.targetContext.assets.open("script/$name").bufferedReader().use { it.readText() }
            instrumentation.runOnMainSync { view!!.evaluateJavascript(source, null) }
        }
    }

    private fun destroy() = instrumentation.runOnMainSync {
        view?.let { it.loadUrl("about:blank"); it.stopLoading(); it.destroy() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun controllerHistoryTransitionPrecedesPauseAndShortsRestoresThePlayer() {
        try {
            activity.scenario.onActivity { context ->
                view = WebView(context).apply { settings.javaScriptEnabled = true }
                context.setContentView(view)
                view!!.loadDataWithBaseURL("https://m.youtube.com/watch?v=abcdefghijk", """
                    <html><body><div id=movie_player><video></video></div><script>
                    window.calls=[];window.Bridge={play:()=>calls.push('native-play'),hidePlayer:()=>{}};
                    const p=document.getElementById('movie_player');let state=-1,listener;
                    Object.defineProperty(document.querySelector('video'),'paused',{get:()=>state!==1});
                    const add=p.addEventListener.bind(p),remove=p.removeEventListener.bind(p);
                    p.addEventListener=(type,fn)=>type==='onStateChange'?listener=fn:add(type,fn);
                    p.removeEventListener=(type,fn)=>type==='onStateChange'?listener=null:remove(type,fn);
                    p.mute=()=>calls.push('mute');p.unMute=()=>calls.push('unmute');
                    p.getPlayerState=()=>state;p.getCurrentTime=()=>23;
                    p.getVideoData=()=>({video_id:new URL(location.href).searchParams.get('v')});
                    p.seekTo=t=>calls.push('seek:'+t);p.setPlaybackQualityRange=q=>calls.push('quality:'+q);
                    p.playVideo=()=>{calls.push('play');setTimeout(()=>{state=1;listener?.(1);calls.push('history')},0)};
                    p.pauseVideo=()=>{state=2;calls.push('pause')};window.fixtureReady=true;
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            probe.waitFor("return {ready:!!window.fixtureReady}")
            inject()
            val observed = probe.waitFor("return {ready:calls.includes('pause'),calls,muted:document.querySelector('video').muted}")
            val calls = observed.getJSONArray("calls").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertTrue(observed.getBoolean("muted"))
            assertTrue(calls.indexOf("history") < calls.indexOf("pause"))
            assertTrue(calls.contains("seek:23"))
            probe.evaluate("history.replaceState({},'','/shorts/abcdefghijk');__syncPlayerCompact();return {}")
            val shorts = probe.evaluate("return {muted:document.querySelector('video').muted,quality:calls[calls.length-2]}")
            assertFalse(shorts.getBoolean("muted"))
            assertEquals("quality:default", shorts.getString("quality"))
        } finally { destroy() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun liveYoutubeEmitsHistoryRequestAndLeavesWebMediaPaused() {
        assumeTrue("Supply -e network=1 for live YouTube acceptance",
            InstrumentationRegistry.getArguments().getString("network") == "1")
        val requests = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()
        val historyLoaded = CountDownLatch(1)
        val scripts = listOf("core.js", "player-hook.js").joinToString("\n") { name ->
            instrumentation.targetContext.assets.open("script/$name").bufferedReader().use { it.readText() }
        }
        try {
            activity.scenario.onActivity { context ->
                view = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = Constants.userAgent()
                    addJavascriptInterface(Bridge({}, {}, extensionManager = GlobalContext.get().get<ExtensionManager>()), Bridge.NAME)
                    PageScript("script/core.js", "HistoryCore").install(context, this)
                    PageScript("script/player-hook.js", "HistoryPlayer").install(context, this)
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val uri = request.url
                            if (uri.host == "youtube.com" || uri.host?.endsWith(".youtube.com") == true)
                                paths.add(uri.path.orEmpty())
                            if ((uri.host == "youtube.com" || uri.host?.endsWith(".youtube.com") == true) &&
                                uri.path in listOf("/api/stats/playback", "/api/stats/watchtime") &&
                                uri.getQueryParameter("docid") == "jNQXAC9IVRw") requests.add(uri.path!!)
                            return null
                        }
                        override fun onPageFinished(v: WebView, url: String) {
                            v.evaluateJavascript(scripts, null)
                            if (url.contains("/feed/history")) historyLoaded.countDown()
                        }
                    }
                }
                context.setContentView(view)
                view!!.loadUrl("https://m.youtube.com/watch?v=jNQXAC9IVRw")
            }
            // Bridge-only stand-in: the page lifecycle is real; no second native
            // extraction is needed to observe the page's own history request.
            val deadline = System.currentTimeMillis() + 60_000
            var observed = org.json.JSONObject()
            while (System.currentTimeMillis() < deadline) {
                observed = probe.evaluate("window.__syncPlayerCompact?.();const p=document.querySelector('#movie_player'),v=p?.querySelector('video');return {ready:!!p,state:p?.getPlayerState?.(),muted:v?.muted,paused:v?.paused,signedIn:!!window.ytcfg?.get?.('LOGGED_IN'),title:document.title}")
                if (requests.isNotEmpty() && observed.optBoolean("paused") && observed.optBoolean("muted")) break
                Thread.sleep(250)
            }
            observed.put("historyRequests", org.json.JSONArray(requests.distinct()))
            observed.put("requestPaths", org.json.JSONArray(paths.distinct()))
            observed.put("resourcePaths", probe.evaluate("return {paths:performance.getEntriesByType('resource').map(e=>{try{return new URL(e.name).pathname}catch(_){return ''}}).filter(p=>p.includes('/api/')||p.includes('/youtubei/'))}").getJSONArray("paths"))
            DeviceEvidence.writeJson("watch-history-live.json", observed.toString(2))
            assertTrue("YouTube emitted no history request: $observed", requests.isNotEmpty())
            assertTrue("Web player kept playing behind native playback: $observed", observed.optBoolean("paused"))
            assertTrue(observed.optBoolean("muted"))
            if (observed.optBoolean("signedIn")) {
                instrumentation.runOnMainSync { view!!.loadUrl("https://m.youtube.com/feed/history") }
                assertTrue("Account history page did not load", historyLoaded.await(30, TimeUnit.SECONDS))
                val history = probe.waitFor("const ids=Array.from(document.querySelectorAll('a[href]')).map(a=>{try{return new URL(a.href).searchParams.get('v')}catch(_){return null}}).filter(Boolean);return {ready:location.pathname==='/feed/history'&&ids.includes('jNQXAC9IVRw'),containsWatchedVideo:ids.includes('jNQXAC9IVRw')}")
                assertTrue(history.getBoolean("containsWatchedVideo"))
                observed.put("accountHistoryContainsVideo", true)
                DeviceEvidence.writeJson("watch-history-live.json", observed.toString(2))
            }
        } finally { destroy() }
    }
}
