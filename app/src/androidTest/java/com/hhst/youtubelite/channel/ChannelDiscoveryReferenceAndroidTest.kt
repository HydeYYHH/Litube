package com.hhst.youtubelite.channel

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.junit.Assume.assumeTrue
import org.junit.Test

class ChannelDiscoveryReferenceAndroidTest {
    @Test fun collectPublicSurfaces() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        WebView.setDataDirectorySuffix("channel-discovery-reference")
        val size = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val density = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        try {
            for (width in (args.getString("widths") ?: "390,1280").split(',').map(String::toInt)) {
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size ${width}x${if (width == 390) 844 else 800}")
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
                    fun capture(name: String) {
                        DeviceEvidence.writeJson("discovery-$name-$width.json", probe.evaluate(SNAPSHOT).toString(2))
                        DeviceEvidence.captureScene("discovery-$name-$width")
                    }
                    fun load(url: String) {
                        host.onActivity { web.loadUrl(url) }
                        probe.waitFor("return {ready: document.readyState === 'complete' && location.hostname.includes('youtube.com') && document.body.innerText.length > 100};")
                        Thread.sleep(1800)
                    }
                    try {
                        load("https://m.youtube.com/results?search_query=blender&sp=EgIQAg%253D%253D&hl=en")
                        capture("channels")
                        load("https://m.youtube.com/@BlenderStudio/about?hl=en")
                        capture("about-route")
                        probe.evaluate("const b=[...document.querySelectorAll('button,a,yt-description-preview-view-model')].find(e=>/more/i.test(e.getAttribute('aria-label')||'')&&e.getBoundingClientRect().y>48); if(b)b.click(); return {found:!!b};")
                        Thread.sleep(800)
                        capture("about")
                        probe.evaluate("const e=document.querySelector('ytm-about-channel-renderer'); if(e){let p=e.parentElement;while(p&&p.scrollHeight<=p.clientHeight)p=p.parentElement;if(p)p.scrollTop=p.scrollHeight;}return {ok:true};")
                        Thread.sleep(200)
                        capture("about-bottom")
                        load("https://m.youtube.com/@BlenderStudio/search?query=blender&hl=en")
                        capture("channel-search")
                        probe.evaluate("const e=document.querySelector('ytm-channel-search-box-renderer input');if(e){e.value='blender';e.dispatchEvent(new Event('input',{bubbles:true}));e.closest('form').requestSubmit();}return {found:!!e};")
                        Thread.sleep(1800)
                        capture("channel-query")
                        load("https://m.youtube.com/@BlenderStudio?hl=en")
                        capture("channel-home")
                        probe.evaluate("const b=[...document.querySelectorAll('button')].find(e=>/search/i.test(e.getAttribute('aria-label')||'')); if(b)b.click();return {found:!!b};")
                        Thread.sleep(500)
                        capture("search-editor")
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
