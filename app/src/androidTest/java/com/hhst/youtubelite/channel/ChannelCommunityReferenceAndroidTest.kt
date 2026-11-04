package com.hhst.youtubelite.channel

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
 * Read-only public sampling for the channel Shorts/Live/Posts tabs and a post detail page.
 * The production build never instantiates this browser; it is gated behind `-e network 1`.
 */
class ChannelCommunityReferenceAndroidTest {
    @Test fun collectPublicSurfaces() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("network") == "1")
        WebView.setDataDirectorySuffix("channel-community-reference")
        val channel = args.getString("channel") ?: "BlenderStudio"
        val liveChannel = args.getString("liveChannel") ?: channel
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

                    fun pathReady(path: String): Boolean {
                        var state = org.json.JSONObject()
                        repeat(40) {
                            state = runCatching {
                                probe.evaluate("return {ready: location.pathname === '$path' && document.readyState === 'complete' && document.fonts.status === 'loaded' && document.body.innerText.length > 100, path: location.pathname};")
                            }.getOrElse { org.json.JSONObject() }
                            if (state.optBoolean("ready")) return true
                            Thread.sleep(500)
                        }
                        return false
                    }

                    fun load(url: String): Boolean {
                        host.onActivity { web.loadUrl(url) }
                        val path = Uri.parse(url).path ?: "/"
                        val ready = pathReady(path)
                        Thread.sleep(1600)
                        return ready
                    }

                    fun snapshot(name: String, extra: String = ""): org.json.JSONObject {
                        val json = probe.evaluate(SNAPSHOT.replace("__EXTRA__", extra))
                        DeviceEvidence.writeJson("community-$name-$width.json", json.toString(2))
                        DeviceEvidence.captureScene("community-$name-$width")
                        return json
                    }

                    fun scrollTo(top: Int) {
                        runCatching {
                            probe.evaluate("const e=document.scrollingElement||document.documentElement; e.scrollTop=$top; window.scrollTo(0,$top); return {top:e.scrollTop,height:e.scrollHeight};")
                        }
                        Thread.sleep(1200)
                    }

                    fun scrollToBottom() = scrollTo(100000)

                    try {
                        load("https://m.youtube.com/@$channel/posts?hl=en")
                        val posts = snapshot("posts")
                        scrollTo(700)
                        val postsScrolled = snapshot("posts-scroll")
                        val cards = postsScrolled.optJSONArray("postCards")
                        fun imagePostHref(preferMultiple: Boolean): String? = cards?.let { array ->
                            (0 until array.length()).map { array.getJSONObject(it) }
                                .filter { it.optInt("imageCount") > 0 }
                                .filter { if (preferMultiple) it.optInt("imageCount") > 1 else true }
                                .map { it.optString("href") }
                                .firstOrNull { it.isNotBlank() }
                        }
                        val singleImage = imagePostHref(false)
                        val multiImage = imagePostHref(true)
                        if (singleImage != null) {
                            val href = singleImage.substringAfter("youtube.com")
                            load("https://m.youtube.com$href" + if (href.contains("?")) "&hl=en" else "?hl=en")
                            snapshot("post-image")
                            scrollTo(500)
                            snapshot("post-image-scroll")
                            runCatching {
                                probe.evaluate("const b=[...document.querySelectorAll('button')].find(e=>/read more|show more/i.test((e.innerText||'')+' '+(e.getAttribute('aria-label')||''))); if(b){b.click();return {found:true,text:(b.innerText||'').trim()};}return {found:false};")
                            }
                            Thread.sleep(800)
                            snapshot("post-image-expanded")
                            scrollTo(900)
                            snapshot("post-image-bottom")
                            runCatching {
                                probe.evaluate("const b=[...document.querySelectorAll('button')].find(e=>/next|previous/i.test(e.getAttribute('aria-label')||'')); if(b){b.click();return {found:true,aria:b.getAttribute('aria-label')};}return {found:false};")
                            }
                            Thread.sleep(800)
                            snapshot("post-image-next")
                            runCatching {
                                probe.evaluate("const b=document.querySelector('button[aria-label=\"More options\"]'); if(b){b.click();return {found:true};}return {found:false};")
                            }
                            Thread.sleep(500)
                            snapshot("post-detail-menu")
                        }
                        if (multiImage != null && multiImage != singleImage) {
                            val href = multiImage.substringAfter("youtube.com")
                            load("https://m.youtube.com$href" + if (href.contains("?")) "&hl=en" else "?hl=en")
                            snapshot("post-multi-image")
                            scrollTo(500)
                            snapshot("post-multi-image-lower")
                        }
                        load("https://m.youtube.com/@$channel/posts?hl=en")
                        scrollTo(900)
                        snapshot("posts-images")
                        scrollTo(1800)
                        snapshot("posts-lower")

                        // The first text/video post remains useful evidence for the card's attached media.
                        val first = posts.optJSONArray("postLinks")?.optString(0)
                        if (first != null && first.contains("/post/")) {
                            val href = first.substringAfter("youtube.com")
                            load("https://m.youtube.com$href" + if (href.contains("?")) "&hl=en" else "?hl=en")
                            snapshot("post-video")
                        }

                        if (args.getString("postsOnly") != "true") {
                            load("https://m.youtube.com/@$channel/shorts?hl=en")
                            snapshot("shorts")
                            scrollTo(700)
                            snapshot("shorts-scroll")

                            load("https://m.youtube.com/@$liveChannel/live?hl=en")
                            snapshot("live")
                            scrollTo(700)
                            snapshot("live-scroll")
                            if (liveChannel != channel) {
                                load("https://m.youtube.com/@$channel/live?hl=en")
                                snapshot("live-primary")
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
            const fields=['fontFamily','fontSize','fontWeight','lineHeight','color','backgroundColor','backgroundImage','padding','margin','borderRadius','border','boxShadow','display','gap','maxWidth','minWidth','aspectRatio','filter','opacity','transform','whiteSpace','overflow','textOverflow','WebkitLineClamp','gridTemplateColumns','alignItems','justifyContent'];
            const visible=e=>{const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&r.y<1600&&r.bottom>0&&s.visibility!=='hidden'&&s.display!=='none'&&!['SCRIPT','STYLE','PATH','SVG','DEFS','G','USE'].includes(e.tagName)};
            const selected=[...document.querySelectorAll('body *')].filter(visible).slice(0,1200);
            const nodes=selected.map(e=>({tag:e.tagName,id:e.id,cls:typeof e.className==='string'?e.className:'',role:e.getAttribute('role'),text:(e.innerText||'').slice(0,1200),aria:e.getAttribute('aria-label'),href:e.getAttribute('href'),rect:rect(e),style:Object.fromEntries(fields.map(k=>[k,getComputedStyle(e)[k]])),svg:e.tagName==='SVG'?e.outerHTML:null}));
            const css=[];const walk=(rules,condition='')=>{for(const rule of rules){if(rule.selectorText){try{if(selected.some(e=>e.matches(rule.selectorText)))css.push({condition,rule:rule.cssText});}catch(e){}}if(rule.cssRules?.length){walk(rule.cssRules,condition+' '+(rule.conditionText||''));}}};
            for(const sheet of [...document.styleSheets,...document.adoptedStyleSheets]){try{walk(sheet.cssRules)}catch(e){}}
            const postLinks=[...document.querySelectorAll('a[href*="/post/"]')].map(a=>a.getAttribute('href'));
            const images=[...document.querySelectorAll('img')].map(i=>({src:i.currentSrc||i.src,alt:i.alt,rect:rect(i),naturalWidth:i.naturalWidth,naturalHeight:i.naturalHeight}));
            const svgs=[...document.querySelectorAll('svg')].map(s=>({svg:s.outerHTML,aria:s.closest('button,a,[role=button]')?.getAttribute('aria-label'),text:s.closest('button,a,[role=button]')?.innerText?.slice(0,200)}));
            const tabs=[...document.querySelectorAll('a,button,[role=tab],ytm-tab-renderer')].map(e=>({text:(e.innerText||'').trim().slice(0,80),aria:e.getAttribute('aria-label'),href:e.getAttribute('href'),role:e.getAttribute('role'),selected:e.getAttribute('aria-selected'),rect:rect(e)})).filter(e=>e.text||e.aria);
            const scrollables=[...document.querySelectorAll('*')].filter(e=>e.scrollHeight>e.clientHeight+20&&getComputedStyle(e).overflowY!=='visible').slice(0,40).map(e=>({tag:e.tagName,cls:e.className,rect:rect(e),scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,scrollTop:e.scrollTop}));
            const postCards=[...document.querySelectorAll('ytm-backstage-post-renderer,ytm-backstage-post-thread-renderer')].map(card=>{
                const href=card.querySelector('a[href*="/post/"]')?.getAttribute('href')||'';
                const contentImages=[...card.querySelectorAll('img,yt-core-image')].map(i=>({src:i.currentSrc||i.src||'',rect:rect(i),tag:i.tagName})).filter(i=>i.rect.width>200&&i.rect.height>200);
                const nextButtons=[...card.querySelectorAll('button')].map(b=>({aria:b.getAttribute('aria-label'),text:(b.innerText||'').trim()})).filter(b=>/next|previous|photo|image/i.test((b.aria||'')+' '+b.text));
                return {href,rect:rect(card),text:(card.innerText||'').slice(0,2500),imageCount:contentImages.length,images:contentImages,nextButtons,readMore:[...card.querySelectorAll('button')].some(b=>/read more/i.test(b.innerText||''))};
            });
            __EXTRA__
            return {url:location.href,language:document.documentElement.lang,ua:navigator.userAgent,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio,deviceWidth:screen.width,deviceHeight:screen.height},fontStatus:document.fonts.status,scroll:{top:(document.scrollingElement||document.documentElement).scrollTop,height:(document.scrollingElement||document.documentElement).scrollHeight},body:document.body.innerText.slice(0,30000),nodes,icons:svgs,svgPaths:svgs.slice(0,80),tabs,postLinks,postCards,images,scrollables,css,sheets:[...document.styleSheets].map(s=>s.href)};
        """
    }
}
