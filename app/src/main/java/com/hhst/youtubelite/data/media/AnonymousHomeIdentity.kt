package com.hhst.youtubelite.data.media

import com.liskovsoft.youtubeapi.videoinfo.V2.HttpClient
import com.liskovsoft.youtubeapi.videoinfo.V2.HttpRequest
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.PlayerClient
import com.liskovsoft.youtubeapi.videoinfo.V2.playerQuery

private const val CONSENT_COOKIE = "SOCS=CAISEwgDEgk5NTA1MzE4OTQaAmVuIAEaBgiA7frSBg"

internal fun anonymousHomeContext(context: RequestContext, http: HttpClient, verified: Boolean = false): RequestContext {
    if (context.signedIn || verified) return context
    val cookies = context.visitorCookie.orEmpty().split(';').map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith("SOCS=") }.plus(CONSENT_COOKIE).joinToString("; ")
    val agent = playerQuery(PlayerClient.TV, "context", null).headers.getValue("User-Agent")
    val reply = http.execute(HttpRequest("GET", "https://www.youtube.com/tv?hl=en&gl=US",
        mapOf("Cookie" to cookies, "User-Agent" to agent)))
    if (reply.status !in 200..299) return context
    val visitor = Regex("\"(?:VISITOR_DATA|visitorData)\"\\s*:\\s*\"([^\"]{1,4096})\"")
        .find(reply.body)?.groupValues?.get(1) ?: return context
    val cookie = anonymousVisitorCookie(reply.header("Set-Cookie").orEmpty(), context.visitorCookie) ?: return context
    return context.copy(visitor = visitor, visitorCookie = cookie)
}

internal fun anonymousVisitorCookie(headers: String, stored: String? = null): String? {
    val allowed = setOf("VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "YSC", "__Secure-ROLLOUT_TOKEN", "__Secure-YEC")
    val cookies = stored.orEmpty().split(';').mapNotNull { pair ->
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "").trim()
        if (name in allowed && value.isNotBlank()) name to value else null
    }.toMap().toMutableMap()
    Regex("(?:^|[\\n,])\\s*([A-Za-z0-9_-]+)=([^;\\s,]*)").findAll(headers)
        .map { it.groupValues[1] to it.groupValues[2] }
        .filter { it.first in allowed && it.second.isNotBlank() }.forEach { (name, value) -> cookies[name] = value }
    if (cookies["VISITOR_INFO1_LIVE"].isNullOrBlank()) return null
    return (cookies.entries.map { "${it.key}=${it.value}" } + CONSENT_COOKIE).joinToString("; ").takeIf { it.length <= 4096 }
}
