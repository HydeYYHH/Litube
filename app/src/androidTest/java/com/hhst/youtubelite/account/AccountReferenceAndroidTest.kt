package com.hhst.youtubelite.account

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.browser.WebProbe
import com.hhst.youtubelite.core.DeviceEvidence
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only sampling of the account menu and its settings entry. */
class AccountReferenceAndroidTest {
    @Test fun collectAccountSurfaces() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        WebView.setDataDirectorySuffix("library-reference")
        val size = DeviceEvidence.shell("wm size").substringAfterLast(":").trim()
        val density = DeviceEvidence.shell("wm density").substringAfterLast(":").trim()
        val widths = (args.getString("widths") ?: "390").split(',').map(String::toInt)
        try {
            for (width in widths) {
                DeviceEvidence.shell("wm density 160")
                DeviceEvidence.shell("wm size ${width}x844")
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

                    fun settled(href: String): Boolean = runCatching {
                        probe.evaluate("""
                            return {ok: location.href.indexOf('$href') === 0 &&
                                document.readyState === 'complete' && document.fonts.status === 'loaded' &&
                                document.body.innerText.length > 60};
                        """).optBoolean("ok")
                    }.getOrDefault(false)

                    fun await(href: String): Boolean {
                        repeat(48) { if (settled(href)) return true; Thread.sleep(500) }
                        return false
                    }

                    fun load(url: String, href: String): Boolean {
                        host.onActivity { web.loadUrl(url) }
                        return await(href)
                    }

                    fun snapshot(name: String, extra: org.json.JSONObject? = null) {
                        val json = runCatching { probe.evaluate(SNAPSHOT) }
                            .getOrElse { org.json.JSONObject().put("error", it.message ?: "evaluate failed") }
                        extra?.let { for (key in it.keys()) json.put(key, it.get(key)) }
                        DeviceEvidence.writeJson("$name-$width.json", json.toString(2))
                        DeviceEvidence.captureScene("$name-$width")
                    }

                    try {
                        val home = load("https://m.youtube.com/?hl=en", "https://m.youtube.com/")
                        snapshot("account-home", org.json.JSONObject().put("ready", home))
                        if (home) {
                            val opened = probe.evaluate(OPEN_MENU)
                            Thread.sleep(1000)
                            snapshot("account-menu", opened)
                            if (opened.optBoolean("found")) {
                                val settings = probe.evaluate(OPEN_SETTINGS)
                                if (settings.optBoolean("found")) {
                                    await("https://m.youtube.com/") // any first-party page
                                    Thread.sleep(1200)
                                    snapshot("account-settings", settings)
                                }
                            }
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
            const selected=[...document.querySelectorAll('body *')].filter(visible).slice(0,1000);
            const nodes=selected.map(e=>({tag:e.tagName,id:e.id,cls:typeof e.className==='string'?e.className:'',role:e.getAttribute('role'),text:(e.innerText||'').slice(0,400),aria:e.getAttribute('aria-label'),href:e.getAttribute('href'),rect:rect(e),style:Object.fromEntries(fields.map(k=>[k,getComputedStyle(e)[k]])),svg:e.tagName==='SVG'?e.outerHTML:null}));
            const svgs=[...document.querySelectorAll('svg')].map(s=>({svg:s.outerHTML,aria:s.closest('button,a,[role=button]')?.getAttribute('aria-label'),text:s.closest('button,a,[role=button]')?.innerText?.slice(0,200)}));
            const tabs=[...document.querySelectorAll('a,button,[role=tab],[role=menuitem]')].map(e=>({text:(e.innerText||'').trim().slice(0,80),aria:e.getAttribute('aria-label'),href:e.getAttribute('href'),rect:rect(e)})).filter(e=>e.text||e.aria);
            return {url:location.href,language:document.documentElement.lang,ua:navigator.userAgent,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,deviceWidth:screen.width,deviceHeight:screen.height},fontStatus:document.fonts.status,body:document.body.innerText.slice(0,12000),nodes,icons:svgs,tabs};
        """
        const val OPEN_MENU = """
            const visible=e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0};
            const buttons=[...document.querySelectorAll('button,a,[role=button]')].filter(visible);
            const list=buttons.map(e=>({text:(e.innerText||'').trim().slice(0,80),aria:e.getAttribute('aria-label'),href:e.getAttribute('href')}));
            const byAria=a=>buttons.find(e=>(e.getAttribute('aria-label')||'').trim().toLowerCase()===a);
            const target=byAria('account')||byAria('sign in')||byAria('avatar')||byAria('more options');
            if(target){target.click();}
            return {found:!!target, target:target?target.getAttribute('aria-label'):null, buttons:list};
        """
        const val OPEN_SETTINGS = """
            const visible=e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0};
            const items=[...document.querySelectorAll('a,button,[role=menuitem],[role=button]')].filter(visible);
            const list=items.map(e=>({text:(e.innerText||'').trim().slice(0,80),aria:e.getAttribute('aria-label'),href:e.getAttribute('href')}));
            const target=items.find(e=>{const t=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')).trim().toLowerCase();return t==='settings'||t.startsWith('settings ');});
            if(target){target.click();}
            return {found:!!target, items:list};
        """
    }
}
