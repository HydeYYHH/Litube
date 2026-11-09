package com.hhst.youtubelite.player

import com.hhst.youtubelite.player.surface.PlayerCallbackBridge
import com.hhst.youtubelite.player.surface.PlayerPlaybackActions
import com.hhst.youtubelite.player.surface.PlayerWindowActions
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerCallbackBridgeTest {
    @Test fun castLinkIsSharedOnlyAfterTheProxyIsReady() {
        var ready: (() -> Unit)? = null
        var shares = 0
        var completions = 0
        val playback = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PlayerPlaybackActions::class.java)) { _, method, args ->
            if (method.name == "onShareCastLink") {
                @Suppress("UNCHECKED_CAST")
                ready = args[0] as () -> Unit
            }
            null
        } as PlayerPlaybackActions
        val window = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PlayerWindowActions::class.java)) { _, method, _ ->
            if (method.name == "shareCastLink") shares++
            null
        } as PlayerWindowActions
        PlayerCallbackBridge(playback, window).onShareCastLink { completions++ }
        assertEquals(0, shares)
        assertEquals(0, completions)
        requireNotNull(ready).invoke()
        assertEquals(1, shares)
        assertEquals(1, completions)
    }
}
