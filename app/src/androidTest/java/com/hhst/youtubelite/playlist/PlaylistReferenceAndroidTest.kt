package com.hhst.youtubelite.playlist

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.junit.Assume.assumeTrue
import org.junit.Test

class PlaylistReferenceAndroidTest {
    @Test fun collectPublicSurfaces() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("network") == "1")
        WebView.setDataDirectorySuffix("playlist-reference")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val size = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val density = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            val widths = InstrumentationRegistry.getArguments().getString("widths")?.split(',')?.map { it.toInt() }
                ?: listOf(390, 600, 840, 1280)
            for (width in widths) {
                val height = InstrumentationRegistry.getArguments().getString("height")?.toInt() ?: if (width == 390) 844 else 800
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
                            webViewClient = android.webkit.WebViewClient()
                        }
                        activity.setContentView(web)
                    }
                    val probe = WebProbe { web }
                    fun load(url: String) {
                        host.onActivity { web.loadUrl(url) }
                        val path = android.net.Uri.parse(url).path
                        probe.waitFor("return {ready: location.pathname === '$path' && document.readyState === 'complete' && document.body.innerText.length > 100};")
                        Thread.sleep(1500)
                    }
                    fun capture(name: String) = probe.evaluate(SNAPSHOT).also {
                        DeviceEvidence.writeJson("playlist-$name-$width.json", it.toString(2))
                        DeviceEvidence.captureScene("playlist-$name-$width")
                    }
                    try {
                        if (InstrumentationRegistry.getArguments().getString("detailOnly") == "true") {
                            load("https://m.youtube.com/playlist?list=PL6B3937A5D230E335&hl=en")
                            capture("detail")
                            return@use
                        }
                        load("https://m.youtube.com/results?search_query=blender+open+movie&sp=EgIQAw%253D%253D&hl=en")
                        val search = capture("search")
                        probe.evaluate("const b=document.querySelector('button[aria-label=\"Action menu\"]');if(b)b.click();return {found:!!b};")
                        Thread.sleep(500)
                        capture("search-menu")
                        val links = search.getJSONArray("links")
                        val playlistUrl = (0 until links.length()).map { links.getString(it) }
                            .firstOrNull { it.contains("/playlist?list=") }
                        if (playlistUrl != null) {
                            load("https://m.youtube.com" + playlistUrl.substringAfter("youtube.com") + "&hl=en")
                            capture("detail")
                            probe.evaluate("const b=[...document.querySelectorAll('button')].find(x=>x.getAttribute('aria-label')==='More actions'); if(b)b.click(); return {found:!!b};")
                            Thread.sleep(500)
                            capture("menu")
                            probe.evaluate("const b=document.querySelector('ytw-scrim button,button.close-button');if(b)b.click();return {found:!!b};")
                            Thread.sleep(300)
                            probe.evaluate("const b=document.querySelector('yt-lockup-view-model button[aria-label=\"More actions\"]');if(b)b.click();return {found:!!b};")
                            Thread.sleep(500)
                            capture("video-menu")
                        }
                        load("https://m.youtube.com/@BlenderStudio/playlists?hl=en")
                        capture("channel")
                        probe.evaluate("const b=document.querySelector('ytm-compact-playlist-renderer button');if(b)b.click();return {found:!!b};")
                        Thread.sleep(500)
                        capture("channel-menu")
                    } finally { host.onActivity { web.destroy() } }
                }
            }
        } finally {
            DeviceEvidence.shell("wm size $size")
            DeviceEvidence.shell("wm density $density")
        }
    }

    private companion object {
        const val SNAPSHOT = """
            const rect=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}};
            const fields=['fontFamily','fontSize','fontWeight','lineHeight','color','backgroundColor','backgroundImage','padding','margin','borderRadius','boxShadow','display','gap','maxWidth','aspectRatio','filter','opacity','transform'];
            const selected=[...document.querySelectorAll('body *')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&r.y<1100&&r.bottom>0&&!['SCRIPT','STYLE','PATH','SVG'].includes(e.tagName)}).slice(0,850);
            const nodes=selected.map(e=>({tag:e.tagName,cls:e.className,text:(e.innerText||'').slice(0,800),aria:e.getAttribute('aria-label'),rect:rect(e),style:Object.fromEntries(fields.map(k=>[k,getComputedStyle(e)[k]])),svg:['BUTTON','A'].includes(e.tagName)?[...e.querySelectorAll('svg')].map(s=>s.outerHTML):[]}));
            const css=[];const walk=(rules,condition='')=>{for(const rule of rules){if(rule.selectorText){try{if(selected.some(e=>e.matches(rule.selectorText)))css.push({condition,rule:rule.cssText});}catch(e){}}if(rule.cssRules?.length){walk(rule.cssRules,condition+' '+(rule.conditionText||''));}}};
            for(const sheet of [...document.styleSheets,...document.adoptedStyleSheets]){try{walk(sheet.cssRules)}catch(e){}}
            return {url:location.href,language:document.documentElement.lang,ua:navigator.userAgent,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio},body:document.body.innerText.slice(0,20000),nodes,icons:[...document.querySelectorAll('svg')].map(s=>({svg:s.outerHTML,text:s.closest('yt-list-item-view-model,button,a')?.innerText})),links:[...document.querySelectorAll('a[href*="list="]')].map(a=>a.getAttribute('href')),css,sheets:[...document.styleSheets].map(s=>s.href)};
        """
    }
}
