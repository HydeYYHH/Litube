package com.hhst.youtubelite.browser

import com.hhst.youtubelite.core.Constants
import java.net.URI
import java.util.Locale

/** Host allowlist for in-app navigation and WebView loads. */
object UrlPolicy {

    private val allowedHosts = setOf(
        Constants.YOUTUBE_DOMAIN,
        "youtu.be",
        "youtube.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "googleusercontent.com",
        "apis.google.com",
        "gstatic.com",
    )

    /** True for allowlisted https hosts (tab opens, JS bridge). */
    fun isAllowedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
                uri.port in setOf(-1, 443) && isAllowedHost(uri.host)
        } catch (_: Exception) { false }
    }

    /** True if the WebView may navigate here, including about/file/data. */
    fun canLoad(url: String): Boolean {
        val scheme = schemeOf(url)
        if (scheme == "file" || scheme == "about" || scheme == "data" || scheme == "javascript") {
            return true
        }
        return isAllowedUrl(url)
    }

    fun isLoginUrl(url: String?): Boolean {
        if (!isAllowedUrl(url)) return false
        val uri = URI(url)
        val host = uri.host.lowercase(Locale.ROOT)
        return host in LOGIN_HOSTS || (isYoutubeHost(host) &&
            uri.path.orEmpty().lowercase(Locale.ROOT).let {
                it == "/signin" || it.startsWith("/signin/") || it.startsWith("/accounts/") ||
                    it == "/check_connection" || it == "/set_setting"
            })
    }

    fun shouldInject(url: String?): Boolean = isAllowedUrl(url) && !isLoginUrl(url) &&
        hostOf(url!!)?.lowercase(Locale.ROOT)?.let { isYoutubeHost(it) || it == "youtu.be" } == true

    // Login hosts match exactly: regional Google sign-in domains are
    // enumerated from https://www.google.com/supported_domains because a
    // prefix match would admit lookalikes such as accounts.google.co.zz.
    private val LOGIN_HOSTS = setOf(
        "accounts.google", "accounts.youtube.com", "consent.google.com", "consent.youtube.com",
    ) + (
        "com ad ae com.af com.ag al am co.ao com.ar as at com.au az ba com.bd be bf bg com.bh bi bj " +
        "com.bn com.bo com.br bs bt co.bw by com.bz ca cd cf cg ch ci co.ck cl cm cn com.co co.cr com.cu " +
        "cv com.cy cz de dj dk dm com.do dz com.ec ee com.eg es com.et fi com.fj fm fr ga ge gg com.gh " +
        "com.gi gl gm gr com.gt gy com.hk hn hr ht hu co.id ie co.il im co.in iq is it je com.jm jo " +
        "co.jp co.ke com.kh ki kg co.kr com.kw kz la com.lb li lk co.ls lt lu lv com.ly co.ma md me mg " +
        "mk ml com.mm mn com.mt mu mv mw com.mx com.my co.mz com.na com.ng com.ni ne nl no com.np nr nu " +
        "co.nz com.om com.pa com.pe com.pg com.ph com.pk pl pn com.pr ps pt com.py com.qa ro ru rw " +
        "com.sa com.sb sc se com.sg sh si sk com.sl sn so sm sr st com.sv td tg co.th com.tj tl tm tn to " +
        "com.tr tt com.tw co.tz com.ua co.ug co.uk com.uy co.uz com.vc co.ve co.vi com.vn vu ws rs co.za " +
        "co.zm co.zw cat"
    ).split(' ').map { "accounts.google.$it" }

    private fun isYoutubeHost(host: String) = host == "youtube.com" || host.endsWith(".youtube.com")

    private fun isAllowedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.lowercase(Locale.ROOT)
        if (normalized in LOGIN_HOSTS) return true
        return allowedHosts.any { domain ->
            normalized == domain || normalized.endsWith(".$domain")
        }
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }

    private fun schemeOf(url: String): String? = try {
        URI(url).scheme?.lowercase(Locale.ROOT)
    } catch (_: Exception) {
        null
    }
}
