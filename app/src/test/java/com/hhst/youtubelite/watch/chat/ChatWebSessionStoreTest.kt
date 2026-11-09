package com.hhst.youtubelite.watch.chat

import com.hhst.youtubelite.data.media.MemoryAuthStore
import com.liskovsoft.youtubeapi.videoinfo.V2.Account
import com.liskovsoft.youtubeapi.videoinfo.V2.RequestContext
import com.liskovsoft.youtubeapi.videoinfo.V2.WebSession
import org.junit.Assert.*
import org.junit.Test

class ChatWebSessionStoreTest {
    private val native = RequestContext(accountId = "123", accessToken = "oauth", generation = 7)
    private val web = native.copy(accessToken = null, webSession = WebSession("SAPISID=test; SID=other", 2), visitor = "web-visitor")

    @Test fun chatUsesCookieAuthWithoutChangingNativeAuthAndSurvivesRestart() {
        val storage = MemoryAuthStore()
        ChatWebSessionStore(storage).save(native, web)
        val restored = requireNotNull(ChatWebSessionStore(storage).context(native))
        assertNull(restored.accessToken)
        assertEquals("SAPISID=test; SID=other", restored.webSession?.cookies)
        assertEquals(2, restored.webSession?.accountIndex)
        assertEquals("web-visitor", restored.visitor)
        assertEquals(7L, restored.generation)
        assertEquals("oauth", native.accessToken)
    }

    @Test fun changingAccountAnonymousModeAndClearingNeverReuseTheSession() {
        val store = ChatWebSessionStore(MemoryAuthStore())
        store.save(native, web)
        assertNull(store.context(native.copy(accountId = "456")))
        assertNull(store.context(native.copy(onBehalfOfUser = "brand")))
        assertNull(store.context(native.copy(accessToken = null)))
        store.clear()
        assertNull(store.context(native))
    }

    @Test fun invalidStoredCredentialsAreIgnored() {
        val storage = MemoryAuthStore()
        storage.write(ChatWebSessionStore.KEY, "malformed")
        assertNull(ChatWebSessionStore(storage).context(native))
    }

    @Test fun namesCannotAuthorizeAnotherAccountAndBrandChannelsMustMatch() {
        val main = Account("123", "Same name", "owner@example.com", null, true, false, null)
        assertTrue(ChatWebSessionStore.sameAccount(main, main.copy(id = "different")))
        assertFalse(ChatWebSessionStore.sameAccount(main, main.copy(id = "different", email = "other@example.com")))
        assertFalse(ChatWebSessionStore.sameAccount(main, main.copy(pageId = "brand")))
        val brand = main.copy(id = "brand", pageId = "brand")
        assertTrue(ChatWebSessionStore.sameAccount(brand, brand.copy(email = null)))
        assertFalse(ChatWebSessionStore.sameAccount(brand, brand.copy(pageId = "other")))
        assertFalse(ChatWebSessionStore.sameAccount(main.copy(id = "Same name", email = null), main.copy(id = "Same name", email = null)))
    }
}
