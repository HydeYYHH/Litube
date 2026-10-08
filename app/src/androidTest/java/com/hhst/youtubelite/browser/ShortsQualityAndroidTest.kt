package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ShortsQualityAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var view: WebView? = null

    private val probe get() = WebProbe { view!! }
    private fun evaluate(source: String) = probe.evaluate(source)
    private fun waitFor(source: String) = probe.waitFor(source)

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun qualityMenuWorksInSheetsAndWideDialogsAndKeepsNativeGestures() {
        val provider = WebViewCompat.getCurrentWebViewPackage(instrumentation.targetContext)?.versionName.orEmpty()
        assumeTrue(provider.substringBefore('.').toIntOrNull()?.let { it >= 80 } == true)
        try {
            activity.scenario.onActivity { context ->
                view = WebView(context).apply { settings.javaScriptEnabled = true }
                context.setContentView(view)
                view!!.loadDataWithBaseURL("https://m.youtube.com/shorts/fixture", """
                    <html lang="en"><style>bottom-sheet-layout{display:contents}ytm-menu-service-item-renderer,yt-list-item-view-model{display:block}</style><body><div id="movie_player" class="html5-video-player">
                    <video style="width:320px;height:240px"></video></div>
                    <bottom-sheet-layout><div id="sheet"><ytm-menu-service-item-renderer>
                    <button><c3-icon style="margin-right:16px"><svg><path></path></svg></c3-icon>
                    <span class="menu-item-text">Description</span></button></ytm-menu-service-item-renderer></div></bottom-sheet-layout>
                    <div class="menu-content" role="dialog" id="wide"><yt-list-item-view-model>
                    <div class="ytListItemViewModelLeading"><c3-icon><svg><path></path></svg></c3-icon></div>
                    <button><span class="ytListItemViewModelTitle" role="text">Description</span></button></yt-list-item-view-model></div>
                    <script>window.applied=[];window.available=['hd720','small'];
                    const p=document.getElementById('movie_player');p.getAvailableQualityLevels=()=>available;
                    p.setPlaybackQualityRange=(min,max)=>applied.push([min,max]);
                    document.querySelector('video').playbackRate=2;
                    </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            waitFor("return {ready:!!window.applied}")
            for (asset in listOf("core.js", "shorts.js", "shorts-quality.js")) {
                val body = instrumentation.targetContext.assets.open("script/$asset").bufferedReader().use { it.readText() }
                instrumentation.runOnMainSync { view!!.evaluateJavascript(body, null) }
            }
            waitFor("return {ready:document.querySelectorAll('[data-injected=shorts-quality]').length===2}")
            evaluate("document.querySelector('#sheet [data-injected] button').click();return {}")
            val options = waitFor("return {ready:document.querySelectorAll('[data-quality]').length===3,labels:Array.from(document.querySelectorAll('[data-quality]')).map(n=>n.textContent).join(','),speed:document.querySelector('video').playbackRate}")
            assertEquals("Auto,720p,240p", options.getString("labels"))
            assertEquals(2.0, options.getDouble("speed"), 0.0)
            evaluate("document.querySelector('[data-quality=hd720]').click();return {}")
            val applied = evaluate("return {quality:applied[0].join(','),closed:!document.querySelector('[data-quality]'),description:document.querySelector('#sheet').firstElementChild.style.display}")
            assertEquals("hd720,hd720", applied.getString("quality"))
            assertTrue(applied.getBoolean("closed"))
            assertEquals("", applied.getString("description"))
            evaluate("document.documentElement.lang='ar';return {}")
            waitFor("return {ready:document.querySelector('#wide [data-injected] [role=text]').textContent===Lite.text('quality')}")
            evaluate("document.querySelector('#wide [data-injected] button').click();return {}")
            evaluate("document.querySelector('[data-quality=default]').click();return {}")
            assertEquals("default,default", evaluate("return {last:applied[applied.length-1].join(',')}").getString("last"))
            evaluate("document.querySelector('#wide [data-injected] button').click();history.replaceState({},'','/shorts/next');Lite.wake();return {}")
            waitFor("return {ready:!document.querySelector('[data-quality]')}")
            evaluate("window.available=[];document.querySelector('#wide [data-injected] button').click();return {}")
            assertTrue(evaluate("return {empty:document.querySelectorAll('[data-quality]').length===0,unavailable:document.querySelector('[data-injected=shorts-quality-panel]').textContent.includes(Lite.text('unavailable'))}").getBoolean("unavailable"))
        } finally {
            instrumentation.runOnMainSync { view?.let { it.loadUrl("about:blank"); it.destroy() } }
        }
    }
}
