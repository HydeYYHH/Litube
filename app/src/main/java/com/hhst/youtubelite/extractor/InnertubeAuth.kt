package com.hhst.youtubelite.extractor

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Innertube request headers, including multi-SID SAPISID authorization for signed-in sessions. */
object InnertubeAuth {
    fun headers(
        session: YoutubeWebSession,
        client: StreamClient,
        origin: String,
        timestampSeconds: Long,
    ): Map<String, List<String>> {
        val headers = LinkedHashMap<String, List<String>>()
        headers["User-Agent"] = listOf(client.userAgent(session))
        headers["Origin"] = listOf(origin)
        headers["Referer"] = listOf("$origin/")
        headers["Content-Type"] = listOf("application/json")
        headers["X-Youtube-Client-Name"] = listOf(client.clientId.toString())
        headers["X-Youtube-Client-Version"] = listOf(client.version(session))
        if (!session.visitorData.isNullOrEmpty()) {
            headers["X-Goog-Visitor-Id"] = listOf(session.visitorData)
        }
        if (!client.supportsCookies || origin != "https://www.youtube.com") return headers
        val cookie = session.cookies(origin)
        if (cookie.isEmpty()) return headers
        headers["Cookie"] = listOf(cookie)
        if (session.account == WebAccount.ANONYMOUS) return headers
        headers["X-Youtube-Bootstrap-Logged-In"] = listOf("true")
        val values = LinkedHashMap<String, String>()
        for (part in cookie.split(";")) {
            val equal = part.indexOf('=')
            if (equal > 0) values[part.substring(0, equal).trim()] = part.substring(equal + 1)
        }
        val schemes = arrayOf("SAPISIDHASH", "SAPISID1PHASH", "SAPISID3PHASH")
        val sids = arrayOf(
            values["SAPISID"] ?: values["__Secure-3PAPISID"],
            values["__Secure-1PAPISID"],
            values["__Secure-3PAPISID"],
        )
        val authorization = StringBuilder()
        for (i in schemes.indices) {
            val sid = sids[i] ?: continue
            val user = !session.userSessionId.isNullOrEmpty()
            val input = (if (user) session.userSessionId + " " else "") +
                timestampSeconds + " " + sid + " " + origin
            if (authorization.isNotEmpty()) authorization.append(' ')
            authorization.append(schemes[i]).append(' ').append(timestampSeconds)
                .append('_').append(sha1(input))
            if (user) authorization.append("_u")
        }
        if (authorization.isNotEmpty()) {
            headers["Authorization"] = listOf(authorization.toString())
            headers["X-Origin"] = listOf(origin)
            headers["X-Goog-AuthUser"] = listOf(session.accountIndex.toString())
            if (!session.delegatedId.isNullOrEmpty()) {
                headers["X-Goog-PageId"] = listOf(session.delegatedId)
            }
        }
        return headers
    }

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
