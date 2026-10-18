package com.hhst.youtubelite.debug.reference

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import org.json.JSONObject

/** Read-only reference collector. Runs with a separate, anonymous WebView data directory. */
class LayoutReferenceActivity : Activity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false
    private val label get() = intent.getStringExtra("reference")?.replace(Regex("[^a-zA-Z0-9_-]"), "").orEmpty().ifEmpty { "home" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("anonymous-layout-reference")
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView, url: String) {
                    capture("commit")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!captured) {
                        captured = true
                        handler.postDelayed({
                            when (intent.getStringExtra("page")) {
                                "search" -> web.evaluateJavascript("document.querySelector('button[aria-label=\"Search YouTube\"]')?.click()", null)
                                "menu" -> web.evaluateJavascript("document.querySelector('button[aria-label=\"More actions\"]')?.click()", null)
                            }
                            handler.postDelayed({ capture("settled") }, 750)
                        }, 5000)
                    }
                }
            }
        }
        setContentView(web)
        val path = when (intent.getStringExtra("page")) {
            "results", "menu" -> "https://m.youtube.com/results?search_query=nature&hl=en"
            else -> "https://m.youtube.com/?persist_app=1&app=m&hl=en"
        }
        web.loadUrl(path)
    }

    private fun capture(stage: String) {
        if (web.width == 0 || web.height == 0) return
        val directory = File(getExternalFilesDir(null), "layout-reference").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        web.draw(Canvas(bitmap))
        File(directory, "$label-$stage.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        web.evaluateJavascript("""
            JSON.stringify({url:location.href,viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio},
              text:document.body.innerText,
              elements:Array.from(document.querySelectorAll('ytm-mobile-topbar-renderer,ytm-pivot-bar-renderer,ytm-message-renderer,ytm-rich-item-renderer,ytm-chip-cloud-chip-renderer,input,button,h1,h2,h3')).slice(0,50).map(e=>{
                const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {tag:e.tagName,text:e.innerText,label:e.getAttribute('aria-label'),
                  x:r.x,y:r.y,width:r.width,height:r.height,font:s.fontFamily,size:s.fontSize,lineHeight:s.lineHeight,weight:s.fontWeight,
                  padding:s.padding,margin:s.margin,radius:s.borderRadius,color:s.color,background:s.backgroundColor};}),
              landmarks:Array.from(document.querySelectorAll('*')).filter(e=>e.children.length<2 && e.getBoundingClientRect().height>0 && /Try searching|Start watching|Search YouTube/.test(e.textContent)).flatMap(e=>[e,e.parentElement,e.parentElement?.parentElement]).filter(Boolean).map(e=>{
                const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {tag:e.tagName,classes:e.className,x:r.x,y:r.y,width:r.width,height:r.height,padding:s.padding,margin:s.margin,radius:s.borderRadius,shadow:s.boxShadow,size:s.fontSize,lineHeight:s.lineHeight};}),
              icons:Array.from(document.querySelectorAll('svg')).filter(e=>e.getBoundingClientRect().height>0).slice(0,20).map(e=>({svg:e.outerHTML,label:e.closest('[aria-label]')?.getAttribute('aria-label')})),
              inlineStyles:Array.from(document.querySelectorAll('style')).map(e=>e.textContent)})
        """.trimIndent()) { value ->
            val decoded = runCatching { org.json.JSONTokener(value).nextValue() as String }.getOrDefault("{}")
            File(directory, "$label-$stage.json").writeText(JSONObject(decoded).toString(2))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
