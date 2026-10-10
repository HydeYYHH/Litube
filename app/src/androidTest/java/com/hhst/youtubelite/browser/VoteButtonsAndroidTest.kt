package com.hhst.youtubelite.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.Constants
import com.hhst.youtubelite.core.DeviceEvidence
import com.hhst.youtubelite.core.WebViewTimerHandle
import com.hhst.youtubelite.core.WebViewTimerOccupancy
import com.hhst.youtubelite.core.WebViewTimerOwner
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.net.PageScript
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WebView layout and hydration, including the injected download/queue chips. */
class VoteButtonsAndroidTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var view: WebView? = null
    private var timerHandle: WebViewTimerHandle? = null
    private val probe get() = WebProbe { view!! }
    private val assets = listOf("core.js", "player-hook.js", "display_dislikes.js", "download.js")
    private val script get() = assets.joinToString("\n") { name ->
        instrumentation.targetContext.assets.open("script/$name").bufferedReader().use { it.readText() }
    }
    private fun activate() = instrumentation.runOnMainSync {
        val timers = GlobalContext.get().get<WebViewTimerOccupancy>()
        timers.attach(view!!)
        timerHandle = timers.acquire(WebViewTimerOwner.BROWSER)
        view!!.onResume()
    }
    private fun destroy() = instrumentation.runOnMainSync {
        view?.stopLoading()
        timerHandle?.release()
        timerHandle = null
        view?.destroy()
    }

    private val metrics = """
        const row=document.querySelector('.slim-video-action-bar-actions');
        const votes=['like','dislike'].map(kind=>{
          const host=row?.querySelector(kind+'-button-view-model'),button=host?.querySelector('button');
          const icon=button?.querySelector('c3-icon,yt-icon')||button?.querySelector('svg'),count=host?.querySelector('[data-lite-vote-count]');
          const painted=Array.from(icon?.querySelectorAll('path,use,rect,circle,polygon')||[])
            .filter(n=>!n.closest('defs,clipPath,mask,pattern')&&getComputedStyle(n).visibility==='visible')
            .map(n=>n.getBoundingClientRect()).filter(r=>r.width>0&&r.height>0);
          const g=painted.length?{left:Math.min(...painted.map(r=>r.left)),right:Math.max(...painted.map(r=>r.right)),
            top:Math.min(...painted.map(r=>r.top)),bottom:Math.max(...painted.map(r=>r.bottom))}:null;
          if(g){g.width=g.right-g.left;g.height=g.bottom-g.top;}
          const r=button?.getBoundingClientRect(),c=count?.getBoundingClientRect();
          let clippedBy=null;
          for(let n=button;n&&n!==row;n=n.parentElement){
            const s=getComputedStyle(n),b=n.getBoundingClientRect();
            if([g,c].some(v=>v&&((s.overflowX!=='visible'&&(v.left<b.left-1||v.right>b.right+1))||
              (s.overflowY!=='visible'&&(v.top<b.top-1||v.bottom>b.bottom+1))))) {clippedBy=n.tagName+'.'+n.className;break;}
          }
          return {kind,width:r?.width||0,height:r?.height||0,glyphWidth:g?.width||0,glyphHeight:g?.height||0,
            unclipped:!!r&&!clippedBy&&button.scrollWidth<=button.clientWidth+2&&(!c||c.right<=r.right+1),
            clippedBy,count:count?.textContent};
        });
        const entries=Array.from(row?.querySelectorAll('[data-injected="entry"]')||[]);
        const rects=entries.map(el=>el.querySelector('button').getBoundingClientRect());
        const next=entries.at(-1)?.nextElementSibling;
        const nextRect=(next?.querySelector('button,a')||next)?.getBoundingClientRect();
        return {ready:votes.every(v=>v.width>24&&v.glyphWidth>=16),votes,url:location.href,title:document.title,
          scrollable:!!row&&getComputedStyle(row).overflowX==='auto',
          actionGaps:rects.slice(1).map((r,i)=>r.left-rects[i].right),
          plainActions:entries.every(el=>[el,el.querySelector('button')].every(n=>{
            const s=getComputedStyle(n);
            return ['transparent','rgba(0, 0, 0, 0)'].includes(s.backgroundColor)&&s.boxShadow==='none'&&s.filter==='none';
          })),
          nativeActionGap:nextRect&&rects.length?nextRect.left-rects.at(-1).right:null,
          injected:document.querySelectorAll('[data-injected="entry"]').length};
    """.trimIndent()

    private fun assertActionSpacing(state: org.json.JSONObject) {
        assertTrue("Injected actions must have no fixed background or shadow: $state", state.getBoolean("plainActions"))
        val gaps = state.getJSONArray("actionGaps")
        assertEquals(2, gaps.length())
        repeat(gaps.length()) { assertTrue("Injected action backgrounds touch: $state", gaps.getDouble(it) >= 7) }
        if (!state.isNull("nativeActionGap"))
            assertTrue("Injected and native action backgrounds touch: $state", state.getDouble("nativeActionGap") >= 7)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun lateHydrationAndFortyRendererUpdatesKeepBothButtonsAndCountsComplete() {
        try {
            activity.scenario.onActivity { context ->
                view = WebView(context).apply { settings.javaScriptEnabled = true }
                context.setContentView(view)
                view!!.loadDataWithBaseURL("https://m.youtube.com/watch?v=abcdefghijk", """
                  <html><head><meta name=viewport content="width=device-width,initial-scale=1"><style>
                  body{margin:0;background:#0f0f0f;color:white;font:14px sans-serif}
                  .slim-video-action-bar-actions{display:flex;width:100%;overflow:hidden;gap:8px}
                  like-button-view-model,dislike-button-view-model{display:flex;flex:1;min-width:0}
                  button-view-model{display:flex}button{display:flex;align-items:center;justify-content:center;
                    width:36px;height:36px;overflow:hidden;border:0;border-radius:18px;color:white;background:#272727;padding:0 8px}
                  c3-icon,svg{width:24px;height:24px;display:inline-flex;flex-shrink:0;fill:currentColor}
                  .ytSpecButtonShapeNextContent{display:flex;align-items:center;width:100%;flex:1}
                  .segmented-buttons-wrapper{display:flex;width:36px;max-width:36px;overflow:hidden}
                  .segmented-buttons{display:flex}
                  </style></head><body><div class=slim-video-action-bar-actions>
                  <button style="width:110px;flex-shrink:0">Subscribe</button>
                  <segmented-like-dislike-button-view-model><div class=segmented-buttons-wrapper><div class=segmented-buttons>
                  <like-button-view-model></like-button-view-model><dislike-button-view-model></dislike-button-view-model>
                  </div></div></segmented-like-dislike-button-view-model>
                  <button-view-model class=ytSpecButtonViewModelHost><button><c3-icon><svg viewBox="0 0 24 24"><path d="M2 2H22V22H2Z"/></svg></c3-icon></button></button-view-model>
                  </div><script>
                  window.prefs={enable_show_likes:true,enable_display_dislikes:true};
                  window.Bridge={getPreferences:()=>JSON.stringify(prefs)};window.Download={postMessage:()=>{}};
                  window.fetch=async()=>({ok:true,json:async()=>({likes:7535,dislikes:246})});
                  window.renderVotes=replace=>{
                    for(const kind of ['like','dislike']){
                      let host=document.querySelector(kind+'-button-view-model');
                      if(replace){const next=document.createElement(kind+'-button-view-model');host.replaceWith(next);host=next}
                      host.innerHTML='<button-view-model><button><div class="ytSpecButtonShapeNextContent"><div id="text"><c3-icon></c3-icon><span role="text">native-'+kind+'</span></div></div></button></button-view-model>';
                      setTimeout(()=>{const icon=host.querySelector('c3-icon');if(icon)icon.innerHTML='<svg viewBox="0 0 24 24"><path d="M2 2H22V22H2Z"/></svg>'},30);
                    }
                  };renderVotes(false);window.fixtureReady=true;
                  </script></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
            activate()
            probe.waitFor("return {ready:!!window.fixtureReady}")
            instrumentation.runOnMainSync { view!!.evaluateJavascript(script, null) }
            repeat(41) { cycle ->
                if (cycle > 0) probe.evaluate("renderVotes(${cycle % 2 == 0});return {}")
                if (cycle % 3 == 2) {
                    probe.waitFor("return {ready:document.querySelectorAll('[data-lite-vote-count]').length===2}")
                    probe.evaluate("for(const n of document.querySelectorAll('like-button-view-model,dislike-button-view-model,like-button-view-model button,dislike-button-view-model button')){n.style.width='36px';n.style.maxWidth='36px'}return {}")
                }
                if (cycle % 4 == 3) probe.evaluate("document.querySelector('like-button-view-model button').setAttribute('aria-pressed','true');document.querySelector('dislike-button-view-model').style.display='none';document.querySelector('.segmented-buttons-wrapper').style.width='36px';document.querySelector('.slim-video-action-bar-actions').style.overflow='hidden';return {}")
                val state = probe.waitFor(metrics.replace("ready:votes.every(v=>v.width>24&&v.glyphWidth>=16)",
                    "ready:votes.every(v=>v.width>24&&v.glyphWidth>=16&&v.count)&&(votes[0].count==='7,535')"))
                val votes = state.getJSONArray("votes")
                repeat(2) { index -> assertTrue("Clipped vote after hydration $cycle: $state", votes.getJSONObject(index).getBoolean("unclipped")) }
                assertEquals("Both counts must be rendered", "246", votes.getJSONObject(1).getString("count"))
                assertTrue(state.getBoolean("scrollable"))
                assertEquals(3, state.getInt("injected"))
                assertActionSpacing(state)
            }
            DeviceEvidence.captureScene("youtube-votes-hydrated")
            probe.evaluate("prefs.enable_show_likes=false;prefs.enable_display_dislikes=false;dispatchEvent(new CustomEvent('preferencesChanged',{detail:{key:'*'}}));return {}")
            val restored = probe.waitFor("return {ready:!document.querySelector('[data-lite-vote-count]'),labels:Array.from(document.querySelectorAll('span[role=text]')).map(n=>({text:n.textContent,display:getComputedStyle(n).display})),icons:document.querySelectorAll('like-button-view-model svg,dislike-button-view-model svg').length}")
            assertEquals(2, restored.getInt("icons"))
            val labels = restored.getJSONArray("labels")
            repeat(2) { assertNotEquals("none", labels.getJSONObject(it).getString("display")) }
            assertEquals("native-like", labels.getJSONObject(0).getString("text"))
        } finally { destroy() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test fun liveYoutubeKeepsVoteGlyphsAndCountsCompleteAcrossRepeatedLoads() {
        assumeTrue("Supply -e network=1 for live YouTube acceptance", InstrumentationRegistry.getArguments().getString("network") == "1")
        val observations = org.json.JSONArray()
        var pageFinished = CountDownLatch(1)
        try {
            activity.scenario.onActivity { context ->
                view = WebView(context).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = Constants.userAgent()
                    addJavascriptInterface(Bridge({}, {}, extensionManager = GlobalContext.get().get<ExtensionManager>()), Bridge.NAME)
                    for (name in assets) PageScript("script/$name", "Votes-$name").install(context, this)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(v: WebView, url: String) {
                            if (!url.startsWith("https://m.youtube.com/watch")) return
                            v.evaluateJavascript(script, null)
                            pageFinished.countDown()
                        }
                    }
                }
                context.setContentView(view)
            }
            activate()
            repeat(6) { cycle ->
                pageFinished = CountDownLatch(1)
                val videoId = if (cycle % 2 == 0) "E-HuR_CEdo4" else "jNQXAC9IVRw"
                instrumentation.runOnMainSync { view!!.loadUrl("https://m.youtube.com/watch?v=$videoId") }
                assertTrue("YouTube navigation did not finish at load $cycle", pageFinished.await(45, TimeUnit.SECONDS))
                var state = org.json.JSONObject()
                val deadline = System.currentTimeMillis() + 45_000
                while (System.currentTimeMillis() < deadline) {
                    state = probe.evaluate(metrics)
                    if (state.optBoolean("ready") && state.optInt("injected") == 3) break
                    Thread.sleep(250)
                }
                Thread.sleep(1500) // Include late RYD responses and native icon hydration.
                state = probe.evaluate(metrics)
                observations.put(state)
                if (!state.optBoolean("ready")) {
                    state.put("page", probe.evaluate("return {text:document.body?.innerText?.slice(0,800)}"))
                    DeviceEvidence.writeJson("youtube-votes-live-failure.json", observations.toString(2))
                    DeviceEvidence.captureScene("youtube-votes-live-failure")
                }
                assertTrue("Missing vote glyphs at live load $cycle: $state", state.getBoolean("ready"))
                val votes = state.getJSONArray("votes")
                repeat(2) { assertTrue("Clipped live vote: $state", votes.getJSONObject(it).getBoolean("unclipped")) }
                assertActionSpacing(state)
            }
            DeviceEvidence.writeJson("youtube-votes-live.json", observations.toString(2))
            DeviceEvidence.captureScene("youtube-votes-live")
        } finally { destroy() }
    }
}
