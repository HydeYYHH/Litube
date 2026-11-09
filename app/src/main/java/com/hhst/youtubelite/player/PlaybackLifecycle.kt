package com.hhst.youtubelite.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.hhst.youtubelite.extension.ExtensionManager
import com.hhst.youtubelite.extension.PreferenceKeys
import org.koin.java.KoinJavaComponent.getKoin

internal class PlaybackLifecycle : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var started = 0
    private val background = Runnable {
        if (started != 0) return@Runnable
        PlaybackSession.player.value?.let { player ->
            if (!player.audioOnlyBackground) player.setVideoVisible(false)
            if (!getKoin().get<ExtensionManager>().isEnabled(PreferenceKeys.ENABLE_BACKGROUND_PLAY))
                player.pauseForBackground()
        }
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        main.removeCallbacks(background)
        PlaybackSession.player.value?.let { player ->
            if (!player.audioOnlyBackground) player.setVideoVisible(player.uiState.value.visible)
        }
    }
    override fun onActivityResumed(activity: Activity) {
        // A navigation handoff ends when the destination owns the foreground,
        // not when the source activity behind it comes back or dies.
        com.hhst.youtubelite.core.PipAutoEnter.onActivityResumed(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        main.postDelayed(background, 700)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
