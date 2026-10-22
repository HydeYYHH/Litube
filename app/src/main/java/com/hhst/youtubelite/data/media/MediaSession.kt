package com.hhst.youtubelite.data.media

import android.webkit.CookieManager
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession

object MediaSession {
    fun snapshot(): RequestContext {
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        val session = WebSession(cookies)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cookies.toByteArray())
        val generation = java.nio.ByteBuffer.wrap(digest).long
        return RequestContext(generation = generation, webSession = session.takeIf { it.signedIn })
    }
    fun signedIn(): Boolean = snapshot().signedIn
}
