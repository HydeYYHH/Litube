package com.hhst.youtubelite.watch

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

/** Read-only geometry sampling for the mobile watch page. Requires `-e network 1`. */
class WatchReferenceAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun pageView(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) pageView(root.getChildAt(index))?.let { return it }
        return null
    }

    private fun dismissSystemDialogs() {
        // Only dismiss notification permission if present; do not send BACK (that finishes the host).
        val dump = DeviceEvidence.shell("uiautomator dump /data/local/tmp/watch-ui.xml >/dev/null 2>&1; cat /data/local/tmp/watch-ui.xml 2>/dev/null")
        when {
            dump.contains("Don't allow") || dump.contains("Don’t allow") -> {
                val match = Regex("text=\"Don.t allow\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(dump)
                if (match != null) {
                    val (l, t, r, b) = match.destructured.toList().map { it.toInt() }
                    DeviceEvidence.shell("input tap ${(l + r) / 2} ${(t + b) / 2}")
                    Thread.sleep(400)
                }
            }
            dump.contains(">Allow<") || dump.contains("POST_NOTIFICATION") -> {
                val match = Regex("text=\"Don.t allow\"|text=\"Allow\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(dump)
                if (match != null) {
                    val (l, t, r, b) = match.destructured.toList().map { it.toInt() }
                    // Prefer deny; if only Allow matched this still dismisses.
                    DeviceEvidence.shell("input tap ${(l + r) / 2} ${(t + b) / 2}")
                    Thread.sleep(400)
                } else {
                    DeviceEvidence.shell("input keyevent KEYCODE_BACK")
                    Thread.sleep(300)
                }
            }
        }
    }

    private fun sample(url: String, scene: String, readyExpression: String) {
        assumeTrue("Supply -e network 1 to sample the live watch page", InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = ActivityScenario.launch<PageReferenceActivity>(Intent(instrumentation.targetContext, PageReferenceActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
        })
        lateinit var host: PageReferenceActivity
        scenario.onActivity { host = it }
        try {
            val loadDeadline = SystemClock.elapsedRealtime() + 40_000
            var loaded = false
            while (!loaded && SystemClock.elapsedRealtime() < loadDeadline) {
                compose.waitForIdle()
                instrumentation.runOnMainSync { loaded = pageView(host.window.decorView)?.progress == 100 }
                if (!loaded) Thread.sleep(200)
            }
            assertTrue("Browser watch page did not finish", loaded)
            dismissSystemDialogs()
            Thread.sleep(2500)
            val probe = WebProbe {
                pageView(host.window.decorView) ?: error("WebView not found after load")
            }
            val collect = COLLECT.replace("__READY__", readyExpression)
            var payload = probe.evaluate(collect)
            val contentDeadline = SystemClock.elapsedRealtime() + 30_000
            while (!payload.optBoolean("ready") && SystemClock.elapsedRealtime() < contentDeadline) {
                Thread.sleep(500)
                compose.waitForIdle()
                payload = probe.evaluate(collect)
            }
            val width = payload.optJSONObject("viewport")?.optInt("width") ?: 0
            val json = payload.toString(2)
            DeviceEvidence.writeJson("$scene-$width.json", json)
            DeviceEvidence.shell("run-as com.hhst.litube.debug cp files/device-evidence/$scene-$width.json /data/local/tmp/ 2>/dev/null")
            DeviceEvidence.captureScene("$scene-$width")
            assertTrue("Expected watch page, got ${payload.optString("url")}", Uri.parse(payload.optString("url")).let { it.host?.endsWith("youtube.com") == true && it.path == "/watch" })
            assertTrue("Sampled page reported it was not ready: probe=${payload.optString("probe")} tags=${payload.optString("customTags")}", payload.optBoolean("ready"))
            val titleText = payload.optJSONObject("title")?.optString("text") ?: ""
            val bodyText = payload.optString("bodyText")
            assertTrue("Watch page has no title text: body=${bodyText.take(120)}", titleText.length > 5 || bodyText.contains("Blender") || bodyText.contains("Bunny"))
        } finally {
            scenario.close()
        }
    }

    @Test fun collectWatchGeometryReference() = sample(
        "https://m.youtube.com/watch?v=aqz-KE-bpKQ&hl=en", "watch-page", READY,
    )

    @Test fun collectUnmodifiedWatchReference() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        android.webkit.WebView.setDataDirectorySuffix("clean-watch-reference")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            for ((width, height) in listOf(390 to 844, 1280 to 800)) {
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size " + width + "x" + height)
                Thread.sleep(500)
                val scenario = ActivityScenario.launch<androidx.activity.ComponentActivity>(
                    Intent(instrumentation.targetContext, androidx.activity.ComponentActivity::class.java))
                lateinit var web: WebView
                scenario.onActivity { host ->
                    web = WebView(host).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewClient = android.webkit.WebViewClient()
                    }
                    host.setContentView(web)
                    web.loadUrl("https://m.youtube.com/watch?v=aqz-KE-bpKQ")
                }
                try {
                    val loadDeadline = SystemClock.elapsedRealtime() + 40_000
                    var loaded = false
                    while (!loaded && SystemClock.elapsedRealtime() < loadDeadline) {
                        instrumentation.runOnMainSync { loaded = web.progress == 100 && web.url?.contains("/watch") == true }
                        if (!loaded) Thread.sleep(200)
                    }
                    assertTrue("Reference page did not load", loaded)
                    Thread.sleep(2000)
                    val probe = WebProbe { web }
                    val script = COLLECT.replace("__READY__", READY)
                    var payload = probe.evaluate(script)
                    val deadline = SystemClock.elapsedRealtime() + 45_000
                    while (!payload.optBoolean("ready") && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(500)
                        payload = probe.evaluate(script)
                    }
                    Thread.sleep(2500)
                    payload = probe.evaluate(script)
                    DeviceEvidence.writeJson("watch-clean-" + width + ".json", payload.toString(2))
                    DeviceEvidence.captureScene("watch-clean-" + width)
                    assertTrue(payload.optBoolean("ready"))
                } finally {
                    scenario.onActivity { web.destroy() }
                    scenario.close()
                }
            }
        } finally {
            DeviceEvidence.shell("wm density " + originalDensity)
            DeviceEvidence.shell("wm size " + originalSize)
        }
    }

    private companion object {
        const val READY =
            "(document.body.innerText || '').includes('Blender') || document.querySelector('ytm-watch-metadata, #movie_player, video') !== null"

        val COLLECT = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x, y:r.y, width:r.width, height:r.height, bottom:r.bottom, right:r.right}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize, fontWeight:s.fontWeight, lineHeight:s.lineHeight,
              fontFamily:s.fontFamily, padding:s.padding, margin:s.margin, color:s.color, backgroundColor:s.backgroundColor,
              borderRadius:s.borderRadius, display:s.display, aspectRatio:s.aspectRatio, overflow:s.overflow, textOverflow:s.textOverflow,
              maxHeight:s.maxHeight, maxWidth:s.maxWidth, gap:s.gap, boxShadow:s.boxShadow, alignItems:s.alignItems, flexDirection:s.flexDirection,
              position:s.position, top:s.top, left:s.left, width:s.width, height:s.height, minWidth:s.minWidth, textAlign:s.textAlign,
              boxSizing:s.boxSizing, border:s.border, webkitLineClamp:s.webkitLineClamp, opacity:s.opacity}; };
            const leafText = e => { const n = [...e.querySelectorAll('*')].filter(c => c.childElementCount === 0 && (c.innerText || '').trim()).map(c => (c.innerText || '').trim()); return n.join(' ').slice(0, 400); };
            const svgOf = e => { const s = e.querySelector('svg'); return s ? s.outerHTML.slice(0, 1600) : ''; };
            const firstVisible = sel => [...document.querySelectorAll(sel)].find(vis) || null;
            const full = e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e), style: S(e),
              text: (e.childElementCount === 0 ? (e.innerText || '') : '').slice(0, 120),
              label: (e.getAttribute('aria-label') || '').slice(0, 60), svg: svgOf(e)});
            const out = {
              url: location.href,
              viewport: {width: innerWidth, height: innerHeight, clientWidth: document.documentElement.clientWidth,
                dpr: devicePixelRatio, scrollbar: innerWidth - document.documentElement.clientWidth},
              ua: navigator.userAgent,
              html: {lang: document.documentElement.lang, cls: String(document.documentElement.className),
                bodyCls: String(document.body.className), colorScheme: getComputedStyle(document.documentElement).colorScheme},
              ready: __READY__,
              probe: 'meta=' + (document.querySelector('ytm-watch-metadata') ? 1 : 0),
              customTags: [...new Set([...document.querySelectorAll('*')].map(e => e.tagName).filter(t => t.includes('-')))].slice(0, 80).join(','),
              topChildren: [...document.body.children].slice(0, 20).map(e => e.tagName + '.' + String(e.className).slice(0, 40)),
              bodyText: (document.body.innerText || '').slice(0, 600),
            };

            const player = firstVisible('ytm-player, #movie_player, ytm-video-player, video');
            if (player) {
              out.player = {tag: player.tagName, cls: String(player.className).slice(0, 120), rect: R(player), style: S(player),
                parent: player.parentElement ? {tag: player.parentElement.tagName, cls: String(player.parentElement.className).slice(0, 100), rect: R(player.parentElement), style: S(player.parentElement)} : null,
                html: player.outerHTML.replace(/\ssrc="[^"]{40,}"/g, ' src="..."').slice(0, 2500)};
              const ancestors = [];
              let e = player.parentElement;
              for (let i = 0; e && i < 6; i++, e = e.parentElement) ancestors.push({tag: e.tagName, cls: String(e.className).slice(0, 100), rect: R(e), style: S(e)});
              out.playerAncestors = ancestors;
            }

            const slimAll = [...document.querySelectorAll('ytm-slim-video-metadata-section-renderer, ytm-slim-video-information-renderer, ytm-slim-video-action-bar-renderer, ytm-subscribe-button-renderer, ytm-crawler-description')];
            out.slimAll = slimAll.map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 100), rect: R(e), style: S(e),
              display: getComputedStyle(e).display, visibility: getComputedStyle(e).visibility, opacity: getComputedStyle(e).opacity,
              text: (e.innerText || '').slice(0, 240)}));

            const slimSection = slimAll.find(e => e.tagName === 'YTM-SLIM-VIDEO-METADATA-SECTION-RENDERER') || null;
            out.slimSection = slimSection ? {tag: slimSection.tagName, cls: String(slimSection.className).slice(0, 120), rect: R(slimSection), style: S(slimSection),
              text: (slimSection.innerText || '').slice(0, 400),
              descendants: [...slimSection.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 80).map(o => full(o.n))} : null;

            const information = slimAll.find(e => e.tagName === 'YTM-SLIM-VIDEO-INFORMATION-RENDERER') || null;
            out.information = information ? {tag: information.tagName, cls: String(information.className).slice(0, 100), rect: R(information), style: S(information),
              children: [...information.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 50).map(o => full(o.n))} : null;

            const actionBar = slimAll.find(e => e.tagName === 'YTM-SLIM-VIDEO-ACTION-BAR-RENDERER') || null;
            out.actionBar = actionBar ? {tag: actionBar.tagName, cls: String(actionBar.className).slice(0, 100), rect: R(actionBar), style: S(actionBar),
              children: [...actionBar.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 50).map(o => full(o.n))} : null;

            const subscribe = slimAll.find(e => e.tagName === 'YTM-SUBSCRIBE-BUTTON-RENDERER') || null;
            out.subscribe = subscribe ? {tag: subscribe.tagName, cls: String(subscribe.className).slice(0, 100), rect: R(subscribe), style: S(subscribe),
              text: (subscribe.innerText || '').slice(0, 40),
              children: [...subscribe.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 20).map(o => full(o.n))} : null;

            const likeBtn = [...document.querySelectorAll('like-button-view-model, dislike-button-view-model')].find(e => { const r = e.getBoundingClientRect(); return r.width > 0; }) || null;
            out.likeButton = likeBtn ? {tag: likeBtn.tagName, cls: String(likeBtn.className).slice(0, 100), rect: R(likeBtn), style: S(likeBtn),
              text: (likeBtn.innerText || '').slice(0, 40),
              children: [...likeBtn.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 20).map(o => full(o.n))} : null;

            const titleEl = information ? [...information.querySelectorAll('h1, yt-formatted-string, .title')].find(e => { const r = e.getBoundingClientRect(); return r.width > 0 && (e.innerText || '').length > 5; }) : document.querySelector('h1');
            out.title = titleEl ? {tag: titleEl.tagName, cls: String(titleEl.className).slice(0, 90), rect: R(titleEl), style: S(titleEl), text: (titleEl.innerText || '').slice(0, 200),
              parent: titleEl.parentElement ? {tag: titleEl.parentElement.tagName, cls: String(titleEl.parentElement.className).slice(0, 90), rect: R(titleEl.parentElement), style: S(titleEl.parentElement)} : null} : null;

            const channelRow = information;
            out.channel = channelRow ? {tag: channelRow.tagName, cls: String(channelRow.className).slice(0, 100), rect: R(channelRow), style: S(channelRow),
              name: leafText(channelRow).slice(0, 120),
              children: [...channelRow.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 40).map(o => full(o.n))} : null;

            const avatar = [...document.querySelectorAll('ytm-slim-video-information-renderer img, ytm-watch-metadata #channel img, #owner img, img[src*="yt3"], .ytProfileIconImage')].find(e => { const r = e.getBoundingClientRect(); return r.width > 0; }) || null;
            out.avatar = avatar ? {tag: avatar.tagName, cls: String(avatar.className).slice(0, 80), rect: R(avatar), style: S(avatar),
              src: (avatar.currentSrc || avatar.src || '').slice(0, 120)} : null;

            const actionButtons = [...document.querySelectorAll('ytm-slim-video-action-bar-renderer button, ytm-slim-video-action-bar-renderer [role="button"], ytm-subscribe-button-renderer button, like-button-view-model button, dislike-button-view-model button, ytm-slim-video-action-bar-renderer a')]
              .filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }).slice(0, 24);
            out.actions = actionButtons.map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 90), rect: R(e), style: S(e),
              text: (e.innerText || e.getAttribute('aria-label') || '').trim().slice(0, 40),
              label: (e.getAttribute('aria-label') || '').slice(0, 60), svg: svgOf(e),
              parent: e.parentElement ? {tag: e.parentElement.tagName, cls: String(e.parentElement.className).slice(0, 80), rect: R(e.parentElement), style: S(e.parentElement)} : null}));

            const desc = [...document.querySelectorAll('ytm-crawler-description, ytm-text-inline-expander, #description-inline-expander')].find(e => { const r = e.getBoundingClientRect(); return r.width > 0; }) || null;
            out.description = desc ? {tag: desc.tagName, cls: String(desc.className).slice(0, 110), rect: R(desc), style: S(desc),
              text: (desc.innerText || '').slice(0, 500),
              children: [...desc.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 30).map(o => full(o.n))} : null;

            const relatedItems = [...document.querySelectorAll('ytm-compact-video-renderer, ytm-video-with-context-renderer, ytm-compact-playlist-renderer, ytm-media-item')].filter(vis);
            out.relatedCount = relatedItems.length;
            out.related = relatedItems.slice(0, 6).map(e => ({tag: e.tagName, cls: String(e.className).slice(0, 110), rect: R(e), style: S(e),
              text: (e.innerText || '').slice(0, 160),
              thumb: (() => { const t = e.querySelector('.media-item-thumbnail-container, yt-image, ytm-thumbnail'); return t ? {rect: R(t), style: S(t)} : null; })(),
              title: (() => { const t = e.querySelector('.media-item-headline, h3, yt-formatted-string'); return t ? {rect: R(t), style: S(t), text: (t.innerText || '').slice(0, 80)} : null; })(),
              metadata: (() => { const m = e.querySelector('.media-item-metadata'); return m ? {rect: R(m), style: S(m), text: leafText(m).slice(0, 120)} : null; })()}));

            const results = firstVisible('ytm-single-column-watch-next-results, ytm-item-section-renderer, ytm-section-list-renderer');
            out.relatedContainer = results ? {tag: results.tagName, cls: String(results.className).slice(0, 110), rect: R(results), style: S(results)} : null;
            const watchNext = firstVisible('ytm-single-column-watch-next-results-renderer');
            out.watchNext = watchNext ? {rect: R(watchNext),
              children: [...watchNext.children].slice(0, 10).map(c => ({tag: c.tagName, cls: String(c.className).slice(0, 90), rect: R(c), text: (c.innerText || '').slice(0, 60)}))} : null;

            const comments = [...document.querySelectorAll('ytm-item-section-renderer')]
              .filter(e => vis(e) && (e.innerText || '').includes('Comments') && (e.innerText || '').length < 400)[0] || null;
            out.comments = comments ? {tag: comments.tagName, cls: String(comments.className).slice(0, 110), rect: R(comments), style: S(comments),
              text: (comments.innerText || '').slice(0, 300),
              descendants: [...comments.querySelectorAll('*')].map(e => ({n: e, r: e.getBoundingClientRect()})).filter(o => o.r.width > 0 && o.r.height > 0).slice(0, 60).map(o => full(o.n))} : null;

            const header = firstVisible('ytm-header-bar, ytm-mobile-topbar-renderer, ytm-pivot-header-renderer');
            out.header = header ? {tag: header.tagName, cls: String(header.className).slice(0, 100), rect: R(header), style: S(header),
              controls: [...header.querySelectorAll('button, [role="button"], a')].filter(vis).slice(0, 10).map(c => ({tag: c.tagName, cls: String(c.className).slice(0, 70),
                label: (c.getAttribute('aria-label') || c.innerText || '').slice(0, 40), rect: R(c), style: S(c), svg: svgOf(c)}))} : null;

            const nav = firstVisible('ytm-pivot-bar-renderer, ytm-bottom-navigation, [role="tablist"]');
            out.nav = nav ? {tag: nav.tagName, cls: String(nav.className).slice(0, 90), rect: R(nav), style: S(nav),
              children: [...nav.children].filter(vis).map(c => ({tag: c.tagName, cls: String(c.className).slice(0, 60), rect: R(c), label: (c.getAttribute('aria-label') || c.innerText || '').slice(0, 20)}))} : null;

            out.icons = [...document.querySelectorAll('svg')].filter(vis).slice(0, 20)
              .map(e => ({cls: String(e.className.baseVal || '').slice(0, 40), rect: R(e), parent: e.parentElement ? e.parentElement.tagName : '',
                parentLabel: e.parentElement ? (e.parentElement.getAttribute('aria-label') || '') : '', html: e.outerHTML.slice(0, 800)}));

            out.media = ['(orientation: portrait)', '(orientation: landscape)', '(min-aspect-ratio: 13/9)', '(min-width: 931px)', '(min-width: 550px)']
              .map(q => ({query:q, matches:matchMedia(q).matches}));

            out.css = [];
            const wanted = /slim-video|subscribe-button|action-bar|inline-expander|crawler-description|single-column-watch|watch-metadata/;
            const walk = (rules, contexts = []) => {
              for (const rule of rules) {
                if (rule.selectorText) {
                  if (!wanted.test(rule.selectorText)) continue;
                  let css = rule.cssText;
                  for (const context of [...contexts].reverse()) css = context + ' { ' + css + ' }';
                  out.css.push(css);
                  if (out.css.length > 200) return;
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
              if (out.css.length > 200) break;
            }
            return out;
        """.trimIndent()
    }
}
