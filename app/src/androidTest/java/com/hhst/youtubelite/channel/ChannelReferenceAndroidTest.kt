package com.hhst.youtubelite.channel

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Read-only collector for the mobile-web channel page: header and videos tab grid.
 * Nothing on the page is clicked; samples load per viewport.
 */
class ChannelReferenceAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun pageView(root: View): WebView? {
        if (root is WebView && root.isShown) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) pageView(root.getChildAt(index))?.let { return it }
        return null
    }

    private fun originalGeometry(): Pair<String, String> {
        val size = DeviceEvidence.shell("wm size").lineAfter("Physical size:")
        val density = DeviceEvidence.shell("wm density").lineAfter("Physical density:")
        return size to density
    }

    private fun String.lineAfter(prefix: String): String =
        lines().firstOrNull { it.contains(prefix) }?.substringAfter(prefix)?.trim() ?: ""

    /** Density chosen so CSS px = target width. */
    private fun applyViewport(title: String, widthCs: Int, heightCs: Int) {
        when (widthCs) {
            390, 411, 320 -> { DeviceEvidence.shell("wm density 480"); DeviceEvidence.shell("wm size ${widthCs * 3}x${heightCs * 3}") }
            else -> { DeviceEvidence.shell("wm density 320"); DeviceEvidence.shell("wm size ${widthCs * 2}x${heightCs * 2}") }
        }
        SystemClock.sleep(2500)
        DeviceEvidence.writeJson("channel-viewport-$title.txt", "target $widthCs\n")
    }

    private fun sample(url: String, scene: String, readyExpression: String, requireVideos: Boolean, expectWidth: Int, collectSource: String = COLLECT) {
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
                if (!loaded) Thread.sleep(300)
            }
            assertTrue("Channel page did not finish loading", loaded)
            val probe = WebProbe { pageView(host.window.decorView)!! }
            val collect = collectSource.replace("__READY__", readyExpression)
            var payload = probe.evaluate(collect)
            val contentDeadline = SystemClock.elapsedRealtime() + 30_000
            while (!payload.optBoolean("ready") && SystemClock.elapsedRealtime() < contentDeadline) {
                Thread.sleep(500)
                compose.waitForIdle()
                payload = probe.evaluate(collect)
            }
            val width = payload.optJSONObject("viewport")?.optInt("width") ?: 0
            assertEquals("Viewport mismatch", expectWidth, width)
            val target = "$scene-$width"
            DeviceEvidence.writeJson("$target.json", payload.toString(2))
            DeviceEvidence.captureScene(target)
            assertTrue("Expected channel page, got ${payload.optString("url")}",
                Uri.parse(payload.optString("url")).host == "m.youtube.com")
            assertTrue("Sampled channel page was not ready: ${payload.optString("probe")}", payload.optBoolean("ready"))
            if (requireVideos) {
                assertTrue("Channel videos tab has no items", (payload.optJSONArray("videos")?.length() ?: 0) > 0)
            }
        } finally {
            scenario.close()
        }
    }

    @Test fun collectChannelHeaderAndTabsReference() {
        assumeTrue("Supply -e network 1 to sample the live channel page", InstrumentationRegistry.getArguments().getString("network") == "1")
        val original = originalGeometry()
        try {
            applyViewport("390", 390, 844)
            sample("https://m.youtube.com/@blenderguru?hl=en", "channel-header", HEADER_READY, false, 390)
            applyViewport("1280", 1280, 800)
            sample("https://m.youtube.com/@blenderguru?hl=en", "channel-header", HEADER_READY, false, 1280)
            // Mid-width probes pin the banner/avatar responsive rules.
            applyViewport("600", 600, 890)
            sample("https://m.youtube.com/@blenderguru?hl=en", "channel-header", HEADER_READY, false, 600)
            applyViewport("840", 840, 915)
            sample("https://m.youtube.com/@blenderguru?hl=en", "channel-header", HEADER_READY, false, 840)
        } finally {
            DeviceEvidence.shell("wm density ${original.second}")
            DeviceEvidence.shell("wm size ${original.first}")
        }
    }

    @Test fun collectChannelVideosReference() {
        assumeTrue("Supply -e network 1 to sample the live channel page", InstrumentationRegistry.getArguments().getString("network") == "1")
        val original = originalGeometry()
        try {
            applyViewport("390", 390, 844)
            sample("https://m.youtube.com/@blenderguru/videos?hl=en", "channel-videos", VIDEOS_READY, true, 390)
            applyViewport("1280", 1280, 800)
            sample("https://m.youtube.com/@blenderguru/videos?hl=en", "channel-videos", VIDEOS_READY, true, 1280)
        } finally {
            DeviceEvidence.shell("wm density ${original.second}")
            DeviceEvidence.shell("wm size ${original.first}")
        }
    }

    @Test fun collectChannelHeaderIdentityReference() {
        assumeTrue("Supply -e network 1 to sample the live channel page", InstrumentationRegistry.getArguments().getString("network") == "1")
        val original = originalGeometry()
        try {
            applyViewport("identity-390", 390, 844)
            sample("https://m.youtube.com/@blenderguru/videos?hl=en", "channel-identity", VIDEOS_READY, false, 390, IDENTITY_COLLECT)
            applyViewport("identity-1280", 1280, 800)
            sample("https://m.youtube.com/@blenderguru/videos?hl=en", "channel-identity", VIDEOS_READY, false, 1280, IDENTITY_COLLECT)
        } finally {
            DeviceEvidence.shell("wm density ${original.second}")
            DeviceEvidence.shell("wm size ${original.first}")
        }
    }

    @Test fun collectMidWidthIdentityReference() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val original = originalGeometry()
        try {
            for (width in (InstrumentationRegistry.getArguments().getString("referenceWidths") ?: "600,840").split(",").map(String::toInt)) {
                applyViewport("identity-" + width, width, 1000)
                sample("https://m.youtube.com/@blenderguru/videos?hl=en", "channel-review-identity",
                    VIDEOS_READY, false, width, IDENTITY_COLLECT)
            }
        } finally {
            DeviceEvidence.shell("wm density ${original.second}")
            DeviceEvidence.shell("wm size ${original.first}")
        }
    }

    private companion object {
        /** Deep header descendants rendered on the videos tab header; sampling happens there, not on the home tab. */
        const val VIDEOS_READY =
            "[...document.querySelectorAll('a[href*=\"/watch\"]')].filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }).length > 3"

        /**
         * Focused header collection: the generic census misses name/handle/description because
         * they are not `ytm-*`; this records visible header text leaves and banner/avatar images.
         */
        val IDENTITY_COLLECT = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:Math.round(r.x*100)/100, y:Math.round(r.y*100)/100, width:Math.round(r.width*100)/100, height:Math.round(r.height*100)/100}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,
              fontFamily:s.fontFamily,fontVariationSettings:s.fontVariationSettings,padding:s.padding,margin:s.margin,color:s.color,backgroundColor:s.backgroundColor,
              borderRadius:s.borderRadius,display:s.display,maxHeight:s.maxHeight,maxWidth:s.maxWidth,gap:s.gap,
              boxSizing:s.boxSizing,overflow:s.overflow,textOverflow:s.textOverflow,webkitLineClamp:s.webkitLineClamp,
              letterSpacing:s.letterSpacing,textDecorationLine:s.textDecorationLine,border:s.border,position:s.position,alignItems:s.alignItems,
              justifyContent:s.justifyContent,aspectRatio:s.aspectRatio,width:s.width,height:s.height,opacity:s.opacity};
            };
            const leaf = e => ({tag:e.tagName, cls:String(e.className).slice(0,140), id:e.id || '',
              rect:R(e), style:S(e), text:(e.childElementCount === 0 ? (e.innerText || '') : '').slice(0, 200),
              label:(e.getAttribute('aria-label') || '').slice(0, 90)});
            const header = document.querySelector('ytm-channel-header-renderer') ||
              document.querySelector('[class*=pageHeaderRendererHost]') || document.querySelector('ytm-profile-page-renderer');
            const out = {url: location.href, ready: __READY__,
              viewport: {width: innerWidth, height: innerHeight, dpr: devicePixelRatio}};
            out.fontResources = performance.getEntriesByType('resource').map(e => e.name).filter(n => /woff|ttf|font/i.test(n));
            out.fonts = [...document.fonts].map(f => ({family:f.family,weight:f.weight,stretch:f.stretch,status:f.status}));
            out.fontFaceRules = [];
            const fontRules = rules => {
              for (const rule of rules) {
                if (rule.type === CSSRule.FONT_FACE_RULE) out.fontFaceRules.push(rule.cssText);
                else if (!rule.selectorText && rule.cssRules) fontRules(rule.cssRules);
              }
            };
            for (const sheet of document.styleSheets) { try { fontRules(sheet.cssRules); } catch(e) {} }
            if (header) {
              out.header = {tag: header.tagName, cls: String(header.className).slice(0, 120), rect: R(header),
                style: {padding: getComputedStyle(header).padding, margin: getComputedStyle(header).margin, background: getComputedStyle(header).backgroundColor},
                texts: [...header.querySelectorAll('*')].map(n => ({n, w: n.getBoundingClientRect().width}))
                  .filter(o => o.w > 0 && o.n.getBoundingClientRect().height > 0).map(o => leaf(o.n))
                  .filter(o => o.text.trim().length > 0 && o.text.length <= 200).slice(0, 60),
                shapes: [...header.querySelectorAll('*')].filter(vis).filter(e => e.tagName === 'IMG' ||
                  /avatar-shape|banner|image-host/.test(String(e.className))).slice(0, 12).map(leaf)};
              const underline = [...header.querySelectorAll('[aria-selected="true"], .selected, [class*=Selected]')].filter(vis).slice(0, 3);
              out.selectedTab = underline.map(e => ({...leaf(e), children: [...e.children].filter(vis).slice(0, 6).map(leaf)}));
              const images = [...header.querySelectorAll('img')].filter(vis).slice(0, 8);
              out.images = images.map(i => ({rect: R(i), src: (i.currentSrc || i.src || '').slice(0, 160),
                cls: String(i.className).slice(0, 110), style: S(i)}));
              const expander = header.querySelector('yt-text-inline-expander');
              if (expander) out.expander = {rect: R(expander), style: S(expander),
                text: (expander.innerText || '').slice(0, 300),
                scrollHeight: expander.scrollHeight, clientHeight: expander.clientHeight};
              // Channel name is nested; match its exact text anywhere in the header.
              const nameText = (document.querySelector('[class*=pageHeaderRendererHost]') ?
                (document.querySelector('ytm-page-header-channels-entrypoint-renderer') ?
                  (document.querySelector('ytm-page-header-channels-entrypoint-renderer').innerText || '') : '') : '');
              out.name = [...header.querySelectorAll('*')].filter(e => (e.innerText || '').trim() === 'Blender Guru'
                && e.getBoundingClientRect().y > 48 && vis(e)).slice(0, 6)
                .map(e => ({cls: String(e.className).slice(0, 140), rect: R(e), style: S(e), tag: e.tagName,
                  kids: [...e.children].filter(vis).slice(0, 4).map(k => ({tag: k.tagName,
                    cls: String(k.className).slice(0, 110), rect: R(k), style: S(k)}))}));
            }
            return out;
        """.trimIndent()

        const val HEADER_READY =
            "[...document.querySelectorAll('[role=\"tab\"]')].some(t => /home|videos/i.test(t.textContent))"

        val COLLECT = """
            const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x, y:r.y, width:r.width, height:r.height}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,
              fontFamily:s.fontFamily,fontVariationSettings:s.fontVariationSettings,padding:s.padding,margin:s.margin,color:s.color,backgroundColor:s.backgroundColor,
              borderRadius:s.borderRadius,display:s.display,aspectRatio:s.aspectRatio,overflow:s.overflow,textOverflow:s.textOverflow,
              maxHeight:s.maxHeight,maxWidth:s.maxWidth,gap:s.gap,boxShadow:s.boxShadow,alignItems:s.alignItems,flexDirection:s.flexDirection,
              position:s.position,top:s.top,left:s.left,width:s.width,height:s.height,minWidth:s.minWidth,textAlign:s.textAlign,
              boxSizing:s.boxSizing,border:s.border,opacity:s.opacity,justifyContent:s.justifyContent,whiteSpace:s.whiteSpace,
              webkitLineClamp:s.webkitLineClamp,textDecorationLine:s.textDecorationLine,filter:s.filter};
            };
            const full = e => ({tag: e.tagName, cls: String(e.className).slice(0, 110), id: e.id || '', rect: R(e), style: S(e),
              text: (e.childElementCount === 0 ? (e.innerText || '') : '').slice(0, 80),
              label: (e.getAttribute('aria-label') || '').slice(0, 60),
              svg: e.querySelector('svg') ? e.querySelector('svg').outerHTML.slice(0, 1400) : ''});
            const descendants = (e, limit) => [...e.querySelectorAll('*')].map(n => ({n, r: n.getBoundingClientRect()}))
              .filter(o => o.r.width > 0 && o.r.height > 0).slice(0, limit).map(o => full(o.n));
            const headerRoot = [...document.querySelectorAll('ytm-channel-header-renderer, ytm-profile-page-renderer, ytm-c4-tabbed-header-renderer, [id*=channel-header], [class*=page-header]')]
              .filter(vis);
            const bannerProbe = [...document.querySelectorAll('[class*=banner], [id*=banner], ytm-profile-banner')].filter(vis);
            const container = headerRoot[0] || bannerProbe[0]?.closest('div') || bannerProbe[0];
            const out = {
              url: location.href,
              viewport: {width: innerWidth, height: innerHeight, clientWidth: document.documentElement.clientWidth,
                dpr: devicePixelRatio, scrollbar: innerWidth - document.documentElement.clientWidth},
              ua: navigator.userAgent,
              ready: __READY__,
              probe: {tabs: document.querySelectorAll('[role=tab]').length,
                videos: document.querySelectorAll('ytm-video-with-context-renderer, ytm-media-item, ytm-compact-video-renderer').length},
              bodyText: (document.body.innerText || '').slice(0, 600),
            };
            out.header = container ? [{tag: container.tagName, cls: String(container.className).slice(0, 110), rect: R(container),
              ancestors: [...(() => { const a=[]; let e=container.parentElement; while(e){a.push(e);e=e.parentElement;} return a; })()]
                .slice(0, 3).map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 60), rect: R(e)})),
              descendants: descendants(container, 90)}] : [];
            out.avatars = [...document.querySelectorAll('[class*=avatar], ytm-avatar, img[src*="yt3"], img[src*=" analogy"], [class*=profile-image]')]
              .map(e => ({e, r: e.getBoundingClientRect()})).filter(o => vis(o.e)).slice(0, 6)
              .map(o => ({tag: o.e.tagName, cls: String(o.e.className).slice(0, 90), rect: R(o.e), style: S(o.e),
                src: (o.e.currentSrc || o.e.src || '').slice(0, 110)}));
            out.contexts = ['(orientation: portrait)', '(orientation: landscape)', '(min-aspect-ratio: 13/9)',
              '(min-width: 400px)', '(min-width: 528px)', '(min-width: 600px)', '(min-width: 700px)', '(min-width: 931px)']
              .map(q => ({query: q, matches: matchMedia(q).matches}));
            const tabs = [...document.querySelectorAll('[role=\"tab\"], [role=\"tablist\"]')].filter(vis);
            out.tabs = tabs.slice(0, 20).map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e), style: S(e),
              selected: e.getAttribute('aria-selected') === 'true' || e.getAttribute('aria-selected') === '1',
              scrollableWidth: e.scrollWidth || '', svg: e.querySelector('svg') ? e.querySelector('svg').outerHTML : ''}));
            out.tabbar = [...document.querySelectorAll('ytm-tab-renderer, ytm-profile-tabs, [class*=tab-bar], [id*=tab]')].filter(vis).slice(0, 6)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e), style: S(e),
                childRects: [...e.children].filter(vis).slice(0, 12).map(c => ({cls: String(c.className).slice(0, 60), rect: R(c)}))}));
            // First-viewport-only page snapshot of reachable vertical anchors.
            out.pageTop = [...document.querySelectorAll('ytm-browse > *, [class*=tab-content] > *')].filter(vis).slice(0, 4)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e)}));
            // First-visit census: every visible custom element plus every button/anchor,
            // so one run identifies the actual channel tags before they are re-targeted.
            out.dump = [...document.querySelectorAll('*')].filter(e => e.tagName.startsWith('YTM-')).filter(vis).slice(0, 90)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e),
                text: (e.innerText || '').replace(/\s+/g, ' ').slice(0, 60)}));
            out.buttons = [...document.querySelectorAll('button, ytm-button-renderer, [role=\"button\"], a')]
              .map(e => ({e, label: e.getAttribute('aria-label') || (e.innerText || '').trim()}))
              .filter(o => vis(o.e) && o.label.length > 0 && o.label.length < 40
                && /subscribe|share|join|view|members|full description|show more|more about|feedback/i.test(o.label)).slice(0, 12)
              .map(o => ({tag: o.e.tagName, cls: String(o.e.className).slice(0, 90), label: o.label.slice(0, 40),
                rect: R(o.e), style: S(o.e), html: (o.e.innerHTML || '').replace(/src="[^"]{30,}"/g, 'src="..."').slice(0, 700)}));
            out.stats = [...document.querySelectorAll('span, div')].map(e => ({e, text: (e.childElementCount === 0 ? e.innerText : '').trim()}))
              .filter(o => o.text.length > 0 && o.text.length < 140 && /subscriber|views|video\b/i.test(o.text)).slice(0, 8)
              .map(o => ({tag: o.e.tagName, cls: String(o.e.className).slice(0, 70), rect: R(o.e), style: S(o.e), text: o.text}));
            const ITEM = 'ytm-video-with-context-renderer, ytm-media-item, ytm-compact-video-renderer';
            const videos = [...document.querySelectorAll(ITEM)].filter(vis);
            out.videos = videos.slice(0, 5).map(e => {
              const thumb = e.querySelector('.media-item-thumbnail-container, [class*=thumbnail]');
              const title = e.querySelector('.media-item-headline, h3');
              const meta = e.querySelector('.media-item-metadata');
              const overlays = thumb ? [...thumb.querySelectorAll('*')].map(n => ({n, r: n.getBoundingClientRect(), s: getComputedStyle(n)}))
                .filter(o => o.r.width > 0 && o.r.height > 0 && o.s.position === 'absolute').map(o => full(o.n)) : [];
              return {cls: String(e.className).slice(0, 110), rect: R(e), style: S(e),
                thumb: thumb ? {rect: R(thumb), style: S(thumb), img: [...thumb.querySelectorAll('img')].map(i => ({rect: R(i), src: (i.currentSrc || i.src || '').slice(0, 90)}))} : null,
                overlays: overlays,
                title: title ? full(title) : null,
                metadata: meta ? {rect: R(meta), style: S(meta), children: descendants(meta, 12)} : null,
                texts: [...e.querySelectorAll('*')].map(n => ({n, r: n.getBoundingClientRect()}))
                  .filter(o => o.n.childElementCount === 0 && (o.n.innerText || '').trim().length > 0 && o.r.width > 0)
                  .map(o => ({text: o.n.innerText.trim().slice(0, 70), rect: {x:o.r.x,y:o.r.y,width:o.r.width,height:o.r.height}, style: S(o.n)}))};
            });
            out.gridHost = [...document.querySelectorAll('ytm-browse, ytm-section-list-renderer, [class*=grid], [class*=rich-grid]')].filter(vis).slice(0, 6)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 100), rect: R(e), style: S(e)}));
            // First-viewport-only page snapshot of reachable vertical anchors.
            out.pageTop = [...document.querySelectorAll('ytm-browse > *, [class*=tab-content] > *')].filter(vis).slice(0, 4)
              .map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e)}));
            const wanted = /channel|banner|tab|avatar|chip|media-item|video-with-context|compact-video|subscribe|profile|header|shelf|grid|metadata|headline|thumbnail/;
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
              try { rules = sheet.cssRules; } catch (err) { out.styleSheets = out.styleSheets || [{error: String(err)}]; continue; }
              if (!rules) continue;
              out.styleSheets = out.styleSheets || [];
              out.styleSheets.push({href: sheet.href, rules: rules.length});
              walk(rules);
            }
            out.svgs = [...document.querySelectorAll('svg')].filter(vis).slice(0, 30)
              .map(e => ({tag: e.tagName, cls: String(e.className.baseVal || '').slice(0, 40), rect: R(e),
                parentLabel: e.parentElement ? (e.parentElement.getAttribute('aria-label') || e.parentElement.title || '') : '',
                html: e.outerHTML}));
            return out;
        """.trimIndent()
    }
}
