package com.hhst.youtubelite.player.surface

import java.lang.reflect.Proxy

/** JDK-proxy stub of [PlayerSurfaceCallbacks] that records every invoked method name. */
internal fun proxyActions(label: String, onCall: (name: String, args: Array<out Any?>?) -> Unit = { _, _ -> }):
    PlayerSurfaceCallbacks =
    Proxy.newProxyInstance(PlayerSurfaceCallbacks::class.java.classLoader,
        arrayOf(PlayerSurfaceCallbacks::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> label
            else -> { onCall(method.name, args); null }
        }
    } as PlayerSurfaceCallbacks
