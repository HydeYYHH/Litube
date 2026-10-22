package com.hhst.youtubelite.account

import com.hhst.youtubelite.core.UrlPolicy
import java.net.URI

internal object SignInNavigation {
    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
            (UrlPolicy.isLoginUrl(url) || uri.host in setOf("www.youtube.com", "m.youtube.com", "youtube.com"))
    }.getOrDefault(false)
}
