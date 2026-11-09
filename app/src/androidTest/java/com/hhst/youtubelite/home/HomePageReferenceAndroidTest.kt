package com.hhst.youtubelite.home

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.debug.reference.PageReferenceActivity
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONArray

/** Read-only home geometry + screenshot from the app's own browser session; no account actions. */
class HomePageReferenceAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun pageView(root: View): WebView? {
        if (root is WebView && root.isShown) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) pageView(root.getChildAt(index))?.let { return it }
        return null
    }

    @Test fun collectHomeGeometryReference() {
        assumeTrue("Supply -e network 1 to sample the live home page", InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<PageReferenceActivity>(Intent(instrumentation.targetContext, PageReferenceActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("https://m.youtube.com/?persist_app=1&app=m&hl=en")
        })
        lateinit var host: PageReferenceActivity
        scenario.onActivity { host = it }
        try {
            val loadDeadline = SystemClock.elapsedRealtime() + 30_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < loadDeadline) {
                compose.waitForIdle()
                instrumentation.runOnMainSync { loaded = pageView(host.window.decorView)?.progress == 100 }
                if (!loaded) Thread.sleep(200)
            }
            assertTrue("Browser home did not finish", loaded)
            val probe = WebProbe { pageView(host.window.decorView)!! }
            var payload = probe.evaluate(COLLECT)
            val contentDeadline = SystemClock.elapsedRealtime() + 20_000
            while ((payload.optJSONArray("items")?.length() ?: 0) == 0 && SystemClock.elapsedRealtime() < contentDeadline) {
                Thread.sleep(400)
                compose.waitForIdle()
                payload = probe.evaluate(COLLECT)
            }
            val width = payload.optJSONObject("viewport")?.optInt("width") ?: 0
            val items = payload.optJSONArray("items")?.length() ?: 0
            val titles = payload.optJSONArray("titles")?.length() ?: 0
            val menus = payload.optJSONArray("menus")?.length() ?: 0
            val avatars = payload.optJSONArray("avatars")?.length() ?: 0
            val pageUri = Uri.parse(payload.optString("url"))
            val isHome = pageUri.host == "m.youtube.com" && pageUri.path.orEmpty() in listOf("", "/")
            val scene = if (isHome) "home-inapp-$width" else "home-reference-error-$width"
            DeviceEvidence.writeJson("$scene.json", payload.toString(2))
            DeviceEvidence.captureScene(scene)
            assertTrue("Expected mobile home, got $pageUri; text=${payload.opt("text")}", isHome)
            assertTrue("Home reference has no feed items; text=${payload.opt("text")}", items > 0)
            assertTrue("Home reference missing title/avatar/menu geometry: t=$titles a=$avatars m=$menus", titles > 0 && avatars > 0 && menus > 0)
            assertTrue("Home reference missing thumbnails", (payload.optJSONArray("thumbs")?.length() ?: 0) > 0)
            if (InstrumentationRegistry.getArguments().getString("scanHomeShelves") == "true") {
                var initial = probe.evaluate(COLLECT_SHELVES)
                val post = (0 until initial.getJSONArray("shelves").length()).map { initial.getJSONArray("shelves").getJSONObject(it) }
                    .firstOrNull { it.getString("tag") == "YTM-BACKSTAGE-POST-THREAD-RENDERER" }
                if (post != null) {
                    val top = initial.optDouble("scrollY") + post.getJSONObject("rect").getDouble("y") - 108
                    val dpr = payload.getJSONObject("viewport").getDouble("dpr")
                    instrumentation.runOnMainSync { pageView(host.window.decorView)!!.scrollTo(0, (top.coerceAtLeast(0.0) * dpr).toInt()) }
                    Thread.sleep(800)
                    initial = probe.evaluate(COLLECT_SHELVES)
                    DeviceEvidence.captureScene("home-post-$width")
                }
                val samples = JSONArray().put(initial)
                repeat(3) {
                    val previousCount = samples.getJSONObject(samples.length() - 1).optInt("itemCount")
                    instrumentation.runOnMainSync { pageView(host.window.decorView)!!.pageDown(true) }
                    val deadline = SystemClock.elapsedRealtime() + 8_000
                    var sample = probe.evaluate(COLLECT_SHELVES)
                    while (sample.optInt("itemCount") <= previousCount && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(400)
                        sample = probe.evaluate(COLLECT_SHELVES)
                    }
                    samples.put(sample)
                }
                DeviceEvidence.writeJson("home-shelves-$width.json", samples.toString(2))
                DeviceEvidence.captureScene("home-shelves-$width")
            }
        } finally {
            scenario.close()
        }
    }

    private companion object {
        val COLLECT_SHELVES = """
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height}; };
            const S = e => { const s = getComputedStyle(e); return {font:s.font,margin:s.margin,padding:s.padding,gap:s.gap,background:s.backgroundColor,borderRadius:s.borderRadius,aspectRatio:s.aspectRatio,animation:s.animation,display:s.display,overflow:s.overflow,textOverflow:s.textOverflow,maxHeight:s.maxHeight,maxWidth:s.maxWidth}; };
            const items = [...document.querySelectorAll('ytm-rich-item-renderer')];
            const shelves = [...document.querySelectorAll('*')].filter(e => /SHORTS|REEL.*SHELF|BACKSTAGE|POST.*(?:RENDERER|VIEW-MODEL)/.test(e.tagName));
            return {url:location.href,width:innerWidth,clientWidth:document.documentElement.clientWidth,scrollY,itemCount:items.length,
              itemTypes:[...new Set(items.map(e => e.firstElementChild?.tagName))],
              matchedTags:[...new Set(shelves.map(e => e.tagName))],
              shelves:shelves.slice(0,20).map(e => ({tag:e.tagName,rect:R(e),style:S(e),parents:[e.parentElement,e.parentElement?.parentElement,e.parentElement?.parentElement?.parentElement].filter(Boolean).map(c => ({tag:c.tagName,cls:String(c.className).slice(0,120),rect:R(c),style:S(c)})),heading:e.querySelector('h2,h3')?.textContent?.slice(0,60),children:[...e.querySelectorAll('*')].filter(c => c.getBoundingClientRect().width > 0 && c.getBoundingClientRect().height > 0).slice(0,80).map(c => ({tag:c.tagName,cls:String(c.className).slice(0,120),rect:R(c),style:S(c)})),icons:[...e.querySelectorAll('svg')].map(s => s.outerHTML)}))};
        """.trimIndent()

        val COLLECT = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x: r.x, y: r.y, width: r.width, height: r.height}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize: s.fontSize, fontWeight: s.fontWeight, lineHeight: s.lineHeight, padding: s.padding, margin: s.margin, color: s.color}; };
            const out = {
              url: location.href,
              viewport: {width: innerWidth, height: innerHeight, clientWidth: document.documentElement.clientWidth, dpr: devicePixelRatio},
              ua: navigator.userAgent,
              text: document.body.innerText.slice(0, 300),
            };
            out.items = [...document.querySelectorAll('ytd-rich-item-renderer, ytm-rich-item-renderer, ytd-video-renderer, ytm-compact-video-renderer')]
              .filter(vis).slice(0, 4)
              .map(e => ({tag: e.tagName, rect: R(e), text: (e.innerText || '').slice(0, 90)}));
            out.titles = [...document.querySelectorAll('a#video-title, .yt-lockup-metadata-view-model-title, yt-lockup-metadata-view-model a, h3 a, h3')]
              .filter(vis).slice(0, 8)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 70), rect: R(e), style: S(e), text: (e.innerText || '').slice(0, 60)}));
            out.avatars = [...document.querySelectorAll('img')]
              .map(e => ({e, r: e.getBoundingClientRect()}))
              .filter(o => vis(o.e) && o.r.width >= 24 && o.r.width <= 52 && Math.abs(o.r.width - o.r.height) < 2)
              .slice(0, 8)
              .map(o => ({rect: {x: o.r.x, y: o.r.y, width: o.r.width, height: o.r.height}, cls: String(o.e.className).slice(0, 60), alt: o.e.alt || ''}));
            out.avatarHosts = [...document.querySelectorAll('.yt-spec-avatar-shape, #channel-thumbnail, ytm-compact-channel-renderer, yt-img-shadow#channel-img')]
              .filter(vis).slice(0, 6)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 70), rect: R(e)}));
            out.menus = [...document.querySelectorAll('button')]
              .map(e => ({e, label: e.getAttribute('aria-label') || ''}))
              .filter(o => vis(o.e) && /action menu|more actions|menu/i.test(o.label))
              .slice(0, 8)
              .map(o => ({label: o.label, rect: R(o.e), style: S(o.e)}));
            out.thumbs = [...document.querySelectorAll('yt-thumbnail-view-model, ytm-thumbnail-cover, ytd-thumbnail, ytm-thumbnail, a#thumbnail')]
              .filter(vis).slice(0, 4)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 60), rect: R(e)}));
            out.shelves = [...document.querySelectorAll('ytd-rich-shelf-renderer, ytm-rich-shelf-renderer, ytd-reel-shelf-renderer, ytm-shelf-renderer, ytm-shorts-lockup-view-model')]
              .filter(vis).slice(0, 4)
              .map(e => ({tag: e.tagName, rect: R(e), text: (e.innerText || '').slice(0, 60)}));
            out.chips = [...document.querySelectorAll('ytm-chip-cloud-chip-renderer, chip-cloud-chip-renderer, ytd-feed-filter-chip-bar-renderer')]
              .filter(vis).slice(0, 12)
              .map(e => ({tag: e.tagName, rect: R(e), text: (e.innerText || '').slice(0, 24)}));
            return out;
        """.trimIndent()
    }
}
