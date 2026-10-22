package com.hhst.youtubelite.shorts

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only sampling of the signed-in mobile shorts page. Gated behind `-e network 1`. */
class ShortsReferenceAndroidTest {
    @Test fun collectShortsSurfaces() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        WebView.setDataDirectorySuffix("library-reference")
        val size = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val density = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        val widths = (args.getString("widths") ?: "390").split(',').map(String::toInt)
        try {
            for (width in widths) {
                val height = args.getString("height")?.toInt() ?: 844
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
                    fun load(url: String): Boolean {
                        host.onActivity { web.loadUrl(url) }
                        repeat(48) {
                            val state = runCatching {
                                probe.evaluate("return {ready: document.readyState === 'complete' && document.fonts.status === 'loaded' && document.body.innerText.length > 40};")
                            }.getOrElse { org.json.JSONObject() }
                            if (state.optBoolean("ready")) return true
                            Thread.sleep(500)
                        }
                        return false
                    }
                    fun snapshot(name: String, ready: Boolean) {
                        val json = runCatching { probe.evaluate(SNAPSHOT) }.getOrElse { org.json.JSONObject().put("error", it.message ?: "evaluate failed") }
                        json.put("ready", ready)
                        DeviceEvidence.writeJson("shorts-$name-$width.json", json.toString(2))
                        DeviceEvidence.captureScene("shorts-$name-$width")
                    }
                    try {
                        val ok = load("https://m.youtube.com/shorts?hl=en")
                        snapshot("feed", ok)
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
            const svgs=[...document.querySelectorAll('svg')].map(s=>({svg:s.outerHTML,aria:s.closest('button,a,[role=button]')?.getAttribute('aria-label'),text:s.closest('button,a,[role=button]')?.innerText?.slice(0,200)}));
            const tabs=[...document.querySelectorAll('a,button,[role=tab]')].map(e=>({text:(e.innerText||'').trim().slice(0,80),aria:e.getAttribute('aria-label'),href:e.getAttribute('href'),rect:rect(e)})).filter(e=>e.text||e.aria);
            return {url:location.href,language:document.documentElement.lang,ua:navigator.userAgent,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,deviceWidth:screen.width,deviceHeight:screen.height},fontStatus:document.fonts.status,body:document.body.innerText.slice(0,12000),nodes,icons:svgs,tabs,css:[],sheets:[]};
        """
    }
}
