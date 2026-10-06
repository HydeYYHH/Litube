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
        "accounts.google.com",
        "accounts.google",
        "consent.google.com",
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

    private val LOGIN_HOSTS = setOf("accounts.google", "accounts.google.com",
        "accounts.youtube.com", "consent.google.com", "consent.youtube.com")

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
