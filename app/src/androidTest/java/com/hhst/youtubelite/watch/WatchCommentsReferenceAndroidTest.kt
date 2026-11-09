package com.hhst.youtubelite.watch

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only sampling for the comments panel: a plain WebView in a separate data directory, with no app scripts or account/session state injected. */
class WatchCommentsReferenceAndroidTest {
    @Test
    fun collectExpandedRepliesAndWidePanel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebView.setDataDirectorySuffix("clean-comments-replies")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            val requestedWidth = InstrumentationRegistry.getArguments().getString("referenceWidth")?.toIntOrNull()
            val viewports = requestedWidth?.let { listOf(it to 800) } ?: listOf(390 to 844, 1280 to 800)
            for ((width, height) in viewports) {
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size ${width}x$height")
                Thread.sleep(600)
                val scenario = ActivityScenario.launch<ComponentActivity>(
                    Intent(instrumentation.targetContext, ComponentActivity::class.java),
                )
                lateinit var web: WebView
                var webOrigin = intArrayOf(0, 0)
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
                    web.loadUrl("https://m.youtube.com/watch?v=aqz-KE-bpKQ&hl=en")
                }
                try {
                    val loadDeadline = SystemClock.elapsedRealtime() + 45_000
                    var loaded = false
                    while (!loaded && SystemClock.elapsedRealtime() < loadDeadline) {
                        instrumentation.runOnMainSync {
                            loaded = web.progress == 100 && web.url?.contains("/watch") == true
                        }
                        if (!loaded) Thread.sleep(200)
                    }
                    assertTrue("Reference page did not load", loaded)
                    Thread.sleep(2_000)
                    dismissSystemDialogs()
                    instrumentation.runOnMainSync { web.getLocationOnScreen(webOrigin) }
                    val probe = WebProbe { web }
                    val entry = probeUntil(probe, ENTRY, 20_000) { it.optBoolean("found") }
                    if (!entry.optBoolean("found")) continue
                    val rect = entry.optJSONObject("target")!!.optJSONObject("rect")!!
                    DeviceEvidence.shell(
                        "input tap ${webOrigin[0] + rect.optInt("centerX")} ${webOrigin[1] + rect.optInt("centerY")}",
                    )
                    var panel = probeUntil(probe, DETAIL, 15_000) { it.optBoolean("hasComments") }
                    if (!panel.optBoolean("hasComments")) continue
                    DeviceEvidence.writeJson("watch-comments-$width-detail.json", panel.toString(2))
                    DeviceEvidence.writeJson("watch-comments-$width-header.json", probe.evaluate(HEADER).toString(2))
                    DeviceEvidence.captureScene("watch-comments-$width-panel-wide")
                    val clicked = probe.evaluate(EXPAND_REPLIES)
                    DeviceEvidence.writeJson("watch-comments-$width-expand-click.json", clicked.toString(2))
                    if (clicked.optBoolean("clicked")) {
                        val box = clicked.optJSONObject("rect")!!
                        Thread.sleep(400)
                        DeviceEvidence.shell(
                            "input tap ${webOrigin[0] + box.optInt("x") + box.optInt("width") / 2} " +
                                "${webOrigin[1] + box.optInt("y") + box.optInt("height") / 2}",
                        )
                    }
                    Thread.sleep(1_500)
                    val expanded = probeUntil(probe, EXPANDED, 15_000) { it.optBoolean("expanded") }
                    DeviceEvidence.writeJson("watch-comments-$width-expanded.json", expanded.toString(2))
                    assertTrue("No replies were expanded", expanded.optBoolean("expanded"))
                    probe.evaluate("const reply = document.querySelector('ytm-comment-replies-renderer ytm-comment-renderer'); reply?.scrollIntoView({block:'center'}); return {found: !!reply};")
                    Thread.sleep(500)
                    DeviceEvidence.captureScene("watch-comments-$width-expanded")
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

    private fun webView(root: View): WebView? {
        if (root is WebView) return root
        if (root is android.view.ViewGroup) {
            for (index in 0 until root.childCount) webView(root.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun dismissSystemDialogs() {
        val dump = DeviceEvidence.shell(
            "uiautomator dump /data/local/tmp/comments-ui.xml >/dev/null 2>&1; " +
                "cat /data/local/tmp/comments-ui.xml 2>/dev/null",
        )
        if (!dump.contains("Don't allow") && !dump.contains("Don’t allow")) return
        val match = Regex("text=\"Don.t allow\"[^>]*bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(dump) ?: return
        val (left, top, right, bottom) = match.destructured.toList().map { it.toInt() }
        DeviceEvidence.shell("input tap ${(left + right) / 2} ${(top + bottom) / 2}")
        Thread.sleep(400)
    }

    private fun probeUntil(probe: WebProbe, source: String, timeoutMs: Long, ready: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var payload = probe.evaluate(source)
        while (!ready(payload) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(350)
            payload = probe.evaluate(source)
        }
        return payload
    }

    @Test
    fun collectCommentsPanelReference() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebView.setDataDirectorySuffix("clean-comments-reference")
        val originalSize = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val originalDensity = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            for ((width, height) in listOf(390 to 844, 1280 to 800)) {
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size ${width}x$height")
                Thread.sleep(600)
                val scenario = ActivityScenario.launch<ComponentActivity>(
                    Intent(instrumentation.targetContext, ComponentActivity::class.java),
                )
                lateinit var web: WebView
                var webOrigin = intArrayOf(0, 0)
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
                    web.loadUrl("https://m.youtube.com/watch?v=aqz-KE-bpKQ&hl=en")
                }
                try {
                    val loadDeadline = SystemClock.elapsedRealtime() + 45_000
                    var loaded = false
                    while (!loaded && SystemClock.elapsedRealtime() < loadDeadline) {
                        instrumentation.runOnMainSync {
                            loaded = web.progress == 100 && web.url?.contains("/watch") == true
                        }
                        if (!loaded) Thread.sleep(200)
                    }
                    assertTrue("Reference page did not load", loaded)
                    Thread.sleep(2_000)
                    dismissSystemDialogs()
                    instrumentation.runOnMainSync {
                        web.getLocationOnScreen(webOrigin)
                    }
                    val probe = WebProbe { web }
                    val entry = probeUntil(probe, ENTRY, 20_000) { it.optBoolean("found") }
                    DeviceEvidence.writeJson("watch-comments-$width-entry.json", entry.toString(2))
                    DeviceEvidence.captureScene("watch-comments-$width-entry")
                    if (!entry.optBoolean("found")) continue
                    val rect = entry.optJSONObject("target")!!.optJSONObject("rect")!!
                    DeviceEvidence.shell(
                        "input tap ${webOrigin[0] + rect.optInt("centerX")} ${webOrigin[1] + rect.optInt("centerY")}",
                    )
                    var panel = probeUntil(probe, PANEL, 15_000) { it.optBoolean("ready") }
                    if (!panel.optBoolean("ready")) {
                        // Fall back to an element click for hosts where the tap lands on a scrolling page.
                        probe.evaluate(CLICK_ENTRY)
                        panel = probeUntil(probe, PANEL, 15_000) { it.optBoolean("ready") }
                    }
                    DeviceEvidence.writeJson("watch-comments-$width-panel.json", panel.toString(2))
                    DeviceEvidence.captureScene("watch-comments-$width-panel")
                    if (!panel.optBoolean("ready")) continue
                    var detail = probeUntil(probe, DETAIL, 20_000) { it.optBoolean("hasComments") }
                    if (!detail.optBoolean("hasComments")) {
                        // The panel list is lazy: scroll its internal container and retry once.
                        repeat(6) {
                            probe.evaluate(SCROLL_PANEL)
                            Thread.sleep(700)
                        }
                        detail = probeUntil(probe, DETAIL, 10_000) { it.optBoolean("hasComments") }
                    }
                    DeviceEvidence.writeJson("watch-comments-$width-detail.json", detail.toString(2))
                    DeviceEvidence.captureScene("watch-comments-$width-detail")

                    val sort = probeUntil(probe, SORT_ENTRY, 8_000) { it.optBoolean("found") }
                    DeviceEvidence.writeJson("watch-comments-$width-sort-entry.json", sort.toString(2))
                    if (sort.optBoolean("found")) {
                        val sortRect = sort.optJSONObject("target")!!.optJSONObject("rect")!!
                        DeviceEvidence.shell(
                            "input tap ${webOrigin[0] + sortRect.optInt("centerX")} ${webOrigin[1] + sortRect.optInt("centerY")}",
                        )
                        val sortOpen = probeUntil(probe, SORT_MENU, 8_000) { it.optBoolean("ready") }
                        DeviceEvidence.writeJson("watch-comments-$width-sort.json", sortOpen.toString(2))
                        DeviceEvidence.captureScene("watch-comments-$width-sort")
                    }

                    val panelAgain = probeUntil(probe, PANEL, 5_000) { it.optBoolean("ready") }
                    if (panelAgain.optBoolean("ready")) {
                        val replies = probeUntil(probe, REPLY_ENTRY, 8_000) { it.optBoolean("found") }
                        DeviceEvidence.writeJson("watch-comments-$width-replies-entry.json", replies.toString(2))
                        if (replies.optBoolean("found")) {
                            val replyRect = replies.optJSONObject("target")!!.optJSONObject("rect")!!
                            DeviceEvidence.shell(
                                "input tap ${webOrigin[0] + replyRect.optInt("centerX")} ${webOrigin[1] + replyRect.optInt("centerY")}",
                            )
                            val expanded = probeUntil(probe, PANEL, 8_000) { it.optBoolean("replyReady") }
                            DeviceEvidence.writeJson("watch-comments-$width-replies.json", expanded.toString(2))
                            DeviceEvidence.captureScene("watch-comments-$width-replies")
                        }
                    }
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
        const val ENTRY = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; };
            const rect = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom,centerX:Math.round(r.x+r.width/2),centerY:Math.round(r.y+r.height/2)}; };
            const textOf = e => ((e.innerText || e.textContent || e.getAttribute('aria-label') || '') + '').trim();
            const leaves = [...document.querySelectorAll('*')].filter(e => vis(e) && e.childElementCount === 0 && /^Comments\s*[0-9.,KMB]*${'$'}/i.test(textOf(e)));
            const leaf = leaves[0] || null;
            let target = leaf;
            for (let i = 0; target && i < 6; i++) {
              const tag = target.tagName;
              const role = target.getAttribute && target.getAttribute('role');
              if (tag === 'BUTTON' || tag === 'A' || role === 'button' || /comments-entry|comment-teaser|item-section/i.test(String(target.className))) break;
              target = target.parentElement;
            }
            return {found: !!(leaf && target), leaf: leaf ? {tag: leaf.tagName, text: textOf(leaf), rect: rect(leaf)} : null,
              target: target ? {tag: target.tagName, cls: String(target.className).slice(0, 120), text: textOf(target).slice(0, 160), rect: rect(target)} : null,
              body: (document.body.innerText || '').slice(0, 600)};
        """

        const val CLICK_ENTRY = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const textOf = e => ((e.innerText || e.textContent || e.getAttribute('aria-label') || '') + '').trim();
            const leaf = [...document.querySelectorAll('*')].find(e => vis(e) && e.childElementCount === 0 && /^Comments\s*[0-9.,KMB]*${'$'}/i.test(textOf(e)));
            let target = leaf; for (let i = 0; target && i < 6; i++) { const tag = target.tagName; const role = target.getAttribute && target.getAttribute('role'); if (tag === 'BUTTON' || tag === 'A' || role === 'button' || /comments-entry|comment-teaser|item-section/i.test(String(target.className))) break; target = target.parentElement; }
            if (target) target.click();
            return {clicked: !!target};
        """

        const val PANEL = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,fontFamily:s.fontFamily,padding:s.padding,margin:s.margin,color:s.color,backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,display:s.display,overflow:s.overflow,gap:s.gap,boxShadow:s.boxShadow,position:s.position,top:s.top,left:s.left,width:s.width,height:s.height,zIndex:s.zIndex,opacity:s.opacity,transform:s.transform}; };
            const textOf = e => ((e.innerText || e.textContent || '') + '').trim();
            const svgOf = e => { const s = e.querySelector('svg'); return s ? s.outerHTML : ''; };
            const panel = [...document.querySelectorAll('ytm-comments-section, ytm-comment-section-renderer, ytm-comments-header-renderer, [role="dialog"], ytm-bottom-sheet-renderer')].find(e => vis(e) && /Add a comment|Comments|Sort/i.test(textOf(e))) || null;
            const roots = panel ? [panel] : [];
            const all = [...document.querySelectorAll('*')].filter(vis);
            const relevant = all.filter(e => /comment|reply|sort|dialog|sheet/i.test(e.tagName + ' ' + String(e.className))).slice(0, 90);
            const buttons = all.filter(e => e.tagName === 'BUTTON' || e.getAttribute('role') === 'button' || e.tagName === 'YTM-BUTTON-RENDERER').slice(0, 50);
            const out = {ready: !!panel, url: location.href, scrollY: scrollY, viewport: {width: innerWidth, height: innerHeight},
              body: textOf(document.body).slice(0, 1200),
              panel: panel ? {tag: panel.tagName, cls: String(panel.className).slice(0, 120), rect: R(panel), style: S(panel), text: textOf(panel).slice(0, 500)} : null,
              relevant: relevant.map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), text:textOf(e).slice(0,120), rect:R(e), style:S(e), svg:svgOf(e)})),
              buttons: buttons.map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), text:textOf(e).slice(0,80), label:(e.getAttribute('aria-label')||'').slice(0,80), rect:R(e), style:S(e), svg:svgOf(e)})),
              iconSvg: panel ? [...panel.querySelectorAll('svg')].slice(0,20).map(e => ({rect:R(e), html:e.outerHTML})) : []};
            out.replyReady = relevant.some(e => /Show replies|Hide replies|View replies|Read replies|replies/i.test(textOf(e)) && /Show replies|View replies|Read replies/i.test(textOf(e))) || /replies/i.test(out.body);
            return out;
        """

        const val DETAIL = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,fontFamily:s.fontFamily,padding:s.padding,margin:s.margin,color:s.color,backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,display:s.display,overflow:s.overflow,overflowY:s.overflowY,gap:s.gap,boxShadow:s.boxShadow,position:s.position,top:s.top,left:s.left,width:s.width,height:s.height,zIndex:s.zIndex,opacity:s.opacity}; };
            const textOf = e => ((e.innerText || e.textContent || e.getAttribute('aria-label') || '') + '').trim();
            const panel = document.querySelector('ytm-engagement-panel-section-list-renderer.engagement-panel-comments-section') ||
              [...document.querySelectorAll('ytm-engagement-panel-section-list-renderer')].find(e => vis(e) && /Comments/i.test(textOf(e))) || null;
            const root = panel || document.body;
            const ancestors = []; let a = panel;
            for (let i = 0; a && i < 8; i++, a = a.parentElement) ancestors.push({tag:a.tagName, cls:String(a.className).slice(0,120), rect:R(a), style:S(a), text:textOf(a).slice(0,120)});
            const all = [...root.querySelectorAll('*')].filter(vis);
            const comments = all.filter(e => /COMMENT/i.test(e.tagName) && !/TEASER|SIMPLEBOX|HEADER/i.test(e.tagName));
            const scrollables = all.filter(e => e.scrollHeight > e.clientHeight + 2 || e.scrollWidth > e.clientWidth + 2)
              .map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), rect:R(e), style:S(e), scrollTop:e.scrollTop, scrollHeight:e.scrollHeight, clientHeight:e.clientHeight, scrollLeft:e.scrollLeft, scrollWidth:e.scrollWidth, clientWidth:e.clientWidth}));
            const buttons = all.filter(e => e.tagName === 'BUTTON' || e.getAttribute('role') === 'button' || /BUTTON-RENDERER/i.test(e.tagName))
              .map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), text:textOf(e).slice(0,120), label:(e.getAttribute('aria-label')||'').slice(0,120), rect:R(e), style:S(e), html:e.outerHTML.slice(0,900)}));
            const textLeaves = all.filter(e => e.childElementCount === 0 && textOf(e)).slice(0, 120)
              .map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), text:textOf(e).slice(0,180), rect:R(e), style:S(e)}));
            const svgs = [...root.querySelectorAll('svg')].slice(0, 40).map(e => ({rect:R(e), html:e.outerHTML, parentLabel:e.parentElement ? (e.parentElement.getAttribute('aria-label')||'') : ''}));
            return {ready: !!panel, hasComments: comments.length > 0 || /No comments|Comments are turned off|Comments are disabled/i.test(textOf(root)),
              panel: panel ? {tag:panel.tagName, cls:String(panel.className).slice(0,120), rect:R(panel), style:S(panel), text:textOf(panel).slice(0,700), outer:panel.outerHTML.slice(0,5000)} : null,
              ancestors: ancestors, comments: comments.slice(0,40).map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), text:textOf(e).slice(0,220), rect:R(e), style:S(e)})),
              scrollables: scrollables, buttons: buttons, textLeaves: textLeaves, svgs: svgs,
              body: textOf(document.body).slice(0, 3000)};
        """

        const val SCROLL_PANEL = """
            const panel = document.querySelector('ytm-engagement-panel-section-list-renderer.engagement-panel-comments-section') ||
              [...document.querySelectorAll('ytm-engagement-panel-section-list-renderer')].find(e => /Comments/i.test(e.innerText || '')) || null;
            if (!panel) return {scrolled:false};
            const all = [panel, ...panel.querySelectorAll('*')];
            const scrollable = all.filter(e => e.scrollHeight > e.clientHeight + 2).sort((a,b) => b.scrollHeight - a.scrollHeight)[0] || panel;
            scrollable.scrollTop = Math.min(scrollable.scrollHeight, scrollable.scrollTop + Math.max(400, scrollable.clientHeight * 0.8));
            scrollable.dispatchEvent(new Event('scroll', {bubbles:true}));
            return {scrolled:true, tag:scrollable.tagName, top:scrollable.scrollTop, height:scrollable.scrollHeight, client:scrollable.clientHeight};
        """

        const val HEADER = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,color:s.color,backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,padding:s.padding,margin:s.margin,borderTop:s.borderTop,borderBottom:s.borderBottom,boxShadow:s.boxShadow,display:s.display,alignItems:s.alignItems,justifyContent:s.justifyContent,gap:s.gap,position:s.position,height:s.height,width:s.width}; };
            const textOf = e => ((e.innerText || e.textContent || e.getAttribute('aria-label') || '') + '').trim();
            const panel = document.querySelector('ytm-engagement-panel-section-list-renderer.engagement-panel-comments-section');
            const title = [...(panel || document.body).querySelectorAll('span, h2, div')].find(e => vis(e) && textOf(e) === 'Comments' && e.childElementCount === 0);
            const chain = []; let e = title;
            for (let i = 0; e && i < 6; i++, e = e.parentElement) chain.push({tag:e.tagName, cls:String(e.className).slice(0,100), rect:R(e), style:S(e), text:textOf(e).slice(0,80), html:e.outerHTML.slice(0,400)});
            const closeCandidates = panel ? [...panel.querySelectorAll('button, [role="button"]')].filter(vis).filter(b => /close/i.test(b.getAttribute('aria-label') || '') || b.querySelector('svg path[d^="M17.293"]')) : [];
            const close = closeCandidates.find(e => R(e).width >= 24 && R(e).height >= 24) || null;
            const dividers = panel ? [...panel.querySelectorAll('*')].filter(e => { const s = getComputedStyle(e); const r = e.getBoundingClientRect(); return r.height > 0 && (s.borderBottomWidth === '1px' || s.borderTopWidth === '1px'); }).slice(0, 12).map(e => ({tag:e.tagName, cls:String(e.className).slice(0,100), rect:R(e), style:S(e)})) : [];
            return {chain: chain,
              close: close ? {tag:close.tagName, label:close.getAttribute('aria-label'), rect:R(close), style:S(close), svg:close.querySelector('svg') ? close.querySelector('svg').outerHTML : ''} : null,
              closeCandidates: closeCandidates.map(b => ({label:b.getAttribute('aria-label'), rect:R(b), style:S(b)})),
              dividers: dividers,
              svgs: panel ? [...panel.querySelectorAll('svg')].slice(0, 12).map(s => ({rect:R(s), parentLabel: s.parentElement ? (s.parentElement.getAttribute('aria-label') || '') : '', html: s.outerHTML})) : [],
              panel: panel ? {rect:R(panel), style:S(panel)} : null};
        """

        const val EXPAND_REPLIES = """
            const textOf = e => ((e.innerText || e.textContent || '') + '').trim().replace(/\s+/g,' ');
            const candidates = [...document.querySelectorAll('button, [role="button"], ytm-button-renderer')]
              .filter(b => textOf(b) === 'Show more replies');
            const target = candidates.find(b => b.tagName === 'BUTTON') || candidates[0] || null;
            if (!target) return {clicked:false};
            target.scrollIntoView({block:'center'});
            const r = target.getBoundingClientRect();
            return {clicked:true, tag:target.tagName, rect:{x:r.x,y:r.y,width:r.width,height:r.height},
              candidates: candidates.map(b => ({tag:b.tagName, rect:(() => { const q = b.getBoundingClientRect(); return {x:q.x,y:q.y,width:q.width,height:q.height}; })()}))};
        """

        const val EXPANDED = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,color:s.color,backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,padding:s.padding,margin:s.margin,display:s.display,gap:s.gap,position:s.position,width:s.width,height:s.height,borderTop:s.borderTop,borderBottom:s.borderBottom}; };
            const textOf = e => ((e.innerText || e.textContent || e.getAttribute('aria-label') || '') + '').trim().replace(/\s+/g,' ');
            const panel = document.querySelector('ytm-engagement-panel-section-list-renderer.engagement-panel-comments-section');
            const root = panel || document.body;
            const threads = [...root.querySelectorAll('ytm-comment-thread-renderer')].filter(vis).slice(0, 2);
            const all = [...root.querySelectorAll('*')].filter(vis);
            const replyRenderers = [...root.querySelectorAll('ytm-comment-replies-renderer ytm-comment-renderer, ytm-comment-replies-renderer ytm-comment-view-model')].filter(vis);
            const hide = all.filter(e => /^Hide replies$/.test(textOf(e))).map(e => ({tag:e.tagName, text:textOf(e), rect:R(e), style:S(e)}));
            const buttons = all.filter(e => e.tagName === 'BUTTON' || /BUTTON-RENDERER/.test(e.tagName) || e.getAttribute('role') === 'button')
              .map(e => ({tag:e.tagName, text:textOf(e).slice(0,80), label:(e.getAttribute('aria-label')||'').slice(0,80), rect:R(e), style:S(e)})).slice(0, 30);
            const avatars = [...root.querySelectorAll('ytm-profile-icon')].filter(vis).slice(0, 8).map(e => ({rect:R(e), style:S(e), html:e.outerHTML.slice(0, 600)}));
            const bodies = [...root.querySelectorAll('yt-attributed-string, yt-formatted-string, p')].filter(vis).filter(e => { const t = textOf(e); return t.length > 8 && t.length < 400; }).slice(0, 14)
              .map(e => ({tag:e.tagName, cls:String(e.className).slice(0,80), text:textOf(e).slice(0,160), rect:R(e), style:S(e)}));
            return {expanded: replyRenderers.length > 0,
              hideReplies: hide, replyCount: replyRenderers.length,
              threads: threads.map(t => ({rect:R(t), style:S(t), text:textOf(t).slice(0,220),
                children:[...t.children].filter(vis).map(c => ({tag:c.tagName, cls:String(c.className).slice(0,90), rect:R(c), style:S(c), text:textOf(c).slice(0,90)}))})),
              buttons: buttons, avatars: avatars, bodies: bodies,
              panelText: textOf(root).slice(0, 700)};
        """

        const val SORT_ENTRY = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,centerX:Math.round(r.x+r.width/2),centerY:Math.round(r.y+r.height/2)}; };
            const textOf = e => ((e.innerText || e.textContent || e.getAttribute('aria-label') || '') + '').trim();
            const candidates = [...document.querySelectorAll('button, [role="button"], ytm-button-renderer, ytm-sort-filter-button, ytm-comments-header-renderer *')]
              .filter(e => vis(e) && /^((Sort( by)?( comments)?)|Top comments|Newest first)${'$'}/i.test(textOf(e)));
            const target = candidates[0] || null;
            return {found: !!target, target: target ? {tag:target.tagName, cls:String(target.className).slice(0,100), text:textOf(target), rect:R(target), html:target.outerHTML.slice(0,1200)} : null,
              candidates: candidates.slice(0,10).map(e => ({tag:e.tagName, text:textOf(e), rect:R(e)}))};
        """

        const val SORT_MENU = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom}; };
            const S = e => { const s = getComputedStyle(e); return {fontSize:s.fontSize,fontWeight:s.fontWeight,lineHeight:s.lineHeight,color:s.color,backgroundColor:s.backgroundColor,borderRadius:s.borderRadius,padding:s.padding,margin:s.margin,boxShadow:s.boxShadow,position:s.position,display:s.display,gap:s.gap}; };
            const textOf = e => ((e.innerText || e.textContent || '') + '').trim();
            const dialogs = [...document.querySelectorAll('[role="dialog"], ytm-bottom-sheet-renderer, ytm-sheet-view-model, tp-yt-paper-dialog, ytm-menu, ytm-menu-renderer, ytm-sort-filter-sub-menu-renderer')].filter(vis);
            const menu = dialogs.find(e => /Top|Newest|Sort/i.test(textOf(e))) || null;
            const rows = menu ? [...menu.querySelectorAll('*')].filter(vis).filter(e => /^(Top|Newest|Top comments|Newest first)${'$'}/i.test(textOf(e))).map(e => ({tag:e.tagName,text:textOf(e),rect:R(e),style:S(e)})) : [];
            return {ready: !!menu, menu: menu ? {tag:menu.tagName, cls:String(menu.className).slice(0,100), rect:R(menu), style:S(menu), text:textOf(menu).slice(0,200), html:menu.outerHTML.slice(0,2500)} : null, rows:rows,
              dialogs: dialogs.map(e => ({tag:e.tagName, cls:String(e.className).slice(0,80), text:textOf(e).slice(0,120), rect:R(e)}))};
        """

        const val REPLY_ENTRY = """
            const vis = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
            const R = e => { const r = e.getBoundingClientRect(); return {x:r.x,y:r.y,width:r.width,height:r.height,centerX:Math.round(r.x+r.width/2),centerY:Math.round(r.y+r.height/2)}; };
            const textOf = e => ((e.innerText || e.textContent || e.getAttribute('aria-label') || '') + '').trim();
            const candidates = [...document.querySelectorAll('button, [role="button"], ytm-button-renderer, ytm-comment-replies-renderer *')]
              .filter(e => vis(e) && /^(View|Show|Read) ?\d* ?repl(y|ies)|^View replies${'$'}|^Read replies${'$'}|^\d+ repl(y|ies)${'$'}/i.test(textOf(e)));
            const target = candidates[0] || null;
            if (target) target.scrollIntoView({block:'center'});
            return {found: !!target, target: target ? {tag:target.tagName, text:textOf(target), rect:R(target), html:target.outerHTML.slice(0,1000)} : null,
              candidates: candidates.slice(0,10).map(e => ({tag:e.tagName, text:textOf(e), rect:R(e)}))};
        """
    }
}
