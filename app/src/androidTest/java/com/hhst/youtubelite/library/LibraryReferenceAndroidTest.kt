package com.hhst.youtubelite.library

import android.net.Uri
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only public sampling of the signed-out library surfaces: you, history,
 * subscriptions and the notifications prompt; gated behind `-e network 1`.
 */
class LibraryReferenceAndroidTest {
    @Test fun collectSignedOutSurfaces() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        WebView.setDataDirectorySuffix("library-reference")
        val size = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val density = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        val widths = (args.getString("widths") ?: "390,1280").split(',').map(String::toInt)
        try {
            for (width in widths) {
                val height = args.getString("height")?.toInt() ?: if (width == 390) 844 else 800
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size ${width}x$height")
                Thread.sleep(500)
                ActivityScenario.launch(ComponentActivity::class.java).use { host ->
                    lateinit var web: WebView
                    host.onActivity { activity ->
                        web = WebView(activity).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.textZoom = 100
                            webViewClient = android.webkit.WebViewClient()
                        }
                        activity.setContentView(web)
                    }
                    val probe = WebProbe { web }

                    fun ready(): Boolean {
                        var state = org.json.JSONObject()
                        repeat(40) {
                            state = runCatching {
                                probe.evaluate("return {ready: document.readyState === 'complete' && document.fonts.status === 'loaded' && document.body.innerText.length > 40};")
                            }.getOrElse { org.json.JSONObject() }
                            if (state.optBoolean("ready")) return true
                            Thread.sleep(500)
                        }
                        return false
                    }

                    fun load(url: String): Boolean {
                        host.onActivity { web.loadUrl(url) }
                        val ok = ready()
                        Thread.sleep(1600)
                        return ok
                    }

                    fun snapshot(name: String, ready: Boolean) {
                        val json = runCatching { probe.evaluate(SNAPSHOT) }.getOrElse { org.json.JSONObject().put("error", it.message ?: "evaluate failed") }
                        json.put("ready", ready)
                        DeviceEvidence.writeJson("library-$name-$width.json", json.toString(2))
                        DeviceEvidence.captureScene("library-$name-$width")
                    }

                    try {
                        val targets = if (args.getString("surface") == "explore") listOf(
                            "explore" to "https://m.youtube.com/feed/explore?hl=en",
                            "music" to "https://m.youtube.com/channel/UC-9-kyTW8ZkZNDHQJ6FgpwQ?hl=en",
                            "gaming" to "https://m.youtube.com/gaming?hl=en",
                            "news" to "https://m.youtube.com/channel/UCYfdidRxbB8Qhf0Nx7ioOYw?hl=en",
                            "sports" to "https://m.youtube.com/channel/UCEgdi0XIXXZ-qJOFPf4JSKw?hl=en",
                        ) else listOf(
                            "you" to "https://m.youtube.com/feed/you?hl=en",
                            "history" to "https://m.youtube.com/feed/history?hl=en",
                            "subscriptions" to "https://m.youtube.com/feed/subscriptions?hl=en",
                            "channels" to "https://m.youtube.com/feed/channels?hl=en",
                            "notifications" to "https://m.youtube.com/account/notifications?hl=en",
                        )
                        for ((name, url) in targets) {
                            val ok = load(url)
                            snapshot(name, ok)
                            // The prompt pages are short; a second window records the whole card.
                            runCatching {
                                probe.evaluate("const e=document.scrollingElement||document.documentElement; e.scrollTop=400; window.scrollTo(0,400); return {top:e.scrollTop};")
                            }
                            Thread.sleep(800)
                            snapshot("$name-lower", ok)
                        }
                    } finally {
                        host.onActivity { web.destroy() }
                    }
                }
            }
        } finally {
            DeviceEvidence.shell("wm size $size")
            DeviceEvidence.shell("wm density $density")
        }
    }

    /** Signed-in gap samples: the You page needs app persistence params; mobile notifications live under /feed/notifications. */
    @Test fun collectSignedInGaps() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        WebView.setDataDirectorySuffix("library-reference")
        val size = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val density = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        val widths = (args.getString("widths") ?: "390,1280").split(',').map(String::toInt)
        try {
            for (width in widths) {
                val height = args.getString("height")?.toInt() ?: if (width == 390) 844 else 800
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size ${width}x$height")
                Thread.sleep(500)
                ActivityScenario.launch(ComponentActivity::class.java).use { host ->
                    lateinit var web: WebView
                    host.onActivity { activity ->
                        web = WebView(activity).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.textZoom = 100
                            webViewClient = android.webkit.WebViewClient()
                        }
                        activity.setContentView(web)
                    }
                    val probe = WebProbe { web }

                    fun ready(minLen: Int): Boolean {
                        repeat(48) {
                            val state = runCatching {
                                probe.evaluate("return {ready: document.readyState === 'complete' && document.fonts.status === 'loaded' && document.body.innerText.length > $minLen};")
                            }.getOrElse { org.json.JSONObject() }
                            if (state.optBoolean("ready")) return true
                            Thread.sleep(500)
                        }
                        return false
                    }

                    fun load(url: String, minLen: Int = 40): Boolean {
                        host.onActivity { web.loadUrl(url) }
                        val ok = ready(minLen)
                        Thread.sleep(2200)
                        return ok
                    }

                    fun snapshot(name: String, ready: Boolean) {
                        val json = runCatching { probe.evaluate(SNAPSHOT) }.getOrElse { org.json.JSONObject().put("error", it.message ?: "evaluate failed") }
                        json.put("ready", ready)
                        DeviceEvidence.writeJson("library-$name-$width.json", json.toString(2))
                        DeviceEvidence.captureScene("library-$name-$width")
                    }

                    try {
                        val targets = listOf(
                            "you2" to "https://m.youtube.com/feed/you?hl=en&persist_app=1&app=m",
                            "notifications2" to "https://m.youtube.com/feed/notifications?hl=en",
                            "notifications3" to "https://m.youtube.com/notifications?hl=en",
                        )
                        for ((name, url) in targets) {
                            val ok = load(url)
                            snapshot(name, ok)
                        }
                    } finally {
                        host.onActivity { web.destroy() }
                    }
                }
            }
        } finally {
            DeviceEvidence.shell("wm size $size")
            DeviceEvidence.shell("wm density $density")
        }
    }

    private companion object {
        const val SNAPSHOT = """
            const rect=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,bottom:r.bottom,right:r.right}};
            const fields=['fontFamily','fontSize','fontWeight','lineHeight','color','backgroundColor','padding','margin','borderRadius','border','boxShadow','display','gap','maxWidth','alignItems','justifyContent'];
            const visible=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&r.y<1600&&r.bottom>0&&s.visibility!=='hidden'&&s.display!=='none'&&!['SCRIPT','STYLE','PATH','SVG','DEFS','G','USE'].includes(e.tagName)};
            const selected=[...document.querySelectorAll('body *')].filter(visible).slice(0,900);
            const nodes=selected.map(e=>({tag:e.tagName,id:e.id,cls:typeof e.className==='string'?e.className:'',role:e.getAttribute('role'),text:(e.innerText||'').slice(0,400),aria:e.getAttribute('aria-label'),href:e.getAttribute('href'),rect:rect(e),style:Object.fromEntries(fields.map(k=>[k,getComputedStyle(e)[k]])),svg:e.tagName==='SVG'?e.outerHTML:null}));
            const css=[];const walk=(rules,condition='')=>{for(const rule of rules){if(rule.selectorText){try{if(selected.some(e=>e.matches(rule.selectorText)))css.push({condition,rule:rule.cssText});}catch(e){}}if(rule.cssRules?.length){walk(rule.cssRules,condition+' '+(rule.conditionText||''));}}};
            for(const sheet of [...document.styleSheets,...document.adoptedStyleSheets]){try{walk(sheet.cssRules)}catch(e){}}
            const svgs=[...document.querySelectorAll('svg')].map(s=>({svg:s.outerHTML,aria:s.closest('button,a,[role=button]')?.getAttribute('aria-label'),text:s.closest('button,a,[role=button]')?.innerText?.slice(0,200)}));
            const tabs=[...document.querySelectorAll('a,button,[role=tab]')].map(e=>({text:(e.innerText||'').trim().slice(0,80),aria:e.getAttribute('aria-label'),href:e.getAttribute('href'),rect:rect(e)})).filter(e=>e.text||e.aria);
            return {url:location.href,language:document.documentElement.lang,ua:navigator.userAgent,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,deviceWidth:screen.width,deviceHeight:screen.height},fontStatus:document.fonts.status,body:document.body.innerText.slice(0,12000),nodes,icons:svgs,tabs,css,sheets:[...document.styleSheets].map(s=>s.href)};
        """
    }
}
