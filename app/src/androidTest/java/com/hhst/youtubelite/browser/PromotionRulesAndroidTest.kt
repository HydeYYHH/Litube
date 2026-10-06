package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PromotionRulesAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var view: WebView? = null
    private fun evaluate(script: String): JSONObject {
        val value = AtomicReference("{}")
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { view!!.evaluateJavascript(script) { value.set(it ?: "{}"); latch.countDown() } }
        assertTrue(latch.await(4, TimeUnit.SECONDS))
        return runCatching { JSONObject(JSONTokener(value.get()).nextValue().toString()) }.getOrDefault(JSONObject())
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun structuralRulesCoverWatchAndShortsAcrossLanguagesWithoutTheScheduler() {
        val languages = listOf("en", "zh-CN", "zh-TW", "ja", "ko", "es", "de", "fr", "pt-BR", "ar", "ru", "hi", "th", "vi", "tr", "id")
        val records = JSONArray()
        try {
            activity.scenario.onActivity { context ->
                view = WebView(context).apply { settings.javaScriptEnabled = true }
                context.setContentView(view)
                view!!.loadDataWithBaseURL("https://m.youtube.com/watch?v=fixture", """
                    <html><body><ytm-mobile-topbar-renderer>
                    <a id="app" href="intent://watch#Intent;package=com.google.android.youtube;end">…</a>
                    <a id="normal" href="intent://accounts.google.com#Intent;package=com.android.chrome;end">…</a>
                    <a id="lookalike" href="intent://watch#Intent;package=com.google.android.youtube.fake;end">…</a>
                    <button id="account">…</button></ytm-mobile-topbar-renderer>
                    <button id="prompt" class="ytp-unmute">…</button>
                    <button id="volume" class="ytp-mute-button">…</button>
                    <ytm-ad-slot-renderer id="ad">…</ytm-ad-slot-renderer>
                    <div role="dialog"><yt-list-item-view-model id="menu-app">
                    <a href="intent://shorts#Intent;package=com.google.android.youtube;end">…</a></yt-list-item-view-model>
                    <yt-list-item-view-model id="menu-normal"><button>…</button></yt-list-item-view-model></div></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            val deadline = System.currentTimeMillis() + 10_000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                ready = evaluate("JSON.stringify({ready:!!document.getElementById('prompt')})").optBoolean("ready")
                if (!ready) Thread.sleep(100)
            }
            assertTrue(ready)
            val body = instrumentation.targetContext.assets.open("script/ads.js").bufferedReader().use { it.readText() }
            instrumentation.runOnMainSync { view!!.evaluateJavascript(body, null) }
            for (path in listOf("/watch?v=fixture", "/shorts/fixture")) {
                for (language in languages) {
                    val state = evaluate("""JSON.stringify((function(){
                        history.replaceState({},'',${JSONObject.quote(path)});document.documentElement.lang=${JSONObject.quote(language)};
                        ['app','normal','lookalike','account','prompt','volume','ad'].forEach(function(id){document.getElementById(id).textContent=${JSONObject.quote(language)};});
                        var hidden=function(id){return getComputedStyle(document.getElementById(id)).display==='none';};
                        return {lang:document.documentElement.lang,path:location.pathname,script:!!window.__ads,scheduler:!!window.Lite,
                            appHidden:hidden('app'),menuAppHidden:hidden('menu-app'),menuNormalVisible:!hidden('menu-normal'),unmuteHidden:hidden('prompt'),adHidden:hidden('ad'),
                            normalVisible:!hidden('normal'),lookalikeVisible:!hidden('lookalike'),accountVisible:!hidden('account'),volumeVisible:!hidden('volume'),
                            cssRules:document.getElementById('lite-ad-rules').sheet.cssRules.length};})())""")
                    records.put(state)
                    assertTrue(state.toString(), state.optBoolean("script"))
                    assertFalse(state.optBoolean("scheduler"))
                    for (key in listOf("appHidden", "menuAppHidden", "menuNormalVisible", "unmuteHidden", "adHidden", "normalVisible", "lookalikeVisible", "accountVisible", "volumeVisible"))
                        assertTrue("$language $path $key: $state", state.optBoolean(key))
                    assertTrue(state.optInt("cssRules") >= 2)
                }
            }
        } finally {
            val result = JSONObject().put("provider", WebView.getCurrentWebViewPackage()?.versionName).put("cases", records)
            File(instrumentation.targetContext.filesDir, "upgrade-language-rules.json").writeText(result.toString(2))
            instrumentation.runOnMainSync { view?.let { it.loadUrl("about:blank"); it.stopLoading(); it.destroy() } }
        }
    }
}
