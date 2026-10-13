@file:OptIn(UnstableApi::class)
package com.hhst.youtubelite.browser

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import android.webkit.WebView
import org.json.JSONObject
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.diagnostics.BrowserDiagnostics
import com.hhst.youtubelite.player.QueueRepository
import com.hhst.youtubelite.player.engine.OfflineWatchFixture
import com.hhst.youtubelite.extension.PreferenceKeys
import com.hhst.youtubelite.ui.browser.BrowserViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.koin.core.context.GlobalContext

class TransientUiAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun modalInteractionPreservesPlayback() {
        OfflineWatchFixture().use { fixture ->
            fixture.launch { compose.waitForIdle() }
            lateinit var browser: BrowserViewModel
            lateinit var page: WebView
            fixture.instrumentation.runOnMainSync {
                browser = ViewModelProvider(fixture.activity)[BrowserViewModel::class.java]
                page = fixture.webView()
                page.stopLoading()
                page.loadDataWithBaseURL(fixture.url, html, "text/html", "UTF-8", fixture.url)
            }
            val probe = WebProbe { page }
            probe.waitFor("return {ready:!!window.fixtureReady && !!window.__watchAutoplayNext && !!Lite.ui}")
            val owner = browser.uiState.value.activeId
            fun tap(selector: String, y: Int? = null) {
                val vertical = y?.toString() ?: "r.top+r.height/2"
                val point = probe.evaluate("""
                    const r=document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect();
                    return {x:r.left+r.width/2,y:$vertical,viewport:innerWidth};
                """.trimIndent())
                val screen = IntArray(2)
                var width = 0
                fixture.instrumentation.runOnMainSync { page.getLocationOnScreen(screen); width = page.width }
                val scale = width / point.getDouble("viewport")
                DeviceEvidence.shell("input tap ${screen[0] + (point.getDouble("x") * scale).toInt()} ${screen[1] + (point.getDouble("y") * scale).toInt()}")
            }
            fun open() {
                tap("#more")
                probe.waitFor("return {ready:Lite.ui.kind()==='media_menu' && !!document.querySelector('[data-injected=queue-item]')}")
                try {
                    compose.waitUntil(10_000) { compose.onAllNodesWithTag("web-modal", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("web-modal", useUnmergedTree = true).assertIsDisplayed()
                } catch (failure: Throwable) {
                    val state = probe.evaluate("return {kind:Lite.ui.kind(),generation:Bridge.currentDocumentGeneration(),url:location.href}")
                        .put("visible", fixture.model.uiState.value.visible)
                        .put("mini", fixture.model.uiState.value.mini)
                        .put("fullscreen", fixture.model.uiState.value.fullscreen)
                        .put("originTab", BrowserDiagnostics.context(page)?.tabId)
                        .put("activeTab", browser.uiState.value.activeId)
                        .put("nodes", compose.onRoot(useUnmergedTree = true).printToString())
                    DeviceEvidence.writeJson("modal-state.json", state.toString())
                    DeviceEvidence.captureScene("modal-state")
                    throw failure
                }
            }
            fun closed() {
                probe.waitFor("""
                    const kind=Lite.ui.kind(), overflow=document.body.style.overflow, hidden=document.getElementById('sheet').hidden;
                    return {ready:kind==='none' && overflow==='' && hidden,kind,overflow,hidden};
                """.trimIndent())
                assertEquals(owner, browser.uiState.value.activeId)
                assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
                assertTrue(fixture.engine.snapshot.value.isPlaying)
                assertFalse(fixture.model.uiState.value.fullscreen)
            }
            repeat(2) { open(); tap("#movie_player"); closed() }
            open(); tap(".bottom-sheet-overlay", 350); closed()
            open(); tap("ytm-menu-service-item-renderer:not([data-injected]) #save")
            probe.waitFor("return {ready:window.saved===1 && window.menuTouchEnds>0 && Lite.ui.kind()==='media_menu'}")
            DeviceEvidence.shell("input keyevent KEYCODE_BACK"); closed()
            open(); tap("[data-injected=queue-item] button"); closed()
            assertTrue(GlobalContext.get().get<QueueRepository>().state.value.items.any { it.videoId == "bbbbbbbbbbb" })
            for (selector in listOf("#like", "#dislike", "#comment")) tap(selector)
            probe.waitFor("return {ready:window.actions===3}")
            val trustedEndsBeforeOverlap = probe.evaluate("return {count:window.menuTrustedEnds}").getInt("count")
            probe.evaluate("document.querySelector('.menu-content').style.top='110px';document.querySelector('.menu-content').style.bottom='auto';return {}")
            open(); tap("ytm-menu-service-item-renderer:not([data-injected]) #save")
            probe.waitFor("return {ready:window.saved===2 && window.menuTrustedEnds>$trustedEndsBeforeOverlap && Lite.ui.kind()==='media_menu'}")
            fixture.instrumentation.waitForIdleSync()
            DeviceEvidence.capture("menu-overlap.png")
            DeviceEvidence.shell("input keyevent KEYCODE_BACK"); closed()
            open(); tap("[data-injected=queue-item] button"); closed()
            for (back in listOf("history.back()", "history.go(-1)")) {
                probe.evaluate("window.searchBack=${JSONObject.quote(back)};return {}")
                tap("#search")
                probe.waitFor("return {ready:Lite.ui.kind()==='search'}")
                tap(".searchbox-back-button")
                probe.waitFor("return {ready:Lite.ui.kind()==='none' && location.hash===''}")
                assertEquals(owner, browser.uiState.value.activeId)
                assertEquals(fixture.id, fixture.engine.snapshot.value.videoId)
            }
            repeat(2) {
                probe.evaluate("document.getElementById('promotion').hidden=false;document.body.style.overflow='hidden';Lite.wake();return {}")
                probe.waitFor("return {ready:document.getElementById('promotion').hidden && document.body.style.overflow===''}")
                assertEquals(owner, browser.uiState.value.activeId)
                assertTrue(fixture.engine.snapshot.value.isPlaying)
            }
            assertFalse(page.isVerticalScrollBarEnabled)
            assertFalse(page.isHorizontalScrollBarEnabled)
            val next = probe.evaluate("return window.__watchAutoplayNext()")
            assertTrue(next.getBoolean("enabled"))
            assertEquals("https://m.youtube.com/watch?v=bbbbbbbbbbb", next.getString("url"))
            probe.evaluate("document.getElementById('autoplay').setAttribute('aria-checked','false');return {}")
            assertFalse(probe.evaluate("return window.__watchAutoplayNext()").getBoolean("enabled"))
            assertFalse(probe.evaluate("return {advanced:window.__watchAutoplayAdvance('${fixture.id}','https://m.youtube.com/watch?v=bbbbbbbbbbb')}").getBoolean("advanced"))
            probe.evaluate("window.importKeys=[];addEventListener('preferencesChanged',e=>window.importKeys.push(e.detail.key));return {}")
            fixture.instrumentation.runOnMainSync {
                fixture.prefs.importPreferences(mapOf(PreferenceKeys.ENABLE_PIP to false), 42)
            }
            fixture.await { !fixture.model.uiState.value.pipAvailable }
            probe.waitFor("return {ready:window.importKeys.includes('*') && Lite.prefs().enable_pip===false}")
        }
    }

    private val html = """
        <html><head><meta name=viewport content="width=device-width,initial-scale=1">
        <style>
          body{margin:0} [hidden]{display:none!important} header{height:64px}
          #movie_player{height:200px;background:#222}
          .watch-below-the-player{height:600px} button{min-height:48px;min-width:65px}
          bottom-sheet-layout{display:block;position:fixed;inset:0;z-index:100}
          .bottom-sheet-overlay{position:absolute;inset:0;background:#0008}
          .menu-content{position:absolute;left:10px;right:10px;bottom:10px;background:white;padding:10px}
          ytm-menu-service-item-renderer{display:block} .menu-content button{width:100%;height:55px}
          .searchbox{position:fixed;inset:0;z-index:101;background:#fff}
        </style></head><body>
        <header><button id=search onclick="history.pushState({},'', '#searching');document.getElementById('searchbox').hidden=false;document.body.style.overflow='hidden'">Search</button></header>
        <div id=movie_player class=html5-video-player></div>
        <div class=watch-below-the-player>
          <button id=like>Like</button><button id=dislike>Dislike</button><button id=comment>Comment</button>
          <ytm-media-item><div class=media-item-info><div class=media-item-metadata>
            <a href="/watch?v=bbbbbbbbbbb"><h3 class=media-item-headline>Next video</h3></a></div>
            <button id=more class=media-item-menu onclick="document.getElementById('sheet').hidden=false;document.body.style.overflow='hidden'">More</button>
          </div></ytm-media-item>
          <bottom-sheet-layout id=sheet hidden>
            <div class=bottom-sheet-overlay onclick="closeMenu()"></div>
            <div class=menu-content role=dialog>
              <ytm-menu-service-item-renderer><button id=save class=menu-item-button onclick="window.saved++"><span class=yt-core-attributed-string>Watch later</span></button></ytm-menu-service-item-renderer>
            </div>
          </bottom-sheet-layout>
        </div>
        <div id=searchbox class=searchbox hidden><button class=searchbox-back-button onclick="eval(window.searchBack)">Back</button><input type=search></div>
        <button id=autoplay class=ytp-autonav-toggle-button aria-checked=true></button>
        <div id=promotion role=dialog hidden style="position:fixed;inset:20px;z-index:102;background:white">
          <a href="intent://watch#Intent;package=com.google.android.youtube;end">Open app</a>
          <button class=dialog-close-button onclick="document.getElementById('promotion').hidden=true;document.body.style.overflow=''">Close</button>
        </div>
        <script>
          window.saved=0;window.actions=0;window.menuTouchEnds=0;window.menuTrustedEnds=0;
          function closeMenu(){document.getElementById('sheet').hidden=true;document.body.style.overflow=''}
          window.addEventListener('popstate',()=>{if(!location.hash){document.getElementById('searchbox').hidden=true;document.body.style.overflow=''}});
          document.addEventListener('touchend', e=>{
            if(e.target.closest('#like,#dislike,#comment')) window.actions++;
            if(e.target.closest('.menu-content')) { window.menuTouchEnds++; if(e.isTrusted) window.menuTrustedEnds++; }
          });
          window.ytInitialData={currentVideoEndpoint:{watchEndpoint:{videoId:'menuTest001'}},contents:{singleColumnWatchNextResults:{autoplay:{autoplay:{autoplaySets:[{autoplayVideo:{watchEndpoint:{videoId:'bbbbbbbbbbb'}}}]}}}}};
          window.fixtureReady=true;
        </script></body></html>
    """.trimIndent()
}
