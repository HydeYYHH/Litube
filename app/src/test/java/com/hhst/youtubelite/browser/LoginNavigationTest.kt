package com.hhst.youtubelite.browser

import org.junit.Assert.*
import org.junit.Test

class LoginNavigationTest {
    @Test fun authenticationAndIntermediateRedirectsStayInTheirSourceTab() {
        val login = LoginNavigation()
        val source = "https://m.youtube.com/watch?v=abc"
        val account = "https://accounts.google.com/ServiceLogin?service=youtube"
        assertFalse(login.keepInTab(source, "https://m.youtube.com/results?q=a"))
        assertTrue(login.keepInTab(source, account))
        login.started(account)
        assertFalse(login.finished(account))
        assertTrue(login.keepInTab(account, "https://accounts.youtube.com/accounts/SetSID"))
        assertFalse(login.finished("https://accounts.google.co.jp/accounts/SetSID"))
        assertFalse(login.finished("https://m.youtube.com/check_connection"))
        assertTrue(login.keepInTab("https://m.youtube.com/check_connection", source))
        assertTrue(login.finished(source))
        assertFalse(login.keepInTab(source, "https://m.youtube.com/results?q=b"))
    }

    @Test fun userAgentRetainsTheInstalledBrowserVersionOnPhonesAndTablets() {
        val phone = "Mozilla/5.0 (Linux; Android 8.0; device; wv) AppleWebKit/537.36 Version/4.0 Chrome/69.0.3497.100 Mobile Safari/537.36"
        val clean = BrowserUserAgent.from(phone)
        assertFalse(clean.contains("; wv"))
        assertFalse(clean.contains("Version/4.0"))
        assertTrue(clean.contains("Chrome/69.0.3497.100 Mobile"))
        assertEquals(clean, BrowserUserAgent.from(clean))
        assertTrue(BrowserUserAgent.from(phone.replace(" Mobile", "")).contains(" Mobile Safari/"))
    }

    @Test fun loginPagesRemainScriptlessAndLookalikesAreRejected() {
        for (url in listOf("https://accounts.google.com/ServiceLogin", "https://accounts.youtube.com/accounts/SetSID",
            "https://accounts.google.co.jp/accounts/SetSID",
            "https://accounts.google.co.uk/accounts/SetSID", "https://accounts.google.com.hk/accounts/SetSID",
            "https://accounts.google.de/accounts/SetSID", "https://accounts.google.co.in/accounts/SetSID",
            "https://consent.google.com/m", "https://consent.youtube.com/m", "https://m.youtube.com/signin")) {
            assertTrue(url, UrlPolicy.canLoad(url)); assertTrue(url, UrlPolicy.isLoginUrl(url))
            assertFalse(url, UrlPolicy.shouldInject(url))
        }
        for (url in listOf("https://accounts.google.com.evil.test", "https://accounts.google.fake", "https://user@accounts.google.com",
            "http://accounts.google.com", "https://accounts.google.com:8443", "https://accounts.google.co.jp.evil.test",
            "https://sub.accounts.google.co.jp", "https://accounts.google.co.uk.evil.test", "https://accounts.google.co.zz",
            "https://accounts.google.co.uk@evil.test", "https://accounts.google.co.uk:8443")) assertFalse(url, UrlPolicy.canLoad(url))
        assertTrue(UrlPolicy.shouldInject("https://m.youtube.com/watch?v=abc"))
        assertFalse(UrlPolicy.shouldInject("https://gstatic.com/resource"))
    }

    @Test fun authNavigationKeepsTheWatchIdentityAndBackReturnsToTheSource() {
        val tabs = TabController()
        tabs.openTab("https://m.youtube.com/watch?v=abc")
        val id = tabs.state().activeId
        tabs.updateUrl(id, "https://accounts.google.com/ServiceLogin")
        assertEquals("watch", tabs.state().active?.kind)
        tabs.updateUrl(id, "https://accounts.youtube.com/accounts/SetSID")
        assertEquals(id, tabs.state().activeId)
        assertEquals("watch", tabs.state().active?.kind)
        tabs.updateUrl(id, "https://m.youtube.com/watch?v=abc")
        assertEquals("watch", tabs.state().active?.kind)
    }
}
