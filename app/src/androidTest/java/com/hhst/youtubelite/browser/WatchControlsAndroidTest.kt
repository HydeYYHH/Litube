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

class WatchControlsAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var view: WebView? = null

    private val probe get() = WebProbe { view!! }
    private fun evaluate(source: String) = probe.evaluate(source)
    private fun waitFor(source: String) = probe.waitFor(source)

    @SuppressLint("SetJavaScriptEnabled")
    private fun fixture(body: String) {
        val provider = WebViewCompat.getCurrentWebViewPackage(instrumentation.targetContext)?.versionName.orEmpty()
        assumeTrue(provider.substringBefore('.').toIntOrNull()?.let { it >= 80 } == true)
        activity.scenario.onActivity { context ->
            view = WebView(context).apply { settings.javaScriptEnabled = true }
            context.setContentView(view)
            view!!.loadDataWithBaseURL("https://m.youtube.com/watch?v=abcdefghijk", "<html lang=en><body>$body</body></html>", "text/html", "UTF-8", null)
        }
        waitFor("return {ready:!!window.fixtureReady}")
    }

    private fun inject(vararg assets: String) {
        assets.forEach { name ->
            val body = instrumentation.targetContext.assets.open("script/$name").bufferedReader().use { it.readText() }
            instrumentation.runOnMainSync { view!!.evaluateJavascript(body, null) }
        }
    }

    private fun destroy() {
        instrumentation.runOnMainSync { view?.let { it.loadUrl("about:blank"); it.destroy() } }
    }

    @Test fun dislikeKeepsGlyphWhileFetchingFailingDisablingAndRebinding() {
        try {
            fixture("""
                <div class="slim-video-action-bar-actions"><like-button-view-model><button><svg><path/></svg><span class="button-renderer-text">12</span></button></like-button-view-model>
                <dislike-button-view-model><button style="width:48px;position:relative"><span role="text"><svg width="24" height="24"><path/></svg></span></button></dislike-button-view-model></div>
                <script>window.prefs={enable_display_dislikes:true,enable_show_likes:true};window.pending=[];
                window.Bridge={getPreferences:()=>JSON.stringify(prefs)};window.fetch=()=>new Promise(resolve=>pending.push(resolve));window.fixtureReady=true;</script>
            """.trimIndent())
            inject("core.js", "display_dislikes.js")
            waitFor("return {ready:pending.length===1}")
            val before = evaluate("const b=document.querySelector('dislike-button-view-model button');return {glyph:!!b.querySelector('svg'),width:b.style.width,own:!!b.querySelector('[data-lite-vote-count]'),tap:getComputedStyle(document.documentElement).webkitTapHighlightColor}")
            assertTrue(before.getBoolean("glyph"))
            assertFalse(before.getBoolean("own"))
            assertEquals("48px", before.getString("width"))
            assertEquals("rgba(0, 0, 0, 0)", before.getString("tap"))
            evaluate("pending[0]({ok:false});return {}")
            evaluate("history.replaceState({},'','/watch?v=lmnopqrstuv');return {}")
            waitFor("return {ready:pending.length===2}")
            evaluate("pending[1]({ok:true,json:()=>Promise.resolve({likes:1234,dislikes:8})});return {}")
            waitFor("return {ready:document.querySelector('[data-lite-vote-count]')?.textContent==='8'}")
            val loaded = evaluate("const b=document.querySelector('dislike-button-view-model button');return {glyph:!!b.querySelector('svg'),width:b.getBoundingClientRect().width,min:b.style.minWidth,like:document.querySelector('.button-renderer-text').textContent}")
            assertTrue(loaded.getBoolean("glyph"))
            assertTrue(loaded.getDouble("width") >= 48)
            assertEquals("48px", loaded.getString("min"))
            assertEquals("1,234", loaded.getString("like"))
            evaluate("prefs.enable_display_dislikes=false;prefs.enable_show_likes=false;dispatchEvent(new CustomEvent('preferencesChanged',{detail:{key:'*'}}));return {}")
            val restored = evaluate("const b=document.querySelector('dislike-button-view-model button');return {glyph:!!b.querySelector('svg'),width:b.style.width,min:b.style.minWidth,own:!!b.querySelector('[data-lite-vote-count]'),like:document.querySelector('.button-renderer-text').textContent}")
            assertTrue(restored.getBoolean("glyph")); assertFalse(restored.getBoolean("own"))
            assertEquals("48px", restored.getString("width")); assertEquals("", restored.getString("min"))
            assertEquals("12", restored.getString("like"))
            evaluate("prefs.enable_display_dislikes=true;dispatchEvent(new CustomEvent('preferencesChanged',{detail:{key:'*'}}));return {}")
            waitFor("return {ready:document.querySelector('[data-lite-vote-count]')?.textContent==='8'}")
            evaluate("const old=document.querySelector('dislike-button-view-model');const next=document.createElement('dislike-button-view-model');next.innerHTML='<button aria-pressed=false style=width:48px><svg width=24 height=24><path/></svg></button>';old.replaceWith(next);dispatchEvent(new Event('yt-navigate-finish'));return {}")
            waitFor("return {ready:document.querySelector('[data-lite-vote-count]')?.textContent==='8'}")
            evaluate("document.querySelector('dislike-button-view-model button').click();return {}")
            waitFor("return {ready:document.querySelector('[data-lite-vote-count]')?.textContent==='9'}")
            assertTrue(evaluate("return {glyph:!!document.querySelector('dislike-button-view-model svg')}").getBoolean("glyph"))
        } finally { destroy() }
    }

    @Test fun settingsClosesSourceSheetAndRestoredWatchCanOpenAndDismissIt() {
        try {
            fixture("""
                <button id=more>More</button><div id=sheet hidden><a href=/select_site>Settings</a></div>
                <script>window.opened=[];window.hashEvents=[];window.Bridge={openTab:url=>opened.push(url)};
                const sheet=document.getElementById('sheet');const sync=()=>sheet.hidden=location.hash!=='#bottom-sheet';
                document.getElementById('more').onclick=()=>location.hash='#bottom-sheet';
                addEventListener('hashchange',e=>{hashEvents.push([e.oldURL,e.newURL]);sync()});window.fixtureReady=true;</script>
            """.trimIndent())
            inject("nav.js")
            evaluate("document.getElementById('more').click();return {}")
            waitFor("return {ready:!document.getElementById('sheet').hidden}")
            val result = evaluate("document.querySelector('#sheet a').click();return {hidden:document.getElementById('sheet').hidden,hash:location.hash,opened:opened[0],old:hashEvents[hashEvents.length-1][0],next:hashEvents[hashEvents.length-1][1]}")
            assertTrue(result.getBoolean("hidden")); assertEquals("", result.getString("hash"))
            assertEquals("https://m.youtube.com/select_site", result.getString("opened"))
            assertTrue(result.getString("old").endsWith("#bottom-sheet"))
            assertFalse(result.getString("next").contains("#bottom-sheet"))
            evaluate("document.getElementById('more').click();return {}")
            waitFor("return {ready:!document.getElementById('sheet').hidden}")
            evaluate("location.hash='';return {}")
            waitFor("return {ready:document.getElementById('sheet').hidden}")
        } finally { destroy() }
    }
}
