package com.hhst.youtubelite.search

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

class SearchResultsReferenceAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun pageView(root: View): WebView? {
        if (root is WebView && root.isShown) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) pageView(root.getChildAt(index))?.let { return it }
        return null
    }

    private fun sample(url: String, scene: String, readyExpression: String, requireItems: Boolean) {
        assumeTrue("Supply -e network 1 to sample the live results page", InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<PageReferenceActivity>(Intent(instrumentation.targetContext, PageReferenceActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
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
            assertTrue("Browser results page did not finish", loaded)
            val probe = WebProbe { pageView(host.window.decorView)!! }
            val collect = COLLECT.replace("__READY__", readyExpression)
            var payload = probe.evaluate(collect)
            val contentDeadline = SystemClock.elapsedRealtime() + 25_000
            while (!payload.optBoolean("ready") && SystemClock.elapsedRealtime() < contentDeadline) {
                Thread.sleep(400)
                compose.waitForIdle()
                payload = probe.evaluate(collect)
            }
            val width = payload.optJSONObject("viewport")?.optInt("width") ?: 0
            val target = "$scene-$width"
            DeviceEvidence.writeJson("$target.json", payload.toString(2))
            DeviceEvidence.captureScene(target)
            assertTrue("Expected results page, got ${payload.optString("url")}", Uri.parse(payload.optString("url")).let { it.host == "m.youtube.com" && it.path == "/results" })
            assertTrue("Sampled page reported it was not ready: ${payload.optString("probe")}", payload.optBoolean("ready"))
            if (requireItems) {
                assertTrue("Results page has no video items", (payload.optJSONArray("items")?.length() ?: 0) > 0)
                assertTrue("Results page has no titles", (payload.optJSONArray("titles")?.length() ?: 0) > 0)
                assertTrue("Results page has no thumbnails", (payload.optJSONArray("thumbs")?.length() ?: 0) > 0)
            }
        } finally {
            scenario.close()
        }
    }

    @Test fun collectResultsGeometryReference() = sample(
        "https://m.youtube.com/results?search_query=blender+open+movie&hl=en", "search-results", ITEMS_READY, true,
    )

    @Test fun collectNoResultsGeometryReference() = sample(
        "https://m.youtube.com/results?search_query=%22qzxjvwkp+9182+hdje%22+-blender&hl=en", "search-no-results", MESSAGE_READY, false,
    )

    private companion object {
        const val ITEMS_READY = "document.querySelectorAll('ytm-video-with-context-renderer, ytm-media-item, ytm-compact-video-renderer').length > 0"

        const val MESSAGE_READY =
            "[...document.querySelectorAll('ytm-message-renderer')].some(e => /no results|did not match/i.test(e.innerText)) || /no results found|did not match any/i.test(document.body.innerText)"

        val COLLECT = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x, y:r.y, width:r.width, height:r.height}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,
              fontFamily:s.fontFamily,padding:s.padding,margin:s.margin,color:s.color,backgroundColor:s.backgroundColor,
              borderRadius:s.borderRadius,display:s.display,aspectRatio:s.aspectRatio,overflow:s.overflow,textOverflow:s.textOverflow,
              maxHeight:s.maxHeight,maxWidth:s.maxWidth,gap:s.gap,boxShadow:s.boxShadow,alignItems:s.alignItems,flexDirection:s.flexDirection,
              position:s.position,top:s.top,left:s.left,width:s.width,height:s.height,minWidth:s.minWidth,textAlign:s.textAlign,
              boxSizing:s.boxSizing,border:s.border,webkitLineClamp:s.webkitLineClamp}; };
            const lineCount = e => { const r = document.createRange(); r.selectNodeContents(e); return r.getClientRects().length; };
            const rects = e => [...e.getClientRects()].map(r => ({x:r.x,y:r.y,width:r.width,height:r.height}));
            const ITEM = 'ytm-video-with-context-renderer, ytm-media-item, ytm-compact-video-renderer';
            const items = [...document.querySelectorAll(ITEM)].filter(vis);
            const out = {
              url: location.href,
              viewport: {width: innerWidth, height: innerHeight, clientWidth: document.documentElement.clientWidth,
                dpr: devicePixelRatio, scrollbar: innerWidth - document.documentElement.clientWidth},
              ua: navigator.userAgent,
              html: {lang: document.documentElement.lang, cls: String(document.documentElement.className),
                bodyCls: String(document.body.className), colorScheme: getComputedStyle(document.documentElement).colorScheme},
              ready: __READY__,
              probe: 'items=' + items.length,
              bodyText: (document.body.innerText || '').slice(0, 400),
            };
            const svgOf = e => { const s = e.querySelector('svg'); return s ? s.outerHTML.slice(0, 1200) : ''; };
            out.header = [...document.querySelectorAll('ytm-header-bar, ytm-mobile-topbar-renderer, header, ytm-searchbox, ytm-search-box')]
              .filter(vis).slice(0, 4).map(e => ({tag: e.tagName, rect: R(e), style: S(e), text: (e.innerText || '').slice(0, 40),
                controls: [...e.querySelectorAll('button, a, [role="button"]')].filter(vis).slice(0, 12).map(c => ({tag: c.tagName,
                  cls: String(c.className).slice(0, 80), label: (c.getAttribute('aria-label') || c.innerText || '').slice(0, 40),
                  rect: R(c), style: S(c), svg: svgOf(c)}))}));
            out.nav = [...document.querySelectorAll('ytm-pivot-bar-renderer, ytm-bottom-navigation, [role="tablist"], ytm-navigation-bar')]
              .filter(vis).slice(0, 2).map(e => ({tag: e.tagName, rect: R(e), style: S(e),
                children: [...e.children].filter(vis).map(c => ({tag: c.tagName, cls: String(c.className).slice(0, 60), rect: R(c), style: S(c),
                  label: (c.getAttribute('aria-label') || c.innerText || '').slice(0, 24), svg: svgOf(c)}))}));
            out.filterRow = [...document.querySelectorAll('[class*="search-filter"], ytm-chip-cloud-renderer, [role="tablist"] > *')]
              .filter(vis).slice(0, 12).map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e), style: S(e), text: (e.innerText || '').slice(0, 24)}));
            out.chips = [...document.querySelectorAll('ytm-chip-cloud-chip-renderer, ytm-search-filter-renderer, chip-cloud-chip-renderer, [role="tab"], ytm-chip-cloud-renderer')]
              .filter(vis).slice(0, 14).map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 80), rect: R(e), style: S(e), text: (e.innerText || '').slice(0, 24)}));
            out.items = items.slice(0, 8).map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 120), rect: R(e), style: S(e), text: (e.innerText || '').slice(0, 140)}));
            out.titles = [...document.querySelectorAll('h3, .media-item-headline, [class*="headline"], a[href*="/watch"]')]
              .filter(e => vis(e) && (e.innerText || '').length > 10).slice(0, 8)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e), style: S(e),
                lines: lineCount(e), lineRects: rects(e), text: (e.innerText || '').slice(0, 70)}));
            out.thumbs = [...document.querySelectorAll('.media-item-thumbnail-container, ytm-thumbnail-overlay, img, yt-image, .video-thumbnail-container')]
              .map(e => ({e, r: e.getBoundingClientRect()}))
              .filter(o => vis(o.e) && o.r.width >= 80 && o.r.height >= 40).slice(0, 8)
              .map(o => ({tag: o.e.tagName, cls: String(o.e.className).slice(0, 90), rect: {x:o.r.x,y:o.r.y,width:o.r.width,height:o.r.height},
                style: S(o.e), src: (o.e.currentSrc || o.e.src || o.e.getAttribute('src') || '').slice(0, 120)}));
            out.avatars = [...document.querySelectorAll('.media-item-metadata img, ytm-channel-thumbnail, [class*="avatar"] img, img[src*="yt3"]')]
              .map(e => ({e, r: e.getBoundingClientRect()}))
              .filter(o => vis(o.e) && o.r.width >= 16 && o.r.width <= 60 && Math.abs(o.r.width - o.r.height) < 3).slice(0, 6)
              .map(o => ({tag: o.e.tagName, cls: String(o.e.className).slice(0, 90), rect: {x:o.r.x,y:o.r.y,width:o.r.width,height:o.r.height}, style: S(o.e)}));
            out.menus = [...document.querySelectorAll('button, ytm-button-renderer, [role="button"]')]
              .map(e => ({e, label: e.getAttribute('aria-label') || (e.innerText || '')}))
              .filter(o => vis(o.e) && /menu|more|action|filter/i.test(o.label)).slice(0, 8)
              .map(o => ({label: o.label.slice(0, 50), rect: R(o.e), style: S(o.e), html: (o.e.innerHTML || '').slice(0, 600)}));
            out.icons = [...document.querySelectorAll('svg')].filter(vis).slice(0, 24)
              .map(e => ({tag: e.tagName, cls: String(e.className.baseVal || '').slice(0, 40), rect: R(e), parent: e.parentElement ? e.parentElement.tagName : '',
                parentLabel: e.parentElement ? (e.parentElement.getAttribute('aria-label') || '') : '', html: e.outerHTML}));
            const full = e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e), style: S(e),
              text: (e.childElementCount === 0 ? (e.innerText || '') : '').slice(0, 80),
              label: (e.getAttribute('aria-label') || '').slice(0, 40), svg: svgOf(e)});
            const descendants = (e, limit) => [...e.querySelectorAll('*')].map(n => ({n, r: n.getBoundingClientRect()}))
              .filter(o => o.r.width > 0 && o.r.height > 0).slice(0, limit).map(o => full(o.n));
            const videos = [...document.querySelectorAll('ytm-video-with-context-renderer')].filter(vis);
            out.videoItems = videos.slice(0, 4).map(e => {
              const thumb = e.querySelector('.media-item-thumbnail-container');
              const title = e.querySelector('.media-item-headline, h3');
              const meta = e.querySelector('.media-item-metadata');
              const overlays = thumb ? [...thumb.querySelectorAll('*')].map(n => ({n, r: n.getBoundingClientRect(), s: getComputedStyle(n)}))
                .filter(o => o.r.width > 0 && o.r.height > 0 && o.s.position === 'absolute').map(o => full(o.n)) : [];
              return {rect: R(e), style: S(e), html: e.innerHTML.replace(/src="[^"]{40,}"/g, 'src="..."').slice(0, 2000),
                thumb: thumb ? {rect: R(thumb), style: S(thumb), img: [...thumb.querySelectorAll('img')].map(i => ({rect: R(i), src: (i.currentSrc || i.src || '').slice(0, 90)}))} : null,
                overlays: overlays, title: title ? full(title) : null, metadata: meta ? {rect: R(meta), style: S(meta), children: descendants(meta, 14)} : null,
                texts: [...e.querySelectorAll('*')].map(n => ({n, r: n.getBoundingClientRect()}))
                  .filter(o => o.n.childElementCount === 0 && (o.n.innerText || '').trim().length > 0 && o.r.width > 0)
                  .map(o => ({text: o.n.innerText.trim().slice(0, 60), rect: {x:o.r.x,y:o.r.y,width:o.r.width,height:o.r.height}, style: S(o.n)}))};
            });
            const first = videos[0] || items[0];
            out.firstCard = first ? {tag: first.tagName, cls: String(first.className), rect: R(first), style: S(first),
              descendants: descendants(first, 70)} : null;
            const wanted = /media-item|video-with-context|compact-video|search-filter|ytm-searchbox|chip-cloud|message-renderer|ytm-media-item|ytm-thumbnail/;
            out.media = ['(orientation: portrait)', '(orientation: landscape)', '(min-aspect-ratio: 13/9)', '(min-width: 931px)']
              .map(q => ({query:q, matches:matchMedia(q).matches}));
            out.ancestors = first ? [first, ...(() => { const a=[]; let e=first.parentElement; while(e){a.push(e);e=e.parentElement;} return a; })()]
              .map(e => ({tag:e.tagName, cls:String(e.className), attrs:[...e.attributes].map(a=>[a.name,a.value]), rect:R(e)})) : [];
            out.styleSheets = [...document.styleSheets].map(sheet => {
              try { return {href:sheet.href, rules:sheet.cssRules.length}; }
              catch (error) { return {href:sheet.href, error:String(error)}; }
            });
            out.css = [];
            const walk = (rules, contexts = []) => {
              for (const rule of rules) {
                if (rule.selectorText) {
                  if (!wanted.test(rule.selectorText)) continue;
                  let css = rule.cssText;
                  for (const context of [...contexts].reverse()) css = context + ' { ' + css + ' }';
                  out.css.push(css);
                } else if (rule.cssRules) {
                  const prefix = rule.cssText.slice(0, rule.cssText.indexOf('{')).trim();
                  walk(rule.cssRules, [...contexts, prefix]);
                }
              }
            };
            for (const sheet of document.styleSheets) {
              let rules = null;
              try { rules = sheet.cssRules; } catch (err) { continue; }
              if (!rules) continue;
              walk(rules);
            }
            return out;
        """.trimIndent()
    }
}
