package com.hhst.youtubelite.browser

/** Keeps authentication redirects in the WebView that owns their cookies and history. */
internal class LoginNavigation {
    private var inProgress = false

    fun keepInTab(current: String?, next: String): Boolean =
        inProgress || UrlPolicy.isLoginUrl(current) || UrlPolicy.isLoginUrl(next)

    fun started(url: String): Boolean {
        if (inProgress || !UrlPolicy.isLoginUrl(url)) return false
        inProgress = true
        return true
    }

    fun finished(url: String): Boolean {
        if (!inProgress || !UrlPolicy.shouldInject(url)) return false
        inProgress = false
        return true
    }
}

internal object BrowserUserAgent {
    fun from(default: String): String = default.replace("; wv", "")
        .replace(Regex("\\s+Version/[0-9.]+"), "")
        .let { if (it.contains("Chrome/") && !it.contains("Mobile"))
            it.replace(" Safari/", " Mobile Safari/") else it }
}
