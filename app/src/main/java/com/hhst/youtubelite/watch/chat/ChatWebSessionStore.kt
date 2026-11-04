package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.data.media.AuthStore
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import java.net.URLDecoder
import java.net.URLEncoder

class ChatAuthorizationRequiredException : Exception("chat_web_authorization_required")

/** Encrypted by AuthStore in production; never overlays the app's normal TV session. */
class ChatWebSessionStore(private val store: AuthStore) {
    fun context(native: RequestContext): RequestContext? = runCatching {
        if (!native.signedIn) return null
        val fields = store.read(KEY)?.split('\n')?.map { URLDecoder.decode(it, "UTF-8") } ?: return null
        if (fields.size != 6 || fields[0] != native.accountId || fields[1] != native.onBehalfOfUser.orEmpty()) return null
        val session = WebSession(fields[2], fields[3].toInt())
        if (!session.signedIn) return null
        native.copy(accessToken = null, webSession = session,
            onBehalfOfUser = fields[4].ifBlank { null }, visitor = fields[5].ifBlank { null }, visitorCookie = null)
    }.getOrNull()

    fun save(native: RequestContext, web: RequestContext) {
        require(native.signedIn && native.accountId != null && web.webSession?.signedIn == true)
        val session = requireNotNull(web.webSession)
        store.write(KEY, listOf(native.accountId.orEmpty(), native.onBehalfOfUser.orEmpty(), session.cookies,
            session.accountIndex.toString(), web.onBehalfOfUser.orEmpty(), web.visitor.orEmpty())
            .joinToString("\n") { URLEncoder.encode(it, "UTF-8") })
    }

    fun clear() = store.write(KEY, null)

    companion object {
        const val KEY = "chat:web_session"

        fun sameAccount(expected: Account, actual: Account): Boolean {
            if (expected.disabled || actual.disabled) return false
            if (expected.pageId != null || actual.pageId != null) return expected.pageId != null && expected.pageId == actual.pageId
            if (expected.id != expected.name && expected.id == actual.id) return true
            val email = expected.email?.takeIf { it.contains('@') && !it.startsWith('@') }
            return email != null && email.equals(actual.email, ignoreCase = true)
        }
    }
}
