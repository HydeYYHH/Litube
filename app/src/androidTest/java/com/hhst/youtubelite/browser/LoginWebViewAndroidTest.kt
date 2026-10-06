package com.hhst.youtubelite.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import com.hhst.youtubelite.ui.browser.BackResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class LoginWebViewAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var host: BrowserHost? = null
    private val routed = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var navigation: BrowserViewModel
    private fun create(url: String) {
        activity.scenario.onActivity { context ->
            navigation = BrowserViewModel(GlobalContext.get().get<ExtensionManager>())
            val callbacks = object : WebViewCallbacks by navigation.callbacksFor(navigation.uiState.value.activeId) {
                override fun onOpenTab(url: String) { routed += url; navigation.openTab(url) }
            }
            scope.launch { navigation.loadRequests.collect { (_, target) -> host?.webView?.loadUrl(target) } }
            host = WebViewFactory.create(context, callbacks, GlobalContext.get().get<ExtensionManager>(),
                {}, {}, {}, {}, {}, GlobalContext.get().get<Extractor>(), object : PlayerHooks {
                    override fun playVideo(url: String, origin: PageOrigin) {}
                    override fun hidePlayer(origin: PageOrigin) {}
                    override fun seekLoadedVideo(url: String, positionMs: Long) = false
                    override fun setPlayerLayout(topDp: Int, heightDp: Int, origin: PageOrigin) {}
                }, tabId = navigation.uiState.value.activeId)
            context.setContentView(host!!.container)
            host!!.webView.loadUrl(url)
        }
    }
    private fun evaluate(script: String): JSONObject {
        val value = AtomicReference("{}")
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { host!!.webView.evaluateJavascript(script) {
            value.set(it ?: "{}"); latch.countDown()
        } }
        assertTrue(latch.await(4, TimeUnit.SECONDS))
        return runCatching { JSONObject(JSONTokener(value.get()).nextValue().toString()) }.getOrDefault(JSONObject())
    }
    private fun poll(script: String, ready: (JSONObject) -> Boolean): JSONObject {
        val deadline = System.currentTimeMillis() + 45_000
        var result = JSONObject()
        while (System.currentTimeMillis() < deadline) {
            result = evaluate(script)
            if (ready(result)) break
            Thread.sleep(300)
        }
        return result
    }
    private fun close() = instrumentation.runOnMainSync { scope.cancel(); host?.webView?.let {
        it.loadUrl("about:blank"); it.stopLoading(); it.destroy()
    } }

    @Test fun loginEntryLoadsWithoutPageHooksAndPreservesRedirectOwnership() {
        assumeTrue("Supply -e network=1 for live-web login acceptance",
            InstrumentationRegistry.getArguments().getString("network") == "1")
        try {
            create("https://m.youtube.com/")
            val source = poll("JSON.stringify({ready:document.readyState==='complete',host:location.hostname})") { it.optBoolean("ready") && it.optString("host") == "m.youtube.com" }
            assertEquals("m.youtube.com", source.optString("host"))
            evaluate("JSON.stringify((function(){location.href='https://m.youtube.com/signin';return {started:true};})())")
            val evidence = poll("""JSON.stringify({host:location.hostname,title:document.title,
                identifier:!!document.querySelector('input[type="email"],#identifierId'),
                hooks:!!(window.__nav||window.__ads||window.Lite),agent:navigator.userAgent})""") {
                it.optBoolean("identifier")
            }
            evidence.put("provider", WebView.getCurrentWebViewPackage()?.versionName)
            File(instrumentation.targetContext.filesDir, "upgrade-login-webview.json").writeText(evidence.toString(2))
            assertEquals("accounts.google.com", evidence.optString("host"))
            assertTrue("Sign-in form not available: $evidence", evidence.optBoolean("identifier"))
            assertFalse(evidence.optBoolean("hooks"))
            assertFalse(evidence.optString("agent").contains("; wv"))
            instrumentation.runOnMainSync {
                for (url in listOf("https://accounts.youtube.com/accounts/SetSID", "https://consent.google.com/m",
                    "https://accounts.google.co.jp/accounts/SetSID",
                    "https://accounts.google.co.uk/accounts/SetSID", "https://accounts.google.com.hk/accounts/SetSID",
                    "https://m.youtube.com/check_connection", "https://m.youtube.com/")) {
                    val request = object : WebResourceRequest {
                        override fun getUrl() = Uri.parse(url)
                        override fun isForMainFrame() = true
                        override fun isRedirect() = true
                        override fun hasGesture() = false
                        override fun getMethod() = "GET"
                        override fun getRequestHeaders(): Map<String, String> = emptyMap()
                    }
                    assertFalse(url, host!!.webView.webViewClient.shouldOverrideUrlLoading(host!!.webView, request))
                }
            }
            assertTrue(routed.isEmpty())
            instrumentation.runOnMainSync {
                val hasHistory = host!!.webView.canGoBack()
                evidence.put("webHistoryAvailable", hasHistory)
                when (navigation.onBack(hasHistory)) {
                    BackResult.GoWebBack -> host!!.webView.goBack()
                    BackResult.Handled -> Unit
                    BackResult.Finish -> fail("Login should return to its source")
                }
            }
            val returned = poll("JSON.stringify({host:location.hostname,path:location.pathname,ready:document.readyState==='complete'})") {
                it.optString("host") == "m.youtube.com" && it.optString("path") == "/" && it.optBoolean("ready")
            }
            assertEquals("m.youtube.com", returned.optString("host"))
            assertEquals("/", returned.optString("path"))
            evidence.put("returnedToSource", true)
            File(instrumentation.targetContext.filesDir, "upgrade-login-webview.json").writeText(evidence.toString(2))
        } finally { close() }
    }

    @Test fun watchPromotionsStayHiddenAcrossLanguages() {
        assumeTrue("Supply -e network=1 for live-web login acceptance",
            InstrumentationRegistry.getArguments().getString("network") == "1")
        val records = JSONArray()
        var originalPreference: String? = null
        instrumentation.runOnMainSync {
            originalPreference = CookieManager.getInstance().getCookie("https://m.youtube.com")
                ?.split(';')?.map(String::trim)?.firstOrNull { it.startsWith("PREF=") }
        }
        fun setPreference(value: String) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie("https://m.youtube.com", "$value; Domain=.youtube.com; Path=/; Secure") { latch.countDown() }
            }
            assertTrue(latch.await(4, TimeUnit.SECONDS))
        }
        try {
            val languages = listOf("en", "zh-CN", "zh-TW", "ja", "ko", "es", "de", "fr", "ar")
            val base = "https://m.youtube.com/watch?v=bJkVNDRksDY&hl="
            setPreference("PREF=hl=${languages.first()}")
            create(base + languages.first())
            for ((index, language) in languages.withIndex()) {
                if (index > 0) {
                    setPreference("PREF=hl=$language")
                    instrumentation.runOnMainSync { host!!.webView.loadUrl(base + language, mapOf("Accept-Language" to language)) }
                }
                val loaded = poll("""JSON.stringify({requested:new URL(location.href).searchParams.get('hl'),lang:document.documentElement.lang,
                    ready:document.readyState==='complete'&&!!document.querySelector('ytm-mobile-topbar-renderer')&&!document.getElementById('lite-rule-probe'),
                    rules:!!document.getElementById('lite-ad-rules')})""") {
                    it.optString("lang").substringBefore("-") == language.substringBefore("-") && it.optBoolean("ready") && it.optBoolean("rules")
                }
                assertTrue("Watch page did not load for $language: $loaded", loaded.optBoolean("ready"))
                assertEquals("Page locale did not change: $loaded", language.substringBefore("-"), loaded.optString("lang").substringBefore("-"))
                val evidence = evaluate("""JSON.stringify((function(){
                    var header=document.querySelector('ytm-mobile-topbar-renderer');
                    var promotions=Array.from(header.querySelectorAll('a')).filter(function(x){return (x.getAttribute('href')||'').indexOf('package=com.google.android.youtube;')>=0;});
                    var account=header.querySelector('ytm-menu button');
                    var search=header.querySelector('.topbar-button-search-button');
                    var probe=document.createElement('div');probe.id='lite-rule-probe';
                    var prompt=document.createElement('button');prompt.className='ytp-unmute';prompt.textContent='🔇';probe.appendChild(prompt);
                    var volume=document.createElement('button');volume.className='ytp-mute-button';volume.textContent='🔊';probe.appendChild(volume);
                    var ad=document.createElement('ytm-ad-slot-renderer');ad.textContent='…';probe.appendChild(ad);document.body.appendChild(probe);
                    return {lang:document.documentElement.lang,promotions:promotions.map(function(x){return {label:x.getAttribute('aria-label'),display:getComputedStyle(x).display};}),
                        unmuteHidden:getComputedStyle(prompt).display==='none',adHidden:getComputedStyle(ad).display==='none',
                        volumeVisible:getComputedStyle(volume).display!=='none',accountVisible:!!account&&getComputedStyle(account).display!=='none',
                        searchVisible:!!search&&getComputedStyle(search).display!=='none'};
                })())""")
                records.put(evidence.put("requestedLanguage", language))
                assertTrue(evidence.toString(), evidence.optBoolean("unmuteHidden"))
                assertTrue(evidence.toString(), evidence.optBoolean("adHidden"))
                assertTrue(evidence.toString(), evidence.optBoolean("volumeVisible"))
                assertTrue(evidence.toString(), evidence.optBoolean("accountVisible"))
                assertTrue(evidence.toString(), evidence.optBoolean("searchVisible"))
                val promotions = evidence.getJSONArray("promotions")
                assertTrue("No application promotion available for $language", promotions.length() > 0)
                for (i in 0 until promotions.length()) assertEquals("none", promotions.getJSONObject(i).getString("display"))
            }
        } finally {
            File(instrumentation.targetContext.filesDir, "upgrade-watch-language-promotions.json").writeText(records.toString(2))
            try { setPreference(originalPreference ?: "PREF=; Max-Age=0") } finally { close() }
        }
    }
}
