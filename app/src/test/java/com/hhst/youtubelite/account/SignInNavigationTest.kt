package com.hhst.youtubelite.account

import com.hhst.youtubelite.core.BrowserUserAgent
import org.junit.Assert.*
import org.junit.Test

class SignInNavigationTest {
    @Test fun authenticationRedirectsStayInsideTheSharedSession() {
        for (url in listOf("https://accounts.google.com/ServiceLogin", "https://accounts.youtube.com/accounts/SetSID",
            "https://accounts.google.co.jp/accounts/SetSID", "https://consent.google.com/m", "https://consent.youtube.com/m",
            "https://m.youtube.com/", "https://www.youtube.com/feed/you")) assertTrue(url, SignInNavigation.allowed(url))
    }

    @Test fun unrelatedOriginsAndDeceptiveAuthoritiesAreRejected() {
        for (url in listOf("https://accounts.google.com.evil.test", "https://accounts.google.co.zz/", "https://sub.accounts.google.com/",
            "http://accounts.google.com/", "https://user@accounts.google.com/", "https://accounts.google.com:8443/",
            "https://m.youtube.com@evil.test/", "https://gstatic.com/resource", "javascript:alert(1)", "file:///page.html"))
            assertFalse(url, SignInNavigation.allowed(url))
    }

    @Test fun userAgentPreservesTheInstalledChromeVersion() {
        val original = "Mozilla/5.0 (Linux; Android 8; device; wv) AppleWebKit/537.36 Version/4.0 Chrome/69.0.3497.100 Mobile Safari/537.36"
        val clean = BrowserUserAgent.from(original)
        assertFalse(clean.contains("; wv"))
        assertFalse(clean.contains("Version/4.0"))
        assertTrue(clean.contains("Chrome/69.0.3497.100 Mobile"))
        assertEquals(clean, BrowserUserAgent.from(clean))
        assertTrue(BrowserUserAgent.from(original.replace(" Mobile", "")).contains(" Mobile Safari/"))
    }
}
