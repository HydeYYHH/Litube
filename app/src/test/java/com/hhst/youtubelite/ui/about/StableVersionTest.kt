package com.hhst.youtubelite.ui.about
import org.junit.Assert.*
import org.junit.Test
class StableVersionTest {
    @Test fun comparesComponentsAndSeparatesDevelopmentChannel() {
        assertTrue(StableVersion.isDevelopment("3.0.0-devx-debug"))
        assertFalse(StableVersion.isDevelopment("2.1.4"))
        assertTrue(StableVersion.isNewer("2.9.9", "v2.10.0"))
        assertFalse(StableVersion.isNewer("3.0.0-devx", "v2.1.4"))
        assertFalse(StableVersion.isNewer("2.1.4", "v2.1.4"))
    }
}
